package com.github.klboke.kkrepo.server.staging;

import com.github.klboke.kkrepo.auth.PermissionSubject;
import com.github.klboke.kkrepo.core.BlobReference;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.BlobReferenceDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.PersistenceHashes;
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityAuditDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.StagingDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.ComponentRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.StagingItemRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.StagingPromotionRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.StagingRepositoryRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.StagingSessionRecord;
import com.github.klboke.kkrepo.protocol.maven.policy.WritePolicy;
import com.github.klboke.kkrepo.server.blob.BlobReferenceCodec;
import com.github.klboke.kkrepo.server.maven.BlobStorageRegistry;
import com.github.klboke.kkrepo.server.maven.MavenExceptions;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntimeRegistry;
import com.github.klboke.kkrepo.server.raw.RawHostedService;
import com.github.klboke.kkrepo.server.security.SecurityManagementService;
import jakarta.servlet.http.HttpServletRequest;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * Multi-repository staging sessions.
 *
 * <p>Staged content never enters the live asset tables. Blobs are content-addressed and retained
 * through {@code blob_reference} with owner type {@value #BLOB_OWNER_TYPE}; promotion links those
 * blobs into the live repository in one database transaction. Session state, revisions, items and
 * promotion reports live only in the shared database, so every replica observes the same session
 * and promotion progress.
 *
 * <p>This first delivery supports {@link RepositoryFormat#RAW} hosted repositories. The session,
 * item, manifest and promotion model is format-neutral so later protocol modules can stage their
 * own generated metadata without changing the lifecycle.
 */
@Service
public class StagingService {
  public static final String HEADER = "X-KkRepo-Staging";
  public static final String TOKEN_HEADER = "X-KkRepo-Staging-Token";
  public static final String BLOB_OWNER_TYPE = "staging_item";
  private static final Pattern SESSION_NAME =
      Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_.-]{0,199}$");
  private static final Set<String> ACTIVE_FOR_READ = Set.of(
      StagingDao.SESSION_OPEN,
      StagingDao.SESSION_FROZEN,
      StagingDao.SESSION_PREFLIGHTED,
      StagingDao.SESSION_PROMOTING,
      StagingDao.SESSION_PROMOTED);
  private static final Set<String> WRITABLE = Set.of(StagingDao.SESSION_OPEN);

  private final StagingDao staging;
  private final RepositoryDao repositories;
  private final AssetDao assets;
  private final BlobReferenceDao blobReferences;
  private final RepositoryRuntimeRegistry runtimes;
  private final RawHostedService rawHosted;
  private final BlobStorageRegistry blobStorageRegistry;
  private final SecurityAuditDao audit;
  private final SecurityManagementService security;
  private final TransactionTemplate transaction;
  private final Map<RepositoryFormat, StagingContentAdapter> contentAdapters;
  private final int maxItems;
  private StagingCredentialService credentials;

  public StagingService(
      StagingDao staging,
      RepositoryDao repositories,
      AssetDao assets,
      BlobReferenceDao blobReferences,
      RepositoryRuntimeRegistry runtimes,
      RawHostedService rawHosted,
      BlobStorageRegistry blobStorageRegistry,
      SecurityAuditDao audit,
      SecurityManagementService security,
      List<StagingContentAdapter> contentAdapters,
      org.springframework.transaction.PlatformTransactionManager transactionManager,
      @Value("${kkrepo.staging.max-items:1000}") int maxItems) {
    this.staging = staging;
    this.repositories = repositories;
    this.assets = assets;
    this.blobReferences = blobReferences;
    this.runtimes = runtimes;
    this.rawHosted = rawHosted;
    this.blobStorageRegistry = blobStorageRegistry;
    this.audit = audit;
    this.security = security;
    this.transaction = new TransactionTemplate(transactionManager);
    this.contentAdapters = (contentAdapters == null ? List.<StagingContentAdapter>of() : contentAdapters)
        .stream()
        .filter(java.util.Objects::nonNull)
        .collect(java.util.stream.Collectors.toUnmodifiableMap(
            adapter -> formatOf(adapter), adapter -> adapter, (left, right) -> left));
    this.maxItems = Math.max(1, maxItems);
  }

  @org.springframework.beans.factory.annotation.Autowired(required = false)
  void setStagingCredentialService(StagingCredentialService credentials) {
    this.credentials = credentials;
  }

  // ---------------------------------------------------------------------------------------------
  // Session lifecycle
  // ---------------------------------------------------------------------------------------------

  @Transactional
  public StagingSessionView create(
      CreateSessionRequest request, PermissionSubject actor, String remoteAddr) {
    return createInternal(request, actor, remoteAddr).session();
  }

  @Transactional
  public CreateResult createWithCredential(
      CreateSessionRequest request, PermissionSubject actor, String remoteAddr) {
    return createInternal(request, actor, remoteAddr);
  }

  private CreateResult createInternal(
      CreateSessionRequest request, PermissionSubject actor, String remoteAddr) {
    String name = request == null ? null : request.name();
    if (name == null || !SESSION_NAME.matcher(name.trim()).matches()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid staging session name");
    }
    name = name.trim();
    if (staging.findSessionByName(name).isPresent()) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Staging session name already exists");
    }
    List<String> repositoryNames = request.repositories() == null
        ? List.of()
        : request.repositories().stream()
            .filter(value -> value != null && !value.isBlank())
            .map(String::trim)
            .distinct()
            .toList();
    List<RepositoryRecord> targets = resolveStagingRepositories(repositoryNames);
    Instant now = Instant.now();
    Instant expiresAt = request.expiresAt() == null
        ? now.plus(request.retentionDays() == null ? 7 : Math.max(1, request.retentionDays()),
            ChronoUnit.DAYS)
        : request.expiresAt();
    if (!expiresAt.isAfter(now)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Staging expiry must be in the future");
    }
    long id = staging.insertSession(new StagingSessionRecord(
        null,
        name,
        StagingDao.SESSION_OPEN,
        0,
        actorSource(actor),
        actorUserId(actor),
        now,
        now,
        null,
        null,
        expiresAt,
        null,
        request.attributes() == null ? Map.of() : request.attributes()));
    for (RepositoryRecord target : targets) {
      staging.addRepository(id, target.id(), target.format().id());
    }
    StagingSessionRecord session = staging.findSessionById(id).orElseThrow();
    StagingCredentialService.IssuedCredential issued = null;
    if (credentials != null) {
      issued = credentials.issue(session, actor);
      if (!staging.updateSessionMetadata(
          session.id(), session.revision(), session.expiresAt(),
          credentials.withToken(session, issued))) {
        throw new ResponseStatusException(
            HttpStatus.CONFLICT, "Staging session changed concurrently; retry");
      }
      session = staging.findSessionById(session.id()).orElseThrow();
    }
    audit(actor, remoteAddr, "staging-create", session,
        "created", Map.of("repositories", repositoryNames));
    return new CreateResult(view(session), issued == null ? null : issued.token());
  }

  @Transactional
  public StagingCredentialService.IssuedCredential rotateCredential(
      long sessionId, PermissionSubject actor, String remoteAddr) {
    StagingSessionRecord session = lockOwnedSession(sessionId, actor);
    requireState(session, Set.of(StagingDao.SESSION_OPEN),
        "Staging credentials can only be rotated while the session is OPEN");
    requireNotExpired(session);
    if (credentials == null) {
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE, "Staging credentials are unavailable");
    }
    credentials.revoke(session);
    StagingCredentialService.IssuedCredential issued = credentials.issue(session, actor);
    if (!staging.updateSessionMetadata(
        session.id(), session.revision(), session.expiresAt(),
        credentials.withToken(session, issued))) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Staging session changed concurrently; retry");
    }
    audit(actor, remoteAddr, "staging-credential", session, "rotated", Map.of());
    return issued;
  }

  @Transactional
  public StagingReport commit(
      long sessionId, PermissionSubject actor, String remoteAddr) {
    preflight(sessionId, actor, remoteAddr);
    return promote(sessionId, actor, remoteAddr);
  }

  @Transactional
  public StagingSessionView rollback(
      long sessionId, PermissionSubject actor, String remoteAddr) {
    return cancel(sessionId, actor, remoteAddr);
  }

  @Transactional
  public StagingSessionView addRepository(
      long sessionId, String repositoryName, PermissionSubject actor, String remoteAddr) {
    StagingSessionRecord session = lockOwnedSession(sessionId, actor);
    requireState(session, Set.of(StagingDao.SESSION_OPEN),
        "Repositories can only be added while a staging session is OPEN");
    RepositoryRecord repository = repositories.findByName(repositoryName)
        .orElseThrow(() -> new ResponseStatusException(
            HttpStatus.NOT_FOUND, "Repository not found: " + repositoryName));
    requireStagingRepository(repository);
    staging.addRepository(session.id(), repository.id(), repository.format().id());
    audit(actor, remoteAddr, "staging-repository-add", session,
        "added", Map.of("repository", repository.name()));
    return view(staging.findSessionById(session.id()).orElseThrow());
  }

  @Transactional
  public StagingSessionView removeRepository(
      long sessionId, long repositoryId, PermissionSubject actor, String remoteAddr) {
    StagingSessionRecord session = lockOwnedSession(sessionId, actor);
    requireState(session, Set.of(StagingDao.SESSION_OPEN),
        "Repositories can only be removed while a staging session is OPEN");
    boolean hasItems = staging.listItems(session.id()).stream()
        .anyMatch(item -> item.repositoryId() == repositoryId);
    if (hasItems) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Remove staged items before removing their repository");
    }
    if (!staging.removeRepository(session.id(), repositoryId)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Repository is not part of the session");
    }
    audit(actor, remoteAddr, "staging-repository-remove", session,
        "removed", Map.of("repositoryId", repositoryId));
    return view(staging.findSessionById(session.id()).orElseThrow());
  }

  @Transactional
  public StagingSessionView update(
      long sessionId, UpdateSessionRequest request, PermissionSubject actor, String remoteAddr) {
    StagingSessionRecord session = lockOwnedSession(sessionId, actor);
    requireState(session, Set.of(StagingDao.SESSION_OPEN, StagingDao.SESSION_FROZEN),
        "Only OPEN or FROZEN staging sessions can be updated");
    Instant expiresAt = request == null || request.expiresAt() == null
        ? session.expiresAt()
        : request.expiresAt();
    Map<String, Object> attributes = request == null || request.attributes() == null
        ? session.attributes()
        : request.attributes();
    if (expiresAt != null && !expiresAt.isAfter(Instant.now())) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Staging expiry must be in the future");
    }
    if (!staging.updateSessionMetadata(
        session.id(), session.revision(), expiresAt, attributes)) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Staging session changed concurrently; retry");
    }
    audit(actor, remoteAddr, "staging-update", session, "updated", Map.of());
    return view(staging.findSessionById(session.id()).orElseThrow());
  }

  @Transactional
  public StagingSessionView close(
      long sessionId, PermissionSubject actor, String remoteAddr) {
    StagingSessionRecord session = lockOwnedSession(sessionId, actor);
    requireState(session, Set.of(StagingDao.SESSION_OPEN),
        "Only an OPEN staging session can be closed");
    if (!staging.transitionSession(
        session.id(), session.revision(), Set.of(StagingDao.SESSION_OPEN),
        StagingDao.SESSION_FROZEN, Instant.now(), null, null)) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Staging session changed concurrently; retry");
    }
    if (credentials != null) credentials.revoke(session);
    audit(actor, remoteAddr, "staging-close", session, "frozen", Map.of());
    return view(staging.findSessionById(session.id()).orElseThrow());
  }

  @Transactional
  public StagingSessionView cancel(
      long sessionId, PermissionSubject actor, String remoteAddr) {
    StagingSessionRecord session = lockOwnedSession(sessionId, actor);
    if (session.state().equals(StagingDao.SESSION_PROMOTED)
        || session.state().equals(StagingDao.SESSION_PROMOTING)
        || session.state().equals(StagingDao.SESSION_CANCELLED)) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Staging session cannot be cancelled from " + session.state());
    }
    for (StagingItemRecord item : staging.listItems(session.id())) {
      blobReferences.releaseOwner(BLOB_OWNER_TYPE, item.id());
    }
    staging.deleteItems(session.id());
    if (!staging.transitionSession(
        session.id(), session.revision(),
        Set.of(StagingDao.SESSION_OPEN, StagingDao.SESSION_FROZEN,
            StagingDao.SESSION_PREFLIGHTED, StagingDao.SESSION_FAILED),
        StagingDao.SESSION_CANCELLED, null, Instant.now(), null)) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Staging session changed concurrently; retry");
    }
    if (credentials != null) credentials.revoke(session);
    audit(actor, remoteAddr, "staging-cancel", session, "cancelled", Map.of());
    return view(staging.findSessionById(session.id()).orElseThrow());
  }

  @Transactional(readOnly = true)
  public StagingSessionView get(long sessionId, PermissionSubject actor) {
    StagingSessionRecord session = findSession(sessionId);
    requireOwnerOrAdmin(session, actor);
    return view(session);
  }

  @Transactional(readOnly = true)
  public List<StagingSessionView> list(String state, int limit, PermissionSubject actor) {
    boolean admin = isAdmin(actor);
    return staging.listSessions(state, limit).stream()
        .filter(session -> admin || owns(session, actor))
        .map(this::view)
        .toList();
  }

  @Transactional(readOnly = true)
  public List<StagingItemView> items(long sessionId, PermissionSubject actor) {
    StagingSessionRecord session = findSession(sessionId);
    requireOwnerOrAdmin(session, actor);
    return staging.listItems(session.id()).stream().map(StagingItemView::of).toList();
  }

  @Transactional(readOnly = true)
  public StagingSessionView getByKey(String sessionKey, PermissionSubject actor) {
    StagingSessionRecord session = findSessionByKey(sessionKey, false);
    requireOwnerOrAdmin(session, actor);
    return view(session);
  }

  @Transactional(readOnly = true)
  public List<StagingItemView> itemsByKey(String sessionKey, PermissionSubject actor) {
    StagingSessionRecord session = findSessionByKey(sessionKey, false);
    requireOwnerOrAdmin(session, actor);
    requireNotExpired(session);
    return staging.listItems(session.id()).stream().map(StagingItemView::of).toList();
  }

  @Transactional(readOnly = true)
  public Optional<StagingItemView> itemByKey(
      String sessionKey, long itemId, PermissionSubject actor) {
    StagingSessionRecord session = findSessionByKey(sessionKey, false);
    requireOwnerOrAdmin(session, actor);
    requireNotExpired(session);
    return staging.listItems(session.id()).stream()
        .filter(item -> item.id() == itemId)
        .findFirst()
        .map(StagingItemView::of);
  }

  @Transactional
  public int deleteItemByKey(
      String sessionKey, long itemId, PermissionSubject actor, String remoteAddr) {
    StagingSessionRecord session = findSessionByKey(sessionKey, true);
    requireOwnerOrAdmin(session, actor);
    requireState(session, WRITABLE, "Staging session is not OPEN");
    requireNotExpired(session);
    StagingItemRecord item = staging.lockItem(itemId)
        .filter(candidate -> candidate.sessionId() == session.id())
        .orElseThrow(() -> new ResponseStatusException(
            HttpStatus.NOT_FOUND, "Staged item not found"));
    releaseItemBlob(item);
    staging.deleteItem(item.id());
    audit(actor, remoteAddr, "staging-delete", session,
        "unstaged", Map.of(
            "repositoryId", item.repositoryId(),
            "path", item.logicalPath()));
    return 1;
  }

  @Transactional(readOnly = true)
  public StagingReport report(long sessionId, PermissionSubject actor) {
    StagingSessionRecord session = findSession(sessionId);
    requireOwnerOrAdmin(session, actor);
    StagingPromotionRecord promotion = session.promotionId() == null
        ? staging.findLatestPromotion(session.id()).orElse(null)
        : staging.findPromotion(session.promotionId()).orElse(null);
    if (promotion == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No promotion report for this session");
    }
    return StagingReport.fromRecord(promotion);
  }

  // ---------------------------------------------------------------------------------------------
  // Preflight and promotion
  // ---------------------------------------------------------------------------------------------

  @Transactional
  public StagingReport preflight(
      long sessionId, PermissionSubject actor, String remoteAddr) {
    StagingSessionRecord session = lockOwnedSession(sessionId, actor);
    requireNotExpired(session);
    if (session.state().equals(StagingDao.SESSION_PROMOTED)
        || session.state().equals(StagingDao.SESSION_PROMOTING)
        || session.state().equals(StagingDao.SESSION_CANCELLED)
        || session.state().equals(StagingDao.SESSION_FAILED)) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Preflight is not valid from " + session.state());
    }
    if (session.state().equals(StagingDao.SESSION_OPEN)) {
      if (!staging.transitionSession(
          session.id(), session.revision(), Set.of(StagingDao.SESSION_OPEN),
          StagingDao.SESSION_FROZEN, Instant.now(), null, session.promotionId())) {
        throw new ResponseStatusException(
            HttpStatus.CONFLICT, "Staging session changed concurrently; retry");
      }
      session = staging.findSessionById(session.id()).orElseThrow();
    }
    return runPreflight(session, actor, remoteAddr);
  }

  public StagingReport promote(
      long sessionId, PermissionSubject actor, String remoteAddr) {
    java.util.concurrent.atomic.AtomicReference<PromotionAttempt> attempt =
        new java.util.concurrent.atomic.AtomicReference<>();
    try {
      return transaction.execute(status -> {
        PromotionAttempt started = beginPromotion(sessionId, actor, remoteAddr);
        attempt.set(started);
        StagingReport report = applyPromotion(started, actor, remoteAddr);
        if (credentials != null) {
          staging.findSessionById(sessionId).ifPresent(credentials::revoke);
        }
        return report;
      });
    } catch (RuntimeException error) {
      recordPromotionFailure(attempt.get(), error, actor, remoteAddr);
      throw error;
    }
  }

  private PromotionAttempt beginPromotion(
      long sessionId, PermissionSubject actor, String remoteAddr) {
    StagingSessionRecord session = lockOwnedSession(sessionId, actor);
    requireNotExpired(session);
    if (!session.state().equals(StagingDao.SESSION_PREFLIGHTED)) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "Preflight a frozen staging session before promoting; current state is "
              + session.state());
    }
    List<StagingItemRecord> items = staging.listItems(session.id());
    if (items.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Staging session has no items");
    }
    StagingPromotionRecord promotion = currentPromotion(session);
    String manifest = manifestHash(items);
    if (promotion.manifestSha256() != null
        && !promotion.manifestSha256().equals(manifest)) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Staging manifest changed after preflight; run preflight again");
    }
    List<ItemValidation> validations = validate(session, items);
    List<ItemValidation> conflicts = validations.stream()
        .filter(validation -> validation.status().equals(StagingDao.ITEM_CONFLICT))
        .toList();
    if (!conflicts.isEmpty()) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Preflight conflicts: " + conflictSummary(conflicts));
    }
    Instant now = Instant.now();
    if (!staging.transitionSession(
        session.id(), session.revision(), Set.of(StagingDao.SESSION_PREFLIGHTED),
        StagingDao.SESSION_PROMOTING, null, null, promotion.id())) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Staging session changed concurrently; retry");
    }
    staging.updatePromotion(
        promotion.id(), StagingDao.PROMOTION_RUNNING, manifest, promotion.report(), now, null);
    audit(actor, remoteAddr, "staging-promote-start", session,
        "running", Map.of("promotionId", promotion.id(), "manifestSha256", manifest));
    return new PromotionAttempt(session, items, promotion.id(), manifest, now);
  }

  private StagingReport applyPromotion(
      PromotionAttempt attempt, PermissionSubject actor, String remoteAddr) {
    StagingSessionRecord session = staging.lockSession(attempt.session().id())
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Staging session not found"));
    if (!session.state().equals(StagingDao.SESSION_PROMOTING)) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Staging session is not currently promoting");
    }
    List<StagingItemRecord> items = staging.listItems(session.id());
    if (!manifestHash(items).equals(attempt.manifestSha256())) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Staging manifest changed during promotion");
    }
    List<Long> repositoryIds = items.stream().map(StagingItemRecord::repositoryId).distinct().toList();
    List<Long> locked = staging.lockRepositories(repositoryIds);
    if (!new LinkedHashSet<>(locked).containsAll(repositoryIds)) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "A staging target repository no longer exists");
    }
    Map<Long, RepositoryRuntime> runtimeById = new LinkedHashMap<>();
    for (Long repositoryId : repositoryIds) {
      RepositoryRecord record = repositories.findById(repositoryId)
          .orElseThrow(() -> new ResponseStatusException(
              HttpStatus.CONFLICT, "Repository " + repositoryId + " no longer exists"));
      requireStagingRepository(record);
      runtimeById.put(repositoryId, runtimes.resolve(record).orElseThrow(() -> new ResponseStatusException(
          HttpStatus.CONFLICT, "Repository " + record.name() + " is not available")));
    }
    List<ItemValidation> validations = validate(session, items);
    List<ItemValidation> conflicts = validations.stream()
        .filter(validation -> validation.status().equals(StagingDao.ITEM_CONFLICT))
        .toList();
    if (!conflicts.isEmpty()) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Preflight conflicts: " + conflictSummary(conflicts));
    }

    List<Map<String, Object>> results = new ArrayList<>();
    for (StagingItemRecord item : items.stream()
        .sorted(Comparator.comparingLong(StagingItemRecord::repositoryId)
            .thenComparing(StagingItemRecord::logicalPath))
        .toList()) {
      RepositoryRuntime runtime = runtimeById.get(item.repositoryId());
      String result = applyItem(runtime, item, actor);
      blobReferences.releaseOwner(BLOB_OWNER_TYPE, item.id());
      staging.updateItemStatus(item.id(), StagingDao.ITEM_PROMOTED);
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("repository", runtime.name());
      row.put("path", item.logicalPath());
      row.put("action", item.action());
      row.put("result", result);
      row.put("sha256", item.sha256());
      results.add(row);
    }
    Instant finishedAt = Instant.now();
    Map<String, Object> report = reportBody(
        session.id(), true, attempt.manifestSha256(), results, null);
    staging.updatePromotion(
        attempt.promotionId(), StagingDao.PROMOTION_SUCCEEDED, attempt.manifestSha256(),
        report, null, finishedAt);
    if (!staging.transitionSession(
        session.id(), session.revision(), Set.of(StagingDao.SESSION_PROMOTING),
        StagingDao.SESSION_PROMOTED, null, finishedAt, attempt.promotionId())) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Staging session changed during promotion");
    }
    audit(actor, remoteAddr, "staging-promote", session,
        "promoted", Map.of("promotionId", attempt.promotionId(), "items", items.size()));
    return new StagingReport(
        true, attempt.manifestSha256(), StagingDao.PROMOTION_SUCCEEDED, List.copyOf(results), null);
  }

  private String applyItem(
      RepositoryRuntime runtime, StagingItemRecord item, PermissionSubject actor) {
    String createdBy = actorUserId(actor);
    StagingContentAdapter adapter = adapterFor(item.format());
    if (item.action().equals(StagingDao.ITEM_DELETE)) {
      boolean deleted = adapter != null
          ? adapter.delete(runtime, item.logicalPath())
          : rawHosted.deleteInternal(runtime, item.logicalPath()).status() != 404;
      return deleted ? "deleted" : "already-absent";
    }
    AssetBlobRecord blob = assets.lockLiveBlobById(item.assetBlobId())
        .orElseThrow(() -> new ResponseStatusException(
            HttpStatus.CONFLICT, "Staged blob is no longer available for " + item.logicalPath()));
    requireBlobMatchesItem(blob, item);
    if (adapter != null) {
      Optional<String> promoted = adapter.promote(
          runtime, item, blobSource(blob), actorUserId(actor), "staging");
      if (promoted.isPresent()) {
        return promoted.get();
      }
    }
    if (StagingComponents.unindexed(item.attributes())) {
      rawHosted.linkInternalBlobUnindexed(
          runtime, item.logicalPath(), blob, item.contentType(), createdBy, "staging");
      return item.action().equals(StagingDao.ITEM_ADD) ? "added" : "replaced";
    }
    Optional<ComponentRecord> encoded = StagingComponents.decode(
        item.repositoryId(), item.format(), item.attributes(), Instant.now());
    ComponentRecord component = encoded.orElseGet(() -> {
      if (adapter != null) {
        throw new ResponseStatusException(
            HttpStatus.CONFLICT,
            "Staged item has no component projection for " + item.logicalPath());
      }
      return rawComponent(runtime, item.logicalPath());
    });
    if (item.action().equals(StagingDao.ITEM_ADD)) {
      boolean inserted = rawHosted.linkInternalBlobWithComponentAtBrowsePathIfAbsent(
          runtime, item.logicalPath(), blob, item.contentType(), createdBy, "staging",
          component, item.logicalPath());
      if (inserted) {
        return "added";
      }
      Optional<AssetRecord> live = assets.findAssetByPath(runtime.id(), item.logicalPath());
      if (live.isPresent() && live.get().assetBlobId() != null) {
        Optional<AssetBlobRecord> liveBlob = assets.findBlobById(live.get().assetBlobId());
        if (liveBlob.isPresent()
            && java.util.Objects.equals(liveBlob.get().sha256(), item.sha256())) {
          return "unchanged";
        }
      }
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Live asset appeared at " + runtime.name() + "/" + item.logicalPath());
    }
    if (WritePolicy.parse(runtime.writePolicy()) == WritePolicy.DENY) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Repository write policy denies " + runtime.name());
    }
    if (WritePolicy.parse(runtime.writePolicy()) == WritePolicy.ALLOW_ONCE
        && assets.findAssetByPath(runtime.id(), item.logicalPath()).isPresent()) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "Write policy ALLOW_ONCE forbids replacing " + runtime.name() + "/" + item.logicalPath());
    }
    rawHosted.linkInternalBlobWithComponentAtBrowsePath(
        runtime, item.logicalPath(), blob, item.contentType(), createdBy, "staging",
        component, item.logicalPath());
    return "replaced";
  }

  private void recordPromotionFailure(
      PromotionAttempt attempt, RuntimeException error, PermissionSubject actor, String remoteAddr) {
    if (attempt == null) {
      return;
    }
    try {
      transaction.executeWithoutResult(status -> {
        StagingSessionRecord session = staging.lockSession(attempt.session().id()).orElse(null);
        if (session == null) {
          return;
        }
        String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        Map<String, Object> report = reportBody(
            session.id(), false, attempt.manifestSha256(), List.of(), message);
        staging.updatePromotion(
            attempt.promotionId(), StagingDao.PROMOTION_FAILED, attempt.manifestSha256(),
            report, null, Instant.now());
        staging.transitionSession(
            session.id(), session.revision(),
            Set.of(StagingDao.SESSION_PROMOTING, StagingDao.SESSION_PREFLIGHTED),
            StagingDao.SESSION_FAILED, null, Instant.now(), attempt.promotionId());
        audit(actor, remoteAddr, "staging-promote", session, "failed",
            Map.of("promotionId", attempt.promotionId(), "error", message));
      });
    } catch (RuntimeException ignored) {
      // The original promotion error remains the primary result.
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Staged content
  // ---------------------------------------------------------------------------------------------

  /** True when the format adapter accepts a publication body at this path (for example POST). */
  public boolean supportsPublication(RepositoryFormat format, String rawPath) {
    StagingContentAdapter adapter = adapterFor(format);
    return adapter != null && adapter.supportsPublication(rawPath);
  }

  @Transactional(readOnly = true)
  public Optional<MavenResponse> getStagedContent(
      RepositoryRuntime runtime,
      String sessionKey,
      String rawPath,
      boolean headOnly,
      PermissionSubject actor) {
    StagingSessionRecord session = findSessionByKey(sessionKey, false);
    requireSessionReadable(session, runtime, actor);
    StagingContentAdapter adapter = adapterFor(runtime.format());
    String path;
    if (adapter != null) {
      Optional<String> normalized = adapter.normalizePath(rawPath);
      if (normalized.isEmpty()) {
        return Optional.empty();
      }
      path = normalized.get();
    } else if (runtime.format() == RepositoryFormat.RAW) {
      if (rawPath == null || rawPath.isBlank() || rawPath.endsWith("/")) {
        return Optional.empty();
      }
      path = RawHostedService.normalizeAssetPath(rawPath);
    } else {
      throw new MavenExceptions.MethodNotAllowed(
          "Staging reads are not supported for " + runtime.format().id() + " repositories");
    }
    Optional<StagingItemRecord> staged = staging.findItem(
        session.id(), runtime.id(), PersistenceHashes.pathHash(path));
    if (staged.isEmpty()) {
      return Optional.empty();
    }
    StagingItemRecord item = staged.get();
    if (item.action().equals(StagingDao.ITEM_DELETE)) {
      return Optional.of(MavenResponse.noBody(404));
    }
    AssetBlobRecord blob = item.assetBlobId() == null
        ? null
        : assets.findBlobById(item.assetBlobId()).orElse(null);
    if (blob == null) {
      return Optional.of(MavenResponse.noBody(404));
    }
    return Optional.of(stagedResponse(runtime, item, blob, path, headOnly));
  }

  /** Returns the staged item/blob identity without materializing a protocol response. */
  @Transactional(readOnly = true)
  public Optional<StagedContent> findStagedContent(
      RepositoryRuntime runtime,
      String sessionKey,
      String rawPath,
      PermissionSubject actor) {
    StagingSessionRecord session = findSessionByKey(sessionKey, false);
    requireSessionReadable(session, runtime, actor);
    StagingContentAdapter adapter = adapterFor(runtime.format());
    if (adapter == null) {
      return Optional.empty();
    }
    Optional<String> normalized = adapter.normalizePath(rawPath);
    if (normalized.isEmpty()) {
      return Optional.empty();
    }
    Optional<StagingItemRecord> staged = staging.findItem(
        session.id(), runtime.id(), PersistenceHashes.pathHash(normalized.get()));
    if (staged.isEmpty()) {
      return Optional.empty();
    }
    StagingItemRecord item = staged.get();
    AssetBlobRecord blob = item.assetBlobId() == null
        ? null
        : assets.findBlobById(item.assetBlobId()).orElse(null);
    return Optional.of(new StagedContent(item, blob));
  }

  /** Reads a small staged payload, used by protocol boundaries that need raw bytes. */
  public byte[] readStagedBody(AssetBlobRecord blob) {
    if (blob == null) {
      throw new MavenExceptions.MavenNotFoundException("Staged blob is missing");
    }
    BlobReference reference = BlobReferenceCodec.reference(
        blob.blobRef(), blob.objectKey(), blob.sha256(), blob.size());
    try (InputStream body = blobStorageRegistry.forBlobStoreId(blob.blobStoreId())
        .get(reference)
        .orElseThrow(() -> new MavenExceptions.MavenNotFoundException(blob.objectKey()))) {
      return body.readAllBytes();
    } catch (java.io.IOException error) {
      throw new java.io.UncheckedIOException(error);
    }
  }

  public record StagedContent(StagingItemRecord item, AssetBlobRecord blob) {
  }

  @Transactional
  public StagingWriteResult putRaw(
      RepositoryRuntime runtime,
      String sessionKey,
      String rawPath,
      InputStream body,
      String contentType,
      PermissionSubject actor,
      String remoteAddr) {
    StagingSessionRecord session = lockSessionByKey(sessionKey);
    requireSessionWritable(session, runtime, actor);
    StagingContentAdapter adapter = adapterFor(runtime.format());
    if (adapter == null && runtime.format() != RepositoryFormat.RAW) {
      throw new MavenExceptions.MethodNotAllowed(
          "Staging writes are not supported for " + runtime.format().id() + " repositories");
    }
    StagingContentAdapter.StagedUpload upload = adapter == null
        ? stageRaw(runtime, rawPath, body, contentType, actorUserId(actor), remoteAddr)
        : adapter.stage(runtime, rawPath, body, contentType, actorUserId(actor), remoteAddr);
    return persistStagedUpload(session, runtime, upload, actor, remoteAddr);
  }

  /** Request-aware staging entry point used for multipart publication endpoints. */
  @Transactional
  public StagingWriteResult putRequest(
      RepositoryRuntime runtime,
      String sessionKey,
      String rawPath,
      HttpServletRequest request,
      PermissionSubject actor,
      String remoteAddr) throws java.io.IOException {
    StagingSessionRecord session = lockSessionByKey(sessionKey);
    requireSessionWritable(session, runtime, actor);
    StagingContentAdapter adapter = adapterFor(runtime.format());
    StagingContentAdapter.StagedUpload upload;
    if (adapter == null) {
      if (runtime.format() != RepositoryFormat.RAW) {
        throw new MavenExceptions.MethodNotAllowed(
            "Staging writes are not supported for " + runtime.format().id() + " repositories");
      }
      try (InputStream body = request.getInputStream()) {
        upload = stageRaw(
            runtime, rawPath, body, request.getContentType(), actorUserId(actor), remoteAddr);
      }
    } else {
      upload = adapter.stageRequest(
              runtime, rawPath, request, actorUserId(actor), remoteAddr)
          .orElseGet(() -> stageAdapterStream(
              adapter, runtime, rawPath, request, actorUserId(actor), remoteAddr));
    }
    return persistStagedUpload(session, runtime, upload, actor, remoteAddr);
  }

  private StagingContentAdapter.StagedUpload stageAdapterStream(
      StagingContentAdapter adapter,
      RepositoryRuntime runtime,
      String rawPath,
      HttpServletRequest request,
      String actor,
      String remoteAddr) {
    try (InputStream body = request.getInputStream()) {
      return adapter.stage(
          runtime, rawPath, body, request.getContentType(), actor, remoteAddr);
    } catch (java.io.IOException error) {
      throw new java.io.UncheckedIOException(error);
    }
  }

  private StagingContentAdapter.StagedUpload stageRaw(
      RepositoryRuntime runtime,
      String rawPath,
      InputStream body,
      String contentType,
      String actor,
      String remoteAddr) {
    String path = RawHostedService.normalizeAssetPath(rawPath);
    AssetBlobRecord blob = rawHosted.storeStagedBlob(
        runtime, path, body, contentType, Map.of(), actor, remoteAddr);
    Map<String, Object> attributes = new LinkedHashMap<>();
    attributes.put(StagingComponents.ATTRIBUTE,
        StagingComponents.encode(rawComponent(runtime, path)));
    return new StagingContentAdapter.StagedUpload(blob, path, contentType, attributes);
  }

  /**
   * Retains a completed Docker blob for the session. Promotion only releases this staging
   * ownership; the manifest publication makes the blob reachable.
   */
  @Transactional
  public StagingWriteResult stageDockerBlob(
      RepositoryRuntime runtime,
      String sessionKey,
      String digest,
      long blobId,
      PermissionSubject actor,
      String remoteAddr) {
    StagingSessionRecord session = lockSessionByKey(sessionKey);
    requireSessionWritable(session, runtime, actor);
    if (adapterFor(RepositoryFormat.DOCKER) == null) {
      throw new MavenExceptions.MethodNotAllowed("Docker staging adapter is not installed");
    }
    AssetBlobRecord blob = assets.findBlobById(blobId)
        .orElseThrow(() -> new MavenExceptions.MavenNotFoundException(
            "Docker blob not found: " + blobId));
    Map<String, Object> attributes = new LinkedHashMap<>();
    attributes.put("dockerBlob", true);
    attributes.put("dockerDigest", digest);
    StagingContentAdapter.StagedUpload upload = new StagingContentAdapter.StagedUpload(
        blob, "blob/" + digest, "application/octet-stream", attributes);
    return persistStagedUpload(session, runtime, upload, actor, remoteAddr);
  }

  private StagingWriteResult persistStagedUpload(
      StagingSessionRecord session,
      RepositoryRuntime runtime,
      StagingContentAdapter.StagedUpload upload,
      PermissionSubject actor,
      String remoteAddr) {
    String path = upload.logicalPath();
    WritePolicy policy = WritePolicy.parse(runtime.writePolicy());
    Optional<AssetRecord> live = assets.findAssetByPath(runtime.id(), path);
    if (policy == WritePolicy.DENY) {
      throw new MavenExceptions.WritePolicyDenied("Write policy DENY forbids writing " + path);
    }
    if (policy == WritePolicy.ALLOW_ONCE && live.isPresent()) {
      throw new MavenExceptions.WritePolicyDenied(
          "Write policy ALLOW_ONCE forbids overwriting " + path);
    }
    AssetBlobRecord blob = upload.blob();
    Map<String, Object> attributes = new LinkedHashMap<>();
    if (upload.attributes() != null) {
      attributes.putAll(upload.attributes());
    }
    attributes.put("stagedBy", actorUserId(actor));
    Optional<StagingItemRecord> existing = staging.findItem(
        session.id(), runtime.id(), PersistenceHashes.pathHash(path));
    String action = live.isPresent()
        ? StagingDao.ITEM_REPLACE
        : StagingDao.ITEM_ADD;
    Instant now = Instant.now();
    StagingItemRecord candidate = new StagingItemRecord(
        existing.map(StagingItemRecord::id).orElse(null),
        session.id(),
        runtime.id(),
        runtime.format(),
        path,
        PersistenceHashes.pathHash(path),
        action,
        StagingDao.ITEM_STAGED,
        blob.blobStoreId(),
        blob.id(),
        upload.contentType() == null || upload.contentType().isBlank()
            ? blob.contentType() : upload.contentType(),
        blob.size(),
        blob.sha256(),
        blob.md5(),
        blob.sha1(),
        path,
        null,
        null,
        attributes,
        now,
        now);
    long itemId;
    if (existing.isEmpty()) {
      OptionalLong inserted = staging.tryInsertItem(candidate);
      if (inserted.isPresent()) {
        itemId = inserted.getAsLong();
      } else {
        StagingItemRecord winner = staging.findItem(
                session.id(), runtime.id(), PersistenceHashes.pathHash(path))
            .orElseThrow(() -> new IllegalStateException("Staged item insert lost without a winner"));
        itemId = winner.id();
        releaseItemBlob(winner);
        staging.updateItem(withId(candidate, itemId));
      }
    } else {
      itemId = existing.get().id();
      releaseItemBlob(existing.get());
      staging.updateItem(withId(candidate, itemId));
    }
    if (!blobReferences.retain(BLOB_OWNER_TYPE, itemId, blob.id())) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Staged blob is no longer available; retry the upload");
    }
    audit(actor, remoteAddr, "staging-write", session,
        "staged", Map.of(
            "repository", runtime.name(),
            "path", path,
            "action", action,
            "sha256", blob.sha256()));
    return new StagingWriteResult(session.id(), itemId, action, blob.sha256(), blob.size());
  }

  @Transactional
  public int deleteRaw(
      RepositoryRuntime runtime,
      String sessionKey,
      String rawPath,
      PermissionSubject actor,
      String remoteAddr) {
    StagingSessionRecord session = lockSessionByKey(sessionKey);
    requireSessionWritable(session, runtime, actor);
    StagingContentAdapter adapter = adapterFor(runtime.format());
    String path;
    if (adapter != null) {
      path = adapter.normalizePath(rawPath).orElseThrow(() -> new MavenExceptions.BadRequestException(
          "Staging delete requires a concrete content path"));
    } else if (runtime.format() == RepositoryFormat.RAW) {
      path = RawHostedService.normalizeAssetPath(rawPath);
    } else {
      throw new MavenExceptions.MethodNotAllowed(
          "Staging deletes are not supported for " + runtime.format().id() + " repositories");
    }
    Optional<StagingItemRecord> existing = staging.findItem(
        session.id(), runtime.id(), PersistenceHashes.pathHash(path));
    Optional<AssetRecord> live = assets.findAssetByPath(runtime.id(), path);
    if (existing.isEmpty() && live.isEmpty()) {
      return 0;
    }
    if (live.isEmpty() && existing.isPresent()) {
      releaseItemBlob(existing.get());
      staging.deleteItem(existing.get().id());
      audit(actor, remoteAddr, "staging-delete", session,
          "unstaged", Map.of("repository", runtime.name(), "path", path));
      return 1;
    }
    Instant now = Instant.now();
    StagingItemRecord candidate = new StagingItemRecord(
        existing.map(StagingItemRecord::id).orElse(null),
        session.id(),
        runtime.id(),
        runtime.format(),
        path,
        PersistenceHashes.pathHash(path),
        StagingDao.ITEM_DELETE,
        StagingDao.ITEM_STAGED,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        path,
        null,
        null,
        Map.of("stagedBy", actorUserId(actor)),
        now,
        now);
    long itemId;
    if (existing.isEmpty()) {
      itemId = staging.tryInsertItem(candidate)
          .orElseGet(() -> staging.findItem(
                  session.id(), runtime.id(), PersistenceHashes.pathHash(path))
              .orElseThrow(() -> new IllegalStateException(
                  "Staged delete insert lost without a winner"))
              .id());
    } else {
      itemId = existing.get().id();
      releaseItemBlob(existing.get());
      staging.updateItem(withId(candidate, itemId));
    }
    audit(actor, remoteAddr, "staging-delete", session,
        "staged-delete", Map.of("repository", runtime.name(), "path", path));
    return 1;
  }

  // ---------------------------------------------------------------------------------------------
  // Validation
  // ---------------------------------------------------------------------------------------------

  private StagingReport runPreflight(
      StagingSessionRecord session, PermissionSubject actor, String remoteAddr) {
    List<StagingItemRecord> items = staging.listItems(session.id());
    if (items.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Staging session has no items");
    }
    if (items.size() > maxItems) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "Staging session has " + items.size() + " items; the configured limit is " + maxItems);
    }
    List<ItemValidation> validations = validate(session, items);
    List<Map<String, Object>> results = new ArrayList<>();
    List<ItemValidation> conflicts = new ArrayList<>();
    for (ItemValidation validation : validations) {
      staging.updateItemStatus(validation.item().id(), validation.status());
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("repositoryId", validation.item().repositoryId());
      row.put("path", validation.item().logicalPath());
      row.put("action", validation.item().action());
      row.put("status", validation.status());
      if (validation.message() != null) {
        row.put("message", validation.message());
      }
      results.add(row);
      if (validation.status().equals(StagingDao.ITEM_CONFLICT)) {
        conflicts.add(validation);
      }
    }
    String manifest = manifestHash(items);
    boolean promotable = conflicts.isEmpty();
    Map<String, Object> report = reportBody(
        session.id(), promotable, manifest, results,
        promotable ? null : conflictSummary(conflicts));
    Instant now = Instant.now();
    StagingSessionRecord current = staging.findSessionById(session.id()).orElseThrow();
    long promotionId = staging.insertPromotion(new StagingPromotionRecord(
        null,
        session.id(),
        StagingDao.PROMOTION_PREFLIGHT,
        null,
        manifest,
        report,
        null,
        null,
        now));
    if (promotable) {
      if (!staging.transitionSession(
          current.id(), current.revision(),
          Set.of(StagingDao.SESSION_FROZEN, StagingDao.SESSION_PREFLIGHTED),
          StagingDao.SESSION_PREFLIGHTED, null, null, promotionId)) {
        throw new ResponseStatusException(
            HttpStatus.CONFLICT, "Staging session changed concurrently; retry");
      }
    } else {
      // Keep the session frozen so operators can inspect or cancel it, and record the report.
      if (!staging.transitionSession(
          current.id(), current.revision(),
          Set.of(StagingDao.SESSION_FROZEN, StagingDao.SESSION_PREFLIGHTED),
          StagingDao.SESSION_FROZEN, null, null, promotionId)) {
        throw new ResponseStatusException(
            HttpStatus.CONFLICT, "Staging session changed concurrently; retry");
      }
    }
    audit(actor, remoteAddr, "staging-preflight", session,
        promotable ? "promotable" : "conflict",
        Map.of("promotionId", promotionId, "conflicts", conflicts.size()));
    return new StagingReport(promotable, manifest, StagingDao.PROMOTION_PREFLIGHT,
        List.copyOf(results), promotable ? null : conflictSummary(conflicts));
  }

  private List<ItemValidation> validate(
      StagingSessionRecord session, List<StagingItemRecord> items) {
    Map<Long, RepositoryRecord> repositoryCache = new LinkedHashMap<>();
    List<ItemValidation> results = new ArrayList<>();
    for (StagingItemRecord item : items.stream()
        .sorted(Comparator.comparingLong(StagingItemRecord::repositoryId)
            .thenComparing(StagingItemRecord::logicalPath))
        .toList()) {
      RepositoryRecord repository = repositoryCache.computeIfAbsent(
          item.repositoryId(), id -> repositories.findById(id).orElse(null));
      if (repository == null) {
        results.add(new ItemValidation(item, StagingDao.ITEM_CONFLICT,
            "Target repository no longer exists"));
        continue;
      }
      if (!staging.hasRepository(session.id(), repository.id())) {
        results.add(new ItemValidation(item, StagingDao.ITEM_CONFLICT,
            "Repository is not part of this staging session"));
        continue;
      }
      try {
        requireStagingRepository(repository);
      } catch (ResponseStatusException error) {
        results.add(new ItemValidation(item, StagingDao.ITEM_CONFLICT,
            error.getReason() == null ? "Repository is not a supported staging target" : error.getReason()));
        continue;
      }
      if (!repository.online()) {
        results.add(new ItemValidation(item, StagingDao.ITEM_CONFLICT,
            "Repository is offline"));
        continue;
      }
      WritePolicy policy = WritePolicy.parse(repository.writePolicy());
      if (!item.action().equals(StagingDao.ITEM_DELETE) && policy == WritePolicy.DENY) {
        results.add(new ItemValidation(item, StagingDao.ITEM_CONFLICT,
            "Repository write policy is DENY"));
        continue;
      }
      Optional<AssetRecord> live = assets.findAssetByPath(repository.id(), item.logicalPath());
      if (item.action().equals(StagingDao.ITEM_DELETE)) {
        results.add(new ItemValidation(item, StagingDao.ITEM_VALIDATED, null));
        continue;
      }
      Optional<AssetBlobRecord> blob = item.assetBlobId() == null
          ? Optional.empty()
          : assets.lockLiveBlobById(item.assetBlobId());
      if (blob.isEmpty()) {
        results.add(new ItemValidation(item, StagingDao.ITEM_CONFLICT,
            "Staged blob is no longer available"));
        continue;
      }
      String mismatch = blobMismatch(blob.get(), item);
      if (mismatch != null) {
        results.add(new ItemValidation(item, StagingDao.ITEM_CONFLICT, mismatch));
        continue;
      }
      if (repository.blobStoreId() == null
          || repository.blobStoreId() != blob.get().blobStoreId()) {
        results.add(new ItemValidation(item, StagingDao.ITEM_CONFLICT,
            "Repository blob store changed after the content was staged"));
        continue;
      }
      StagingContentAdapter adapter = adapterFor(item.format());
      if (adapter != null) {
        String error = adapter.validateItem(repository, item, live.orElse(null));
        if (error != null) {
          results.add(new ItemValidation(item, StagingDao.ITEM_CONFLICT, error));
          continue;
        }
      }
      if (item.action().equals(StagingDao.ITEM_ADD) && live.isPresent()) {
        Optional<AssetBlobRecord> liveBlob = live.get().assetBlobId() == null
            ? Optional.empty()
            : assets.findBlobById(live.get().assetBlobId());
        if (liveBlob.isPresent()
            && java.util.Objects.equals(liveBlob.get().sha256(), item.sha256())) {
          results.add(new ItemValidation(item, StagingDao.ITEM_VALIDATED, null));
        } else {
          results.add(new ItemValidation(item, StagingDao.ITEM_CONFLICT,
              "A different live asset already exists at this path"));
        }
        continue;
      }
      if (item.action().equals(StagingDao.ITEM_REPLACE)
          && policy == WritePolicy.ALLOW_ONCE && live.isPresent()) {
        results.add(new ItemValidation(item, StagingDao.ITEM_CONFLICT,
            "Write policy ALLOW_ONCE forbids replacing a live asset"));
        continue;
      }
      results.add(new ItemValidation(item, StagingDao.ITEM_VALIDATED, null));
    }
    return results;
  }

  private void requireBlobMatchesItem(AssetBlobRecord blob, StagingItemRecord item) {
    String mismatch = blobMismatch(blob, item);
    if (mismatch != null) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, mismatch);
    }
  }

  private static String blobMismatch(AssetBlobRecord blob, StagingItemRecord item) {
    if (item.sha256() != null
        && !item.sha256().equalsIgnoreCase(blob.sha256() == null ? "" : blob.sha256())) {
      return "Staged checksum no longer matches its blob";
    }
    if (item.size() != null && item.size() != blob.size()) {
      return "Staged size no longer matches its blob";
    }
    return null;
  }

  // ---------------------------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------------------------

  private StagingSessionView view(StagingSessionRecord session) {
    List<StagingRepositoryView> repositoryViews = staging.listRepositories(session.id()).stream()
        .map(repo -> new StagingRepositoryView(
            repo.repositoryId(),
            repositories.findById(repo.repositoryId()).map(RepositoryRecord::name).orElse(null),
            repo.format() == null ? null : repo.format().id()))
        .toList();
    long itemCount = staging.listItems(session.id()).size();
    return new StagingSessionView(session, repositoryViews, itemCount);
  }

  private void requireSessionReadable(
      StagingSessionRecord session, RepositoryRuntime runtime, PermissionSubject actor) {
    requireOwnerOrAdmin(session, actor);
    requireNotExpired(session);
    if (!ACTIVE_FOR_READ.contains(session.state())) {
      throw new MavenExceptions.MavenNotFoundException("Staging session is not readable");
    }
    if (!staging.hasRepository(session.id(), runtime.id())) {
      throw new MavenExceptions.MavenNotFoundException("Repository is not part of this staging session");
    }
    if (runtime.format() != RepositoryFormat.RAW && adapterFor(runtime.format()) == null) {
      throw new MavenExceptions.MethodNotAllowed(
          "Staging reads are not supported for " + runtime.format().id() + " repositories");
    }
  }

  private void requireSessionWritable(
      StagingSessionRecord session, RepositoryRuntime runtime, PermissionSubject actor) {
    requireOwnerOrAdmin(session, actor);
    requireState(session, WRITABLE, "Staging session is not OPEN");
    requireNotExpired(session);
    if (!staging.hasRepository(session.id(), runtime.id())) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Repository is not part of this staging session");
    }
    if (runtime.format() != RepositoryFormat.RAW && adapterFor(runtime.format()) == null) {
      throw new MavenExceptions.MethodNotAllowed(
          "Staging writes are not supported for " + runtime.format().id() + " repositories");
    }
    if (!runtime.isHosted()) {
      throw new MavenExceptions.MethodNotAllowed("Staging writes require a hosted repository");
    }
  }

  private StagingSessionRecord lockOwnedSession(long sessionId, PermissionSubject actor) {
    StagingSessionRecord session = staging.lockSession(sessionId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Staging session not found"));
    requireOwnerOrAdmin(session, actor);
    return session;
  }

  private StagingSessionRecord findSession(long sessionId) {
    return staging.findSessionById(sessionId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Staging session not found"));
  }

  private StagingSessionRecord findSessionByKey(String key, boolean lock) {
    if (key == null || key.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing staging session");
    }
    String value = key.trim();
    try {
      long id = Long.parseLong(value);
      Optional<StagingSessionRecord> found = lock
          ? staging.lockSession(id)
          : staging.findSessionById(id);
      return found.orElseThrow(() -> new ResponseStatusException(
          HttpStatus.NOT_FOUND, "Staging session not found"));
    } catch (NumberFormatException ignored) {
      Optional<StagingSessionRecord> found = lock
          ? staging.lockSessionByName(value)
          : staging.findSessionByName(value);
      return found.orElseThrow(() -> new ResponseStatusException(
          HttpStatus.NOT_FOUND, "Staging session not found"));
    }
  }

  private StagingSessionRecord lockSessionByKey(String key) {
    return findSessionByKey(key, true);
  }

  private void requireOwnerOrAdmin(StagingSessionRecord session, PermissionSubject actor) {
    if (!owns(session, actor) && !isAdmin(actor)) {
      throw new ResponseStatusException(
          HttpStatus.FORBIDDEN, "Staging session belongs to another user");
    }
  }

  private boolean owns(StagingSessionRecord session, PermissionSubject actor) {
    return actor != null
        && java.util.Objects.equals(session.ownerSource(), actor.source())
        && java.util.Objects.equals(session.ownerUserId(), actor.userId());
  }

  private boolean isAdmin(PermissionSubject actor) {
    return actor != null && security.decide(actor, "nexus:staging:admin").allowed();
  }

  private static void requireState(
      StagingSessionRecord session, Set<String> states, String message) {
    if (!states.contains(session.state())) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, message);
    }
  }

  private static void requireNotExpired(StagingSessionRecord session) {
    if (session.expiresAt() != null && !session.expiresAt().isAfter(Instant.now())) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Staging session has expired");
    }
  }

  private List<RepositoryRecord> resolveStagingRepositories(List<String> names) {
    List<RepositoryRecord> targets = new ArrayList<>();
    for (String name : names) {
      RepositoryRecord repository = repositories.findByName(name)
          .orElseThrow(() -> new ResponseStatusException(
              HttpStatus.BAD_REQUEST, "Repository not found: " + name));
      requireStagingRepository(repository);
      targets.add(repository);
    }
    return targets;
  }

  private void requireStagingRepository(RepositoryRecord repository) {
    if (repository.type() != RepositoryType.HOSTED) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "Multi-repository staging requires hosted repositories: " + repository.name());
    }
    if (repository.format() != RepositoryFormat.RAW
        && !contentAdapters.containsKey(repository.format())) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "No staging adapter is available for " + repository.format().id()
              + " repository " + repository.name());
    }
  }

  private StagingContentAdapter adapterFor(RepositoryFormat format) {
    return contentAdapters.get(format);
  }

  private static RepositoryFormat formatOf(StagingContentAdapter adapter) {
    for (RepositoryFormat format : RepositoryFormat.values()) {
      if (adapter.supports(format)) {
        return format;
      }
    }
    throw new IllegalStateException(
        "Staging adapter supports no repository format: " + adapter.getClass().getName());
  }

  private ComponentRecord rawComponent(RepositoryRuntime runtime, String path) {
    int slash = path.lastIndexOf('/');
    String group = slash <= 0 ? "/" : "/" + path.substring(0, slash);
    return new ComponentRecord(
        null,
        runtime.id(),
        runtime.format(),
        group,
        path,
        null,
        runtime.format().id(),
        PersistenceHashes.componentCoordinateHash(group, path, null),
        Map.of("path", path),
        Instant.now());
  }

  private MavenResponse stagedResponse(
      RepositoryRuntime runtime,
      StagingItemRecord item,
      AssetBlobRecord blob,
      String path,
      boolean headOnly) {
    String etag = blob.sha1();
    if (headOnly) {
      return MavenResponse.noBody(200, blob.size(), item.contentType(), etag, item.updatedAt());
    }
    BlobReference reference = BlobReferenceCodec.reference(
        blob.blobRef(), blob.objectKey(), blob.sha256(), blob.size());
    MavenResponse response = MavenResponse.ok(
        () -> blobStorageRegistry.forBlobStoreId(blob.blobStoreId()).get(reference)
            .orElseThrow(() -> new MavenExceptions.MavenNotFoundException(path)),
        blob.size(),
        item.contentType(),
        etag,
        item.updatedAt());
    if (runtime.format() == RepositoryFormat.RAW) {
      response = response.withHeader("Content-Disposition",
          runtime.rawContentDispositionOrDefault().toLowerCase(Locale.ROOT));
    }
    return response;
  }

  private StagingBlobSource blobSource(AssetBlobRecord blob) {
    BlobReference reference = BlobReferenceCodec.reference(
        blob.blobRef(), blob.objectKey(), blob.sha256(), blob.size());
    return () -> blobStorageRegistry.forBlobStoreId(blob.blobStoreId()).get(reference)
        .orElseThrow(() -> new MavenExceptions.MavenNotFoundException(blob.objectKey()));
  }

  private void releaseItemBlob(StagingItemRecord item) {    if (item.id() != null && item.assetBlobId() != null) {
      blobReferences.releaseOwner(BLOB_OWNER_TYPE, item.id());
    }
  }

  private static StagingItemRecord withId(StagingItemRecord item, long id) {
    return new StagingItemRecord(
        id, item.sessionId(), item.repositoryId(), item.format(), item.logicalPath(),
        item.logicalPathHash(), item.action(), item.status(), item.blobStoreId(),
        item.assetBlobId(), item.contentType(), item.size(), item.sha256(), item.md5(),
        item.sha1(), item.sourcePath(), item.expectedSha256(), item.expectedSize(),
        item.attributes(), item.createdAt(), item.updatedAt());
  }

  private StagingPromotionRecord currentPromotion(StagingSessionRecord session) {
    StagingPromotionRecord promotion = session.promotionId() == null
        ? null
        : staging.lockPromotion(session.promotionId()).orElse(null);
    if (promotion == null) {
      promotion = staging.findLatestPromotion(session.id()).orElse(null);
    }
    if (promotion == null || !promotion.state().equals(StagingDao.PROMOTION_PREFLIGHT)) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Run preflight before promoting this staging session");
    }
    return promotion;
  }

  private static String manifestHash(List<StagingItemRecord> items) {
    StringBuilder manifest = new StringBuilder();
    for (StagingItemRecord item : items.stream()
        .sorted(Comparator.comparingLong(StagingItemRecord::repositoryId)
            .thenComparing(StagingItemRecord::logicalPath))
        .toList()) {
      manifest.append(item.repositoryId()).append('\u0000')
          .append(item.logicalPath()).append('\u0000')
          .append(item.action()).append('\u0000')
          .append(item.sha256() == null ? "" : item.sha256()).append('\u0000')
          .append(item.size() == null ? "" : item.size()).append('\n');
    }
    return sha256Hex(manifest.toString());
  }

  private static String sha256Hex(String value) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return java.util.HexFormat.of().formatHex(
          digest.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is not available", e);
    }
  }

  private static Map<String, Object> reportBody(
      long sessionId,
      boolean promotable,
      String manifestSha256,
      List<Map<String, Object>> items,
      String error) {
    Map<String, Object> report = new LinkedHashMap<>();
    report.put("sessionId", sessionId);
    report.put("promotable", promotable);
    report.put("manifestSha256", manifestSha256);
    report.put("items", items);
    if (error != null) {
      report.put("error", error);
    }
    return report;
  }

  private static String conflictSummary(List<ItemValidation> conflicts) {
    return conflicts.stream()
        .limit(5)
        .map(conflict -> conflict.item().repositoryId() + ":" + conflict.item().logicalPath()
            + " (" + conflict.message() + ")")
        .reduce((left, right) -> left + "; " + right)
        .orElse("no conflicts");
  }

  private void audit(
      PermissionSubject actor,
      String remoteAddr,
      String permission,
      StagingSessionRecord session,
      String outcome,
      Map<String, Object> details) {
    try {
      Map<String, Object> payload = new LinkedHashMap<>(details);
      payload.put("sessionId", session.id());
      payload.put("sessionName", session.name());
      payload.put("revision", session.revision());
      audit.insert(new SecurityAuditDao.AuditLogRecord(
          LocalDateTime.now(),
          actorSource(actor),
          actorUserId(actor),
          null,
          null,
          remoteAddr,
          "INTERNAL",
          "/internal/staging/sessions/" + session.id(),
          permission,
          outcome.equals("failed") ? 500 : 200,
          outcome.toUpperCase(Locale.ROOT),
          payload));
    } catch (RuntimeException ignored) {
      // Staging correctness never depends on the audit sink.
    }
  }

  private static String actorSource(PermissionSubject actor) {
    return actor == null || actor.source() == null ? "anonymous" : actor.source();
  }

  private static String actorUserId(PermissionSubject actor) {
    return actor == null || actor.userId() == null ? "anonymous" : actor.userId();
  }

  // ---------------------------------------------------------------------------------------------
  // API records
  // ---------------------------------------------------------------------------------------------

  public record CreateSessionRequest(
      String name,
      List<String> repositories,
      Instant expiresAt,
      Integer retentionDays,
      Map<String, Object> attributes) {
  }

  public record UpdateSessionRequest(Instant expiresAt, Map<String, Object> attributes) {
  }

  public record StagingSessionView(
      StagingSessionRecord session,
      List<StagingRepositoryView> repositories,
      long itemCount) {
  }

  public record StagingRepositoryView(long repositoryId, String name, String format) {
  }

  public record StagingItemView(
      long id,
      long repositoryId,
      String format,
      String logicalPath,
      String action,
      String status,
      String contentType,
      Long size,
      String sha256,
      String sourcePath,
      Map<String, Object> attributes,
      Instant createdAt,
      Instant updatedAt) {
    static StagingItemView of(StagingItemRecord item) {
      return new StagingItemView(
          item.id(), item.repositoryId(),
          item.format() == null ? null : item.format().id(),
          item.logicalPath(), item.action(), item.status(), item.contentType(), item.size(),
          item.sha256(), item.sourcePath(), item.attributes(), item.createdAt(), item.updatedAt());
    }
  }

  public record StagingReport(
      boolean promotable,
      String manifestSha256,
      String state,
      List<Map<String, Object>> items,
      String error) {
    static StagingReport fromRecord(StagingPromotionRecord record) {
      Map<String, Object> report = record.report() == null ? Map.of() : record.report();
      Object promotable = report.get("promotable");
      Object error = report.get("error");
      Object items = report.get("items");
      List<Map<String, Object>> rows = items instanceof List<?> list
          ? list.stream()
              .filter(Map.class::isInstance)
              .map(row -> (Map<String, Object>) row)
              .toList()
          : List.of();
      return new StagingReport(
          Boolean.TRUE.equals(promotable),
          record.manifestSha256(),
          record.state(),
          rows,
          error == null ? null : String.valueOf(error));
    }
  }

  public record StagingWriteResult(
      long sessionId, long itemId, String action, String sha256, long size) {
  }

  public record CreateResult(StagingSessionView session, String token) {
  }

  private record ItemValidation(StagingItemRecord item, String status, String message) {
  }

  private record PromotionAttempt(
      StagingSessionRecord session,
      List<StagingItemRecord> items,
      long promotionId,
      String manifestSha256,
      Instant startedAt) {
  }
}

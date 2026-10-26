package com.github.klboke.kkrepo.server.rubygems;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.StagingItemRecord;
import com.github.klboke.kkrepo.server.blob.TempBlobFiles;
import com.github.klboke.kkrepo.server.maven.MavenExceptions;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.raw.RawHostedService;
import com.github.klboke.kkrepo.server.staging.StagingBlobSource;
import com.github.klboke.kkrepo.server.staging.StagingContentAdapter;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Staged RubyGems gem publication. */
@Component
final class RubygemsStagingContentAdapter implements StagingContentAdapter {
  private final RawHostedService hosted;
  private final RubygemsService rubygemsHosted;

  RubygemsStagingContentAdapter(RawHostedService hosted, RubygemsService rubygemsHosted) {
    this.hosted = hosted;
    this.rubygemsHosted = rubygemsHosted;
  }

  @Override
  public boolean supports(RepositoryFormat format) {
    return format == RepositoryFormat.RUBYGEMS;
  }

  @Override
  public Optional<String> normalizePath(String rawPath) {
    String path = rawPath == null ? "" : rawPath.trim();
    while (path.startsWith("/")) path = path.substring(1);
    if (path.isEmpty() || path.contains("..")) return Optional.empty();
    return Optional.of(path);
  }

  @Override
  public boolean supportsPublication(String rawPath) {
    return normalizePath(rawPath).filter("api/v1/gems"::equals).isPresent();
  }

  @Override
  public StagedUpload stage(
      RepositoryRuntime runtime,
      String requestedPath,
      InputStream body,
      String contentType,
      String actor,
      String ip) {
    Path temp = null;
    try {
      temp = TempBlobFiles.createTempFile(null, "rubygems-staging-", ".gem");
      try (InputStream input = body) {
        Files.copy(input, temp, StandardCopyOption.REPLACE_EXISTING);
      }
      RubygemsService.UploadPlan plan = rubygemsHosted.planPush(temp);
      AssetBlobRecord blob = hosted.storeStagedBlobFile(
          runtime, plan.gemPath(), temp, contentType, Map.of(), actor, ip);
      return new StagedUpload(blob, plan.gemPath(), contentType, Map.of());
    } catch (IOException error) {
      throw new MavenExceptions.BadRequestException("Unable to read RubyGems package", error);
    } finally {
      TempBlobFiles.deleteQuietly(temp);
    }
  }

  @Override
  public Optional<StagedUpload> stageRequest(
      RepositoryRuntime runtime,
      String requestedPath,
      HttpServletRequest request,
      String actor,
      String ip) {
    try (InputStream body = request.getInputStream()) {
      return Optional.of(stage(
          runtime, requestedPath, body, request.getContentType(), actor, ip));
    } catch (IOException error) {
      throw new UncheckedIOException(error);
    }
  }

  @Override
  public boolean delete(RepositoryRuntime runtime, String path) {
    return rubygemsHosted.delete(runtime, path).status() != 404;
  }

  @Override
  public String validateItem(
      RepositoryRecord repository, StagingItemRecord item, AssetRecord live) {
    if ("DELETE".equals(item.action())) return "RubyGems staging does not support deletions";
    if (live != null) return "RubyGems package already exists: " + item.logicalPath();
    return null;
  }

  @Override
  public Optional<String> promote(
      RepositoryRuntime runtime,
      StagingItemRecord item,
      StagingBlobSource source,
      String actor,
      String ip) {
    try (InputStream body = source.open()) {
      rubygemsHosted.push(runtime, body, item.contentType(), actor, ip);
      return Optional.of("published");
    } catch (IOException error) {
      throw new UncheckedIOException(error);
    }
  }
}

package com.github.klboke.kkrepo.server.swift;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.protocol.swift.SwiftPath;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.StagingItemRecord;
import com.github.klboke.kkrepo.server.blob.TempBlobFiles;
import com.github.klboke.kkrepo.server.config.RepositoryUrlPrefix;
import com.github.klboke.kkrepo.server.maven.MavenExceptions;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.raw.RawHostedService;
import com.github.klboke.kkrepo.server.security.ForwardedHeaderPolicy;
import com.github.klboke.kkrepo.server.staging.StagingBlobSource;
import com.github.klboke.kkrepo.server.staging.StagingContentAdapter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.Part;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Base64;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

/** Staged Swift Package Registry publication. */
@Component
final class SwiftStagingContentAdapter implements StagingContentAdapter {
  private final RawHostedService hosted;
  private final SwiftService swiftHosted;
  private final ForwardedHeaderPolicy forwardedHeaderPolicy;

  SwiftStagingContentAdapter(
      RawHostedService hosted,
      SwiftService swiftHosted,
      ForwardedHeaderPolicy forwardedHeaderPolicy) {
    this.hosted = hosted;
    this.swiftHosted = swiftHosted;
    this.forwardedHeaderPolicy = forwardedHeaderPolicy;
  }

  @Override
  public boolean supports(RepositoryFormat format) {
    return format == RepositoryFormat.SWIFT;
  }

  @Override
  public Optional<String> normalizePath(String rawPath) {
    String path = rawPath == null ? "" : rawPath.trim();
    while (path.startsWith("/")) path = path.substring(1);
    if (path.isEmpty() || path.contains("..")) return Optional.empty();
    return Optional.of(path);
  }

  @Override
  public StagedUpload stage(
      RepositoryRuntime runtime,
      String requestedPath,
      InputStream body,
      String contentType,
      String actor,
      String ip) {
    throw new MavenExceptions.BadRequestException(
        "Swift staging requires a multipart publish request");
  }

  @Override
  public Optional<StagedUpload> stageRequest(
      RepositoryRuntime runtime,
      String requestedPath,
      HttpServletRequest request,
      String actor,
      String ip) {
    String accept = request.getHeader(HttpHeaders.ACCEPT);
    SwiftPath path = swiftHosted.validatePublishRequest(
        runtime, requestedPath, request.getQueryString(), accept);
    try {
      Collection<Part> parts = request.getParts();
      SwiftMultipartRequest multipart = SwiftMultipartRequest.parse(parts);
      Path temp = null;
      try {
        temp = TempBlobFiles.createTempFile(null, "swift-staging-", ".zip");
        try (InputStream archive = multipart.openArchive()) {
          Files.copy(archive, temp, StandardCopyOption.REPLACE_EXISTING);
        }
        String logicalPath = swiftAssetPath(path);
        AssetBlobRecord blob = hosted.storeStagedBlobFile(
            runtime, logicalPath, temp, "application/zip", Map.of(), actor, ip);
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("swiftScope", path.scope());
        attributes.put("swiftName", path.name());
        attributes.put("swiftVersion", path.version());
        attributes.put("swiftMetadata", multipart.metadataJson());
        attributes.put("swiftBaseUrl", repositoryBaseUrl(request, runtime.name()));
        attributes.put("swiftSignatureFormat",
            request.getHeader("X-Swift-Package-Signature-Format"));
        putSignature(attributes, "swiftSourceSignature", multipart.sourceArchiveSignature());
        putSignature(attributes, "swiftMetadataSignature", multipart.metadataSignature());
        return Optional.of(new StagedUpload(
            blob, logicalPath, "application/zip", attributes));
      } finally {
        TempBlobFiles.deleteQuietly(temp);
      }
    } catch (IOException error) {
      throw new SwiftExceptions.BadRequest("Unable to read Swift publish request", error);
    } catch (jakarta.servlet.ServletException error) {
      throw new SwiftExceptions.BadRequest("Unable to parse Swift multipart request", error);
    }
  }

  @Override
  public boolean delete(RepositoryRuntime runtime, String path) {
    throw new MavenExceptions.MethodNotAllowed(
        "Swift staging does not support deletions");
  }

  @Override
  public String validateItem(
      RepositoryRecord repository, StagingItemRecord item, AssetRecord live) {
    if ("DELETE".equals(item.action())) return "Swift staging does not support deletions";
    return null;
  }

  @Override
  public Optional<String> promote(
      RepositoryRuntime runtime,
      StagingItemRecord item,
      StagingBlobSource source,
      String actor,
      String ip) {
    Map<String, Object> attributes = item.attributes();
    String scope = String.valueOf(attributes.get("swiftScope"));
    String name = String.valueOf(attributes.get("swiftName"));
    String version = String.valueOf(attributes.get("swiftVersion"));
    String metadata = String.valueOf(attributes.getOrDefault("swiftMetadata", "{}"));
    String baseUrl = String.valueOf(attributes.getOrDefault("swiftBaseUrl", ""));
    String signatureFormat = attributes.get("swiftSignatureFormat") == null
        ? null : String.valueOf(attributes.get("swiftSignatureFormat"));
    byte[] sourceSignature = signature(attributes, "swiftSourceSignature");
    byte[] metadataSignature = signature(attributes, "swiftMetadataSignature");
    try (InputStream archive = source.open()) {
      swiftHosted.publishUpload(
          runtime, scope, name, version, archive, metadata, sourceSignature, metadataSignature,
          signatureFormat, baseUrl, actor, ip);
      return Optional.of("published");
    } catch (IOException error) {
      throw new java.io.UncheckedIOException(error);
    }
  }

  private String repositoryBaseUrl(HttpServletRequest request, String repository) {
    return forwardedHeaderPolicy.serverBaseUrl(request)
        + request.getContextPath() + RepositoryUrlPrefix.path(request) + repository;
  }

  private static String swiftAssetPath(SwiftPath path) {
    return "swift/" + path.scope() + "/" + path.name() + "/" + path.version() + ".zip";
  }

  private static void putSignature(Map<String, Object> attributes, String key, byte[] value) {
    if (value != null && value.length > 0) {
      attributes.put(key, Base64.getEncoder().encodeToString(value));
    }
  }

  private static byte[] signature(Map<String, Object> attributes, String key) {
    Object value = attributes == null ? null : attributes.get(key);
    return value == null ? null : Base64.getDecoder().decode(String.valueOf(value));
  }
}

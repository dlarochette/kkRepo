package com.github.klboke.kkrepo.server.nuget;

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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Staged NuGet package publication. */
@Component
final class NugetStagingContentAdapter implements StagingContentAdapter {
  private final RawHostedService hosted;
  private final NugetService nugetHosted;

  NugetStagingContentAdapter(RawHostedService hosted, NugetService nugetHosted) {
    this.hosted = hosted;
    this.nugetHosted = nugetHosted;
  }

  @Override
  public boolean supports(RepositoryFormat format) {
    return format == RepositoryFormat.NUGET;
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
    Path temp = null;
    try {
      temp = TempBlobFiles.createTempFile(null, "nuget-staging-", ".nupkg");
      try (InputStream input = body) {
        Files.copy(input, temp, StandardCopyOption.REPLACE_EXISTING);
      }
      NugetService.UploadPlan plan = nugetHosted.planPackage(temp);
      AssetBlobRecord blob = hosted.storeStagedBlobFile(
          runtime, plan.packagePath(), temp, contentType, Map.of(), actor, ip);
      Map<String, Object> attributes = new LinkedHashMap<>();
      attributes.put("nugetPublishPath", publishPath(requestedPath));
      return new StagedUpload(blob, plan.packagePath(), contentType, attributes);
    } catch (IOException error) {
      throw new MavenExceptions.BadRequestException("Unable to read NuGet package", error);
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
    return nugetHosted.deletePackage(runtime, path).status() != 404;
  }

  @Override
  public String validateItem(
      RepositoryRecord repository, StagingItemRecord item, AssetRecord live) {
    if ("DELETE".equals(item.action())) return "NuGet staging does not support deletions";
    if (live != null) return "NuGet package already exists: " + item.logicalPath();
    return null;
  }

  @Override
  public Optional<String> promote(
      RepositoryRuntime runtime,
      StagingItemRecord item,
      StagingBlobSource source,
      String actor,
      String ip) {
    String publishPath = String.valueOf(
        item.attributes().getOrDefault("nugetPublishPath", "api/v2/package"));
    try (InputStream body = source.open()) {
      nugetHosted.putPackage(
          runtime, publishPath, body, item.contentType(), actor, ip);
      return Optional.of("published");
    } catch (IOException error) {
      throw new UncheckedIOException(error);
    }
  }

  private static String publishPath(String requestedPath) {
    String value = requestedPath == null ? "" : requestedPath.trim();
    return value.isEmpty() ? "api/v2/package" : value;
  }
}

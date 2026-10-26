package com.github.klboke.kkrepo.server.helm;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.StagingItemRecord;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.raw.RawHostedService;
import com.github.klboke.kkrepo.server.staging.StagingBlobSource;
import com.github.klboke.kkrepo.server.staging.StagingContentAdapter;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Staged Helm chart publication replayed through the normal Helm writer. */
@Component
final class HelmStagingContentAdapter implements StagingContentAdapter {
  private final RawHostedService hosted;
  private final HelmHostedService helmHosted;

  HelmStagingContentAdapter(RawHostedService hosted, HelmHostedService helmHosted) {
    this.hosted = hosted;
    this.helmHosted = helmHosted;
  }

  @Override
  public boolean supports(RepositoryFormat format) {
    return format == RepositoryFormat.HELM;
  }

  @Override
  public Optional<String> normalizePath(String rawPath) {
    String path = rawPath == null ? "" : rawPath.trim();
    while (path.startsWith("/")) path = path.substring(1);
    if (path.isEmpty() || !path.endsWith(".tgz") || path.contains("..")) {
      return Optional.empty();
    }
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
    String path = normalizePath(requestedPath)
        .orElseThrow(() -> new IllegalArgumentException(
            "Staged Helm writes require a chart .tgz path"));
    AssetBlobRecord blob = hosted.storeStagedBlob(
        runtime, path, body, contentType, Map.of(), actor, ip);
    return new StagedUpload(blob, path, contentType, Map.of());
  }

  @Override
  public boolean delete(RepositoryRuntime runtime, String path) {
    return helmHosted.delete(runtime, path).status() != 404;
  }

  @Override
  public Optional<String> promote(
      RepositoryRuntime runtime,
      StagingItemRecord item,
      StagingBlobSource source,
      String actor,
      String ip) {
    try (InputStream body = source.open()) {
      helmHosted.put(
          runtime, item.logicalPath(), body, item.contentType(), actor, ip);
      return Optional.of("published");
    } catch (IOException error) {
      throw new UncheckedIOException(error);
    }
  }
}

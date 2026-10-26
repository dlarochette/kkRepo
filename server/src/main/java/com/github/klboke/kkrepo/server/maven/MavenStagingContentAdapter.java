package com.github.klboke.kkrepo.server.maven;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import com.github.klboke.kkrepo.protocol.maven.path.MavenPath;
import com.github.klboke.kkrepo.protocol.maven.path.MavenPathParser;
import com.github.klboke.kkrepo.server.maven.MavenExceptions;
import com.github.klboke.kkrepo.server.raw.RawHostedService;
import com.github.klboke.kkrepo.server.staging.StagingBlobSource;
import com.github.klboke.kkrepo.server.staging.StagingContentAdapter;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Staged Maven publication.
 *
 * <p>Staging only retains the uploaded bytes. Promotion replays the normal Maven writer, which
 * applies layout/version policy and rebuilds generated metadata exactly like a live PUT.
 */
@Component
final class MavenStagingContentAdapter implements StagingContentAdapter {
  private final MavenPathParser parser = new MavenPathParser();
  private final RawHostedService hosted;
  private final MavenHostedService mavenHosted;

  MavenStagingContentAdapter(RawHostedService hosted, MavenHostedService mavenHosted) {
    this.hosted = hosted;
    this.mavenHosted = mavenHosted;
  }

  @Override
  public boolean supports(RepositoryFormat format) {
    return format == RepositoryFormat.MAVEN2;
  }

  @Override
  public Optional<String> normalizePath(String rawPath) {
    try {
      MavenPath path = parser.parsePath(rawPath);
      return Optional.of(path.path());
    } catch (IllegalArgumentException invalid) {
      return Optional.empty();
    }
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
        .orElseThrow(() -> new MavenExceptions.BadRequestException("Invalid Maven path"));
    AssetBlobRecord blob = hosted.storeStagedBlob(
        runtime, path, body, contentType, Map.of(), actor, ip);
    return new StagedUpload(blob, path, contentType, Map.of());
  }

  @Override
  public boolean delete(RepositoryRuntime runtime, String path) {
    return mavenHosted.delete(runtime, parser.parsePath(path)).status() != 404;
  }

  @Override
  public Optional<String> promote(
      RepositoryRuntime runtime,
      com.github.klboke.kkrepo.persistence.jdbc.api.model.StagingItemRecord item,
      StagingBlobSource source,
      String actor,
      String ip) {
    try (InputStream body = source.open()) {
      mavenHosted.put(
          runtime, parser.parsePath(item.logicalPath()), body, item.contentType(), actor, ip);
      return Optional.of("published");
    } catch (IOException error) {
      throw new UncheckedIOException(error);
    }
  }
}

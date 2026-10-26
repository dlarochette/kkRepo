package com.github.klboke.kkrepo.server.npm;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.StagingItemRecord;
import com.github.klboke.kkrepo.protocol.npm.NpmPath;
import com.github.klboke.kkrepo.protocol.npm.NpmPathParser;
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

/**
 * Staged npm publication.
 *
 * <p>Promotion replays the normal npm publish writer, which writes the packument and all tarball
 * attachments and preserves npm revision semantics.
 */
@Component
final class NpmStagingContentAdapter implements StagingContentAdapter {
  private final NpmPathParser parser = new NpmPathParser();
  private final RawHostedService hosted;
  private final NpmHostedService npmHosted;

  NpmStagingContentAdapter(RawHostedService hosted, NpmHostedService npmHosted) {
    this.hosted = hosted;
    this.npmHosted = npmHosted;
  }

  @Override
  public boolean supports(RepositoryFormat format) {
    return format == RepositoryFormat.NPM;
  }

  @Override
  public Optional<String> normalizePath(String rawPath) {
    try {
      NpmPath path = parser.parse(rawPath);
      if (path.kind() == NpmPath.Kind.PACKAGE_ROOT) {
        return Optional.of(path.packageId().id());
      }
      return Optional.empty();
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
    NpmPath path = parser.parse(requestedPath);
    if (path.kind() != NpmPath.Kind.PACKAGE_ROOT) {
      throw new NpmExceptions.MethodNotAllowed(
          "Staged npm writes require a package publish path");
    }
    String logicalPath = path.packageId().id();
    AssetBlobRecord blob = hosted.storeStagedBlob(
        runtime, logicalPath, body, contentType, Map.of(), actor, ip);
    return new StagedUpload(blob, logicalPath, contentType, Map.of());
  }

  @Override
  public boolean delete(RepositoryRuntime runtime, String path) {
    NpmPath parsed = parser.parse(path);
    return switch (parsed.kind()) {
      case PACKAGE_ROOT -> npmHosted.deletePackage(
          runtime, parsed.packageId(), parsed.revision()).status() != 404;
      case TARBALL -> npmHosted.deleteTarball(
          runtime, parsed.packageId(), parsed.tarballName(), parsed.revision()).status() != 404;
      case DIST_TAG -> false;
      default -> false;
    };
  }

  @Override
  public Optional<String> promote(
      RepositoryRuntime runtime,
      StagingItemRecord item,
      StagingBlobSource source,
      String actor,
      String ip) {
    NpmPath path = parser.parse(item.logicalPath());
    if (path.kind() != NpmPath.Kind.PACKAGE_ROOT) {
      throw new NpmExceptions.MethodNotAllowed("Unsupported staged npm path");
    }
    try (InputStream body = source.open()) {
      npmHosted.putPackage(
          runtime, path.packageId(), path.revision(), body, actor, ip);
      return Optional.of("published");
    } catch (IOException error) {
      throw new UncheckedIOException(error);
    }
  }
}

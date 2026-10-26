package com.github.klboke.kkrepo.server.apt;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.StagingItemRecord;
import com.github.klboke.kkrepo.protocol.apt.AptPath;
import com.github.klboke.kkrepo.protocol.apt.AptPathParser;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.raw.RawHostedService;
import com.github.klboke.kkrepo.server.staging.PathReplayStagingAdapter;
import com.github.klboke.kkrepo.server.staging.StagingBlobSource;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Staged Debian package publication replayed through the normal APT writer. */
@Component
final class AptStagingContentAdapter extends PathReplayStagingAdapter {
  private final AptPathParser parser = new AptPathParser();
  private final AptService aptHosted;

  AptStagingContentAdapter(RawHostedService hosted, AptService aptHosted) {
    super(hosted);
    this.aptHosted = aptHosted;
  }

  @Override
  public boolean supports(RepositoryFormat format) {
    return format == RepositoryFormat.APT;
  }

  @Override
  public Optional<String> normalizePath(String rawPath) {
    try {
      AptPath path = parser.parse(rawPath);
      return path.kind() == AptPath.Kind.PACKAGE
          ? Optional.of(path.normalized()) : Optional.empty();
    } catch (IllegalArgumentException invalid) {
      return Optional.empty();
    }
  }

  @Override
  public boolean delete(RepositoryRuntime runtime, String path) {
    return aptHosted.delete(runtime, path, "staging promotion", false).status() != 404;
  }

  @Override
  protected String replay(
      RepositoryRuntime runtime,
      StagingItemRecord item,
      StagingBlobSource source,
      String actor,
      String ip) {
    return replay(source, body -> aptHosted.put(
        runtime, item.logicalPath(), body, item.contentType(), actor, ip));
  }
}

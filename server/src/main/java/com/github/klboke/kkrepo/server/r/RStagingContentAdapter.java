package com.github.klboke.kkrepo.server.r;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.StagingItemRecord;
import com.github.klboke.kkrepo.protocol.r.RPath;
import com.github.klboke.kkrepo.protocol.r.RPathParser;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.raw.RawHostedService;
import com.github.klboke.kkrepo.server.staging.PathReplayStagingAdapter;
import com.github.klboke.kkrepo.server.staging.StagingBlobSource;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Staged R source package publication replayed through the normal R writer. */
@Component
final class RStagingContentAdapter extends PathReplayStagingAdapter {
  private final RPathParser parser = new RPathParser();
  private final RService rHosted;

  RStagingContentAdapter(RawHostedService hosted, RService rHosted) {
    super(hosted);
    this.rHosted = rHosted;
  }

  @Override
  public boolean supports(RepositoryFormat format) {
    return format == RepositoryFormat.R;
  }

  @Override
  public Optional<String> normalizePath(String rawPath) {
    try {
      RPath path = parser.parse(rawPath);
      return path.kind() == RPath.Kind.SOURCE_PACKAGE
          ? Optional.of(path.normalized()) : Optional.empty();
    } catch (IllegalArgumentException invalid) {
      return Optional.empty();
    }
  }

  @Override
  public boolean delete(RepositoryRuntime runtime, String path) {
    return rHosted.delete(runtime, path, "staging promotion", false).status() != 404;
  }

  @Override
  protected String replay(
      RepositoryRuntime runtime,
      StagingItemRecord item,
      StagingBlobSource source,
      String actor,
      String ip) {
    return replay(source, body -> rHosted.put(
        runtime, item.logicalPath(), body, item.contentType(), actor, ip));
  }
}

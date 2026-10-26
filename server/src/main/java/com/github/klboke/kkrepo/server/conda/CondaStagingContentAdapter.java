package com.github.klboke.kkrepo.server.conda;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.StagingItemRecord;
import com.github.klboke.kkrepo.protocol.conda.CondaPath;
import com.github.klboke.kkrepo.protocol.conda.CondaPathParser;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.raw.RawHostedService;
import com.github.klboke.kkrepo.server.staging.PathReplayStagingAdapter;
import com.github.klboke.kkrepo.server.staging.StagingBlobSource;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Staged Conda package publication replayed through the normal Conda writer. */
@Component
final class CondaStagingContentAdapter extends PathReplayStagingAdapter {
  private final CondaPathParser parser = new CondaPathParser();
  private final CondaService condaHosted;

  CondaStagingContentAdapter(RawHostedService hosted, CondaService condaHosted) {
    super(hosted);
    this.condaHosted = condaHosted;
  }

  @Override
  public boolean supports(RepositoryFormat format) {
    return format == RepositoryFormat.CONDA;
  }

  @Override
  public Optional<String> normalizePath(String rawPath) {
    try {
      CondaPath path = parser.parse(rawPath);
      return path.packageFile() ? Optional.of(path.canonicalPath()) : Optional.empty();
    } catch (IllegalArgumentException invalid) {
      return Optional.empty();
    }
  }

  @Override
  public boolean delete(RepositoryRuntime runtime, String path) {
    return condaHosted.delete(runtime, path).status() != 404;
  }

  @Override
  protected String replay(
      RepositoryRuntime runtime,
      StagingItemRecord item,
      StagingBlobSource source,
      String actor,
      String ip) {
    return replay(source, body -> condaHosted.put(
        runtime, item.logicalPath(), body, item.contentType(), actor, ip));
  }
}

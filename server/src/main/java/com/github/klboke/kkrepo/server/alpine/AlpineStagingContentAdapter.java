package com.github.klboke.kkrepo.server.alpine;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.StagingItemRecord;
import com.github.klboke.kkrepo.protocol.alpine.AlpinePath;
import com.github.klboke.kkrepo.protocol.alpine.AlpinePathParser;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.raw.RawHostedService;
import com.github.klboke.kkrepo.server.staging.PathReplayStagingAdapter;
import com.github.klboke.kkrepo.server.staging.StagingBlobSource;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Staged Alpine APK publication replayed through the normal Alpine writer. */
@Component
final class AlpineStagingContentAdapter extends PathReplayStagingAdapter {
  private final AlpinePathParser parser = new AlpinePathParser();
  private final AlpineService alpineHosted;

  AlpineStagingContentAdapter(RawHostedService hosted, AlpineService alpineHosted) {
    super(hosted);
    this.alpineHosted = alpineHosted;
  }

  @Override
  public boolean supports(RepositoryFormat format) {
    return format == RepositoryFormat.ALPINE;
  }

  @Override
  public Optional<String> normalizePath(String rawPath) {
    try {
      AlpinePath path = parser.parse(rawPath);
      return path.kind() == AlpinePath.Kind.PACKAGE
          ? Optional.of(path.normalized()) : Optional.empty();
    } catch (IllegalArgumentException invalid) {
      return Optional.empty();
    }
  }

  @Override
  public boolean delete(RepositoryRuntime runtime, String path) {
    return alpineHosted.delete(runtime, path, "staging promotion", false).status() != 404;
  }

  @Override
  protected String replay(
      RepositoryRuntime runtime,
      StagingItemRecord item,
      StagingBlobSource source,
      String actor,
      String ip) {
    return replay(source, body -> alpineHosted.put(
        runtime, item.logicalPath(), body, item.contentType(), actor, ip));
  }
}

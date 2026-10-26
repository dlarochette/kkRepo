package com.github.klboke.kkrepo.server.goartifact;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.StagingItemRecord;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.raw.RawHostedService;
import com.github.klboke.kkrepo.server.staging.PathReplayStagingAdapter;
import com.github.klboke.kkrepo.server.staging.StagingBlobSource;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Staged Go module archive publication replayed through the normal Go writer. */
@Component
final class GoStagingContentAdapter extends PathReplayStagingAdapter {
  private final GoHostedService goHosted;

  GoStagingContentAdapter(RawHostedService hosted, GoHostedService goHosted) {
    super(hosted);
    this.goHosted = goHosted;
  }

  @Override
  public boolean supports(RepositoryFormat format) {
    return format == RepositoryFormat.GO;
  }

  @Override
  public Optional<String> normalizePath(String rawPath) {
    String path = rawPath == null ? "" : rawPath.trim();
    while (path.startsWith("/")) path = path.substring(1);
    if (path.isEmpty() || path.contains("..")) return Optional.empty();
    return Optional.of(path);
  }

  @Override
  protected String replay(
      RepositoryRuntime runtime,
      StagingItemRecord item,
      StagingBlobSource source,
      String actor,
      String ip) {
    return replay(source, body -> goHosted.publish(
        runtime, item.logicalPath(), body, actor, ip));
  }
}

package com.github.klboke.kkrepo.server.yum;

import com.github.klboke.kkrepo.persistence.jdbc.api.model.StagingItemRecord;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.raw.RawHostedService;
import com.github.klboke.kkrepo.server.staging.PathReplayStagingAdapter;
import com.github.klboke.kkrepo.server.staging.StagingBlobSource;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Staged Yum RPM publication replayed through the normal Yum writer. */
@Component
final class YumStagingContentAdapter extends PathReplayStagingAdapter {
  private final YumService yumHosted;

  YumStagingContentAdapter(RawHostedService hosted, YumService yumHosted) {
    super(hosted);
    this.yumHosted = yumHosted;
  }

  @Override
  public boolean supports(RepositoryFormat format) {
    return format == RepositoryFormat.YUM;
  }

  @Override
  public Optional<String> normalizePath(String rawPath) {
    String path = rawPath == null ? "" : rawPath.trim();
    while (path.startsWith("/")) path = path.substring(1);
    if (path.isEmpty() || path.contains("..")) return Optional.empty();
    return Optional.of(path);
  }

  @Override
  public boolean delete(RepositoryRuntime runtime, String path) {
    return yumHosted.delete(runtime, path).status() != 404;
  }

  @Override
  protected String replay(
      RepositoryRuntime runtime,
      StagingItemRecord item,
      StagingBlobSource source,
      String actor,
      String ip) {
    return replay(source, body -> yumHosted.put(
        runtime, item.logicalPath(), body, item.contentType(), actor, ip));
  }
}

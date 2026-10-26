package com.github.klboke.kkrepo.server.terraform;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.StagingItemRecord;
import com.github.klboke.kkrepo.protocol.terraform.TerraformPath;
import com.github.klboke.kkrepo.protocol.terraform.TerraformPathParser;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.raw.RawHostedService;
import com.github.klboke.kkrepo.server.staging.PathReplayStagingAdapter;
import com.github.klboke.kkrepo.server.staging.StagingBlobSource;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Staged Terraform archive publication replayed through the normal Terraform writer. */
@Component
final class TerraformStagingContentAdapter extends PathReplayStagingAdapter {
  private final TerraformPathParser parser = new TerraformPathParser();
  private final TerraformService terraformHosted;

  TerraformStagingContentAdapter(RawHostedService hosted, TerraformService terraformHosted) {
    super(hosted);
    this.terraformHosted = terraformHosted;
  }

  @Override
  public boolean supports(RepositoryFormat format) {
    return format == RepositoryFormat.TERRAFORM;
  }

  @Override
  public Optional<String> normalizePath(String rawPath) {
    try {
      TerraformPath path = parser.parse(rawPath);
      if (path.kind() == TerraformPath.Kind.UNKNOWN) return Optional.empty();
      return Optional.of(path.rawPath());
    } catch (IllegalArgumentException invalid) {
      return Optional.empty();
    }
  }

  @Override
  protected String replay(
      RepositoryRuntime runtime,
      StagingItemRecord item,
      StagingBlobSource source,
      String actor,
      String ip) {
    return replay(source, body -> terraformHosted.put(
        runtime, parser.parse(item.logicalPath()), body, item.contentType(), null, null,
        actor, ip));
  }
}

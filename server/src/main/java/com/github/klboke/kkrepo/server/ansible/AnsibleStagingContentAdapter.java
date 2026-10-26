package com.github.klboke.kkrepo.server.ansible;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.StagingItemRecord;
import com.github.klboke.kkrepo.protocol.ansible.AnsibleGalaxyPath;
import com.github.klboke.kkrepo.protocol.ansible.AnsibleGalaxyPathParser;
import com.github.klboke.kkrepo.server.maven.MavenExceptions;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.raw.RawHostedService;
import com.github.klboke.kkrepo.server.staging.PathReplayStagingAdapter;
import com.github.klboke.kkrepo.server.staging.StagingBlobSource;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Staged Ansible Galaxy collection artifact publication. */
@Component
final class AnsibleStagingContentAdapter extends PathReplayStagingAdapter {
  private final AnsibleGalaxyPathParser parser = new AnsibleGalaxyPathParser();
  private final AnsibleGalaxyService ansibleHosted;

  AnsibleStagingContentAdapter(
      RawHostedService hosted, AnsibleGalaxyService ansibleHosted) {
    super(hosted);
    this.ansibleHosted = ansibleHosted;
  }

  @Override
  public boolean supports(RepositoryFormat format) {
    return format == RepositoryFormat.ANSIBLEGALAXY;
  }

  @Override
  public Optional<String> normalizePath(String rawPath) {
    try {
      AnsibleGalaxyPath path = parser.parse(rawPath);
      return path.kind() == AnsibleGalaxyPath.Kind.ARTIFACT
          ? Optional.of(path.rawPath()) : Optional.empty();
    } catch (IllegalArgumentException invalid) {
      return Optional.empty();
    }
  }

  @Override
  public boolean delete(RepositoryRuntime runtime, String path) {
    throw new MavenExceptions.MethodNotAllowed(
        "Ansible Galaxy staging does not support deletions");
  }

  @Override
  protected String replay(
      RepositoryRuntime runtime,
      StagingItemRecord item,
      StagingBlobSource source,
      String actor,
      String ip) {
    return replay(source, body -> ansibleHosted.putArtifact(
        runtime, item.logicalPath(), body, actor, ip));
  }
}

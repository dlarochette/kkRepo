package com.github.klboke.kkrepo.server.terraform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.StagingItemRecord;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.raw.RawHostedService;
import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TerraformStagingContentAdapterTest {
  private final RawHostedService hosted = mock(RawHostedService.class);
  private final TerraformService terraformHosted = mock(TerraformService.class);
  private final TerraformStagingContentAdapter adapter =
      new TerraformStagingContentAdapter(hosted, terraformHosted);
  private final RepositoryRuntime runtime = new RepositoryRuntime(
      1L, "tf-hosted", RepositoryFormat.TERRAFORM, RepositoryType.HOSTED, "tf-hosted",
      true, 1L, "ALLOW", null, null, true, null, null, null, null, null, List.of());
  private final String path = "v1/modules/acme/network/aws/1.2.3/network.zip";

  @Test
  void acceptsKnownTerraformPaths() {
    assertTrue(adapter.normalizePath(path).isPresent());
    assertTrue(adapter.normalizePath("nope").isEmpty());
  }

  @Test
  void promotionReplaysTheTerraformWriter() {
    when(terraformHosted.put(any(), any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(MavenResponse.created());
    StagingItemRecord item = item();
    assertEquals("published", adapter.promote(
        runtime, item, () -> new ByteArrayInputStream(new byte[] {1}), "alice", "127.0.0.1")
        .orElseThrow());
    verify(terraformHosted).put(
        eq(runtime), any(), any(), any(), any(), any(), eq("alice"), eq("127.0.0.1"));
  }

  private StagingItemRecord item() {
    return new StagingItemRecord(
        1L, 1L, 1L, RepositoryFormat.TERRAFORM, path, new byte[32], "ADD", "STAGED",
        1L, 1L, "application/zip", 1L, "2".repeat(64), "3".repeat(32), "1".repeat(40),
        path, null, null, Map.of(), Instant.now(), Instant.now());
  }
}

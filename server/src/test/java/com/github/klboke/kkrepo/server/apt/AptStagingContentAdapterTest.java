package com.github.klboke.kkrepo.server.apt;

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

class AptStagingContentAdapterTest {
  private final RawHostedService hosted = mock(RawHostedService.class);
  private final AptService aptHosted = mock(AptService.class);
  private final AptStagingContentAdapter adapter =
      new AptStagingContentAdapter(hosted, aptHosted);
  private final RepositoryRuntime runtime = new RepositoryRuntime(
      1L, "apt-hosted", RepositoryFormat.APT, RepositoryType.HOSTED, "apt-hosted", true,
      1L, "ALLOW", null, null, true, null, null, null, null, null, List.of());
  private final String path = "pool/main/d/demo/demo_1.0-1_amd64.deb";

  @Test
  void acceptsOnlyPackagePaths() {
    assertTrue(adapter.normalizePath(path).isPresent());
    assertTrue(adapter.normalizePath("dists/stable/Release").isEmpty());
  }

  @Test
  void promotionAndDeleteReplayTheAptWriter() {
    when(aptHosted.put(any(), any(), any(), any(), any(), any()))
        .thenReturn(MavenResponse.created());
    when(aptHosted.delete(any(), any(), any(), eq(false)))
        .thenReturn(MavenResponse.noBody(204));
    StagingItemRecord item = item();

    assertEquals("published", adapter.promote(
        runtime, item, () -> new ByteArrayInputStream(new byte[] {1}), "alice", "127.0.0.1")
        .orElseThrow());
    assertTrue(adapter.delete(runtime, path));
    verify(aptHosted).put(eq(runtime), eq(path), any(), any(), eq("alice"), eq("127.0.0.1"));
    verify(aptHosted).delete(runtime, path, "staging promotion", false);
  }

  private StagingItemRecord item() {
    return new StagingItemRecord(
        1L, 1L, 1L, RepositoryFormat.APT, path, new byte[32], "ADD", "STAGED",
        1L, 1L, "application/vnd.debian.binary-package", 1L, "2".repeat(64),
        "3".repeat(32), "1".repeat(40), path, null, null, Map.of(),
        Instant.now(), Instant.now());
  }
}

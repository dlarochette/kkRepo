package com.github.klboke.kkrepo.server.conda;

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

class CondaStagingContentAdapterTest {
  private final RawHostedService hosted = mock(RawHostedService.class);
  private final CondaService condaHosted = mock(CondaService.class);
  private final CondaStagingContentAdapter adapter =
      new CondaStagingContentAdapter(hosted, condaHosted);
  private final RepositoryRuntime runtime = new RepositoryRuntime(
      1L, "conda-hosted", RepositoryFormat.CONDA, RepositoryType.HOSTED, "conda-hosted",
      true, 1L, "ALLOW", null, null, true, null, null, null, null, null, List.of());
  private final String path = "noarch/demo-1.0-py_0.conda";

  @Test
  void acceptsOnlyPackagePaths() {
    assertTrue(adapter.normalizePath(path).isPresent());
    assertTrue(adapter.normalizePath("noarch/repodata.json").isEmpty());
  }

  @Test
  void promotionAndDeleteReplayTheCondaWriter() {
    when(condaHosted.put(any(), any(), any(), any(), any(), any()))
        .thenReturn(MavenResponse.created());
    when(condaHosted.delete(any(), any())).thenReturn(MavenResponse.noBody(204));
    StagingItemRecord item = item();
    assertEquals("published", adapter.promote(
        runtime, item, () -> new ByteArrayInputStream(new byte[] {1}), "alice", "127.0.0.1")
        .orElseThrow());
    assertTrue(adapter.delete(runtime, path));
    verify(condaHosted).put(eq(runtime), eq(path), any(), any(), eq("alice"), eq("127.0.0.1"));
    verify(condaHosted).delete(runtime, path);
  }

  private StagingItemRecord item() {
    return new StagingItemRecord(
        1L, 1L, 1L, RepositoryFormat.CONDA, path, new byte[32], "ADD", "STAGED",
        1L, 1L, "application/octet-stream", 1L, "2".repeat(64), "3".repeat(32),
        "1".repeat(40), path, null, null, Map.of(), Instant.now(), Instant.now());
  }
}

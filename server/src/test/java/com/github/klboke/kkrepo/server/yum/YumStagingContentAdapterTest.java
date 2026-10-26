package com.github.klboke.kkrepo.server.yum;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.StagingItemRecord;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.raw.RawHostedService;
import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class YumStagingContentAdapterTest {
  private final RawHostedService hosted = mock(RawHostedService.class);
  private final YumService yumHosted = mock(YumService.class);
  private final YumStagingContentAdapter adapter =
      new YumStagingContentAdapter(hosted, yumHosted);
  private final RepositoryRuntime runtime = new RepositoryRuntime(
      1L, "yum-hosted", RepositoryFormat.YUM, RepositoryType.HOSTED, "yum-hosted", true,
      1L, "ALLOW", null, null, true, null, null, null, null, null, List.of());
  private final String path = "Packages/d/demo-1.0-1.x86_64.rpm";

  @Test
  void normalizesRepositoryPaths() {
    assertEquals(path, adapter.normalizePath("/" + path).orElseThrow());
    assertTrue(adapter.normalizePath("../etc/passwd").isEmpty());
  }

  @Test
  void promotionAndDeleteReplayTheYumWriter() {
    when(yumHosted.put(any(), any(), any(), any(), any(), any()))
        .thenReturn(MavenResponse.noBody(200));
    when(yumHosted.delete(any(), any())).thenReturn(MavenResponse.noBody(204));
    StagingItemRecord item = item();

    assertEquals("published", adapter.promote(
        runtime, item, () -> new ByteArrayInputStream(new byte[] {1}), "alice", "127.0.0.1")
        .orElseThrow());
    assertTrue(adapter.delete(runtime, path));
    verify(yumHosted).put(eq(runtime), eq(path), any(), eq("application/x-rpm"),
        eq("alice"), eq("127.0.0.1"));
    verify(yumHosted).delete(runtime, path);
  }

  private StagingItemRecord item() {
    return new StagingItemRecord(
        1L, 1L, 1L, RepositoryFormat.YUM, path, new byte[32], "ADD", "STAGED",
        1L, 1L, "application/x-rpm", 1L, "2".repeat(64), "3".repeat(32), "1".repeat(40),
        path, null, null, Map.of(), Instant.now(), Instant.now());
  }

  private static AssetBlobRecord blob() {
    Instant now = Instant.now();
    return new AssetBlobRecord(
        1L, 1L, "blob", new byte[32], "objects/blob", new byte[32], "1".repeat(40),
        "2".repeat(64), "3".repeat(32), 1L, "application/x-rpm", "alice", "127.0.0.1",
        now, now, Map.of());
  }
}

package com.github.klboke.kkrepo.server.pub;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.StagingItemRecord;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PubStagingContentAdapterTest {
  private final PubHostedService pubHosted = mock(PubHostedService.class);
  private final PubStagingContentAdapter adapter =
      new PubStagingContentAdapter(pubHosted);
  private final RepositoryRuntime runtime = new RepositoryRuntime(
      1L, "pub-hosted", RepositoryFormat.PUB, RepositoryType.HOSTED, "pub-hosted", true,
      1L, "ALLOW", null, null, true, null, null, null, null, null, List.of());
  private final String path = "packages/demo-1.0.0.tar.gz";

  @Test
  void normalizesArchivePaths() {
    assertEquals(path, adapter.normalizePath("/" + path).orElseThrow());
    assertTrue(adapter.normalizePath("../demo").isEmpty());
  }

  @Test
  void promotionReplaysThePubArchiveWriter() {
    when(pubHosted.uploadArchive(any(), any(), any(), any(), any())).thenReturn(path);
    StagingItemRecord item = item();
    assertEquals("published", adapter.promote(
        runtime, item, () -> new ByteArrayInputStream(new byte[] {1}), "alice", "127.0.0.1")
        .orElseThrow());
    verify(pubHosted).uploadArchive(
        eq(runtime), any(), eq("alice"), eq("127.0.0.1"), eq("staging"));
  }

  @Test
  void preflightRejectsDeletesAndExistingArchives() {
    RepositoryRecord repository = new RepositoryRecord(
        1L, "pub-hosted", RepositoryFormat.PUB, RepositoryType.HOSTED, "pub-hosted", true,
        1L, null, null, null, null, "ALLOW", true, Map.of());
    assertTrue(adapter.validateItem(repository, item("DELETE"), null) != null);
    assertTrue(adapter.validateItem(repository, item("ADD"), mock(AssetRecord.class)) != null);
  }

  private StagingItemRecord item() {
    return item("ADD");
  }

  private StagingItemRecord item(String action) {
    return new StagingItemRecord(
        1L, 1L, 1L, RepositoryFormat.PUB, path, new byte[32], action, "STAGED",
        1L, 1L, "application/octet-stream", 1L, "2".repeat(64), "3".repeat(32),
        "1".repeat(40), null, null, null, Map.of(), Instant.now(), Instant.now());
  }
}

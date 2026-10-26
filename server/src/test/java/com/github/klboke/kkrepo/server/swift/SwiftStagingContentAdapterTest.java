package com.github.klboke.kkrepo.server.swift;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.StagingItemRecord;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.raw.RawHostedService;
import com.github.klboke.kkrepo.server.security.ForwardedHeaderPolicy;
import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SwiftStagingContentAdapterTest {
  private final RawHostedService hosted = mock(RawHostedService.class);
  private final SwiftService swiftHosted = mock(SwiftService.class);
  private final SwiftStagingContentAdapter adapter = new SwiftStagingContentAdapter(
      hosted, swiftHosted, mock(ForwardedHeaderPolicy.class));
  private final RepositoryRuntime runtime = new RepositoryRuntime(
      1L, "swift-hosted", RepositoryFormat.SWIFT, RepositoryType.HOSTED, "swift-hosted",
      true, 1L, "ALLOW", null, null, true, null, null, null, null, null, List.of());

  @Test
  void normalizesPublishPaths() {
    assertEquals("acme/demo/1.0.0", adapter.normalizePath("/acme/demo/1.0.0").orElseThrow());
    assertTrue(adapter.normalizePath("../demo").isEmpty());
  }

  @Test
  void plainStreamStagingIsRejectedBecauseSwiftRequiresMultipart() {
    assertThrows(com.github.klboke.kkrepo.server.maven.MavenExceptions.BadRequestException.class,
        () -> adapter.stage(runtime, "acme/demo/1.0.0",
            new ByteArrayInputStream(new byte[] {1}), "application/zip", "alice", "ip"));
  }

  @Test
  void preflightRejectsDeletes() {
    RepositoryRecord repository = new RepositoryRecord(
        1L, "swift-hosted", RepositoryFormat.SWIFT, RepositoryType.HOSTED, "swift-hosted",
        true, 1L, null, null, null, null, "ALLOW", true, Map.of());
    StagingItemRecord item = new StagingItemRecord(
        1L, 1L, 1L, RepositoryFormat.SWIFT, "swift/acme/demo/1.0.0.zip", new byte[32],
        "DELETE", "STAGED", 1L, 1L, "application/zip", 1L, "2".repeat(64),
        "3".repeat(32), "1".repeat(40), null, null, null, Map.of(), Instant.now(), Instant.now());
    assertNotNull(adapter.validateItem(repository, item, null));
  }
}

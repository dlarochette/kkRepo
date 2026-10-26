package com.github.klboke.kkrepo.server.cargo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.StagingItemRecord;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.raw.RawHostedService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CargoStagingContentAdapterTest {
  private final CargoStagingContentAdapter adapter = new CargoStagingContentAdapter(
      mock(RawHostedService.class), mock(CargoHostedService.class), new ObjectMapper());
  private final RepositoryRuntime runtime = new RepositoryRuntime(
      1L, "cargo-hosted", RepositoryFormat.CARGO, RepositoryType.HOSTED, "cargo-hosted",
      true, 1L, "ALLOW", null, null, true, null, null, null, null, null, List.of());

  @Test
  void recognizesThePublishEndpoint() {
    assertTrue(adapter.supportsPublication("api/v1/crates/new"));
    assertTrue(!adapter.supportsPublication("api/v1/crates/demo/1.0.0/download"));
    assertEquals("api/v1/crates/new", adapter.normalizePath("/api/v1/crates/new").orElseThrow());
  }

  @Test
  void preflightRejectsDeletesAndExistingVersions() {
    RepositoryRecord repository = new RepositoryRecord(
        1L, "cargo-hosted", RepositoryFormat.CARGO, RepositoryType.HOSTED, "cargo-hosted",
        true, 1L, null, null, null, null, "ALLOW", true, Map.of());
    assertNotNull(adapter.validateItem(repository, item("DELETE"), null));
    assertNotNull(adapter.validateItem(repository, item("ADD"), mock(
        com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord.class)));
  }

  private StagingItemRecord item(String action) {
    String path = "crates/demo/1.0.0/demo-1.0.0.crate";
    return new StagingItemRecord(
        1L, 1L, 1L, RepositoryFormat.CARGO, path, new byte[32], action, "STAGED",
        1L, 1L, "application/octet-stream", 1L, "2".repeat(64), "3".repeat(32),
        "1".repeat(40), null, null, null, Map.of("cargoPublishPath", "api/v1/crates/new"),
        Instant.now(), Instant.now());
  }
}

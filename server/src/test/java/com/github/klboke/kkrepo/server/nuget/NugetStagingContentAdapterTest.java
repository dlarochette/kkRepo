package com.github.klboke.kkrepo.server.nuget;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.StagingItemRecord;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.raw.RawHostedService;
import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class NugetStagingContentAdapterTest {
  private final RawHostedService hosted = mock(RawHostedService.class);
  private final NugetService nugetHosted = mock(NugetService.class);
  private final NugetStagingContentAdapter adapter =
      new NugetStagingContentAdapter(hosted, nugetHosted);
  private final RepositoryRuntime runtime = new RepositoryRuntime(
      1L, "nuget-hosted", RepositoryFormat.NUGET, RepositoryType.HOSTED, "nuget-hosted", true,
      1L, "ALLOW", null, null, true, null, null, null, null, null, List.of());
  private final String packagePath = "acme/demo/1.0.0/demo.1.0.0.nupkg";

  @Test
  void stageUsesThePackagePathDerivedFromTheUpload() throws Exception {
    when(nugetHosted.planPackage(any())).thenReturn(
        new NugetService.UploadPlan(packagePath, "demo", "1.0.0"));
    when(hosted.storeStagedBlobFile(
        eq(runtime), eq(packagePath), any(), any(), any(), any(), any()))
        .thenReturn(blob());
    NugetStagingContentAdapter.StagedUpload upload = adapter.stage(
        runtime, "api/v2/package", new ByteArrayInputStream(new byte[] {1}),
        "application/octet-stream", "alice", "127.0.0.1");
    assertEquals(packagePath, upload.logicalPath());
    assertEquals("api/v2/package", upload.attributes().get("nugetPublishPath"));
  }

  @Test
  void promotionReplaysTheNormalNugetPublishWriter() {
    when(nugetHosted.putPackage(any(), any(), any(), any(), any(), any()))
        .thenReturn(MavenResponse.created());
    StagingItemRecord item = item();
    assertEquals("published", adapter.promote(
        runtime, item, () -> new ByteArrayInputStream(new byte[] {1}), "alice", "127.0.0.1")
        .orElseThrow());
    verify(nugetHosted).putPackage(
        eq(runtime), eq("api/v2/package"), any(), any(), eq("alice"), eq("127.0.0.1"));
  }

  @Test
  void preflightRejectsDeletesAndExistingPackagePaths() {
    RepositoryRecord repository = new RepositoryRecord(
        1L, "nuget-hosted", RepositoryFormat.NUGET, RepositoryType.HOSTED, "nuget-hosted",
        true, 1L, null, null, null, null, "ALLOW", true, Map.of());
    assertNotNull(adapter.validateItem(repository, itemWithAction("DELETE"), null));
    assertNotNull(adapter.validateItem(
        repository, itemWithAction("ADD"), mock(AssetRecord.class)));
    assertTrue(adapter.normalizePath("api/v2/package").isPresent());
  }

  private StagingItemRecord item() {
    return itemWithAction("ADD");
  }

  private StagingItemRecord itemWithAction(String action) {
    return new StagingItemRecord(
        1L, 1L, 1L, RepositoryFormat.NUGET, packagePath, new byte[32], action, "STAGED",
        1L, 1L, "application/octet-stream", 1L, "2".repeat(64), "3".repeat(32),
        "1".repeat(40), null, null, null, Map.of("nugetPublishPath", "api/v2/package"),
        Instant.now(), Instant.now());
  }

  private static AssetBlobRecord blob() {
    Instant now = Instant.now();
    return new AssetBlobRecord(
        1L, 1L, "blob", new byte[32], "objects/blob", new byte[32], "1".repeat(40),
        "2".repeat(64), "3".repeat(32), 1L, "application/octet-stream", "alice",
        "127.0.0.1", now, now, Map.of());
  }
}

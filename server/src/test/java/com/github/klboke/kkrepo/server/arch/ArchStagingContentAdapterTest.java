package com.github.klboke.kkrepo.server.arch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.ComponentRecord;
import com.github.klboke.kkrepo.protocol.arch.ArchDesc;
import com.github.klboke.kkrepo.protocol.arch.ArchMediaTypes;
import com.github.klboke.kkrepo.protocol.arch.ArchPackageFilename;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.raw.RawHostedService;
import com.github.klboke.kkrepo.server.staging.StagingComponents;
import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ArchStagingContentAdapterTest {
  private final RawHostedService hosted = mock(RawHostedService.class);
  private final ArchPackageInspector inspector = mock(ArchPackageInspector.class);
  private final ArchAssetSupport assets = mock(ArchAssetSupport.class);
  private final ArchStagingContentAdapter adapter =
      new ArchStagingContentAdapter(hosted, inspector, assets);
  private final RepositoryRuntime runtime = new RepositoryRuntime(
      1L, "arch-hosted", RepositoryFormat.ARCH, RepositoryType.HOSTED, "arch-hosted", true,
      1L, "ALLOW", null, null, true, null, null, null, null, null, List.of());

  @Test
  void normalizesOnlyPackageAndPackageSignaturePaths() {
    assertEquals("demo-1.0.0-1-x86_64.pkg.tar.zst",
        adapter.normalizePath("demo-1.0.0-1-x86_64.pkg.tar.zst").orElseThrow());
    assertEquals("demo-1.0.0-1-x86_64.pkg.tar.zst.sig",
        adapter.normalizePath("demo-1.0.0-1-x86_64.pkg.tar.zst.sig").orElseThrow());
    assertTrue(adapter.normalizePath("core.db").isEmpty());
    assertTrue(adapter.normalizePath("core.db.sig").isEmpty());
    assertTrue(adapter.normalizePath("").isEmpty());
  }

  @Test
  void stagesAPackageWithItsPromotionComponentProjection() throws Exception {
    String fileName = "demo-1.0.0-1-x86_64.pkg.tar.zst";
    ArchPackageFilename parsed = ArchPackageFilename.parse(fileName);
    when(inspector.inspect(any(), eq(fileName), anyLong(), anyString(), anyString()))
        .thenReturn(new ArchPackageInspector.Inspected(parsed, desc(fileName)));
    ComponentRecord component = new ComponentRecord(
        null, 1L, RepositoryFormat.ARCH, null, "demo", "1.0.0-1@x86_64",
        ArchAssetSupport.KIND, new byte[32], Map.of("archName", "demo"), Instant.now());
    when(assets.component(any(), any(), anyString(), anyString(), anyString()))
        .thenReturn(component);
    AssetBlobRecord blob = blob(11L, ArchMediaTypes.PACKAGE_ZSTD);
    when(hosted.storeStagedBlobFile(
        any(), eq(fileName), any(), eq(ArchMediaTypes.PACKAGE_ZSTD), any(), any(), any()))
        .thenReturn(blob);

    ArchStagingContentAdapter.StagedUpload upload = adapter.stage(
        runtime, fileName, new ByteArrayInputStream(new byte[] {1}), "application/octet-stream",
        "alice", "127.0.0.1");

    assertEquals(fileName, upload.logicalPath());
    assertEquals(blob, upload.blob());
    assertFalse(StagingComponents.unindexed(upload.attributes()));
    assertTrue(upload.attributes().containsKey(StagingComponents.ATTRIBUTE));
  }

  @Test
  void stagesASignatureAsAnUnindexedAsset() throws Exception {
    String fileName = "demo-1.0.0-1-x86_64.pkg.tar.zst.sig";
    AssetBlobRecord blob = blob(12L, ArchMediaTypes.SIGNATURE);
    when(hosted.storeStagedBlobFile(
        any(), eq(fileName), any(), eq(ArchMediaTypes.SIGNATURE), any(), any(), any()))
        .thenReturn(blob);

    ArchStagingContentAdapter.StagedUpload upload = adapter.stage(
        runtime, fileName, new ByteArrayInputStream(new byte[] {1}), null, "alice", "127.0.0.1");

    assertEquals(fileName, upload.logicalPath());
    assertTrue(StagingComponents.unindexed(upload.attributes()));
  }

  @Test
  void deletingAPackageAlsoRemovesItsSignature() {
    String fileName = "demo-1.0.0-1-x86_64.pkg.tar.zst";
    when(hosted.deleteInternal(runtime, fileName)).thenReturn(MavenResponse.noBody(204));
    when(hosted.deleteInternal(runtime, fileName + ".sig")).thenReturn(MavenResponse.noBody(204));

    assertTrue(adapter.delete(runtime, fileName));

    verify(hosted).deleteInternal(runtime, fileName);
    verify(hosted).deleteInternal(runtime, fileName + ".sig");
  }

  private static AssetBlobRecord blob(long id, String contentType) {
    Instant now = Instant.now();
    return new AssetBlobRecord(
        id, 1L, "blob-" + id, new byte[32], "objects/" + id, new byte[32],
        "1".repeat(40), "2".repeat(64), "3".repeat(32), 1L, contentType, "alice",
        "127.0.0.1", now, now, Map.of());
  }

  private static ArchDesc desc(String fileName) {
    return new ArchDesc(
        fileName, "demo", "demo", "1.0.0-1", "Demo", 1L, 2L, "md5", "sha256", null, null,
        List.of("MIT"), "x86_64", 1L, "packager", List.of(), List.of(), List.of(), List.of(),
        List.of(), List.of(), List.of(), List.of());
  }
}

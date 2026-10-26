package com.github.klboke.kkrepo.server.openvsx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.StagingItemRecord;
import com.github.klboke.kkrepo.protocol.openvsx.OpenVsxProtocol;
import com.github.klboke.kkrepo.server.maven.MavenExceptions;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.raw.RawHostedService;
import com.github.klboke.kkrepo.server.staging.StagingComponents;
import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class OpenVsxStagingContentAdapterTest {
  private final RawHostedService hosted = mock(RawHostedService.class);
  private final OpenVsxVsixInspector inspector = mock(OpenVsxVsixInspector.class);
  private final OpenVsxAssetSupport assets = mock(OpenVsxAssetSupport.class);
  private final OpenVsxStagingContentAdapter adapter =
      new OpenVsxStagingContentAdapter(hosted, inspector, assets, 1024 * 1024);
  private final RepositoryRuntime runtime = new RepositoryRuntime(
      1L, "openvsx-hosted", RepositoryFormat.OPENVSX, RepositoryType.HOSTED,
      "openvsx-hosted", true, 1L, "ALLOW", null, null, true, null, null, null, null, null,
      List.of());

  @Test
  void normalizesFileRoutesToTheirStoragePathAndRecognizesPublication() {
    assertEquals("acme/demo/1.0.0/demo-1.0.0.vsix",
        adapter.normalizePath("api/acme/demo/1.0.0/file/demo-1.0.0.vsix").orElseThrow());
    assertTrue(adapter.normalizePath("api/acme/demo").isEmpty());
    assertTrue(adapter.supportsPublication("api/-/publish"));
    assertFalse(adapter.supportsPublication("api/acme/demo"));
  }

  @Test
  void stagesAVsixWithItsPromotionComponentProjection() throws Exception {
    OpenVsxVsixInspector.Inspected inspected = inspected();
    when(inspector.inspect(any(), any())).thenReturn(inspected);
    when(assets.find(any(), any())).thenReturn(Optional.empty());
    AssetBlobRecord blob = blob();
    when(hosted.storeStagedBlobFile(
        any(), eq("acme/demo/1.0.0/demo-1.0.0.vsix"), any(),
        eq(OpenVsxProtocol.VSIX_MEDIA_TYPE), any(), any(), any()))
        .thenReturn(blob);

    OpenVsxStagingContentAdapter.StagedUpload upload = adapter.stage(
        runtime, "api/-/publish", new ByteArrayInputStream(new byte[] {1}), null,
        "alice", "127.0.0.1");

    assertEquals("acme/demo/1.0.0/demo-1.0.0.vsix", upload.logicalPath());
    assertEquals(OpenVsxProtocol.VSIX_MEDIA_TYPE, upload.contentType());
    assertNotNull(upload.attributes().get(StagingComponents.ATTRIBUTE));
  }

  @Test
  void rejectsAnAlreadyPublishedExtensionVersion() throws Exception {
    when(inspector.inspect(any(), any())).thenReturn(inspected());
    when(assets.find(any(), any())).thenReturn(Optional.of(mock(AssetRecord.class)));

    assertThrows(MavenExceptions.BadRequestException.class, () -> adapter.stage(
        runtime, "api/-/publish", new ByteArrayInputStream(new byte[] {1}), null,
        "alice", "127.0.0.1"));
  }

  @Test
  void preflightRejectsAnExistingLiveVersion() {
    RepositoryRecord repository = new RepositoryRecord(
        1L, "openvsx-hosted", RepositoryFormat.OPENVSX, RepositoryType.HOSTED,
        "openvsx-hosted", true, 1L, null, null, null, null, "ALLOW", true, Map.of());
    StagingItemRecord item = new StagingItemRecord(
        1L, 1L, 1L, RepositoryFormat.OPENVSX, "acme/demo/1.0.0/demo-1.0.0.vsix",
        new byte[32], "ADD", "STAGED", 1L, 1L, OpenVsxProtocol.VSIX_MEDIA_TYPE, 1L,
        "2".repeat(64), "3".repeat(32), "1".repeat(40), null, null, null, Map.of(),
        Instant.now(), Instant.now());

    assertEquals("Extension version already exists",
        adapter.validateItem(repository, item, mock(AssetRecord.class)));
  }

  private static OpenVsxVsixInspector.Inspected inspected() {
    return new OpenVsxVsixInspector.Inspected(
        "acme", "demo", "1.0.0", null, false, "demo-1.0.0.vsix", "Demo", "Demo extension",
        Map.of("name", "demo", "version", "1.0.0", "publisher", "acme"));
  }

  private static AssetBlobRecord blob() {
    Instant now = Instant.now();
    return new AssetBlobRecord(
        1L, 1L, "blob", new byte[32], "objects/blob", new byte[32],
        "1".repeat(40), "2".repeat(64), "3".repeat(32), 1L,
        OpenVsxProtocol.VSIX_MEDIA_TYPE, "alice", "127.0.0.1", now, now, Map.of());
  }
}

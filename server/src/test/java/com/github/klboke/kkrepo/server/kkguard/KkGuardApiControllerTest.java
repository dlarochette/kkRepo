package com.github.klboke.kkrepo.server.kkguard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.github.klboke.kkrepo.server.kkguard.KkGuardCatalogService.ApplicationInput;
import com.github.klboke.kkrepo.server.kkguard.KkGuardCatalogService.ApplicationView;
import com.github.klboke.kkrepo.server.kkguard.KkGuardCatalogService.OrganizationView;
import com.github.klboke.kkrepo.server.kkguard.KkGuardCatalogService.TagView;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

class KkGuardApiControllerTest {
  private static final String ORG = "11111111-1111-1111-1111-111111111111";
  private static final String APP = "22222222-2222-2222-2222-222222222222";

  private KkGuardCatalogService catalog;
  private KkGuardScanService scans;
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    catalog = mock(KkGuardCatalogService.class);
    scans = mock(KkGuardScanService.class);
    mvc = MockMvcBuilders.standaloneSetup(new KkGuardApiController(catalog, scans)).build();
  }

  @Test
  void organizationsListAndCreationUseIqShapes() throws Exception {
    when(catalog.listOrganizations()).thenReturn(List.of(
        new OrganizationView(ORG, "Payments", "payment org")));
    when(catalog.createOrganization(any())).thenReturn(
        new OrganizationView(ORG, "Payments", "payment org"));

    var listed = mvc.perform(get("/api/v2/organizations")).andReturn().getResponse();
    assertEquals(200, listed.getStatus());
    assertTrue(listed.getContentAsString().contains(
        "\"id\":\"" + ORG + "\",\"name\":\"Payments\",\"description\":\"payment org\""));

    var created = mvc.perform(post("/api/v2/organizations")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"Payments\",\"description\":\"payment org\"}"))
        .andReturn().getResponse();
    assertEquals(200, created.getStatus());
    assertTrue(created.getContentAsString().contains("\"name\":\"Payments\""));
    verify(catalog).createOrganization(eq(new KkGuardCatalogService.OrganizationInput(
        "Payments", "payment org")));

    assertEquals(204, mvc.perform(delete("/api/v2/organizations/" + ORG))
        .andReturn().getResponse().getStatus());
    verify(catalog).deleteOrganization(ORG);
  }

  @Test
  void applicationsExposeNexusIqFields() throws Exception {
    ApplicationView view = new ApplicationView(
        APP, "orders-api", "Orders API", ORG, "jdoe",
        List.of(new TagView("tag-uuid", "payments", "payment services")));
    when(catalog.listApplications()).thenReturn(List.of(view));
    when(catalog.listApplicationsByOrganization(ORG)).thenReturn(List.of(view));
    when(catalog.application(APP)).thenReturn(view);
    when(catalog.createApplication(any())).thenReturn(view);

    var listed = mvc.perform(get("/api/v2/applications")).andReturn().getResponse();
    assertEquals(200, listed.getStatus());
    String body = listed.getContentAsString();
    assertTrue(body.contains("\"id\":\"" + APP + "\""));
    assertTrue(body.contains("\"publicId\":\"orders-api\""));
    assertTrue(body.contains("\"organizationId\":\"" + ORG + "\""));
    assertTrue(body.contains("\"contactUserName\":\"jdoe\""));
    assertTrue(body.contains("\"applicationTags\":[{\"id\":\"tag-uuid\",\"name\":\"payments\""));

    assertEquals(200, mvc.perform(get("/api/v2/applications/organization/" + ORG))
        .andReturn().getResponse().getStatus());
    assertEquals(200, mvc.perform(get("/api/v2/applications/" + APP))
        .andReturn().getResponse().getStatus());

    var created = mvc.perform(post("/api/v2/applications")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"publicId":"orders-api","name":"Orders API","organizationId":"%s",
                 "contactUserName":"jdoe","applicationTags":[{"name":"payments"}]}
                """.formatted(ORG)))
        .andReturn().getResponse();
    assertEquals(200, created.getStatus());
    verify(catalog).createApplication(eq(new ApplicationInput(
        "orders-api", "Orders API", ORG, "jdoe", List.of(new TagView(null, "payments", null)))));

    var updated = mvc.perform(put("/api/v2/applications/" + APP)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"publicId\":\"orders-api\",\"name\":\"Orders\",\"organizationId\":\""
                + ORG + "\"}"))
        .andReturn().getResponse();
    assertEquals(204, updated.getStatus());
    assertEquals("", updated.getContentAsString());

    assertEquals(204, mvc.perform(delete("/api/v2/applications/" + APP))
        .andReturn().getResponse().getStatus());
    verify(catalog).deleteApplication(APP);
  }

  @Test
  void missingCatalogEntriesPropagateNotFound() throws Exception {
    when(catalog.organization(ORG))
        .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Organization not found"));
    when(catalog.application(APP))
        .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Application not found"));

    assertEquals(404, mvc.perform(get("/api/v2/organizations/" + ORG))
        .andReturn().getResponse().getStatus());
    assertEquals(404, mvc.perform(get("/api/v2/applications/" + APP))
        .andReturn().getResponse().getStatus());
  }

  @Test
  void invalidPayloadsKeepTheirBadRequestStatus() throws Exception {
    when(catalog.createApplication(any()))
        .thenThrow(new ResponseStatusException(HttpStatus.BAD_REQUEST, "publicId is required"));

    assertEquals(400, mvc.perform(post("/api/v2/applications")
            .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Orders\"}"))
        .andReturn().getResponse().getStatus());
  }

  @Test
  void scanSubmissionStatusAndReportsExposeThePipelineState() throws Exception {
    var status = new KkGuardScanService.ScanStatusView(
        "scan-1", APP, "orders-api", "build", "SCANNING", "build", 0, 0, null,
        java.time.Instant.parse("2026-01-01T00:00:00Z"), null, null);
    when(scans.submit(eq(APP), eq("build"), any(), any())).thenReturn(status);
    when(scans.status(APP, "build")).thenReturn(status);
    when(scans.report(APP, "scan-1")).thenReturn(new KkGuardScanService.ReportView(
        status, List.of(), List.of(), List.of()));

    var submitted = mvc.perform(post("/api/v2/scan/applications/" + APP + "/sources/build")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"bomFormat\":\"CycloneDX\",\"specVersion\":\"1.5\",\"components\":[]}"))
        .andReturn().getResponse();
    assertEquals(200, submitted.getStatus());
    assertTrue(submitted.getContentAsString().contains("\"scanId\":\"scan-1\""));
    verify(scans).submit(eq(APP), eq("build"), eq("application/json"), any());

    var polled = mvc.perform(get("/api/v2/scan/applications/" + APP + "/sources/build/status"))
        .andReturn().getResponse();
    assertEquals(200, polled.getStatus());
    assertTrue(polled.getContentAsString().contains("\"status\":\"SCANNING\""));

    var reported = mvc.perform(get("/api/v2/applications/" + APP + "/reports/scan-1"))
        .andReturn().getResponse();
    assertEquals(200, reported.getStatus());
    assertTrue(reported.getContentAsString().contains("\"components\":[]"));
  }
}

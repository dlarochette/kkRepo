package com.github.klboke.kkrepo.server.kkguard;

import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Nexus IQ-compatible {@code /api/v2} catalogue surface for kkGuard.
 *
 * <p>Status codes and payload shapes follow the IQ clients documented in
 * {@code docs/en/dev/kkguard-design.md}. Authorization is enforced by
 * {@code SecurityManagementFilter} with {@code nexus:lifecycle:read} and
 * {@code nexus:lifecycle:update}.
 */
@RestController
@RequestMapping("/api/v2")
public class KkGuardApiController {
  private final KkGuardCatalogService catalog;

  public KkGuardApiController(KkGuardCatalogService catalog) {
    this.catalog = catalog;
  }

  @GetMapping("/organizations")
  public List<KkGuardCatalogService.OrganizationView> organizations() {
    return catalog.listOrganizations();
  }

  @PostMapping("/organizations")
  public KkGuardCatalogService.OrganizationView createOrganization(
      @RequestBody(required = false) KkGuardCatalogService.OrganizationInput input) {
    return catalog.createOrganization(input);
  }

  @GetMapping("/organizations/{id}")
  public KkGuardCatalogService.OrganizationView organization(@PathVariable("id") String id) {
    return catalog.organization(id);
  }

  @DeleteMapping("/organizations/{id}")
  public ResponseEntity<Void> deleteOrganization(@PathVariable("id") String id) {
    catalog.deleteOrganization(id);
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/applications")
  public List<KkGuardCatalogService.ApplicationView> applications() {
    return catalog.listApplications();
  }

  @PostMapping("/applications")
  public KkGuardCatalogService.ApplicationView createApplication(
      @RequestBody(required = false) KkGuardCatalogService.ApplicationInput input) {
    return catalog.createApplication(input);
  }

  @GetMapping("/applications/organization/{organizationId}")
  public List<KkGuardCatalogService.ApplicationView> applicationsByOrganization(
      @PathVariable("organizationId") String organizationId) {
    return catalog.listApplicationsByOrganization(organizationId);
  }

  @GetMapping("/applications/{id}")
  public KkGuardCatalogService.ApplicationView application(@PathVariable("id") String id) {
    return catalog.application(id);
  }

  @PutMapping("/applications/{id}")
  public ResponseEntity<Void> updateApplication(
      @PathVariable("id") String id,
      @RequestBody(required = false) KkGuardCatalogService.ApplicationInput input) {
    catalog.updateApplication(id, input);
    return ResponseEntity.noContent().build();
  }

  @DeleteMapping("/applications/{id}")
  public ResponseEntity<Void> deleteApplication(@PathVariable("id") String id) {
    catalog.deleteApplication(id);
    return ResponseEntity.noContent().build();
  }
}

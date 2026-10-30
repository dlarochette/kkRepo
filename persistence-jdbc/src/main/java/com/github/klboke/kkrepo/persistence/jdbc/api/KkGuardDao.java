package com.github.klboke.kkrepo.persistence.jdbc.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Durable state for the kkGuard application scanning API. */
public interface KkGuardDao {
  record Organization(
      long id, String externalId, String name, String description,
      Instant createdAt, Instant updatedAt) {}

  record Application(
      long id, long organizationId, String externalId, String publicId, String name,
      Long repositoryId, Instant createdAt, Instant updatedAt) {}

  record Scan(
      long id, long applicationId, String scanId, String status, String stage, String sourceId,
      String sbomSha256, String reportSha256, int componentCount, int violationCount,
      String error, Instant createdAt, Instant startedAt, Instant completedAt) {}

  record ScanComponent(
      long scanId, String ref, String purl, String name, String version,
      String license, String highestSeverity) {}

  record Finding(
      long scanId, String componentRef, String vulnerabilityId, String severity,
      BigDecimal cvssScore, String source, String description, String url) {}

  record Policy(
      long id, long organizationId, String externalId, String publicId, String name,
      int threatLevel, Map<String, Object> constraints, Instant createdAt, Instant updatedAt) {}

  record Violation(
      long scanId, long policyId, String componentRef, int threatLevel,
      Map<String, Object> constraint) {}

  List<Organization> listOrganizations();

  Optional<Organization> findOrganization(long id);

  Optional<Organization> findOrganizationByExternalId(String externalId);

  long insertOrganization(String externalId, String name, String description, Instant now);

  boolean updateOrganization(long id, String name, String description, Instant now);

  boolean deleteOrganization(long id);

  List<Application> listApplications();

  List<Application> listApplicationsByOrganization(long organizationId);

  Optional<Application> findApplication(long id);

  Optional<Application> findApplicationByExternalId(String externalId);

  Optional<Application> findApplicationByPublicId(String publicId);

  long insertApplication(
      long organizationId, String externalId, String publicId, String name, Long repositoryId,
      Instant now);

  boolean updateApplication(
      long id, long organizationId, String externalId, String publicId, String name,
      Long repositoryId, Instant now);

  boolean deleteApplication(long id);

  long insertScan(Scan scan);

  Optional<Scan> findScan(long id);

  Optional<Scan> findScanByScanId(String scanId);

  Optional<Scan> lockScanByScanId(String scanId);

  boolean updateScan(
      long id, String status, String stage, String error, int componentCount, int violationCount,
      String sbomSha256, String reportSha256, Instant startedAt, Instant completedAt);

  void replaceScanComponents(long scanId, List<ScanComponent> components);

  void replaceScanFindings(long scanId, List<Finding> findings);

  List<ScanComponent> listScanComponents(long scanId);

  List<Finding> listScanFindings(long scanId);

  List<Policy> listPolicies(long organizationId);

  Optional<Policy> findPolicy(long id);

  Optional<Policy> findPolicyByPublicId(long organizationId, String publicId);

  long insertPolicy(
      long organizationId, String externalId, String publicId, String name, int threatLevel,
      Map<String, Object> constraints, Instant now);

  boolean updatePolicy(
      long id, String externalId, String publicId, String name, int threatLevel,
      Map<String, Object> constraints, Instant now);

  boolean deletePolicy(long id);

  void replaceViolations(long scanId, List<Violation> violations);

  List<Violation> listViolations(long scanId);
}

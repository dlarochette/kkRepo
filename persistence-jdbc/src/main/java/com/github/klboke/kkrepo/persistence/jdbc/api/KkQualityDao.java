package com.github.klboke.kkrepo.persistence.jdbc.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Durable state for the kkQuality code-quality service. */
public interface KkQualityDao {
  record Project(
      long id, String externalId, String key, String name, String organization, String visibility,
      Instant createdAt, Instant updatedAt) {}

  record Branch(long id, long projectId, String name, boolean main, Instant createdAt) {}

  record Analysis(
      long id, long projectId, Long branchId, String analysisId, String status, String revision,
      String sarifSha256, int issueCount, Instant createdAt, Instant startedAt,
      Instant completedAt, String error) {}

  record Rule(
      long id, String engine, String ruleKey, String name, String severity, String language,
      String description, String remediation, Instant createdAt, Instant updatedAt) {}

  record Issue(
      long id, long analysisId, long projectId, Long branchId, String issueKey, String filePath,
      Integer line, String engine, String ruleKey, String severity, String issueType,
      String message, String status, String assignee, Instant createdAt, Instant updatedAt) {}

  record QualityGate(
      long id, Long projectId, String externalId, String name,
      List<Map<String, Object>> conditions, boolean isDefault,
      Instant createdAt, Instant updatedAt) {}

  record Measure(long analysisId, String metricKey, BigDecimal valueNum, String valueText) {}

  List<Project> listProjects();

  Optional<Project> findProject(long id);

  Optional<Project> findProjectByKey(String key);

  Optional<Project> findProjectByExternalId(String externalId);

  long insertProject(
      String externalId, String key, String name, String organization, String visibility,
      Instant now);

  boolean updateProject(
      long id, String key, String name, String organization, String visibility, Instant now);

  boolean deleteProject(long id);

  Optional<Branch> findBranch(long projectId, String name);

  long insertBranch(long projectId, String name, boolean main, Instant now);

  long insertAnalysis(Analysis analysis);

  Optional<Analysis> findAnalysis(long id);

  Optional<Analysis> findAnalysisByExternalId(String analysisId);

  Optional<Analysis> findLatestAnalysis(long projectId, Long branchId);

  boolean updateAnalysis(
      long id, String status, int issueCount, String sarifSha256, String error,
      Instant startedAt, Instant completedAt);

  void replaceIssues(long analysisId, List<Issue> issues);

  List<Issue> listIssues(
      long projectId, String status, String ruleKey, String severity, int limit, int offset);

  long countIssues(long projectId, String status, String ruleKey, String severity);

  boolean updateIssue(String issueKey, String status, String assignee, Instant now);

  void upsertRule(
      String engine, String ruleKey, String name, String severity, String language,
      String description, String remediation, Instant now);

  List<Rule> listRules(String engine, String query, int limit, int offset);

  long countRules(String engine, String query);

  long insertQualityGate(
      Long projectId, String externalId, String name,
      List<Map<String, Object>> conditions, boolean isDefault, Instant now);

  List<QualityGate> listQualityGates(Long projectId);

  Optional<QualityGate> findDefaultQualityGate();

  boolean updateQualityGate(
      long id, String name, List<Map<String, Object>> conditions, boolean isDefault, Instant now);

  boolean deleteQualityGate(long id);

  void replaceMeasures(long analysisId, List<Measure> measures);

  List<Measure> listMeasures(long analysisId);
}

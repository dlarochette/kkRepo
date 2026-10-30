package com.github.klboke.kkrepo.persistence.jdbc.internal;

import com.fasterxml.jackson.core.type.TypeReference;
import com.github.klboke.kkrepo.persistence.jdbc.api.KkQualityDao;
import com.github.klboke.kkrepo.persistence.jdbc.internal.support.JdbcInserts;
import com.github.klboke.kkrepo.persistence.jdbc.internal.support.JsonColumns;
import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcKkQualityDao implements KkQualityDao {
  private static final TypeReference<List<Map<String, Object>>> CONDITIONS =
      new TypeReference<>() {};
  private final JdbcTemplate jdbc;
  private final JsonColumns json;

  public JdbcKkQualityDao(JdbcTemplate jdbc, JsonColumns json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  private final RowMapper<Project> projects = (rs, n) -> new Project(
      rs.getLong("id"), rs.getString("external_id"), rs.getString("project_key"),
      rs.getString("name"), rs.getString("organization"), rs.getString("visibility"),
      instant(rs, "created_at"), instant(rs, "updated_at"));

  private final RowMapper<Branch> branches = (rs, n) -> new Branch(
      rs.getLong("id"), rs.getLong("project_id"), rs.getString("name"),
      rs.getBoolean("is_main"), instant(rs, "created_at"));

  private final RowMapper<Analysis> analyses = (rs, n) -> new Analysis(
      rs.getLong("id"), rs.getLong("project_id"), nullableLong(rs, "branch_id"),
      rs.getString("analysis_id"), rs.getString("status"), rs.getString("revision"),
      rs.getString("sarif_sha256"), rs.getInt("issue_count"), instant(rs, "created_at"),
      instant(rs, "started_at"), instant(rs, "completed_at"), rs.getString("error"));

  private final RowMapper<Rule> rules = (rs, n) -> new Rule(
      rs.getLong("id"), rs.getString("engine"), rs.getString("rule_key"),
      rs.getString("name"), rs.getString("severity"), rs.getString("language"),
      rs.getString("description"), rs.getString("remediation"),
      instant(rs, "created_at"), instant(rs, "updated_at"));

  private final RowMapper<Issue> issues = (rs, n) -> new Issue(
      rs.getLong("id"), rs.getLong("analysis_id"), rs.getLong("project_id"),
      nullableLong(rs, "branch_id"), rs.getString("issue_key"), rs.getString("file_path"),
      nullableInt(rs, "line"), rs.getString("engine"), rs.getString("rule_key"),
      rs.getString("severity"), rs.getString("issue_type"), rs.getString("message"),
      rs.getString("status"), rs.getString("assignee"), instant(rs, "created_at"),
      instant(rs, "updated_at"));

  private final RowMapper<QualityGate> gates = (rs, n) -> new QualityGate(
      rs.getLong("id"), nullableLong(rs, "project_id"), rs.getString("external_id"),
      rs.getString("name"), readConditions(rs.getString("conditions_json")),
      rs.getBoolean("is_default"), instant(rs, "created_at"), instant(rs, "updated_at"));

  private final RowMapper<Measure> measures = (rs, n) -> new Measure(
      rs.getLong("analysis_id"), rs.getString("metric_key"),
      rs.getBigDecimal("value_num"), rs.getString("value_text"));

  @Override
  public List<Project> listProjects() {
    return jdbc.query("SELECT * FROM kkq_project ORDER BY name, id", projects);
  }

  @Override
  public Optional<Project> findProject(long id) {
    return jdbc.query("SELECT * FROM kkq_project WHERE id = ?", projects, id).stream().findFirst();
  }

  @Override
  public Optional<Project> findProjectByKey(String key) {
    return jdbc.query("SELECT * FROM kkq_project WHERE project_key = ?", projects, key)
        .stream().findFirst();
  }

  @Override
  public Optional<Project> findProjectByExternalId(String externalId) {
    return jdbc.query("SELECT * FROM kkq_project WHERE external_id = ?", projects, externalId)
        .stream().findFirst();
  }

  @Override
  public long insertProject(
      String externalId, String key, String name, String organization, String visibility,
      Instant now) {
    return JdbcInserts.insert(jdbc, """
        INSERT INTO kkq_project
          (external_id, project_key, name, organization, visibility, created_at, updated_at)
        VALUES (?, ?, ?, ?, ?, ?, ?)
        """, ps -> {
      ps.setString(1, externalId);
      ps.setString(2, key);
      ps.setString(3, name);
      ps.setString(4, organization);
      ps.setString(5, visibility);
      ps.setTimestamp(6, Timestamp.from(now));
      ps.setTimestamp(7, Timestamp.from(now));
    });
  }

  @Override
  public boolean updateProject(
      long id, String key, String name, String organization, String visibility, Instant now) {
    return jdbc.update("""
        UPDATE kkq_project SET project_key = ?, name = ?, organization = ?, visibility = ?,
          updated_at = ? WHERE id = ?
        """, key, name, organization, visibility, Timestamp.from(now), id) > 0;
  }

  @Override
  public boolean deleteProject(long id) {
    return jdbc.update("DELETE FROM kkq_project WHERE id = ?", id) > 0;
  }

  @Override
  public Optional<Branch> findBranch(long projectId, String name) {
    return jdbc.query(
        "SELECT * FROM kkq_branch WHERE project_id = ? AND name = ?", branches, projectId, name)
        .stream().findFirst();
  }

  @Override
  public long insertBranch(long projectId, String name, boolean main, Instant now) {
    return JdbcInserts.insert(jdbc, """
        INSERT INTO kkq_branch (project_id, name, is_main, created_at) VALUES (?, ?, ?, ?)
        """, ps -> {
      ps.setLong(1, projectId);
      ps.setString(2, name);
      ps.setBoolean(3, main);
      ps.setTimestamp(4, Timestamp.from(now));
    });
  }

  @Override
  public long insertAnalysis(Analysis analysis) {
    return JdbcInserts.insert(jdbc, """
        INSERT INTO kkq_analysis
          (project_id, branch_id, analysis_id, status, revision, sarif_sha256, issue_count,
           created_at, started_at, completed_at, error)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """, ps -> {
      ps.setLong(1, analysis.projectId());
      bindLong(ps, 2, analysis.branchId());
      ps.setString(3, analysis.analysisId());
      ps.setString(4, analysis.status());
      ps.setString(5, analysis.revision());
      ps.setString(6, analysis.sarifSha256());
      ps.setInt(7, analysis.issueCount());
      ps.setTimestamp(8, Timestamp.from(
          analysis.createdAt() == null ? Instant.now() : analysis.createdAt()));
      bindInstant(ps, 9, analysis.startedAt());
      bindInstant(ps, 10, analysis.completedAt());
      ps.setString(11, analysis.error());
    });
  }

  @Override
  public Optional<Analysis> findAnalysis(long id) {
    return jdbc.query("SELECT * FROM kkq_analysis WHERE id = ?", analyses, id)
        .stream().findFirst();
  }

  @Override
  public Optional<Analysis> findAnalysisByExternalId(String analysisId) {
    return jdbc.query("SELECT * FROM kkq_analysis WHERE analysis_id = ?", analyses, analysisId)
        .stream().findFirst();
  }

  @Override
  public Optional<Analysis> findLatestAnalysis(long projectId, Long branchId) {
    if (branchId == null) {
      return jdbc.query("""
          SELECT * FROM kkq_analysis WHERE project_id = ? ORDER BY created_at DESC, id DESC
          """, analyses, projectId).stream().findFirst();
    }
    return jdbc.query("""
        SELECT * FROM kkq_analysis
        WHERE project_id = ? AND branch_id = ?
        ORDER BY created_at DESC, id DESC
        """, analyses, projectId, branchId).stream().findFirst();
  }

  @Override
  public boolean updateAnalysis(
      long id, String status, int issueCount, String sarifSha256, String error,
      Instant startedAt, Instant completedAt) {
    return jdbc.update("""
        UPDATE kkq_analysis SET status = ?, issue_count = ?, sarif_sha256 = COALESCE(?, sarif_sha256),
          error = ?, started_at = COALESCE(?, started_at), completed_at = COALESCE(?, completed_at)
        WHERE id = ?
        """, status, issueCount, sarifSha256, error,
        startedAt == null ? null : Timestamp.from(startedAt),
        completedAt == null ? null : Timestamp.from(completedAt), id) > 0;
  }

  @Override
  public void replaceIssues(long analysisId, List<Issue> values) {
    jdbc.update("DELETE FROM kkq_issue WHERE analysis_id = ?", analysisId);
    for (Issue issue : values) {
      jdbc.update("""
          INSERT INTO kkq_issue
            (analysis_id, project_id, branch_id, issue_key, file_path, line, engine, rule_key,
             severity, issue_type, message, status, assignee, created_at, updated_at)
          VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
          """, analysisId, issue.projectId(), issue.branchId(), issue.issueKey(),
          issue.filePath(), issue.line(), issue.engine(), issue.ruleKey(), issue.severity(),
          issue.issueType(), issue.message(), issue.status(),
          issue.assignee() == null ? null : issue.assignee(),
          Timestamp.from(issue.createdAt() == null ? Instant.now() : issue.createdAt()),
          Timestamp.from(issue.updatedAt() == null ? Instant.now() : issue.updatedAt()));
    }
  }

  @Override
  public List<Issue> listIssues(
      long projectId, String status, String ruleKey, String severity, int limit, int offset) {
    List<Object> args = new ArrayList<>();
    String where = issueFilter(projectId, status, ruleKey, severity, args);
    args.add(Math.max(1, Math.min(limit, 500)));
    args.add(Math.max(0, offset));
    return jdbc.query("SELECT * FROM kkq_issue " + where
        + " ORDER BY updated_at DESC, id DESC LIMIT ? OFFSET ?", issues, args.toArray());
  }

  @Override
  public long countIssues(long projectId, String status, String ruleKey, String severity) {
    List<Object> args = new ArrayList<>();
    String where = issueFilter(projectId, status, ruleKey, severity, args);
    Long count = jdbc.queryForObject(
        "SELECT COUNT(*) FROM kkq_issue " + where, Long.class, args.toArray());
    return count == null ? 0 : count;
  }

  private static String issueFilter(
      long projectId, String status, String ruleKey, String severity, List<Object> args) {
    StringBuilder where = new StringBuilder("WHERE project_id = ?");
    args.add(projectId);
    if (status != null && !status.isBlank()) {
      where.append(" AND status = ?");
      args.add(status);
    }
    if (ruleKey != null && !ruleKey.isBlank()) {
      where.append(" AND rule_key = ?");
      args.add(ruleKey);
    }
    if (severity != null && !severity.isBlank()) {
      where.append(" AND severity = ?");
      args.add(severity);
    }
    return where.toString();
  }

  @Override
  public boolean updateIssue(String issueKey, String status, String assignee, Instant now) {
    return jdbc.update("""
        UPDATE kkq_issue SET status = COALESCE(?, status), assignee = COALESCE(?, assignee),
          updated_at = ? WHERE issue_key = ?
        """, status, assignee, Timestamp.from(now), issueKey) > 0;
  }

  @Override
  public void upsertRule(
      String engine, String ruleKey, String name, String severity, String language,
      String description, String remediation, Instant now) {
    int updated = jdbc.update("""
        UPDATE kkq_rule SET name = ?, severity = ?, language = ?, description = ?,
          remediation = ?, updated_at = ? WHERE engine = ? AND rule_key = ?
        """, name, severity, language, description, remediation, Timestamp.from(now),
        engine, ruleKey);
    if (updated == 0) {
      jdbc.update("""
          INSERT INTO kkq_rule
            (engine, rule_key, name, severity, language, description, remediation,
             created_at, updated_at)
          VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
          """, engine, ruleKey, name, severity, language, description, remediation,
          Timestamp.from(now), Timestamp.from(now));
    }
  }

  @Override
  public List<Rule> listRules(String engine, String query, int limit, int offset) {
    List<Object> args = new ArrayList<>();
    String where = ruleFilter(engine, query, args);
    args.add(Math.max(1, Math.min(limit, 500)));
    args.add(Math.max(0, offset));
    return jdbc.query("SELECT * FROM kkq_rule " + where
        + " ORDER BY name, id LIMIT ? OFFSET ?", rules, args.toArray());
  }

  @Override
  public long countRules(String engine, String query) {
    List<Object> args = new ArrayList<>();
    Long count = jdbc.queryForObject(
        "SELECT COUNT(*) FROM kkq_rule " + ruleFilter(engine, query, args),
        Long.class, args.toArray());
    return count == null ? 0 : count;
  }

  private static String ruleFilter(String engine, String query, List<Object> args) {
    StringBuilder where = new StringBuilder("WHERE 1 = 1");
    if (engine != null && !engine.isBlank()) {
      where.append(" AND engine = ?");
      args.add(engine);
    }
    if (query != null && !query.isBlank()) {
      where.append(" AND (LOWER(name) LIKE ? OR LOWER(rule_key) LIKE ?)");
      args.add("%" + query.toLowerCase(java.util.Locale.ROOT) + "%");
      args.add("%" + query.toLowerCase(java.util.Locale.ROOT) + "%");
    }
    return where.toString();
  }

  @Override
  public long insertQualityGate(
      Long projectId, String externalId, String name,
      List<Map<String, Object>> conditions, boolean isDefault, Instant now) {
    return JdbcInserts.insert(jdbc, """
        INSERT INTO kkq_quality_gate
          (project_id, external_id, name, conditions_json, is_default, created_at, updated_at)
        VALUES (?, ?, ?, ?, ?, ?, ?)
        """, ps -> {
      bindLong(ps, 1, projectId);
      ps.setString(2, externalId);
      ps.setString(3, name);
      json.bindSerialized(ps, 4, json.writeValue(conditions));
      ps.setBoolean(5, isDefault);
      ps.setTimestamp(6, Timestamp.from(now));
      ps.setTimestamp(7, Timestamp.from(now));
    });
  }

  @Override
  public List<QualityGate> listQualityGates(Long projectId) {
    if (projectId == null) {
      return jdbc.query("SELECT * FROM kkq_quality_gate ORDER BY is_default DESC, name", gates);
    }
    return jdbc.query("""
        SELECT * FROM kkq_quality_gate WHERE project_id IS NULL OR project_id = ?
        ORDER BY is_default DESC, name
        """, gates, projectId);
  }

  @Override
  public Optional<QualityGate> findDefaultQualityGate() {
    return jdbc.query("SELECT * FROM kkq_quality_gate WHERE is_default = TRUE ORDER BY id LIMIT 1",
        gates).stream().findFirst();
  }

  @Override
  public boolean updateQualityGate(
      long id, String name, List<Map<String, Object>> conditions, boolean isDefault,
      Instant now) {
    return jdbc.update("""
        UPDATE kkq_quality_gate SET name = ?, conditions_json = ?, is_default = ?, updated_at = ?
        WHERE id = ?
        """, name, json.serializedParameter(json.writeValue(conditions)), isDefault,
        Timestamp.from(now), id) > 0;
  }

  @Override
  public boolean deleteQualityGate(long id) {
    return jdbc.update("DELETE FROM kkq_quality_gate WHERE id = ?", id) > 0;
  }

  @Override
  public void replaceMeasures(long analysisId, List<Measure> values) {
    jdbc.update("DELETE FROM kkq_measure WHERE analysis_id = ?", analysisId);
    for (Measure measure : values) {
      jdbc.update("""
          INSERT INTO kkq_measure (analysis_id, metric_key, value_num, value_text)
          VALUES (?, ?, ?, ?)
          """, analysisId, measure.metricKey(), measure.valueNum(), measure.valueText());
    }
  }

  @Override
  public List<Measure> listMeasures(long analysisId) {
    return jdbc.query(
        "SELECT * FROM kkq_measure WHERE analysis_id = ? ORDER BY metric_key",
        measures, analysisId);
  }

  private List<Map<String, Object>> readConditions(String value) {
    return value == null || value.isBlank() ? List.of() : json.readValue(value, CONDITIONS);
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    Timestamp value = rs.getTimestamp(column);
    return value == null ? null : value.toInstant();
  }

  private static Long nullableLong(ResultSet rs, String column) throws SQLException {
    long value = rs.getLong(column);
    return rs.wasNull() ? null : value;
  }

  private static Integer nullableInt(ResultSet rs, String column) throws SQLException {
    int value = rs.getInt(column);
    return rs.wasNull() ? null : value;
  }

  private static void bindLong(PreparedStatement ps, int index, Long value) throws SQLException {
    if (value == null) ps.setNull(index, java.sql.Types.BIGINT);
    else ps.setLong(index, value);
  }

  private static void bindInstant(PreparedStatement ps, int index, Instant value)
      throws SQLException {
    if (value == null) ps.setNull(index, java.sql.Types.TIMESTAMP);
    else ps.setTimestamp(index, Timestamp.from(value));
  }
}

CREATE TABLE kkq_project (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  external_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  project_key VARCHAR(400) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  name VARCHAR(255) NOT NULL,
  organization VARCHAR(255) NULL,
  visibility VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'private',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_kkq_project_external_id (external_id),
  UNIQUE KEY uk_kkq_project_key (project_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE kkq_branch (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  project_id BIGINT UNSIGNED NOT NULL,
  name VARCHAR(255) NOT NULL,
  is_main BOOLEAN NOT NULL DEFAULT FALSE,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_kkq_branch_project_name (project_id, name),
  CONSTRAINT fk_kkq_branch_project
    FOREIGN KEY (project_id) REFERENCES kkq_project(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE kkq_rule (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  engine VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  rule_key VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  name VARCHAR(512) NOT NULL,
  severity VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL,
  language VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  description TEXT NULL,
  remediation TEXT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_kkq_rule_engine_key (engine, rule_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE kkq_analysis (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  project_id BIGINT UNSIGNED NOT NULL,
  branch_id BIGINT UNSIGNED NULL,
  analysis_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  status VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  revision VARCHAR(255) NULL,
  sarif_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  issue_count INT NOT NULL DEFAULT 0,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  started_at DATETIME(3) NULL,
  completed_at DATETIME(3) NULL,
  error TEXT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_kkq_analysis_external_id (analysis_id),
  INDEX idx_kkq_analysis_project (project_id, created_at),
  INDEX idx_kkq_analysis_status (status, created_at),
  CONSTRAINT fk_kkq_analysis_project
    FOREIGN KEY (project_id) REFERENCES kkq_project(id) ON DELETE CASCADE,
  CONSTRAINT fk_kkq_analysis_branch
    FOREIGN KEY (branch_id) REFERENCES kkq_branch(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE kkq_issue (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  analysis_id BIGINT UNSIGNED NOT NULL,
  project_id BIGINT UNSIGNED NOT NULL,
  branch_id BIGINT UNSIGNED NULL,
  issue_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  file_path VARCHAR(1024) NOT NULL,
  line INT NULL,
  engine VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  rule_key VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
  severity VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL,
  issue_type VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL,
  message TEXT NULL,
  status VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'OPEN',
  assignee VARCHAR(255) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_kkq_issue_key (issue_key),
  INDEX idx_kkq_issue_analysis (analysis_id),
  INDEX idx_kkq_issue_project (project_id, status),
  INDEX idx_kkq_issue_rule (engine, rule_key),
  CONSTRAINT fk_kkq_issue_analysis
    FOREIGN KEY (analysis_id) REFERENCES kkq_analysis(id) ON DELETE CASCADE,
  CONSTRAINT fk_kkq_issue_project
    FOREIGN KEY (project_id) REFERENCES kkq_project(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE kkq_quality_gate (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  project_id BIGINT UNSIGNED NULL,
  external_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  name VARCHAR(255) NOT NULL,
  conditions_json JSON NOT NULL,
  is_default BOOLEAN NOT NULL DEFAULT FALSE,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_kkq_quality_gate_external_id (external_id),
  UNIQUE KEY uk_kkq_quality_gate_project_name (project_id, name),
  CONSTRAINT fk_kkq_quality_gate_project
    FOREIGN KEY (project_id) REFERENCES kkq_project(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE kkq_measure (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  analysis_id BIGINT UNSIGNED NOT NULL,
  metric_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  value_num DECIMAL(24,4) NULL,
  value_text TEXT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_kkq_measure_analysis_metric (analysis_id, metric_key),
  CONSTRAINT fk_kkq_measure_analysis
    FOREIGN KEY (analysis_id) REFERENCES kkq_analysis(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

# kkQuality — SonarQube-compatible code quality service

kkQuality is kkRepo's code-quality and static-analysis service. It replaces SonarQube for teams
that want a self-hosted quality gate without a separate Elasticsearch/OrientDB stack, while
exposing a SonarQube-compatible read API so existing dashboards and CI checks can migrate.

Naming: the service is **kkQuality** everywhere (`kkq_*` tables, `server.kkquality` package in the
next increment). "SonarQube" is referenced only as the API compatibility target.

## Architecture

```text
CI / kkq-scanner ── SARIF + metadata ──> kkQuality (server/kkquality)
                                            │
                              ┌─────────────┼──────────────┐
                              │             │              │
                         kkq_* tables   SARIF blob     quality gates
                       (MySQL/PG)       (OSS/S3)       (local evaluation)
```

- Analyzers run in CI, not on the server: **Semgrep**, **PMD**, **Checkstyle**, **SpotBugs**,
  **ESLint**, **Ruff**, **golangci-lint**, **Clippy**, **detekt**, **ShellCheck**, **Hadolint**.
- Every analyzer emits **SARIF 2.1.0**; kkQuality normalizes rules, results, locations and
  severities from SARIF. No analyzer-specific server code is required to add a tool.
- SARIF documents are immutable blobs in OSS/S3 keyed by SHA-256; MySQL stores project, branch,
  analysis, rule, issue, measure and quality-gate rows.
- Ingestion and gate evaluation are fenced with row locks so multiple kkRepo replicas can process
  analyses safely.

## Data model

| Table | Purpose |
| --- | --- |
| `kkq_project` | SonarQube project key, name, visibility, external UUID |
| `kkq_branch` | main/feature branches per project |
| `kkq_analysis` | one SARIF submission: status, revision, SARIF digest, issue count |
| `kkq_rule` | normalized analyzer rule metadata |
| `kkq_issue` | normalized issue: file, line, rule, severity, type, status, assignee |
| `kkq_quality_gate` | gate conditions (metric/operator/threshold), optional project scope |
| `kkq_measure` | per-analysis metric values (bugs, vulnerabilities, code smells, coverage, duplication) |

## API compatibility

Read compatibility with the SonarQube Web API subset used by dashboards and CI:

| Area | Endpoints |
| --- | --- |
| Projects | `GET /api/projects/search`, `POST /api/projects/create`, `DELETE /api/projects/delete` |
| Issues | `GET /api/issues/search`, `POST /api/issues/do_transition`, `POST /api/issues/assign` |
| Measures | `GET /api/measures/component` |
| Quality gates | `GET /api/qualitygates/list`, `GET /api/qualitygates/project_status` |
| Rules | `GET /api/rules/search` |

Analysis ingestion is a kkRepo extension for the first release:
`POST /api/kkquality/analyses` accepts a SARIF document plus `projectKey`, `branch`, `revision`
and optional measures. A later phase can add the Sonar scanner submission protocol
(`/api/ce/submit`) for unmodified `sonar-scanner` clients.

Authentication uses kkRepo API keys/tokens; the compatibility layer maps Sonar-style permissions
(`sonar:read`, `sonar:admin`) to kkRepo privileges.

## Quality gates

Gate conditions are local and deterministic:

```json
{"metric": "bugs", "operator": "GREATER_THAN", "value": "0", "severity": "BLOCKER"}
```

Metrics are computed during ingestion: open issues by severity/type, new issues versus the previous
analysis on the same branch, coverage and duplication when the scanner supplies them. A failed gate
returns `status: "ERROR"` with the failing conditions in the Sonar-compatible project status
document.

## Delivery phases

1. **Foundation** (this branch): `kkq_*` schema, `KkQualityDao`, contract tests.
2. **SARIF ingestion**: analyzer-normalized rules/issues, blob storage, analysis status, issue
   deduplication by fingerprint.
3. **Sonar-compatible read API**: projects, issues, measures, rules and gate status.
4. **kkq-scanner**: container image/CLI that runs the open-source analyzers and uploads SARIF.
5. **Operations**: admin UI, project history, retention, black-box compatibility tests.

## Non-goals

- Running analyzers on the kkRepo server by default (CI execution keeps resource use and attack
  surface bounded; a server-side adapter can be added later).
- SonarQube Java plugin API and the full scanner protocol in the first release.
- Elasticsearch, OrientDB or any external search cluster.

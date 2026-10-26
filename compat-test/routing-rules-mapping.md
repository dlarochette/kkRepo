# Routing rules (Issue #452)

Reference: Nexus Repository 3.68.1 runtime behavior and 3.94.0 API schema and [Sonatype routing rules documentation](https://help.sonatype.com/en/routing-rules.html).

| Console operation | Nexus endpoint / representation | kkRepo endpoint |
| --- | --- | --- |
| Settings → Repository → Routing Rules: list/create | GET/POST `/service/rest/v1/routing-rules` | Same |
| Read/update/delete a rule | GET/PUT/DELETE `/service/rest/v1/routing-rules/{name}` | Same |
| Proxy repository: Routing Rule selector | `routingRule` (3.68) / `routingRuleName` (3.94) in repository REST configuration | `routingRuleName` in `/internal/repositories` configuration |
| Rule tester | Internal UI routing-rule preview resource | `/internal/routing-rules/preview` |

A rule has `name`, non-null `description`, `mode` (`ALLOW` or `BLOCK`), and a non-empty list of Java regular expressions (`matchers`). Each matcher matches the entire repository-relative path (leading slash), including query parameters. ANY matching expression allows an ALLOW rule or rejects a BLOCK rule. Rules attach to proxies only. Assigned rules cannot be deleted. They apply before cached content is served; a blocked direct proxy read returns 403, and groups continue to the next available member.

`RoutingRulesBlackBoxCompatibilityTest` creates disposable rules and Raw and Maven repositories, warms proxy and group caches, changes rules, and verifies HTTP status and body semantics. It removes its fixtures in `finally`. Enable with `-Dcompat.routingRules=true`; use `-Dcompat.routingRules.referenceOnly=true` to establish the Nexus reference before deploying kkRepo. Repository administration uses each product's existing API; the routing-rule CRUD contract is shared.

The Nexus reference must permit its controlled loopback upstream (`http://localhost:8081`). The CI setup adds `localhost` to the Nexus SSRF domain allowlist; on a shared reference instance use an already permitted fixture endpoint or configure this prerequisite explicitly.

The kkRepo fixture upstream defaults to its client-facing base URL. When kkRepo runs in a container, set `ROUTING_RULES_KKREPO_UPSTREAM_BASE_URL` (or `-Dcompat.routingRules.kkrepoUpstreamBaseUrl`) to an address reachable from inside that container. CI uses `http://localhost:8080`, independently of the host-published client port `18092`.

Matcher input canonicalizes repeated slashes and dot segments before evaluating policy, preventing alternate URLs from reaching denied proxy content. The black-box checks cover direct GET/HEAD denial for both forms and group fallback for repeated slashes. Hosted dot-segment resolution remains a separate compatibility difference: Nexus can resolve a group fallback through such a URL while kkRepo can return 404; the denied proxy must never be restored.

Rule matching follows the consuming handler's segment parsing. PyPI MVC routes remove literal matrix parameters, while encoded semicolons remain part of the segment. `PypiRoutingRuleTest` verifies that GET and HEAD project-index requests with matrix parameters are rejected before the proxy service can read a cache or contact upstream. Manual protocol handlers retain literal semicolons as asset data; the Raw black-box regression warms `/allowed;private/item.jar` before enabling ALLOW `/allowed/.*`, then verifies GET/HEAD rejection, group fallback, and successful reads of `/allowed/item;public.jar`. Nexus 3.68 can dispatch matrix aliases to its repository browser instead of the PyPI index, so that browser response is not treated as a protocol-equivalent index response.

## Configuration migration

`NexusRestClient` reads `GET /service/rest/v1/routing-rules` together with repository details. The target `/internal/migration/nexus/preflight` exposes `routingRules.rules`, `bindings`, `errors` and `checksum`; `/run` imports rules before binding repositories inside the existing configuration transaction. An inaccessible policy API is a blocking error, never an empty rule set.

`compat-test/scripts/routing-rules-migration.py prepare|verify|cleanup` provisions a dedicated Raw proxy and policy, then compares rule content, binding, unique name, HTTP 403 (including encoded paths), and the SHA-256 of an allowed Maven Central fixture. `scripts/docker-compat/migration-e2e.sh` runs this before and after configuration migration, including its repeat run and secondary replica verification where enabled. The full configuration import also migrates security settings: use a disposable target, not a shared acceptance instance. Unit tests cover dry-run, old/new binding field names, idempotency, malformed regex, missing inventory, conflicting policies and concurrent policy changes.

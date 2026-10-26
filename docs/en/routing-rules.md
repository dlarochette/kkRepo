# Routing Rules

Routing rules control which paths a **proxy repository** can serve. They are useful for keeping internal namespaces out of public upstreams or allowing only approved paths.

1. Open **Repository → Routing Rules** and create a rule.
2. Choose **Block matching paths** or **Allow only matching paths**.
3. Enter one Java regular expression per line. Expressions match the entire repository-relative path, including the leading `/` and query parameters. Any matching expression activates the rule.
4. Use **Test path** to check the result before saving.
5. Edit a proxy repository and select the rule under **Proxy settings → Routing Rule**.

For example, a BLOCK rule with `/com/company/.*` prevents a Maven proxy from serving that namespace. A rule can be shared by multiple proxies; each proxy can use one rule. Changes apply to every assigned repository. Unassign a rule from all repositories before deleting it.

Blocked direct proxy reads return HTTP 403, including already cached content. A group skips blocked proxy members and continues in member order. Hosted repositories are unaffected. Rules match request paths; they do not inspect package metadata for vulnerabilities or remove matching entries from otherwise allowed index documents. Use artifact scanning policies for vulnerability decisions and content selectors for user-specific access grants.

Matching uses the path identity consumed by the protocol. For example, PyPI `/simple/PRIVATE` matches as `/simple/private/`, npm package aliases use the parsed package name, and Terraform URL credentials are excluded. Raw filenames retain literal semicolons. The rule tester evaluates the path you enter; use the canonical protocol path when testing these cases.

The management API follows Nexus:

- `GET /service/rest/v1/routing-rules`
- `POST /service/rest/v1/routing-rules`
- `GET`, `PUT`, `DELETE /service/rest/v1/routing-rules/{name}`

```json
{"name":"block-company","description":"Keep internal namespaces private","mode":"BLOCK","matchers":["/com/company/.*"]}
```

Creation, update and deletion return 204. Invalid expressions and deletion of assigned rules return 400. Management requires the `nexus:*` administrative permission. Repository configuration uses `routingRuleName` in `/internal/repositories` (the older Nexus spelling `routingRule` is also accepted). An empty string removes the assignment; omission preserves the assignment on a partial repository update.

Rules and bindings live in the shared database. Each request reads a consistent policy snapshot before serving protocol caches. Group cache generations incorporate the applicable rules and selected members, so a cache warmed under another routing decision cannot bypass a new rule. Requests already in progress retain their original snapshot.

See [compatibility mapping and test instructions](../../compat-test/routing-rules-mapping.md). Nexus 3 configuration migration imports routing rules and restores bindings for supported proxy repositories. Preflight lists the rules, bindings, blocking errors and a SHA-256 checksum of the source policy. Both `routingRule` and `routingRuleName` source fields are accepted.

Rules with the same name and identical content are reused; conflicting definitions, missing rules, invalid expressions or an unreadable source rule API block configuration migration. Existing target repository assignments are not silently replaced or removed. Resolve the reported conflict explicitly and retry. Rules and bindings are written in the same database transaction as repository configuration, and the result report validates both. Repeating a completed migration reuses rule identities. Concurrent creation conflicts roll back and can be retried; row locks protect existing rules and repository bindings across replicas.

The source account needs access to the routing-rule API. This migration copies configuration, not cached artifact bytes; repository data migration remains a separate step.

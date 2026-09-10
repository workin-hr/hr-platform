# Tool Decision Matrix

**Discovery-informed update (2026-08-04):** the rows below marked
"Discovery note" were updated after the `hr-legacy` API/dashboard
Discovery pass (`docs/api/existing-endpoint-inventory.md`,
`docs/legacy/business-rule-extraction.md`,
`docs/security/threat-model.md`) resolved or newly informed the stated
blocker for that tool. These are recommendations for human confirmation,
not decisions made by this document alone — the same standard every ADR
in this repository already holds itself to.

| Tool | Category | Current Position | Reason |
| --- | --- | --- | --- |
| Git | Core SCM | Install during Phase 0 | Mandatory repository baseline |
| GitHub CLI | Repository operations | Install during Phase 0 | Useful for controlled GitHub operations |
| GitHub Projects | Planning | Install during Phase 0 | Needed for cross-repository tracking |
| GitHub Issues | Planning | Install during Phase 0 | Needed for backlog and evidence |
| GitHub Actions | Validation | Install during Phase 0 | Needed for bootstrap CI |
| Git worktrees | Workflow | Approved — no install needed (built into Git) | Built into Git; use freely now for parallel bootstrap or implementation branches |
| Claude Code | Planning and review | Install during Phase 0 | Core planning and review agent runtime |
| Codex CLI | Controlled implementation | Install during Phase 0 | Core implementation and verification runtime |
| Spec Kit | Specification workflow | Install during Phase 0 | Needed for structured planning flow |
| GitHub MCP | Agent integration | Evaluate during discovery | Requires security and permission design |
| Mermaid | Diagramming | Approve now, install later | Lightweight diagram support |
| Structurizr DSL | Architecture modeling | Approve now, install later | Useful for maintainable architecture diagrams |
| Markdownlint | Documentation quality | Approve now, install later | Improves markdown consistency |
| Yamllint | Configuration quality | Approve now, install later | Improves YAML safety |
| ShellCheck | Script quality | Approve now, install later | Improves shell script reliability |
| actionlint | GitHub Actions quality | Approve now, install later | Validates workflows |
| Gitleaks | Secret detection | Approve now, install later | Prevents credential exposure |
| Lychee | Link checking | Approve now, install later | Detects broken links |
| Java 25 | Runtime | Approve now, install later | Target stack, not needed for bootstrap |
| Spring Boot 4.x | Runtime | Approve now, install later | Target stack, not needed for bootstrap |
| Maven | Build tooling | Approve now, install later | Needed only once Java implementation begins |
| PostgreSQL | Database | Approve now, install later | Target system component, not bootstrap |
| Flyway | Migration tooling | Approve now, install later | Needed after migration strategy is approved |
| pgloader | Migration tooling | Evaluate during discovery | Depends on actual schema and cutover approach |
| Node LTS | Frontend runtime | Approve now, install later | Needed only once web implementation begins |
| ~~pnpm~~ | ~~Frontend package manager~~ | **Not used (D-151)** | No Node frontend: the admin web is JTE in-process (ADR-0015) |
| ~~Next.js 16~~ | ~~Frontend~~ | **Not used (D-151)** | Superseded by JTE pages in the existing Spring app (ADR-0015) |
| Flutter | Client | Evaluate during discovery | Compatibility and release details first. Discovery note: `hr-legacy` API-side Discovery is done, but every "Consumer" field in `docs/api/existing-endpoint-inventory.md` is explicitly marked inferred — no Flutter client source was available in that pass. This blocker is only partially resolved; the actual Flutter app still needs its own read-through before this can move to "Approve now." |
| .NET | Gateway | Evaluate during discovery | Depends on device discovery. Discovery note: `docs/devices/*.md` (vendor capability matrix, device/firmware inventory) remain empty templates — device discovery has not happened yet. This blocker is fully unresolved, not something the `hr-legacy` software Discovery pass could address. **Update 2026-09-02**: D-164 (accepted) makes device-initiated ADMS push — no local component — the primary ZKTeco path and the .NET gateway a fallback for terminals without ADMS; the hardware checklist in `../superpowers/specs/2026-09-02-attendance-device-ingestion-design.md` §4.3 is the recorded condition before the adapter is declared verified. |
| Docker | Local infra | Approve now, install later | Not needed for Phase 0 |
| Docker Compose | Local infra | Approve now, install later | Not needed for Phase 0 |
| Testcontainers | Test infra | Evaluate during discovery | Depends on implementation-phase test design |
| JUnit | Backend testing | Evaluate during discovery | Depends on Java implementation choices. Discovery note: Java + Spring Boot is the recorded target stack above (`Java 25`, `Spring Boot 4.x`) — recommend advancing to "Approve now, install later." |
| ArchUnit | Architecture testing | Evaluate during discovery | Valuable once backend module design exists. Discovery note: ADR-0002 (Modular Monolith Baseline)'s own stated risk is "module boundaries drift into a tangled monolith without enforced internal contracts" — ArchUnit is a direct, low-cost mitigation for exactly that risk and doesn't need to wait for module design to exist first (it can enforce boundary rules as they're decided, incrementally). Recommend advancing to "Approve now, install later." |
| Spring Modulith | Architecture support | Evaluate during discovery | Depends on chosen modular monolith structure. Discovery note: same reasoning as ArchUnit above — this is the Spring-native tool built specifically to enforce and verify module boundaries in a Spring Boot modular monolith, directly addressing ADR-0002's stated risk. Recommend advancing to "Approve now, install later," ahead of rather than after module design, since it can shape that design. |
| REST Assured | API testing | Evaluate during discovery | Depends on Java API implementation approach. Discovery note: the legacy system is a REST JSON API throughout (`docs/legacy/existing-php-module-inventory.md`) and ADR-0003 assumes REST-shaped Flutter compatibility work — no evidence found this session suggesting a non-REST approach is under consideration. Recommend advancing to "Approve now, install later" unless ADR-0003 resolves toward something else. |
| WireMock | Integration testing | Evaluate during discovery | Depends on external integration patterns |
| Schemathesis | Contract testing | Evaluate during discovery | Depends on OpenAPI maturity |
| ~~Vitest~~ | ~~Web unit testing~~ | **Not used (D-151)** | Was contingent on the Next.js app that is no longer built |
| ~~React Testing Library~~ | ~~Web UI testing~~ | **Not used (D-151)** | Was contingent on the Next.js app that is no longer built |
| Playwright | E2E testing | Evaluate during discovery | Useful later, not bootstrap |
| k6 | Performance testing | Evaluate during discovery | Useful later for load scenarios |
| Trivy | Security scanning | Evaluate during discovery | Depends on container and dependency scope |
| Semgrep | Static security analysis | Evaluate during discovery | Valuable later when implementation exists |
| OWASP Dependency-Check | Dependency scanning | Evaluate during discovery | Useful after application dependencies exist |
| OWASP ZAP | DAST | Evaluate during discovery | Depends on running application surfaces |
| OpenTelemetry | Observability | Evaluate during discovery | Important but not needed for bootstrap |
| Prometheus | Metrics | Evaluate during discovery | Depends on observability baseline ADR |
| Grafana | Dashboards | Evaluate during discovery | Depends on observability stack choice |
| Loki | Log storage | Evaluate during discovery | Depends on observability stack choice |
| Tempo | Trace storage | Evaluate during discovery | Depends on observability stack choice |
| Alertmanager | Alert routing | Evaluate during discovery | Depends on operations design |
| SonarQube | Code quality platform | Evaluate during discovery | Useful later, not required for bootstrap |
| Microservices | Architecture style | Explicitly rejected or deferred | No operational justification yet |
| Kafka | Messaging platform | Explicitly rejected or deferred | Not justified for initial MVP |
| Kubernetes | Platform | Explicitly rejected or deferred | Too heavy for initial MVP |
| Service mesh | Platform | Explicitly rejected or deferred | Adds complexity without current need |
| Elasticsearch | Search platform | Explicitly rejected or deferred | Not justified for initial MVP |
| Debezium | CDC | Explicitly rejected or deferred | Not justified before migration strategy matures |
| Redis without a demonstrated use case | Caching | Explicitly rejected or deferred | Avoid speculative infrastructure. Discovery note: `hr-legacy` Discovery found a concrete, named use case that didn't exist when this was rejected — OTP verification has no rate limiting at all (GitHub issue #10 in `workin-hr/hr-legacy`), and a Redis-backed per-phone attempt counter with TTL is the standard tool for exactly that gap. This is flagged for the row's original owner to revisit as a discrete decision — not treated as reversed by this note, since "no demonstrated use case" was the entire stated reason and that specific premise no longer holds. |
| Paid test-management platforms | Governance | Explicitly rejected or deferred | Unnecessary for current phase |
| Uncontrolled autonomous agent orchestration | AI runtime | Explicitly rejected or deferred | Unsafe governance boundary |
| Keycloak (or an equivalent self-hosted IAM) | Identity & Access Management | **Superseded 2026-08-04, reaffirmed 2026-08-05 — explicitly rejected for the MVP**, not "evaluate during discovery" | `docs/adr/ADR-0005-authentication-direction.md` records the confirmed direction: self-managed JWT + refresh, no Keycloak or external IdP for the MVP. The authentication-side gaps this row originally cited (10-year JWT, no revocation — issue #7) are addressed by that decision directly. The authorization-side gaps (per-admin identity/MFA — issue #11; tenant-scoping enforcement — issues #2/#3/#5/#6) are addressed by `docs/adr/ADR-0010-authorization-model.md` (Accepted 2026-08-05), which explicitly rejects Keycloak Authorization Services (and OPA/Cedar/OpenFGA) for this MVP too — the confirmed real complexity (4 roles, a low-double-digit permission catalog) doesn't justify an external policy engine. Not foreclosed forever, just not adopted now. |
| springdoc-openapi (or equivalent OpenAPI generation) | API documentation & contract tooling | New — recommend: Approve now, install later | Not previously in this matrix. `Schemathesis` is already listed as "Evaluate during discovery... depends on OpenAPI maturity" — that dependency can't be resolved without something actually generating an OpenAPI spec from the Java API. Given ADR-0003 (API Versioning And Flutter Compatibility) needs an evidence-backed compatibility contract, spec-first/generated OpenAPI is a near-prerequisite for that ADR's own stated goal, not an optional add-on. |
| S3-compatible object storage (e.g. self-hosted MinIO, or a cloud provider's object storage) | File storage | New — recommend: Evaluate during discovery | Not previously in this matrix. `docs/legacy/existing-php-module-inventory.md` confirms `hr-legacy` stores all uploads (employee photos, company logos, commercial-registration documents, employee documents) on local disk (`uploads/`, `AppConfig::UPLOAD_PATH`) — kept local-only in the sanitized import specifically because it doesn't belong in git. Local disk storage doesn't survive a multi-instance or containerized deployment (implied by `Docker`/`Docker Compose` already being in this matrix); needs an explicit decision before the file-upload endpoints are rebuilt, not discovered as a gap after deployment. |

# Tool Catalog

Git worktrees are a built-in Git capability, not a separate tool — they
require no installation beyond Git itself, and are approved for use now
(e.g. for parallel bootstrap or implementation branches). See
`docs/tools/tool-decision-matrix.md` for the authoritative classification;
do not list it separately as something to "install."

This list was updated 2026-08-04 to match `tool-decision-matrix.md`'s
Discovery-informed recommendations following the `hr-legacy` API/dashboard
Discovery pass — see that file for the evidence behind each change (still
recommendations pending human confirmation, not decisions).

## Install During Phase 0

- Git (worktrees included — no separate install)
- GitHub Projects
- GitHub Issues
- GitHub Actions
- GitHub CLI
- Spec Kit
- Claude Code
- Codex CLI

## Approve Now, Install Later

- Mermaid
- Structurizr DSL
- Markdownlint
- Yamllint
- ShellCheck
- actionlint
- Gitleaks
- Lychee
- Java 25
- Spring Boot 4.x
- Maven
- PostgreSQL
- Flyway
- Node LTS
- pnpm
- Next.js 16
- Docker
- Docker Compose
- JUnit (moved from Evaluate During Discovery — Java/Spring Boot is the confirmed target stack)
- ArchUnit (moved from Evaluate During Discovery — directly mitigates ADR-0002's stated module-boundary-drift risk)
- Spring Modulith (moved from Evaluate During Discovery — same reasoning as ArchUnit)
- REST Assured (moved from Evaluate During Discovery — the legacy system and ADR-0003 both assume REST)
- springdoc-openapi (new — near-prerequisite for ADR-0003's compatibility-contract goal and for Schemathesis below)

## Evaluate During Discovery

- GitHub MCP
- Flutter (Discovery note: API-side Discovery done, but no Flutter client source read yet — blocker only partially resolved)
- .NET (Discovery note: device discovery still not started — `docs/devices/*.md` remain empty templates)
- pgloader
- Testcontainers
- WireMock
- Schemathesis
- Keycloak or equivalent self-hosted IAM (new — see `tool-decision-matrix.md` for why: most of this session's highest-severity `hr-legacy` findings are auth/identity design gaps)
- S3-compatible object storage, e.g. MinIO (new — legacy system stores uploads on local disk, not viable for multi-instance deployment)
- Vitest
- React Testing Library
- Playwright
- k6
- Trivy
- Semgrep
- OWASP Dependency-Check
- OWASP ZAP
- OpenTelemetry
- Prometheus
- Grafana
- Loki
- Tempo
- Alertmanager
- SonarQube

## Explicitly Rejected Or Deferred For Initial MVP

- microservices
- Kafka
- Kubernetes
- service mesh
- Elasticsearch
- Debezium
- Redis without a demonstrated use case (Discovery note: a concrete use case — OTP rate-limiting, `hr-legacy` GitHub issue #10 — now exists; flagged for the row owner to revisit as a discrete decision, see `tool-decision-matrix.md`)
- paid test-management platforms
- uncontrolled autonomous agent orchestration

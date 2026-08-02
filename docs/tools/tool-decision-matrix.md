# Tool Decision Matrix

| Tool | Category | Current Position | Reason |
| --- | --- | --- | --- |
| Git | Core SCM | Install during Phase 0 | Mandatory repository baseline |
| GitHub CLI | Repository operations | Install during Phase 0 | Useful for controlled GitHub operations |
| GitHub Projects | Planning | Install during Phase 0 | Needed for cross-repository tracking |
| GitHub Issues | Planning | Install during Phase 0 | Needed for backlog and evidence |
| GitHub Actions | Validation | Install during Phase 0 | Needed for bootstrap CI |
| Git worktrees | Workflow | Approve now, install later | Useful once parallel implementation starts |
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
| pnpm | Frontend package manager | Approve now, install later | Useful for Next.js work, not bootstrap |
| Next.js 16 | Frontend | Approve now, install later | Target admin app, not bootstrap |
| Flutter | Client | Evaluate during discovery | Compatibility and release details first |
| .NET | Gateway | Evaluate during discovery | Depends on device discovery |
| Docker | Local infra | Approve now, install later | Not needed for Phase 0 |
| Docker Compose | Local infra | Approve now, install later | Not needed for Phase 0 |
| Testcontainers | Test infra | Evaluate during discovery | Depends on implementation-phase test design |
| JUnit | Backend testing | Evaluate during discovery | Depends on Java implementation choices |
| ArchUnit | Architecture testing | Evaluate during discovery | Valuable once backend module design exists |
| Spring Modulith | Architecture support | Evaluate during discovery | Depends on chosen modular monolith structure |
| REST Assured | API testing | Evaluate during discovery | Depends on Java API implementation approach |
| WireMock | Integration testing | Evaluate during discovery | Depends on external integration patterns |
| Schemathesis | Contract testing | Evaluate during discovery | Depends on OpenAPI maturity |
| Vitest | Web unit testing | Evaluate during discovery | Depends on Next.js implementation |
| React Testing Library | Web UI testing | Evaluate during discovery | Depends on Next.js implementation |
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
| Redis without a demonstrated use case | Caching | Explicitly rejected or deferred | Avoid speculative infrastructure |
| Paid test-management platforms | Governance | Explicitly rejected or deferred | Unnecessary for current phase |
| Uncontrolled autonomous agent orchestration | AI runtime | Explicitly rejected or deferred | Unsafe governance boundary |

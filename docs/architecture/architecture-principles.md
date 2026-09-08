# Architecture Principles

1. Start with a modular monolith.
2. Prefer API-first development.
3. Preserve Flutter API compatibility where required by validated client behavior.
4. Treat multi-tenant isolation as a first-class architecture constraint.
5. The database is **MySQL** (MariaDB in production), permanently — see `docs/adr/ADR-0017-mysql-is-the-production-database.md`, `Accepted`, which supersedes ADR-0004's PostgreSQL target. Tenant isolation is enforced in the application (ADR-0012, D-041, D-176); there is no database-level backstop and none is coming (**R-070**).
6. Attendance events should be modeled as immutable facts.
7. External ingestion must be idempotent.
8. Use a transactional outbox where reliable downstream processing is required.
9. Do not introduce microservices without measurable operational justification and an approved ADR.
10. Do not introduce Kafka, Kubernetes, Redis, Elasticsearch, or a service mesh during the initial MVP unless an approved ADR proves the need.
11. Repository documentation and automated tests are sources of truth.
12. Do not assume undocumented production behavior.

# Architecture Principles

1. Start with a modular monolith.
2. Prefer API-first development.
3. Preserve Flutter API compatibility where required by validated client behavior.
4. Treat multi-tenant isolation as a first-class architecture constraint.
5. The intended target database is PostgreSQL, subject to Discovery and ADR-0004 approval — see `docs/adr/ADR-0004-mysql-to-postgresql-migration-approach.md`, currently `Proposed`. This is an intended direction, not an accepted architecture decision.
6. Attendance events should be modeled as immutable facts.
7. External ingestion must be idempotent.
8. Use a transactional outbox where reliable downstream processing is required.
9. Do not introduce microservices without measurable operational justification and an approved ADR.
10. Do not introduce Kafka, Kubernetes, Redis, Elasticsearch, or a service mesh during the initial MVP unless an approved ADR proves the need.
11. Repository documentation and automated tests are sources of truth.
12. Do not assume undocumented production behavior.

# Data Principles

- **Confirmed current-state fact**: the existing production database is MySQL.
- **Accepted, and settled**: MySQL stays. `ADR-0017` supersedes ADR-0004's
  MySQL-to-PostgreSQL migration and ADR-0011's Phase 2; the PostgreSQL domain,
  its migrations and its ETL are deleted rather than deferred. Do not plan work
  that assumes a future storage migration.
- Migration planning must be evidence-driven and reversible where possible.
- Tenant isolation requirements must be explicit in data design.
- Attendance events should remain immutable.
- Differential validation between PHP behavior and target behavior is required for risky flows.

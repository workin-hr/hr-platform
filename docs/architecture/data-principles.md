# Data Principles

- **Confirmed current-state fact**: the existing production database is MySQL.
- **Intended direction, not yet accepted**: MySQL-to-PostgreSQL migration is
  a proposed modernization direction, not yet an accepted architecture
  decision — see `docs/adr/ADR-0004-mysql-to-postgresql-migration-approach.md`
  (`Proposed`). Do not plan as though PostgreSQL is confirmed; do not
  weaken the modernization goal either — Discovery is what's pending, not
  the intent.
- Migration planning must be evidence-driven and reversible where possible.
- Tenant isolation requirements must be explicit in data design.
- Attendance events should remain immutable.
- Differential validation between PHP behavior and target behavior is required for risky flows.

# ADR-0004: MySQL-To-PostgreSQL Migration Approach

## Metadata

| Field | Value |
|---|---|
| ADR ID | ADR-0004 |
| Title | MySQL-To-PostgreSQL Migration Approach |
| Status | Proposed |
| Date | 2026-08-02 |
| Owners | Solution Architect, Legacy PHP Analyst |
| Deciders | Human engineering leadership — recorded at approval time in `docs/bootstrap/decision-log.md` |
| Related Issues | None yet |
| Supersedes | None |
| Superseded By | None |

## Context

The current production database is MySQL, while the target direction is PostgreSQL.

## Decision

**Approval status: Proposed — this decision has not been approved.**

Establish a discovery-led migration strategy based on schema, procedure, trigger, performance, and rollback evidence before deciding the migration pattern.

## Alternatives Considered

- one-step direct migration with limited discovery
- long-term dual-database strategy

## Consequences

- avoids naive schema translation assumptions
- requires discovery effort before planning cutover
- keeps data-risk visibility early

## Risks

- MySQL-specific features (e.g. specific collation behavior, stored routines, `ON UPDATE` semantics) may not translate cleanly and could be discovered late without thorough inventory work
- migration approach chosen without volume/data-quality evidence could underestimate cutover downtime or reconciliation effort

## Validation Evidence

None yet — pending Discovery. Requires the full `docs/migration/` discovery template set (schema, views, events, stored procedures/functions, triggers, data-quality, character-set/collation, invalid-date, orphan-reference, duplicate-key, table-volume, sequence/identity, validation queries, cutover/rollback — see `docs/migration/README.md`) to be populated before this decision can move to Accepted.

## Open Questions

- which MySQL features are hardest to migrate
- what rollback and reconciliation model is acceptable

# ADR-0004: MySQL-To-PostgreSQL Migration Approach

## Metadata

| Field | Value |
|---|---|
| ADR ID | ADR-0004 |
| Title | MySQL-To-PostgreSQL Migration Approach |
| Status | Accepted |
| Date | 2026-08-02 (accepted 2026-08-05 — see `docs/bootstrap/decision-log.md` D-022) |
| Owners | Solution Architect, Legacy PHP Analyst |
| Deciders | Human engineering leadership — recorded at approval time in `docs/bootstrap/decision-log.md` |
| Related Issues | None yet |
| Supersedes | None |
| Superseded By | None |

> **Scope note (D-151, 2026-09-01) — not a supersession.** PostgreSQL remains
> the accepted long-term target and this ADR's approach is unchanged. Its
> *execution* is **Phase 2 and out of scope for the current phase, and must
> not be advanced**: no migration, ETL, dual-write, or cutover work proceeds
> now. The current phase is the PHP-to-Java port at parity **against the
> existing MySQL schema**. See **ADR-0011** for the phase boundary and the
> freeze-do-not-delete treatment of the existing Phase 2 material.

## Context

The current production database is MySQL, while the target direction is PostgreSQL.

## Decision

**Accepted 2026-08-05** (`docs/bootstrap/decision-log.md` D-022).

**Single-cutover bulk copy, not chunked/online replication or a
long-term dual-database strategy**, based on real, measured evidence
from the actual schema and a real (throwaway, isolated) data dump:

- **No server-side MySQL logic to port.** All four relevant inventories
  (`docs/migration/mysql-views-inventory.md`, `mysql-events-inventory.md`,
  `stored-procedure-and-function-inventory.md`, `trigger-inventory.md`)
  confirm zero views, events, stored procedures/functions, or triggers
  exist in the schema — the single biggest typical source of MySQL→
  Postgres migration difficulty is absent here, not just assumed away.
- **Small enough volume that bulk copy is sufficient.** ~62K total rows
  across all tables (`docs/migration/table-volume-analysis.md`) — this
  does not need chunked, incremental, or dual-write online migration; a
  single-cutover bulk copy fits comfortably within a reasonable
  maintenance window.
- **Data is already clean at the referential and tenant-boundary
  level.** Zero orphan references across all 41 foreign keys
  (`docs/migration/orphan-reference-analysis.md`), zero cross-tenant
  data inconsistencies (`docs/migration/tenant-boundary-verification.md`)
  — the migration does not need to design around reconciling broken
  references.
- **A short, known, pre-migration data-cleanup checklist covers the real
  defects found**, rather than leaving them as open unknowns: one stray
  non-standard collation (`configs` table,
  `docs/migration/character-set-and-collation-analysis.md`), 45 invalid
  zero-dates (`docs/migration/invalid-date-analysis.md`), and duplicate
  business-key groups in 4 tables lacking uniqueness constraints
  (`docs/migration/duplicate-business-key-analysis.md`). These are
  remediation tasks with known scope, not migration-pattern blockers.

**Explicit, non-optional carry-forward condition**: this evidence is a
single point-in-time snapshot (dump dated 2026-08-03). **Re-verify
volume and data-quality findings against a fresh snapshot before actual
cutover** — production may have grown or changed since — as a named
prerequisite of the cutover phase itself, not of this ADR's acceptance.

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

**Update 2026-08-04**: the full `docs/migration/` discovery template set
is now populated with real evidence, not left empty — `database-schema-inventory.md`,
`mysql-views-inventory.md`/`mysql-events-inventory.md`/`stored-procedure-and-function-inventory.md`/`trigger-inventory.md`
(all confirmed empty — zero views/events/procedures/triggers exist),
`data-quality-analysis.md`, `character-set-and-collation-analysis.md`,
`invalid-date-analysis.md`, `orphan-reference-analysis.md`,
`duplicate-business-key-analysis.md`, `table-volume-analysis.md`,
`sequence-and-identity-mapping.md`, `tenant-boundary-verification.md` —
measured directly against the real schema + a real data dump (loaded
into a throwaway, isolated Docker MySQL container, queried, destroyed;
no customer data reproduced in any document). Key results: zero orphan
references across all 41 foreign keys, zero cross-tenant data
inconsistencies, ~62K total rows across all tables (small — bulk-copy is
sufficient at this volume, not chunked/online replication), one stray
collation (`configs`), 45 invalid zero-dates, and real duplicate-name
groups in 4 tables lacking uniqueness constraints. `migration-validation-queries.md`
and `cutover-and-rollback-assumptions.md` remain partially open — they
depend on an actual migrated target and closer-to-cutover timing
respectively, not on missing schema/data evidence.

### Classification (2026-08-04 revision, decision recorded 2026-08-05)

This decision did not depend on the technical spike. Accepted by the
repository owner on 2026-08-05, using the evidence above directly —
nothing about it was still waiting on Discovery.

## Open Questions

- ~~which MySQL features are hardest to migrate~~ — **Resolved
  2026-08-04**: none found. Zero views, events, stored
  procedures/functions, or triggers exist in the schema (confirmed via
  the relevant inventory templates) — there is no server-side MySQL
  logic to port, the single biggest typical source of migration
  difficulty for this class of system.
- what rollback and reconciliation model is acceptable — still open,
  see `docs/migration/cutover-and-rollback-assumptions.md`

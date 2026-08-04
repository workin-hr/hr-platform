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

### Classification (2026-08-04 revision)

**Needs an actual decision now, informed by existing evidence — not
fully blocked.** The stored-procedure/trigger/view inventories being
confirmed empty already rules out the hardest class of MySQL-specific
migration risk (no server-side logic to port). The measured data
findings (no orphans, no tenant-boundary violations, small volume)
support deciding a **straightforward single-cutover bulk-copy approach**
now, with remediation steps for the confirmed data-quality findings
(zero-dates, `configs` collation, duplicate names) as pre-migration
cleanup tasks rather than open unknowns. **Caveat requiring explicit
carry-forward, not silently assumed away**: this evidence is a
single point-in-time snapshot (dump dated 2026-08-03) — re-verify
volume/quality findings against a fresh snapshot before actual cutover,
since production may have grown or changed since. Recommend: accept a
migration-approach direction now (single cutover, bulk copy, pre-migration
data cleanup checklist), with an explicit re-verification step against a
fresh snapshot as a named prerequisite of the cutover phase itself, not
of this ADR's acceptance.

## Open Questions

- ~~which MySQL features are hardest to migrate~~ — **Resolved
  2026-08-04**: none found. Zero views, events, stored
  procedures/functions, or triggers exist in the schema (confirmed via
  the relevant inventory templates) — there is no server-side MySQL
  logic to port, the single biggest typical source of migration
  difficulty for this class of system.
- what rollback and reconciliation model is acceptable — still open,
  see `docs/migration/cutover-and-rollback-assumptions.md`

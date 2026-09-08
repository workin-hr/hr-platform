# ADR-0017: MySQL Is The Production Database, And Stays So

## Metadata

| Field | Value |
|---|---|
| ADR ID | ADR-0017 |
| Title | MySQL Is The Production Database, And Stays So |
| Status | Accepted |
| Date | 2026-09-08 |
| Owners | Solution Architect |
| Deciders | Repository owner — instruction of 2026-09-07 ("we don't use PostgreSQL, so remove it from any place it exists; we use normal MySQL, because it's my prod DB now and I will convert it to be on a VPS instead of the Hostinger DB"), recorded in `docs/bootstrap/decision-log-wave12r.md` D-203 |
| Related Issues | None |
| Supersedes | ADR-0004 (the MySQL-to-PostgreSQL migration approach) and ADR-0011's Phase 2 (the storage migration). Amends ADR-0013: the `phase1-mysql` profile is no longer a profile, it is the application. |
| Superseded By | None |

## Context

ADR-0011 sequenced the work as *implementation, then storage, then
modernisation*: replace PHP with Java on the existing MySQL first (Phase 1),
migrate storage to PostgreSQL second (Phase 2). ADR-0004 designed that
migration and the repository carried both halves side by side — a PostgreSQL
domain with its own JPA entities, Flyway migrations, row-level security and a
tenant API under `/api/**`, gated behind the default profile; and the PHP port
over MySQL, gated behind `phase1-mysql`. `BackendApplication` anchored its
component scan at an empty package so that neither half could find the other.

The owner has decided that the production database is MySQL and will remain so:
the same database, moved from the Hostinger host to the VPS the Java
application deploys to. There is no Phase 2.

## Decision

MySQL (MariaDB 11.8 in production, D-037) is the application's only database.
Everything that existed to run against PostgreSQL, or to move data to it, is
removed rather than left dormant:

- the PostgreSQL domain — `identity`, `tenancy`, `authorization`'s enforcement
  half, and the eleven business packages (`advances`, `attendance`,
  `companysettings`, `employees`, `holidays`, `members`, `organization`,
  `payroll`, `penalties`, `requests`, `schedule`) — with their tests;
- Flyway and every migration under `db/migration`;
- the `phase2Test` source set and the ETL (`scripts/etl/`, `migration_diff.py`,
  the Flyway-version and RLS checks and their CI steps);
- the tenant security chain and its JWT filter;
- the `!phase1-mysql` / `phase1-mysql` profile split. `LegacyPersistenceConfig`
  is the persistence configuration, scanned like any other; the three
  environment profiles (`local`, `integration`, `prod`) remain and gate only
  environment values.

What stays is what the frozen PHP stack has: the legacy schema, `phase1_extensions.sql`
for the tables this application adds (platform administrators, their sessions,
audit), and the compensating tenant controls of ADR-0012 — which were written as
"until Phase 2" and are now permanent.

## Alternatives Considered

- **Keep the PostgreSQL half as a dormant option** -- the profile split, the
  Flyway migrations, the ETL and the domain packages, compiled and tested but
  never deployed. Rejected: the owner's instruction was to remove it, and a
  half the suite spends a third of its time on is not dormant; it is a tax on
  every push and a standing invitation to build on the wrong side of the
  split.
- **Remove the code but keep the migrations and ETL as history in the tree.**
  Rejected: they would fail the schema-drift and Flyway-version gates the
  moment they stopped being maintained, and `git log` already keeps them.
- **Migrate to PostgreSQL as originally planned (ADR-0004).** Rejected by the
  owner: the production database is MySQL and will move to the VPS as MySQL.

## Consequences

- **ADR-0012's controls are the tenant-isolation model**, not a stopgap. The
  application-level tenant guard (D-176, `AdminTenantGuardCoverageTest`) is the
  enforcement; there will be no row-level security underneath it.
- **The test suite runs on MariaDB throughout.** `AbstractIntegrationTest` — the
  base of the platform-admin tests — now takes its database from the suite's
  one MariaDB (PR 177's `LegacyMariaDb`) instead of starting PostgreSQL. Tests
  that existed only to exercise the PostgreSQL domain are deleted with it; the
  platform/client domain-separation test is rewritten against the client domain
  that actually remains, the PHP-format tokens the apps present.
- **R-040 is closed as moot.** The jar could not start under the default
  profile because nothing supplied `JdbcConnectionDetails`; there is no default
  profile any more.
- **The `JwtService` in `identity` stays.** It is not the PostgreSQL login: the
  legacy filter accepts, beside PHP-format tokens, a transitional Java-issued
  format, and `LegacyLoginService` issues it. That is part of the client
  contract D-111 freezes and is untouched here.
- The 58 documents under `docs/migration/` and the ETL-era discovery notes are
  **history, not plan**. They are left in place; this ADR and D-203 are the
  record that they no longer describe intended work.

## Risks

- **The tenant model has no database-level backstop, and now permanently.**
  ADR-0012 accepted that loss as an interim state that Phase 2's row-level
  security would end; there is no Phase 2. The application-level guard (D-041,
  D-176) is the only enforcement, and `AdminTenantGuardCoverageTest` is the
  gate that keeps every admin write behind it. Tracked under its own entry,
  **R-070** -- not R-046, which is the legacy dashboard's row-id writes and is
  closed.
- **A dependency on MariaDB/MySQL semantics is permanent**, including PHP's
  arithmetic (`PhpMath`) and the legacy schema's quirks. Accepted: that is the
  contract the clients already depend on (D-111).

## Rollback

`git revert` of the commit that carries this ADR restores every deleted file;
nothing was rewritten in place that a revert would not undo. There is no data
rollback because no data moved.

## Validation Evidence

Repository owner instruction, 2026-09-07 (quoted above). Full backend suite on
the MySQL-only tree, and the deployment E2E suite against the rebuilt image —
recorded in D-203.

## Open Questions

- None. The VPS move of the MySQL database itself is an operations task
  (`docs/operations/release-cutover-and-rollback.md`), not a decision this ADR
  leaves open.

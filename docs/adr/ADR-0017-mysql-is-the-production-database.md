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

## Rollback

`git revert` of the commit that carries this ADR restores every deleted file;
nothing was rewritten in place that a revert would not undo. There is no data
rollback because no data moved.

## Evidence

Repository owner instruction, 2026-09-07 (quoted above). Full backend suite on
the MySQL-only tree, and the deployment E2E suite against the rebuilt image —
recorded in D-203.

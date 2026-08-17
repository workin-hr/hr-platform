# Phase 1 — Punch List

## Purpose

D-040/ADR-0011 reset the migration sequencing on 2026-08-16: implement
Phase 1 (Java against the existing legacy MySQL/MariaDB contract) before
touching storage. That superseded
`2026-08-13-etl-next-steps-punch-list.md`'s priority order, which was
written for the ETL/PostgreSQL cutover path D-040 froze. This document
is Phase 1's equivalent — an ordered list of what is done and what is
next — built from the commits and decisions actually on
`phase1/legacy-auth-and-tenant-isolation` (PR #101), not just `main`. It
is a planning artifact — it does not itself implement anything, per this
repository's [`CLAUDE.md`](../../CLAUDE.md) boundary — and it indexes
`decision-log.md`, `ADR-0011`, and `ADR-0012` rather than replacing them.

## Done — merged to `main`

| # | Item | Evidence |
|---|---|---|
| 1 | MariaDB 11.8 substrate + `com.workin.legacy` adapter layer (`LegacyEmployee`, `LegacyEmployeeRepository`, `LegacyValues`), isolated outside the PostgreSQL scan root, schema drift-checked byte-identical against `hr-legacy` | PR #100, `cfef222` |
| 2 | ETL/PostgreSQL suite frozen into `phase2Test` — still compiles and self-tests in CI, runnable on demand via `./gradlew phase2Test`, not deleted | `cfef222` |

## Done — on PR #101, not yet on `main`

| # | Item | Evidence |
|---|---|---|
| 3 | D-040 / ADR-0011 — sequencing reset recorded (Phase 1 implementation-first, storage-second, modernize-third; strict contract parity; full 38-module replacement, not a strangler; freeze-not-delete for PostgreSQL/ETL) | `aee5280` |
| 4 | D-041 / ADR-0012 — application-enforced tenant isolation posture recorded, with its 4 mandatory controls | `aee5280` |
| 5 | `TenantScope` fail-closed core — `current()` raises rather than returning empty or all rows; 8 tests, no database required | `bc3ecc0` |
| 6 | Hibernate tenant filter + `TenantFilterActivator` + `TenantFilterCoverageTest` (build-failing architecture guard: every tenant-owned entity must be filtered) + `TenantFilterFailClosedTest` (ported from `RlsFailClosedTest`) | `c028fdf` |
| 7 | D-042 — Phase 1 keeps legacy login semantics (409 `MULTIPLE_ACCOUNTS_SAME_PHONE`, pending-account path, three 403 outcomes) but not legacy's 10-year JWT | `4342139` |
| 8 | Legacy login decision ported branch-for-branch from `login_employee.php:50-119`, as a pure function (`LegacyLoginResolver`) over already-fetched rows — **decision logic only, no HTTP controller, no JWT issuance for a legacy identity**. `TenantScopeFilter` binds/releases a tenant for one request and `TenantAwareJpaTransactionManager` binds the filter on every transaction (`NO_TENANT` sentinel, fail-closed) — **mechanism proven, but not wired to `SecurityConfig` or any real endpoint**: `TenantBindingEndToEndTest` and `TenantScopeFilterTest` exercise it with a hand-supplied resolver stub (e.g. `request -> Optional.of(801L)`), not a resolver derived from an authenticated legacy principal | `7030757`, `d5da4e6` |
| 9 | **Wire legacy login to a real endpoint and issue a JWT for legacy identities — done.** Required, discovered along the way, and built in order: `LegacyTenantContextService` (the trust-boundary re-derivation, not just claim-trusting); a parallel `legacy_refresh_tokens` rotation-with-reuse-detection state machine (`RefreshTokenService` had real FKs to the PostgreSQL identity model, unusable as-is); **ADR-0013/D-043** — the `phase1-mysql` Spring profile bootstrap mechanism, since no Phase 1 code had ever run inside a live Spring context (`PostgresPersistenceConfig`/`LegacyPersistenceConfig`, mutually exclusive; a build-failing `ProfileCoverageArchTest` guarding 33 Postgres-only classes across 5 mixed packages, built first per the accepted amendment); the `@Order(2)` `/api/legacy/**` security chain; `LegacyLoginController` at `POST /api/legacy/auth/login_employee`. Proven end-to-end over real HTTP against real MariaDB, with the default PostgreSQL profile and its ~50 tests confirmed unaffected. Two honest, documented gaps: the 409 `MULTIPLE_ACCOUNTS_SAME_PHONE` case isn't E2E-covered (the vendored schema's own `UNIQUE KEY (phone)` makes it unseedable against real MariaDB — covered instead at the pure-function level by `LegacyLoginResolverTest`), and no authenticated request against a *protected* legacy resource exists yet, since no protected legacy endpoint exists yet (that's items #11–13). | `528a6ec`, `23820f4`, `d2d3172`; design record `docs/migration/2026-08-17-phase1-legacy-login-endpoint-design.md`; ADR-0013/D-043 |
| 10 | **Complete auth-level tenant isolation tests — done.** `LegacyTenantContextIsolationTest` (real HTTP, real MariaDB, `phase1-mysql` active) covers: a forged `tenant_id` claiming a company the employee doesn't belong to; a `membership_id` claiming a different employee's row entirely; a signed token missing the tenant claim (401, fails closed rather than crashing); and the honest control case (a genuine token reads only its own company's data). Behavior differs from the PostgreSQL original by design, documented explicitly in the test: `legacySecurityFilterChain`'s resolver catches `NoTenantScopeException` itself, so a rejected claim reaches the controller unscoped rather than 404ing at the filter — what proves the claim was never trusted is the query then returning zero rows via the `NO_TENANT` sentinel, not a rejection status code. Used a minimal test-only probe endpoint (`LegacyIsolationProbeController`, never shipped) as the protected resource, since no real legacy business endpoint exists yet — the same move `TenantBindingEndToEndTest` made for the honest path. | `7294aeb` |

## Next, in order

| # | Item | Why it's next |
|---|---|---|
| 11 | **`hr_permissions` authorization mapping** | ADR-0010 already specifies the target design — map every legacy `hr_permissions` column (17 `can_*` booleans) to a canonical permission — but nothing implements it against the legacy MySQL contract yet. |
| 12 | **Remap the remaining built modules** | ADR-0011's discovery found 20 table names shared between the existing PostgreSQL entities and legacy MySQL. Those modules need the same treatment `employees` got — a Phase 1 adapter under `com.workin.legacy`, isolated from the PostgreSQL scan root — rather than a schema they don't actually run against in Phase 1. |
| 13 | **Implement the 19 missing legacy modules** | ADR-0011: 19 of 38 legacy API modules and 23 of 42 legacy tables have no Java counterpart at all — the finding that roughly doubles remaining Phase 1 engineering versus the pre-reset trajectory. |
| 14 | **PostgreSQL/ETL remains frozen for Phase 2** | D-040 — not deleted; `phase2Test` keeps compiling and self-testing in CI, untouched until Phase 2's storage migration begins. |

## Standing references

- Sequencing and scope: `docs/adr/ADR-0011-phase-sequencing.md`, D-040
- Tenant isolation posture: `docs/adr/ADR-0012-phase-1-tenant-isolation.md`, D-041
- Login/token contract: D-042
- MySQL-profile bootstrap mechanism: `docs/adr/ADR-0013-phase1-mysql-profile-bootstrap.md`, D-043
- Superseded: `docs/migration/2026-08-13-etl-next-steps-punch-list.md` (ETL/PostgreSQL path, frozen for Phase 2 per D-040)

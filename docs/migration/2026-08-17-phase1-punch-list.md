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

## Next, in order

| # | Item | Why it's next |
|---|---|---|
| 9 | **Wire legacy login to a real endpoint and design/issue a JWT for legacy identities** | Discovered 2026-08-17 while scoping the isolation-test item below: `LegacyLoginResolver` returns a decision but nothing calls it from HTTP, and nothing issues a token carrying a legacy identity's claims. `TenantScopeFilter` has no real resolver to bind. This is its own security design surface — endpoint contract, JWT claim shape for a legacy-authenticated identity, and what the resolver trusts — and needs an explicit call, not an unstated default, given D-042's constraint that outcomes must match `login_employee.php` exactly. PR #101's description ("Login/token implementation is deliberately not started") is accurate on this specific point even though items 6–8 landed after that text was written. |
| 10 | **Complete auth-level tenant isolation tests** | Blocked on #9 — there is no real HTTP path yet to send a forged/tampered tenant claim through. Once #9 lands: ADR-0012 item 3 calls for every `RlsFailClosedTest`/`TenantContextIsolationTest` scenario ported assertion-for-assertion. The RLS-fail-closed side is ported (`TenantFilterFailClosedTest`); `TenantContextIsolationTest`'s guarantee — a token's `membership_id`/`tenant_id` claims are never trusted without server-side re-validation — still lives only in the PostgreSQL-era `com.workin.backend.tenancy` suite and hasn't been re-proven against the legacy login/tenant-scope path. |
| 11 | **`hr_permissions` authorization mapping** | ADR-0010 already specifies the target design — map every legacy `hr_permissions` column (17 `can_*` booleans) to a canonical permission — but nothing implements it against the legacy MySQL contract yet. |
| 12 | **Remap the remaining built modules** | ADR-0011's discovery found 20 table names shared between the existing PostgreSQL entities and legacy MySQL. Those modules need the same treatment `employees` got — a Phase 1 adapter under `com.workin.legacy`, isolated from the PostgreSQL scan root — rather than a schema they don't actually run against in Phase 1. |
| 13 | **Implement the 19 missing legacy modules** | ADR-0011: 19 of 38 legacy API modules and 23 of 42 legacy tables have no Java counterpart at all — the finding that roughly doubles remaining Phase 1 engineering versus the pre-reset trajectory. |
| 14 | **PostgreSQL/ETL remains frozen for Phase 2** | D-040 — not deleted; `phase2Test` keeps compiling and self-testing in CI, untouched until Phase 2's storage migration begins. |

## Standing references

- Sequencing and scope: `docs/adr/ADR-0011-phase-sequencing.md`, D-040
- Tenant isolation posture: `docs/adr/ADR-0012-phase-1-tenant-isolation.md`, D-041
- Login/token contract: D-042
- Superseded: `docs/migration/2026-08-13-etl-next-steps-punch-list.md` (ETL/PostgreSQL path, frozen for Phase 2 per D-040)

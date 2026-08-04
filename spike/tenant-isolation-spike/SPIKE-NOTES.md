# H2 Spike Notes — Tenant Isolation (RLS vs. Repository Guard)

Live findings log for `docs/migration/technical-spike-plan.md`'s H2
experiment. This file is part of the disposable `spike/` tree — its
conclusions get promoted into `docs/migration/technical-spike-plan.md`
and `docs/adr/ADR-0002-modular-monolith-baseline.md` (Part B) as the
spike's deliverable; this file itself is not.

## What Was Built

Real Spring Boot 4.1.0 / Java 25 project (generated via the live
`start.spring.io`), Gradle wrapper build. Spring Modulith was in the
initial Initializr selection but removed once the spike was narrowed to
H2 only (H1's modulith-boundary tooling is explicitly out of scope for
this spike per `docs/migration/technical-spike-plan.md`'s revision) --
it added an unrelated `event_publication` schema requirement with no
value for what H2 actually tests. One interface (`BranchService`), two
implementations selected by Spring profile:

- `isolation-rls` — `RlsBranchService` deliberately calls the
  repository's **unscoped** `findAll`/`findById`; correctness depends
  entirely on `V3__enable_row_level_security.sql`'s Postgres RLS policy
  (`FORCE ROW LEVEL SECURITY`, fail-closed on an unset session variable)
  plus a per-transaction `SET LOCAL app.current_company_id`.
- `isolation-guard` — `GuardBranchService` calls the repository's
  **explicitly company_id-scoped** methods; no RLS exists in this
  database at all.

Both arms run identical controller/business logic (`BranchController`
depends only on the `BranchService` interface) — the only variable
between the two spike runs is which isolation mechanism sits underneath.

## Cross-Tenant Test (H2's Acceptance Criteria)

Real Postgres via Testcontainers (`postgres:17-alpine`), not H2/mocks.
Both test classes register two companies, have company B create a
branch, then attempt to read it as company A — the exact shape of the
bug found in 15 `hr-legacy` files (`hr-legacy#2/#3/#5/#6`).

- `RlsCrossTenantIsolationTest` — 3 cases: company A blocked from
  reading company B's branch by ID (even via an **unscoped** repository
  call); company A's list never includes company B's branch; company B
  can still read its own data (isolation isn't overly broad).
- `GuardCrossTenantIsolationTest` — 3 cases: the same two
  cannot-cross-read assertions via the **correctly-scoped** controller
  path, **plus** a deliberate demonstration
  (`forgettingToScopeLeaksCrossTenantData`) of what happens when
  application code "forgets" to scope — calling the repository's
  unscoped `findById` directly, exactly as a developer accidentally
  would. Because this database has no RLS, nothing catches the mistake:
  the test asserts the leak actually happens.

## Results (2026-08-05, real execution, both arms passing, clean-build reproduced)

- [x] `./gradlew clean test` — **BUILD SUCCESSFUL**, 6/6 tests passing,
      reproduced on a clean rebuild (not a stale-build fluke).
- [x] RLS arm (`RlsCrossTenantIsolationTest`): **3/3 passed** — company A
      blocked from reading company B's branch by ID via an unscoped
      repository call; company A's list never includes company B's
      branch; company B can still read its own data.
- [x] Guard arm (`GuardCrossTenantIsolationTest`): **3/3 passed** —
      company A blocked via the correctly-scoped controller path (both
      cases); **and** the deliberate `forgettingToScopeLeaksCrossTenantData`
      case confirmed the leak actually happens the moment a query skips
      the explicit `company_id` filter.
- [x] Setup/build time: ~33 minutes total, almost entirely the one-time
      Gradle 9.5.1 distribution download on this sandbox's slow
      connection to `services.gradle.org` (Maven Central itself was
      fast). Actual compile+test cycles after that: single-digit
      seconds each.

### A Real Bug Was Found And Fixed Along The Way: RLS Silently Did Nothing At First

The first real test run of the RLS arm **passed the app, failed the
isolation** — company A could read company B's branch (`200`, not
`404`). Root cause, confirmed by reading Postgres's own documentation
after seeing the behavior: **Postgres Row-Level Security is always
bypassed for superusers, regardless of `FORCE ROW LEVEL SECURITY`**
(`FORCE` only overrides the *table-owner* exemption, never the
superuser exemption). Testcontainers' `PostgreSQLContainer` default
user becomes the initdb superuser for a fresh container — so the
application was silently connecting as a role RLS could never apply to.

**This is exactly the kind of dangerous, easy-to-miss footgun a
hands-on spike exists to surface before it reaches production**: a team
adopting RLS without knowing this could deploy it, see it "work" in
every manual test (because a developer's own DB session is very likely
a superuser too), and have zero real tenant isolation in production the
moment the app connects with different credentials than expected --- or
the *opposite* failure, more relevant here: if the *production*
connection role happens to be an admin/superuser (a common shortcut),
RLS provides no protection at all while looking fully configured.

**Fix**: `V4__create_non_superuser_app_role.sql` creates a real,
unprivileged `app_runtime` role; `RlsDataSourceConfig` (RLS profile
only) gives JPA a `@Primary` DataSource connecting as that role while
Flyway keeps using the original superuser connection (`@FlywayDataSource`)
for migrations. This mirrors the realistic production shape (migrations
run as an owner/admin role, application runtime connects as a more
restricted one) and is not a spike-only workaround -- it is the correct
shape for real implementation too, and is now a **required setup
element** for choosing RLS, not an optional hardening step.

### Test Coverage Gap, Noted Honestly

The Guard arm has a deliberate test proving what happens when application
code "forgets" to scope (`forgettingToScopeLeaksCrossTenantData`). The
RLS arm has **no equivalent test** for "what if the code forgets to call
`setTenantSessionVariable()` before querying" -- every `RlsBranchService`
method in this spike always sets it first, so RLS's fail-closed design
(`NULLIF(current_setting(...), '')::BIGINT` -- an unset session variable
resolves to `NULL`, which never matches any `company_id`, so zero rows
are visible) was never exercised by a test that actually omits the call.
Real implementation should add this test explicitly before relying on
RLS's fail-closed behavior as a proven property rather than a design
intent.

### Operational Trade-Off Comparison

| | RLS | Repository Guard |
|---|---|---|
| Protects against a developer forgetting to scope a query | **Yes, structurally** (proven: the RLS arm's service deliberately uses unscoped queries and isolation still held) | **No** (proven: the deliberate "forgot to scope" test leaked cross-tenant data instantly, with nothing in the database catching it) |
| Setup complexity | Real and non-trivial: a dedicated non-superuser DB role (easy to get wrong -- silently fails closed... no, silently fails *open*, no error, no warning, just no protection), per-transaction session-variable wiring, a second DataSource/Flyway-qualifier configuration | Low: ordinary repository methods, no special DB role or session-variable machinery, works with standard connection pooling out of the box |
| Failure mode if misconfigured | **Silent and dangerous** -- looks fully set up, provides zero protection, no error at runtime (confirmed directly: this is exactly what happened on the first real run) | **Loud in code review, silent in production** -- a missing `company_id` filter is visible to anyone who reads the specific line, but nothing stops it from shipping, and it leaks immediately once it does |
| Matches hr-legacy's actual historical bug class | Directly addresses it -- `hr-legacy#2/#3/#5/#6`'s root cause (a missing check, scattered across dozens of call sites) is exactly the failure mode RLS closes structurally | Does not address the root cause -- repeats the same "depends on every call site remembering" shape that produced the original bugs, just relocated |

### Recommendation: Accept RLS As The Primary Mechanism, With An Explicit, Non-Optional Setup Requirement

Both mechanisms **work correctly when used as designed** -- this spike
does not find either one broken. The deciding factor is which failure
mode is safer to inherit at scale, given `hr-legacy`'s actual, repeated
history: the guard pattern's failure mode is identical in shape to the
15-file bug class already found in Discovery (a human forgets one
check, once, anywhere); RLS's failure mode requires a specific
misconfiguration (wrong DB role) that is a one-time, auditable setup
concern rather than a per-query, per-developer, forever risk.

**Recommend**: adopt RLS as the structural tenant-isolation mechanism
for `docs/adr/ADR-0002-modular-monolith-baseline.md` Part B, **on the
explicit condition that**:

1. The non-superuser application role requirement is treated as a hard
   architectural constraint, not a footnote -- ideally enforced by a
   startup-time check that fails loudly if the application's runtime
   DataSource ever connects as a superuser.
2. Repository-layer scoping is still applied where practical as a
   second, defense-in-depth layer -- not relied upon alone, but not
   discarded either, consistent with layered-defense practice for the
   system's highest-severity confirmed bug class.
3. The RLS-arm test-coverage gap noted above (a test for "forgot to set
   the session variable") is closed before this pattern is trusted in
   real implementation.

This is a recommendation for a human decider to accept, per
`docs/adr/ADR-0002-modular-monolith-baseline.md`'s own governance rule
-- this document does not itself change that ADR's `Status`.

## Evidence

`docs/migration/technical-spike-plan.md` (H2); `hr-legacy#2`, `#3`,
`#5`, `#6` (the bug class this test shape is modeled on); real
`./gradlew clean test` output, 2026-08-05, 6/6 tests passing across
`RlsCrossTenantIsolationTest` and `GuardCrossTenantIsolationTest`;
Postgres RLS-and-superuser behavior confirmed directly by observing the
first run's failure and its root cause, not assumed in advance.

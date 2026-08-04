# Technical Spike Plan

## Status

**Executed 2026-08-05 — H2 complete, real result obtained and
accepted.** Scope was cut 2026-08-04 from a 10-day, 6-hypothesis plan to
a single required 3-day spike (H2 only). Per direct instruction, the
spike was then actually built and run, not left as a plan: a real
Spring Boot 4.1/Java 25 project (built and run at
`spike/tenant-isolation-spike/`, excluded from `validate_phase0.py`'s
product-code scanner via the deliberate `SPIKE_DIR_NAME` exclusion,
deleted 2026-08-05 per the Rollback Strategy once its findings were
promoted below), real Postgres via Testcontainers, real cross-tenant
tests. **Result: `./gradlew clean test` — 6/6 tests passing, reproduced
on a clean rebuild.** Full findings, including a real bug found and
fixed mid-spike (Postgres RLS silently does nothing against a
superuser connection, which Testcontainers' default Postgres user is):
see "Full Spike Findings" below. **The spike's recommendation was
accepted in full by a human decider on 2026-08-05** — see
`docs/adr/ADR-0002-modular-monolith-baseline.md` Part B and
`docs/bootstrap/decision-log.md` D-018.

## Revision Summary — What's Required Vs. What's Not

Reviewed all 6 original hypotheses against one question: **does this
need dedicated, isolated experimental validation before real
implementation starts, or is it mature/low-risk enough to adopt
directly and validate organically while building the first real
module?**

| Hypothesis | Original framing | Revised classification | Why |
|---|---|---|---|
| **H2 — tenant isolation (RLS vs. repository-guard)** | Required | **Required — the only genuinely blocking spike** | This is the structural fix for the single most-repeated, highest-severity bug class found in `hr-legacy` Discovery (15 files, `hr-legacy#2/#3/#5/#6`). The choice shapes every module written afterward — expensive to retrofit if wrong. RLS's session-variable-per-request interaction with connection pooling is genuinely non-obvious and worth verifying hands-on before committing every module to it. |
| H1 — Spring Modulith boundaries | Required | **Not a blocker — validate organically** | Even if Spring Modulith's tooling turns out clunky, the fallback (manual package boundaries + ArchUnit rules) is still a perfectly good modular monolith. Nothing here is expensive to change mid-course. Confirm boundary discipline while building the first real module instead of a separate isolated exercise. |
| H3 — auth approach + Keycloak comparison | Required (1 day time-boxed for Keycloak) | **Not a blocker — implement directly; Keycloak comparison dropped** | The auth direction is now a confirmed decision (`docs/adr/ADR-0005-authentication-direction.md`, `docs/security/authentication-remediation-design.md`: short-lived JWT + refresh + revocation, forced re-authentication for existing users, no dual-validation). Spring Security JWT+refresh is mature, well-documented technology — implement it as the real auth module directly. The Keycloak comparison arm is superseded by this decision and dropped from scope entirely, not deferred. |
| H4 — springdoc-openapi mechanism | Required | **Not a blocker — adopt directly** | Mature, standard Spring Boot tooling. Additionally, the original rationale for even a mechanism-only test ("cannot validate real Flutter compatibility, blocked on PMR-02") no longer applies — PMR-02 is resolved with real evidence (`docs/api/flutter-request-response-compatibility.md`, `docs/api/three-frontend-api-usage-matrix.md`). Generate and review the real spec against real contract evidence as part of implementing the first real endpoint, not a synthetic slice. |
| H5 — testing stack (JUnit5/Testcontainers/ArchUnit/REST Assured) | Required | **Not a blocker — adopt directly** | Mature, standard 2026 Spring Boot testing stack; `docs/testing/test-strategy.md`/`quality-gate-cadence.md` already document the taxonomy. Set it up as part of the first real module's scaffolding. |
| H6 — observability baseline | Required | **Not a blocker — adopt directly** | Mature, standard practice (structured logging + correlation ID + OpenTelemetry auto-instrumentation). Adopt as part of initial project scaffolding, not a separate validation exercise. |

**Net effect**: the spike shrinks from 10 days / 6 hypotheses to
**3 days / 1 hypothesis**. Everything else becomes first-milestone
implementation work instead of pre-implementation validation.

## Purpose

Validate the **one** technology choice genuinely uncertain enough to
need hands-on proof before committing every module to it: the tenant-isolation
mechanism (H2). This closes the blocking portion of PMR-07 and feeds
evidence directly into `docs/adr/ADR-0002-modular-monolith-baseline.md`'s
tenant-isolation-pattern detail. The other ADRs this spike originally
targeted (ADR-0003, ADR-0004, ADR-0005, ADR-0007, ADR-0008) are addressed
directly in `docs/migration/pre-migration-readiness-gap-analysis.md`'s
revised ADR classification (2026-08-04) — most move toward acceptance now
using existing evidence, without needing this spike.

## Vertical Slice Scope

**Tenant/company identity, plus one simple reference-data endpoint.**
Concretely:

1. **Company identity**: register a company (name, phone, password),
   hash the password, log in, issue a JWT. Deliberately excludes the
   real system's OTP/WhatsApp verification flow — that is orthogonal to
   what this spike tests (stack viability), and adding it would pull in
   an external delivery dependency the spike doesn't need.
2. **One reference-data endpoint, tenant-scoped**: `branches` (or an
   equally simple entity — `job_titles` is an acceptable substitute) —
   plain CRUD, scoped to the authenticated company via `company_id`.

**Explicitly excluded from this spike**: payroll, attendance, device
integration, or any other high-risk domain, per direct instruction. If
during execution any experiment starts to require touching one of these
domains to proceed, stop and flag it rather than expanding scope.

## The Required Spike: H2 — Tenant Isolation Mechanism

The spike is now scoped to this one hypothesis. Fully falsifiable — the
spike is expected to produce a real Accept/Revise/Reject answer, not a
foregone conclusion.

### H2 — PostgreSQL + Flyway can represent the multi-tenant model, and Row-Level Security is a viable structural fix for the tenant-isolation bug class found in `hr-legacy`

- **Decision it validates**: how the new backend structurally prevents
  the cross-tenant access class confirmed in `hr-legacy#2/#3/#5/#6` (and
  data-level-clean per `docs/migration/tenant-boundary-verification.md`
  — the data itself is consistent, only the authorization layer is
  missing) — Postgres Row-Level Security vs. a repository-layer guard
  pattern.
- **Minimal implementation scope**: version the schema for `companies`
  and `branches` via Flyway migrations (the same minimal vertical slice
  as originally scoped — tenant identity + one reference-data entity, no
  payroll/attendance/device domains). Implement tenant isolation two
  ways for direct comparison: (a) Postgres RLS keyed on a session
  variable set per request, and (b) a repository-layer guard (the
  `org_verify_post_row()` pattern already proven correct in 4
  `hr-legacy` dashboard modules).
- **Acceptance criteria**: an automated test that attempts a
  cross-tenant read/write — the exact shape of the bug found in 15
  `hr-legacy` files — is written and confirmed to correctly **block**
  the attempt under both approaches, with a recorded trade-off
  comparison (ORM/connection-pooling interaction for RLS,
  discipline-dependence for the repository guard).
- **Required environment/access**: local Postgres (Docker), Java
  25/Spring Boot 4.x scaffold, Flyway — no production data, no Flutter
  client, no device access needed (same as the original plan's "What
  Can Be Tested Without..." analysis, still accurate for this narrowed
  scope).
- **Expected output/evidence**: a working (disposable) two-table slice
  with both isolation approaches implemented, the cross-tenant test
  passing against both, and a short written recommendation for which
  pattern (or which pattern per data-sensitivity tier) the real backend
  adopts.
- **Blocking or parallel-safe?**: **Real implementation blocker.** This
  is the one decision expensive to retrofit once dozens of modules are
  built against it — every module's data-access layer depends on
  whichever pattern is chosen here.
- **Falsifies if**: RLS introduces enough operational complexity
  (connection pooling, session-variable propagation through the ORM) to
  outweigh its structural-safety benefit for this project's scale.

## Moved To First-Milestone Implementation (Not Spiked)

The remaining five original hypotheses are no longer separate
pre-implementation experiments — they're mature/low-risk enough to
adopt directly as part of building the first real module, per the
Revision Summary table above. Recorded here for traceability, not as
spike work:

- **H1 (Modulith boundaries)**: adopt Spring Modulith (or plain package
  boundaries + ArchUnit if it proves clunky) while building the first
  real module; not isolated pre-validation.
- **H3 (Auth approach)**: implement Spring Security with short-lived
  JWT + refresh + revocation directly, per the confirmed decision in
  `docs/security/authentication-remediation-design.md`. The Keycloak
  comparison arm is dropped, not deferred — the direction is decided.
- **H4 (springdoc-openapi)**: generate and review the real OpenAPI spec
  against the real Flutter contract evidence (now available,
  `docs/api/flutter-request-response-compatibility.md`) as part of
  implementing the first real endpoint.
- **H5 (testing stack)**: set up JUnit 5 + Testcontainers + ArchUnit +
  REST Assured as part of first-module scaffolding — this stack is also
  what H2's own acceptance criteria above needs, so it gets exercised
  regardless.
- **H6 (observability baseline)**: structured logging + correlation ID +
  OpenTelemetry auto-instrumentation, adopted as part of initial project
  scaffolding.

## What Can Be Tested Without Flutter, Production Data, Or Devices

**Everything in this narrowed spike.** The vertical slice needs none of
the three biggest external unknowns:

- Tenant identity and reference-data CRUD do not require the real
  Flutter client. (Real Flutter contract evidence is now available
  anyway — `docs/api/flutter-request-response-compatibility.md`,
  PMR-02 resolved 2026-08-04 — but this spike's H2 scope doesn't touch
  API-contract validation either way.)
- A fresh Flyway-versioned schema for two new tables does not require
  production data (real schema/data analysis is now separately
  available too — `docs/migration/data-quality-analysis.md` and
  siblings, filled in 2026-08-04 — again, orthogonal to H2).
- Neither endpoint touches attendance hardware.

## What Must Remain Blocked

- **Payroll/attendance business-logic migration** — blocked on the
  product decisions in PMR-06 and requires its own, later, higher-risk
  spike or direct planning once this one's findings are in.
- **Data-migration cutover mechanics requiring a fresh production
  snapshot** — the 2026-08-04 analysis pass covers what the available
  dump can prove; live cutover timing/downtime estimation still needs a
  snapshot closer to actual migration.
- **Final device/gateway validation** — blocked on hardware/vendor
  access (PMR-04); the vendor-neutral architecture itself is now
  designed ahead of that access, see
  `docs/devices/device-integration-architecture.md`.

Do not let spike momentum pull any of these into scope. If an experiment
seems to require one of them, that is a signal to stop and note it, not
to expand the slice.

## Proposed Time-Box

**3 working days, proposed for human confirmation or adjustment — not a
committed deadline. Revised down from the original 10.** Suggested
allocation:

| Day | Focus |
|---|---|
| 1 | Environment setup: Java 25, Spring Boot 4.x scaffold, Postgres via Docker, Flyway baseline; company identity (register, login, JWT issuance) |
| 2 | `branches` CRUD, tenant-scoped; implement and test both RLS and repository-guard tenant isolation (H2) |
| 3 | Write the cross-tenant test, confirm both approaches block it, record trade-offs, write findings/recommendation, review checkpoint, teardown per rollback strategy |

If the time-box is exceeded, that is itself a reportable finding (see
Exit Criteria), not a reason to silently extend without flagging it.

## Deliverables

1. A working (but disposable — see Rollback Strategy) vertical-slice
   codebase satisfying the exit criteria below.
2. A written spike report: what was tried, what was found, and whether
   H2 confirms or falsifies.
3. An explicit recommendation — **Accept**, **Revise**, or **Reject** —
   for the tenant-isolation pattern, feeding directly into
   `docs/adr/ADR-0002-modular-monolith-baseline.md`'s tenant-isolation
   detail. This spike no longer produces recommendations for
   ADR-0003/0004/0005/0007/0008 — those are addressed directly via
   existing evidence in the revised gap-analysis ADR classification
   (2026-08-04), not gated on this spike.
4. A recorded comparison of RLS vs. repository-layer tenant-isolation,
   since this directly informs how the migration addresses the most
   repeated bug class found in `hr-legacy` Discovery.

## Risks Of The Spike Itself

- **Scope creep into real implementation.** Mitigation: the vertical
  slice boundary is explicit and enforced by the rollback strategy below
  (spike code cannot become production code by default); the day-3
  review checkpoint catches drift before it compounds.
- **Environment/tooling setup consuming a disproportionate share of the
  now-3-day time-box.** Mitigation: if setup isn't done within the first
  half of day 1, that itself is a finding about tooling friction,
  reported honestly rather than absorbed silently into later days.
- **A vertical slice this simple produces false confidence** by never
  encountering `hr-legacy`'s real complexity. Mitigation: the
  cross-tenant test is deliberately modeled directly on the actual bug
  class found in 15 `hr-legacy` files — the one place this spike
  intentionally reaches for real complexity rather than staying trivial.

## Rollback / Discard Strategy

**Spike code must not be able to accidentally become production code.**

- **Preferred**: spike work happens in a separate, throwaway repository
  (e.g. `workin-hr/spike-tech-validation`), created only after explicit
  human approval — repository creation is a real action, not something
  to do unprompted. The repository is archived or deleted at spike
  conclusion regardless of outcome.
- **Fallback**, if a new repository is not wanted: a `spike/` directory
  at the `hr-platform` repository root, explicitly outside the governed
  component boundaries (`backend/`, `admin-web/`, `edge-gateway/`, etc.
  — those are reserved for real implementation, per
  `docs/bootstrap/execution-checklist.md`'s Notes section), with a
  mandatory `README.md` declaring it disposable. This directory would
  need explicit exclusion from `validate_phase0.py`'s CODEOWNERS
  component-coverage check, added deliberately, not silently.
- **Either way**: the spike branch/repository is deleted (not merged to
  `main` as "done," not left lingering as an ambiguous half-artifact)
  once the report is written. Only the **written report** and any
  explicitly graduated, deliberately-chosen reusable outputs (e.g. a
  validated Flyway migration-naming convention, a proven ArchUnit rule
  set) are intentionally promoted into the real repository — as a
  separate, deliberate decision after the spike, never by default.

## Exit Criteria

The spike is complete when all of the following are true:

- [x] The vertical slice runs end-to-end: register a company, log in,
      obtain a JWT, perform tenant-scoped CRUD on `branches`. Confirmed
      via the real HTTP flow both cross-tenant test classes exercise
      (`register` → JWT → authenticated `POST`/`GET /api/branches`).
- [x] The cross-tenant isolation test demonstrates both the RLS and
      repository-guard approaches correctly block a cross-tenant
      access attempt, with a recorded trade-off comparison. 6/6 tests
      passing; full comparison in "Full Spike Findings" below.
- [x] A written recommendation (Accept/Revise/Reject) exists for the
      tenant-isolation pattern, feeding `docs/adr/ADR-0002-modular-monolith-baseline.md`.
      **Recommendation: Accept RLS as the primary mechanism**, on the
      explicit condition of a non-superuser application DB role (a real
      bug found and fixed mid-spike — see "Full Spike Findings" below),
      retaining repository-layer scoping as a secondary defense-in-depth
      layer. **Accepted in full by a human decider, 2026-08-05** — see
      ADR-0002 Part B and `docs/bootstrap/decision-log.md` D-018.
- [x] The time-box was respected, or its overrun is explicitly reported
      with a reason. **Overrun**: ~33 minutes of the ~3-day time-box was
      consumed by this sandbox's slow one-time Gradle distribution
      download; actual engineering time (writing the slice, debugging
      three real environment-specific issues, and fixing the RLS/superuser
      bug) fit comfortably within the 3-day allocation. Reported
      honestly per this document's own instruction, not absorbed
      silently.
- [x] **Done 2026-08-05**: the spike codebase was torn down per the
      Rollback Strategy, after human review and after this document
      absorbed the full findings below (the only thing the Rollback
      Strategy requires to survive deletion). `spike/` no longer exists
      in this repository.

## Full Spike Findings (Promoted From `spike/tenant-isolation-spike/SPIKE-NOTES.md`)

**Promoted here 2026-08-05, immediately before the `spike/` directory
was deleted per the Rollback Strategy above** ("Only the written report
... [is] intentionally promoted into the real repository ... as a
separate, deliberate decision after the spike, never by default"). This
section is now the permanent, authoritative record of what the spike
built and found — any older reference elsewhere in this repository to
`spike/tenant-isolation-spike/SPIKE-NOTES.md` should be read as pointing
here.

### What Was Built

Real Spring Boot 4.1.0 / Java 25 project (generated via the live
`start.spring.io`), Gradle wrapper build. Spring Modulith was in the
initial Initializr selection but removed once the spike was narrowed to
H2 only (H1's modulith-boundary tooling is explicitly out of scope for
this spike per the Revision Summary above) — it added an unrelated
`event_publication` schema requirement with no value for what H2
actually tests. One interface (`BranchService`), two implementations
selected by Spring profile:

- `isolation-rls` — `RlsBranchService` deliberately calls the
  repository's **unscoped** `findAll`/`findById`; correctness depends
  entirely on a Postgres RLS policy (`FORCE ROW LEVEL SECURITY`,
  fail-closed on an unset session variable) plus a per-transaction
  `SET LOCAL app.current_company_id`.
- `isolation-guard` — `GuardBranchService` calls the repository's
  **explicitly `company_id`-scoped** methods; no RLS exists in this
  database at all.

Both arms run identical controller/business logic (`BranchController`
depends only on the `BranchService` interface) — the only variable
between the two spike runs is which isolation mechanism sits underneath.

### Cross-Tenant Test (H2's Acceptance Criteria)

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

### Results (2026-08-05, real execution, both arms passing, clean-build reproduced)

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

#### A Real Bug Was Found And Fixed Along The Way: RLS Silently Did Nothing At First

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
moment the app connects with different credentials than expected — or
the *opposite* failure, more relevant here: if the *production*
connection role happens to be an admin/superuser (a common shortcut),
RLS provides no protection at all while looking fully configured.

**Fix**: a dedicated migration created a real, unprivileged
`app_runtime` role; a profile-scoped `RlsDataSourceConfig` gave JPA a
`@Primary` DataSource connecting as that role while Flyway kept using
the original superuser connection (`@FlywayDataSource`) for migrations.
This mirrors the realistic production shape (migrations run as an
owner/admin role, application runtime connects as a more restricted
one) and is not a spike-only workaround — it is the correct shape for
real implementation too, and is now a **required setup element** for
choosing RLS, not an optional hardening step.

#### Test Coverage Gap, Noted Honestly

The Guard arm has a deliberate test proving what happens when application
code "forgets" to scope (`forgettingToScopeLeaksCrossTenantData`). The
RLS arm has **no equivalent test** for "what if the code forgets to call
`setTenantSessionVariable()` before querying" — every `RlsBranchService`
method in this spike always sets it first, so RLS's fail-closed design
(`NULLIF(current_setting(...), '')::BIGINT` — an unset session variable
resolves to `NULL`, which never matches any `company_id`, so zero rows
are visible) was never exercised by a test that actually omits the call.
Real implementation should add this test explicitly before relying on
RLS's fail-closed behavior as a proven property rather than a design
intent.

#### Operational Trade-Off Comparison

| | RLS | Repository Guard |
|---|---|---|
| Protects against a developer forgetting to scope a query | **Yes, structurally** (proven: the RLS arm's service deliberately uses unscoped queries and isolation still held) | **No** (proven: the deliberate "forgot to scope" test leaked cross-tenant data instantly, with nothing in the database catching it) |
| Setup complexity | Real and non-trivial: a dedicated non-superuser DB role (easy to get wrong — silently fails closed... no, silently fails *open*, no error, no warning, just no protection), per-transaction session-variable wiring, a second DataSource/Flyway-qualifier configuration | Low: ordinary repository methods, no special DB role or session-variable machinery, works with standard connection pooling out of the box |
| Failure mode if misconfigured | **Silent and dangerous** — looks fully set up, provides zero protection, no error at runtime (confirmed directly: this is exactly what happened on the first real run) | **Loud in code review, silent in production** — a missing `company_id` filter is visible to anyone who reads the specific line, but nothing stops it from shipping, and it leaks immediately once it does |
| Matches hr-legacy's actual historical bug class | Directly addresses it — `hr-legacy#2/#3/#5/#6`'s root cause (a missing check, scattered across dozens of call sites) is exactly the failure mode RLS closes structurally | Does not address the root cause — repeats the same "depends on every call site remembering" shape that produced the original bugs, just relocated |

#### Recommendation: Accept RLS As The Primary Mechanism, With An Explicit, Non-Optional Setup Requirement

Both mechanisms **work correctly when used as designed** — this spike
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
   architectural constraint, not a footnote — ideally enforced by a
   startup-time check that fails loudly if the application's runtime
   DataSource ever connects as a superuser.
2. Repository-layer scoping is still applied where practical as a
   second, defense-in-depth layer — not relied upon alone, but not
   discarded either, consistent with layered-defense practice for the
   system's highest-severity confirmed bug class.
3. The RLS-arm test-coverage gap noted above (a test for "forgot to set
   the session variable") is closed before this pattern is trusted in
   real implementation.

This recommendation was accepted in full by a human decider on
2026-08-05 — see `docs/adr/ADR-0002-modular-monolith-baseline.md` Part B
and `docs/bootstrap/decision-log.md` D-018.

## Evidence

Builds on `docs/migration/pre-migration-readiness-gap-analysis.md`
(PMR-07), `docs/tools/tool-catalog.md`, `docs/tools/tool-decision-matrix.md`,
`docs/security/threat-model.md` (the tenant-isolation findings this
spike directly tests against),
`docs/migration/tenant-boundary-verification.md` (confirms the data
itself is clean — this spike addresses the authorization-layer gap, not
data corruption), and ADR-0002. Revision rationale (2026-08-04, cutting
H1/H3/H4/H5/H6 from spike scope): see "Revision Summary" at the top of
this document and the ADR classification in
`docs/migration/pre-migration-readiness-gap-analysis.md`. **Execution
evidence (2026-08-05)**: a real Spring Boot 4.1/Java 25 project was
built and run at `spike/tenant-isolation-spike/` (Testcontainers
Postgres, 6/6 tests passing, reproduced on a clean rebuild), reviewed,
and then deleted per the Rollback Strategy above once its findings were
fully absorbed into "Full Spike Findings" above — the section is now
the permanent record, not the deleted directory.
`scripts/validate_phase0.py`'s `SPIKE_DIR_NAME` exclusion (added
deliberately for that execution, with regression test coverage in
`scripts/test_validate_phase0.py`) is no longer exercised now that
`spike/` is gone, but is left in place undisturbed as a documented,
narrow, tested carve-out ready for any future spike.

# ADR-0002: Modular Monolith Baseline

## Metadata

| Field | Value |
|---|---|
| ADR ID | ADR-0002 |
| Title | Modular Monolith Baseline |
| Status | Accepted |
| Date | 2026-08-02 (Part A accepted 2026-08-04 — see `docs/bootstrap/decision-log.md` D-016; Part B accepted 2026-08-05 — see D-018). Both parts are now Accepted; the `Status` field applies to the ADR as a whole. |
| Owners | Solution Architect (see `docs/agents/responsibility-matrix.md`) |
| Deciders | Human engineering leadership — recorded at approval time in `docs/bootstrap/decision-log.md` |
| Related Issues | None yet |
| Supersedes | None |
| Superseded By | None |

## Context

The target system needs a credible delivery path for an MVP in roughly two months without unnecessary operational complexity.

## Decision

**Both parts are Accepted.** Part A: 2026-08-04, recorded in
`docs/bootstrap/decision-log.md` D-016. Part B: 2026-08-05, recorded in
D-018, following the H2 spike's real, executed recommendation below.

This decision has two parts, deliberately split so that the strategic
choice was not held hostage to a narrow technical detail still pending
evidence:

**Part A — Strategic architecture decision: Accepted.** Use a modular
monolith as the system's baseline architecture — a single deployable
unit with explicit internal module boundaries (Spring Modulith or
equivalent package-boundary discipline), not microservices from day one
and not an undifferentiated layered monolith. Module implementation may
proceed against this direction and the candidate boundary diagram in
`docs/architecture/module-boundaries.md`.

**Part B — Tenant-isolation implementation detail: Accepted 2026-08-05.**
*How* tenant isolation is structurally enforced across
every module — PostgreSQL Row-Level Security vs. a repository-layer
guard pattern — was tested hands-on, not just planned:
`docs/migration/technical-spike-plan.md`'s H2 experiment ran a real
Spring Boot 4.1/Java 25 vertical slice (built and run at
`spike/tenant-isolation-spike/`, deleted 2026-08-05 after its findings
were promoted — see below)
against real Postgres via Testcontainers, with both mechanisms
implemented and a deliberate cross-tenant attack test for each,
modeled directly on `hr-legacy#2/#3/#5/#6`.

**Result — 6/6 tests passing, reproduced on a clean rebuild.** Both
mechanisms work correctly when used as designed. The spike also found
and fixed a real, dangerous bug along the way: Postgres Row-Level
Security is always bypassed for superusers regardless of `FORCE ROW
LEVEL SECURITY`, and Testcontainers' default Postgres user is a
superuser — meaning the RLS arm initially passed the app while silently
providing **zero** actual isolation, until a dedicated non-superuser
application role was introduced. Full findings, the operational
trade-off comparison, and the deliberate "forgot to scope" demonstration
(which the repository-guard arm failed exactly as hr-legacy's real bugs
did): `docs/migration/technical-spike-plan.md`'s "Full Spike Findings" section.

**Decided**: adopt **RLS as the primary tenant-isolation mechanism**,
accepted in full by a human decider on 2026-08-05, on the explicit,
non-optional condition that:

1. The non-superuser application role requirement is treated as a hard
   architectural constraint, not a footnote — ideally enforced by a
   startup-time check that fails loudly if the application's runtime
   DataSource ever connects as a superuser.
2. Repository-layer scoping is still applied where practical as a
   second, defense-in-depth layer — not relied upon alone, but not
   discarded either.
3. **The RLS-arm test-coverage gap found during the spike is closed
   before this pattern is trusted in real implementation** — fully
   specified below so it cannot be silently forgotten or reinterpreted
   during implementation:

   **Required test — "forgot to set the tenant session variable proves
   fail-closed, not fail-open":**
   - **Given**: two tenants exist (company A, company B); company B has
     created at least one row in an RLS-protected table; the querying
     code path executes **without** first calling the session-variable
     setter (`SET LOCAL app.current_company_id`/`setTenantSessionVariable()`
     or equivalent) — simulating a developer who forgot to wire tenant
     context before this query, the exact human-error mode this
     ADR's Part B decision is meant to close structurally.
   - **When**: that unscoped-context query attempts to read company B's
     row (by ID, and via a list/`findAll`-style query).
   - **Then**: zero rows are returned (a 404/empty result, not an error,
     not company B's data) — proving the RLS policy's
     `NULLIF(current_setting('app.current_company_id', true), '')::BIGINT`
     fail-closed design actually holds when the setter is skipped, not
     merely assumed to hold because every other test happens to call the
     setter first.
   - **Explicitly not satisfied by**: any test that still calls the
     session-variable setter before querying (that is what every
     existing RLS-arm test in the spike already did) or that only tests
     the *guard* pattern's forgot-to-scope case (`GuardCrossTenantIsolationTest.forgettingToScopeLeaksCrossTenantData`
     in the spike, which tests the guard arm, not RLS).
   - **Where this must live**: as a real, automated test in the first
     backend module that adopts RLS, run in CI like every other test in
     `docs/testing/test-strategy.md`'s taxonomy — not a manual check, not
     a one-time verification, not deferred to a later module "once
     things are stable."

   This condition is a documentation-level specification, written so the
   test is unambiguous to implement — it is not itself the test's
   execution. Execution happens during real backend implementation
   (`backend/`), which is out of `hr-platform`'s planning-only scope per
   `CLAUDE.md` absent explicit, scoped authorization (as was given for
   the H2 spike itself).

All three conditions are implementation requirements for whoever builds
the first module using RLS, not optional follow-ups — they carry
forward into `docs/migration/consolidated-task-matrix.md` as acceptance
criteria, not just this ADR's text.

## Alternatives Considered

- microservices from the start
- layered monolith without explicit modular boundaries

## Consequences

- simplifies delivery and operations during MVP
- keeps module boundary work inside one deployment unit initially
- requires disciplined internal boundaries to avoid accidental monolith sprawl
- **Added 2026-08-04**: a first-pass module boundary diagram now exists
  (`docs/architecture/module-boundaries.md`) — 9 candidate modules
  (Identity & Access, Platform Administration, Organization Structure,
  Workforce, Attendance, Payroll & Compensation, Leave & Requests,
  Notifications, Platform Content & Reference Data), derived from the
  real legacy module inventory and the capability/ownership matrix, not
  invented. This is implementation guidance, not a locked-in structure —
  expected to be refined once real modules are built.
- **Added 2026-08-04**: measurable module-extraction criteria now exist
  (`docs/architecture/module-boundaries.md`, "Module-Extraction
  Criteria") — independent scaling, independent deployment cadence,
  operational isolation, sustained performance bottleneck, distinct
  ownership/release cadence, and a coupling-based gating factor. Directly
  answers this ADR's former "what measurable threshold would justify
  decomposition" open question.

## Risks

- module boundaries drift into a tangled monolith without enforced internal contracts
- deferred decomposition becomes harder the longer real module coupling is left unmeasured

## Validation Evidence

**Update 2026-08-04**: module boundary candidates are now formalized,
not just informed — `docs/legacy/existing-php-module-inventory.md`
(the full 38-module API surface + 34-page dashboard structure) and
`docs/api/three-frontend-api-usage-matrix.md`'s capability/ownership
matrix feed directly into `docs/architecture/module-boundaries.md`'s
diagram and legacy-mapping table (added 2026-08-04). This satisfies
Part A's evidence requirement in full — nothing about Part A's
acceptance is still waiting on Discovery.

**Update 2026-08-05**: Part B's evidence requirement is now also
satisfied — `docs/migration/technical-spike-plan.md`'s H2 experiment
was executed for real (not just planned), producing a recorded
recommendation (RLS, with the non-superuser-role condition) backed by a
working, reproducible test suite. See
`docs/migration/technical-spike-plan.md`'s "Full Spike Findings"
section. **Part B was accepted by a human decider on 2026-08-05** — see
`docs/bootstrap/decision-log.md` D-018.

## Open Questions

- ~~which domains become modules first~~ — **Resolved 2026-08-04**: see
  `docs/architecture/module-boundaries.md`'s 9-module diagram and
  legacy-mapping table. Treat as a candidate first cut, refined
  organically once real modules are built, not as a final locked
  structure.
- ~~what measurable threshold would justify decomposition~~ —
  **Resolved 2026-08-04**: see `docs/architecture/module-boundaries.md`'s
  "Module-Extraction Criteria" section — 5 concrete triggers plus a
  coupling-based gating factor, none currently met by any module (this
  is a forward-looking framework, not a current extraction
  recommendation).
- ~~Part B: RLS vs. repository-guard tenant isolation~~ — **Resolved
  2026-08-05**: `docs/migration/technical-spike-plan.md` H2 produced a
  recorded recommendation (RLS, primary mechanism, with the
  non-superuser-role condition), accepted in full by a human decider —
  see `docs/bootstrap/decision-log.md` D-018.
- The RLS-arm test-coverage gap found during the spike (no test yet
  proves RLS's fail-closed behavior when the session-variable-setting
  call itself is omitted, unlike the Guard arm's deliberate "forgot to
  scope" test) remains open **as an implementation acceptance
  criterion**, not as a blocker on this ADR's acceptance — see Decision,
  condition 3.

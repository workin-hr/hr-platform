# Technical Spike Plan

## Status

**Revised 2026-08-04 — scope cut from a 10-day, 6-hypothesis plan down
to a single required 3-day spike, per explicit direction not to treat
the full plan as a blanket blocker.** Still Proposed/not started. No
spike work begins until this revised plan itself is approved (see
`docs/migration/pre-migration-readiness-gap-analysis.md`, PMR-07, and
the Migration-Readiness Gate). This spike does not authorize or
constitute the start of migration implementation.

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

- [ ] The vertical slice runs end-to-end: register a company, log in,
      obtain a JWT, perform tenant-scoped CRUD on `branches`.
- [ ] The cross-tenant isolation test demonstrates both the RLS and
      repository-guard approaches correctly block a cross-tenant
      access attempt, with a recorded trade-off comparison.
- [ ] A written recommendation (Accept/Revise/Reject) exists for the
      tenant-isolation pattern, feeding `docs/adr/ADR-0002-modular-monolith-baseline.md`.
- [ ] The time-box was respected, or its overrun is explicitly reported
      with a reason.
- [ ] The spike codebase is fully torn down per the Rollback Strategy,
      with only the report (and any explicitly graduated outputs)
      retained.

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
`docs/migration/pre-migration-readiness-gap-analysis.md`.

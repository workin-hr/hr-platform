# Technical Spike Plan

## Status

**Proposed — not yet approved or started.** This document is a plan for
human review. No spike work begins until the plan itself is approved (see
`docs/migration/pre-migration-readiness-gap-analysis.md`, PMR-07, and the
Migration-Readiness Gate at the end of that document). This spike does
not authorize or constitute the start of migration implementation.

## Purpose

Validate the technology choices already recorded in
`docs/tools/tool-catalog.md`/`tool-decision-matrix.md` against this
project's real characteristics — before committing to them at
full-implementation scale — using one small, low-risk, representative
vertical slice. This closes PMR-07 and feeds evidence directly into
whether ADR-0002, ADR-0003, ADR-0004, ADR-0005, ADR-0007, and ADR-0008
move toward Accepted, Revised, or Rejected.

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

## Hypotheses And Experiments

Each hypothesis is falsifiable — the spike is expected to produce a real
Accept/Revise/Reject answer, not a foregone conclusion.

### H1 — Java 25 / Spring Boot 4.x can express ADR-0002's modular-monolith boundaries without excessive ceremony

- **Experiment**: Scaffold the slice as two Spring Modulith modules
  (`identity`, `reference-data`) with explicit module boundaries.
  Attempt a deliberately incorrect cross-module internal-package
  reference and confirm Spring Modulith's verification catches it
  (`ApplicationModules.verify()`). Measure setup time and boilerplate.
- **Falsifies if**: boundary enforcement requires disproportionate
  ceremony for a project this size, or Spring Modulith's assumptions
  don't fit this domain.

### H2 — PostgreSQL + Flyway can represent the multi-tenant model, and Row-Level Security is a viable structural fix for the tenant-isolation bug class found in `hr-legacy`

- **Experiment**: Version the schema for `companies` and `branches` via
  Flyway migrations. Implement tenant isolation two ways for direct
  comparison: (a) Postgres RLS keyed on a session variable set per
  request, and (b) a repository-layer guard (the `org_verify_post_row()`
  pattern already proven correct in 4 `hr-legacy` dashboard modules).
  Write an automated test that attempts a cross-tenant read/write — the
  exact shape of the bug found in 15 `hr-legacy` files across the API
  and dashboard — and confirm both approaches block it. Record the
  operational trade-offs of each (ORM/connection-pooling interaction for
  RLS, discipline-dependence for the repository guard).
- **Falsifies if**: RLS introduces enough operational complexity
  (connection pooling, session-variable propagation through the ORM) to
  outweigh its structural-safety benefit for this project's scale.

### H3 — A chosen authentication approach addresses the specific failure classes found in `hr-legacy` without disproportionate complexity

- **Experiment (primary)**: Implement Spring Security with short-lived
  JWTs (minutes-to-hours, not `hr-legacy`'s 10-year tokens) plus refresh
  tokens and explicit server-side revocation on logout/password-change —
  directly answering `hr-legacy` issue #7's findings.
- **Experiment (comparison, time-boxed)**: Stand up a local Keycloak
  instance and evaluate it for the same slice — per-tenant realm support,
  built-in brute-force protection, admin audit logging (directly
  answering issues #10 and #11). Cap this at one day; if Keycloak's own
  setup complexity consumes the full day without a working comparison,
  record that as a real finding (Keycloak may be too heavy for this
  MVP), not a reason to extend the time-box.
- **Falsifies if**: neither approach can be wired into the slice within
  its time allocation without unresolved security gaps.

### H4 — springdoc-openapi can produce a usable API-compatibility baseline without Flutter access

- **Experiment**: Generate an OpenAPI spec from the two slice endpoints.
  Review it for completeness (does it capture error responses, auth
  requirements, field types accurately). This cannot validate real
  Flutter compatibility (blocked on PMR-02) — it can only prove the
  *mechanism* for producing and versioning a contract is viable so that
  when real client evidence arrives, there's a working spec-generation
  pipeline to compare it against.
- **Falsifies if**: the generated spec is not accurate/complete enough
  to be a meaningful comparison baseline once Flutter evidence exists.

### H5 — JUnit 5 + Testcontainers + ArchUnit + REST Assured provide adequate confidence at proportionate setup cost

- **Experiment**: Write tests at three levels for the slice: unit
  (JUnit 5), integration against a real Postgres via Testcontainers (not
  H2/mocks — directly avoiding a "tests pass against a fake DB, fails
  against real Postgres" gap), architecture-boundary (ArchUnit, tied to
  H1), and API-level (REST Assured, tied to H2's cross-tenant test).
  Measure total setup time and whether the suite runs fast enough for a
  tight feedback loop.
- **Falsifies if**: setup cost or run time is disproportionate for a
  slice this small, suggesting friction that would compound at full
  scale.

### H6 — A minimal observability baseline is cheap enough to adopt from day one

- **Experiment**: Add structured JSON logging with a per-request
  correlation ID, plus OpenTelemetry auto-instrumentation emitting
  traces to a local/throwaway collector (not the full
  Prometheus/Grafana/Loki/Tempo stack — that's a separate, larger
  decision deferred by design). Confirm a single request can be traced
  end-to-end through both slice endpoints.
- **Falsifies if**: baseline instrumentation meaningfully slows
  development or requires infrastructure disproportionate to "day one."

## What Can Be Tested Without Flutter, Production Data, Or Devices

**Everything in this spike.** The vertical slice was deliberately chosen
because it needs none of the three biggest external unknowns:

- Tenant identity and reference-data CRUD do not require the real
  Flutter client (H4 tests the spec-generation *mechanism*, not
  real-client compatibility).
- A fresh Flyway-versioned schema for two new tables does not require
  production data.
- Neither endpoint touches attendance hardware.

## What Must Remain Blocked

- **Full API-compatibility validation** against real Flutter behavior —
  blocked on PMR-02.
- **Payroll/attendance business-logic migration** — blocked on the
  product decisions in PMR-06 and requires its own, later, higher-risk
  spike or direct planning once this one's findings are in.
- **Data-migration cutover mechanics** — blocked on PMR-03.
- **Device/gateway integration** — blocked on PMR-04.

Do not let spike momentum pull any of these into scope. If an experiment
seems to require one of them, that is a signal to stop and note it, not
to expand the slice.

## Proposed Time-Box

**10 working days (2 weeks), proposed for human confirmation or
adjustment — not a committed deadline.** Suggested allocation:

| Day | Focus |
|---|---|
| 1 | Environment setup: Java 25, Spring Boot 4.x scaffold, Postgres via Docker, Flyway baseline |
| 2–3 | Company identity: registration, login, JWT issuance (H1, H3 groundwork) |
| 3–4 | `branches` CRUD, tenant-scoped; implement and test RLS vs. repository-guard (H2) |
| 5 | OpenAPI generation and review (H4) |
| 6 | Testing stack across the slice (H5) |
| 7 | Observability baseline (H6) |
| 8 | Keycloak comparison arm (H3, time-boxed to this one day) |
| 9 | Write findings, per-ADR recommendations, spike report |
| 10 | Review checkpoint with human stakeholder; teardown per rollback strategy |

If the time-box is exceeded, that is itself a reportable finding (see
Exit Criteria), not a reason to silently extend without flagging it.

## Deliverables

1. A working (but disposable — see Rollback Strategy) vertical-slice
   codebase satisfying the exit criteria below.
2. A written spike report covering, per hypothesis: what was tried, what
   was found, and whether it confirms or falsifies the hypothesis.
3. An explicit recommendation — **Accept**, **Revise**, or **Reject** —
   for each of: ADR-0002, ADR-0003 (scoped to the spec-generation
   mechanism, not full Flutter compatibility), ADR-0004 (scoped to
   schema/migration mechanics, not full data-migration approach),
   ADR-0005, ADR-0007, ADR-0008. **ADR-0001 (repository strategy) and
   ADR-0006 (attendance edge-gateway) are out of scope for this spike**
   — nothing in the vertical slice exercises either.
4. A recorded comparison of RLS vs. repository-layer tenant-isolation
   (H2), since this directly informs how the migration addresses the
   most repeated bug class found in `hr-legacy` Discovery.

## Risks Of The Spike Itself

- **Scope creep into real implementation.** Mitigation: the vertical
  slice boundary is explicit and enforced by the rollback strategy below
  (spike code cannot become production code by default); a review
  checkpoint on day 10 catches drift before it compounds.
- **Environment/tooling setup consuming the whole time-box.** Mitigation:
  day 1 is reserved specifically for this; if setup isn't done by end of
  day 1, that itself is a finding about tooling friction, reported
  honestly rather than absorbed silently into later days.
- **A vertical slice this simple produces false confidence** by never
  encountering `hr-legacy`'s real complexity. Mitigation: H2's
  cross-tenant test is deliberately modeled directly on the actual bug
  class found in 15 `hr-legacy` files — the one place this spike
  intentionally reaches for real complexity rather than staying trivial.
- **The Keycloak comparison (H3) could balloon given its own setup
  complexity.** Mitigation: hard-capped at one day; "ran out of time to
  fully evaluate it" is an acceptable, honestly-reported outcome, not a
  spike failure.

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
      obtain a JWT, perform tenant-scoped CRUD on `branches`, with
      automated tests passing at every level (H5).
- [ ] The cross-tenant isolation test (H2) demonstrates both the RLS and
      repository-guard approaches correctly block a cross-tenant
      access attempt, with a recorded trade-off comparison.
- [ ] An OpenAPI spec is generated and reviewed for completeness (H4).
- [ ] Structured logs and at least one end-to-end OpenTelemetry trace
      are observed for a single request (H6).
- [ ] A written recommendation (Accept/Revise/Reject) exists for each
      in-scope ADR listed under Deliverables.
- [ ] The time-box was respected, or its overrun is explicitly reported
      with a reason.
- [ ] The spike codebase is fully torn down per the Rollback Strategy,
      with only the report (and any explicitly graduated outputs)
      retained.

## Evidence

Builds on `docs/migration/pre-migration-readiness-gap-analysis.md`
(PMR-07), `docs/tools/tool-catalog.md`, `docs/tools/tool-decision-matrix.md`,
`docs/security/threat-model.md` (the tenant-isolation and auth findings
this spike directly tests against), and ADR-0002/0003/0004/0005/0007/0008.

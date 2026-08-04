# ADR-0002: Modular Monolith Baseline

## Metadata

| Field | Value |
|---|---|
| ADR ID | ADR-0002 |
| Title | Modular Monolith Baseline |
| Status | Proposed |
| Date | 2026-08-02 |
| Owners | Solution Architect (see `docs/agents/responsibility-matrix.md`) |
| Deciders | Human engineering leadership — recorded at approval time in `docs/bootstrap/decision-log.md` |
| Related Issues | None yet |
| Supersedes | None |
| Superseded By | None |

## Context

The target system needs a credible delivery path for an MVP in roughly two months without unnecessary operational complexity.

## Decision

**Approval status: Proposed — this decision has not been approved. Only
a human decider can move `Status` to `Accepted`, recorded in
`docs/bootstrap/decision-log.md`; nothing in this document performs that
change.**

This decision has two parts, deliberately split so that the strategic
choice is not held hostage to a narrow technical detail still pending
evidence:

**Part A — Strategic architecture decision: recommended for acceptance
now.** Use a modular monolith as the system's baseline architecture —
a single deployable unit with explicit internal module boundaries
(Spring Modulith or equivalent package-boundary discipline), not
microservices from day one and not an undifferentiated layered monolith.
This part does not depend on the technical spike and is recommended for
immediate human sign-off. See Validation Evidence below for why this is
ready now.

**Part B — Tenant-isolation implementation detail: pending the H2 spike
result.** *How* tenant isolation is structurally enforced across every
module — PostgreSQL Row-Level Security vs. a repository-layer guard
pattern — remains open until
`docs/migration/technical-spike-plan.md`'s H2 experiment produces a
recorded Accept/Revise/Reject comparison of the two. This is the single
most consequential, hardest-to-retrofit pattern decision in this
architecture (the structural fix for the most-repeated bug class found
in `hr-legacy` Discovery — `hr-legacy#2/#3/#5/#6`), and is deliberately
carved out from Part A rather than blocking it.

**Do not treat Part A's acceptance as implicitly deciding Part B.** A
human accepting the modular-monolith strategy now does not pre-empt or
shortcut the H2 spike's comparison — both RLS and the repository-guard
pattern remain live options until that spike reports.

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
acceptance is still waiting on Discovery. Part B (tenant-isolation
mechanism) remains pending `docs/migration/technical-spike-plan.md`'s
H2 experiment specifically, unaffected by this update.

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
- **Part B, still genuinely open**: RLS vs. repository-guard tenant
  isolation — pending `docs/migration/technical-spike-plan.md` H2, the
  spike's sole remaining required hypothesis.

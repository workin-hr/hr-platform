# ADR-0007: Testing And Quality-Gate Strategy

## Metadata

| Field | Value |
|---|---|
| ADR ID | ADR-0007 |
| Title | Testing And Quality-Gate Strategy |
| Status | Accepted |
| Date | 2026-08-02 (accepted 2026-08-05 — see `docs/bootstrap/decision-log.md` D-020) |
| Owners | Test Architect |
| Deciders | Human engineering leadership — recorded at approval time in `docs/bootstrap/decision-log.md` |
| Related Issues | None yet |
| Supersedes | None |
| Superseded By | None |

## Context

The target program requires strong quality controls across compatibility, migration, performance, security, and operational reliability.

## Decision

**Accepted 2026-08-05** (`docs/bootstrap/decision-log.md` D-020).

Adopt layered quality gates that escalate in cost from every commit to pre-release, with independent review and evidence capture, as detailed in `docs/testing/test-strategy.md` and `docs/testing/quality-gate-cadence.md`. Real CI wiring of each tier is tracked as implementation work (P2-8), not a precondition of this acceptance.

## Alternatives Considered

- minimal CI with heavy manual QA
- every test on every commit

## Consequences

- supports reliable progressive validation
- requires disciplined test taxonomy and CI design
- avoids running every expensive test on every commit

## Risks

- under-resourcing nightly/pre-release tiers could let expensive-but-important checks (load, stress, soak, recovery) silently stop running
- tooling choices deferred to Discovery (see `docs/tools/tool-catalog.md`) could turn out not to support every required test category

## Validation Evidence

The taxonomy and cadence are documented in full in `docs/testing/test-strategy.md`
and `docs/testing/quality-gate-cadence.md`. Real CI implementation of
each tier, and confirmation that the required tools are wired into
GitHub Actions (tracked as P2-8), is implementation follow-up work, not
a precondition this ADR's acceptance was waiting on — the strategic
taxonomy/cadence decision is independent of that wiring work.

### Classification (2026-08-04 revision)

The technical-spike plan's H5 hypothesis (JUnit 5 + Testcontainers +
ArchUnit + REST Assured) was downgraded from "required spike" to "adopt
directly" — this is mature, standard 2026 Spring Boot testing stack, not
something needing isolated experimental validation (see
`docs/migration/technical-spike-plan.md`'s Revision Summary). The
taxonomy/cadence documented in `docs/testing/` gives the strategic
direction; wiring the actual tiers into GitHub Actions is real
implementation work that naturally happens alongside first-milestone
backend work. Accepted by the repository owner on 2026-08-05; "real CI
implementation of each tier" tracked as an implementation task (P2-8).

## Open Questions

- which tests become mandatory by phase — a sequencing detail, not a
  strategic blocker; can be refined during implementation
- what tooling is practical within the MVP timeline — resolved in
  practice by the spike-plan revision: the full stack (JUnit 5,
  Testcontainers, ArchUnit, REST Assured) is judged practical enough to
  adopt directly, not deferred

# ADR-0007: Testing And Quality-Gate Strategy

## Metadata

| Field | Value |
|---|---|
| ADR ID | ADR-0007 |
| Title | Testing And Quality-Gate Strategy |
| Status | Proposed |
| Date | 2026-08-02 |
| Owners | Test Architect |
| Deciders | Human engineering leadership — recorded at approval time in `docs/bootstrap/decision-log.md` |
| Related Issues | None yet |
| Supersedes | None |
| Superseded By | None |

## Context

The target program requires strong quality controls across compatibility, migration, performance, security, and operational reliability.

## Decision

**Approval status: Proposed — this decision has not been approved.**

Adopt layered quality gates that escalate in cost from every commit to pre-release, with independent review and evidence capture, as detailed in `docs/testing/test-strategy.md` and `docs/testing/quality-gate-cadence.md`.

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

Partial evidence exists: the taxonomy and cadence are documented in `docs/testing/`. Still pending before Accepted: real CI implementation of each tier and confirmation that the required tools (see P2-8 CI remediation) are actually wired into GitHub Actions rather than only described in strategy documents.

## Open Questions

- which tests become mandatory by phase
- what tooling is practical within the MVP timeline

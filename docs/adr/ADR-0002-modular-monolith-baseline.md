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

**Approval status: Proposed — this decision has not been approved.**

Use a modular monolith as the initial architecture assumption until evidence proves a need for a more distributed model.

## Alternatives Considered

- microservices from the start
- layered monolith without explicit modular boundaries

## Consequences

- simplifies delivery and operations during MVP
- keeps module boundary work inside one deployment unit initially
- requires disciplined internal boundaries to avoid accidental monolith sprawl

## Risks

- module boundaries drift into a tangled monolith without enforced internal contracts
- deferred decomposition becomes harder the longer real module coupling is left unmeasured

## Validation Evidence

None yet — pending Discovery. Module boundary candidates should be informed by legacy PHP module inventory (`docs/legacy/existing-php-module-inventory.md`) before this decision can move to Accepted.

## Open Questions

- which domains become modules first
- what measurable threshold would justify decomposition

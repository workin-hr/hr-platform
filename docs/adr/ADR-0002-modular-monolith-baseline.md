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

**Update 2026-08-04**: module boundary candidates are now informed —
`docs/legacy/existing-php-module-inventory.md` documents the full
38-module API surface and 34-page dashboard structure Discovery found,
and `docs/api/three-frontend-api-usage-matrix.md`'s capability/ownership
matrix (added 2026-08-04) gives a first real cut at module groupings by
who owns each capability (platform-admin / tenant-admin / employee
self-service / shared).

### Classification (2026-08-04 revision)

**Split decision.** The core strategic choice — modular monolith over
microservices-from-day-1 or an unstructured layered monolith — **can be
accepted now**: nothing about the two-month MVP timeline, small team
size, or Discovery findings changes this reasoning, and none of it
depends on the spike. What genuinely **depends on the spike** is the
narrower, tooling-specific question of *how* module boundaries and
(critically) tenant isolation are structurally enforced — this is what
`docs/migration/technical-spike-plan.md`'s H2 experiment (revised
2026-08-04, now the spike's sole required hypothesis) validates: RLS vs.
repository-guard tenant isolation, the single most consequential,
hardest-to-retrofit pattern decision for this architecture. Recommend: a
human decider can accept the **strategic** direction (modular monolith)
now, while treating the tenant-isolation-pattern detail as pending the
H2 spike result specifically — not the whole ADR blocked on the whole
original 10-day plan.

## Open Questions

- which domains become modules first — a first-pass answer now exists
  in `docs/api/three-frontend-api-usage-matrix.md`'s ownership matrix,
  not yet formalized into a module boundary diagram
- what measurable threshold would justify decomposition
- ~~tenant-isolation enforcement mechanism~~ — moved to
  `docs/migration/technical-spike-plan.md` H2, the spike's sole
  remaining required hypothesis

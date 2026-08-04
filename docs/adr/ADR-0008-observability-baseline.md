# ADR-0008: Observability Baseline

## Metadata

| Field | Value |
|---|---|
| ADR ID | ADR-0008 |
| Title | Observability Baseline |
| Status | Accepted |
| Date | 2026-08-02 (accepted 2026-08-05 — see `docs/bootstrap/decision-log.md` D-024) |
| Owners | Solution Architect, Test Architect |
| Deciders | Human engineering leadership — recorded at approval time in `docs/bootstrap/decision-log.md` |
| Related Issues | None yet |
| Supersedes | None |
| Superseded By | None |

## Context

The future system must support debugging, auditability, migration validation, and operational reliability.

## Decision

**Accepted 2026-08-05** (`docs/bootstrap/decision-log.md` D-024).

**Minimum MVP observability baseline: structured logging + a
correlation/request ID propagated across every request + OpenTelemetry
auto-instrumented traces sent to a lightweight/throwaway collector.**
The heavier operational stack (a full Prometheus/Grafana/Loki/Tempo
deployment) is explicitly **not** adopted by this decision and is
deliberately deferred to a separate, later, evidence-informed decision
once real production load and cost data exist — not blocked, just not
decided now, since deciding it now would mean choosing a heavy stack
before the load/cost evidence that should drive that choice is
available.

This is deliberately the low-risk option: this ADR's own risk analysis
already identifies that adopting a heavy stack before real load/cost
data exists would add operational burden disproportionate to MVP needs,
while deferring observability entirely risks migration and
attendance-integration issues going undetected in production. The
minimal baseline sits between those two risks — structured
logs/correlation/traces give real debuggability and migration-validation
signal from day one, without committing to the operational cost of a
full metrics/dashboarding platform before there's real traffic to
justify it.

**Basis for accepting now rather than waiting on further Discovery**:
the technical-spike plan's H6 hypothesis (structured logging +
correlation ID + OpenTelemetry auto-instrumentation) was already
downgraded from "required spike" to "adopt directly" — this is mature,
standard 2026 practice, not something needing isolated experimental
validation (`docs/migration/technical-spike-plan.md`'s Revision
Summary). Nothing about the minimal baseline specifically was actually
waiting on Discovery; it was previously left as a placeholder text
despite the real reasoning already existing in this ADR's own Risks
section and the spike-plan revision.

## Alternatives Considered

- defer observability until after MVP delivery
- adopt a heavy platform stack prematurely

## Consequences

- prevents observability from becoming an afterthought
- influences API, background processing, and device integration design
- requires toolchain and cost evaluation

## Risks

- adopting a heavy observability stack (e.g. a full Prometheus/Grafana/Loki/Tempo deployment) before real load and cost data exists could add operational burden disproportionate to MVP needs
- deferring observability entirely risks migration and attendance-integration issues going undetected in production

## Validation Evidence

Signal requirements are informed by `docs/operations/monitoring-and-alerting.md`
ownership assignments and the tool evaluation recorded in
`docs/tools/tool-catalog.md`. The MVP-baseline recommendation itself
draws directly on this ADR's own Risks section (the two competing risks
of a too-heavy stack vs. no observability at all) and on
`docs/migration/technical-spike-plan.md`'s Revision Summary (H6
downgraded from required spike to "adopt directly," standard 2026
Spring Boot practice) — real reasoning that existed before this
acceptance, not new evidence gathered for it. The heavier-stack question
remains genuinely without evidence, which is exactly why it stays
deferred rather than decided.

### Classification (2026-08-04 revision, decision recorded 2026-08-05)

Accepted for the MVP baseline specifically by the repository owner on
2026-08-05. This did not depend on the technical spike or further
Discovery — see Decision above.

## Open Questions

- which signals are mandatory at MVP — answered by the Decision above
  (structured logs, correlation ID, basic traces); not yet formalized as
  a signal-by-signal implementation checklist, which is normal
  implementation detail, not a blocker on this ADR
- when to revisit the heavier stack (Prometheus/Grafana/Loki/Tempo) —
  deliberately deferred to a separate, later, evidence-informed decision
  once real production load and cost data exist; not scheduled or
  triggered by any specific threshold yet

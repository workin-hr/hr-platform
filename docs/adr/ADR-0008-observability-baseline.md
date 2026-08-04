# ADR-0008: Observability Baseline

## Metadata

| Field | Value |
|---|---|
| ADR ID | ADR-0008 |
| Title | Observability Baseline |
| Status | Proposed |
| Date | 2026-08-02 |
| Owners | Solution Architect, Test Architect |
| Deciders | Human engineering leadership — recorded at approval time in `docs/bootstrap/decision-log.md` |
| Related Issues | None yet |
| Supersedes | None |
| Superseded By | None |

## Context

The future system must support debugging, auditability, migration validation, and operational reliability.

## Decision

**Approval status: Proposed — this decision has not been approved.**

Define a minimum observability baseline covering logs, traces, metrics, correlation, and alerting before implementation begins.

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

None yet — pending Discovery. Signal requirements should be informed by `docs/operations/monitoring-and-alerting.md` ownership assignments and the tool evaluation recorded in `docs/tools/tool-catalog.md` before this decision can move to Accepted.

### Classification (2026-08-04 revision)

**Can be accepted now for the MVP baseline specifically.** The
technical-spike plan's H6 hypothesis (structured logging + correlation
ID + OpenTelemetry auto-instrumentation) was downgraded from "required
spike" to "adopt directly" — mature, standard practice, not something
needing isolated experimental validation (see
`docs/migration/technical-spike-plan.md`'s Revision Summary). This ADR's
own stated risk ("adopting a heavy observability stack... before real
load and cost data exists") argues *for* accepting a deliberately
minimal baseline now (structured logs + correlation ID + traces to a
lightweight/throwaway collector) rather than waiting — the minimal
baseline is precisely the low-risk option this ADR already identifies.
The heavier decision (full Prometheus/Grafana/Loki/Tempo adoption) is
explicitly *not* being accepted here and should remain a separate,
later, evidence-informed decision once real load exists. Recommend:
accept the minimal baseline direction now; treat the heavier stack
question as intentionally deferred, not blocked.

## Open Questions

- which signals are mandatory at MVP — a first-pass answer now exists
  (structured logs, correlation ID, basic traces) via the spike-plan
  revision's H6 downgrade; not yet formalized as a signal-by-signal list
- how much of the stack should be adopted during early implementation —
  answered in principle (minimal baseline now, heavier stack deferred to
  a later evidence-informed decision), not yet a detailed rollout plan

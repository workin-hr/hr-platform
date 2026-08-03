# ADR-0006: Attendance Edge-Gateway Direction

## Metadata

| Field | Value |
|---|---|
| ADR ID | ADR-0006 |
| Title | Attendance Edge-Gateway Direction |
| Status | Proposed |
| Date | 2026-08-02 |
| Owners | Solution Architect |
| Deciders | Human engineering leadership — recorded at approval time in `docs/bootstrap/decision-log.md` |
| Related Issues | None yet |
| Supersedes | None |
| Superseded By | None |

## Context

Attendance devices may require local connectivity patterns, vendor-specific protocols, push callbacks, polling, or vendor APIs.

## Decision

**Approval status: Proposed — this decision has not been approved.**

Treat the .NET edge gateway as a candidate direction pending vendor and device discovery.

## Alternatives Considered

- no local gateway
- gateway-first design for all vendors

## Consequences

- keeps local integration patterns explicit
- avoids overcommitting to a single device strategy too early
- requires device and vendor evidence first

## Risks

- committing to a local gateway for vendors that support direct push or cloud APIs would add unnecessary operational surface
- device/firmware diversity discovered late could invalidate an early gateway design

## Validation Evidence

None yet — pending Discovery. Requires `docs/devices/attendance-device-model-and-firmware-inventory.md` and `docs/devices/vendor-capability-matrix.md` to be populated before this decision can move to Accepted.

## Open Questions

- which vendors require local network access
- which protocols require persistent local services

# ADR-0006 Attendance Edge-Gateway Direction

## Status

Proposed

## Context

Attendance devices may require local connectivity patterns, vendor-specific protocols, push callbacks, polling, or vendor APIs.

## Proposed Direction

Treat the .NET edge gateway as a candidate direction pending vendor and device discovery.

## Consequences

- keeps local integration patterns explicit
- avoids overcommitting to a single device strategy too early
- requires device and vendor evidence first

## Alternatives Considered

- no local gateway
- gateway-first design for all vendors

## Open Questions

- which vendors require local network access
- which protocols require persistent local services

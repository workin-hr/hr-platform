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

**Update 2026-08-04**: `docs/devices/attendance-device-model-and-firmware-inventory.md`
and `docs/devices/vendor-capability-matrix.md` remain correctly empty —
still no real vendor/hardware access (PMR-04). What's new:
`docs/devices/device-integration-architecture.md` designs a
vendor-neutral adapter/SPI pattern that works ahead of that access,
specifically so this ADR's "gateway vs. no gateway, per vendor" question
becomes a per-adapter implementation choice rather than a single
architecture-wide commitment blocked entirely on vendor evidence.

### Classification (2026-08-04 revision)

**Split decision, matching the pattern used for ADR-0002.** The
**architectural pattern** — vendor-neutral core with per-vendor
adapters implementing a stable ingestion contract, supporting local
gateway, direct vendor-cloud, or push-webhook connectivity all through
the same seam — **can be accepted now**; it doesn't require knowing
which real vendors this system integrates with, only that vendor
diversity should be isolated behind an adapter boundary rather than
baked into core business logic. What **remains blocked on PMR-04**
(hardware/vendor access) is: which specific vendors need a local
`.NET` edge gateway vs. direct cloud API integration, final protocol
selection, and the test-scenario checklist in
`device-integration-architecture.md`'s final section. Recommend: accept
the adapter/SPI architectural pattern now; treat final
vendor-specific gateway-or-not decisions as scoped, later, per-adapter
work that does not block starting backend implementation.

## Open Questions

- which vendors require local network access — still blocked on PMR-04
- which protocols require persistent local services — still blocked on
  PMR-04
- ~~whether the architecture can be vendor-agnostic ahead of vendor
  evidence~~ — **Resolved 2026-08-04**: yes, see
  `docs/devices/device-integration-architecture.md`.

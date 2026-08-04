# ADR-0006: Attendance Edge-Gateway Direction

## Metadata

| Field | Value |
|---|---|
| ADR ID | ADR-0006 |
| Title | Attendance Edge-Gateway Direction |
| Status | Accepted |
| Date | 2026-08-02 (Part A accepted 2026-08-05 — see `docs/bootstrap/decision-log.md` D-023. Part B — vendor-specific gateway-or-not decisions — remains genuinely `Proposed`/blocked on PMR-04 device/vendor access; the ADR format has no per-part status field, so this is the closest honest representation.) |
| Owners | Solution Architect |
| Deciders | Human engineering leadership — recorded at approval time in `docs/bootstrap/decision-log.md` |
| Related Issues | None yet |
| Supersedes | None |
| Superseded By | None |

## Context

Attendance devices may require local connectivity patterns, vendor-specific protocols, push callbacks, polling, or vendor APIs.

## Decision

**Part A is Accepted (2026-08-05, `docs/bootstrap/decision-log.md` D-023).
Part B remains Proposed and blocked on PMR-04 (real vendor/hardware
access) — it is not decided by Part A's acceptance.**

This decision splits the same way `docs/adr/ADR-0002-modular-monolith-baseline.md`
did, so the strategic architectural choice is not held hostage to
vendor-specific detail that genuinely cannot be known yet:

**Part A — Architectural pattern: Accepted.** Attendance device
ingestion uses a **vendor-neutral core with per-vendor adapters**: a
Java Service Provider Interface (`DeviceEventAdapter`, see
`docs/devices/device-integration-architecture.md`) that every
vendor-specific implementation satisfies, translating vendor-native
events (webhook, polled API, local-protocol frame) into one canonical
`DeviceAttendanceEvent` shape before handing off to a single,
vendor-agnostic ingestion pipeline. All business logic (anti-fraud
rules, tenant scoping, notification triggering) lives once in that
pipeline, never inside an adapter. Adapters self-register (e.g.
Spring component-scanning) rather than the pipeline branching on vendor
identity — adding a vendor must never require touching ingestion or
business-rule code. This directly answers this ADR's original framing
("is a local `.NET` edge gateway the right direction") by making
gateway-vs-direct-API-vs-webhook a **per-adapter implementation
choice**, not a single architecture-wide commitment: some adapters may
talk to a local `.NET` edge gateway (matching this repository's existing
`edge-gateway/` component boundary), others may call a vendor cloud API
directly, others may receive push webhooks — the core architecture
supports all three without knowing which any given company's hardware
uses. This pattern does not require real vendor/hardware access to
accept — it requires only that vendor diversity should be isolated
behind an adapter boundary rather than baked into core business logic,
which is a design judgment, not a fact PMR-04 discovery would change.

**Part B — Vendor-specific gateway-or-not decisions: remains Proposed,
blocked on PMR-04.** Which specific vendors actually need a local
`.NET` edge gateway vs. direct cloud API integration, final protocol
selection per vendor, and the test-scenario checklist in
`docs/devices/device-integration-architecture.md`'s final section all
require real vendor/hardware access that does not exist in this
environment. **Do not treat Part A's acceptance as implicitly deciding
Part B** — accepting the adapter/SPI pattern now does not pre-empt or
shortcut which vendors get which adapter implementation; that remains
live and undecided until PMR-04 access exists.

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

### Classification (2026-08-04 revision, Part A accepted 2026-08-05)

Split decision, matching the pattern used for ADR-0002. Part A doesn't
require knowing which real vendors this system integrates with, so it
did not depend on PMR-04 or the technical spike — accepted by the
repository owner on 2026-08-05. Part B remains genuinely blocked on
PMR-04 (hardware/vendor access) for the reasons in Decision above.

## Open Questions

- which vendors require local network access — still blocked on PMR-04;
  this is Part B, not resolved by Part A's acceptance
- which protocols require persistent local services — still blocked on
  PMR-04; also Part B
- ~~whether the architecture can be vendor-agnostic ahead of vendor
  evidence~~ — **Resolved 2026-08-04, accepted as Part A 2026-08-05**:
  yes, see `docs/devices/device-integration-architecture.md` and
  `docs/bootstrap/decision-log.md` D-023.

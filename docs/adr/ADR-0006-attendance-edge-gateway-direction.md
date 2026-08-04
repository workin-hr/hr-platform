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

**Part B — Vendor-specific gateway-or-not decisions: partially resolved
2026-08-05 (corrected same day), still not `Accepted`.** **Vendor
identity is decided**, directly by the product/business owner: attendance
devices are **ZKTeco** hardware
(`https://www.zkteco.com/en/documents`), and the adapter built for them
must support every model/firmware version in that product line, not one
specific model. (An earlier same-day note in this ADR recorded the
vendor as "FK fingerprint" — the product/business owner corrected this
to ZKTeco specifically; "FK" is not a real, retained vendor name. The
correction is recorded here rather than silently overwritten, since the
earlier text was already committed.) This resolves the "which vendor"
question that PMR-04's hardware access gap had otherwise left fully
open.

**What remains genuinely open, not resolvable from the vendor name
alone**: whether ZKTeco devices connect via a local `.NET` edge gateway,
direct cloud API, or push webhook, and the exact wire protocol. A real
attempt was made to resolve this from ZKTeco's own public documentation
page (the URL the product/business owner gave, fetched directly
2026-08-05): the page lists whitepaper-style titles on biometric
recognition technology (3D structured-light facial recognition, palm
recognition, face-anti-spoofing, etc.) with no visible SDK name,
protocol specification, or connectivity-architecture detail — actual
integration/SDK documents were not retrievable from that page (likely
gated behind a download center or support contact, per the page's own
structure). **The product/business owner also stated directly not
knowing the protocol/connectivity detail either** ("for fk device i
don't know" — said before the ZKTeco correction, understood to apply to
protocol/connectivity generally, not just to the corrected vendor name).
This ADR does **not** invent a connectivity pattern from general
industry familiarity with biometric-device vendors — only what was
directly fetched and read this session is treated as evidence. Real
resolution requires either downloading ZKTeco's actual SDK/protocol
documents (not just this landing page), contacting ZKTeco's technical
support (`service@zkteco.com`, listed on the page), or physical device
access — the same PMR-04 constraint as before, now narrowed to one
named, real vendor instead of an unknown one. The test-scenario
checklist in `docs/devices/device-integration-architecture.md`'s final
section likewise still needs real ZKTeco protocol details to execute.
**Do not treat the vendor decision as resolving connectivity pattern or
protocol** — those remain live and undecided until real ZKTeco
technical documentation or hardware access is available. Part A's
adapter/SPI pattern (already accepted) is exactly what absorbs this
remaining uncertainty without blocking other modules: the ZKTeco
adapter gets built and wired to whichever connectivity pattern its real
protocol turns out to need, once known.

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

**Update 2026-08-05**: the vendor is now named directly by the
product/business owner — ZKTeco devices, all versions (see Decision,
Part B; corrected same day from an initial "FK fingerprint" note).
`https://www.zkteco.com/en/documents` was fetched directly this
session — it is a real, public vendor documents page, but contains
whitepaper-style technology introductions, not integration/SDK protocol
documentation. `docs/devices/attendance-device-model-and-firmware-inventory.md`
and `docs/devices/vendor-capability-matrix.md` still remain correctly
empty — a vendor name (and a landing page that doesn't contain protocol
detail) is not the same evidence as real device model/firmware/protocol
detail, which still requires ZKTeco's actual SDK/integration
documentation (not yet retrieved) or physical hardware access, neither
of which exists in this environment yet.

### Classification (2026-08-04 revision, Part A accepted 2026-08-05)

Split decision, matching the pattern used for ADR-0002. Part A doesn't
require knowing which real vendors this system integrates with, so it
did not depend on PMR-04 or the technical spike — accepted by the
repository owner on 2026-08-05. Part B remains genuinely blocked on
PMR-04 (hardware/vendor access) for the reasons in Decision above.

## Open Questions

- ~~which vendors this system integrates with~~ — **Resolved
  2026-08-05**: ZKTeco devices, all versions, per direct
  product/business-owner statement (see Decision, Part B).
- whether ZKTeco devices need a local network gateway or connect via
  direct cloud API/push webhook — still open; ZKTeco's public documents
  landing page was fetched 2026-08-05 and did not contain this detail,
  requires ZKTeco's actual SDK/integration documentation or vendor
  support contact, not resolvable from the vendor name or that page
  alone
- which protocol(s) ZKTeco devices actually speak, and whether
  persistent local services are required — still open, same dependency
  as above
- ~~whether the architecture can be vendor-agnostic ahead of vendor
  evidence~~ — **Resolved 2026-08-04, accepted as Part A 2026-08-05**:
  yes, see `docs/devices/device-integration-architecture.md` and
  `docs/bootstrap/decision-log.md` D-023.

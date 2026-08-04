# Attendance Device Integration Architecture (Pre-Hardware-Access Design)

## Purpose And Boundary

Hardware/vendor access is not currently available
(`docs/devices/attendance-device-model-and-firmware-inventory.md` and
`vendor-capability-matrix.md` remain correctly empty — nothing to fill
in without real device evidence). Per explicit direction, **hardware
access should block only final device validation, not the rest of the
backend architecture or implementation.** This document is everything
that can be designed now, vendor-agnostically, so that the moment device
access arrives, integration is a matter of writing one adapter against
an already-proven contract — not a from-scratch design exercise. This is
a design document, not implementation, per this repository's `CLAUDE.md`
boundary.

## Design Principle: Vendor-Neutral Core, Vendor-Specific Adapters

The backend's attendance-event ingestion, storage, and business-rule
layer (check-in validation, the 2-hour minimum-gap rule currently
inconsistently enforced across QR/GPS/manual — `hr-legacy#16` — batch
processing, notifications) must not know which vendor, protocol, or
connectivity pattern produced an event. A single **Device Event
Ingestion Contract** (below) is the seam: everything on the business
side of that seam is vendor-agnostic and buildable today; everything on
the device side is a small, swappable adapter written once real vendor
access exists.

```text
[ Device / Vendor Cloud / Local Gateway ]
              |
              v
   [ Vendor-Specific Adapter ]   <-- one per vendor/protocol, written
              |                       after hardware access (SPI impl)
              v
  [ Device Event Ingestion Contract ]  <-- designed now, stable
              |
              v
   [ Attendance Business Logic ]  <-- vendor-agnostic, buildable now
```

## Vendor-Neutral Adapter / SPI

A Java Service Provider Interface (SPI), one implementation per vendor
integration pattern found during device Discovery (PMR-04). Candidate
shape (illustrative, not final — exact method signatures are an
implementation detail for when real vendor protocols are known):

- `DeviceEventAdapter` — the contract every vendor-specific
  implementation satisfies: receive a vendor-native event (however it
  arrives — webhook payload, polled API response, local-protocol frame),
  translate it into the canonical `DeviceAttendanceEvent` shape (below),
  and hand it to the ingestion pipeline. **The adapter's only job is
  translation** — no business logic (anti-fraud rules, tenant scoping,
  notification triggering) belongs in an adapter; all of that lives once
  in the vendor-agnostic ingestion pipeline, so behavior is consistent
  regardless of which vendor a given company uses.
- Adapters register themselves (e.g. Spring's own component-scanning /
  a small adapter-registry keyed by vendor identifier) rather than the
  core pipeline having vendor-specific `if` branches — adding a new
  vendor should never require touching ingestion or business-rule code.
- **This directly addresses ADR-0006's core open question** (local
  gateway vs. no gateway, per-vendor) by making that decision a
  per-adapter implementation detail rather than an architecture-wide
  commitment — some adapters may talk to a local `.NET` edge gateway
  (matching the existing `edge-gateway/` component boundary in this
  repository), others may call a vendor cloud API directly, others may
  receive push webhooks. The core architecture supports all three
  patterns without knowing which any given company's hardware uses.

## Device Event Ingestion Contract

The stable seam every adapter produces and the business logic consumes:

```text
DeviceAttendanceEvent {
  vendor_event_id:    string   // vendor's own event identifier, for idempotency
  device_id:           string   // vendor/device-specific identifier
  company_id:           int      // resolved tenant — adapter's responsibility to map
                                   // device_id -> company_id, likely via a
                                   // device-registration table (not yet designed,
                                   // depends on real vendor device-identification schemes)
  employee_identifier: string   // vendor-native identifier (badge ID, fingerprint
                                   // template match ID, etc.) -- mapped to a real
                                   // employee_id downstream, not assumed 1:1 with
                                   // any existing hr-legacy identifier
  event_type:           enum     // check_in | check_out | unknown (some vendors
                                   // don't distinguish; the business layer may need
                                   // to infer from sequence, matching hr-legacy's
                                   // existing check-in/out pairing logic)
  event_timestamp:      datetime // device-reported time, device-local timezone
                                   // explicit (not assumed UTC or server-local)
  received_at:           datetime // ingestion-time server timestamp, separate from
                                   // event_timestamp -- devices can be offline and
                                   // deliver events late (see Offline Sync below)
  raw_payload:           jsonb    // the untranslated vendor payload, retained for
                                   // debugging/audit, never parsed by business logic
}
```

This mirrors the confirmed real check-in contract already documented
(`docs/api/flutter-request-response-compatibility.md`, "Check-In Request
Contract" — `{latitude, longitude, method:'app'}`) conceptually: a
device event is the *hardware* equivalent of that same underlying
"someone checked in" fact, differing only in how the location/identity
signal is captured (GPS+app-session for mobile vs. device/badge/biometric
for hardware).

## Idempotency Strategy

- **`vendor_event_id` is the idempotency key.** Every event ingestion
  is `INSERT ... ON CONFLICT (vendor_event_id) DO NOTHING`-shaped (or the
  ORM-appropriate equivalent) — a device or gateway retrying a delivery
  (network blip, at-least-once delivery semantics from a vendor cloud
  API) must never create a duplicate attendance record.
- **Vendors without a stable event ID** (some simpler/older hardware may
  not provide one) need a synthesized idempotency key at the adapter
  level — e.g. a hash of `(device_id, employee_identifier, event_timestamp)`
  at a defined precision. This is an adapter-specific concern, not a
  core-pipeline one, but the core pipeline's `vendor_event_id` field
  must always be populated by the time an event reaches ingestion,
  synthesized or native.

## Retry And Offline Synchronization Behavior

- **Device-side offline buffering** (the device itself queues events
  while disconnected, delivers them once connectivity returns) is a
  device/vendor capability, not something the backend can control — but
  the backend must be designed to **accept late-arriving events
  gracefully**: `event_timestamp` (when it happened) and `received_at`
  (when the backend learned about it) are deliberately separate fields
  specifically so attendance records reflect the real event time, not
  ingestion time, even when a device delivers a batch of buffered events
  hours or days late.
- **Backend-side retry** (for adapters calling out to a vendor cloud API
  rather than receiving pushes): standard exponential-backoff retry with
  a dead-letter path for events that fail translation/ingestion after
  exhausting retries — those need to be visible to an operator, not
  silently dropped, given they represent real attendance data.
- **Late-event business-rule implications**: the 2-hour minimum-gap
  anti-fraud rule (`hr-legacy#16`) and any other time-window business
  logic must be evaluated against `event_timestamp`, not `received_at` —
  designing this now avoids a subtle bug class where offline-buffered
  device events get incorrectly gap-checked against ingestion order
  rather than real chronological order.

## Device Authentication / Security Model

- **Every adapter's inbound surface (webhook endpoint, polling
  credential, local-gateway connection) must itself be authenticated** —
  a device/vendor integration is a real external trust boundary, no
  different in principle from the tenant-isolation and object-level
  authorization findings already driving this migration
  (`hr-legacy#2/#3/#5/#6`). Concretely: webhook endpoints need a
  vendor-specific shared secret or signature verification (whatever the
  real vendor protocol supports, once known); polling adapters need
  their own scoped credential, not a shared platform-admin credential;
  a local gateway (if used) needs its own device-identity/certificate
  rather than an inherited user session.
- **`company_id` resolution must happen inside the trust boundary, not
  be trusted from the device payload** — a device claiming "I belong to
  company X" is exactly the shape of trust-the-client mistake already
  found repeatedly in `hr-legacy` (e.g. `hr-legacy#9`'s guessable
  `company_id`). The adapter/core pipeline must resolve `company_id`
  from a server-side device-registration record keyed by `device_id`,
  never accept a client-asserted tenant value directly.
- **Raw payload retention** (`raw_payload` field above) needs its own
  data-handling policy once real vendor payloads are known — some
  biometric vendors' raw payloads could contain sensitive template data
  that shouldn't be retained indefinitely or logged carelessly; flagged
  here as a design constraint to apply once real payload shapes exist,
  not resolved now.

## Mock Device / Simulator

**Buildable now, exercises the entire pipeline above without any real
hardware**: a simple test/dev-only HTTP client (or a small CLI tool)
that POSTs synthetic `DeviceAttendanceEvent`-shaped payloads through a
fake `DeviceEventAdapter` implementation, letting the ingestion
pipeline, idempotency logic, and business-rule layer all be built and
tested end-to-end before any real vendor is integrated. This is standard
practice for the "vendor-neutral core, vendor-specific adapter" pattern
and should be one of the first things built once backend implementation
starts — it de-risks the entire pipeline design well ahead of hardware
access, and doubles as the foundation for the automated test scenarios
below.

## Test Scenarios And Evidence Checklist (For When Real Devices Arrive)

Prepared now; executed once hardware/vendor access exists (PMR-04):

- [ ] A real device event reaches the backend and produces a correct
      attendance record — device_id/company_id/employee resolution all
      confirmed against real data, not synthetic.
- [ ] A duplicate delivery of the same real event (simulate a
      vendor/network retry) does not create a duplicate record —
      idempotency confirmed against real `vendor_event_id` behavior
      (or the real vendor's substitute, if no native event ID exists).
- [ ] An event delivered late (device was offline, buffered, delivered
      after reconnection) is recorded with the correct original
      `event_timestamp`, not the late `received_at` time, and is
      correctly evaluated against the minimum-gap rule using the
      original time.
- [ ] An unauthenticated or forged request to the adapter's inbound
      surface is rejected — confirms the device authentication model
      actually holds against a real (or realistically simulated)
      attack, not just a design assumption.
- [ ] A device event for an unregistered/unknown `device_id` is rejected
      rather than silently accepted or mis-attributed to the wrong
      company.
- [ ] End-to-end latency (device event to visible attendance record) is
      measured against real vendor connectivity, informing whether any
      user-facing "processing" state is needed in the clients.

## Consequences For ADR-0006

This architecture directly informs, but does not by itself resolve,
`docs/adr/ADR-0006-attendance-edge-gateway-direction.md`: the
adapter/SPI pattern is vendor-agnostic by design, meaning ADR-0006's
"local gateway vs. no gateway, per vendor" question becomes a
per-adapter implementation choice rather than a single architecture-wide
commitment that has to be made before any vendor evidence exists. See
that ADR's revised classification for what can be decided now versus
what still requires real device/vendor Discovery.

## Evidence

`docs/devices/attendance-device-model-and-firmware-inventory.md`,
`docs/devices/vendor-capability-matrix.md` (both confirmed still empty —
no vendor/hardware access), `docs/legacy/business-rule-extraction.md`
(the 2-hour minimum-gap rule, `hr-legacy#16`),
`docs/api/flutter-request-response-compatibility.md` (the existing
mobile check-in contract this design's event shape parallels),
`docs/security/threat-model.md` (`hr-legacy#9`, the trust-the-client
pattern this design's device-authentication section explicitly avoids
repeating), `docs/bootstrap/execution-checklist.md` (the `edge-gateway/`
component boundary this design's adapter pattern is compatible with).

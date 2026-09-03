# Monitoring And Alerting

Covers both monitoring ownership and alert routing. See
`docs/architecture/quality-attributes.md` and ADR-0008 (Observability
Baseline) for the architecture-level observability decision this operates
under.

## Signal (log, trace, metric, alert)

Record the observable signal needed to detect, diagnose, or confirm system
behavior.

Useful signal categories include:

- logs for event detail and audit trail
- traces for request or workflow path analysis
- metrics for rates, latency, errors, and saturation
- alerts for human attention when conditions exceed defined thresholds
- migration validation outputs for cutover and rollback confidence
- business or workflow health indicators for customer-visible success paths

Prefer signals tied to meaningful failure modes rather than collecting data
only because a tool can emit it.

### Attendance-device receiver (D-164)

Emitted by `com.workin.devices` when `app.devices.ingest.enabled=true`;
design section 9 of `docs/superpowers/specs/2026-09-02-attendance-device-ingestion-design.md`.

- Metrics (Micrometer counters, tag `vendor`): `devices.punches.stored`,
  `devices.punches.duplicate`, `devices.punches.unmatched`,
  `devices.punches.malformed`, `devices.punches.rejected`,
  `devices.unclaimed.hits`, `devices.biometric.discarded`,
  `devices.stamp.rejected`, `devices.requests.rejected`,
  `devices.uploads.oversized` (second tag `table`), and
  `devices.uploads.discarded` (second tag `table`, drawn from a closed set so
  a caller cannot grow the registry).
- Logs, all carrying the serial: WARN on unmatched PINs, malformed lines,
  template lines discarded, unknown tables; INFO on command results; ERROR
  with the cause when the receiver fails (the device retries after its
  `ErrorDelay`, so an ERROR here repeats until fixed).
- Liveness is data, not a metric yet: `attendance_devices.last_seen_at` is
  advanced by every command poll (about every 10 seconds). "Device offline"
  is that column older than a threshold for an active device; until a gauge
  exists, a scheduled query is the alert source.
- Meaningful failure modes: a claimed device silent beyond the threshold
  (site network or power); `devices.unclaimed.hits` rising (a terminal
  pointed at the receiver and not yet claimed — or a probe); a sustained
  `devices.punches.unmatched` rate (PINs nobody bound, or a badge still in
  use after the employee left); any `devices.biometric.discarded` (a firmware
  ignoring `TransFlag`; record it in the inventory); any
  `devices.punches.rejected` (a row the database refused — it is acknowledged
  rather than retried, so this counter is the only trace); and
  `devices.stamp.rejected` or `devices.requests.rejected` above a trickle,
  which means something is sending values no terminal would send; and any
  `devices.uploads.oversized`, which is either a buffered reconnect larger
  than the record cap (raise it, and record the real batch size on the
  hardware checklist) or an attempt to amplify one request into many
  statements.

## Source System

Identify where the signal comes from.

Examples:

- application service
- background job or scheduler
- database or migration process
- edge gateway or integration adapter
- authentication or access-control component
- API gateway or ingress
- customer-impact workflow check

If the source system does not exist yet because implementation has not begun,
describe the planned boundary rather than inventing a deployed component name.

## Ownership (who watches it)

Record the human role or team responsible for noticing, reviewing, or acting
on the signal.

Ownership may differ by signal type:

- engineering owner for service-level diagnostics
- operations owner for deployment or availability signals
- migration owner for cutover and validation signals
- security owner for security-relevant anomalies
- support or product owner for customer-impact indicators

If a signal has no clear human owner, treat that as an operational gap.

## Alert Routing (who gets paged, and how)

Define how a signal reaches a human when action is required.

Capture:

- which conditions generate an alert versus being retained only for diagnosis
- who receives the alert first
- how it is delivered
- who is next if the first owner does not respond
- which incidents require broader stakeholder escalation

Possible routing paths include:

- paging or on-call tool
- email
- chat or operations channel
- ticketing system
- manual escalation by a release or incident owner

Do not invent a live paging tool or support rota that has not been approved.

## Severity Classification

Classify the importance of the signal or alert so response urgency is clear.

Severity should be based on impact, not gut feel. Useful dimensions include:

- customer-facing outage or degraded workflow
- security or data-integrity risk
- migration or rollback risk
- operational degradation with no immediate customer impact
- informational signal for trend analysis only

If the repository later adopts named severity levels, this document should map
signals to those levels explicitly.

## Evidence

Link the artifacts that prove the monitoring and alerting definition is real
and reviewable. Evidence may include:

- dashboard or metric definition
- alert rule
- log or trace field definition
- sample alert payload
- ownership record
- escalation-path record
- release-readiness packet showing the required signals for a change
- incident evidence showing that the signal helped detect or resolve an issue

If a signal is expected but there is no evidence that it can be observed or
routed, leave it open rather than implying coverage.

## Open Questions

- Which signals are mandatory for MVP versus optional for later phases?
- Which alert-routing and paging tools will actually be used in the live
  operating model?
- What severity taxonomy will be adopted across release, incident, and support
  workflows?
- Which customer-visible workflows need dedicated health indicators rather
  than only infrastructure metrics?
- Which signals are required before a high-risk release can pass the release
  gate?

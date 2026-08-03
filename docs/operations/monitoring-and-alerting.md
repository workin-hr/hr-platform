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

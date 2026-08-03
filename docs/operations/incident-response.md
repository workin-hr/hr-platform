# Incident Response

Use this document to define how the team detects, classifies, escalates,
communicates, and reviews operational incidents. It applies to both release-
related failures and non-release incidents that affect service availability,
data correctness, security posture, or critical customer workflows.

This is a planning template, not evidence that a live incident-management
process already exists. If an escalation path, paging channel, or response
role has not yet been established, record that openly.

## Incident Class (e.g. data exposure, outage, migration failure, device integration failure)

Describe the class of incident in concrete terms so responders know whether
the event is primarily:

- security-related
- availability-related
- data-integrity-related
- migration-related
- integration-related
- customer-workflow-related
- vendor or gateway-related

Where useful, define severity bands separately from incident class. Severity
rules should be based on measurable impact rather than intuition alone.

## Detection Method

Record how the incident is first detected.

Possible detection methods include:

- automated alert
- failed smoke or post-deployment check
- customer report
- support escalation
- migration validation failure
- operator observation
- vendor or third-party notification

If detection depends on monitoring that is not yet implemented, call that out
explicitly as an operational gap.

## Escalation Path

Define how the incident moves from initial detection to active human
ownership.

At minimum, capture:

- who receives the first signal
- who is authorized to declare an incident
- who joins for technical triage
- who decides whether rollback, containment, or customer communication is
  required
- who must be informed for high-severity incidents

If different incident classes use different paths, document those differences
rather than forcing one generic route.

## Response Owner

Record the human role accountable for coordinating the response.

Depending on the incident, that may include:

- incident commander or release owner
- engineering owner
- operations owner
- security owner
- migration owner
- support or customer-communication owner

Agents may help prepare notes or evidence, but humans own live incident
decisions and communications.

## Communication Plan (internal and customer-facing)

Reference the communication expectations for the incident:

- internal coordination channel
- stakeholder update cadence
- criteria for customer-facing communication
- channel used for customer notice
- owner for drafting, approving, and sending updates
- resolution or closure communication after mitigation

Use `docs/operations/customer-communication.md` when the incident requires
customer-facing messaging.

## Post-Incident Review Requirement

State whether a post-incident review is mandatory and what it must include.

A meaningful post-incident review should capture:

- incident summary and timeline
- impact assessment
- root cause or current best explanation
- mitigation and recovery steps taken
- what evidence confirmed recovery
- follow-up actions to prevent recurrence
- owner and due date for each follow-up action

If a review is intentionally skipped for a low-severity event, record why.

## Evidence

Link the artifacts that prove the incident was detected, handled, and closed
responsibly. Evidence may include:

- alert or incident ticket
- timeline notes
- rollback or mitigation record
- customer communication record
- validation results showing recovery
- post-incident review document
- follow-up action tracker

If no evidence exists, the incident process should be treated as incomplete.

## Open Questions

- Which human roles will form the real incident response path in the live
  operating model?
- What severity model will be used, and who is authorized to assign it?
- Which incident classes require immediate customer communication?
- Which incidents require automatic rollback versus manual containment?
- What review threshold separates a lightweight incident note from a full
  post-incident review?

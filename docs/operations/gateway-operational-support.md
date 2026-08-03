# Gateway Operational Support

Scope: operational support for the local .NET edge gateway candidate
direction (ADR-0006). Nothing here should be read as confirming the gateway
exists or is deployed — it does not, and ADR-0006 remains Proposed.

## Support Scenario (offline, reconnect, firmware mismatch, vendor outage)

Describe the support scenario in concrete terms so responders can distinguish
between local connectivity issues, vendor incompatibility, and broader
platform failures.

Typical scenarios include:

- gateway offline or unreachable
- gateway reconnect after network loss
- device firmware mismatch or unsupported protocol behavior
- vendor API outage or vendor-side throttling
- duplicate, delayed, or missing attendance events
- local environment drift such as certificate, host, or configuration mismatch
- rollback from a gateway-related release or configuration change

If the scenario could be handled without a gateway because the vendor supports
another pattern, record that ambiguity explicitly rather than assuming the
gateway is mandatory.

## Detection Method

Record how the scenario is detected.

Possible detection methods include:

- automated health or heartbeat alert
- failed polling or callback processing
- device sync lag or missing event detection
- customer or site-admin report
- vendor notice
- post-deployment validation failure
- operator inspection during rollout or support triage

If the detection method depends on observability not yet designed, record that
as an operational gap.

## Response Procedure

Document the expected response path for the scenario.

For each support procedure, capture:

- first triage step
- checks that distinguish local gateway failure from vendor or device failure
- temporary containment or workaround
- criteria for retry, restart, rollback, or escalation
- evidence that confirms recovery
- follow-up action if the problem is recurring or firmware-specific

Typical response actions may include:

- verify gateway process or service health
- confirm network reachability to the device or vendor endpoint
- compare observed behavior against known vendor or firmware capability notes
- switch to a safe fallback mode if one exists
- escalate to incident response if customer attendance workflows are impacted
- record firmware or vendor-specific findings back into discovery artifacts

## Ownership

Record the human roles responsible for:

- first-line triage
- gateway technical support
- vendor or device escalation
- release or rollback decision if a deployment caused the issue
- customer communication when customer workflows are affected

Because the gateway direction is still Proposed, role names may remain generic
until humans define the live support model.

## Evidence

Link the artifacts that prove the scenario, the response taken, and the
recovery status. Evidence may include:

- health-check or alert output
- gateway logs
- vendor or device error details
- firmware or model inventory reference
- capability-matrix reference
- rollback or mitigation notes
- customer-impact confirmation
- post-incident review or follow-up task

If the issue reveals a new vendor or firmware constraint, that evidence should
also update the device discovery documents rather than remaining only in an
incident note.

## Open Questions

- Which vendors actually require a local gateway versus direct push, polling,
  or cloud API integration?
- Which gateway failures are safe to recover automatically, and which require
  human intervention?
- What local deployment footprint, service model, and support tooling would a
  gateway require if ADR-0006 is accepted?
- Which customer-impact thresholds require immediate incident escalation for
  attendance failures?
- How will firmware-specific findings be fed back into the vendor capability
  matrix and device inventory?

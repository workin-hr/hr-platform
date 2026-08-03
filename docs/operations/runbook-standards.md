# Runbook Standards

Defines what a runbook must contain before it is considered usable during
an incident — not the runbooks themselves, which do not exist yet.

## Required Runbook Sections (proposed)

- Trigger / symptom
- Diagnosis steps
- Mitigation steps
- Escalation contact
- Rollback reference
- Verification steps after mitigation

Each runbook should also make clear:

- scope and supported system boundary
- prerequisites or required access
- expected evidence to capture while executing it
- customer-communication dependency, if any
- related release, incident, or monitoring references

If a runbook depends on tribal knowledge outside the repository, it is not
yet complete enough for operational use.

## Ownership Of Runbook Freshness

Record the human role responsible for keeping each runbook current. The owner
should be the team best positioned to notice drift between the documented
steps and the real system behavior.

Ownership should include responsibility for:

- updating the runbook after material system changes
- reviewing it after relevant incidents or failed releases
- ensuring links and references still work

## Review Cadence

Define how often runbooks are reviewed and what events force immediate review.

Useful triggers include:

- after an incident
- after a rollback
- after a significant architecture or deployment change
- on a scheduled operational review cadence
- when a validation or smoke-test gap reveals stale instructions

## Evidence

Link the proof that a runbook is maintained and usable. Evidence may include:

- review record
- incident or exercise note showing it was used
- post-incident update record
- validation checklist proving referenced steps still exist
- owner assignment record

If no evidence exists, the runbook standard may be documented, but readiness
to operate that runbook remains unproven.

## Open Questions

- Which systems require formal runbooks before implementation may be promoted
  to production?
- What review cadence is appropriate for low-change versus high-change areas?
- Which runbooks must be exercised through drills rather than only reviewed?
- How will runbook ownership map to the eventual operating teams?

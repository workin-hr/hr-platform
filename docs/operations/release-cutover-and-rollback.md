# Release Cutover And Rollback

Scope: the overall system rollout cutover and rollback plan. For the
database-migration-specific cutover/rollback assumptions, see
`docs/migration/cutover-and-rollback-assumptions.md`.

This document should be completed for any release that introduces customer
impact, operational risk, non-trivial deployment sequencing, or a plausible
need to reverse the change after rollout starts. Unknown details must remain
explicit; do not invent production timings, topology, or rollback guarantees
that Discovery has not evidenced.

## Cutover Step

Record the release as an ordered sequence of concrete steps. Each step should
be specific enough that a reviewer can see what happens before customer
traffic is affected, while customer traffic is affected, and after the release
is considered live.

For each step, capture:

- step name
- purpose
- preconditions
- execution owner
- expected outcome
- whether the step is reversible
- evidence or validation check that confirms success

Typical step classes include:

- pre-release verification
- maintenance-window entry, if one exists
- deployment or rollout action
- configuration or feature-toggle change
- migration or compatibility action
- smoke validation
- customer or stakeholder notification
- maintenance-window exit

## Sequence / Dependencies

Document dependencies between steps rather than assuming they are obvious.

At minimum, identify:

- which steps must finish before the next may start
- which steps require human approval before continuing
- which systems, environments, or teams the release depends on
- which checks must pass before customer traffic or production data is exposed
- which steps can safely pause and which create a point of no easy return

If there is a point after which rollback becomes materially harder, mark it
explicitly.

## Rollback Trigger

Define the conditions that force a stop, rollback, or human re-evaluation.
Triggers should be observable and should not rely on vague judgment alone.

Examples of valid trigger types:

- smoke test failure
- migration validation failure
- elevated error rate or failed health check
- contract incompatibility observed by a consumer or Flutter client
- missing or incorrect customer communication
- monitoring or alert-routing gap discovered during rollout
- inability to complete a required release step within the approved window

For each trigger, capture:

- trigger condition
- who is allowed to declare it
- whether rollout pauses or immediately rolls back
- what evidence confirms the trigger was real

## Rollback Procedure

The rollback procedure should describe how the release returns to a safe
state, not merely state that rollback is possible.

For each rollback path, capture:

- initiating trigger
- rollback owner
- rollback steps in order
- whether data rollback is required, prohibited, or not yet discovered
- validation steps proving the rollback succeeded
- follow-up communication required after rollback

If a full rollback is not possible, say so plainly and describe the fallback
containment plan instead.

## Owner

Record the responsible human roles for:

- release owner
- execution owner for each cutover step
- rollback decision-maker
- operations owner
- communication owner
- migration owner, if database-affecting work is involved

Agents may prepare this plan, but only humans may approve and execute the
final cutover decision.

## Evidence

Link the artifacts that justify and validate the plan. Relevant evidence may
include:

- release-readiness review
- test results
- migration validation queries or dry-run results
- smoke-test checklist
- monitoring and alert-routing definition
- customer communication draft
- change request or approved specification
- risk acceptance record for any residual risk

If no evidence exists yet, leave the section incomplete rather than inserting
fictional examples.

## Open Questions

- Which release types require a maintenance window versus live rollout?
- Which changes are safely reversible, and which require forward-fix-only
  handling?
- What is the latest safe decision point for aborting before customer impact?
- Which rollback steps depend on data-migration behavior that Discovery has
  not yet evidenced?
- Which human roles must be present during a high-risk cutover?

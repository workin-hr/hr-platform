# Release Readiness

This document defines the minimum review packet required before any
customer-affecting release, migration cutover, or production-risking change
is approved. It is intentionally evidence-driven: unknowns must remain
explicit, and no release may be treated as ready on the basis of chat claims,
tribal knowledge, or assumed parity with the legacy system.

Use this together with:

- `docs/testing/test-strategy.md`
- `docs/operations/release-cutover-and-rollback.md`
- `docs/operations/production-smoke-and-post-deployment-validation.md`
- `docs/operations/customer-communication.md`
- `docs/operations/monitoring-and-alerting.md`
- `docs/migration/cutover-and-rollback-assumptions.md`
- `docs/bootstrap/risk-register.md`

## Release Gate

The release gate is human-controlled. A release is only ready when the
required evidence exists, the named owners have reviewed that evidence, open
questions are either resolved or explicitly accepted as risk, and a human
decision-maker records a go/no-go decision.

Minimum gate expectations:

- the scope is documented and traceable to approved work items
- test evidence matches the release risk level
- compatibility, migration, and operational risks are visible
- rollback and communication plans exist for customer-facing change
- monitoring, smoke tests, and post-deployment checks are defined
- unresolved items are called out explicitly rather than hidden

## Required Evidence Before Release (test results, migration validation, security review)

Every release candidate should assemble a review packet containing the
following evidence, marking any unknown item as `Not yet discovered` or `Not
applicable` with a reason:

- scope summary: what is being released, why, and what customer or operator
  behavior may change
- requirements and approval evidence: linked specification, ADRs, and human
  approval where the change depends on a proposed direction
- test evidence: commit, pull-request, and pre-release checks appropriate to
  the change, following `docs/testing/test-strategy.md`
- compatibility evidence: API, Flutter, contract, or consumer-impact evidence
  for any externally visible behavior change
- migration evidence: schema, data-migration, cutover, rollback, and
  validation evidence for any database-affecting release
- security evidence: security review findings, exception decisions, and any
  required secrets-handling or access-control checks
- operational evidence: deployment sequence, smoke tests, post-deployment
  validation, alert routing, and support readiness
- communication evidence: internal and customer communication plan where the
  change is customer-visible, risky, or time-bound
- risk evidence: known release-specific risks, triggers, and contingencies

The release owner should reject the packet if evidence is implied but not
linked.

## Sign-Off Owner

The exact people are not yet discovered, but the sign-off roles should be
explicit for every release:

- release owner: the human accountable for the go/no-go decision
- engineering owner: confirms implementation scope and technical readiness
- test or quality owner: confirms test evidence is adequate for the risk
- operations owner: confirms deployment, smoke-check, rollback, and support
  readiness
- security owner: confirms security review is complete when the change has
  security impact
- product or customer owner: confirms communication and customer-impact
  readiness when the release changes externally visible behavior

No agent may approve or merge its own release work. Human sign-off is
mandatory.

## Go/No-Go Criteria

Use the following decision rules:

Go only if:

- required evidence is present and reviewable
- all blocking defects are resolved or explicitly accepted by a human owner
- rollback conditions and rollback steps are defined
- production smoke and post-deployment checks are prepared
- monitoring and alert routing are defined for the affected area
- customer communication is prepared where needed
- no open question would make the release unsafe or non-reversible

No-go if:

- required evidence is missing
- compatibility or migration impact is unknown for a risky change
- rollback is absent for a non-trivial or customer-affecting release
- critical monitoring, smoke validation, or alert ownership is undefined
- a human owner required for sign-off is unknown or unavailable
- release timing depends on assumptions that have not been evidenced

If a release proceeds with known residual risk, the accepting human owner
must record that risk and the reason for acceptance.

## Evidence

Record each release-readiness review with at least:

- release name or identifier
- candidate version, branch, or commit SHA
- planned release date
- release owner
- linked specification or change set
- linked test evidence
- linked migration evidence, if any
- linked security evidence, if any
- linked cutover/rollback plan
- linked smoke-test or post-deployment validation checklist
- linked communication plan, if any
- final decision: `Go`, `No-Go`, or `Go with accepted risk`
- decision timestamp
- reviewer names or roles

If no release exists yet, leave this section empty rather than creating
fictional examples.

## Open Questions

- Which exact human roles and names will own release approval in the live
  organization?
- Which changes require one approver versus multiple approvers?
- What is the minimum evidence threshold for low-risk configuration-only
  releases versus high-risk migration or compatibility releases?
- Which environments and deployment methods will exist after Discovery and
  approved ADRs define them?
- Which production smoke checks can be automated, and which require manual
  operator validation?

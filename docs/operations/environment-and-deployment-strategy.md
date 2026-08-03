# Environment And Deployment Strategy

## Environment (dev / staging / production / other)

Record each environment explicitly rather than assuming a standard set will
exist. If an environment is not yet approved, mark it as a candidate rather
than as a fact.

Typical entries may include:

- local development
- shared integration or QA
- staging or pre-production
- production
- temporary migration rehearsal environment

## Purpose

Describe what decisions or checks the environment supports.

Examples:

- developer feedback and local verification
- integration testing across service boundaries
- release rehearsal or smoke-validation dry run
- migration rehearsal
- customer-facing production traffic

If two environments appear to serve the same purpose, call that out as a sign
that the strategy may be redundant.

## Deployment Method (candidate, pending Discovery and ADR)

Describe how changes are expected to reach the environment.

Capture:

- whether deployment is manual, automated, or hybrid
- whether rollout is full, phased, or feature-flag-driven
- whether the method is reversible
- which approvals are required before deployment proceeds

Do not lock in a delivery model that approved ADRs and Discovery have not yet
justified.

## Ownership (team or role responsible for deploys to this environment)

Record the human team or role accountable for deployment to the environment,
including who can approve, execute, and halt a deployment.

If ownership is unclear, treat that as an operational gap rather than leaving
it implicit.

## Access Control Notes

Document the expected access boundary for the environment.

Examples:

- who may deploy
- who may view logs or dashboards
- who may change configuration
- whether agent access is prohibited, read-only, or tightly scoped
- whether customer or production data is permitted

Do not insert real credentials, hostnames, or secret values here.

## Evidence

Link the artifacts that justify the environment definition, such as:

- approved ADRs
- release or deployment workflow definition
- access-control decision record
- validation or smoke-test expectations for that environment
- migration rehearsal evidence, if relevant

If an environment is only hypothetical, leave the evidence incomplete rather
than implying it exists.

## Open Questions

- Which environments are actually required for MVP delivery?
- Which environments may contain customer-like or production-derived data?
- What deployment approach is acceptable for production-risking changes?
- Which environments need independent smoke and rollback validation?
- Which human roles own environment administration and deployment approval?

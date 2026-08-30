# Release Cutover And Rollback

Scope: the overall system rollout cutover and rollback plan. For the
database-migration-specific cutover/rollback assumptions, see
`docs/migration/cutover-and-rollback-assumptions.md`.

This document should be completed for any release that introduces customer
impact, operational risk, non-trivial deployment sequencing, or a plausible
need to reverse the change after rollout starts. Unknown details must remain
explicit; do not invent production timings, topology, or rollback guarantees
that Discovery has not evidenced.

## Phase 1 Rollback: What Is Verified, And The One Thing That Is Not

*Added 2026-08-30 against G11, which requires Phase 1's rollback property to be
**verified rather than assumed** — it is the reason the phase's risk profile is
considered acceptable. The sections below this one remain the unfilled template
for the release plan itself; this section records only what has actually been
established, per the instruction above not to invent guarantees Discovery has
not evidenced.*

### The claim under test

The completion plan's G11 states: *"Phase 1 has a genuinely cheap rollback — the
database is unchanged and PHP still runs."* That is two claims, and they have
different evidence.

### Claim 1 — the database is unchanged: **verified, in code**

Java applies **no DDL to the legacy MariaDB schema**. Flyway is bound to
`spring.datasource` and its migrations under `db/migration/{common,rls}` are the
Phase-2 PostgreSQL target schema. The legacy connection is a separate,
independently configured datasource (`app.legacy-db.*`), built by
`LegacyPersistenceConfig`, which is `@Profile("phase1-mysql")` and states
outright: *"No Flyway ownership of any MariaDB schema."* `spring.jpa.hibernate.ddl-auto`
is `validate`, not `update`.

So rolling back to PHP means pointing PHP at a database Java never restructured.
Java writes **rows** through the legacy tables, exactly as PHP does; it changes
no columns, tables, constraints or routines.

### Claim 2 — sessions survive in both directions: **verified, conditional on one input**

This is the part that decides whether a rollback is invisible to users or logs
out the entire active base a second time.

`LegacyPhpJwtService` emits tokens byte-identical to `jwtEncode()`
(`apis/helpers/functions.php:420-430`): the same header, the same HS256
signature construction, the same flat claim set **in the same order** — order
matters, because the signature covers the encoded bytes. Default expiry is
87600 hours, matching PHP's `JWT_EXPIRE_HOURS`.

`LegacyPhpJwtWireCompatibilityTest` pins all of it, and reimplements
`jwtEncode()` independently rather than calling the production encoder, so a
change to the encoder cannot drag the expectation along with it. It asserts
both directions: a Java-minted token equals what PHP would have produced, and a
PHP-minted token is accepted by Java.

**The unverified input is the signing secret.** All of the above holds *only if*
the deployed `app.jwt.secret` is byte-identical to PHP's
`AppConfig::JWT_SECRET`. Nothing in this repository can check that:
`JwtSecretStartupCheck` rejects a known placeholder value and nothing more, and
no document states the parity requirement. The test pins the failure mode
explicitly — a token signed with a different secret is rejected outright.

If the two secrets differ:

| Moment | Consequence |
|---|---|
| Cutover | every live PHP-issued session invalid at once — a mass forced logout of the entire active user base |
| Rollback | every session issued by Java since cutover invalid — a **second** mass forced logout |

If they match, both transitions are transparent and no user is logged out in
either direction.

### Required pre-cutover verification

This is the concrete step G11's rehearsal is missing. It costs one request and
closes the only unverified input:

1. With the Java backend deployed and configured for the cutover, obtain a token
   from a Java-served login route.
2. Present that token to the **PHP** application on an authenticated endpoint.
3. It must be accepted. If PHP answers `unauthorized_invalid_token`, the two
   deployments do not share a signing secret — **stop the cutover**, because
   both the cutover and any rollback will force-log-out every user.
4. Repeat in reverse: a token from PHP must be accepted by Java.

Record the result here. Until it is recorded, G11 is not closed, and the
"transparent rollback" property must be described as *expected* rather than
*verified*.

### A stale assumption this supersedes

`docs/migration/cutover-and-rollback-assumptions.md` (2026-08-04) records that
existing `hr-legacy` JWTs are *"not migrated or dual-validated against the new
backend"*, and that rollback is therefore *"not silently transparent to users
who already migrated."* That was written for ADR-0005's new authentication
design, **before D-111 settled Phase 1 as zero-client-change**. Under D-111 the
tokens are not migrated because they do not need to be: the same secret and the
same claim shape make them the same tokens. That assumption should be revisited
rather than carried forward as-is — it currently overstates Phase 1's rollback
cost, and overstating it is how a cheap rollback stops being attempted.

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

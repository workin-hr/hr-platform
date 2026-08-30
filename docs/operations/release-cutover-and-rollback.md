# Release Cutover And Rollback

Scope: the overall system rollout cutover and rollback plan. For the
database-migration-specific cutover/rollback assumptions, see
`docs/migration/cutover-and-rollback-assumptions.md`.

This document should be completed for any release that introduces customer
impact, operational risk, non-trivial deployment sequencing, or a plausible
need to reverse the change after rollout starts. Unknown details must remain
explicit; do not invent production timings, topology, or rollback guarantees
that Discovery has not evidenced.

## Phase 1 Rollback: What Is Verified, And What Is Not

*Added 2026-08-30 against G11, which requires Phase 1's rollback property to be
**verified rather than assumed** — it is the reason the phase's risk profile is
considered acceptable. Revised the same day after independent review corrected
two claims in the first draft. The sections below this one remain the unfilled
template for the release plan itself; this section records only what has
actually been established, per the instruction above not to invent guarantees
Discovery has not evidenced.*

### The claim under test

The completion plan's G11 states: *"Phase 1 has a genuinely cheap rollback — the
database is unchanged and PHP still runs."* That is two claims, and they have
different evidence. **Neither is true as literally stated**; both are close
enough to true that the conclusion survives, but the gaps are where a cutover
goes wrong, so they are recorded precisely.

### Claim 1 — "the database is unchanged": **true of the legacy contract, false of the database**

**What is verified.** Java applies **no DDL to the vendored legacy schema**.
Flyway is bound to `spring.datasource` and its migrations under
`db/migration/{common,rls}` are the Phase-2 PostgreSQL target schema; it owns no
MariaDB location. The legacy connection is a separate datasource
(`app.legacy-db.*`) built by `LegacyPersistenceConfig`, which is
`@Profile("phase1-mysql")` and states outright: *"No Flyway ownership of any
MariaDB schema."* That config sets `hibernate.hbm2ddl.auto` to **`none`** on the
legacy `EntityManagerFactory` — Hibernate neither migrates nor validates it, and
the vendored schema's drift check (`scripts/check_legacy_schema_drift.py`) is
what keeps the mapping honest instead.

> An earlier draft of this section cited `spring.jpa.hibernate.ddl-auto=validate`
> as the protection. That property governs the **PostgreSQL** datasource and
> says nothing about MariaDB. The real setting is `none`, which is stronger for
> this purpose — but the citation was wrong and is corrected here.

**What is not true.** Phase 1 adds exactly one table to the legacy database:
**`legacy_refresh_tokens`** (`backend/src/test/resources/legacy/phase1_extensions.schema.sql`),
which does **not** exist in production legacy MySQL. It is new infrastructure
this application owns, authorised as a deliberate, narrow exception by **D-043
amendment 3** — narrow enough that D-050/D-051 later declined to spend a second
schema exception on an unrelated problem rather than widen it.

So the cutover is not zero-DDL. It has a schema prerequisite, and that
prerequisite has **no decided provisioning mechanism**: ADR-0013's Open
Questions record that how `legacy_refresh_tokens` gets created against a real,
non-test MariaDB instance is undecided, and `LegacyPersistenceConfig` says a
persistent instance *"needs its own, separately-approved provisioning mechanism
first."* Today the table exists only where a test container applies
`phase1_extensions.schema.sql` out-of-band.

**Rollback treatment.** The change is purely additive and PHP has no reference
to the table anywhere, so a rollback does not need to reverse it: the table is
simply orphaned, and left in place it costs nothing and preserves the option of
rolling forward again. Dropping it is therefore **not** part of the rollback
procedure. This is the reason the overall "cheap rollback" conclusion still
holds despite the claim being literally false — but it holds by argument, not by
the absence of a schema change.

**This is an open G11 item, not a closed one.** The provisioning mechanism must
be decided and approved before cutover; it is a DDL statement against the
production legacy database, which is precisely the class of action that needs an
owner, a rehearsal and a rollback note rather than an implicit assumption.

### Claim 2 — sessions survive in both directions: **verified, conditional on the deployed secret**

This is the part that decides whether a rollback is invisible to users or logs
out the entire active base a second time.

`LegacyPhpJwtService` emits tokens byte-identical to `jwtEncode()`
(`apis/helpers/functions.php:420-430`): the same header, the same HS256
signature construction, the same flat claim set **in the same order** — order
matters, because the signature covers the encoded bytes. The configured default
expiry is 87600 hours, matching PHP's `JWT_EXPIRE_HOURS`.

Two test layers pin this, both building their expectations from `PhpJwtOracle`,
an independent reimplementation of `jwtEncode()` — never from the production
encoder, so a change to the encoder cannot drag the expectation with it:

| Layer | File | What it establishes |
|---|---|---|
| Codec | `LegacyPhpJwtWireCompatibilityTest` | header, algorithm, claim set and order, signature, and the bound default expiry (exercised through property binding, not a fixture literal) |
| Real request | `LegacyLoginEndToEndTest` | oracle-encoded employee **and** company tokens accepted over real HTTP against real MariaDB, through `LegacyPhpJwtAuthenticationFilter`, tenant re-derivation and `LegacyRequestGuard` — with a stale-`token_version` token still rejected, so the acceptances are evidence rather than an unguarded route |

The second layer exists because the codec alone is not the claim: a live request
traverses the filter chain and the token-version check, and a regression in any
of that would break every pre-cutover session while the codec test stayed green.

#### Precondition A — the two deployments must share a signing secret

All of the above holds *only if* the deployed `app.jwt.secret` is byte-identical
to PHP's `AppConfig::JWT_SECRET`. Nothing in this repository can check that:
`JwtSecretStartupCheck` rejects a known placeholder value and nothing more. The
test suite pins the failure mode explicitly — a token signed with a different
secret is rejected outright.

If the two secrets differ:

| Moment | Consequence |
|---|---|
| Cutover | every live PHP-issued session invalid at once — a mass forced logout of the entire active user base |
| Rollback | every session issued by Java since cutover invalid — a **second** mass forced logout |

If they match, both transitions are transparent and no user is logged out in
either direction.

#### Precondition B — the secret must be at least 32 bytes, or Java will not start

Byte equality is not the only constraint on that value, and this one fails
*earlier* and more confusingly. The `phase1-mysql` context also constructs
`JwtService` (it is component-scanned by `LegacyPersistenceConfig`; only the
other `identity` classes are excluded), and its constructor calls
`Keys.hmacShaKeyFor(secret.getBytes())`, which **rejects any key shorter than
256 bits**. PHP's `hash_hmac` has no such floor and accepts a secret of any
length.

So had the legacy secret been shorter than 32 bytes, configuring Java with the
byte-identical value required by Precondition A would have prevented the
application from starting at all — and the token exchange below could never have
been run.

**Checked: the legacy secret is 65 bytes**, comfortably above the floor, so this
precondition is satisfied and is not a cutover blocker. It is recorded because
it is invisible in the code (the constraint comes from JJWT, not from anything
Phase 1 wrote) and because it constrains any future rotation of that secret: a
replacement value must be ≥ 32 bytes or Phase 1 will not boot. The value itself
is not recorded here or anywhere in this repository.

### Required pre-cutover verification

These are the concrete steps G11's rehearsal is missing.

1. **Provision `legacy_refresh_tokens`** in the production legacy database by an
   approved mechanism (ADR-0013 Open Questions — undecided). Rehearse it against
   a restored copy first, and record the mechanism, its owner and its lock
   duration here. Without this table the Java application cannot serve a login.
2. **Exchange a token in both directions.** With the Java backend deployed and
   configured for the cutover, obtain a token from a Java-served login route and
   present it to the **PHP** application on an authenticated endpoint. It must be
   accepted. Then reverse it: a token from PHP must be accepted by Java.
3. If PHP answers `unauthorized_invalid_token`, the two deployments do not share
   a signing secret — **stop the cutover**, because both the cutover and any
   rollback will force-log-out every user.

Record the results here. Until they are recorded, G11 is not closed, and the
"transparent rollback" property must be described as *expected* rather than
*verified*.

### A stale assumption this supersedes

`docs/migration/cutover-and-rollback-assumptions.md` (2026-08-04) records that
existing `hr-legacy` JWTs are *"not migrated or dual-validated against the new
backend"*, and that rollback is therefore *"not silently transparent to users
who already migrated."* The same statement is made, at more length, by its
owning design: `docs/security/authentication-remediation-design.md`.

Both were written for ADR-0005's new authentication design, **before D-111
settled Phase 1 as zero-client-change**. Under D-111 the tokens are not migrated
because they do not need to be: the same secret and the same claim shape make
them the same tokens. Both documents have been annotated in place — the original
text still governs the **Phase-2 auth cutover**, where it continues to apply
unchanged; it simply does not describe Phase 1.

Carrying it forward unscoped overstates Phase 1's rollback cost, and overstating
it is how a cheap rollback stops being attempted.

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

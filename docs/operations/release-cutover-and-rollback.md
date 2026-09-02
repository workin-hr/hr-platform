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
different evidence, and **neither is true as literally stated**.

The first is close enough to true that its conclusion survives by argument (see
Claim 1). **The second has not been examined at all** — see Claim 2b — so the
cheap-rollback conclusion as a whole is **expected, not established**, and stays
open until the prescribed rollback rehearsal succeeds (**R-025**). Treat it as a
conclusion under test, not a finding, when using this document to approve a
cutover.

### Claim 1 — "the database is unchanged": **true of the legacy contract, false of the database**

**What is verified.** Java applies **no DDL to the vendored legacy schema**.
Flyway is bound to its own dedicated `flywayDataSource` and its migrations under
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

**What is not true.** Phase 1 adds tables to the legacy database:
**`legacy_refresh_tokens`** (`backend/src/test/resources/legacy/phase1_extensions.schema.sql`),
which does **not** exist in production legacy MySQL, and — since D-156
(2026-09-02) — the five attendance-device tables in the same file
(`attendance_devices`, `employee_device_identities`, `device_punches`,
`unclaimed_device_sightings`, `device_operation_logs`). The device tables are
additive and referenced by nothing in PHP; they share the provisioning gate
described below and the same orphan-and-leave rollback treatment. It is new infrastructure
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

Two test layers pin this. Every token-shaped expectation is built from
`PhpJwtOracle`, an independent reimplementation of `jwtEncode()` — never from
the production encoder, so a change to the encoder cannot drag the expectation
with it. (The header and expiry assertions compare against literals and
arithmetic rather than the oracle; they need no oracle to be meaningful.)

**The oracle's independence has a limit worth stating**: it is an independent
*Java* reading of `jwtEncode()`, so a shared misreading of PHP's `json_encode`
would pass both layers. PHP escapes `/` as `\/` and non-ASCII as `\uXXXX`;
neither the oracle nor `LegacyPhpJwtService` does. No claim value in the current
contract contains either — the roles are plain ASCII enums — so this is inert
today, but a future role containing `/` or Arabic text would sign differently in
the two systems while every test stayed green. The bidirectional exchange in the
pre-cutover steps is the backstop that does not share this blind spot.

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

### Claim 2b — "PHP still runs": **not verified at all**

G11's second half is a statement about the **rollback target**, and nothing in
this section establishes it. Session compatibility is necessary for a
transparent rollback; it is nowhere near sufficient. If the PHP artifact, its
runtime configuration, or the traffic-routing path back to it is unavailable or
has drifted at the moment rollback is called, then every token test above passes
and the rollback is still impossible.

Nothing here provides a PHP deployment, a routing reversal, a health check, or
post-rollback smoke evidence — and the release/rollback template below this
section is still unfilled. **This is tracked as its own blocker (R-025) rather
than folded into the token work**, because the two fail independently and only
one of them has been looked at.

An end-to-end rollback rehearsal — route traffic back to PHP on a non-production
environment, confirm it serves, run the smoke checks — is what closes it. Until
then the "cheap rollback" claim rests on an untested assumption about the thing
being rolled back to.

> **Every URL in this document is a router path, without `.php`.** That is
> what clients send and what both systems serve; requesting the `.php` file
> directly bypasses the bootstrap it assumes and returns 500 in PHP
> (**R-028**). An earlier version of these steps used the file names taken from
> the endpoint inventory, which would have had an operator probing URLs that
> fail in both stacks and reading that as a cutover problem.

### Required pre-cutover verification

These are the concrete steps G11's rehearsal is missing. Each names its own
negative control, because every one of these checks can otherwise pass for the
wrong reason.

**1. Provision the Phase-1-owned tables** — `legacy_refresh_tokens` and, if the
attendance-device receiver is to be enabled, the five device tables (D-156) —
in the production legacy database by an approved mechanism (ADR-0013 Open
Questions — undecided). Rehearse it against a
restored copy first, and record the mechanism, its owner and its lock duration
here.

> **Do not use a successful login as the check.** The parity login route
> (`/apis/api/auth/login_employee` → `LegacyPhpLoginService`) never touches
> the refresh-token repository — it updates `employees`/`push_tokens` and issues
> a JWT — so it returns 200 whether or not the table exists. The table is
> reached by `LegacyRefreshTokenService.revokeAllForEmployee()`, called from
> `LegacyProfileService` (password change, logout) and `LegacyOtpAuthService`
> (`reset_password.php`, **employee mode only**). A login-only check therefore
> passes while exactly those routes are broken.

**How to verify it, without touching customer data.** The obvious write-path
checks are **destructive against production and must not be used on a real
account**:

| Check | What it actually does |
|---|---|
| Password change | replaces the employee's credential — they can no longer log in with the password they have |
| Logout | worse: `LegacyProfileService.logout()` deletes push tokens, **deactivates the employee**, and notifies the company that the employee left, all before it reaches `legacy_refresh_tokens` |

Legacy's "logout" is an account deactivation, not a session end. Running it
against a live employee to test a table would disable that person's account and
send their employer a departure notice. Use this instead, in order:

1. **On the cutover database, read-only:** `SHOW CREATE TABLE legacy_refresh_tokens`.
   Confirm it exists and its shape matches `phase1_extensions.schema.sql` —
   columns, the `token_hash` unique key, the status check constraint, both
   indexes. This is the only step that touches production, and it mutates
   nothing.
2. **Confirm the runtime principal's grants on the production table.**
   `SHOW CREATE TABLE` proves the table exists; it does not prove the
   application can write to it. An operator account can read the definition
   while the account the application actually connects as (`app.legacy-db.username`)
   holds no `INSERT`/`UPDATE`/`DELETE` on it — newly provisioned tables do not
   inherit grants. Verify privileges for the **runtime** principal, not the one
   running the check: `SHOW GRANTS FOR CURRENT_USER()` on an application
   connection, or the equivalent `information_schema` lookup.
3. **On the restored copy used for the provisioning rehearsal, not production:**
   exercise one write path end to end — **`reset_password.php` with
   `type=employee`** — and confirm it succeeds rather than erroring. Record
   evidence that it actually reached the refresh-token write — **the query log
   showing the `UPDATE` against `legacy_refresh_tokens` executed without
   erroring** — not merely that the request returned 200.

   > **Expect zero rows affected, and treat that as success.** Under D-111 no
   > Phase-1 route ever *inserts* into this table: the only issuer,
   > `LegacyLoginService`, has no controller, so the table is empty in
   > production. `revokeAllForEmployee()` is an unconditional bulk `UPDATE`,
   > which is exactly why it is a valid probe — it errors if the table is absent
   > and succeeds against zero rows if it is present. Do **not** wait for a row
   > to move to `REVOKED`; none will, and an operator who expects one will
   > report a passing check as a failure.
   >
   > The restored copy is where destructive checks belong; that is what it is
   > for.
   >
   > **Not OTP verify, and not company mode.** Two ways to run this check and
   > learn nothing:
   >
   > - `LegacyOtpAuthService.verifyOtp()` never touches the refresh-token
   >   repository at all; only `resetPassword()` does.
   > - Inside `resetPassword()`, the **company** branch calls
   >   `updateCompanyPasswordByPhone()` and stops. Only the **employee** branch
   >   reaches `revokeAllForEmployee()`. A `type=company` reset therefore
   >   succeeds with the table missing.
   >
   > Both are the same false-confidence trap as using login: a green result that
   > never touched the thing under test.
   >
   > **And this path fails badly.** `resetPassword()` calls
   > `updateEmployeePasswordByPhone()` and *only then*
   > `revokeAllForEmployee()`, and `LegacyOtpAuthService` carries **no
   > `@Transactional` anywhere**. If the table is missing or unwritable, the
   > password change has already been committed when the request errors: the
   > user's password is changed, their sessions are not revoked, and they are
   > told the operation failed. Partial write, no rollback.

4. **Only if a production write-path confirmation is judged necessary:** use a
   named disposable canary employee in a non-customer company, created for this
   purpose, and record its identity and cleanup here. Never a real employee, and
   never an account belonging to a customer company — step 3 covers the same
   ground without the exposure. Note that step 2 is **not** optional and does not
   substitute for this: grants and write-path behaviour are different questions.

**2. Exchange a token in both directions**, against a route that genuinely
requires authentication.

> **Do not pick the route arbitrarily.** A 200 from a route that tolerates
> anonymous callers proves nothing about the token. `/apis/api/complaints/create`
> is exactly such a route — `LegacyRequestGuard` carries explicit handling for
> it because it proceeds anonymously when token decoding fails — so using it
> would false-pass the single check that stands between the cutover and a mass
> forced logout.
>
> Use `/apis/api/attendance_exception_types/list`, which requires
> authentication in both systems and is the route the automated end-to-end tests
> already use for this purpose.
>
> **Use a disposable smoke account, never a real employee.** The Java login
> itself mutates production state *before* any compatibility result is known:
> `LegacyPhpLoginService` increments that employee's `token_version` — which
> invalidates their current session under legacy's single-active-session model —
> and deletes their `push_tokens` rows, stopping notifications on their device.
> Verifying the signing secret would therefore log a real user out and silence
> their app, and it would do so even if the exchange then failed. Provision a
> named smoke employee in a non-customer company for this, and record its
> identity and cleanup here alongside the result.

- Obtain a token from a Java-served login and present it to **PHP** on that
  route. It must return 200 with `success: true`, and the payload must be scoped
  to the token's own company — not merely a non-error response.
- Reverse it: a token minted by PHP must be accepted by Java on the same route.
- **Negative control, required in both directions:** repeat each request with a
  token signed by a deliberately foreign secret. Both systems must reject it. If
  a foreign-signed token is *also* accepted, the positive result above is
  meaningless — the route is not enforcing what you think it is, and the exchange
  has told you nothing.

**3. Stop the cutover on rejection.** If PHP answers
`unauthorized_invalid_token` for the genuine token, the two deployments do not
share a signing secret — both the cutover and any rollback will force-log-out
every user (R-024).

**4. Rehearse the rollback itself** (R-025), which is a separate exercise from
all of the above: route traffic back to PHP on a non-production environment,
confirm it serves, and run the smoke checks against it.

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

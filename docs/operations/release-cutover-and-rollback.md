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

**What is not true.** Phase 1 adds exactly one table to the legacy database:
**`legacy_refresh_tokens`** (`backend/src/main/resources/db/phase1-mysql/phase1_extensions.sql`),
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
`phase1_extensions.sql` out-of-band.

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

**1. Provision `legacy_refresh_tokens`** in the production legacy database by an
approved mechanism (ADR-0013 Open Questions — undecided). Rehearse it against a
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
   Confirm it exists and its shape matches `phase1_extensions.sql` —
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

*Filled 2026-09-04, replacing the template that stood here — the absence
of this section is half of **R-025**, which observes that every token
check can pass while the rollback is impossible because there is nothing
to roll back to.*

Two facts shape the whole sequence. **The database is shared and
unchanged**: both stacks read and write the same MariaDB, so there is no
data migration, no backfill, and no dual-write window. And **clients do
not change** (**D-111**): the Flutter apps keep calling `/apis/**` with
the tokens they already hold. The cutover is therefore one action —
moving traffic from PHP to the jar — wrapped in checks.

### Unknowns that must be filled before this is executable

Three facts are not recorded anywhere in this repository and cannot be
invented here. Fill them in during the rehearsal, in this table:

| Fact | Why the procedure needs it | Value |
|---|---|---|
| How the jar is deployed and restarted | Steps 5 and 9, and the rollback's time cost | *(unfilled)* |
| What routes traffic to PHP today, and the exact command that reverses it | Step 8 **is** this, and the rollback is its inverse | *(unfilled)* |
| Whether PHP keeps running alongside or is stopped | Decides whether rollback is a routing change (seconds) or a redeploy (minutes to hours) | *(unfilled)* |

The third is the one that matters most and is cheapest to get right:
**leave PHP running and untouched**. Phase 1's accepted risk profile
rests on a rollback that is a routing change. Stopping PHP at cutover
converts it into a redeploy of an artifact nobody has deployed in months,
which is precisely the failure R-025 describes.

### The sequence

| # | Step | Reversible | Confirms |
|---|---|---|---|
| 1 | Provision the Phase 1 tables | Yes — `DROP TABLE` | `docs/operations/provisioning-phase1-tables.md`; then the startup check logs *all 10 owned tables are present* |
| 2 | Confirm runtime grants on the new tables | n/a | The application's own principal can write, not just the operator who created them (see "Required pre-cutover verification" above) |
| 3 | Compare signing-secret fingerprints | n/a | `docs/operations/verifying-the-signing-secret.md`. **Unequal means stop** |
| 4 | Provision the WhatsApp credentials and send one real OTP | Yes — remove the config | **R-015**. Startup logs *WhatsApp OTP delivery is configured*, and one real send arrives. A 503 here is indistinguishable to a user from the platform being down |
| 5 | Deploy the jar **without routing traffic to it** | Yes — stop it | It starts, the schema check is clean, the fingerprint matches step 3 |
| 6 | Token exchange in both directions | n/a | A Java-minted token is accepted by PHP and vice versa (**R-024**) |
| 7 | Smoke the jar directly, not through the routing layer | n/a | `docs/operations/production-smoke-and-post-deployment-validation.md` |
| 8 | **Move traffic to the jar** | Yes — this is the rollback | Customer traffic is now served by Java |
| 9 | Smoke again through the real path | n/a | The same checks, now through whatever step 8 changed |
| 10 | Watch | n/a | See "Rollback Trigger" |

Steps 1–7 touch no customer traffic. Step 8 is the only customer-visible
moment, and it is a routing change — not a deploy, not a migration, not a
restart.

### The point of no easy return

There is **no** irreversible step in this sequence, which is unusual and
worth stating plainly, because the instinct to treat a cutover as
one-way is what makes people hesitate to reverse it.

- The tables are additive and PHP references none of them. After a
  rollback they sit orphaned and harmless, and leaving them means a
  second attempt needs no DDL.
- Every row Java writes to a legacy table is legacy-shaped — that is what
  the entire parity programme established — so PHP reads its own data
  back unchanged.
- Sessions survive in both directions **provided step 3 passed**. That
  condition is the whole reason step 3 precedes step 8.

The one class of action that is not reversible is a **platform-admin
company action** — approving or rejecting a company writes a status a
rollback does not undo. Those are behind `app.platform-admin.actions.enabled`,
which defaults to false, and ADR-0015 prerequisite 7 keeps them off until
the PHP admin surface is disabled. Do not enable them during a cutover
window.

## Sequence / Dependencies

- **1 → 5.** The jar starts without the tables but the schema check
  reports them missing; deploying first means reading an alarming log for
  a gap you already planned to close.
- **3 → 8.** Hard gate. Moving traffic on a mismatched secret logs out
  every user, and rolling back logs them out again.
- **4 → 8.** Soft gate, and a judgement call: without it every OTP route
  answers 503, so registration, password reset and phone change are dead
  while login still works. If cutover proceeds without it, say so
  deliberately rather than discovering it.
- **6 → 8.** The fingerprint proves the values match; the exchange proves
  both stacks use them the same way.
- **7 → 8.** Smoking after the traffic move confuses "the build is
  broken" with "the routing is wrong". Smoke the jar first, directly.
- **Human approval before step 8.** Everything before it is preparation
  and individually reversible; step 8 is the release.

## Rollback Trigger

Roll back — reverse step 8 — on any of:

| Trigger | Observed as |
|---|---|
| Authentication failing broadly | A rise in 401s across clients, or reports of users logged out |
| Any smoke check in step 9 failing | The check's own pass condition |
| An `/apis/**` route answering differently than PHP did | Client error reports; a 500 where PHP returned a domain error |
| Error rate or latency clearly worse than the PHP baseline | `docs/operations/monitoring-and-alerting.md` |
| `otp_delivery_failed` in the logs | R-015 — the credentials are wrong or missing |
| A missing-table error from any route | R-023 — step 1 was incomplete |

**Do not debug in front of customer traffic.** Reversing step 8 costs
seconds if PHP is still running, and the jar's logs remain readable
afterwards. The one case where rolling back does *not* help is a signing
secret mismatch discovered after step 8: the forced logout has already
happened and rolling back repeats it. That case is a forward-fix decision
plus a customer communication.

## Rollback Procedure

1. Reverse step 8. Traffic returns to PHP.
2. Confirm PHP is serving: run the same smoke checks against it.
3. Leave the Phase 1 tables in place. They are inert under PHP, and
   dropping them only makes a second attempt more expensive.
4. Leave the jar running but unrouted if you can — its logs are the
   evidence for what went wrong.
5. Record what triggered it here, and in `docs/operations/incident-response.md`
   if customers were affected.

**Time-box the rolled-back state.** PHP carries three cross-tenant defects
Java does not — **R-037** (one company can create, approve, reject,
part-pay and delete another company's advances), **R-036** and **R-039**.
Java scopes all of them. So the cutover closes a live vulnerability and a
rollback re-opens it. This does **not** change the rollback decision: an
unavailable platform is worse than an authorization defect that has been
live for years, and hesitating here is how a bad release stays live. It
does mean the rolled-back state has a cost that accrues, so fix forward
on a schedule rather than settling back into PHP indefinitely.

**This procedure is not verified.** No rehearsal has been performed —
that is exactly what **R-025** records. Its first execution must be
against a non-production environment, not a real cutover.

## Owner

Repository owner, for every step. Steps 1, 3 and 4 need production
credentials and access that only the owner holds; step 8 is the release
decision.

## Evidence

| Step | Evidence it produces |
|---|---|
| 1 | The provisioning query's output before and after; the startup check's *all 10 owned tables are present* |
| 3 | Two fingerprints, recorded as equal — the values themselves, never the secrets |
| 4 | One delivered OTP message |
| 5 | Startup log: schema check clean, fingerprint matching, WhatsApp configured |
| 6 | Two accepted tokens, one per direction |
| 7, 9 | The smoke checklist's results |
| 8 | The timestamp traffic moved |

Attach these to the release-readiness packet
(`docs/operations/release-readiness.md`). The go/no-go decision is a
human one and is recorded there, not here.

## Open Questions

1. The three unknowns in the table above.
2. Whether the rollback rehearsal (**R-025**) happens on a dedicated
   environment or a restored copy. There is no non-production environment
   recorded in this repository — that gap is itself part of the risk.
3. Whether cutover proceeds before **R-015** is closed, accepting that
   registration and password reset are dead until it is.

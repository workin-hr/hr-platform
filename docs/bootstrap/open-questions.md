# Open Questions

## GitHub Governance

- Which organization-level GitHub Project, issue type, and ruleset features are available on the current plan?
- Which human maintainers will own `platform-owners`, `backend`, `frontend`, `mobile`, `gateway`, `qa`, `agents-readonly`, and `agents-write`?
- Should `hr-flutter` be created as a new organization repository or should an existing repository be renamed or transferred?

## Legacy Discovery

- Which repositories and branches accurately represent current production behavior?
- Are there deployment-specific PHP behaviors not represented clearly in version control?
- Which stored procedures, triggers, or cron-driven jobs are business-critical?

## Flutter Compatibility

- What request and response contracts are relied on by current mobile and desktop releases?
- Is there any existing client generation process, or are contracts hand-maintained?

## Payroll Migration

Surfaced by `docs/migration/payroll-module-execution-plan.md`
(2026-08-07, updated after reconciliation against `main`) — none of
these are decided by that document, and none should be guessed at
implementation time:

- ~~Is `salary_contracts.housing_allowance` a normal settable contract
  field going forward, or intentionally payslip-only (`hr-legacy#14`)?~~
  **Resolved 2026-08-07 (D-029)**: a real, settable contract field —
  the already-shipped payroll-group behavior is confirmed correct.
- ~~Preserve the legacy fixed-30-day payroll divisor, or move to real calendar days?~~ **Resolved 2026-08-09 (D-031): fixed 30.** Not a decision in the end — reading `payroll_compute_employee_payslip` in full showed `PENALTY_CALENDAR_DAYS_PER_MONTH = 30` drives the day rate, the daily-wage conversion and penalties alike.

- Where does per-company fiscal-period configuration
  (`month_start_day`/`month_end_day`) live until the `company_settings`
  module is built? **Resolved 2026-08-07**: the typed
  `company_settings` module exists (V27,
  `docs/superpowers/specs/2026-08-07-company-settings-first-slice-design.md`)
  and `PayrollBatchService.create` computes batch periods with the
  ported `payroll_fiscal_period_bounds` algorithm; unset settings
  reproduce calendar months exactly.
- What should the real advance-deduction scheduling mechanism be at
  payroll finalize? The current implementation uses an explicit,
  documented v1 heuristic (oldest APPROVED advance first, FIFO) because
  `advances`' `deduction_mode`/`deduction_amount_per_month`/
  `deduction_payroll_year`/`deduction_payroll_month` columns (V12)
  remain unmapped and unused, per that module's own Javadoc parking the
  legacy `deduction_type` redundancy as a product decision. Revisit
  once that decision is made.

## Requests/Leave Migration

Surfaced by `docs/superpowers/specs/2026-08-07-requests-leave-balances-first-slice-design.md` —
neither is decided by that document:

- Legacy's `exception_type_resolve_for_company()` (a company-default
  fallback when a request type has `add_attendance_exception` but no
  `exception_type_id`) was not read this pass — the new approve
  conservatively skips the side effect in that case. Read the resolver
  and decide whether to port its fallback.
- The auto-created leave balance uses the constant 21.0-day fallback;
  legacy consults the `MONTHLY_LEAVE_ACCRUAL` company setting first.
  Revisit when the `company_settings` module exists (same family as
  the payroll fiscal-period question above). **Resolved 2026-08-07**:
  `RequestService` now reads `monthly_leave_accrual` via
  `CompanySettingsService.effective` with 21.0 as the unset fallback.

## Phase 1 Completion

Surfaced by `docs/migration/2026-08-23-phase1-completion-plan.md` §6 C9 and
§8.1 — the questions that gated Item 12's closure and the final exit gate.

- **How does `legacy_refresh_tokens` get created against the production legacy
  MariaDB, and who owns that step?** (**R-023**, ADR-0013 Open Questions,
  D-043 amendment 3.) Phase 1 adds exactly one table to the legacy database and
  nothing in the application creates it — Flyway owns no MariaDB location and
  `hibernate.hbm2ddl.auto` is `none`. Today it exists only where a test
  container applies `phase1_extensions.schema.sql` out of band. **Resolution
  criteria**: an approved provisioning mechanism, rehearsed against a restored
  copy, with the mechanism, its owner and its lock duration recorded in
  `docs/operations/release-cutover-and-rollback.md`. Note the failure mode is
  not a clean startup error — the parity login route never touches the table,
  so a missing table surfaces later as broken password-change, logout and
  employee-mode `reset_password.php` paths. **Not** OTP verify, which never
  touches the table, and **not** a company-mode reset, whose branch stops at
  `updateCompanyPasswordByPhone()` — both succeed with the table absent.
- **Is the PHP rollback target actually restorable?** (**R-025**.) G11's claim
  is "the database is unchanged *and PHP still runs*"; only the first half has
  been examined. Nothing establishes that the PHP artifact, its runtime
  configuration, or the traffic-routing path back to it is available and working
  at the moment a rollback is called, and the release/rollback procedure is
  still an unfilled template. **Resolution criteria**: an end-to-end rollback
  rehearsal on a non-production environment — route traffic back to PHP,
  confirm it serves, run the smoke checks — recorded in the same document.
  Until then Phase 1's risk acceptance rests on a rollback nobody has performed.
- **Do the two deployments share a JWT signing secret?** (**R-024**.) Phase 1's
  zero-client-change property depends on `app.jwt.secret` being byte-identical
  to PHP's `AppConfig::JWT_SECRET`, and nothing compares them. **Resolution
  criteria**: the bidirectional token exchange in the cutover document,
  including its foreign-signed negative control, run against a disposable smoke
  account rather than a real employee.

- ~~Are `attendance/overall_report.php`, `attendance/export.php` and
  `payslips/export.php` delivered, formally excluded under a numbered decision,
  or deferred to a later item?~~ **Resolved 2026-08-28 (O-8/D-120): all three
  are delivered.** None is excluded and none is deferred, so gate G2's live
  denominator stays at 198 and the exclusion ledger stays one row long
  (`time/now.php`, O-3). The governing rule is that Java reproduces what PHP
  does per endpoint — D-074's JSON envelope where PHP calls `ok()`, and where PHP
  terminates in a download helper the same reader-observable workbook, headers
  and filename rather than the same archive bytes (D-085) — which makes
  binary-response support a Phase-1 implementation obligation rather than a
  reason to defer.
- ~~Does the broad Wave-12.6 J.2 payroll boundary still block Wave 12.6.6?~~
  **Resolved 2026-08-27 by evidence, not decision**
  (`docs/migration/2026-08-27-broad-j2-settlement-discovery.md`): every payroll
  function §G.2 named is already in `main`, delivered by Waves 12.8/12.9 in
  their own waves. Both `attendance/overall_report.php` and
  `attendance/export.php` are unblocked on dependency grounds.
- Who performs the independent review the mandatory workflow places before human
  merge? **Resolved 2026-08-28 (D-121)**: `chatgpt-codex-connector[bot]`,
  reviewing the whole pull request. Its externally-billed quota (R-009) makes
  the gate *unavailable* when exhausted, never waived.

## Tooling

- Will `specify-cli` be installed during Phase 0 or deferred until human review approves it?
- Will GitHub MCP be enabled read-only during discovery or deferred entirely?

## Workforce Planning Cross-Tenant Disclosure (D-131 — blocks Wave 13.4b)

**RESOLVED 2026-08-30 (D-141): parity, on both surfaces, and Item 13 is not
held.** The text below is the question as it stood while open, kept unedited.

`workforce_planning`'s `save_target.php` and `update.php` accept unvalidated
foreign `branch_id`/`department_id`/`job_title_id`, and the three name joins in
`list.php` carry no tenant predicate. A `company_admin` or `hr` user in any
tenant can therefore write another company's id into their own planning row and
read that company's **name** back — a cross-tenant read in two ordinary API
calls. Filed upstream as `hr-legacy#33`; demonstrated by a regression that
performs the attack.

**The question is narrower than "should this merge".** The port is faithful and
D-058 already makes parity the default: fixing it in Java alone would make the
two systems answer differently for the same request and would mask the defect
rather than resolve it. What is owed is a decision on one point:

> Given that this particular defect crosses a **tenant boundary**, is parity
> still the right default — or should Item 13 wait for `hr-legacy#33` to land
> and port the fix instead?

**Holding Wave 13.4b alone no longer answers this.** The same unscoped join is
in `dashboard/stats.php`, delivered in **Wave 13.5 (PR #138)**, which sits below
13.4b in the stack — and that surface leaks the foreign department's active
headcount as well as its name. So the options are:

| Option | 13.4b's `list.php` leak | `stats.php` leak |
|---|---|---|
| Merge the stack | ships | ships |
| Hold 13.4b only | held | **ships** |
| Hold from 13.5 up | held | held — but that holds all of Item 13 |

The choice is therefore to accept the disclosure on **both** surfaces for
Phase 1, or to hold **Item 13 as a whole**. An option that holds only #141 would
purport to wait for the fix while still shipping the vulnerable route.

- **RESOLVED 2026-08-30 — parity, on both surfaces, Item 13 not held.** The
  owner's direction: *"i need java to be like php fot fix any issue"*, given
  with an explicit parity ruling on R-016. Recorded as **D-141**; D-131 moves
  from `PROPOSED` to `Accepted` and R-012 from open-undecided to
  owner-accepted. The question text below is kept as written, unedited, as the
  record of what was actually put to the owner.
- **Owner:** repository owner. Not an agent decision — AGENTS.md forbids an
  agent silently making one of this kind, and an earlier revision of D-131
  wrongly recorded it as accepted.
- **Resolution trigger:** either the owner records parity as still correct (D-131
  moves to Accepted, the stack merges, and the regressions keep asserting the
  leak on both surfaces), or the owner elects to wait — in which case the hold
  is from **Wave 13.5 upward**, not #141 alone, and the regressions are
  **inverted rather than deleted** in the same change.
- **Tracked in:** D-131, `docs/security/threat-model.md`'s tenant ↔ tenant row,
  and **R-012** in the risk register. R-012 records it as an *open, undecided*
  exposure — not an accepted residual — precisely so that a cutover or security
  review starting from the canonical register finds it. Registering a risk does
  not presume its disposition; an earlier revision of this bullet said the
  opposite and kept the register silent about a confirmed cross-tenant
  disclosure.

## Anonymous Complaints (`complaints/create.php`, D-132)

`complaints/create.php` is the only endpoint in the Phase-1 surface that is
**both unauthenticated and a write**. It stores a caller-supplied `name`,
`phone` and `message`, with `company_id` and `employee_id` left NULL when no
token is present. D-132 ports it as-is by owner decision; these questions are
what that decision deliberately left open.

- **Is an anonymous complaint meant to be readable at all?** `list.php` filters
  `company_id = ?`, so a NULL-company row can never be returned to any company,
  and there is no other read path in the API. Is it a deliberate "contact us"
  inbox read outside the API (dashboard or direct database), or a defect? Until
  this is answered it is **not** filed upstream, because the code does not
  contradict itself the way `hr-legacy#31`/`#32`/`#33` do.
- **What rate limiting should an anonymous public write have?** There is none
  today. The OTP endpoints have a cooldown and hourly caps
  (`otp_assert_can_send()`); this route has nothing comparable.
- **What is the retention policy for anonymous PII?** The row holds a name and a
  phone number supplied by an unauthenticated caller, with no owning tenant and
  therefore no tenant-scoped deletion path.
- **Is spam a live concern for it?** Related to both of the above; the answer
  decides whether the first two need addressing before or after cutover.

These are recorded here rather than answered because Phase 1's contract is
parity and none of them changes the port. They become live at the point someone
decides the legacy contract should change.

## Push Token Registration (`profile/register_push_token.php`, R-013)

Wave 13.2 ported this route and it **cannot succeed**. The endpoint inserts
into `push_tokens (employee_id, company_id, token, platform)` with
`ON DUPLICATE KEY UPDATE`, and the frozen table has neither a `company_id`
column nor any unique key. Every call is a database error, and the port
reproduces that (D-058) rather than repairing the statement.

Nothing depends on it today — push does not work end to end (F-08), mobile's
call is commented out, the ETL drops the table — so this is recorded, not
fixed. Three questions have to be answered before it can be:

1. **Has production drifted from the dump?** The dump is the frozen evidence
   and it says the column does not exist. If production has it, the dump is
   stale for this table and the ETL inventory needs re-checking too. Answering
   this needs one read-only `SHOW CREATE TABLE push_tokens` against production,
   which requires explicit per-task authorization and has not been requested.
2. **Is a company-owned push token intended?** The endpoint clearly means one:
   it selects the owner column by auth type and leaves the other NULL. But
   `sendPushToEmployee()` only ever looks tokens up by `employee_id`, so a
   company-owned row would be written and never read. Either the write is
   wrong or the read is incomplete — the code does not say which.
3. **What is the upsert key?** `ON DUPLICATE KEY UPDATE` needs a unique key
   that does not exist. `UNIQUE(token)` means one device belongs to one
   account and re-registering moves it; `UNIQUE(employee_id, token)` means one
   device may serve several accounts. Those are different products, and the
   `logout.php` delete (`token = ? AND employee_id = ?`) reads as though the
   second were intended.

These belong with `hr-platform#22`, which owns push delivery, rather than being
answered separately: fixing the table without the delivery half would leave the
same dead route with a different failure mode.

## Platform-Admin Web Surface Authentication (ADR-0014, Accepted)

Surfaced by `docs/adr/ADR-0014-platform-admin-web-authentication.md`, **accepted
2026-08-31 (D-146) over these items**, which were written as acceptance
blockers. They are now **implementation prerequisites**: the decision is
settled, and none of this may ship until they are answered. Two gate any code at
all — throttling, because the surface is currently weaker than the system it
replaces, and the step-up bounds, because an unbounded step-up flag is step-up
in name only. **The active-admin lookup was in that set and is no longer**: it
merged in PR #152 (**R-026** closed, **D-145** accepted) and runs per request
today, so it must not be re-planned as outstanding work.

Also outstanding: **engineering-lead feasibility sign-off**. The ADR names two
deciders and only the owner has approved.

- **Is the BFF boundary the agreed shape, and how is "the browser never receives
  a platform-admin token" *enforced* rather than documented?** The existing
  `/api/platform-admin/login` and `/refresh` hand both tokens to any caller, so
  the wrong wiring is the path of least resistance.
- **What server-side session store does the BFF use**, and what cookie domain
  topology does it imply? Not *whether* one: a stateless signed cookie carrying
  the token pair would send those tokens to the browser, which the whole design
  exists to prevent — signing gives integrity, not confidentiality — and a
  cookie holding only an opaque handle is server-side state by definition.
- **What are the concrete session bounds** — the idle timeout and the
  non-renewable absolute family cap? The 7-day refresh is *sliding* today, so a
  session used daily never expires. Declared mandatory without numbers, the ADR
  could be accepted with that unchanged.
- **What does the MFA-bearing login exchange look like?** `/login` takes phone
  plus password and returns the token pair immediately, so there is nowhere for
  a TOTP challenge to happen. Needs a challenge/response step, enrolment, and a
  representation for a step-up-satisfied session — **with all four binding
  rules, not a generic "step-up satisfied" flag**: a **maximum age** in minutes
  rather than the session lifetime; **single use**; bound to the **canonical
  operation**; and bound to the **resource identifier and a digest of the
  security-relevant request parameters**, recomputed server-side from the
  request about to execute rather than trusted from anything echoed back.
  The last of those is not a detail of the third. An approval bound to
  "suspend" but not to *which company* is consumable by a hijacked session
  against a different tenant — same operation, same session, different tenant,
  every other property satisfied and the wrong company suspended. Closing this
  prerequisite without it recreates exactly the defect the binding exists to
  prevent.
- **Is ADR-0005's "sessions can be listed and revoked individually" going to be
  delivered?** It is unimplemented on **both** surfaces: neither
  `PlatformAdminRefreshTokenRepository` nor `RefreshTokenRepository` has a list
  query, and no controller exposes listing or revoke-by-session. Revocation
  today requires holding the refresh token, or is all-or-nothing. This is an
  ADR-0005 shortfall rather than an ADR-0014 one, but a new admin surface with
  MFA and no session visibility is a conspicuous place to inherit it.
- **What throttling do the password and TOTP steps get, and where is it
  counted?** `PlatformAdminLoginService` has no attempt limit, backoff or
  lockout, while the legacy dashboard it replaces enforces 8 attempts /
  15 minutes. A six-digit second factor without throttling is a feasible online
  search, so this is not a follow-up to MFA. **The limit and window are not
  sufficient to close this**: it must be enforced in **shared state at the Java
  backend boundary**, where every attempt converges regardless of which BFF
  instance received it, and it must **survive a restart**. A process-local
  counter satisfies "8 attempts / 15 minutes" while giving each worker its own
  budget and resetting on restart — against a six-digit code that difference is
  the whole control. The acceptance test is **8 attempts submitted through
  separate workers are refused**, not 8 attempts.
- **How is a lost rotation response recovered?** If the BFF's `rotate()` call
  commits at the backend but the response is lost — an ordinary timeout, not an
  attack — the BFF still holds the superseded token. Presenting it is
  indistinguishable from replay, so reuse-detection revokes the whole family and
  logs the administrator out. Needs a **backend** change and cannot be closed by
  the BFF alone: an idempotency key on `rotate()`, a bounded grace window
  accepting the immediate predecessor once, or an endpoint returning a session's
  current successor. Listed because without it every prerequisite here can be
  answered while a network blip still ends a session.
- **Which TOTP implementation, what recovery design, and how are the seeds
  kept?** The population is bootstrap-provisioned with no self-registration, so
  lockout has no self-service escape today. **Seed custody is part of this
  prerequisite, not a follow-up**: verification requires the backend to hold
  each symmetric seed in *recoverable* form, so a plaintext column or an
  unprotected backup lets anyone who can read the database generate every
  administrator's second factor — MFA defeated by exactly the compromise it
  exists to survive, silently and for every account at once. Required before
  the surface ships: **application-level encryption with the key held outside
  the database**, access restricted to the verification path, **backups
  protected to the same standard** as the primary store, and a **defined
  re-encryption path** for key rotation. Marking this answered with an
  implementation and a recovery flow alone leaves the seeds unprotected.
- **Does the legacy PHP dashboard's `admin`-role surface run in parallel during
  Phase 2?** If so, do both surfaces authenticate independently?
- **What retention applies to `PlatformAdminAuditEvent`?** The audit trail
  exists; how long it must survive is not recorded anywhere.

Not open, recorded here because an earlier draft wrongly reopened it:
**platform-admin identity separation is settled** by D-027 and F-26 — individual
identities, structurally separated JWT sessions, `platform_admin_refresh_tokens`,
and audit attribution with a NOT NULL admin FK.

~~Tracked separately as a defect rather than a question: **R-026**, deactivation
not enforced per request.~~ **Closed.** R-026 was fixed and merged in PR #152;
`PlatformAdminAuthenticationFilter` loads the row and verifies `active` on every
request (**D-145**). Struck rather than deleted so the trail stays followable —
but it is **not outstanding work**, and this section said so above while this
tail still described it as an active defect.

## Session Revocation On Logout — Both Surfaces (R-027)

Surfaced 2026-08-31 by an independent security review of PR #152. This is a
**decision that has not been made**, not a defect awaiting a fix — which is why
it sits here rather than only in the risk register.

- **Should logging out invalidate the live access token, or only the refresh
  family?** Today it is the latter: the token carries a `sid` claim naming its
  session family (`PlatformAdminJwtService:55`) and
  `PlatformAdminAuthenticationFilter` never reads it, so
  `PlatformAdminSessionService.logout(String)` revokes the family while the
  current access token keeps authenticating until `exp` (≤900s).

Two defensible answers, and the choice belongs to whoever owns **ADR-0005**:

1. **Enforce session status per request.** Resolve `sid` in the filter and
   reject a token whose family is `REVOKED`.

   **The cost is not the same on both surfaces**, and the earlier version of
   this entry used a platform-admin fact to argue for both. R-026 added a
   repository lookup to `PlatformAdminAuthenticationFilter` only, so there the
   marginal cost really is small — a second indexed query beside one that is
   already paid. `JwtAuthenticationFilter` has **no repository dependency and
   no existing lookup**, so enforcing `sid` there introduces a database query
   per request to the busiest path in the system. That is a real decision, not
   a rounding error, and it may well be answered differently for each surface.
2. **Accept access-token survival** as the standard stateless-JWT trade, and
   record it, so the inconsistency with R-026's stated principle ("immediate
   revocation over cached authorization state") is a decision rather than an
   accident.

**Why it needs answering rather than deferring**: R-026 made *deactivation*
immediate. Logout is not. So the stronger control works and the weaker one does
not, which is the opposite of what someone reaching for either would assume —
and incident response reaches for logout first.

**This was first written as "not urgent today", scoped to platform admin, where
the only authenticated route is `GET /api/platform-admin/me`. That scoping was
wrong.** The tenant path has the identical defect — `JwtService:69` issues
`sid`, `JwtAuthenticationFilter` never reads it, `RefreshTokenService.logout()`
revokes the family only — and it has **58 mutating endpoints live today**,
including payslip create/update/delete, salary contracts and branch deletion. A
token revoked by logout can still perform all of them until `exp`.

So the decision is owed for the tenant surface now, not at the first destructive
platform-admin endpoint. Whichever way it goes, it should be answered once for
both filters rather than twice.

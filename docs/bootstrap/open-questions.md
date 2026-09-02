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
  D-043 amendment 3.) Phase 1 adds tables to the legacy database —
  `legacy_refresh_tokens`, and since D-156 (2026-09-02) the five
  attendance-device tables in the same file, needed only where the device
  receiver is enabled — and nothing in the application creates them — Flyway owns no MariaDB location and
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

## Platform-Admin Web Surface Authentication (ADR-0015, Accepted)

Surfaced by `docs/adr/ADR-0015-platform-admin-jte-authentication.md`, which
**supersedes ADR-0014**. The admin web is **JTE pages inside the existing
Spring application** — one deployment, server-side rendered, on the
application's existing authentication and session model, per the repository
owner's instruction of 2026-09-01.

**Most of the previous entries in this section are gone, not answered.** They
required a browser-facing application holding credentials: the BFF session
store and its custody (**R-033**, now closed as not applicable),
rotation-result custody, enforcing that the browser never receives a token, and
cookie domain topology. With the surface in-process there is nothing to hold,
so those questions have no subject.

These are **implementation prerequisites**: the design is settled and none of it
may ship until they are answered.

- ~~**What is the first-time TOTP enrolment ceremony?**~~ **ANSWERED
  2026-09-01 by [D-152]** — an **operator-assisted bootstrap flow**. A
  password-only authenticated session may not claim the first factor. A
  cryptographically random, short-lived, single-use enrolment token is
  generated server-side, bound to one specific `PlatformAdmin`, and delivered
  over a separately verified out-of-band channel; enrolment requires
  **password *and* token**; the token is invalidated immediately on success;
  normal access follows a successful **TOTP verification**, not merely
  enrolment. The raw seed never leaves the protected credential store. The
  token is hashed if persisted, short-lived, single-use, and its issuance, use
  and revocation are audited. Existing rows migrate **unbound** and cannot
  perform destructive operations until bound. Recovery must still not become a
  weaker second enrolment path.
- ~~**If the legacy PHP dashboard runs in parallel, is it MFA-gated,
  restricted, or down?**~~ **ANSWERED 2026-09-01 by [D-152]** — it is
  **disabled at cutover**. It must not remain reachable as an alternative
  authentication path once the JTE admin is live; if retained for rollback it
  stays deployed or staged but **network-inaccessible by default**, exposed
  only as part of an explicit rollback procedure. It authenticates with the
  shared admin password (`hr-legacy#11`) and has no second factor, so while
  reachable it makes every control here walk-around-able.
- **Which TOTP implementation, what recovery design, and how are the seeds
  kept?** Unchanged by the architecture correction, because it is a recoverable
  secret at rest either way: **application-level encryption with the key held
  outside the database**, access restricted to the verification path, backups
  protected to the same standard, and a defined re-encryption path for
  rotation. An implementation and a recovery flow alone leave the seeds
  unprotected.
- **What does the step-up-satisfied representation look like**, with all four
  bounds — maximum age in minutes, single use, bound to the canonical
  operation, **and bound to the resource identifier plus a server-recomputed
  digest of the security-relevant request parameters**? The last is not a
  detail of the third: an approval bound to "suspend" but not to *which
  company* is consumable by a hijacked session against a different tenant.
- **What throttling do the password and TOTP steps get, and where is it
  counted?** In **shared, restart-surviving state**; a process-local counter
  meets "8 attempts / 15 minutes" while giving each worker its own budget. The
  acceptance test is attempts through separate workers, not a count.
- **What are the concrete session bounds** — idle timeout and a non-renewable
  absolute cap, as numbers — and is the session id rotated on login to prevent
  fixation? **The cap must bound the API tokens too**, not only the UI session:
  `rotate()` issues every successor at `now + 7 days` without consulting the
  family origin, so a family slides forever and an access token minted near the
  cap outlives it. Java must persist or derive the origin, refuse rotation past
  the cap, and clamp an issued access token to the family's remaining life,
  proven by a test that advances a family beyond it.
- **CSRF**: which state-changing JTE routes carry a synchroniser token, and
  where exactly is the filter-chain boundary between the cookie-authenticated
  UI and the bearer-authenticated API? This is **new** with the in-process
  model — a cookie-authenticated server-rendered surface is exposed in a way a
  bearer API is not, and the two models now live in one application.
- **Session cookie flags** — `HttpOnly`, `Secure`, `SameSite` — chosen and
  pinned by a test rather than left to defaults.
- **Is there a global session-revocation operation?** `revokeAllForPlatformAdmin(Long)`
  is per-administrator despite its name, and unwired. "Revoke every admin
  session" is an ad-hoc procedure today.
- **Does the legacy PHP dashboard's `admin`-role surface run in parallel during
  Phase 2?** If so, do both surfaces authenticate independently?
- **Is ADR-0005's "sessions can be listed and revoked individually" delivered
  for this surface**, or explicitly deferred? Unimplemented on both surfaces
  today, so this ADR does not create the gap.
- **What retention applies to `PlatformAdminAuditEvent`?**

**Identity separation is deliberately not on this list.** **D-027** made
individual platform-admin identity a P0 requirement and **F-26** records it as
substantially closed.

**Deactivation is not on this list either.** **R-026** is closed and **D-145**
accepted: the active-admin lookup runs per request, and the JTE controllers
inherit it because it lives in the request path rather than in a handler.

## Session Revocation On Logout — Both Surfaces (R-027) — ANSWERED

> **Answered 2026-08-31: option 1, on both surfaces — [D-149](decision-log-wave12r.md). Logout now invalidates the live access token.**
> The question and its reasoning are kept below as the record of what was
> weighed, including the cost objection that turned out to be the real
> substance of the decision. **R-027 is closed.**

Surfaced 2026-08-31 by an independent security review of PR #152. This was a
**decision that had not been made**, not a defect awaiting a fix — which is why
it sat here rather than only in the risk register.

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

So the decision was owed for the tenant surface immediately, not at the first
destructive platform-admin endpoint. It was answered once for both filters
rather than twice.

**Resolution.** Option 1. The cost objection above is correct and was accepted
rather than argued away: `JwtAuthenticationFilter` previously had no repository
dependency, so this does add one indexed query per request to the busiest path
in the system. It was accepted because 58 live mutating endpoints sat behind a
control that did not do what its name says, and because it is the same trade
ADR-0010 already makes for authorization. If that query later shows up in
latency measurements, the answer is to measure and revisit it as its own
decision — not to quietly restore a logout that does not log the caller out.

## Attendance Device Ingestion (ADR-0006 Part B, D-156)

Raised 2026-09-02 by `docs/superpowers/specs/2026-09-02-attendance-device-ingestion-design.md`
§12. Q0, Q1 and Q6 were answered on 2026-09-02 (D-156); Q2, Q3, Q5, Q7 and
Q8 were answered the same day (**D-157**). What remains is the work those
answers oblige, tracked in D-157's Follow-up and in R-041 — not a question.

- **Q0 — Accept D-156? — ANSWERED 2026-09-02: accepted.** ADMS push as the
  primary ZKTeco adapter, the edge gateway as fallback, conditional on the
  §4.3 hardware checklist passing on the customers' actual models.
- **Q1 — Device PIN identity.** Reuse `employees.employee_code` as the
  device PIN, or add `employee_device_identities` with `UNIQUE (company_id,
  pin)` seeded from it. **ANSWERED 2026-09-02: the table.**
- **Q2 — Device punches and the two-hour rule. — ANSWERED 2026-09-02
  (D-157): never reject.** The punch is always persisted; a short
  duplicate/debounce window suppresses a double-read, and a rapid
  re-check-in is flagged for review. Lands with Slice B.
- **Q3 — Biometric templates. — ANSWERED 2026-09-02 (D-157): none in
  Phase 1.** Attendance events and metadata only. Already enforced in Slice
  A: `TransFlag` does not request them and the receiver discards any that
  arrive regardless.
- **Q5 — `attendance.method` expansion. — ANSWERED 2026-09-02 (D-157):
  expand-only, with Slice B, and the audit it was conditional on is done.**
  Every frozen-PHP site writes the column; exactly one reads it, rendering it
  verbatim, so no branch depends on the value set. Two residual checks belong
  to Slice B: the dashboard will show the literal word `device` until it is
  given a label, and the Flutter clients could not be inspected (PMR-02) and
  must be verified if either renders `method`.
- **Q6 — Who claims devices in the pilot. — ANSWERED 2026-09-02: tenant
  HR/admin through the new `/api/v1/devices/**` API**, authenticated with the
  legacy JWT; platform staff use a company admin's session for the pilot until
  the JTE admin surface (ADR-0015) renders it.
- **Q8 — Proof of possession when claiming a device — ANSWERED 2026-09-02
  (D-157), and the work is outstanding** (**R-041**). Pilot: supervised
  tenant `company_admin`/`hr` claiming is acceptable. Production: tenant
  admins may **not** claim by serial number — platform staff pre-allocate
  device ownership to a company, and tenant HR then assigns an owned device
  to a branch. An **audited unclaim / transfer / replace-device path is
  required before broad production rollout**; manual database correction is
  not acceptable long-term. Neither is built yet, and R-041 stays open until
  both are.
- **Q7 — Production provisioning of Phase-1-owned MariaDB tables. —
  ANSWERED 2026-09-02 (D-157): it must be explicitly solved before device
  ingestion is enabled in production**, not discovered at cutover. The
  ADR-0013 open question itself (**R-023**) stays open; this makes it a
  precondition of turning `app.devices.ingest.enabled` on.

# Business Rule Extraction

Source for every entry below: `workin-hr/hr-legacy` commit
`83c326e40f68dd0d560595a6c4e465eb681f2ce8`, read directly — not inferred
from naming or assumed from typical HR-system behavior. Each entry states
where in the code the rule actually lives so it can be re-checked.

---

## Rule: Minimum 2-hour gap between consecutive check-ins

**Current Behavior:** An employee cannot check in again within 120 minutes
of their own previous check-in (regardless of whether that previous session
was checked out). The check is `minutes_since_last >= 0 && < 120`; if
triggered, the request fails with a fixed Arabic message: "لا يمكن تسجيل
بصمتين متتاليتين خلال اقل من ساعتين" ("Cannot record two consecutive
check-ins within less than two hours").

**Where Observed:** `apis/api/attendance/check_in.php`, lines 33–42.

**Risk If Misinterpreted:** If a migration silently drops or changes this
window, employees could double-check-in in quick succession (inflating
attendance/payroll) or legitimate re-check-ins after a short break could be
wrongly blocked, depending on which direction the change goes.

---

## Rule: Attendance geofencing is per-branch, with two employee-level modes

**Current Behavior:** Distance is computed with the Haversine formula
(`calculate_haversine_distance()`), compared against a per-branch
`radius_meters` (default 200m if unset). Two modes, selected by the
employee's `can_check_in_any_branch` flag:

- **Off (default):** GPS must be within the radius of the employee's own
  assigned branch only. If that branch has no GPS configured, check-in is
  rejected outright (`BRANCH_LOCATION_NOT_CONFIGURED`) for self-service
  check-in, but not for HR-initiated check-in on someone else's behalf
  (`require_location_configured = false` skips the requirement).
- **On:** GPS must be within the radius of *any* active branch belonging
  to the same company — not just the assigned one.

**Where Observed:** `apis/helpers/attendance_location_helper.php`,
`validate_employee_attendance_location()`; called from
`apis/api/attendance/check_in.php`.

**Risk If Misinterpreted:** This is the actual anti-fraud control for
attendance (preventing check-in from home). Getting the two-mode branching
wrong — especially the HR-initiated exception — would either block
legitimate HR corrections or silently disable geofencing for a class of
employees.

---

## Rule: A phone number matching more than one "ready" employee account blocks login

**Current Behavior:** Employee login looks up **all** rows matching the
given phone (not `LIMIT 1`), verifies the password against each, then
filters to accounts that are simultaneously `join_request_status =
accepted`, `is_active = 1`, and belonging to an active company. If more
than one account satisfies all three, login is rejected with
`MULTIPLE_ACCOUNTS_SAME_PHONE` rather than picking one. A single pending
(not-yet-accepted) match is allowed to log in with a
still-pending-context response; more than one pending match is also
rejected the same way.

**Where Observed:** `apis/api/auth/login_employee.php`, full file — the
filtering logic is not a single check, it's a deliberate multi-branch
decision tree (lines 54–108).

**Risk If Misinterpreted:** A naive migration ("phone is basically unique,
just do `WHERE phone = ? LIMIT 1`") would silently pick an arbitrary
account for anyone with legitimate multi-company employment under the same
phone number, rather than surfacing the ambiguity to the user.

---

## Rule: OTP is single-active-per-phone, WhatsApp-only, 4 digits, 10-minute expiry

**Current Behavior:** Issuing a new OTP for a phone first deletes all
existing OTP rows for that phone (`otp_clear_for_phone`), so only one code
is ever valid at a time — a newly issued code invalidates any
previously-issued one, even if the old one hadn't expired yet. Codes are 4
digits (`otp_generate_code(4)`), default 10-minute expiry, and delivered
exclusively via WhatsApp — SMS is not wired up (see the module inventory).
A resend is throttled at 60 seconds via `otp_has_recent_for_phone()`, but
that throttle is available as a helper, not necessarily enforced at every
call site (needs per-endpoint confirmation, not assumed from the helper's
existence alone).

**Where Observed:** `apis/helpers/otp_helper.php`, full file.

**Risk If Misinterpreted:** Assuming SMS delivery exists (it doesn't — see
module inventory) or assuming multiple concurrent valid codes are allowed
(they aren't) would both break registration/login flows during migration
testing in ways that might not surface until a real device tries to
authenticate.

---

## Rule: Payroll day-rate uses a fixed 30-day month, never the real calendar day count

**Current Behavior:** `PENALTY_CALENDAR_DAYS_PER_MONTH = 30` is used as the
divisor for both penalty-day-rate and payroll gross-to-daily-rate
conversion (`day_rate = gross_salary / 30`), in every month regardless of
whether it actually has 28, 29, 30, or 31 days.

**Where Observed:** `apis/helpers/penalties_amount_helper.php` line 8
(the constant); consumed in `apis/helpers/payroll_calculation.php`
(`payroll_compute_employee_payslip()`, `payroll_enrich_payslip_row()`).

**Risk If Misinterpreted:** This is the single highest-risk rule in the
codebase to get subtly wrong. Switching to "actual days in month" during
migration — which would look like a *correctness improvement* to someone
unaware of the existing convention — would change every employee's
effective daily rate and therefore every absence deduction and payslip
total, without any error or exception to catch it. Needs an explicit
human decision (preserve the fixed-30 convention, or deliberately change
it and communicate that as a real payroll policy change), not a silent
migration artifact.

---

## Rule: Payroll fiscal period is configurable per company, not necessarily the calendar month

**Current Behavior:** Each company selects a `month_start_day` and
`month_end_day` via the generic settings system. If `start_day <=
end_day`, the period is `[year-month-start_day, year-month-end_day]`. If
`start_day > end_day` (e.g. start=26, end=25), the period spans **across**
a month boundary: from the 26th of the *previous* month to the 25th of the
target month. Day values are clamped to the actual last day of whichever
month they fall in (`min(day, last_day_of_month)`).

**Where Observed:** `apis/helpers/payroll_calculation.php`,
`payroll_fiscal_period_bounds()`.

**Risk If Misinterpreted:** Assuming payroll periods are always
calendar-month-aligned would misattribute attendance/absence days at
period boundaries for any company using a non-calendar fiscal cycle —
silently wrong payslips, not a crash.

---

## Rule: Recalculating a payroll batch is a full destructive replace, not an incremental update

**Current Behavior:** `payroll_calculate_batch()` deletes every existing
payslip row for the batch (`DELETE FROM payslips WHERE batch_id=?`) before
recomputing and reinserting one row per active employee with a salary
contract. Any manual edit made directly to a payslip row between
calculations is lost the next time the batch is recalculated. Recalculating
a `finalized` batch is blocked outright
(`throw new InvalidArgumentException('batch_finalized')`); finalizing has
side effects (marks matching penalties `applied_to_payroll = 1`, applies
advance deductions to `advances.remaining`), and reopening a finalized
batch reverses both.

**Where Observed:** `apis/helpers/payroll_calculation.php`,
`payroll_calculate_batch()`, `payroll_finalize_batch_side_effects()`,
`payroll_reopen_batch_side_effects()`.

**Risk If Misinterpreted:** A migration that treats "recalculate" as safe
to run idempotently/incrementally would be wrong — it's a destructive
full-replace operation with real financial side effects on other tables
(advances, penalties) at finalize/reopen time, not just on the batch
itself.

---

## Rule: Server timezone offset switches at runtime based on a `configs` table flag, not the server clock

**Current Behavior:** Every database connection explicitly issues
`SET time_zone = ?` immediately after connecting, using `+03:00` if a
`configs` row with key `is_daylight_saving` has a truthy-looking value
(`1`, `true`, `yes`, `summer`, `dst` — case-insensitive), and `+02:00`
otherwise (also the fallback if the lookup fails for any reason). This is
manually toggled application state, not derived from any real timezone
database or the host OS's own DST rules.

**Where Observed:** `apis/config/pdo.php`, `getDB()`, lines 23–39.

**Risk If Misinterpreted:** All `NOW()`/`current_timestamp()` values
throughout the schema (check-in times, created_at columns, etc.) are
relative to whatever this flag currently says, not a fixed UTC or
IANA-timezone offset. A migration that assumes a fixed offset, or that
derives DST automatically from a real timezone rule instead of this
manual flag, will shift every timestamp by an hour whenever the two
disagree about whether DST is currently "on."

---

## Rule: Payroll batch creation has no database-level uniqueness — only an app-level check

**Current Behavior:** `apis/api/payroll_batches/create.php` prevents a
duplicate `(company_id, month, year)` batch with a `SELECT COUNT(*)`
check immediately before the `INSERT`. The `payroll_batches` table itself
has no unique constraint on that triple (confirmed against
`mysql_workin.schema.sql`). Two concurrent create requests for the same
company/month/year can both pass the check before either commits,
producing two `draft` batches for the same period.

**Where Observed:** `apis/api/payroll_batches/create.php`, the
existence-check block immediately before the `INSERT`; absence of a
matching unique key confirmed in
`docs/migration/database-schema-inventory.md`.

**Risk If Misinterpreted:** A migration that reproduces this endpoint's
logic exactly (app-check-then-insert, no DB constraint) preserves an
existing race condition rather than fixing it silently — worth an
explicit decision (add the constraint, or accept the risk as-is and
document why) rather than assuming the current behavior is intentional
design.

---

## Rule: Batch recalculation is not transactional; finalize and reopen are

**Current Behavior:** `payroll_batches/calculate.php` runs
`payroll_calculate_batch()` — the delete-all-payslips-then-reinsert
operation described above — without wrapping it in a database
transaction. `finalize.php` and `reopen.php`, which each have real
cross-table side effects (penalties, `advances.remaining`), both wrap
their work in `beginTransaction()`/`commit()`/`rollBack()`. Calculation,
which is destructive to the same `payslips` rows, has no equivalent
safety net: a failure partway through a recalculation leaves the batch
with a partial payslip set and no automatic rollback.

**Where Observed:** `apis/api/payroll_batches/calculate.php` (no
transaction calls) versus `apis/api/payroll_batches/finalize.php` and
`reopen.php` (both transactional) and
`apis/helpers/payroll_calculation.php`'s `payroll_calculate_batch()`.

**Risk If Misinterpreted:** Assuming all three batch-mutating operations
share the same failure-safety guarantees (because they read as
"equivalent lifecycle steps") would be wrong — only two of the three
actually roll back cleanly on partial failure.

---

## Rule: Payslip totals are computed by three independent implementations, not one shared function

**Current Behavior:** The canonical payroll math (day-rate, absence
cost, overtime pay, entitlements/deductions totals) lives once, as a
shared SQL fragment in `apis/helpers/payroll_calculation.php`
(`sql_payslip_select_with_computed_totals()` and related functions), used
by `payslips/list.php`, `payslips/one.php`, `payroll_batches/stats.php`,
and `payroll_batches/one.php`. Two other endpoints reimplement the same
domain of calculation independently in PHP rather than calling that
shared logic:

- `payslips/update.php` re-derives the full formula (day-rate,
  absence-cost, overtime, net) inline, matching the shared formula's
  intent but maintained as a separate copy.
- `payslips/create.php` uses a **materially simpler and different**
  formula — `net_salary = (basic_salary + allowances + overtime_pay) -
  (penalties_total + advance_deduction + other_deductions)` — with no
  day-rate or absence-cost derivation at all; `penalties_total` is taken
  directly from the request body instead of being computed from
  `days_absent`.

**Where Observed:** `apis/helpers/payroll_calculation.php` (shared
version); `apis/api/payslips/update.php` and `apis/api/payslips/create.php`
(the two independent reimplementations).

**Risk If Misinterpreted:** A migration that assumes "payroll calculation"
is a single business rule to port once would miss that this legacy system
already has three different code paths that can produce different totals
for what looks like the same operation (adding a payslip to a batch vs.
letting the batch calculate it vs. editing it after the fact). Each path
needs to be ported and tested individually, or the discrepancy needs to
be resolved as a deliberate product decision before migration, not
discovered afterward as a bug.

---

## Rule: The `payslips.allowances` column holds only the housing allowance, despite its generic name

**Current Behavior:** Although `allowances` reads as a general bucket,
the actual value stored there is the employee's housing allowance
specifically — confirmed by `payslips/update.php`'s override logic, which
reconciles this column directly against the salary contract's
`housing_allowance` field (a different, separately-named source column).
Transport, food, risk, and incentive allowances are tracked in their own
separate `payslips` columns.

**Where Observed:** `apis/api/payslips/update.php`, the
housing/allowances reconciliation block.

**Risk If Misinterpreted:** Naming the migrated column `allowances`
without checking what it actually stores would either mislabel housing
pay as generic allowances or cause a naive "sum all allowance-like
columns" migration script to double-count housing under two different
names.

---

## Rule: Salary contracts have a `daily`-wage mode that one payslip endpoint ignores entirely

**Current Behavior:** `salary_contracts` supports two modes,
`monthly` (default) and `daily`. In `daily` mode, `basic_salary` and all
four contract allowances (transport/food/risk/incentives) are forced to
`0` on write, and `daily_wage` is used instead; the batch payroll engine
(`payroll_compute_employee_payslip()`) correctly converts this to a
synthetic monthly-equivalent (`daily_wage × 30`, using the same
30-day-divisor constant) before computing gross salary, and that
converted value is what gets stored as the payslip's `basic_salary`.
`payslips/update.php` inherits that already-correct stored value when no
override is supplied. **`payslips/create.php` (manually adding a payslip
to a batch, bypassing `calculate.php` entirely) does not** — it reads
`$contract[Column::BASIC_SALARY]` directly, which is `0` for any
daily-wage employee, and has no `daily_wage`/`salary_mode` handling and
no request-body override for `basic_salary` at all. For a daily-wage
employee, a payslip added through this specific endpoint silently omits
their entire base pay from `net_salary`, using only whatever
`overtime_pay` was typed into the request.

**Where Observed:** `apis/api/salary_contracts/create.php` (mode-zeroing
on write) and `apis/helpers/payroll_calculation.php`,
`payroll_compute_employee_payslip()` lines ~934–961 (correct daily→monthly
conversion, confirmed stored into `payslips.basic_salary` at the
`INSERT ... ON DUPLICATE KEY UPDATE` around line 1123) versus
`apis/api/payslips/create.php` line 95 (no such conversion, no override
path).

**Risk If Misinterpreted:** This is not a drift risk like the three-formula
finding above — it is a confirmed, reproducible calculation defect for one
specific, real compensation mode on one specific, real endpoint. Any
migration that ports `payslips/create.php`'s logic as-is would carry the
same defect forward; any migration that "fixes" it silently would change
the guarantees whoever currently relies on the batch-only flow already
depends on. Needs a human decision (avoid using this endpoint for
daily-wage employees today; and/or fix it deliberately during migration
with the fix communicated as a bug fix, not a silent behavior change).

---

## Rule: `salary_contracts.housing_allowance` cannot be set to a nonzero value anywhere in the API

**Current Behavior:** Refines the `payslips.allowances`-is-housing entry
above. `housing_allowance` is a real column, wired into the payroll gross
calculation (`payroll_compute_employee_payslip()` reads
`$contract[Column::HOUSING_ALLOWANCE]`), but **every** write path in the
codebase hardcodes it to the literal `0`: `salary_contracts/create.php`
and `salary_contracts/update.php` both write a literal `0` for this
column rather than reading it from the request body at all (not even
defaulted-then-overridable — the field is not read from `$body` in
either endpoint), and the alternate employee-creation path
(`apis/helpers/employee_create_helper.php`, used by
`employees/create.php`) does the same. Consequently every
batch-calculated payslip's housing component is always `0` at
calculate-time. The **only** place a nonzero housing value can ever enter
the system is a human manually typing one into `payslips/update.php`'s
per-payslip `allowances` override, after the batch has already run — and
because that value lives on the payslip row, not the contract, it is not
remembered for the next payroll cycle; HR would need to re-enter it every
single month for every employee who receives housing.

**Where Observed:** `apis/api/salary_contracts/create.php` line 47/58
(literal `0` in the `INSERT`), `apis/api/salary_contracts/update.php`
line 55 (literal `0` in the `UPDATE`), `apis/helpers/employee_create_helper.php`
lines ~170–197 (literal `0` in the `INSERT`), contrasted with
`apis/helpers/payroll_calculation.php` line 942 (the column is read and
used) and `apis/api/payslips/update.php` lines 55–61 (the only functioning
write path, scoped to a single payslip).

**Risk If Misinterpreted:** A migration that assumes `housing_allowance`
is a normal, settable contract field (because it exists in the schema and
the payroll formula) would be wrong — as of this commit it is
functionally a dead field at the contract level. Whether this is an
intentional design (housing is meant to be a manual monthly exception,
not a standing benefit) or an incomplete feature (a UI to set it on the
contract was planned but never wired to the API) is not determinable from
the code alone — worth a direct question to whoever owns the product
before deciding how to migrate this field.

---

## Rule: QR check-in does not enforce the 2-hour minimum-gap rule that GPS check-in does

**Current Behavior:** `attendance/check_in.php` (self, GPS-based) and the
manual `attendance/create.php`/`update.php` (HR-entered) all run the same
`TIMESTAMPDIFF(MINUTE, last_check_in, new_check_in) < 120` guard before
allowing a new check-in. `attendance/check_in_qr.php` does not — it only
checks that there is no currently-open (un-checked-out) session; an
employee can check out and immediately check back in via QR scan with no
minimum gap enforced at all.

**Where Observed:** `apis/api/attendance/check_in_qr.php` (no
`TIMESTAMPDIFF` guard present) versus `apis/api/attendance/check_in.php`
lines 33–42 (guard present) and `apis/api/attendance/create.php` lines
67–73 (same guard, reused for manual entry).

**Risk If Misinterpreted:** The 2-hour rule is documented above as the
system's actual anti-double-check-in control. Assuming it applies
uniformly "to check-in" as a concept, rather than to specific check-in
*methods*, would be wrong — QR-based check-in is a real, live bypass of
that control today. Worth confirming with whoever owns the QR feature
whether this is intentional (QR itself is treated as sufficient proof,
unlike GPS) or an oversight.

---

## Rule: The Manager role gets full company-wide attendance visibility, not branch/department-scoped, despite doc-comments implying otherwise

**Current Behavior:** `attendance/list.php`'s own doc-comment says
"Manager (for their company/department)", and `overall_report.php`
explicitly authorizes the `MANAGER` role — but neither endpoint (nor
`stats.php`) actually restricts a manager's query to their own
branch/department. Both `list.php` and `stats.php` branch only on
`EMPLOYEE` vs. "everyone else" (Admin/HR/Manager all get the same
unrestricted company-wide `WHERE e.company_id=?` query); `Manager` never
reaches a scoping branch. Contrast directly with the `penalties` module
(`docs/api/existing-endpoint-inventory.md`), which implements real
manager branch-scoping via `sql_manager_same_branch_scope()` in
`list.php`, `one.php`, `report.php`, and `stats.php` — proving the
pattern is known elsewhere in the codebase and simply not applied here.

**Where Observed:** `apis/api/attendance/list.php`,
`apis/api/attendance/stats.php`, `apis/api/attendance/overall_report.php`
— all read in full for role-branching logic; `apis/api/penalties/*.php`
as the contrasting correct pattern.

**Risk If Misinterpreted:** A migration that reads the doc-comment
("for their company/department") as the actual specification would
under-scope managers relative to what they currently see (a
functionality regression); a migration that reproduces the code exactly
preserves managers seeing every employee's attendance company-wide,
which may or may not be the intended access model — needs a product
decision, not an assumption either way.

---

## Rule: Bulk attendance deletion is a single irreversible, whole-company, date-range operation

**Current Behavior:** `attendance/delete_range.php` deletes every
attendance row for the entire company within an admin-supplied
`from`/`to` date range in one `DELETE ... JOIN` statement — no per-row
confirmation, no soft-delete, no dry-run requirement (the count is
returned only after deletion, not before), no audit-log entry visible in
this codebase. Separately, `attendance/update.php` also performs a
"soft" version of this at the single-row level: clearing both punches
without setting an exception type deletes the row entirely (a deliberate
convention — "so the day shows as missing/deducted" per the endpoint's
own comment — not a bug), rather than leaving an empty row.

**Where Observed:** `apis/api/attendance/delete_range.php`, full file;
`apis/api/attendance/update.php` lines 64–74.

**Risk If Misinterpreted:** Attendance directly drives payroll absence
calculations (see the day-rate/absence-cost rule above). A migration
that preserves `delete_range.php`'s blast radius without adding a
confirmation/dry-run/audit step would carry forward a tool that can
silently erase a month of payroll-relevant history company-wide in one
call — worth flagging as a candidate for a deliberate safety
improvement during migration, not just a like-for-like port.

---

## Rule: OTP verification has no attempt/rate limiting; only the resend has a cooldown

**Current Behavior:** `otp_verify_latest_for_phone()` — the function
behind `verify_otp.php`, `reset_password.php`, and the OTP-gated login
branches in `login_company.php`/`login_desktop.php` — is a plain
`SELECT ... WHERE phone=? AND code=? AND expires_at > NOW()` with no
attempt counter, no lockout, and no delay between attempts. The only
throttle anywhere in the OTP system is on *issuing* a new code
(`otp_has_recent_for_phone()`, 60-second cooldown on resend). A 4-digit
code (10,000 possibilities) valid for 10 minutes, combined with an
unthrottled verification endpoint, is brute-forceable within its validity
window by an attacker who can send a few thousand requests — no special
access required beyond knowing (or guessing) a target phone number.

**Where Observed:** `apis/helpers/otp_helper.php`,
`otp_verify_latest_for_phone()` (no rate limiting) and
`otp_has_recent_for_phone()` (the only throttle, and it only gates
issuance); consumed by `apis/api/auth/verify_otp.php` and
`apis/api/auth/reset_password.php` directly.

**Risk If Misinterpreted:** Independent of the `DEBUG`-gated OTP
disclosure in `docs/security/threat-model.md`, this is a second, standing
path to the same outcome (account takeover via OTP) that does not depend
on `DEBUG` being true at all — it works against the system exactly as
designed today, just more slowly. Whether an infrastructure-level
WAF/rate-limiter sits in front of the live API was not confirmed in this
pass; this finding assumes only what the application code itself
enforces.

---

## Rule: Changing or resetting a password never invalidates already-issued session tokens

**Current Behavior:** Only a fresh login
(`employee_issue_session_token()`, which bumps `employees.token_version`)
invalidates a previously-issued employee JWT. `reset_password.php` updates
`password_hash` and clears the OTP but never touches `token_version`.
No `profile/*` endpoint (self-service change-password) touches it either.
Company-admin tokens have no equivalent revocation mechanism at all — see
`docs/security/threat-model.md` for the full severity write-up, which
also covers the 10-year JWT expiry this compounds.

**Where Observed:** `apis/api/auth/reset_password.php`, full file (no
`token_version` write); `apis/helpers/functions.php`,
`employee_issue_session_token()` (the only place `token_version` is
incremented).

**Risk If Misinterpreted:** The product-facing assumption "I changed my
password because I think someone else has access, so I'm safe now" does
not hold in the current system — a session token issued before the
change remains valid until it naturally expires (up to 10 years) or the
legitimate user happens to log in again. Worth a direct product decision
on whether this is acceptable to carry into a migrated system as-is.

---

## Rule: Two parallel, non-identical employee self-registration endpoints exist

**Current Behavior:** `auth/register_employee.php` and `auth/join_company.php`
both let a new employee self-register against a company, but they are
not the same flow reproduced twice — they use **different identifiers for
"which company"** despite both binding it to a request field named
`company_code`: `register_employee.php` looks up the company by matching
`company_code` directly against `companies.phone` (the company's own
login phone number); `join_company.php` matches it against
`companies.company_code` (a distinct alphanumeric column, via
`company_find_by_public_code()`). They also differ materially beyond
that: `join_company.php` checks phone uniqueness **globally across all
companies** (`employee_phone_exists_globally()`,
`company_phone_exists_globally()`, with an explicit carve-out for the
company-owner's own phone) and resolves a default branch before
inserting; `register_employee.php` checks uniqueness only **within the
target company** and does not touch branches at all. `join_company.php`
also immediately issues a session JWT (auto-login while
`join_request_status='pending'`); `register_employee.php` does not issue
a token at all.

**Where Observed:** `apis/api/auth/register_employee.php`, full file,
versus `apis/api/auth/join_company.php`, full file, and
`apis/helpers/company_code_helper.php` (`company_find_by_public_code()`).

**Risk If Misinterpreted:** A migration that treats "employee
self-registration" as one business rule to port would produce a system
with either weaker (per-company only) or stronger (global) phone-
uniqueness than whichever of these two endpoints the real mobile client
doesn't actually use — and if the client uses both (e.g. one per app
version, or one deprecated but still reachable), the current system
already has inconsistent duplicate-phone enforcement depending on which
endpoint a given registration went through. Needs confirmation from
whoever owns the mobile client about which of these two is actually live
before assuming either is the "real" one to migrate.

---

## Rule: Employee deletion can cascade-delete payroll/financial history, despite the schema's own RESTRICT constraint

**Current Behavior:** `docs/migration/database-schema-inventory.md`
flagged `payslips.employee_id → employees.id` as one of three FKs with no
explicit `ON DELETE` clause (MySQL default `RESTRICT`), and asked whether
that was intentional protection for payroll history. It is not: the
application layer explicitly works around it.
`employees/delete.php?cascade=1` calls `employee_cascade_delete_related()`,
which — inside a single transaction, before the `employees` row itself is
deleted — explicitly deletes that employee's rows from `payslips`,
`penalties`, `advances`, `salary_contracts`, `attendance`, `leave_balance`,
`requests`, `employee_docs`, `complaints`, `notifications`, `push_tokens`,
`employee_schedules`, `employee_shift_assignments`, and `hr_permissions`,
specifically so the subsequent `DELETE FROM employees` doesn't hit the
FK constraint. A caller only sees a dry-run preview (record counts) first
if they omit `cascade=1`; the actual delete itself is a single API call
with no additional confirmation step beyond that.

**Where Observed:** `apis/api/employees/delete.php`, full file;
`apis/helpers/employee_delete_helper.php`,
`employee_related_records_summary()` and
`employee_cascade_delete_related()`.

**Risk If Misinterpreted:** The schema-level RESTRICT constraint reads
like a deliberate safeguard protecting financial/payroll history from
accidental loss. It is not one in practice — this endpoint is a real,
reachable way to permanently erase an employee's entire payroll and
attendance history in one call. A migration that preserves the DB-level
RESTRICT but drops this cascade-delete helper would be a real behavior
change (deletion would start failing where it previously succeeded);
preserving both means payroll history remains only as safe as whoever
holds Admin/HR credentials choosing not to pass `cascade=1`. Worth a
product decision on whether this should become soft-delete/archival
during migration rather than a like-for-like port.

---

## Rule: Mobile "logout" deactivates the employee's account at that company, no password required

**Current Behavior:** `profile/logout.php`, when called by an
`EMPLOYEE`-type session, does more than end the session: it sets
`employees.is_active = 0` for that employee at that company, deletes all
their push tokens, and — if they were previously active — sends the
company a "employee left" notification
(`notification_employee_left_company_to_company()`). This happens on
**every** employee logout, unconditionally, with no password
confirmation and no explicit "are you sure" step. The employee cannot
simply log back in afterward: `login_employee.php` explicitly rejects an
`accepted`-but-`is_active=0` match with `403 EMPLOYEE_ACCOUNT_NOT_ACTIVE`
— they need an Admin/HR to reactivate them via `employees/reactivate.php`.
The endpoint's own comment acknowledges the severity: "re-join via
company code." Contrast directly with `profile/delete_account.php`'s
employee path, which produces the **exact same** `is_active=0` outcome
but requires re-entering the account password first — logout and
"delete my account" currently have identical system effects, but only
one of them is password-gated. Company-admin logout has no such side
effect at all (push-token cleanup only) — this is employee-specific.

**Where Observed:** `apis/api/profile/logout.php`, full file, lines
31–68; contrasted with `apis/api/profile/delete_account.php` lines
50–91 (the password-gated version of the same state change), and
`apis/api/auth/login_employee.php` lines 98–101 (the resulting login
rejection).

**Risk If Misinterpreted:** This is a strong candidate for a real,
already-happening support/product problem, not just a migration risk:
an employee tapping an ordinary "log out" button loses access to their
account and requires HR intervention to restore it, with the company
side additionally seeing a "this employee left" notification that isn't
actually true. Worth a direct question to whoever owns the mobile client
and support inbox about whether this is a known, intentional design
(e.g. deliberately treating logout as "leaving the company" for a
specific product reason) or a bug that's been silently generating
support burden. Do **not** port this behavior into a migrated system
without an explicit decision either way — it is exactly the kind of
subtle, high-impact rule this Discovery process exists to surface before
it gets carried forward by assumption.

## Evidence

All entries: `workin-hr/hr-legacy` commit `83c326e40f68dd0d560595a6c4e465eb681f2ce8`,
files cited individually per rule above, read in full rather than
sampled.

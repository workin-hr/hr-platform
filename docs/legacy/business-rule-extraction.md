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

## Evidence

All entries: `workin-hr/hr-legacy` commit `83c326e40f68dd0d560595a6c4e465eb681f2ce8`,
files cited individually per rule above, read in full rather than
sampled.

# Payroll–Attendance Reconciliation — Design (2026-08-08)

## Purpose And Authority

`PayrollCalculationService`'s own class Javadoc names the gap this
document closes: the overtime-pay multiplier is "an explicit,
documented placeholder — not a confirmed port of legacy behavior,"
using `dayRate / 8` per hour because prior research "neither...
captured the literal formula"
(`backend/src/main/java/com/workin/backend/payroll/PayrollCalculationService.java:28-38`).
`PayrollBatchService.calculate()` has the matching gap on the input
side: it hardcodes `new AttendanceFigures(totalDaysInPeriod, 0, 0,
BigDecimal.ZERO)` for every employee, its own Javadoc recording this
as a named, temporary limitation — "assumes full attendance for every
employee rather than fabricating a data source. Re-run once attendance
data exists" (`PayrollBatchService.java:44-47,151-152`). This document
reads the real legacy formula in full and defines the target Java
shape; it does not perform the wiring itself (see Scope/Out and
Consequences).

**This document is planning output only** (hr-platform `CLAUDE.md`:
Claude's role is planning/analysis/review; implementation is a
separate, explicitly assigned step). **Confirmed decision** —
repository owner, 2026-08-08: "adopt these legacy formulas exactly as
verified truth" (the same instruction recorded in
`docs/superpowers/specs/2026-08-08-employee-schedule-foundation-design.md`'s
Purpose section for the schedule module). This is not treated as an
open question in this document; every formula element below is taken
as the target behavior unless flagged as a genuine Java-typing
ambiguity in Design.

Evidence: `hr-legacy/apis/helpers/payroll_calculation.php` read in
full (1432 lines, current `main`, commit `d113204`) — every function
`payroll_compute_employee_payslip` (:1101-1276) calls was traced and
read in full in turn; each is cited inline by line range in Design,
below. Supporting constants read directly:
`PENALTY_CALENDAR_DAYS_PER_MONTH = 30`
(`apis/helpers/penalties_amount_helper.php:8`) and the
`CompanySettingEnum` keys `overtime_rate`/`pay_overtime`/
`weekly_off_days`/`month_start_day`/`month_end_day`
(`apis/config/enums.php:117-121`). hr-platform current state read in
full: `PayrollCalculationService.java`, `PayrollBatchService.java`,
`Payslip.java`, `SalaryContract.java`, `CompanySettings.java`,
`CompanySettingsService.java`, `EffectiveCompanySettings.java`,
`V27__create_company_settings.sql`, and both existing payroll test
classes.

This slice depends on two others, referenced but not redesigned here:
`docs/superpowers/specs/2026-08-08-employee-schedule-foundation-design.md`
(shift/schedule lookup — the source of per-employee work hours once
its `expected_daily_hours`-equivalent lands) and the attendance-calendar
engine, spec'd separately (expected path
`docs/superpowers/specs/2026-08-08-attendance-calendar-engine-design.md`).

## Scope

**In:** the exact target formula for `PayrollCalculationService.compute()`,
quoted from `payroll_compute_employee_payslip` with line evidence
(Design, below); the `AttendanceFigures` record's target shape —
which fields it must carry so `compute()` can reproduce the legacy
formula, versus values legacy computes internally that become
caller-supplied inputs here because their real source, day-by-day
classification, lives in the attendance-calendar engine, not this
service; the `company_settings` schema addition (`pay_overtime`
column) and the `EffectiveCompanySettings` accessor gap for
`overtime_rate` (the column exists since V27 but is unexposed — the
same pattern the schedule-foundation spec already named for
`weekly_off_days`); rounding/scale open questions where PHP `round()`
does not map 1:1 onto a `BigDecimal` scale/`RoundingMode` choice,
named rather than silently picked; the dependency-ordering fact that
`PayrollBatchService.calculate()`'s actual wiring to real attendance
figures is blocked on the calendar engine's implementation, not on
this document.

**Out:** performing the wiring in `calculate()` itself — this document
defines the target `AttendanceFigures` interface, populating it is a
later, separate implementation step; the calendar engine's own
day-classification logic (worked/absent/exception/void-weekly-rest/
holiday-credit-suppressed), spec'd separately and treated here as a
given input; the weekly-rest-credit rule's internal earned/void
computation and the official-holiday credit helper's internals — both
black boxes here, their *outputs* (`earned_weekly_rest_days`,
`void_weekly_rest_days`, `official_holiday_days`) consumed as
already-computed inputs, matching how `payroll_compute_employee_payslip`
itself consumes them via `payroll_payslip_attendance_display` rather
than re-deriving the credit rules; deduction-structure divergences
unrelated to attendance (e.g. `SalaryContract.getPenaltyDeduction()`
folded into `otherDeductions` with no equivalent legacy field at
:1227-1231; the advance-deduction scheduling heuristic already
flagged a "v1 heuristic" in `PayrollBatchService`'s own Javadoc) —
real gaps, orthogonal to attendance/overtime, not reopened here; any
UI/API surface change beyond the one new settings field.

## Design

**The confirmed formula.** `payroll_compute_employee_payslip`
(:1101-1276) computes, per employee per batch period:

- `gross_salary = contract_basic + housing + transport + food + risk + incentives`,
  rounded to 2 (:1167) — `contract_basic` is `daily_wage × 30` for
  `SalaryMode.DAILY` contracts, else the raw monthly basic (:1162-1164,
  matching current Java's `monthlyEquivalentBasic`).
- `day_rate = round(gross_salary / 30, 2)` (:1169-1171), divisor
  `PENALTY_CALENDAR_DAYS_PER_MONTH = 30` — the same fixed-30-day
  convention `PayrollCalculationService`'s Javadoc already verifies and
  keeps (`.java:22-23,44`). **Difference from current Java**: legacy
  derives `day_rate` from the *whole gross package*; current Java
  derives `dayRate` from `monthlyEquivalentBasic` alone (`.java:73-77`)
  and never folds allowances in — the root of the next divergence.
- `salary_base` — mid-period proration, `payroll_attendance_salary_base`
  (:261-275): if the period is closed (`!payroll_period_in_progress`,
  :225-229 — `as_of < period_to`), `salary_base = round(gross_salary, 2)`
  (full package); if the period is still open, `salary_base =
  round(day_rate × elapsed_calendar_days, 2)`, where
  `elapsed_calendar_days` (`payroll_elapsed_calendar_days`, :234-255)
  is the inclusive day count from `period_from` through `as_of`
  (`as_of` from `payroll_calculation_as_of_date`, :219-223 — today, or
  `period_to` once the period has ended). **This entire proration step
  does not exist in current Java** — `compute()` has no notion of an
  in-progress period, an as-of date, or a partial-package salary base;
  it always treats the full month as elapsed.
- `absence_cost = round(day_rate × unpaid_days, 2)`, where
  `unpaid_days = max(0, days_absent)` and `days_absent` is *already*
  the combined figure from `payroll_payslip_attendance_display`
  (:292-374) — workday absence plus unearned/void weekly-rest days
  (:357-358, "Unearned weekly rest is unpaid and shown as absence").
  Current Java's `AttendanceFigures.daysAbsent` is a plain count with
  no such folding contract; the redesigned field must document that
  its caller supplies the already-combined value, not raw workday
  absences.
- `salary_by_attendance = max(0, round(salary_base − absence_cost, 2))`
  (:1184). **This is the value that replaces current Java's
  `basicAfterAbsence`, and it is the central formula-shape fix**:
  legacy prorates the *entire* package (basic + all allowances)
  proportionally to absence via a `day_rate` computed from `gross_salary`;
  current Java prorates only the basic salary
  (`basicAfterAbsence = monthlyEquivalentBasic − absenceCost`,
  `.java:78,85`) then adds full, unprorated allowances on top
  (`.java:97-104`). At zero absence and a closed period the two are
  numerically identical; with any absence, or mid-period, they diverge
  — current Java under-deducts because allowances ride along
  un-prorated. Line :1224 confirms the resulting shape:
  `total_entitlements = salary_by_attendance + overtime_pay`, no
  separate allowance terms added, "because allowances are inside the
  gross and therefore inside the salary-by-attendance" (translated
  comment, :1223-1224). The per-allowance fields (`allowances`,
  `transport_allowance`, `food_allowance`, `risk_allowance`,
  `incentives`) are still returned (:1258-1262) for payslip display —
  just not summed a second time into `total_entitlements`.
- `work_hours_per_day` — `payroll_employee_work_hours_per_day`
  (:743-756): `COALESCE(NULLIF(employee.expected_daily_hours, 0),
  NULLIF(job_title.work_hours, 0), 8)`. **Gap**: hr-platform's
  `employees` table has no `expected_daily_hours`-equivalent column
  (confirmed absent by search); `job_titles.work_hours` exists
  (organization-structure spec, V29). **Open question, not decided
  here**: add the employee-level column now for full three-level
  fidelity, or accept a two-level fallback (`job_title.work_hours` →
  `8`) until an override is actually needed — flagging for the owner
  rather than assuming.
- `hourly_rate = day_rate / work_hours_per_day` when both positive,
  else `0` (:1187-1189, kept unrounded). `expected_hours =
  punch_present_days × work_hours_per_day`; `overtime_hours = max(0,
  total_hours_worked − expected_hours)` (:1190-1191).
  `total_hours_worked` sums per-day worked minutes from
  `payroll_attendance_summary` (:672-738), which folds in single-punch
  adjustment ("worked hours = expected daily hours − 2h," :668) and
  timed-request/exception handling — all outside this service's
  scope; `total_hours_worked` and `punch_present_days` are treated
  here as calendar-engine outputs, not re-derived.
- `overtime_pay = round(overtime_hours × hourly_rate × overtime_multiplier, 2)`,
  forced to `0` when `!payroll_company_pays_overtime(company_id)`
  (:1192-1195) — this closes the exact gap the current Javadoc names:
  `dayRate / 8` is replaced by `day_rate / work_hours_per_day` with a
  real multiplier and pay-toggle. `overtime_multiplier`
  (`payroll_overtime_multiplier_from_setting`, :125-133) reads the
  `overtime_rate` setting (default `125`); `raw <= 0` → `1.25`;
  `raw > 10` → `round(raw / 100, 4)` (percentage form, `125` →
  `1.25`); else `raw` as-is (already a multiplier, e.g. `1.5`).
  `payroll_company_pays_overtime` (:140-149) reads `pay_overtime`,
  **default `true` when unset** ("keep existing companies unchanged,"
  :138-139), true only for `'1'`/`'true'`/`'yes'`/`'on'`
  (case-insensitive, trimmed).
- `days_present` (displayed/persisted count, distinct from the money
  formula) = `punch_present + earned_weekly_rest_days +
  official_holiday_days` (:1235-1237) — not used in the pay
  calculation, only the attendance display. `net_salary = max(0,
  round(total_entitlements − total_deductions, 2))` already matches
  current Java's shape.

**Target `AttendanceFigures` redesign.** The record needs to carry
what the formula above consumes, split between what the calendar
engine classifies per day and what this service still derives.
Proposed fields (exact types left to the repository owner where
flagged): `daysPresent` (punch-present count, exceptions included);
`daysAbsent` (the *combined* workday-absence + void-weekly-rest
figure — the field's contract must say so explicitly, since a caller
passing raw workday absences alone would silently under-deduct);
`daysLeave` (already present on the record; becomes load-bearing
instead of decorative, since the `paid_due` cap treats leave as paid
attendance); `earnedWeeklyRestDays`, `voidWeeklyRestDays`,
`officialHolidayDays` (all three needed to reproduce
`payroll_payslip_attendance_display`'s absence math and displayed
`days_present`); `workHoursPerDay` (resolved value, see the
fallback-chain question above); `totalHoursWorked` (replacing the
current flat `overtimeHours` input — `compute()` should derive
`overtimeHours` itself as `totalHoursWorked − daysPresent ×
workHoursPerDay`, matching legacy, rather than accept a pre-computed
figure). `compute()` also needs `periodFrom`/`periodTo`/`asOfDate` (or
equivalent) to run the mid-period branch — whether these belong on
`AttendanceFigures` or as separate `compute()` parameters is a
Java-shape choice left open here.

**Rounding/scale — open questions, not decided here.** PHP's `round()`
is round-half-away-from-zero at a fixed decimal count computed fresh
at each step; `BigDecimal` requires an explicit scale and
`RoundingMode` carried through every intermediate value. Concretely:
legacy rounds `day_rate` to 2 decimals immediately (:1170) and does
the `absence_cost`/`salary_base` arithmetic on that already-rounded
value — current Java keeps `dayRate` at scale 6 unrounded through
`absenceCost` (`.java:44-45,77-78`). Legacy's `hourly_rate` is never
rounded before being multiplied into `overtime_pay` (:1187-1189).
Whether hr-platform should round `dayRate` to 2 immediately (matching
legacy's actual intermediate values, which can matter at the cent
level across many days) or keep higher intermediate precision is a
decision for the repository owner — "adopt legacy exactly" confirms
*which formula*, not *which BigDecimal scale* reproduces PHP's
float/round() behavior. `RoundingMode.HALF_UP` matches PHP `round()`
for these (non-negative) amounts, so that part is not open; the scale
at which each intermediate is rounded is.

**`company_settings` schema addition.** `pay_overtime` does not exist
as a column (V27 created `month_start_day`, `month_end_day`,
`weekly_off_days`, `overtime_rate`, `monthly_leave_accrual` only —
`V27__create_company_settings.sql:8-18`). A new migration is needed:
`pay_overtime BOOLEAN` (nullable, `NULL` = unset = the legacy default
`true`, following V27's own "null means apply the legacy fallback"
convention). `overtime_rate` already exists as a column and on the
`CompanySettings` entity (`CompanySettings.java:36-37`) but
`EffectiveCompanySettings` exposes only `monthStartDay`/`monthEndDay`/
`monthlyLeaveAccrual` — `CompanySettingsService.effective()` needs a
fourth (and with `pay_overtime`, fifth) resolved accessor, each with
its legacy fallback (`overtimeRate` → `125`/`1.25` per
`payroll_overtime_multiplier_from_setting`'s own default; `payOvertime`
→ `true`). Same unexposed-column shape the schedule-foundation spec
already named for `weeklyOffDays` — not a new pattern, the same fix
applied to two more fields.

**Wiring point — explicitly out of scope, not papered over.**
`PayrollBatchService.calculate()` (:151-152) is where
`AttendanceFigures` gets built today and is the actual integration
site for real data once it exists. This document defines the target
formula and `AttendanceFigures` shape now, on the owner's confirmed
instruction to do so ahead of the calendar engine landing. But
populating `AttendanceFigures` with real per-employee
attendance/leave/rest/holiday classification requires the
attendance-calendar engine's day-by-day output, which does not exist
yet. **The wiring in `calculate()` is blocked on that engine's
implementation landing — a dependency-ordering fact, stated here so it
is not mistaken for something this document already solved.**

## Testing

No overtime-formula test exists today (`PayrollCalculationServiceTest.java`
covers only basic/daily-wage salary mode and the fixed-30-day divisor).
Once the formula above is implemented, `PayrollCalculationServiceTest`
needs: overtime multiplier resolution (`125` unset-default → `1.25`;
`150` → `1.5`; `1.5` passed through unchanged — the `> 10` percentage
threshold); `pay_overtime = false` zeroes `overtimePay` while
`overtimeHours` is still recorded (mirrors :137's "hours are still
calculated; pay is zero when disabled"); mid-period proration — an
open batch's `salary_base` uses elapsed calendar days × `day_rate`, a
closed batch uses full `gross_salary`, plus a case at the exact
`as_of == period_to` boundary; absence cost applied against the whole
gross package, not basic alone, with a case asserting current-Java's
full-allowance passthrough under absence is what changed; work-hours
fallback chain, scoped to whichever depth the open question above
resolves to. `PayrollBatchLifecycleTest` stays as-is; a wiring-level
test against real attendance figures is blocked on the same
calendar-engine dependency and is not proposed here.

## Consequences

`PayrollCalculationService.compute()` gets a formula that is a
confirmed port of `payroll_compute_employee_payslip` rather than a
documented placeholder, closing the gap its own Javadoc names. The
`AttendanceFigures` record gains the fields the real formula needs
(work hours, total hours worked, leave/rest/holiday credit), making
`daysLeave` load-bearing instead of accepted-but-ignored. Two concrete
decisions are left open for the repository owner — the employee-level
work-hours override (add the column now vs. two-level fallback) and
the `BigDecimal` intermediate-rounding scale — because "adopt legacy's
formula" answers *what* to compute, not *how precisely Java should
round it*, per `CLAUDE.md`'s requirement to separate confirmed facts
from decisions this document is not entitled to make. The one blocker
this document does not resolve, by design, is
`PayrollBatchService.calculate()`'s actual wiring to real per-employee
attendance data — that remains gated on the attendance-calendar
engine's implementation landing, tracked separately.

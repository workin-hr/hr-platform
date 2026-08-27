# Broad J.2 Settlement — Discovery

## Status and authority

Discovery and research only. This document implements nothing and authorizes
nothing. It answers a question that two accepted documents deliberately left
open, and hands the owner the evidence needed to record a decision.

It does **not** record that decision. Recording it is the owner's, under
`AGENTS.md`'s "No agent may silently resolve unclear requirements".

### The question this answers

`2026-08-22-wave-12.6-attendance-discovery.md` §J.2 resolved half of the
payroll-boundary question (`payroll_is_weekly_rest_day` may be extracted) and
left the other half explicitly open:

> The broader subset — the six DB-backed functions `overall_report` and `export`
> need, two of which reach `company_settings` and the holiday helper D-090
> excluded, and which additionally read Wave 12.7's `requests` table — **remains
> blocked**. §G.2 is the enumeration that was required; the final J.2 decision is
> deliberately **not** recorded yet.

`2026-08-23-phase1-completion-plan.md` §4.5 added the timing constraint:

> It gates 12.6.6 only, and cannot honestly be settled before Wave 12.7 lands,
> because the answer depends on what 12.7 makes available.

Wave 12.7 landed, and Waves 12.8/12.9 landed after it. The precondition is met.

### Repository state measured for this document

| Source | Commit |
|---|---|
| `hr-platform` | `e112ebc` (branch `docs/wave12-completion-record`) |
| `hr-legacy` | `d113204c8a2cf83b997c5e65c6c86e4f59b3f8f6` (frozen) |

Every claim below was measured against those two trees.

---

## 1. Finding: the broad J.2 blocker no longer exists

**The seven payroll functions §G.2 enumerated are all already in `main`.** They
were not pulled forward into Wave 12.6. They landed in their own waves — 12.8
and 12.9 — as part of delivering the payroll engine, which is precisely the
ownership principle §J.2 was protecting.

The question "may Wave 12.6 pull these payroll functions forward?" is therefore
**moot**. There is nothing left to pull.

| §G.2 function | Kind | Ported as | Where |
|---|---|---|---|
| `payroll_is_weekly_rest_day` | pure | `LegacyWeeklyOffDays.isWeeklyRestDay` | D-091 extraction |
| `payroll_calculation_as_of_date` | pure | inlined `asOf` computation | `LegacyPayslipService:235,325`; `LegacyPayrollBatchService:354` |
| `payroll_period_in_progress` | pure | inlined `inProgress` computation | `LegacyPayslipService:326`; `LegacyPayrollBatchService:352` |
| `payroll_approved_leave_days` | DB | `approvedLeaveDays()` | `LegacyPayrollAttendanceFigures:333` |
| `payroll_employee_work_hours_per_day` | DB | `employeeWorkHoursPerDay()` | `LegacyPayrollAttendanceFigures:326` |
| `payroll_period_working_days` | DB | `periodWorkingDays()` | `LegacyPayrollAttendanceFigures` |
| `payroll_expected_work_days_until` | DB | `expectedWorkDaysUntil()` | `LegacyPayrollAttendanceFigures:206` |

### 1.1 The `company_settings` reach is the key D-091 already authorized

§G.2 flagged that `payroll_period_working_days` and
`payroll_expected_work_days_until` "drag in both `company_settings` **and** the
holiday helper D-090 excluded". That was true, and it is worth stating exactly
what the settings reach turned out to be, because it is narrower than the phrase
suggests.

`official_holidays_working_days_in_range()`
(`apis/helpers/official_holidays_helper.php`) reads exactly one key:

```php
$rest = company_setting_selected_values($company_id, CompanySettingEnum::WEEKLY_OFF_DAYS->value);
```

`WEEKLY_OFF_DAYS` is **the same single key D-091 already authorized a bounded,
read-only reader for**, and whose live case-sensitivity defect D-103 fixed. It
is not a new settings dependency, not a second key, and not a step toward Item
13's `company_settings` endpoints.

`LegacyPayrollAttendanceFigures.officialHolidaysWorkingDaysInRange()` already
implements the helper in full, composing three things that all exist:

- `weeklyOffDays.forCompany(companyId)` — the D-091 reader;
- `HOLIDAY_DATES_IN_RANGE` — `official_holiday_dates_in_range()`;
- `isWeeklyRestDayNoArabic(...)` — `official_holidays_is_weekly_rest_day()`.

### 1.2 One measured divergence is already preserved

Worth recording because it is the kind of thing a later "simplification" would
destroy. `LegacyPayrollAttendanceFigures`'s class javadoc documents that legacy
has **two independently-written weekly-rest matchers** that do not agree:

- `payroll_is_weekly_rest_day()` matches English, abbreviated, numeric **and
  Arabic** day names;
- `official_holidays_is_weekly_rest_day()` matches only English, abbreviated and
  numeric — **no Arabic table at all**.

Java keeps them apart rather than unifying them. Any Wave 12.6.6 work must reuse
these as they are; the report reaches both.

### 1.3 The `requests` dependency is closed

§G.2's other blocker was that `payroll_approved_leave_days` and
`payroll_employee_work_hours_per_day` read `requests`/`request_types`. Wave 12.7
delivered `requests` and Wave 12.5 delivered `request_types`. Both are live, and
both functions are ported against them.

### 1.4 A precision note on "the six DB-backed functions"

§J.2's phrase is slightly imprecise and should not be quoted as a count. §G.2's
own table lists **seven** reachable functions, of which one
(`payroll_is_weekly_rest_day`) was separately extracted and **four** are
DB-backed; the other two (`payroll_calculation_as_of_date`,
`payroll_period_in_progress`) are pure date arithmetic. "The other six" is
accurate; "the six DB-backed functions" is not. This changes no decision — every
one of the seven is ported — and is recorded only so the number is not carried
forward as a scope figure.

---

## 2. What `overall_report.php` actually still needs

With the payroll boundary closed, the remaining work is the report's own
per-employee detail helpers. These are **not** payroll functions and were never
part of the J.2 question; they simply had no reason to be ported before, because
no delivered endpoint needed them.

`overall_attendance_report_build()`
(`apis/helpers/overall_attendance_report_helper.php`, 379 lines) has a
fifteen-function call closure. Eleven are already ported:

| Helper | Ported? | Where |
|---|---|---|
| `payroll_approved_leave_days` | yes | `LegacyPayrollAttendanceFigures` |
| `payroll_calculation_as_of_date` | yes | inlined |
| `payroll_employee_work_hours_per_day` | yes | `LegacyPayrollAttendanceFigures` |
| `payroll_expected_work_days_until` | yes | `LegacyPayrollAttendanceFigures` |
| `payroll_period_in_progress` | yes | inlined |
| `official_holidays_by_date_in_range` | yes | `LegacyAttendanceCalendar.holidaysByDate` |
| `official_holidays_credit_days_for_employee` | yes | `officialHolidaysWorkingCreditForEmployee` |
| `weekly_rest_attendance_flags_in_range` | yes | `LegacyWeeklyRestCredit.attendanceFlagsInRange` |
| `weekly_rest_earned_days_in_range` | yes | `weeklyRestDatesByStatus(..., EARNED).size()` |
| `weekly_rest_void_days_in_range` | yes | `weeklyRestDatesByStatus(..., VOID).size()` |
| `attendance_present_details_for_period` | yes | `LegacyPayrollAttendanceFigures.presentDetails` |

**Four are not ported**, all in `apis/helpers/attendance_calendar_helper.php`:

| Helper | Lines | Size |
|---|---|---|
| `attendance_exception_details_for_period` | 447–477 | 31 |
| `attendance_absent_details_for_period` | 540–601 | 62 |
| `attendance_void_weekly_rest_absent_details_for_period` | 610–667 | 58 |
| `attendance_period_work_minutes` | 859–928 | 70 |

They are the same shape as `attendance_present_details_for_period`, which is
already ported and can serve as the pattern.

### 2.1 Honest scope statement

Wave 12.6.6's `overall_report.php` is **one JSON endpoint, four unported
calendar helpers (221 lines of PHP), and the report builder**. It is a real
slice of work. It is not the cross-cutting payroll-boundary decision that §J.2
described, because that boundary closed on its own while other waves shipped.

---

## 3. What this does and does not settle

**Settles (evidence, not decision):** broad J.2's blocker is gone. Every
function it named is in `main`; the settings reach is D-091's existing single
key; the `requests` dependency is closed. No new decision is required to
*unblock* `overall_report.php`, and specifically no new `company_settings`
authorization is needed.

**Does not settle:** whether `overall_report.php` is delivered, formally
excluded, or deferred. That is the C9 disposition recorded as owed in
`2026-08-23-phase1-completion-plan.md` §8.1, and it stays with the owner. This
document only removes "we cannot decide yet, the dependency question is
unresolved" from the list of reasons to wait.

**Unaffected:** `attendance/export.php` and `payslips/export.php`. Their
constraint was never J.2 — it is that both terminate in a streaming helper
declared `: never` rather than returning the `ok()` JSON envelope. This document
says nothing about them, and their disposition remains separately owed.

---

## 4. Standing references

- The open question: `2026-08-22-wave-12.6-attendance-discovery.md` §J.2, §G.2, §G.3
- The timing constraint now met: `2026-08-23-phase1-completion-plan.md` §4.5
- The disposition still owed: same document, §8.1 and §6 C9
- The bounded settings reader: **D-091**; its case-sensitivity fix **D-103**
- The payroll engine that ported the seven: **D-105** (Wave 12.9), **D-104**
- The wire contract every delivered route answers on: **D-074**

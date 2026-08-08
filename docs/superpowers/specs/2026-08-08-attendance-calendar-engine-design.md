# Attendance Calendar Engine — Design (2026-08-08)

## Purpose And Authority

The attendance-module first slice named this exact gap and deferred it:
"payroll's attendance-driven absence figures (payslips keep their
manual `AttendanceInput` until a later slice wires real derivation)"
(`docs/superpowers/specs/2026-08-07-attendance-module-first-slice-design.md`,
Out section). The employee-schedule-foundation spec, written the same
day, names this file directly as the next consumer waiting on its
`ScheduleService.shiftForEmployeeOnDate`/`isWeeklyRestDay` interface
(`docs/superpowers/specs/2026-08-08-employee-schedule-foundation-design.md:11-16`)
and records the repository owner's confirmation to "adopt legacy
schedule/payroll formulas as verified (`hr-legacy` commit `d113204`)"
(same file, lines 21-23) — that adoption-as-verified decision covers
this commit's attendance helpers too, since they landed in the same
commit. **This document is planning output only** (hr-platform
`CLAUDE.md`: planning/analysis/review, not implementation).

Evidence: `hr-legacy` commit `d113204` (current `main`, already pushed)
rewrote `apis/helpers/attendance_calendar_helper.php` (+417 lines),
`apis/helpers/attendance_session_helper.php` (+210 lines), and
`apis/helpers/overall_attendance_report_helper.php` (+201 lines), read
in full; the pre-`d113204` baseline for `attendance_session_helper.php`
read via `git show 4c5be61:apis/helpers/attendance_session_helper.php`
for the open-session diff. Six `apis/api/attendance/*.php` endpoints
read for call sites. hr-platform's current attendance module
(`Attendance.java`, `AttendanceService.java`, `AttendanceController`,
V21/V22) confirmed it has CRUD and the 2-hour gap rule only, no
day-classification logic. `RequestService.java:225-237`
(`applyAttendanceExceptions`), `LeaveRequest.java:45-49` (`fromTime`/
`toTime` already exist), and `RequestType.java:14-15` ("`counts_as_paid_leave`
is stored but has no consumer yet") establish the coordination surface
with the requests module.

## Scope

**In — schema:** none. This is pure computation over the existing
`attendance` table (V21, unchanged) plus the schedule-foundation
spec's planned `employee_shift_assignments`/`employee_schedules`
(consumed through `ScheduleService`, not redesigned here) plus the
existing `requests`/`request_types` tables. One gap, not a schema
addition decided here: legacy's per-employee expected-hours override
column, `employees.expected_daily_hours` (`mysql_workin.schema.sql:408`),
has no hr-platform equivalent — flagged below as an open question, not
assumed.

**In — endpoints** (extending `com.workin.backend.attendance`):

- `GET /api/tenant/attendance/{employeeId}/calendar?from=&to=` — one
  endpoint replacing legacy's three overlapping read paths:
  `employee_monthly_attendance.php`'s `full_month=1` branch
  (`attendance_build_employee_monthly_calendar`, lines 50-61),
  `list.php`'s `fill_days=1` branch (`attendance_build_employee_range_calendar`
  capped at today, `list.php:19-125`), and `employee_monthly_attendance.php`'s
  plain month-scan branch (lines 63-121). Returns one row per calendar
  day, ascending, each classified per the Design section.
- Open-session auto-close, run inline before serving calendar/list
  reads (legacy's own call pattern — see Design for the exact deadline
  formula and a flagged concern about coupling a GET to a write).
- Timed-request/مأمورية hour override, consumed inside worked-minutes
  computation for any day with an approved leave request carrying both
  `fromTime` and `toTime` (`LeaveRequest.java:45-49` already has these
  columns — no schema gap here, only a new repository query).
- `GET /api/tenant/attendance/overall-report` — **scoped down** from
  legacy's `overall_attendance_report_build`
  (`overall_attendance_report_helper.php:19-379`): present/exception/
  absent-day counts, total/expected duration minutes, overtime
  minutes, and the three hover-detail lists (`present_details`,
  `absent_details`, `exception_details`) port now, computed from this
  engine's own day classification. The weekly-rest-credit-derived
  fields (`earned_weekly_rest_days`, `void_weekly_rest_days`,
  `official_holiday_days` credit, `paid_rest_days`,
  `paid_rest_minutes`, `effective_present_days`) are **Out** — see
  below.

**Out (tracked, blockers named):**

- Weekly-rest earned/void/pending status
  (`weekly_rest_credit_helper.php`, `WEEKLY_REST_CREDIT_EARNED`/
  `_VOID`/`_PENDING`, `WEEKLY_REST_MIN_COVERED_WORKDAYS = 3`) — its
  own spec; this engine exposes a plain `isWeeklyRest` boolean per day
  and stops there, deliberately not computing earned/void/pending
  (task boundary: this is the classifier that feature consumes, not
  the feature itself). Do not conflate legacy's "void" (a weekly-rest
  credit *status*) with this engine's own classification set.
- Official-holiday integration beyond a boolean per-day check —
  `official_holidays_by_date_in_range` needs the holiday module, which
  doesn't exist in hr-platform yet (same stub the schedule-foundation
  spec already declared for its monthly overview).
- Payroll's period math: `payroll_calculation_as_of_date`,
  `payroll_period_in_progress`, `payroll_expected_work_days_until`,
  `payroll_approved_leave_days`, `payroll_employee_work_hours_per_day`
  — payroll-reconciliation territory, tracked separately.
  `overall_attendance_report_build` leans on all five
  (`overall_attendance_report_helper.php:48-49,236-297`); the scoped
  endpoint above avoids them by working over the full requested range
  rather than an as-of-capped one.
- Self check-in/out (`check_in_qr.php`) — still blocked by the
  first-slice spec's named reasons (branches/geofencing/QR, F-04). Its
  `attendance_find_open_session` call (lines 59-61) reuses this
  engine's deadline logic unchanged once it ships.
- Excel import/analyze (`attendance_excel_analyzer.php`'s upload flow)
  — separate read-through. Only its two pure functions,
  `attendance_import_expected_for_day` and `attendance_import_classify_day`,
  are ported here as shared primitives.
- Data export (`export.php`, `data_export_helper.php`) — not read for
  this design, separate surface.

## Design

New computation, e.g. `AttendanceCalendarService` in the existing
`com.workin.backend.attendance` package, consuming
`ScheduleService.shiftForEmployeeOnDate`/`isWeeklyRestDay` (the other
spec's interface — referenced, not redesigned).

**Per-employee expected-day resolution** (`attendance_import_expected_for_day`,
`attendance_excel_analyzer.php:498-546`): resolve the shift for the
date; if the day is a holiday or weekly rest, `expected_minutes = 0`
and `is_rest_day = true` — this applies **even with no shift
assigned**, per the comment at `attendance_calendar_helper.php:280`
("Company weekly rest / holidays still apply when no shift is
assigned"). Otherwise, with a shift, `expected_minutes =
shift_duration_minutes(start, end)` (overnight-safe: `end > start` →
`end - start`; `end == start` → `0`; else `(1440 - start) + end`,
`shift_times.php:28-43` — this overnight-rollover helper is referenced
by the calendar/session/report helpers but wasn't in the assigned read
list; it needs porting alongside this engine). With no shift,
`expected_minutes` falls back to
`COALESCE(NULLIF(employee.expected_daily_hours,0), NULLIF(job_title.work_hours,0), 8) * 60` —
the first term has no hr-platform column (open question below).

**Day classification**, mirroring `attendance_build_employee_range_calendar`'s
per-day loop exactly (`attendance_calendar_helper.php:277-420`):

1. Resolve the exception label for the day: holiday name wins if the
   date is in `holiday_by_date` (empty name falls back to the
   localized "weekly rest" string); else the weekly-rest label if
   `ScheduleService.isWeeklyRestDay`; else `null`
   (`schedule_helper.php:73-89`).
2. If an attendance row exists for the day:
   - **Exception-only shape** — `exceptionTypeId` set, no checkout,
     check-in's time component is exactly `00:00:00`
     (`attendance_is_exception_only_row`, lines 12-26) → classify
     **Exception**; duration is `0` unless a timed request overrides.
   - Otherwise → classify **Worked**; duration via
     `attendance_row_worked_minutes` (lines 152-201), precedence
     order: (a) an approved timed request for the day overrides
     everything else; (b) exception-only → `0`; (c) no punches at all
     → `0`; (d) a check-in with no check-out that is still "live"
     (before its deadline, see below) → `0`, **not** the display
     formula — this is what stops an in-progress shift from showing
     phantom hours before it's actually over; (e) otherwise the
     display-duration formula: complete punches → raw
     `checkOut - checkIn` minutes; exactly one punch →
     `expected_minutes - ATTENDANCE_INCOMPLETE_PUNCH_DEDUCTION_MINUTES`
     (the constant is `120`, `attendance_calendar_helper.php:7`),
     floored at `0`; both absent → `0`.
3. No attendance row, day is rest/holiday → classify **Rest** or
   **Holiday**, synthetic row with a stable negative id:
   `-1 * (employeeId * 100000000 + (int(dateWithoutDashes) % 100000000))`
   (`attendance_synthetic_row_id`, lines 206-209).
4. No attendance row, not rest/holiday → classify **Absent**, unless a
   timed request exists with no punches (partial-day duration counts,
   `is_missing = duration <= 0`).

**Timed-request (مأمورية) override** — `attendance_approved_timed_request_for_day`
(lines 53-84): most-recent (`ORDER BY id DESC`) approved request
covering the date with non-empty `fromTime` and `toTime`. hr-platform
already has both columns (`LeaveRequest.java:45-49`); this engine
needs one new `LeaveRequestRepository` query — none of the existing
three (`findByCompanyIdOrderById`, `findByEmployeeIdAndCompanyIdOrderById`,
`findByIdAndCompanyId`) support it. Two sub-cases (lines 106-145): no
complete real punches → duration is the mission window,
`shift_duration_minutes(fromTime, toTime)`; complete punches present →
duration is shift-start (from expected-day resolution, falling back
to the request's `fromTime`) through the actual check-out, `+86400`
seconds when checkout clock time is earlier than start (overnight
rollover).

**Open-session auto-close — what changed.** Baseline
(`4c5be61:apis/helpers/attendance_session_helper.php`): a flat,
**read-only** 18-hour SQL window — `attendance_find_open_session`
simply filtered `check_in >= NOW() - INTERVAL 18 HOUR`, no write, no
shift awareness. `d113204` replaces this with
`attendance_open_session_deadline` (lines 40-72): starting the day
after check-in, scan up to **8 days ahead**, skip any day where
`is_rest_day` is true, and take the first day's resolved shift-start
time as the deadline; if none resolves in 8 days, fall back to
`checkIn + 18 hours`. `attendance_auto_close_stale_open_sessions`
(lines 80-147) then **writes**: for every open row past its deadline,
it sets `check_out = check_in + (expected_minutes - 120) minutes` via
`UPDATE` — the same single-punch deduction formula as the display
rule. `attendance_find_open_session` calls this close **inline**
before every read (lines 176-178, 218-222), and exception-only rows
(midnight, no checkout) are explicitly excluded from ever being
"open" (lines 112-118, 206-212) since they have no real check-out to
synthesize. `attendance_is_live_open_punch` (lines 235-245) is the
predicate the worked-minutes calculation uses to suppress the display
formula while a punch is still inside its window.

**Open question, not decided here**: legacy couples a `SELECT`-style
read (find-open-session, monthly attendance, list) to a `DB WRITE`
(the auto-close). hr-platform's team should decide explicitly whether
to keep that inline coupling (matches legacy, simplest to port) or
move auto-close to a scheduled sweep (cleaner GET semantics, but a new
piece of infrastructure this repo doesn't have a precedent for).

**Coordination with `RequestService.applyAttendanceExceptions`**
(`RequestService.java:225-237`): on leave approval, it already writes
one `Attendance` exception row per approved day, **skipping any day
that already has an attendance row**. This engine's classification
reads those written rows as ordinary exception-only attendance (step 2
above) — no conflict. Separately, legacy's absence carve-out
(`attendance_is_on_approved_leave`, lines 669-683) checks
`RequestType.countsAsPaidLeave` directly against the `requests` table,
not through the synthetic exception row — this engine becomes the
first consumer of that field (`RequestType.java:14-15`: "stored but
has no consumer yet"). The two paths can diverge exactly as legacy
intends: a day that already had a real punch before leave was approved
keeps its punch and is classified **Worked** (the exception row was
skipped), but it is still excluded from **Absent** via the direct
`countsAsPaidLeave` check — not a bug, legacy's actual behavior, worth
stating explicitly so it isn't "fixed" during implementation.

## Testing

Worked classification: complete punches → duration = raw diff; single
punch → duration = `expected - 120`min, classified Worked not Absent;
no punches/no exception/scheduled workday/day elapsed → Absent.
Exception-only row (midnight check-in, `exceptionTypeId` set, null
checkout) → Exception, duration 0 absent a timed request. Rest/holiday
synthetic day (no attendance row): holiday name wins over weekly-rest
label when both apply; synthetic negative id stable across repeated
calls for the same employee+date. Timed request: approved leave with
`fromTime`/`toTime`, no punches → duration = mission window; same day
with complete punches → duration = shift-start to checkout, overnight
rollover (`+1 day`) when checkout clock time precedes shift-start.
Live-open-punch: check-in with no checkout still before its deadline →
duration 0, not `expected-120`, day not yet Worked or Absent.
Auto-close: open session past deadline → synthetic checkout =
check-in + `(expected-120)`min; deadline resolves to the next non-rest
scheduled shift start within 8 days, falls back to flat 18h otherwise;
exception-only open rows never auto-close. Absence carve-outs:
official holiday, weekly rest, approved paid leave
(`countsAsPaidLeave = true`) all excluded from Absent with no
attendance row present. `applyAttendanceExceptions` interaction: a
punched day before leave approval keeps its punch (Worked, no
exception row written) and is still excluded from Absent via the
direct approved-leave check. Overall-report: present/exception/absent
counts and detail lists match per-day classification aggregated over
the range; response omits (not zero-stubs) the weekly-rest-credit
fields until that module lands. Access/tenancy per house convention:
foreign/nonexistent employee → 404; cross-tenant → 404 + list
exclusion; read-without-`attendance.read` → 403; unauthenticated →
non-2xx.

## Consequences

Unblocks weekly-rest-credit (consumes this engine's `isWeeklyRest`/
`isOfficialHoliday` flags plus per-day attendance coverage to compute
earned/void/pending), payroll's real attendance-figures derivation
(the exact gap the first-slice spec named), and the full overall-report/
export surface once the holiday and weekly-rest-credit modules land on
top of this classification without changing it. Two dependency gaps
need the repository owner's explicit answer before an implementation
plan is written: whether `employees.expected_daily_hours` gets ported
as a new column or the fallback collapses to job-title-then-8, and
whether auto-close stays coupled to reads or becomes a scheduled job.
Both are named here as open, not resolved, per `CLAUDE.md`'s
requirement to separate confirmed facts from decisions the owner still
needs to make.

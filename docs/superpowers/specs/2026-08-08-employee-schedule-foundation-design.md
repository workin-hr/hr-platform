# Employee Schedule Foundation — Design (2026-08-08)

## Purpose And Authority

The organization-structure slice explicitly deferred
`employee_shift_assignments`, `employee_schedules`, and
`weekly_off_days`-style working-day math, naming the reason: "its
consumer, schedule/working-day calculation, doesn't exist yet"
(`docs/superpowers/specs/2026-08-07-organization-structure-first-slice-design.md`,
Out section). This slice is that consumer's foundation — it exists
because the attendance-calendar engine, weekly-rest-credit rule, and
payroll's real attendance-figures derivation (tracked separately, see
`docs/superpowers/specs/2026-08-08-attendance-calendar-engine-design.md`
and `docs/superpowers/specs/2026-08-08-payroll-attendance-reconciliation-design.md`)
all need a real answer to "what is this employee's expected schedule
on date X" before they can classify a day as worked/rest/holiday/absent.

**This document is planning output only** (hr-platform `CLAUDE.md`:
Claude's role here is planning/analysis/review; implementation is a
separate, explicitly assigned step). Repository owner confirmed
2026-08-08: adopt legacy schedule/payroll formulas as verified
(`hr-legacy` commit `d113204`), and build this foundation now rather
than deferring further.

Evidence: `employee_schedules`/`employee_shift_assignments`/
`workforce_planning` DDL read from `hr-legacy/mysql_workin.schema.sql`
lines 455-482, 939-948 (current `main`, commit `d113204`); consumer
logic read in full from `hr-legacy/apis/helpers/schedule_helper.php`
(493 lines); write path read from `hr-legacy/apis/api/schedules/assign_employee_schedule.php`
and `hr-legacy/apis/helpers/employee_create_helper.php:67-70,232-239`
(shift assignment happens as a required field at employee creation,
not a separate onboarding flow); legacy permission model confirmed via
`hr-legacy/mysql_workin.schema.sql:560-576` (`hr_permissions` columns)
— **no `can_schedules`/`can_employee_schedules` column exists in
legacy**; the three `apis/api/schedules/*.php` endpoints gate on
`requireAuth([COMPANY_ADMIN, HR, MANAGER])` role membership only, no
granular permission check. hr-platform current state read in full via
prior research (`Shift.java`, `ShiftRepository`, `ShiftService`,
`ShiftController`, `CompanySettings.java`/`CompanySettingsService`,
`PermissionKeys.java`, `V27`/`V29` migrations) — confirmed no
`shift_id` column on `employees`, no assignment/schedule table, no
`weeklyOffDays` fallback accessor on `EffectiveCompanySettings` despite
the column existing since V27.

## Scope

**In — schema:**

- `employee_shift_assignments` (date-effective history, mirrors
  legacy 1:1): `id`, `company_id BIGINT NOT NULL REFERENCES companies`
  (denormalized for RLS — legacy's table has no tenant column, same
  normalization organization-structure applied to `department_branches`),
  `employee_id BIGINT NOT NULL REFERENCES employees`, `shift_id BIGINT
  NOT NULL REFERENCES shifts`, `effective_from DATE NOT NULL`,
  `created_at TIMESTAMPTZ NOT NULL DEFAULT now()`. Index on
  `(employee_id, effective_from DESC)` for the "latest assignment on or
  before date" lookup (`schedule_shift_for_employee_on_date`).
- `employee_schedules` (per-employee per-date materialized/override
  rows): `id`, `company_id BIGINT NOT NULL REFERENCES companies`
  (same denormalization reasoning), `employee_id BIGINT NOT NULL
  REFERENCES employees`, `schedule_date DATE NOT NULL`, `name
  VARCHAR(255)`, `start_time TIME`, `end_time TIME`, `exception_note
  VARCHAR(255)`, `created_at TIMESTAMPTZ NOT NULL DEFAULT now()`.
  `UNIQUE (employee_id, schedule_date)` — legacy relies on `ON
  DUPLICATE KEY UPDATE` (MySQL upsert) against an implicit unique key
  on `(employee_id, schedule_date)`; Postgres needs it explicit for
  `ON CONFLICT` to work the same way.
- `company_settings.weekly_off_days` already exists (V27,
  `VARCHAR(60)`) but has no fallback-resolved accessor. Extend
  `EffectiveCompanySettings` with `weeklyOffDays` (fallback: empty —
  legacy's own fallback when the setting is unset, confirmed by
  `schedule_company_weekly_rest_dows` returning `[]` on empty input).
- RLS enable+force for both new tables, V14's pattern (same migration
  shape every prior slice used).

**In — endpoints** (package `com.workin.backend.schedule` — new
package, this is a new bounded concept, not an organization-structure
extension):

- Employee creation/update gains a required `shiftId` (mirrors
  legacy's required field at creation) — **open question, not decided
  here**: legacy requires `shift_id` on every employee create; does
  hr-platform want the same hard requirement, or optional-with-warning
  given `branchId`/`departmentId`/`jobTitleId` are all nullable on this
  platform? Flagging for the repository owner rather than assuming.
  Whichever answer, a new `EmployeeShiftAssignment` row is inserted
  (never updated in place) when an employee is created with a shift,
  or when update changes the shift — the date-effective history is
  legacy's actual behavior and should not be silently discarded.
- `GET /api/tenant/schedules/{employeeId}/monthly?year=&month=` — read
  model equivalent to `schedule_month_overview`: current shift summary
  (with computed `effectiveTo` — the day before the *next* assignment's
  `effectiveFrom`, or open-ended), this month's weekly-rest day names
  (shift `daysOff` ∪ company `weeklyOffDays`, deduplicated), this
  month's official holidays **once the holiday module exists** (see
  the holiday-credit design doc — until then, this returns an empty
  list, not an error), and a day-by-day array where manual
  `employee_schedules` rows win over computed-from-shift rows exactly
  as `schedule_compute_days_for_range` does (read-only, does not
  persist — legacy's live-compute path, not the pre-generation path).
- `POST /api/tenant/schedules/{employeeId}/assign` — mirrors
  `assign_employee_schedule.php`: `{shiftId, dates: [LocalDate]}`,
  tenant-validates both employee and shift, writes one
  `employee_schedules` row per date via upsert (`schedule_row_from_shift`
  snapshot, no exception note). This is a **materialization write**,
  distinct from changing the employee's ongoing assignment — legacy
  keeps these as two separate concepts (`employee_shift_assignments`
  = "what shift do they have going forward," `employee_schedules` =
  "what does this specific day actually look like, possibly
  overridden") and this slice preserves that split rather than
  collapsing it.
- `POST /api/tenant/schedules/{employeeId}/generate` — mirrors
  `generate_employee_schedule.php`/`schedule_generate_for_employee`:
  `{from, to}`, resolves the employee's shift assignment effective on
  `to`, deletes existing `employee_schedules` rows in range (legacy's
  `$replace_existing = true` is the only mode legacy's endpoint
  exposes), regenerates one row per day including weekly-rest/holiday
  exception labels.
- Access: legacy has no granular permission for this module (role-only
  gate). **Open question, not decided here**: hr-platform's
  authorization model is permission-key-based throughout (`ATTENDANCE_
  READ`/`_CORRECT`, `SHIFTS_READ`/`_MANAGE`, etc.) — does this module
  get new `SCHEDULES_READ`/`SCHEDULES_MANAGE` keys (consistent with
  every other module, but a real behavior change from legacy's
  role-only gate) or ride on the existing `SHIFTS_READ`/`SHIFTS_MANAGE`
  keys (closer to legacy's actual access shape, since schedules are
  shift-derived)? Recommend the former for consistency with every
  other slice in this codebase, but this is the repository owner's
  call, not inferable from either codebase.

**Out (tracked, blockers named):**

- `workforce_planning` (`company_id`, `branch_id`, `department_id`,
  `job_title_id`, `planned_count`) — a headcount-planning tool with no
  relationship to schedule computation; no consumer needs it yet.
  Legacy schema at `mysql_workin.schema.sql:939-948`.
- Holiday integration in the monthly-overview endpoint is a **stub**
  (empty list) until the official-holidays module (currently
  nonexistent in hr-platform, tracked separately) lands — documented
  above, not silently ignored.
- Manager-scoping semantics for who can assign/generate schedules for
  which employees (F-16, same open item organization-structure
  flagged for `departments.manager_id`).
- Notification-on-assign (legacy sends a push notification via
  `notification_to_employee`) — hr-platform has no notification
  infrastructure surfaced in this research; out until that exists.

## Design

New flat package `com.workin.backend.schedule`, following the
one-package-per-bounded-concept precedent
(`organization`, `payroll`). `EmployeeShiftAssignment`
entity/repository (append-only — no update/delete method, matching
legacy's actual usage: no endpoint ever updates or deletes an
assignment row, only inserts new ones); `EmployeeSchedule`
entity/repository with the upsert behavior expressed as
`findByEmployeeIdAndScheduleDate` + save-or-update in the service
(JPA has no native `ON CONFLICT`, so this is an explicit
read-then-write, guarded by the DB unique constraint as the real
backstop under concurrent writes — same pattern as every other
"tenant-scoped upsert" in this codebase should use, though none of
the prior modules research turned up an exact precedent to point to;
flagging as a design choice made here, not a copied convention).

`ScheduleService` holds the pure computation functions ported
1:1 from `schedule_helper.php`: `shiftForEmployeeOnDate`,
`daysOffToDaysOfWeek` (the Arabic/English/abbreviated day-name parser
— legacy's exact token map should be ported verbatim, including the
inconsistent-but-intentional handling of both `الأحد` and `الاحد` for
Sunday), `isWeeklyRestDay`, `monthlyOverview`. `ScheduleController`
exposes the three endpoints above.

`CompanySettingsService.effective()` gains `weeklyOffDays` in
`EffectiveCompanySettings` (fallback empty string/empty list — no
behavior change to existing callers since none currently read this
field).

## Testing

`ScheduleModuleFlowTest`: assignment history — assign shift A
effective day 1, shift B effective day 10, confirm
`shiftForEmployeeOnDate` returns A for days 1-9 and B from day 10
onward (the exact "latest row with effective_from <= date" rule);
monthly overview for an employee with no assignment → empty/null shift,
still returns holiday/day scaffolding without erroring; `assign`
endpoint round-trip — assign two dates, monthly overview shows manual
rows overriding computed ones; `generate` endpoint — deletes existing
rows in range first (regenerate is destructive by design, matching
legacy), weekly-rest days get the correct exception label from shift
`daysOff` ∪ company `weeklyOffDays`; day-name parser — every legacy
token (`Fri`, `friday`, `الجمعة` all resolve to the same day-of-week,
matching `schedule_parse_days_off_to_dows`'s exact map); foreign/
nonexistent employee or shift on assign → 404; cross-tenant → 404 +
list exclusion; unauthenticated → non-2xx. Whichever access-key
decision the owner makes (new `SCHEDULES_*` keys vs. reusing
`SHIFTS_*`), read-without-permission → 403 needs its own case.

## Consequences

Unblocks the attendance-calendar engine (needs `shiftForEmployeeOnDate`
+ `isWeeklyRestDay` to classify a day before it can decide
worked/rest/absent), the weekly-rest-credit rule (same dependency),
and payroll's real attendance-figures derivation (currently hardcoded
to "full attendance every day" in `PayrollBatchService.calculate`).
Two product decisions are named above as open (shift-required-at-creation,
permission-key shape) and are not resolved by this document — they
need the repository owner's explicit answer before the implementation
plan can be written with concrete interfaces, per `CLAUDE.md`'s
requirement to separate confirmed facts from open questions rather
than inventing an answer.

# Weekly-Rest Credit And Official-Holiday Credit — Design (2026-08-08)

## Purpose And Authority

Two legacy features share one rule — "was the employee present enough
this period to earn the credit" — documented together so that rule
isn't re-derived inconsistently across two specs. Neither exists in
hr-platform: no holiday module (no entity/controller/service/migration,
no `holidays.*` keys — confirmed absent from
`backend/src/main/java/com/workin/backend/authorization/PermissionKeys.java`
and `backend/src/main/resources/db/migration/common/V4__create_permission_catalog.sql`,
which stop at `shifts.read`/`shifts.manage`), and no weekly-rest logic
anywhere (grep across `backend/src` for holiday/weekly-rest terms
returns nothing).

**This document is planning output only** (hr-platform `CLAUDE.md`:
planning/analysis/review, not implementation).

Both features depend on the employee-schedule foundation
(`docs/superpowers/specs/2026-08-08-employee-schedule-foundation-design.md`,
package `com.workin.backend.schedule`, itself planning-only and
unimplemented — it supplies `shiftForEmployeeOnDate`/`isWeeklyRestDay`)
and, for day-classification shape, the attendance-calendar-engine spec
(expected at `docs/superpowers/specs/2026-08-08-attendance-calendar-engine-design.md`;
not confirmed to exist yet — see §Design). Payroll consumption is out
of scope by design (owned by
`docs/superpowers/specs/2026-08-08-payroll-attendance-reconciliation-design.md`),
but §4 documents the data shape payroll needs so the interfaces line up.

Evidence: full read of `hr-legacy/apis/helpers/weekly_rest_credit_helper.php`
(358 lines) and `hr-legacy/apis/helpers/official_holidays_helper.php`
(261 lines), `hr-legacy` `main` commit `d113204`; cross-referenced
consumption in `hr-legacy/apis/helpers/payroll_calculation.php` lines
277-443, 507-651; DDL for `company_official_holidays` from
`hr-legacy/mysql_workin.schema.sql` lines 244-250, 1030-1033; legacy
authorization gate from `hr-legacy/apis/api/company_official_holidays/*.php`
(all five endpoints) and `hr-legacy/apis/helpers/hr_permissions.php:170-174`;
corroborated by `hr-platform/docs/security/threat-model.md:58`.

## Scope

### In — official-holidays module (new CRUD)

- **Schema**, mirroring legacy 1:1 (`company_official_holidays`,
  `mysql_workin.schema.sql:244-250`): `id`, `company_id BIGINT NOT
  NULL REFERENCES companies` (legacy already has this column — no
  RLS denormalization needed here, unlike `department_branches`/
  `employee_shift_assignments`), `name VARCHAR(150) NOT NULL`,
  `holiday_date DATE NOT NULL`, `created_at TIMESTAMPTZ NOT NULL
  DEFAULT now()`. `UNIQUE (company_id, holiday_date)` (legacy index
  `uq_company_holiday_date`, `mysql_workin.schema.sql:1032`) —
  confirmed: one holiday row per company per calendar date. RLS
  enable+force, V14's pattern.
- **Endpoints** (new package `com.workin.backend.holidays`, template
  shapes, §8's 404s): `/api/tenant/holidays` list/get/create/update/
  delete. `name`, `holidayDate` required; a colliding date on
  create/update → the unique-constraint violation translated to 409
  (same pattern as organization-structure's delete-conflict handling).
  Cross-tenant get/update/delete → 404 + list exclusion.
- **New permission keys**: `holidays.read`/`holidays.manage`. This is
  a **deliberate departure from legacy's gate**: legacy has no
  holiday-specific flag — all five `company_official_holidays/*.php`
  endpoints call `require_company_settings_access($auth)`
  (`hr_permissions.php:170-174`), which checks the single
  `can_company_settings` boolean shared by the entire company-settings
  module. (Legacy's per-endpoint permission coverage is itself flagged
  as thin elsewhere — `docs/security/threat-model.md:58`.) Giving
  holidays its own CRUD keys matches every prior hr-platform slice's
  permission-key-per-module pattern; it is a proposed decision by
  precedent, not something the legacy code forces.

### In — weekly-rest-credit rule (pure computation, no schema)

No table of its own. Derived per (company, employee, date) from the
employee's schedule (`isWeeklyRestDay`, `shiftForEmployeeOnDate`),
attendance records over a lookback window, approved-leave requests,
and the holiday module's dates — ported 1:1 from
`weekly_rest_credit_helper.php`'s pure functions (§Design, rule quoted
in §3). No endpoint of its own: it's a computation payroll and the
attendance-calendar engine call into, not a standalone surface.

### Out (tracked, blockers named)

- Payroll's consumption of both outputs — owned by the
  payroll-attendance-reconciliation spec; this document documents
  only the shape (§4).
- The attendance-calendar engine's own classification algorithm —
  owned by its own (possibly not-yet-written) spec; treated here as
  an unconfirmed upstream dependency.
- Holiday-aware notifications — legacy's `company_official_holidays`
  CRUD is silent, no `notification_to_employee` call. Nothing to port.
- Recurring/yearly holiday templates — legacy stores one row per
  concrete date, no recurrence rule anywhere in the DDL or helper.
- A standalone weekly-rest diagnostic endpoint (see §Open Questions).

## Design

**Official-holidays module**: standard entity/repository/service/
controller quartet in `com.workin.backend.holidays`. Delete has no
downstream FK to protect (nothing in hr-platform's schema references
`company_official_holidays` by id, and legacy has no such reference
either — it's read only by date-range query), so delete is a plain
204; the unique-date collision is the only constraint-translated case.

**Weekly-rest-credit computation**: port into `com.workin.backend.schedule`
(recommended, since every function's first dependency is
`shiftForEmployeeOnDate`/`isWeeklyRestDay` — **not decided here**,
just the natural home) with the same semantics:

- `weeklyRestBlockStart` — walks backward while `isWeeklyRestDay`
  holds, returns the first day of the contiguous rest block containing
  the target date (`weekly_rest_credit_helper.php:29-45`). A block can
  span multiple days (e.g. a 2-day weekend); the whole block shares
  one eligibility computation.
- `weeklyRestWorkdaysBeforeBlock` — walks backward from the block
  start, collecting non-rest days until the previous rest day
  (`weekly_rest_credit_helper.php:53-76`). **Confirmed from the code,
  correcting the task brief's summary: official holidays are NOT
  excluded from this window.** The function's own comment says so
  explicitly: "Official holidays sit on calendar work slots but are
  not 'absences'. Still include them so leave/holiday coverage can
  earn the rest day." Whether a holiday date counts toward the 3-day
  threshold is decided separately, in the next function, by whether it
  looks "covered" — not by exclusion here.
- `weeklyRestCreditStatus` — the eligibility rule, quoted in §3.

**Shape needed from the attendance-calendar engine**: both legacy
helpers use small purpose-built lookups, not one unified "day
classification" object: `weekly_rest_attendance_flags_in_range` builds
`{date => {hasPunch, isExceptionOnly}}` from raw attendance rows,
expanding the query 7 days before the requested range so a week
starting earlier still resolves (`weekly_rest_credit_helper.php:301-358`);
`attendance_is_on_approved_leave(employeeId, date)` is a separate
per-date lookup against `requests`/`request_types`
(`attendance_calendar_helper.php:669-683`); the holiday-credit
suppression check (§3) needs a third shape, a raw present-day *count*
over a range, not a per-day map. This document does not assume the
engine's eventual internal representation — it lists these three real
call shapes so that spec can design its API against actual callers.

## The Shared "≥3 Days" Rule — Quoted, And Where It Actually Differs

Both features gate a credit on the same constant:

```php
// weekly_rest_credit_helper.php:24
const WEEKLY_REST_MIN_COVERED_WORKDAYS = 3;
```

**Weekly-rest eligibility** (`weekly_rest_credit_helper.php:83-146`)
uses it as a per-week threshold over the specific workdays immediately
preceding *this* rest block:

```php
// weekly_rest_credit_helper.php:126-134
// Official holidays do not count toward the 3-day coverage threshold.
if ($worked || $exception_cover || $on_leave) {
    $covered++;
}
...
if ($covered >= WEEKLY_REST_MIN_COVERED_WORKDAYS) {
    return WEEKLY_REST_CREDIT_EARNED;
}
```

A holiday date can appear in the window (per §Design) but never
satisfies `$worked`/`$exception_cover` alone (no punch, no exception
row); it only counts if the employee also happened to be on approved
leave that day — a coincidence, not a rule about holidays. So the
task brief's summary ("holidays excluded") is *practically* right but
the mechanism is "a holiday alone doesn't satisfy any counted
condition," not an explicit skip — a port that assumed holidays could
never enter the window at all would be a bug.

**Official-holiday credit suppression** uses the identical constant
and operator but a **different population and window** — and it is
not inside `official_holidays_helper.php` at all (verified by reading
the full 261-line file: no threshold logic anywhere in it). It's
enforced twice, independently, by the consumer, `payroll_calculation.php`:

```php
// payroll_calculation.php:315-318
// Prefer not to credit official holidays when the employee has barely worked.
if ($days_present < WEEKLY_REST_MIN_COVERED_WORKDAYS) {
    $holiday_credit = 0;
}
```

```php
// payroll_calculation.php:426
if ($punch_present >= WEEKLY_REST_MIN_COVERED_WORKDAYS) {
    foreach (official_holidays_working_credit_details_for_employee(...) as $row) { ... }
}
```

`$days_present`/`$punch_present` are
`payroll_attendance_summary($employeeId, $periodFrom, $rangeTo)['days_present']`
(`payroll_calculation.php:672-738`) — a count of **distinct dates with
any attendance row (including exception-only) across the entire
elapsed payroll period so far**, not a specific preceding week.

**Confirmed, correcting the task brief**: same constant and comparison
direction, genuinely different rule. Weekly-rest is a
3-of-the-specific-preceding-block threshold restricted to
worked/exception/leave. Holiday-credit suppression is a
3-anywhere-in-the-elapsed-period-to-date raw distinct-attendance-day
count, with no leave distinction. They are two independent uses of one
number that happen to coincide, not the same rule applied twice. A
hr-platform port may share the constant's *value* but must not share
its counting logic or window between the two features.

## Interface Shape For Payroll Reconciliation (illustrative, not decided)

Per-day classification is what payroll consumes, sketched here as a
concrete target for the payroll-reconciliation spec to react to or
override — **not a committed API**:

```java
public record HolidayCreditResult(
    int workingHolidayCount,     // official_holidays_working_credit_for_employee
    List<HolidayCreditDay> days  // official_holidays_working_credit_details_for_employee
) {}
public record HolidayCreditDay(LocalDate date, String label) {}

public enum WeeklyRestCreditStatus { EARNED, VOID, PENDING }
public record WeeklyRestCreditResult(LocalDate restDate, WeeklyRestCreditStatus status) {}
```

The suppression gate (`days_present < 3` ⇒ zero holiday credit) is
**payroll's business, not this feature's** — the holiday service
should expose the unsuppressed count/detail list (mirroring
`official_holidays_working_credit_for_employee`, which itself performs
no suppression), letting the reconciliation spec decide explicitly
whether to keep the whole-period-count gate as-is, change its window,
or drop it. Baking the gate into the holiday service would hide a
payroll-specific policy inside an otherwise pure query.

## Testing

`HolidayModuleFlowTest`: admin CRUD round trip; duplicate
`(company, holidayDate)` on create/update → 409; cross-tenant
get/update/delete → 404 + list exclusion; read-without-`holidays.read`
→ 403; unauthenticated → non-2xx.

`WeeklyRestCreditServiceTest` (pure-function unit tests): block-start
walks back across a multi-day rest block; workdays-before-block
includes a holiday date inside the window, counting only when also on
approved leave (confirming §3); status is `EARNED` at exactly 3
covered days, `VOID` at 2 covered with the window fully past,
`PENDING` at 2 covered with the window still partly future and also
for a rest date after `asOf`; empty preceding-workday list (schedule
edge) returns `EARNED` per documented legacy behavior
(`weekly_rest_credit_helper.php:105-108`) — flag in review, since "no
evidence either way, so credit" is a real product choice, not an
obviously-correct default.

`HolidayCreditSuppressionTest`: confirm the port keeps the
whole-elapsed-period `days_present` count (not a windowed one) as the
gate population, at the exact boundary (2 → suppressed, 3 → not),
matching both legacy call sites' current behavior — until the
payroll-reconciliation spec makes its own explicit call on this gate.

## Consequences

Holidays becomes a real CRUD module with its own permission surface —
the first module in this family to get dedicated `holidays.read`/
`holidays.manage` keys rather than riding on another module's flag, an
improvement over legacy's shared `can_company_settings` gate.
Weekly-rest credit becomes a pure, testable computation with no schema
footprint, ported with its actual semantics (holidays enter the
coverage window but rarely satisfy it alone) rather than the task
brief's simplified summary. The payroll-reconciliation spec inherits a
concrete, evidence-based interface shape and one explicit unresolved
decision (§4's suppression gate) instead of re-deriving both from PHP.

## Open Questions (not decided here)

- **Whole-period vs. per-week suppression window for holiday credit**:
  is `days_present < 3` over the entire elapsed period the intended
  long-term rule, or an artifact of it being cheap to compute from a
  value payroll already had on hand? Owned by the payroll spec, but
  should be asked as a real product question, not silently ported.
- **Weekly-rest block spanning a fiscal-period boundary**: neither
  helper references `period_from`/`period_to` — the block walk is
  purely calendar-based. Payroll's range functions
  (`weekly_rest_earned_days_in_range`/`weekly_rest_void_days_in_range`)
  cap `to` at the period end or `as_of`, but the underlying per-block
  functions (`weekly_rest_block_start`/`weekly_rest_workdays_before_block`)
  take no period bound at all, so a block whose preceding workdays
  fall in the *previous* period appears to resolve correctly in
  isolation — but whether the range functions' capped `to` ever clips
  a block's own workday window (vs. only which rest dates get
  enumerated) was not traced end-to-end. Flagging as unverified.
- **Timezone handling**: every date in both files is a bare `Y-m-d`
  string or a `DateTimeImmutable` built from one, with no timezone
  parameter anywhere. `date('Y-m-d')` (the `$as_of` default) resolves
  in PHP's configured default timezone, not traced in this research.
  hr-platform's `LocalDate` port removes the ambiguity going forward,
  but "the preceding week" boundary should not be assumed UTC without
  independent confirmation.
- **Package placement of the weekly-rest computation** (`schedule` vs.
  a new package) — noted undecided in §Design; affects import
  direction for the attendance-calendar-engine and
  payroll-reconciliation specs.
- **Standalone weekly-rest diagnostic endpoint** — plausible support
  value (an admin asking "why wasn't this employee's rest day paid")
  but not requested by any confirmed source; not resolved either way.

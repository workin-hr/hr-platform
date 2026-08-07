# Requests & Leave Balances — First Slice Design (2026-08-07)

## Purpose And Authority

Wave 4 opener: the leave/permission request workflow and per-year
leave balances — the modules whose approval side effects feed
attendance exceptions (shipped earlier today) and, later, payroll's
paid-leave accounting. Assigned by the repository owner 2026-08-07
("proceed with the next steps"); two scope choices confirmed by the
owner the same day: **HR create-on-behalf** (new capability gated by
a new `requests.manage` key — legacy request creation is
EMPLOYEE-only self-service, deferred codebase-wide) and **both
approval side effects** (leave deduction and attendance-exception
creation).

Evidence beyond the docs: `mysql_workin.schema.sql` and
`apis/helpers/request_actions_helper.php` fetched from
`workin-hr/hr-legacy` at the pinned Discovery commit `83c326e` and
read in full — the approval semantics below are that helper's actual
behavior, not inference.

## Scope

**In — schema (V25/V26, V13's conventions):**

- V25: catalog row `requests.manage` (+ `COMPANY_ADMIN` bundle row;
  `requests.read`/`requests.approve`/`leave_balances.*` already
  exist), plus three tables translated from the legacy DDL:
  - `request_types`: `id`, `company_id`, `name`, `is_active` (default
    true), `deduct_balance` (default false), `counts_as_paid_leave`
    (default true), `add_attendance_exception` (default false),
    `exception_type_id` NULL FK → `exception_types`, `created_at`.
  - `requests`: `id`, `employee_id`, `company_id` (denormalized for
    RLS — legacy scopes via an employees join), `request_type_id`
    NOT NULL FK, `from_date`/`to_date` NOT NULL, `from_time`/
    `to_time` NULL, `notes`, `status` CHECK
    (`PENDING`/`APPROVED`/`REJECTED` — uppercase per the advances
    precedent), `reply`, `approver_membership_id` NULL FK →
    `tenant_memberships` (normalization: legacy stored an employee
    id; the new surface's actors are memberships), `decided_at`
    NULL, `created_at`. Legacy's `updated_at` is not carried (no
    new-schema precedent has one).
  - `leave_balances` (plural, per new-schema naming): `id`,
    `employee_id`, `company_id`, `year` SMALLINT (the inventory's
    `year(4)` mapping), `period_from_month`/`period_to_month`
    (defaults 1/12), `monthly_cap_days` NUMERIC(5,2) NULL,
    `total_days`/`used_days` NUMERIC(5,1) default 0,
    `remaining_days` NUMERIC(5,1) generated always as
    `total_days - used_days`, stored (transcribed, as the schema
    inventory directs), `created_at`, and a real
    `UNIQUE (employee_id, year)` — legacy assumes one row per
    (employee, year) app-level only (`get_one`); same
    fix-by-construction move as hr-legacy#21.
- V26: RLS enable+force for all three (V14's pattern).

**In — endpoints** (template shapes, §8's 404s):

- `/api/tenant/request-types`: `GET` list (`requests.read`), `POST`
  (`requests.manage`) — `name` required; toggles optional with the
  DDL defaults; `exceptionTypeId` accepted only when
  `addAttendanceException` is true (legacy nulls it otherwise) and
  must be a tenant-owned exception type (foreign/nonexistent → 404).
  Update/delete deferred (exception-types precedent).
- `/api/tenant/requests`: `GET` list (optional `employeeId`,
  `status`) + `GET /{id}` — `requests.read`. `POST`
  (`requests.manage`): `employeeId`, `requestTypeId`, `fromDate`,
  `toDate` (≥ `fromDate`, else 400), optional `fromTime`/`toTime`/
  `notes`; **always starts `PENDING`** (recorded decision — decisions
  happen only through approve/reject, unlike legacy advances'
  create-with-any-status). `PUT /{id}` and `DELETE /{id}`
  (`requests.manage`): pending-only, non-pending → 409 (legacy's own
  rule for employee self-edit, applied to the new surface).
- `PUT /{id}/approve` (`requests.approve`, optional `reply`) —
  legacy `request_approve()` ported exactly:
  1. Not found → 404; non-pending → 409.
  2. If the type's `deduct_balance`: days = inclusive
     `to_date - from_date + 1` (min 1), year = `from_date`'s year
     (multi-year ranges attribute every day to the from-year —
     legacy quirk, ported and documented); if a balance row for
     (employee, year) **exists** and `remaining_days < days` → 422.
     **No balance row → the check passes** (legacy quirk, ported: the
     side effect then auto-creates the row, possibly into negative
     remaining).
  3. In one transaction: status → `APPROVED`, `reply`, `decided_at`,
     `approver_membership_id`; then side effects:
     - deduction: existing row → `used_days += days`; missing row →
       insert with `total_days = 21.0` and `used_days = days`. The
       21.0 is legacy's fallback for the `MONTHLY_LEAVE_ACCRUAL`
       company setting — the settings module doesn't exist, so the
       constant is the whole story for now (recorded decision,
       revisit with company-settings).
     - attendance exceptions (when the type's
       `add_attendance_exception`): one exception row per calendar
       day in [from, to], **skipping any day where the employee
       already has an attendance row** (legacy rule); rows use the
       new attendance module's own convention — UTC-midnight
       check-in, null checkout/method — not legacy's 09:00/`'app'`
       quirk (recorded normalization). A type with
       `add_attendance_exception` but a null `exception_type_id`
       skips the side effect entirely (legacy has a company-default
       fallback resolver that was not read this pass — open
       question, conservative skip until read).
- `PUT /{id}/reject` (`requests.approve`): `reply` required
  (legacy's rejection_reason) → 400 blank; pending-only → 409; no
  side effects.
- `/api/tenant/leave-balances`: `GET` list (optional `employeeId`,
  `year`) + `GET /{id}` — `leave_balances.read`. `POST`
  (`leave_balances.manage`): `employeeId`, `year`, `totalDays`,
  optional period months/cap; duplicate (employee, year) → 409 via
  the real constraint. `PUT /{id}`: `totalDays`/period/cap only —
  **`used_days` is not settable through this surface** (recorded
  decision: it belongs to the approval side effect; manual
  correction waits for a real requirement). Delete deferred.

**Out (tracked, blockers named):** employee self-service
(create/list own — the codebase-wide identity-link blocker);
notifications on decision (no notifications module); `counts_as_paid_leave`
consumption (payroll reads manual `AttendanceInput` until the
attendance-derivation slice); leave-balance `generate.php` bulk
creation and the Excel trio; `stats.php`; request-type
update/delete; manager approve/reject scoping (hr-legacy#18 —
structurally parked like #17: the MANAGER bundle is empty and
`membership_resource_scopes` administration is F-16/F-25's
product-gated work); the company-default exception-type fallback
resolver (open question above).

## Design

Package `com.workin.backend.requests` (request types + requests —
one package, they change together) and the `leave_balances` surface
in the same package (the deduction side effect couples them;
separate `LeaveBalance*` classes). Template shapes throughout:
entities, `findByIdAndCompanyId` repositories, services returning
sealed results (`NotFound` | `WrongState` | `InsufficientBalance` |
`Duplicate` | `Done`), controllers mapping 404/409/422/409/2xx. The
approve transaction touches `AttendanceRepository` directly for the
skip-check and row inserts (same-package reuse is the payroll→
advances/penalties precedent). No new audit surface — §9 lists
request decisions nowhere; `tenant_audit_events` stays
member-administration's.

## Testing

`RequestModuleFlowTest`: request-type create/list (+ toggles round
trip, exceptionTypeId nulled without the flag, foreign/nonexistent
exception type → 404); request create→get→update→delete round trip,
always-PENDING, `toDate < fromDate` → 400; pending-only: edit and
delete after decision → 409; approve: happy path sets
status/reply/decided_at/approver; deduction against an existing
balance (used_days asserted); auto-created balance (total 21.0,
used = day count — SQL-asserted); insufficient → 422 with an
existing low balance; **no-row + oversized request approves into
negative remaining** (the ported quirk, locked in); exception rows
created per day at UTC midnight skipping a seeded mid-range
attendance day; no exception rows when the type lacks the flag or
the mapping; approve/reject non-pending → 409; reject blank reply →
400; F-18: cross-tenant get/update/delete/approve/reject → 404 and
list exclusion, foreign `employeeId`/`requestTypeId` on create →
404; `requests.read` alone → 403 on create and approve;
`requests.manage` alone → 403 on approve; unauthenticated → non-2xx.

`LeaveBalanceFlowTest`: create/list/get/update round trip;
duplicate (employee, year) → 409; `usedDays` absent from the update
surface (no request field); cross-tenant → 404s + list exclusion;
foreign/nonexistent employee create → 404; read-without-manage →
403; unauthenticated → non-2xx.

## Consequences

The request→attendance-exception pipeline is live end to end, so
approved leave shows up in the attendance data payroll will
eventually derive from. The remaining legacy request surface
(self-service, notifications, stats) layers on top without schema
change. `hr-legacy#18` stays parked exactly like #17 until F-16's
product decision.

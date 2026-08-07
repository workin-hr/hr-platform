# Attendance Module — First Slice Design (2026-08-07)

## Purpose And Authority

Fourth business module, wave 3 of
`docs/migration/migration-strategy-and-sequencing.md`'s proposal, by the
employees/advances/penalties template. Assigned by the repository owner
2026-08-07 ("accepted, can proceed"); slice-scope choices (punches +
exception days; drop the clear-equals-delete convention) confirmed by
the owner the same day. Unlike prior modules, no schema exists yet —
this slice includes the migrations (V21/V22). V4 already carries the
two keys (`attendance.read`, `attendance.correct`, mapped from legacy
`can_attendance`).

The slice carries the module's two load-bearing documented rules
(`docs/legacy/business-rule-extraction.md`):

- **Minimum 2-hour gap between consecutive check-ins** — legacy
  enforces the same `TIMESTAMPDIFF < 120` guard on HR manual entry
  (`create.php` lines 67–73) as on self check-in, so the rule belongs
  to this admin surface, not just the deferred self-service one.
- **Punches XOR exception day** — a row is either real punches
  (`check_in`/`check_out` timestamps) or a category-only exception day
  (`exception_type_id` set, `check_in` at that day's midnight,
  `check_out` always null), never both; enforced identically in
  legacy's `create.php` and `update.php`. Here it becomes a real DB
  CHECK constraint, not just app code (same fix-by-construction move
  as hr-legacy#21's batch uniqueness).

## Scope

**In — schema** (per V13's conventions: BIGINT IDENTITY, denormalized
`company_id` for RLS, TIMESTAMPTZ, indexes on `employee_id`/`company_id`):

- V21: `exception_types` (`id`, `company_id`, `name`) — the minimal
  lookup the XOR needs; legacy's table is company-defined categories
  (~119 rows). And `attendance` (`id`, `employee_id`, `company_id`,
  `check_in` NOT NULL, `check_out` NULL, `method` NULL with
  CHECK IN ('app','excel','qr'), `latitude`/`longitude` NULL pair,
  `exception_type_id` NULL FK, `created_at`), with
  `CHECK (exception_type_id IS NULL OR check_out IS NULL)` — the XOR's
  DB-enforceable half; midnight-forcing for exception rows stays
  app-level.
- V22: RLS enable+force for both tables, V14's exact pattern.

**In — endpoints** (template service pattern, §8's 404s):

- `/api/tenant/exception-types`: `GET` list (`attendance.read`),
  `POST` create (`attendance.correct`, `name` required). Update/delete
  deferred (referential-integrity questions don't block the slice).
- `/api/tenant/attendance`: `GET` list (optional `employeeId`,
  optional `from`/`to` date range) + `GET /{id}` — `attendance.read`.
  `POST`/`PUT /{id}`/`DELETE /{id}` — `attendance.correct`.
- Create/update accept exactly one of two shapes, mirroring the XOR:
  **punch** (`employeeId`, `checkIn` required, `checkOut` optional,
  `method` required, GPS pair optional) or **exception**
  (`employeeId`, `date`, `exceptionTypeId` — stored as midnight
  check-in, null checkout). Mixed or empty shapes → 400. An update
  that would clear both punches without an exception is a 400, not a
  silent row delete — legacy `update.php`'s clear-equals-delete
  convention is a **deliberate non-port** (owner-confirmed): it
  existed because update was the only path; the new surface has a
  real `DELETE`.
- 2-hour gap: a punch create/update whose `checkIn` lands 0–119
  minutes **after** the employee's latest earlier punch `check_in`
  (other rows only) → 409; legacy's guard formula
  (`minutes_since_last >= 0 && < 120`) only fires in that direction,
  so a backdated entry more than 120 minutes before an existing punch
  is not the guard's concern here either. Two recorded decisions, not legacy facts:
  exception rows are excluded from the guard (it is an
  anti-double-punch control; legacy's depth here is undocumented),
  and 409 replaces legacy's `422 INVALID_INPUT` (consistency with
  this codebase's state-conflict convention; the Arabic-string 422
  contract matters only to the deferred mobile check-in surface).
- Foreign/nonexistent `employeeId` or `exceptionTypeId` → the same
  404, per F-18.

**Out (tracked, with their blockers)**: self check-in/check-out,
geofencing, and QR (need `branches` + the employee↔identity link every
module defers, plus product answers F-04/hr-legacy#16); `delete_range`
bulk delete (hr-legacy#25 product decision on dry-run/audit);
stats/reports/`fill_days` list mode (hr-legacy#24's placeholder-field
cleanup rides along); Excel import/analyze (dedicated read-through
flagged in the endpoint inventory); manager visibility (hr-legacy#17 —
structurally parked: the MANAGER role bundle is empty by default, so
nothing leaks while F-16 awaits its product decision); payroll's
attendance-driven absence figures (payslips keep their manual
`AttendanceInput` until a later slice wires real derivation).

## Design

Package `com.workin.backend.attendance`, exactly the penalties shapes:
entities `ExceptionType` and `Attendance`; repositories with
`findByIdAndCompanyId` + scoped list queries; services returning
result objects (`NotFound` | `GapViolation` | `Done`); controllers
mapping to 404/409/2xx. No lock analogous to penalties'
`applied_to_payroll` exists — payslips snapshot their day counts at
calculate time, so later attendance edits don't retroactively alter
finalized payroll (and legacy has no such lock either).

## Testing

`AttendanceModuleFlowTest` (+ the schema piggybacks on
`PayrollGroupRlsTest`'s pattern only if needed — RLS behavior is
asserted through the API tests): admin round-trip for both shapes
(punch create/update, exception create); XOR violations → 400 (mixed
shape, empty shape, clear-both-punches update); gap rule → second
punch within 120 min → 409, at/after 120 min → 2xx, exception row
within the window → 2xx (excluded from guard); foreign/nonexistent
employee and exception type on create → indistinguishable 404s;
cross-tenant get/update/delete → 404 and list exclusion for both
attendance and exception-types; a cross-tenant `exceptionTypeId`
reference on create → 404; read-without-correct → 403 on create;
unauthenticated → non-2xx.

## Consequences

Wave 3 opens with the payroll group's upstream data source in place.
The deferred self-service surface, when branches and identity links
exist, adds endpoints on top of this schema without changing it — the
2-hour guard and XOR already live in the service layer it will share.

# Existing Endpoint Inventory

## Scope Of This Pass

`workin-hr/hr-legacy` has 199 API endpoint files (see
`docs/legacy/existing-php-module-inventory.md` for the full module
breakdown). This pass documents the three endpoints read in full detail
below — the core auth and attendance path, the highest-traffic and
highest-risk module by row count (see the schema inventory). The
remaining 196 endpoints are inventoried structurally (module, file count,
purpose) in the module inventory, not individually here yet. Do not read
this document as complete endpoint coverage.

Consumer note: no mobile/desktop client source was available in this
pass — every "Consumer" field below is inferred from the API's own
`AuthTypeEnum` roles (`employee`, `company`, `desktop` per
`apis/api/auth/login_desktop.php`'s existence) and the API's request/
response shape, not confirmed against real client code. Marked
accordingly per entry.

Source: `workin-hr/hr-legacy` commit `83c326e40f68dd0d560595a6c4e465eb681f2ce8`.

---

## Endpoint: `POST /api/auth/login_employee`

**Consumer:** Inferred — mobile/desktop employee client (not confirmed
against client source; inferred from `AuthKey::TYPE` and `AuthTypeEnum::EMPLOYEE`
appearing in the response/session logic).

**Request Shape:** JSON body, required fields `phone` (string) and
`password` (string). No headers required beyond standard content-type —
this is the unauthenticated entry point.

**Response Shape:** On success: `{ token: <JWT>, employee: <public_row> }`.
`public_row()` is a shared redaction helper (`apis/helpers/public_row.php`,
not read in this pass) — the exact field list returned is not yet
confirmed, only that a redaction step exists rather than returning the
raw `employees` row (which would include `password_hash`).

**Error Behavior:** All failures are 401/403/404/409, never a generic 500
for expected cases:

- `401 USER_NOT_FOUND` — no employee row for that phone at all
- `401 INCORRECT_PASSWORD` — row(s) found, none password-match
- `409 MULTIPLE_ACCOUNTS_SAME_PHONE` — more than one login-ready or
  more than one pending account matches (see
  `docs/legacy/business-rule-extraction.md`)
- `403 COMPANY_ACCOUNT_NOT_ACTIVE` / `403 EMPLOYEE_ACCOUNT_NOT_ACTIVE` /
  `403 ACCOUNT_DEACTIVATED_ENTER_CODE` — matched but not eligible to log
  in, for different specific reasons
- A single `pending`-status match is treated as a *success* (not an
  error) with a token issued — this is a real, easy-to-miss branch, not
  an oversight to "fix" during migration without checking with someone
  who understands why pending accounts get a token.

**Evidence:** `apis/api/auth/login_employee.php`, full file, read directly.

---

## Endpoint: `POST /api/attendance/check_in`

**Consumer:** Inferred — mobile client (self check-in) and dashboard/HR
client (checking in on behalf of another employee) — the endpoint
explicitly branches on `is_self_attendance` (see Request Shape), which
only makes sense if both consumer types are real.

**Request Shape:** JWT bearer auth required. JSON body, required:
`latitude`, `longitude`, `method` (free-form in validation, but
`AttendanceMethodEnum` constrains it to `app`/`excel`/`qr` elsewhere in
the schema). Optional `employee_id` — if omitted, defaults to the
authenticated employee (self check-in); if provided and different from
the authenticated employee, this is an HR/admin-initiated check-in for
someone else, which changes several downstream rules (mobile-attendance
opt-out check is skipped, geofencing requirement is relaxed — see
business rules).

**Response Shape:** `{ attendance_id: <int>, time: <Y-m-d H:i:s string> }`.

**Error Behavior:**

- `405` wrong HTTP method
- `403 MOBILE_ATTENDANCE_DISABLED` — self check-in attempted by an
  employee with that feature turned off
- plain failure (no specific code path documented in this pass) —
  `ALREADY_CHECKED_IN` if an open session already exists
- `422 INVALID_INPUT` with `field`/`reason` payload — the 2-hour
  minimum-gap rule (see business rules), with a fixed Arabic reason
  string, not a translation key
- geofencing failures bubble up from
  `validate_employee_attendance_location()` — `BRANCH_LOCATION_NOT_CONFIGURED`
  (403), `EMPLOYEE_BRANCH_REQUIRED` (403), `BRANCH_NOT_FOUND` (404), or
  `OUT_OF_RANGE` (400) with `{ dist, radius }` in the payload (both in
  meters, `dist` rounded to the nearest integer)

**Evidence:** `apis/api/attendance/check_in.php` and
`apis/helpers/attendance_location_helper.php`, both read in full.

---

## Endpoint: `POST /api/attendance/check_out`

**Consumer:** Same as check-in — self and HR/admin-on-behalf-of.

**Request Shape:** JWT bearer auth required. JSON body, `employee_id`
optional (same default-to-self behavior as check-in). GPS
(`latitude`/`longitude`) is only required for self-checkout — an
HR-initiated checkout does not re-validate location at all
(`if ($is_self_attendance) { ...validate location... }`, no `else`
branch). If GPS is omitted on a self-checkout, the code falls back to the
*check-in* coordinates rather than failing
(`parse_request_gps_coordinates($body, $open_attendance_row)`).

**Response Shape:** `{ duration_minutes: <int>, time: <Y-m-d H:i:s string> }`.
Duration is computed in PHP (`time() - strtotime(check_in)`), not by a
SQL `TIMESTAMPDIFF` — worth noting only because check-in's own
too-soon-again check *does* use `TIMESTAMPDIFF` in SQL; the two related
endpoints compute elapsed time two different ways.

**Error Behavior:** `NO_OPEN_CHECK_IN` if there is no open session to
close (i.e. `check_out IS NULL` row) for that employee. Same
geofencing failure codes as check-in when GPS validation runs.

**Evidence:** `apis/api/attendance/check_out.php`, full file, read
directly.

---

## Remaining Endpoints (not individually documented in this pass)

See `docs/legacy/existing-php-module-inventory.md` for the full 38-module,
199-file breakdown (module name, entry-point count, business domain). The
payroll module (`payroll_batches`, `payslips` — 16 endpoint files
combined) is the next highest-priority target for individual
documentation, given the payroll calculation engine already documented in
`docs/legacy/business-rule-extraction.md` is the highest-risk business
logic found so far.

## Evidence

Files cited individually per endpoint above, all from `workin-hr/hr-legacy`
commit `83c326e40f68dd0d560595a6c4e465eb681f2ce8`.

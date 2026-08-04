# Existing Endpoint Inventory

## Scope Of This Pass

`workin-hr/hr-legacy` has 199 API endpoint files (see
`docs/legacy/existing-php-module-inventory.md` for the full module
breakdown). This pass documents: `auth/login_employee` (1 of 14), all 15
`attendance` endpoints, all 16 `payroll_batches`/`payslips` endpoints, and
all 20 `advances`/`penalties`/`salary_contracts` endpoints — 52 endpoints
total. The remaining ~147 endpoints are inventoried structurally (module,
file count, purpose) in the module inventory, not individually here yet.
Do not read this document as complete endpoint coverage.

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

## Payroll Batches (`apis/api/payroll_batches/`, 10 endpoints)

**Consumer (all 10):** Dashboard/HR client only — every endpoint in this
module requires `COMPANY_ADMIN` or `HR` role (`requireAuth([UserRoleEnum::COMPANY_ADMIN, UserRoleEnum::HR])`),
never `MANAGER` or `EMPLOYEE`. No self-service surface exists for batches.

**ID convention:** `id` is read from `$_GET[Request::ID]` on every
mutating endpoint, including the `PUT`/`POST` ones (`calculate.php`,
`finalize.php`, `reopen.php`, `update.php`) — the resource id travels in
the query string even when the HTTP verb implies a body-based request.
Consistent across the module, so treat it as a real convention, not a bug
in one file.

| Endpoint | Method | Notes |
|---|---|---|
| `create.php` | POST | Creates a `draft` batch for `(company_id, month, year)`. Uniqueness is enforced only by an app-level `SELECT COUNT(*)` check before the `INSERT` — **no database unique constraint backs it** (confirmed against the schema inventory: `payroll_batches` has no unique key on that triple). Two concurrent create requests for the same company/month/year can both pass the check and both insert, producing duplicate batches. |
| `calculate.php` | POST | Runs `payroll_calculate_batch()` — the full destructive delete-and-reinsert described in the business-rule extraction. **Not wrapped in a DB transaction** at the endpoint level, unlike `finalize.php`/`reopen.php` below. A failure partway through leaves the batch with a partial payslip set. |
| `finalize.php` | PUT | Wrapped in `beginTransaction`/`commit`/`rollBack`. Side effects: marks matching penalties `applied_to_payroll=1`, applies advance deductions to `advances.remaining`. Blocked for already-finalized batches. |
| `reopen.php` | PUT | Wrapped in `beginTransaction`/`commit`/`rollBack`. Reverses both finalize side effects and sets status back to `draft`. |
| `delete.php` | DELETE | Draft-only. Manually runs `DELETE FROM payslips WHERE batch_id=?` before deleting the batch row, despite `payslips.batch_id` already being `ON DELETE CASCADE` in the schema — redundant but harmless given both statements run before any commit boundary. |
| `update.php` | PUT | Draft-only mutation lock — same pattern as `delete.php`/`calculate.php` (any endpoint that mutates a batch checks `status !== 'finalized'` first). |
| `list.php` | GET | Paginated; filters on status/year/search. |
| `one.php` | GET | Fetches via `get_payroll_batch_with_stats()` — a shared helper, not an inline query (contrast with `payslips/one.php`, `payslips/list.php` below, which build their SELECT inline but do call the shared computed-totals SQL fragment). |
| `stats.php` | GET | Aggregate SQL built from the same shared computed-total column expressions used elsewhere (see below), not a separate reimplementation. |
| `fiscal_period.php` | GET | Thin preview wrapper around `payroll_fiscal_period_bounds()` (see business rules) — lets the dashboard show a batch's date range before creating it. |

## Payslips (`apis/api/payslips/`, 6 endpoints)

**Consumer:** Mixed, unlike payroll batches. `create.php`, `update.php`,
`delete.php` are `COMPANY_ADMIN`/`HR` only (management/write). `list.php`,
`one.php`, `export.php` additionally allow `MANAGER` and `EMPLOYEE` —
employees can read their own payslips (never write them). Three different
techniques enforce the "own payslips only" restriction for employees, all
read directly rather than assumed identical:

- `list.php` — silently *overwrites* the `employee_id` filter with the
  caller's own id when `role === EMPLOYEE`, ignoring whatever was
  requested (no error, just scope narrowing).
- `one.php` — fetches the payslip first (scoped only to `company_id`),
  then explicitly checks `payslip.employee_id === auth.employee_id` and
  returns `403 FORBIDDEN` if not — the one endpoint here that can
  distinguish "not yours" from "doesn't exist."
- `export.php` — delegates to `data_export_payslips_csv()`
  (`apis/helpers/data_export_helper.php`), which adds `p.employee_id = ?`
  to the SQL `WHERE` clause itself when `role === EMPLOYEE`, before any
  rows are fetched.

**Finding — three independent implementations of the same payroll math
exist across this module, not one shared calculation reused everywhere:**

1. **`apis/helpers/payroll_calculation.php`**'s shared SQL fragment
   (`sql_payslip_select_with_computed_totals()`,
   `sql_payslip_total_entitlements()`, `sql_payslip_total_deductions()`)
   plus `payroll_enrich_payslip_row()` for attendance display — used by
   `list.php`, `one.php`, `stats.php`, and `payroll_batches/one.php`. This
   is the canonical, single-source path.
2. **`payslips/update.php`** (manual HR override of a single payslip)
   independently re-derives gross/day-rate/absence-cost/overtime/net in
   inline PHP using the same `PENALTY_CALENDAR_DAYS_PER_MONTH`-based
   formula as the batch engine, rather than calling a shared compute
   function. A change to the batch-engine formula would not automatically
   apply here.
3. **`payslips/create.php`** (manually adding one payslip to an existing
   batch) uses a **materially simpler, different formula**:
   `net_salary = (basic_salary + allowances + overtime_pay) - (penalties_total + advance_deduction + other_deductions)`,
   where `allowances` is the sum of transport/food/risk/incentives from
   the salary contract. It does **not** compute a day-rate or an
   absence-cost from `days_absent` at all — `penalties_total` is taken
   directly from the request body rather than derived from `days_absent ×
   day_rate` the way the batch engine and `update.php` both do it. A
   payslip created through this endpoint and one produced by
   `calculate.php` for an otherwise-identical employee can diverge simply
   because one path skips the absence-cost step entirely.

**Finding — `payslips.allowances` (schema column) actually holds only the
housing allowance, not a general allowances bucket:** confirmed directly
from a comment in `payslips/update.php`'s override logic reconciling the
column against the salary contract's separate `housing_allowance` field.
The column name is a legacy artifact — the schema inventory's inferred
"purpose" column for `payslips` should be read with this correction in
mind; a migration that takes the column name at face value would
misclassify this value.

| Endpoint | Method | Notes |
|---|---|---|
| `create.php` | POST | Blocked for finalized batches and for a `(batch_id, employee_id)` pair that already has a payslip (`COUNT(*)` check — same app-level-only uniqueness pattern as `payroll_batches/create.php`, and the schema does carry a real unique constraint here per the schema inventory, so this one is actually DB-backed unlike the batch-level check). Uses the simplified net-salary formula described above. |
| `update.php` | PUT | Draft-batch-only manual override; full recomputation using the shared day-rate/absence-cost formula, independently reimplemented (see finding above). |
| `delete.php` | DELETE | Draft-batch-only (checked via a join to the parent batch's status, not a stored flag on the payslip itself). |
| `list.php` | GET | Paginated; filters on batch/employee/month/year/branch/department/search, plus a `new_employees_this_month` flag that joins on `employees.hire_date` falling inside the batch's period. Employee-role callers are silently scoped to self. |
| `one.php` | GET | Explicit 403 (not silent scoping) when an employee requests a payslip that isn't theirs. |
| `export.php` | GET | CSV export via `data_export_payslips_csv()`; same filter set as `list.php`, SQL-level employee scoping for the employee role. |

## Advances (`apis/api/advances/`, 8 endpoints)

**Consumer:** Mixed. `create.php`, `update.php`, `delete.php` allow
`EMPLOYEE` (self-service request/edit/cancel of their own advance while
`pending`) in addition to `COMPANY_ADMIN`/`HR`. `approve.php`,
`reject.php`, `pay.php` are `COMPANY_ADMIN`/`HR` only.

**Finding — tenant-isolation is inconsistent within this module; see
`docs/security/threat-model.md` for the full write-up.** In short: `one.php`,
`list.php`, and `update.php` all correctly scope by `company_id` (via a
join on `employees.company_id`); `approve.php`, `reject.php`, and
`pay.php` do not scope by `company_id` at all; `delete.php` only checks
ownership for the `EMPLOYEE` role, not `COMPANY_ADMIN`/`HR`; `create.php`
never verifies a company/HR-supplied `employee_id` belongs to the
caller's own company. This is a real, open, unmitigated cross-tenant
authorization gap in the live system — not a migration-only concern.

**Finding — Company/HR can create an advance already marked non-pending,
bypassing the approve workflow:** `create.php` lets `COMPANY_ADMIN`/`HR`
optionally set `status` directly in the create body (defaults to
`pending` only if omitted), with no validation that the supplied value is
a real `AdvanceStatusEnum` member — contrast with `list.php`'s status
filter, which does validate against `AdvanceStatusEnum::values()`.

| Endpoint | Method | Notes |
|---|---|---|
| `create.php` | POST | Employee-initiated requests always start `pending`; Company/HR-initiated can set any status directly and must supply `employee_id` (no cross-tenant check — see finding above). Supports two deduction modes (`single_payroll_month`/`installments`) and an installment schedule stored as raw JSON text, matching the schema inventory's `deduction_installments_json` finding. |
| `approve.php` | PUT | Sets status to `approved`. No `company_id` check (see finding above). |
| `reject.php` | PUT | Sets status to `rejected`, requires a `rejection_reason`. No `company_id` check (see finding above). |
| `pay.php` | PUT | Reduces `remaining` by a supplied amount; rejects overpayment (`new_remaining < 0`). No `company_id` check (see finding above). |
| `delete.php` | DELETE | `pending`-only. Employee ownership is checked; company ownership for Admin/HR is not (see finding above). |
| `update.php` | PUT | Correctly `company_id`-scoped. Employees may edit only `amount`/`reason` on their own `pending` advance; Admin/HR may edit any field including `status` (with the same unvalidated-status gap as `create.php`). |
| `one.php` | GET | Correctly `company_id`-scoped, with an additional ownership check for the `EMPLOYEE` role. |
| `list.php` | GET | Correctly `company_id`-scoped; status filter is validated against the enum (contrast with `create.php`/`update.php`). |

## Penalties (`apis/api/penalties/`, 7 endpoints)

**Consumer:** Mixed, and the most granular role model found so far.
`create.php`/`update.php`/`delete.php` are `COMPANY_ADMIN`/`HR` only.
`one.php`/`list.php` use bare `requireAuth()` (any authenticated role)
with inline scoping: `EMPLOYEE` sees only their own, `MANAGER` is
restricted to their own branch via a shared `sql_manager_same_branch_scope()`/
`manager_can_access_employee_branch()` helper (a role tier not seen
anywhere in payroll or advances), and `COMPANY_ADMIN`/`HR` see the whole
company. `report.php`/`stats.php` allow `COMPANY_ADMIN`/`HR`/`MANAGER`.

**Finding — this module's tenant/ownership scoping is consistently
correct**, in direct contrast to `advances`: every one of the 7 endpoints
verifies `employees.company_id` (and, for managers, branch scope) before
reading or mutating a penalty. Read as confirmation that the omissions in
`advances` are a module-specific defect, not a systemic gap.

**Finding — `report.php`'s CSV format option actually returns XLSX:** the
endpoint's own doc-comment says "Supports JSON and CSV formats" and is
triggered by `?format=csv`, but the internal `streamCSV()` function
(despite its name) builds and streams a real `.xlsx` binary via
`xlsx_writer.php`, forcing a `.xlsx` extension and the OOXML
spreadsheet content-type regardless of what the caller asked for. A
naming/content-type trap for any client or migration tooling that takes
the endpoint's name or doc-comment at face value.

| Endpoint | Method | Notes |
|---|---|---|
| `create.php` | POST | Verifies `employee_id` belongs to caller's `company_id` before insert; fires an in-app notification to the employee. |
| `update.php` | PUT | Company-scoped; blocked once `applied_to_payroll=1` (matches the payroll finalize side-effect documented in business rules). |
| `delete.php` | DELETE | Same scoping and `applied_to_payroll` lock as `update.php`. |
| `one.php` | GET | Role-tiered scoping (self / branch / company) as described above. |
| `list.php` | GET | Same role-tiered scoping, plus employee/date-range/search filters. |
| `report.php` | GET | JSON or (mislabeled) XLSX export — see finding above. Branch-scoped for managers. |
| `stats.php` | GET | Aggregate counts (total, applied, not-applied) plus a total monetary amount via `penalties_total_amount()`; role-tiered scoping. |

## Salary Contracts (`apis/api/salary_contracts/`, 5 endpoints)

**Consumer:** `COMPANY_ADMIN`/`HR` for all mutations; `list.php`/`one.php`
additionally allow `MANAGER` (read-only). No `EMPLOYEE` self-service
surface at all — employees cannot view their own contract through this
module.

**Finding — tenant scoping is correct on all 5 endpoints** (every query
joins to `employees.company_id`), consistent with `penalties`, not
`advances`.

**Finding — the `daily`-wage salary mode and the always-zero
`housing_allowance` column are both rooted here** — see the two new
entries in `docs/legacy/business-rule-extraction.md` for the full
write-up; `create.php` and `update.php` are where the mode-based
zeroing and the hardcoded `housing_allowance=0` literal actually live.

| Endpoint | Method | Notes |
|---|---|---|
| `create.php` | POST | `monthly` (default) or `daily` mode; daily mode zeroes `basic_salary` and all four contract allowances in favor of `daily_wage`. `housing_allowance` is a hardcoded `0` literal, not read from the request body at all. |
| `update.php` | PUT | Same mode-zeroing and hardcoded-zero `housing_allowance` as `create.php`. |
| `delete.php` | DELETE | Company-scoped via join; no additional guard (no "applied to payroll" style lock — a contract can be deleted even if payslips already reference the employee). |
| `list.php` | GET | Per-employee contract history (versioned by `effective_from`), not a company-wide list — requires `employee_id`. |
| `one.php` | GET | Single contract fetch, company-scoped. |

## Attendance (`apis/api/attendance/`, remaining 13 of 15 endpoints)

Extends the `check_in`/`check_out` documentation above with the rest of
the module.

**Consumer:** Mixed. `check_in_qr.php` allows `EMPLOYEE` and
`COMPANY_ADMIN`/`HR` (an admin can QR-check-in on an employee's behalf).
`create.php`/`update.php`/`delete.php`/`delete_range.php` are
`COMPANY_ADMIN`/`HR` only. `one.php`/`list.php`/`stats.php` use bare
`requireAuth()` — see the Manager-scoping finding below for why that
matters. `overall_report.php`/`employee_monthly_attendance.php`
explicitly include `MANAGER`.

**Finding — QR check-in skips the 2-hour minimum-gap rule; Manager role
gets unscoped company-wide visibility despite doc-comments claiming
otherwise; bulk date-range delete has no dry-run/audit trail** — see the
three new entries in `docs/legacy/business-rule-extraction.md` for the
full write-up and evidence.

**Finding — the exception-day convention is a real, reused pattern, not
a one-off:** an attendance row can represent either real punches
(`check_in`/`check_out` timestamps) or a category-only "exception" day
(`exception_type_id` set, `check_in` forced to that day's midnight,
`check_out` always `null`) — never both. This XOR is enforced identically
in `create.php` and `update.php`.

| Endpoint | Method | Notes |
|---|---|---|
| `check_in_qr.php` | POST | Branch identified by scanning a QR code (`branches.qr_code`, with an `expires_at` check); no GPS/distance check at all (location is proven by physical QR presence instead); no 2-hour-gap check (see finding above). |
| `create.php` | POST | HR-entered manual attendance; punches XOR exception; enforces the same 2-hour gap as `check_in.php`. |
| `update.php` | PUT | Supports explicit `clear_*` flags; clearing both punches with no exception deletes the row (documented convention, see business rules). |
| `delete.php` | DELETE | Single-row, company-scoped via join. |
| `delete_range.php` | DELETE | Whole-company, date-range bulk delete — see business-rule finding (high blast radius, no dry-run). |
| `one.php` | GET | Company-scoped; employee-role ownership check. |
| `list.php` | GET | Supports a `fill_days=1` mode that expands each employee into one row per calendar day (including rest/holiday/missing days) via `attendance_build_employee_range_calendar()` — a materially different response shape from the default mode. Manager role unscoped (see finding). |
| `stats.php` | GET | Per-employee or company/branch/department aggregate depending on whether `employee_id` is supplied. Response includes a `leave_days` field that is actually populated from official-holiday count, not real approved-leave data, and a hardcoded `overtime_minutes: 0` — both worth treating as unreliable/placeholder fields, not naming issues alone. |
| `export.php` | GET | Thin wrapper over `data_export_attendance_csv()`; not traced past the company-scoped call site in this pass. |
| `import_excel.php` | POST | Thin wrapper over `attendance_excel_import_punch_log()` in `apis/helpers/attendance_excel_analyzer.php` — a large (~1000+ line) bilingual (Arabic/English) column-detection helper supporting two input formats ("punch log" and "template"). Not traced line-by-line in this pass; flagged as a candidate for a dedicated read-through given its size and role in bulk-loading payroll-relevant data. |
| `analyze_excel.php` | POST | Dry-run/preview counterpart to `import_excel.php` (`attendance_excel_analyze()`), same helper file. |
| `overall_report.php` | GET | Company/branch/department report via `overall_attendance_report_build()`; explicitly allows `MANAGER` but no manager-specific scoping was found in that helper either (see finding). |
| `employee_monthly_attendance.php` | GET | Single-employee monthly view; company-scoped, `EMPLOYEE` self-service allowed. |

## Evidence

Files cited individually per endpoint above, all from `workin-hr/hr-legacy`
commit `83c326e40f68dd0d560595a6c4e465eb681f2ce8`.

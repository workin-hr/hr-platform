# Organization Structure — First Slice Design (2026-08-07)

## Purpose And Authority

The reference group (`branches`, `departments`, `department_branches`,
`job_titles`, `shifts`) pulled forward from the sequencing proposal's
step 5 because it is the groundwork two blocked fronts need: branch
GPS/QR data for the attendance self-check-in slice, and
branch/department scope targets for F-16/F-25's manager scoping.
Assigned by the repository owner 2026-08-07 ("proceed with the next
steps"); two scope choices confirmed by the owner the same day:
**employee organization attribution lands in this slice** (nullable
FKs + edit surface), and **`departments.manager_id` is included** as
tenant-validated data.

Evidence: all five tables' DDL read from `mysql_workin.schema.sql` at
the pinned Discovery commit `83c326e`; endpoint/permission shapes from
`docs/api/existing-endpoint-inventory.md` (reference modules, 5
endpoints each, `can_*` keys already in V4 as
`branches`/`departments`/`job_titles`/`shifts` `.read`/`.manage`).

## Scope

**In — schema (V29/V30, V13's conventions):**

- V29, translated with recorded normalizations:
  - `branches`: `id`, `company_id`, `name` NOT NULL, `address`,
    `latitude`/`longitude` NUMERIC(10,7), `radius_meters` INT NOT
    NULL DEFAULT 200, `qr_code` VARCHAR(100), `expires_at`
    TIMESTAMPTZ (the QR's expiry, legacy name kept), `is_active`
    BOOLEAN DEFAULT TRUE, `created_at`.
  - `departments`: `id`, `company_id`, `name`, `manager_id` NULL FK →
    `employees` (data only — no authorization behavior attaches until
    F-16's decision), `is_active`, `created_at`.
  - `department_branches`: `department_id` FK, `branch_id` FK,
    `company_id` (denormalized for RLS — the legacy junction has no
    tenant column), `UNIQUE (department_id, branch_id)`.
  - `job_titles`: `id`, `company_id`, `department_id` NULL FK,
    `name`, `work_hours` NUMERIC(5,2) NOT NULL DEFAULT 8.00,
    `is_active`, `created_at`.
  - `shifts`: `id`, **`company_id` NOT NULL** (legacy allows null —
    unownable rows are invisible under RLS, so tenant ownership
    becomes mandatory; recorded normalization), `name`, `start_time`/
    `end_time` TIME NOT NULL, `days_off` VARCHAR(20) (legacy's
    "Fri,Sat" free text), `is_active`, `created_at`.
  - `employees` gains `branch_id`/`department_id`/`job_title_id`, all
    NULL FKs (legacy's NOT NULL `branch_id` cannot hold for
    already-created employees — recorded normalization; onboarding
    enforces it when that flow exists).
- V30: RLS enable+force for the five new tables (V14's pattern).

**In — endpoints** (one package `com.workin.backend.organization`,
template shapes, §8's 404s; each surface gated by its own existing
`.read`/`.manage` keys):

- `/api/tenant/branches`: list/get/create/update/delete. `qr_code`/
  `expires_at` appear read-only in the view and have **no request
  fields** — QR issuance is the deferred QR slice's flow, not CRUD.
- `/api/tenant/departments`: list/get/create/update/delete.
  `managerId` nullable, tenant-validated (foreign/nonexistent → the
  same 404). `branchIds` (list, may be empty) on create/update
  replaces the junction set; each id tenant-validated → 404; the view
  returns the current `branchIds`.
- `/api/tenant/job-titles`: list/get/create/update/delete.
  `departmentId` nullable, tenant-validated; `workHours` optional
  (default 8.00), positive.
- `/api/tenant/shifts`: list/get/create/update/delete. `startTime`/
  `endTime` required; `daysOff` ≤ 20 chars.
- Common rules: `name` required everywhere; `isActive` settable via
  update (legacy's soft-disable); **delete of a row referenced from
  outside its own aggregate → 409** (the FK violation caught and
  translated — a real-constraint conflict, not an app-level
  pre-check). The junction belongs to the department aggregate:
  deleting a department first removes its own `department_branches`
  rows, then deletes; deleting a branch that junction rows or
  employees still reference is the 409 case. Cross-tenant everything
  → 404 + list exclusion.
- Employees attribution: `CreateEmployeeRequest`/
  `UpdateEmployeeRequest` gain nullable `branchId`/`departmentId`/
  `jobTitleId` (each tenant-validated → 404); `EmployeeView` exposes
  them.

**Out (tracked, blockers named):** QR generation/validation and
geofencing enforcement (the attendance self-check-in slice;
`hr-legacy#16`/F-04 product answers pending);
`employee_shift_assignments` (date-effective junction — its consumer,
schedule/working-day calculation, doesn't exist yet);
`workforce_planning` and `employee_schedules`; manager-scoping
semantics for `manager_id` (F-16); `weekly_off_days`-style working-day
math (company-settings holds the data).

## Design

Flat package `com.workin.backend.organization` holding all four
modules (the payroll-group one-package precedent — they change
together as the org group): four entity/repository/service/controller
quartets plus the DTO records. Services return `Optional`/sealed
results per the template; delete catches
`DataIntegrityViolationException` → `ResponseStatusException(CONFLICT)`
(thrown, not returned — the rollback-only lesson). The employees
module modification stays in `com.workin.backend.employees`
(entity + DTOs + service validation), importing the organization
repositories for reference checks — the same-direction dependency the
requests module already has on attendance.

## Testing

`OrganizationStructureFlowTest` (one class, four surfaces — shared
fixtures): per surface, admin CRUD round trip incl. `isActive`
toggle; branches: view carries null `qr_code`/`expires_at` and no
request field can set them; departments: `managerId` set/cleared,
foreign/nonexistent manager → 404, `branchIds` replace-set round
trip (add two, replace with one, clear), foreign branch id in the
set → 404; job-titles: `workHours` default 8.00, zero/negative →
400, foreign department → 404; shifts: missing times → 400; delete
of a branch referenced by an employee → 409, unreferenced → 204;
cross-tenant get/update/delete per surface → 404 + list exclusion;
read-without-manage → 403 per surface; unauthenticated → non-2xx.

`EmployeeModuleFlowTest` additions: create/update with valid
attribution round-trips into the view; foreign/nonexistent
`branchId`/`departmentId`/`jobTitleId` → the same 404; clearing to
null works.

## Consequences

The attendance self-check-in slice gets its branch geofence/QR data
model; F-16/F-25's scoping decisions get real branch/department
targets; employees become placeable in the structure. The reference
group's remaining legacy surface (QR issuance, shift assignments,
schedules, workforce planning) layers on without schema change.

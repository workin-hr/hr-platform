# Infra Ports: WhatsApp Failover, Company Cascade-Delete, Data Export (2026-08-08)

## Purpose And Authority

Three independent, low-priority legacy features, grouped in one document
because none blocks or is blocked by other tracked work, and none has
any hr-platform equivalent to extend — each is greenfield design, not a
port of partially-built code. **This document is planning output only**
(hr-platform `CLAUDE.md`: Claude's role is planning/analysis/review, not
implementation).

Evidence: `hr-legacy/apis/helpers/whatsapp_helper.php` (221 lines),
`company_delete_helper.php` (306 lines), and `data_export_helper.php`
(569 lines) read in full, current `main`, commit `d113204`; entry points
`apis/api/profile/delete_account.php` and `apis/api/attendance/export.php`
read in full. hr-platform state confirmed by direct search: no
WhatsApp/SMS sending code anywhere in `backend/`; no
`CompanyController`/`CompanyService` (only
`com.workin.backend.identity.Company` entity + `CompanyRepository
extends JpaRepository<Company, Long>`, no delete endpoint exposed
anywhere); no Excel/XLSX generation dependency or export code found. No
`2026-08-08-otp-hardening-design.md` exists yet in
`docs/superpowers/specs/` as of this writing.

## Scope

### 1. WhatsApp Delivery Failover

**In:** a reusable multi-instance-failover send service, not
OTP-specific — OTP is the likely first consumer (no hardening spec to
cross-reference yet), but the mechanism has no OTP logic in legacy and
shouldn't gain any.

Confirmed legacy behavior:
- Instance list is config-driven, ordered primary-then-fallback
  (`whatsapp_instance_ids()`, `:25-41`), from two `AppConfig` values,
  each skipped if blank/placeholder. Concrete instance IDs are
  environment config, not part of the mechanism — design the contract
  as "N ordered instance IDs from config," not hardcoded values.
- Skip-cache: a JSON file at `sys_get_temp_dir()/workin_whatsapp_skip_instances.json`
  maps `instance_id => unix_expiry`. On a disconnected-instance error,
  `whatsapp_mark_instance_disconnected()` sets expiry to **exactly
  `time() + 15 * 60`** — 15 minutes, hardcoded (`:88-93`).
- Send ordering: not-currently-skipped instances first, skipped
  (recently-disconnected) ones appended last as a final resort, never
  excluded outright (`whatsapp_instance_ids_for_send()`, `:105-117`).
- Error classification (`sendWhatsAppTextViaInstance()`, `:154-171`):
  API error string lowercased, checked by substring. `"not connected"`
  / `"instance not connected"` → **disconnected** (infra outage — marks
  skip cache, try next instance). `"no lid found"` → **no_lid** (number
  itself unreachable on WhatsApp, not infra — does not mark skip
  cache). Anything else is a generic error (neither flag set).
- The send loop (`sendWhatsAppText()`, `:183-220`) tries every instance
  in ready-then-skipped order even after a `no_lid` result — comment:
  "only one gateway may have the contact mapped" (`:210-211`) —
  `no_lid` does not short-circuit.
- Unconfigured state: debug mode logs and returns success (dev no-op);
  otherwise fails silently, never throws.

**Out:** the OTP flow itself; any non-WhatsApp channel — legacy has
none to port.

### 2. Company Cascade-Delete

**Open question, not decided here:** hr-platform has no delete-company
use case today — `Company`/`CompanyRepository` exist with no
controller. The legacy entry point
(`apis/api/profile/delete_account.php`) is **self-service**: the
authenticated company admin deletes *their own* company after
re-entering their password, not an admin tool acting on other
companies. Designing cascade logic before hr-platform decides whether
this is even a supported action would be premature.

Confirmed legacy behavior (`company_cascade_delete()`, `:157-305`), one
PDO transaction, rollback on any `Throwable`. Order and reasoning:

1. `notifications.from_employee_id` **nulled** (self-referencing FK to
   `employees`) before employees are removed (`:174-178`).
2. `notifications` deleted by `company_id` (`:180-184`).
3. Thirteen employee-dependent tables, joined on `employee_id` (none
   carry `company_id` directly), looped, **not** wrapped in try/catch —
   a failure here is fatal to the transaction (`:186-202`): `payslips`,
   `requests`, `advances`, `penalties`, `leave_balance`, `attendance`,
   `employee_schedules`, `employee_shift_assignments`,
   `salary_contracts`, `employee_docs`, `complaints`, `push_tokens`,
   `hr_permissions`.
4. `departments.manager_id` nulled — same self-referencing-FK reasoning
   as step 1 (`:204-209`).
5. `assets`, `administrative_decisions`, `workforce_planning` — each
   individually try/catch-wrapped, failures swallowed (`:212-224`).
6. `employees` deleted by `company_id` — not wrapped, fatal on failure
   — comment: "Employees reference branches — must go before
   branches/company cascade" (`:227-231`).
7. `department_branches` deleted via join to `branches` — junction has
   no `company_id`, must precede `branches`; not wrapped (`:233-238`).
8. `job_title_sections` (join `job_titles`), `section_departments`
   (join `departments`), `company_setting_values` (join
   `company_settings`) — each its own try/catch (`:240-268`).
9. Nine remaining company-scoped tables, deleted directly by
   `company_id`, looped, each try/catch-wrapped (`:270-288`):
   `payroll_batches`, `company_settings`, `request_types`,
   `exception_types`, `company_official_holidays`, `job_titles`,
   `shifts`, `departments`, `branches`.
10. `companies` row deleted by `id` (`:290-293`); `rowCount() !== 1`
    throws, forcing rollback — the routine's one explicit self-check.

**32 distinct tables touched** (31 related + `companies`) — more than a
quick read suggests, since `notifications`/`departments` are each
touched twice (update, then later delete/no-delete) and several
junction/child tables have no `company_id` and must be reached by
joining through their parent.

**Risk to flag, not resolved here:** steps 5, 8, and 9 use a bare
`catch (Throwable $ignored) {}`, indiscriminately swallowing both the
apparent intent (missing table) and a real constraint violation that
should have aborted an irreversible delete. A hr-platform design should
decide this deliberately rather than inherit it silently.

A preview endpoint (`company_delete_preview_payload()`, `:129-147`)
runs the same per-table `COUNT(*)` first, returning counts + total, so
a caller can show "this deletes N records" before committing — worth
carrying forward as UX regardless of the open question above.

### 3. Data Export

Reference material only — real design depends on
`2026-08-08-attendance-calendar-engine-design.md` and
`2026-08-08-payroll-attendance-reconciliation-design.md` (paths as
expected once written), since export reads their computed output.

Legacy exposes two attendance sheets from one endpoint
(`apis/api/attendance/export.php?type=fingerprints|overall`), both
written to `.xlsx`:

- **Fingerprints sheet** (day-level punch,
  `data_export_fingerprints_sheet()`, `:182-308`): one row per
  employee/day in a date range. Columns: row number, employee code,
  name, date, weekday name, check-in, check-out, hours worked,
  exception name (`:161-174`). Rows color-coded green (both punches
  present), red (`is_missing`), white (rest/holiday/other) (`:124-141`)
  — mirrors the desktop "البصمات" table.
- **Overall sheet** (`data_export_attendance_csv()`, `:317-375`): one
  row per employee for the period. Columns: row number, code, name, job
  title, **department, branch**, total days, working days, exception
  days, paid rest days, absent days, hours worked, overtime (`:37-54`)
  — department/branch are the new columns the task calls out. Row data
  sourced from a separate `overall_attendance_report_build()` helper,
  not read here.
- **Config gates — corrected from the working hypothesis:** both sheets
  gate on `app_config_value('show_export_fingerprints_sheet', …)` /
  `…('show_export_overall_sheet', …)` (`export.php:39-44`).
  `app_config_value()` (`configs_helper.php:7-39`) queries a single flat
  `Table::CONFIGS` by key with **no `company_id` filter anywhere** —
  this is a global, system-wide config, **not**
  `company_setting_selected_values` or any per-company lookup as
  originally guessed. Confirmed fact, stated to correct the assumption
  before a tenant-scoped gate gets designed that legacy doesn't have.
  Default `'true'` (fail-open); disabled → HTTP 403.
- Access: `requireAuth()` + `requireCompanyActive()`; `employee` role
  scoped to its own `employee_id` in the fingerprints query (`:206-208`);
  other roles get optional `employee_id`/`branch_id`/`department_id`
  filters.
- A third export, `data_export_payslips_csv()` (`:460-568`), is
  payroll output in the same file — out of scope, belongs with a
  payroll-reconciliation port if ever built.

**Out:** any Java interface/DTO design — premature until the two
upstream specs settle what "a day's attendance classification" and "a
payslip's computed figures" look like as data this export would read.

## Testing And Consequences

None of the three has an implementation plan yet, so there is no
concrete test plan — noted here only as acknowledgment that testing is
deferred with the same rigor as design.

**WhatsApp failover:** self-contained and low-risk to build ahead of
its consumer — its only blocker is the absence of any outbound-message
use case. Whether to build it speculatively before OTP lands is a
timing call for the repository owner, not inferred here.

**Company cascade-delete:** highest risk by nature (irreversible,
destructive, 32 tables) but currently has **no product entry point** in
hr-platform. Recommend leaving fully deferred until "can a company
admin self-delete their company" is answered as a product question —
building the cascade logic first would solve a problem hr-platform
hasn't decided to have. If it is answered yes, the table-order-and-reasoning
list above should still hold, modulo hr-platform's own RLS-tenant-scoped
schema needing the same join-through-parent treatment legacy uses for
`department_branches`/`job_title_sections`/`section_departments`.

**Data export:** blocked in substance, not sequencing, on the two
upstream specs; the inventory above exists to save a second full read
of `data_export_helper.php`. Because legacy's gate is global rather
than tenant-scoped, hr-platform needs its own explicit call — global
system config vs. a per-company `CompanySettings` toggle (the only
settings mechanism hr-platform currently has, and tenant-scoped by
construction per `2026-08-07-company-settings-first-slice-design.md`).
**Open question, not decided here** — it changes depending on whether
the sheets should be toggleable per company or platform-wide.

All three remain independent: none is a prerequisite for the other two,
and none blocks any tracked in-flight slice. Included for backlog
completeness, not urgency.

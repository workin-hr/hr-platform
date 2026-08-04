# Database Schema Inventory

## Source

`workin-hr/hr-legacy`, `mysql_workin.schema.sql` (structure-only export, no
data rows — see that repository's `import/sanitized-legacy-baseline` PR for
how it was sanitized), commit `83c326e40f68dd0d560595a6c4e465eb681f2ce8`.
MariaDB 11.8.8, dumped via phpMyAdmin 5.2.2. 42 tables, all `ENGINE=InnoDB`,
`DEFAULT CHARSET=utf8mb4`.

Row-count estimates below come from each table's `AUTO_INCREMENT` value in
the dump (next-id watermark, so an upper bound on row count for tables that
never delete rows — treat as approximate, not exact).

## System Shape (confirmed from the schema itself)

- **Multi-tenant.** `companies` is the tenant root. Every operational table
  either carries `company_id` directly (`branches`, `departments`,
  `job_titles`, `shifts`, `request_types`, `exception_types`,
  `payroll_batches`, `workforce_planning`, `administrative_decisions`,
  `assets`, `complaints`) or reaches it through `employees.company_id`
  one hop away.
- **Attendance dominates the data volume.** `attendance` is at
  AUTO_INCREMENT 38,960 — roughly 5.5x `employees` (7,098) and by far the
  largest table in the system. Any migration approach (dual-write,
  batch cutover, etc.) needs to size for this table specifically, not the
  system average.
- **~292 tenant companies**, ~413 branches, ~7,098 employees as of the
  dump.

## Full Table List

| Table | Purpose (inferred from columns/FKs) | Est. rows |
|---|---|---|
| `companies` | Tenant root: registration, login (phone+password), status workflow (`pending`/`active`/`rejected`/`suspended`), OTP verification flag | ~292 |
| `company_activities`, `company_titles`, `company_sizes` | Lookup tables for company registration (industry, title, employee-count band) | 13 / 6 / 9 |
| `company_official_holidays` | Per-company holiday calendar, unique on `(company_id, holiday_date)` — feeds payroll working-day calculation | ~84 |
| `company_settings` / `company_setting_values` / `setting_definitions` / `setting_allowed_values` | Generic EAV-style settings system: a company selects one or more allowed values per setting definition (used for `month_start_day`, `month_end_day`, `weekly_off_days`, `overtime_rate` — see Business Rules) | 414 / 584 / 6 / 89 |
| `branches` | Physical sites; `latitude`/`longitude`/`radius_meters` (default 200m) drive attendance geofencing; `qr_code` for QR check-in | ~413 |
| `departments` / `department_branches` | Org units; a department can span multiple branches (many-to-many junction table) | 969 / — |
| `job_titles` | Per-company job titles; carries `work_hours` (default 8.00) used as a payroll fallback | 1,696 |
| `employees` | Core identity: phone+password auth, `role` enum (`company_admin`/`hr`/`manager`/`employee`), `token_version` (JWT invalidation — see Business Rules), `join_request_status`, mobile-attendance opt-in/opt-out flags | ~7,098 |
| `employee_docs` | Uploaded document metadata (`doc_type`, `file_url`) — the actual files live under `uploads/`, kept local-only, not part of this schema export | — |
| `employee_schedules` | Per-employee, per-date schedule overrides (name/start/end time, exception note) | ~625 |
| `shifts` / `employee_shift_assignments` | Named shift templates with `days_off` (free-text, e.g. "Fri,Sat"); assignment is date-effective (`effective_from`), not a single current value | 307 / 3,993 |
| `exception_types` | Company-defined attendance exception categories (e.g. excused absence) | ~119 |
| `attendance` | Check-in/out events; `method` enum (`app`/`excel`/`qr`), optional `exception_type_id`, optional GPS pair | ~38,960 |
| `hr_permissions` | One row per HR-role employee: 17 boolean `can_*` flags (dashboard sections) — a hand-rolled permission matrix, one column per feature. Recount confirmed directly 2026-08-05; full mapping to the new canonical permission catalog: `docs/architecture/authorization-model.md` §7 | ~48 |
| `requests` / `request_types` | Leave/permission request workflow; `request_types.deduct_balance` / `counts_as_paid_leave` / `add_attendance_exception` are per-type behavior toggles that drive payroll and leave-balance side effects | 233 / 193 |
| `leave_balance` | Per-employee, per-year leave allotment; `remaining_days` is a MySQL `GENERATED ALWAYS AS (total_days - used_days) STORED` column | ~3,572 |
| `advances` | Salary advances with two deduction modes (`single_payroll_month` / `installments`); installment schedule stored as a JSON text column (`deduction_installments_json`), not normalized rows | ~33 |
| `penalties` | Disciplinary deductions in **days**, not currency; `applied_to_payroll` flag prevents double-application across payroll runs | ~44 |
| `salary_contracts` | Versioned per-employee compensation (`effective_from`-dated; the effective contract for a period is "most recent row with `effective_from <=` period end"); `total` is a MySQL `GENERATED ALWAYS AS (...) STORED` computed column | ~7,067 |
| `payroll_batches` | One per company/month; `status` enum `draft`/`finalized`; `period_from`/`period_to` are company-fiscal, not necessarily calendar-month (see Business Rules) | ~111 |
| `payslips` | One per employee per batch; unique on `(batch_id, employee_id)`; ~20 numeric columns covering entitlements, deductions, and stored totals recomputed on every batch calculate (destructive replace, not incremental — see Business Rules) | ~7,074 |
| `assets` | Company assets issued to employees, with return tracking | ~25 |
| `complaints` | Support inbox; `source` enum distinguishes employee-submitted vs. company-support-submitted | ~52 |
| `administrative_decisions` | Company-wide announcements/decisions | ~22 |
| `workforce_planning` | Headcount targets per (company, branch, department, job_title) — unique constraint enforces one target row per combination | ~15 |
| `notifications` | In-app notifications; `recipient_kind` enum (`employee`/`company`), polymorphic `reference_type`/`reference_id` pointing at other tables (no FK — application-level only) | ~4,014 |
| `push_tokens` | FCM-style device tokens per employee; no row count recorded (empty or near-empty at dump time) | — |
| `otp_codes` | Short-lived WhatsApp OTP codes; no FK to `employees`/`companies` — looked up by raw phone string (see Business Rules) | ~588 |
| `configs` | Generic app-wide key/value store — the only table using `utf8mb4_general_ci` instead of `utf8mb4_unicode_ci` (see Migration Risks) | ~85 |
| `app_content`, `banners`, `faq_categories`, `faq_items`, `phone_countries` | Static/marketing content: bilingual (`_ar`/`_en`) CMS-style tables, banner CTAs, FAQ, phone-country dial-code list | 10 / 3 / 4 / 5 / 5 |

## MySQL/MariaDB-Specific Features Requiring a Migration Decision

| Feature | Where | PostgreSQL migration note |
|---|---|---|
| `GENERATED ALWAYS AS (...) STORED` | `leave_balance.remaining_days`, `salary_contracts.total` | PostgreSQL supports the same syntax (PG 12+) — portable, but the generation expression itself must be transcribed and tested, not assumed identical. |
| `CHECK (json_valid(...))` on a `longtext` column | `phone_countries.phone_prefixes` | PostgreSQL has a native `jsonb` type with built-in validation; this is a place to actively improve the model during migration rather than replicate the MySQL text+CHECK pattern. |
| `int(10) UNSIGNED` | Every `id`/FK column in the schema | PostgreSQL has no native unsigned integer type. Needs an explicit decision: plain `integer`/`bigint` (accepting the type no longer prevents negative values at the DB layer) or a `CHECK (col >= 0)` constraint. Affects all 42 tables uniformly — worth one schema-wide decision, not 42 separate ones. |
| `year(4)` column type | `leave_balance.year`, `payroll_batches.year` | No direct PostgreSQL equivalent; typically mapped to `smallint`. |
| `ENUM(...)` inline column type | Widely used — `employees.role`, `companies.status`, `attendance.method`, `advances.status`, `requests.status`, `banners.app_platform`, `notifications.recipient_kind`, others | PostgreSQL has a native `CREATE TYPE ... AS ENUM`, but adding a new value later requires an `ALTER TYPE` (a schema migration, not a data change) — worth deciding up front whether to use PG enums or a `varchar` + `CHECK` instead, given how often new statuses tend to get added to systems like this. |
| Inconsistent `ON DELETE` behavior across FKs | See below | Behavior-preservation risk, not a syntax issue — see Migration Risks. |

## Migration Risks Found By Reading the Constraints Directly

- **Inconsistent collation.** Every table uses `utf8mb4_unicode_ci` except
  `configs`, which uses `utf8mb4_general_ci`. This is very likely an
  accident (the general default vs. the project's actual convention), not
  a deliberate choice — worth confirming with whoever ran migrations on
  this database before deciding whether to replicate it or normalize it.
- **Inconsistent FK delete behavior.** Most foreign keys are
  `ON DELETE CASCADE`. Three are not: `employees.branch_id` →
  `branches.id`, `payslips.employee_id` → `employees.id`, and
  `requests.request_type_id` → `request_types.id` all have **no**
  explicit `ON DELETE` clause, which means MySQL's default `RESTRICT`
  applies — deleting a branch/employee/request-type that's still
  referenced would be blocked at the DB layer, unlike the cascading
  behavior everywhere else. Whether this is intentional (protecting
  payroll history from accidental cascade-deletes) or an oversight is an
  open question, not something I can determine from the schema alone —
  flagged here rather than guessed at.
- **`notifications.reference_type`/`reference_id` is a polymorphic
  reference with no foreign key at all** — referential integrity for
  "what does this notification point at" is enforced only in application
  code, not the database. A migration needs to either preserve that
  (application-level checks) or decide to normalize it.
- **`advances.deduction_installments_json`** stores a full installment
  schedule as JSON text inside a single column, parsed and interpreted
  entirely in PHP (see `apis/helpers/payroll_calculation.php`,
  `payroll_advance_planned_for_batch()`). A relational redesign
  (a proper `advance_installments` table) is a real option worth
  considering during migration, not just a type translation.

## Evidence

`workin-hr/hr-legacy` `mysql_workin.schema.sql`, commit `83c326e`, read in
full (all `CREATE TABLE`, `ALTER TABLE ... ADD PRIMARY/UNIQUE/KEY`,
`AUTO_INCREMENT`, and `ADD CONSTRAINT` statements — 1,718 lines). Confirmed
zero `INSERT`/`REPLACE`/`LOAD DATA` and zero `DEFINER` clauses in that file
before treating it as evidence (matches what was independently verified
during the sanitized-import review in `hr-legacy`).

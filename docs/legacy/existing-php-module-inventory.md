# Existing PHP Module Inventory

## Source

`workin-hr/hr-legacy`, commit `83c326e40f68dd0d560595a6c4e465eb681f2ce8`.
Two applications share one codebase: `apis/` (REST backend, pure PHP + PDO,
no framework) and `dashboard/` (server-rendered admin panel, session-based
auth, separate from the API's JWT auth). The module list below matches
`apis/config/http_api.php`'s `ApiModule::allowedList()` — the code's own
enumeration of what counts as a real module — cross-checked against the
actual `apis/api/` directory structure.

## Two Frontends, One Backend Data Model

- **`apis/`** — REST JSON API, JWT bearer auth (`apis/config/auth.php`,
  `apis/helpers/otp_helper.php`), consumed by the (not-yet-in-scope-here)
  mobile/desktop client. 199 endpoint files across 38 module directories.
- **`dashboard/`** — Session-based server-rendered admin panel
  (`dashboard/includes/auth.php`), 92 page files across 34 page
  directories, used directly by browsers. Shares the same MySQL database
  and largely the same business-logic helpers conceptually, but has its
  own separate `constants.php`/`db.php`/`auth.php` — **the two frontends
  are not one shared PHP application**, they are two codebases against one
  schema. A migration needs to decide whether the target system keeps
  that split or unifies it.

## API Modules (`apis/api/`)

| Module | Entry points | Business domain |
|---|---|---|
| `auth` | 14 | Registration, login (company + employee + desktop), OTP issue/verify/resend, forgot/reset password, company lookup, join requests. All 14 endpoints individually documented in `docs/api/existing-endpoint-inventory.md`. **3 critical/high security findings — see `docs/security/threat-model.md`** (DEBUG-gated OTP disclosure, unauthenticated company-registration completion, 10-year JWT expiry with no admin-token revocation) |
| `attendance` | 15 | Check-in/out (app + QR), Excel import/export/analyze, monthly summaries, stats, exception handling — the single largest module by entry-point count. All 15 endpoints individually documented in `docs/api/existing-endpoint-inventory.md`; see `docs/legacy/business-rule-extraction.md` for 3 findings (QR check-in skips the 2-hour gap rule, Manager role is unscoped despite doc-comments, bulk date-range delete has no dry-run) |
| `employees` | 14 | CRUD, bulk import (Excel), photo upload, deactivate/reactivate, delete preview (impact analysis before a destructive action), stats. All 14 endpoints individually documented in `docs/api/existing-endpoint-inventory.md`; correct Manager branch-scoping throughout (contrast with `attendance`); see `docs/legacy/business-rule-extraction.md` for the cascade-delete-of-payroll-history finding |
| `profile` | 9 | Self-service: change password, delete account (with a preview endpoint first), phone-change confirmation flow, push-token registration. All 9 endpoints individually documented in `docs/api/existing-endpoint-inventory.md`. **High-impact finding — mobile logout silently deactivates the employee's account, no password required — see `docs/legacy/business-rule-extraction.md`** |
| `payroll_batches` | 10 | Batch lifecycle: create, calculate, finalize, reopen, fiscal-period resolution, stats — all 10 endpoints individually documented in `docs/api/existing-endpoint-inventory.md` |
| `leave_balances` | 10 | Balance CRUD, generation, bulk import, Excel template/analyze, stats. All 10 endpoints individually documented in `docs/api/existing-endpoint-inventory.md`; consistently company-scoped |
| `advances` | 8 | Create/approve/reject/pay/update/delete — full advance lifecycle. All 8 endpoints individually documented in `docs/api/existing-endpoint-inventory.md`. **5 of 8 have a confirmed cross-tenant authorization gap — see `docs/security/threat-model.md`.** |
| `penalties` | 7 | CRUD, reporting, stats — all 7 endpoints individually documented in `docs/api/existing-endpoint-inventory.md`, consistently correct tenant scoping |
| `requests` | 7 | Leave/permission request workflow: create/approve/reject/update. All 7 endpoints individually documented in `docs/api/existing-endpoint-inventory.md`. **Finding — Manager approve/reject is not branch-scoped, unlike Manager read access in the same module — see `docs/legacy/business-rule-extraction.md`** |
| `workforce_planning` | 7 | Headcount targets: create/update/save_target/summary. All 7 endpoints individually documented in `docs/api/existing-endpoint-inventory.md`; consistently company-scoped |
| `payslips` | 6 | CRUD, export — all 6 endpoints individually documented in `docs/api/existing-endpoint-inventory.md` |
| `branches` | 6 | CRUD, QR-code generation. All 6 documented in `docs/api/existing-endpoint-inventory.md`; company-scoped throughout |
| `company_settings` | 6 | CRUD, options (available settings for a company to choose from). All 6 documented; company-scoped throughout |
| `notifications` | 6 | List, send, mark read, unread count. All 6 documented; ownership-checked per-recipient |
| `job_titles`, `departments`, `shifts`, `request_types`, `attendance_exception_types`, `company_official_holidays`, `assets`, `administrative_decisions` | 5 each | Standard per-company CRUD lookup/config modules |
| `salary_contracts` | 5 | Versioned per-employee compensation. All 5 endpoints individually documented in `docs/api/existing-endpoint-inventory.md`; correct tenant scoping throughout, but see the `daily`-wage-mode and always-zero-`housing_allowance` findings in `docs/legacy/business-rule-extraction.md` |
| `employee_docs` | 4 | Upload, list, update, delete. All documented in `docs/api/existing-endpoint-inventory.md`; company-scoped |
| `company_join_requests`, `hr_employees`, `complaints`, `schedules` | 3 each | Company-scoped workflows (accept/reject join requests, HR permission updates, complaint handling, schedule assignment/generation). All documented; `company_join_requests` accept/reject correctly scoped (contrast with `advances`) |
| `company` | 3 | Company profile update, logo upload, commercial-registration-doc upload. All documented; company-scoped |
| `app_content`, `banners`, `faqs`, `configs`, `phone_countries`, `setting_allowed_values`, `setting_definitions`, `time`, `dashboard` | 1 each | Read-mostly reference/content endpoints; `time` is a single server-time endpoint (`apis/api/time/now.php`) — likely a client clock-sync utility |

**Two directories exist on disk but are empty and are *not* in
`ApiModule::allowedList()`:** `apis/api/employee_custody/` and
`apis/api/sections/`. These look like abandoned or renamed modules
(`sections` is plausibly an old name for what is now `departments` — the
schema's own foreign key names still say `fk_sections_company` and
`fk_sb_section` on the `departments`/`department_branches` tables). Not
reachable through the router (`apis/router.php` only allows modules in
`ApiModule::allowedList()`), so not a live code path — flagged as a dead
artifact, not a hidden feature, but worth confirming with whoever has
institutional knowledge before assuming it's safe to ignore entirely.

## Dashboard Pages (`dashboard/pages/`)

34 page directories, 92 files. Mirrors most API modules 1:1 (e.g.
`pages/employees/`, `pages/attendance/`, `pages/payroll/`) plus
dashboard-only concerns not exposed via the API at all:

- `pages/login/` — session-based admin login, separate from the API's JWT
  auth entirely (`dashboard/includes/auth.php` vs `apis/config/auth.php`)
- `pages/salary_calculator/` — includes
  `egypt_salary_calculator.php`, a standalone calculator page distinct
  from the real payroll-batch calculation engine in
  `apis/helpers/payroll_calculation.php`. **Open question:** whether
  this implements the same rules independently (a duplication/drift risk)
  or is a simpler illustrative tool — needs its own read-through before
  migration, not assumed identical to the real payroll engine.
- `pages/setting_templates/` — no matching API module; dashboard-only
  settings-template management
- `pages/activities/` — recent-activity feed, no matching API module
- `pages/company_settings/` — five sub-tab partials
  (`_tab_general.php`, `_tab_exception_types.php`,
  `_tab_official_holidays.php`, `_tab_request_types.php`,
  `_tab_users.php`) rather than one flat page — the dashboard's settings
  UI is more granular than the API module list suggests

## Data Dependencies (cross-cutting)

- Every module ultimately depends on `employees`/`companies` for identity
  and tenant scoping — see `docs/migration/database-schema-inventory.md`.
- `apis/helpers/db_query.php` and `dashboard/includes/db.php` are the
  respective query-helper layers each frontend uses; they are **not
  shared code** despite doing conceptually the same thing (raw PDO
  wrappers), which is itself a maintenance/drift risk independent of any
  migration.
- File uploads (`employee photos`, `company logos`, `commercial
  registration docs`, `employee_docs`) write to `uploads/`
  (`AppConfig::UPLOAD_PATH` / `UPLOAD_PATH` constant in both
  `constants.php` files) — kept local-only in the sanitized import, real
  customer files, not part of any committed evidence here.
- WhatsApp is the only OTP/notification delivery channel actually wired
  up (`apis/helpers/whatsapp_helper.php`); `AppConfig::SMS_API_KEY` and
  `FCM_SERVER_KEY` exist as constants but hold literal placeholder values
  (`YOUR_SMS_API_KEY_HERE`, `YOUR_FCM_SERVER_KEY_HERE`) — SMS and push
  notification delivery are **not currently live**, only scaffolded.

## Open Questions

- Whether `pages/salary_calculator/egypt_salary_calculator.php` is a
  second, independent implementation of payroll rules or a simplified
  illustrative tool — unresolved, needs a dedicated read-through.
- Whether `employee_custody/` and `sections/` (empty, unreferenced
  directories) can be deleted outright or represent a feature that was
  intentionally paused.
- Whether the target architecture keeps `apis/` and `dashboard/` as two
  separate applications (matching the legacy split) or unifies them —
  this is an ADR-0002 (Modular Monolith Baseline) question, not something
  this inventory can answer on its own.

## Evidence

`workin-hr/hr-legacy` commit `83c326e`: `apis/config/http_api.php`
(`ApiModule::allowedList()`), `apis/router.php`, directory listing of
`apis/api/*/` (199 files, 38 populated module directories + 2 empty ones)
and `dashboard/pages/*/` (92 files, 34 directories).

# Data-Quality Analysis

## Method

Measured directly against the real database, 2026-08-04: `mysql_workin.schema.sql`
(DDL) and `mysql_workin.data.sql` (full data dump, both git-ignored,
local-only, real customer data) loaded into a throwaway, isolated Docker
MySQL 8.0 container (`workin-analysis-mysql`), queried directly, then
destroyed — no customer data or raw rows appear anywhere below, only
aggregate counts. This is **proven-from-data** evidence, not inference —
labeled per finding.

## Finding: `employees.phone` NULL/empty on ~25% of active employees

- **Table Or Column**: `employees.phone`
- **Quality Issue Class**: Missing value on a column that is the sole
  credential identifier for both `auth/login_employee` (mobile) and the
  dashboard's `doHrLogin()`/desktop's `login_desktop.php` (HR-role).
- **Detection Method**: Direct query — `SELECT COUNT(*) FROM employees
  WHERE phone IS NULL OR phone=''`, cross-tabulated against `is_active`.
- **Estimated Scope**: 715 of 2,871 employees total (24.9%); 707 of 2,836
  **active** employees (24.9% of active employees) have no phone on
  file. 8 of the 35 inactive employees also lack one.
- **Migration Impact**: If the new system keeps phone-based
  authentication as the only identity mechanism, roughly a quarter of
  the currently-active workforce has no way to authenticate at all —
  today or after migration. This is either (a) intentional — these are
  employees who are never meant to self-serve digitally (e.g.
  checked in by someone else, no personal device) — or (b) a real
  access gap already present in production. **Proven from data**: the
  715 figure. **Operational assumption requiring confirmation**: which
  of (a)/(b) is true — this cannot be determined from the data alone
  and needs a product/business answer before deciding whether the new
  system needs an alternate onboarding/identity path for phoneless
  employees.
- **Evidence**: Direct count query against the loaded dump, 2026-08-04.

## Finding: `salary_contracts.basic_salary = 0` on 60 monthly-mode contracts

- **Table Or Column**: `salary_contracts.basic_salary`
- **Quality Issue Class**: Zero value on a column that should carry a
  real compensation figure for `monthly`-mode contracts specifically.
- **Detection Method**: `SELECT COUNT(*) FROM salary_contracts WHERE
  basic_salary=0`, cross-tabulated against `salary_mode`.
- **Estimated Scope**: 60 of 2,829 salary contracts (2.1%) have
  `basic_salary=0`. **All 60 are `salary_mode='monthly'`** — zero of
  the `daily`-mode contracts show this. This rules out the
  daily-wage-mode explanation already on record for `hr-legacy#12`
  (that finding is about `payslips/create.php` silently dropping base
  pay for daily-mode employees at calculation time — a different
  mechanism than a monthly contract being stored with a zero base
  figure at the source).
- **Migration Impact**: If these 60 rows represent real employees with
  a genuine current salary of zero on a monthly contract, that is a
  correctness bug worth surfacing to product before migration
  (possible causes: incomplete onboarding, a placeholder never
  updated, or a contract for an employee no longer actively paid via
  this mechanism). Needs product/business review of these specific 60
  employee IDs (not reproduced here) before deciding whether to
  migrate as-is, flag, or exclude.
- **Evidence**: Direct count query against the loaded dump, 2026-08-04.
  **Operational assumption**: root cause (data-entry gap vs. legitimate
  zero) requires business-side review of the specific records, which
  this analysis pass did not have authority or reason to do.

## Finding: `hr_permissions` has 1:1 row coverage with `hr`-role employees, but `manager`-role has zero live employees

- **Table Or Column**: `employees.role`, `hr_permissions`
- **Quality Issue Class**: Not a defect — a scale/coverage fact directly
  relevant to `hr-legacy#26` (Manager-role desktop-login gap).
- **Detection Method**: `SELECT role, COUNT(*) FROM employees GROUP BY
  role`.
- **Estimated Scope**: 2,862 `employee`-role, 9 `hr`-role, **0
  `manager`-role** employees in the entire dataset. `hr_permissions`
  has exactly 9 rows — 1:1 coverage with the 9 `hr`-role employees, no
  gap.
- **Migration Impact**: Directly informs `hr-legacy#26` and
  `docs/adr/ADR-0009-dashboard-vs-desktop-admin-client.md` — see the
  dedicated investigation in that ADR and the consolidated task matrix
  (row F-12) for the full analysis. In one sentence: the Manager role
  is defined in the schema and code but has **zero real users today**,
  which materially changes the urgency of the desktop-login parity gap.
- **Evidence**: Direct count query against the loaded dump, 2026-08-04.

## Finding: Invalid zero-dates present on `NOT NULL` and nullable date columns

See `docs/migration/invalid-date-analysis.md` for the full breakdown
(employees `hire_date`/`birth_date`, `salary_contracts.effective_from`)
— cross-referenced here because it is also a data-quality class, not
duplicated in full.

## Finding: Duplicate business keys with no enforced uniqueness

See `docs/migration/duplicate-business-key-analysis.md` for the full
breakdown (`job_titles`, `departments`, `branches`, `shifts` — real
duplicate `(company_id, name)` groups found) — cross-referenced here,
not duplicated in full.

## Findings Requiring A Fresh Production Snapshot

- **Growth/drift since dump date**: this dump is a single point-in-time
  snapshot (loaded 2026-08-04, dump file timestamps 2026-08-03). Whether
  the phone-null rate, duplicate-name counts, or zero-salary rows have
  changed since is unknown — a fresh snapshot immediately before
  cutover planning should re-run every query in this document.
- **Anything requiring live application behavior** (e.g. whether
  phoneless employees are actively blocked from any workflow today, or
  whether the platform silently accommodates them some other way) needs
  either production log access or a conversation with support/product,
  not just the data snapshot.

## Operational Assumptions Still Requiring Confirmation

- Whether the 715 phoneless employees are an intentional population
  (no digital access needed) or a real gap.
- Root cause of the 60 zero-basic-salary monthly contracts.
- Whether the `manager` role being entirely unused today is permanent
  product direction or simply not yet adopted by any customer company.

## Evidence

`mysql_workin.schema.sql`, `mysql_workin.data.sql` (both git-ignored,
local-only), loaded into a throwaway Docker MySQL 8.0 container and
queried directly, 2026-08-04; container destroyed after analysis. No
raw customer data reproduced above — only aggregate counts.

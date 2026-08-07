# Company Settings — First Slice Design (2026-08-07)

## Purpose And Authority

Closes both halves of the two recorded open questions in
`docs/bootstrap/open-questions.md`: where per-company configuration
lives (a typed `company_settings` table) and who reads it (payroll's
fiscal-period bounds and the leave-accrual fallback, rewired in this
same slice). Assigned by the repository owner 2026-08-07 ("proceed
with the next steps"); two design choices confirmed by the owner the
same day: **typed columns instead of a faithful EAV port**, and
**both consumers rewired now**.

Evidence beyond the docs, all fetched from `workin-hr/hr-legacy` at
the pinned Discovery commit `83c326e` and read in full:
`mysql_workin.schema.sql` (the 4-table EAV), `apis/config/enums.php`
(`CompanySettingEnum` — exactly five code-consumed settings), and
`apis/helpers/payroll_calculation.php`
(`payroll_fiscal_period_bounds()` — the complete period algorithm).

## Scope

**In — schema (V27/V28):**

- V27: `company_settings` — `id`, `company_id` NOT NULL FK with a
  real `UNIQUE (company_id)` (one row per company; the surface is an
  upsert), five typed columns, all NULL with **null meaning "unset —
  apply the legacy fallback"**, mirroring legacy's missing-setting
  behavior exactly: `month_start_day` SMALLINT (fallback 1),
  `month_end_day` SMALLINT (fallback: last day of the month —
  legacy's `0` sentinel becomes null), `weekly_off_days` VARCHAR(60)
  (legacy's comma-separated day-name convention, e.g. "Fri,Sat" —
  stored, consumer deferred), `overtime_rate` NUMERIC(5,2) (stored,
  consumer deferred), `monthly_leave_accrual` NUMERIC(5,1)
  (fallback 21.0), `created_at`. The EAV→typed conversion (legacy
  `setting_definitions`/`setting_allowed_values`/`company_settings`/
  `company_setting_values` rows to these columns) is cutover
  data-migration work, recorded alongside F-15's conversion run.
- V28: RLS enable+force (V14's pattern).
- No new permission keys: `company.settings.read`/
  `company.settings.manage` already exist in V4 (legacy
  `can_company_settings`).

**In — endpoints** (`/api/tenant/company-settings`, no path id —
each company can only ever address its own row, so the cross-tenant
surface is structurally absent):

- `GET` (`company.settings.read`) → 200 always; a company with no
  row yet gets the all-null view (unset everywhere). Raw stored
  values only — fallbacks are the consumers' business and are
  documented here, not echoed as phantom stored values.
- `PUT` (`company.settings.manage`) → 200 upsert of the single row.
  Validation (a recorded normalization — legacy silently clamps
  out-of-range values, the new surface rejects them):
  `monthStartDay`/`monthEndDay` null or 1–31; `overtimeRate`/
  `monthlyLeaveAccrual` null or ≥ 0; `weeklyOffDays` free text ≤ 60
  chars. Nulls are real values ("unset this").

**In — consumer rewiring:**

- **Payroll fiscal periods**: `PayrollBatchService.create` replaces
  its calendar-month period with legacy
  `payroll_fiscal_period_bounds()`, ported exactly: start day =
  setting or 1; end day = setting or the month's last day; each
  capped to the actual last day of its month; **when start > end the
  period spans from the previous month's start day to this month's
  end day** (e.g. 26→25). Companies with no settings row keep
  today's calendar-month behavior byte-for-byte (the fallbacks
  reproduce it), so every existing test stays green unchanged.
- **Leave accrual**: `RequestService`'s auto-created balance reads
  `monthly_leave_accrual` (fallback 21.0 when unset/no row) instead
  of the bare constant — closing that open question fully.
- The settings module exposes one read seam for both:
  `CompanySettingsService.effective(companyId)` returning a typed
  record with fallbacks already applied for scalar reads
  (`monthStartDay` int, `monthEndDay` Integer nullable — null still
  means month-end, which depends on the month and so cannot be
  pre-applied — and `monthlyLeaveAccrual` BigDecimal); called inside
  the caller's tenant transaction like every cross-module read.

**Out (tracked):** `overtime_rate` consumption (payroll overtime
calc — its legacy formula is a separate read); `weekly_off_days`
consumption (working-day/holiday calculation, no rewrite consumer
yet); the definitions/allowed-values catalog and bilingual labels
(they served the dashboard UI ADR-0009 replaces; if a future client
needs a generic settings contract it layers over the typed table);
the EAV data conversion (cutover run, recorded above).

## Design

Package `com.workin.backend.companysettings`: entity
(`CompanySettings`), repository (`findByCompanyId`), service
(`view`, `upsert`, `effective`), controller, records
(`UpdateCompanySettingsRequest`, `CompanySettingsView`,
`EffectiveCompanySettings`). Payroll's period computation stays in
`PayrollBatchService` (period math is payroll domain; settings only
supply the inputs). Upsert concurrency rides the real
`UNIQUE (company_id)` — a lost race surfaces as the same
conflict-throw pattern as leave-balances' duplicate create.

## Testing

`CompanySettingsFlowTest`: GET with no row → 200 all-null; PUT
creates → GET round-trips; second PUT updates the same row (SQL:
still one row); out-of-range day/negative rate → 400; company B's
PUT leaves company A's values untouched and B's GET never shows A's
values; read-without-manage → 403 on PUT; unauthenticated → non-2xx.

Payroll rewiring (new cases in `PayrollBatchLifecycleTest`): with
start=26/end=25, a March 2026 batch spans 2026-02-26 → 2026-03-25;
with start=31 and a February 2026 batch, the start caps to 2026-02-28
(legacy's last-day cap); with no settings row, periods stay exactly
calendar-month (regression pin for every existing batch test).

Leave rewiring (new case in `RequestModuleFlowTest`): with
`monthly_leave_accrual` = 15.5, an approve with no balance row
auto-creates total 15.5 (used = day count); the existing 21.0 test
keeps passing untouched as the no-settings case.

## Consequences

Both open questions close completely and two shipped modules lose
their documented placeholders. `overtime_rate` and
`weekly_off_days` sit ready for the payroll-overtime and
working-day slices with no schema change.

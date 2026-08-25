# Wave 12 completion audit

Working audit for the remaining Phase 1 Wave 12 scope after Wave 12.7.

Authoritative source: frozen `workin-hr/hr-legacy` API tree at `d113204c8a2cf83b997c5e65c6c86e4f59b3f8f6` plus the Java delivered-route inventory.

## Remaining endpoint scope after Wave 12.7

- Deferred Wave 12.6 attendance: 5 routes — `list`, `stats`, `employee_monthly_attendance`, `overall_report`, `export`.
- Wave 12.8: 20 routes — `salary_contracts` 5, `advances` 8, `penalties` 7.
- Wave 12.9: 16 routes — `payroll_batches` 10, `payslips` 6.
- Wave 12.10: 3 routes — `company/update`, `company/upload_logo`, `company/upload_commercial_reg`.
- Wave 12.R: retrofit already-implemented routes that are not yet exact legacy-path/envelope compatible, then run bidirectional route coverage.

That is 44 newly delivered routes before the compatibility retrofit. With the 62 routes delivered through Wave 12.7, the route inventory would reach 106 if every scoped route shipped -- but `attendance/overall_report.php`, `attendance/export.php` and `payslips/export.php` are each deliberately deferred (D-101, D-106) as binary/report responses outside a wave's JSON-route slice, so the inventory settles at **103** once Wave 12.9 and 12.10 are both in, before Wave 12.R changes compatibility classification.

## Execution state

- `salary_contracts`: **implemented and slice-reviewed (5/5)** — production controller/service/store, route guard, inventory and focused regressions are present.
- `advances`: **implemented and adversarially slice-reviewed (8/8)** — all frozen routes (`create`, `list`, `one`, `update`, `approve`, `reject`, `pay`, `delete`) are mapped; focused tests lock role/ownership behavior, null-coalescing, deduction normalization, overpayment, pending-only edits and the legacy id-only action quirks.
- `penalties`: **implemented and adversarially slice-reviewed (7/7)** — CRUD/list/stat/report routes, notification persistence, quarter-day normalization, manager branch scope, historical salary-contract penalty valuation and the legacy `format=csv` → styled XLSX download are present. Wave 12.8 is complete at **20/20** routes.
- Deferred attendance: `stats.php`, `list.php` and `employee_monthly_attendance.php` are mapped (Wave 12.6.4b). `list.php` ports both the regular paginated query and `fill_days=1` calendar expansion, including employee/company scoping, search/date filters, active accepted roster filtering, numeric employee-code ordering, incomplete/timed-request worked-minute calculation, rest/holiday rows, weekly-rest credit state, synthetic rows and cap-at-today behavior. `overall_report.php` and `export.php` remain deliberately deferred (binary/report responses, D-101).
- `payroll_batches`: **implemented (10/10)** across two slices -- D-104 (CRUD + `fiscal_period.php`, no calculation engine) then D-105 (`calculate`, `finalize`, `reopen`, `stats`, the transactional side effects and the calculation engine itself).
- `payslips`: **implemented (5/6)** — D-106. `list.php`, `one.php`, `create.php`, `update.php`, `delete.php` are mapped, including the full `payroll_enrich_payslip_row()` live-recompute path. `export.php` is deliberately deferred (binary XLSX response, same class of exclusion as `attendance/export.php`).
- `company`: **implemented (3/3)** — `update.php`, `upload_logo.php`, `upload_commercial_reg.php` (Wave 12.10, D-102).
- `attendance_exception_types`: **implemented (5/5)**, retrofitted off `/api/legacy/**` (D-107) -- Wave 12.R slice 1 of 5. First proof that "retrofit" means route + envelope + fresh PHP-fidelity re-verification, not a routing-only change: this slice fixed a `{field}`-placeholder substitution gap, a `String.trim()`-vs-PHP-`trim()` charlist mismatch, and discovered `exception_types.created_at`/`updated_at` are the schema's rare `timestamp` columns (not `datetime`), which round-trip through the session's `time_zone` and must be read fresh via `LegacyJdbcValues.rowMapper()` rather than through a JPA entity's `Instant` fields.
- `branches`: **implemented (6/6)**, retrofitted off `/api/legacy/**` (D-108) -- Wave 12.R slice 2 of 5. Same retrofit shape as slice 1, applied to a module sharing its package with two still-unretrofitted siblings (`departments`, `job_titles`): `LegacyWireExceptionHandler` gained an `assignableTypes = LegacyBranchController.class` entry alongside its `basePackages` list, verified empirically (not just by documentation) to leave the sibling controllers' `ApiErrorBody` contract untouched by re-running their own end-to-end tests unchanged. Two more D-071-shaped numeric-coercion gaps found and given the same disclosed, not-fully-measured fix: `radius_meters`/`latitude`/`longitude` have no PHP-side cast in either `create.php` or `update.php`.
- Delivered bidirectional route inventory is now **114**.
- Remaining deferred (deliberately, not pending): `attendance/overall_report.php`, `attendance/export.php`, `payslips/export.php`.
- Wave 12.R (compatibility retrofit/route-coverage audit): **2 of 5 module slices done** (`attendance_exception_types` D-107, `branches` D-108). Remaining: `departments` (5 routes), `job_titles` (5), `auth/login_employee` (1) -- 11 endpoints.
- **Process correction, D-109**: deleting a retrofitted module's `/api/legacy/**` route can silently break *other* modules' tests that borrowed it as a generic "any real guarded endpoint" fixture. D-107's push broke three `com.workin.legacy.auth` tests this way -- invisible in a local `./gradlew clean test` run on this machine's timezone, only caught by CI (`TZ=UTC` reproduces it locally). Every remaining Wave 12.R slice must grep the whole test tree for the module's old route/DTOs before being declared done, not just run its own tests.

### `attendance_exception_types` retrofit notes (Wave 12.R slice 1, D-107)

- `LegacyExceptionType`/`LegacyExceptionTypeRepository` (JPA against `legacyDataSource`, the real MariaDB schema) were already architecturally correct and are unchanged -- the D-074 drift this wave corrects is route/envelope surface, not datasource.
- `com.workin.backend.i18n.ApiException` (no replace-map support) is replaced with `com.workin.legacy.wire.LegacyApiException` throughout the service, fixing a latent bug where `field_required`'s `{field}` placeholder could never substitute through `LegacyWireExceptionHandler.handlePlatform()`'s hardcoded `null` replace map.
- `update.php`'s `is_active` binds PHP's raw JSON-decoded value with no cast (`whitelist_update_fields()`); this slice applies `create.php`'s own `(int)`-cast-then-truthy rule as a disclosed approximation, the same open-measurement shape D-071 already flagged for `branches`.

### `branches` retrofit notes (Wave 12.R slice 2, D-108)

- Body keys switched from the old REST controller's camelCase (`radiusMeters`/`isActive`/`locationLink`/`expiresAt`) to the wire's own snake_case (`radius_meters`/`is_active`/`location_link`/`expires_at`); `generate_qr.php`'s id now comes from `?id=`, not a `/branches/{id}/qr` path segment.
- `com.workin.legacy.organization` cannot be added wholesale to `LegacyWireExceptionHandler`'s `basePackages` yet (it still holds the unretrofitted `LegacyDepartmentController`/`LegacyJobTitleController`); `assignableTypes = LegacyBranchController.class` targets this one class precisely instead, and Spring combines it with `basePackages` by OR, confirmed by re-running `LegacyDepartmentEndToEndTest`/`LegacyJobTitleEndToEndTest` unchanged. Whichever of `departments`/`job_titles` retrofits next needs the same `assignableTypes` entry against its own sibling.
- `branches.created_at` is also `timestamp` (D-107's finding recurs); `branches` has no `updated_at` column at all (verified against schema, not assumed from `exception_types`' shape); `expires_at` is `datetime` (safe, timezone-naive) and is application-set only.

### Attendance-list adversarial review notes

- Preserved method → bare `requireAuth()` → active-company order. PHP computes an Admin/HR boolean but does not use it to gate the route, so Java does not add a role restriction.
- `fill_days` is true only when the parameter is present and neither empty-string nor the literal `0`; its path deliberately bypasses normal page/limit input and reports meta as page 1 with limit `max(1,total)`.
- If either `from` or `to` cannot be parsed in the fill-days branch, both dates fall back to the current month, matching the frozen source. Reversed normalized ranges fail `invalid_input`.
- Fill-days employee enumeration is company-scoped, forces EMPLOYEE users to their authenticated employee id, applies optional employee/branch/department filters only for non-employees, preserves numeric-code-only search, excludes pending join requests, requires active employees and keeps the frozen numeric-first employee-code ordering.
- Calendar expansion keeps the last attendance row for a date after ascending `check_in` order, looks back seven days for holiday/rest-credit context, caps future dates at today, and preserves earned/void/pending weekly-rest state.
- Exception-only attendance rows null their punches and expected duration while timed approved requests may still contribute worked minutes. Missing ordinary days can likewise gain timed-request duration; only zero-duration missing rows are flagged `is_missing=true`.
- Regular list preserves the frozen latest-shift subqueries without an `effective_from <= attendance_date` predicate and then overrides raw `TIMESTAMPDIFF` with the shared worked-minute helper.
- Synthetic IDs use the legacy stable negative formula after employee metadata is merged.

### Salary-contract adversarial review notes

- Preserved PHP method → auth role list → active-company guard order.
- Reads permit `company_admin`, `hr`, `manager`; writes permit only `company_admin`, `hr`.
- `daily` mode zeros `basic_salary`, transport/food/risk allowances and incentives, while preserving `daily_wage` and deductions.
- Invalid update salary modes fall back to `monthly`; create treats every non-`daily` value as `monthly`.
- `daily_wage` update uses `array_key_exists` semantics so explicit null/empty clears it.
- Employee existence and contract ownership remain company-derived through `employees.company_id`.
- Post-write re-read remains id-only, matching PHP rather than adding a new scoped read.

### Advance adversarial review notes

- Preserved method → auth role list → active-company ordering for all eight routes.
- `list`, `one` and the `update` preflight retain company scoping; `approve`, `reject`, `pay` and `delete` deliberately retain the frozen source's id-only lookup/write behavior rather than adding new tenant filtering.
- Employee create ignores caller-supplied `employee_id`/`status`, uses the authenticated employee and forces `pending`; admin/HR create keeps PHP's unvalidated initial status behavior.
- Employee update can only alter amount/reason and resets `remaining` to the chosen amount, exactly as PHP does.
- PHP `??` semantics are preserved on update: an explicit JSON null for amount/reason/status falls back to the stored value.
- `array_key_exists` semantics remain distinct for deduction payroll year/month and installments JSON, so explicit null/empty clears those values.
- Invalid deduction mode/type values normalize to the same legacy defaults.
- Missing rows after id-only approve/reject post-write re-read remain unexpected failures rather than being modernized to 404.
- JDBC row mapping uses `LegacyJdbcValues.read(..., sqlType)` so DECIMAL and temporal values keep PDO-compatible wire representation.

### Penalty adversarial review notes

- `create`, `update` and `delete` remain Admin/HR-only; `list` and `one` keep bare `requireAuth()` behavior; `stats` and `report` allow Admin/HR/Manager. Every route preserves method → auth → active-company order.
- Penalty days accept only the frozen quarter-day whitelist `0.25, 0.5, 1, 2, 3, 4, 5` with PHP's `< 0.0001` tolerance.
- Employee list access remains own-row only; company roles use company scope, and Manager adds the legacy same-branch subquery. `one.php` deliberately performs an id-only read before ownership/company/branch rejection.
- `update.php` and `delete.php` preserve the unusual default **400** `not_found` response from `fail(LangKey::NOT_FOUND)` rather than modernizing it to 404, and already-applied penalties remain immutable with 403.
- Dynamic update fields preserve the frozen whitelist order and `array_key_exists` behavior, including explicit null values.
- Create persists the `penalty_issued` employee notification after the penalty re-read, with the same company/to/from/reference fields and localized date body.
- Stats value penalties against the salary contract effective on each penalty date, use the fixed 30-day divisor and PHP-style two-decimal rounding, then sum the rounded per-row amount.
- `report.php?format=csv` intentionally returns XLSX, not CSV, keeps the frozen nine-header/seven-row-value mismatch, the `Report` sheet, no-cache/content-length headers and the configured legacy date in the filename.

## CI infrastructure blocker (resolved)

GitHub-hosted runner provisioning previously failed before the first job step for this private organization/repository. On head `5c6ce7acea9f9c513db3f08fa4f28014529524a1`, `Backend Validate` run #278 completed as failure with its only job showing `steps=null`; no build or test command ran, so that was never an application test result.

Root cause: org-level GitHub Actions billing on the private `workin-hr` org blocked runner assignment entirely, not a workflow or code defect. Resolved by making `hr-platform` public (the user's explicit choice, R-009), which runs on GitHub's free public-repo Actions minutes with no billing dependency. Runners have been assigned and executing real build/test steps since. A separate, unrelated blocker -- the Codex cloud-review quota on the connected ChatGPT/OpenAI account -- is still open and requires the user to act at `chatgpt.com/codex/cloud/settings/usage`; it affects only the optional AI code-review step, not `test`/`validate`.

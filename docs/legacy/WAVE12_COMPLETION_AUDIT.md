# Wave 12 completion audit

Final Phase 1 audit for Wave 12 after Wave 12.7.

Authoritative source: frozen `workin-hr/hr-legacy` commit `d113204c8a2cf83b997c5e65c6c86e4f59b3f8f6` plus the bidirectional Java literal-route inventory.

## Completion result

Wave 12 is complete for its agreed Phase 1 JSON/API scope.

- Wave 12.8: **20/20** — `salary_contracts` 5, `advances` 8, `penalties` 7.
- Wave 12.9: `payroll_batches` **10/10** and `payslips` **5/6**. `payslips/export.php` is deliberately excluded because it is a binary XLSX response.
- Wave 12.10: **3/3** — `company/update.php`, `company/upload_logo.php`, `company/upload_commercial_reg.php`.
- Deferred attendance JSON work: `list.php`, `stats.php`, and `employee_monthly_attendance.php` are implemented. `overall_report.php` and `export.php` remain deliberate binary/report exclusions.
- Wave 12.R compatibility retrofit: **5/5 module slices complete**.
  - `attendance_exception_types`: 5/5 — D-107.
  - `branches`: 6/6 — D-108.
  - `departments`: 5/5 — D-110.
  - `job_titles`: 5/5 — D-110.
  - `auth/login_employee.php`: 1/1 — D-110/D-111.

The delivered client-facing inventory is **125 literal `/apis/**` routes**.

The only three frozen endpoints deliberately outside this completion boundary are:

- `/apis/api/attendance/overall_report.php`
- `/apis/api/attendance/export.php`
- `/apis/api/payslips/export.php`

They are exclusions by recorded scope, not missing JSON-route implementation.

## Phase 1 compatibility invariant

D-111 is authoritative: Phase 1 is a zero-client-change PHP-to-Java replacement. The existing mobile application, desktop application, and web administration client must not require code changes because the backend implementation changes language.

For every delivered route Java therefore preserves the frozen PHP client-visible behavior: literal path, method handling and guard order, request/query coercion, status codes, D-074 response envelope, field names, authentication/session semantics, SQL-observable ordering, and intentional legacy quirks unless a separately recorded critical security exception exists.

### Employee login

`/apis/api/auth/login_employee.php` now follows the frozen PHP contract rather than the later target auth architecture:

- response data contains `token` and public `employee`; no `refresh_token` is added;
- successful login increments `employees.token_version`;
- existing employee push-token rows are deleted;
- the new token version is re-read before token issuance;
- the employee payload removes `password_hash` and `token_version`;
- `branch_location_configured` keeps the frozen any-branch behavior;
- the JWT is the PHP-compatible HS256 payload containing `type`, `employee_id`, `company_id`, `role`, `token_version`, and `exp`;
- the Phase 1 lifetime remains the frozen PHP value of 87,600 hours;
- PHP company tokens containing `type=company`, `company_id`, `role`, and `exp` are also accepted without incorrectly requiring employee-session claims.

Short-lived access tokens and rotating refresh tokens remain a later modernization target and do not alter Phase 1 compatibility.

## Production route boundary

The transitional REST aliases used while Wave 12.3 and early auth work were being developed are no longer part of production source:

- `/api/legacy/departments/**`
- `/api/legacy/job_titles/**`
- `/api/legacy/auth/login_employee`

Their controller implementations were moved from `src/main` to `src/test` only so the broad pre-D-074 regression suites keep exercising the previously reviewed business behavior without shipping those aliases in the application artifact. The production client contract is the 125 literal `/apis/**` routes only.

This satisfies D-109 without deleting or weakening historical regression coverage.

## Notable parity findings preserved

- `attendance_exception_types` fixed field-placeholder substitution, PHP trim semantics, and lexical `TIMESTAMP` reads.
- `branches` preserves PHP snake-case inputs and route/query conventions, including the previously audited numeric-coercion caveats.
- attendance list/stats preserve the frozen guard order, date/coercion rules, weekly-rest behavior, timed requests, synthetic rows, and legacy shift-resolution behavior.
- payroll batch calculation/finalize/reopen preserve the distinct legacy helper behavior and transactional payroll side effects.
- payslip create, update, and enrichment remain three distinct calculations rather than being incorrectly unified.
- the live `weekly_off_days` key-case defect was fixed separately under D-103 and merged to `main`.

## Validation gate

Wave 12 may be presented for human review only when both required GitHub workflows are green on the final PR head:

- `Backend Validate`, including `./gradlew clean test compilePhase2TestJava`;
- `Phase 0 Bootstrap Validate`, including structural, ADR, markdown, YAML, shell, action, secret, and local-link checks.

The PR must remain unmerged until human review. A green CI result proves the branch validation gate; it does not authorize an agent merge.

# Wave 12 completion audit

Final Phase 1 audit for Wave 12 after Wave 12.7.

Authoritative source: frozen `workin-hr/hr-legacy` commit `d113204c8a2cf83b997c5e65c6c86e4f59b3f8f6` plus the bidirectional Java literal-route inventory.

## Completion result

Wave 12 is complete for its agreed Phase 1 JSON/API scope, with **three endpoints still
open** rather than excluded -- see "Correction -- three open endpoints, not three
exclusions" below.

- Wave 12.8: **20/20** — `salary_contracts` 5, `advances` 8, `penalties` 7.
- Wave 12.9: `payroll_batches` **10/10** and `payslips` **5/6**. `payslips/export.php` is a binary XLSX response and remains **open** per D-106's own follow-up, not excluded.
- Wave 12.10: **3/3** — `company/update.php`, `company/upload_logo.php`, `company/upload_commercial_reg.php`.
- Deferred attendance JSON work: `list.php`, `stats.php`, and `employee_monthly_attendance.php` are implemented.
  `overall_report.php` and `export.php` both remain **open** -- see the correction below.
- Wave 12.R compatibility retrofit: **5/5 module slices complete**.
  - `attendance_exception_types`: 5/5 — D-107.
  - `branches`: 6/6 — D-108.
  - `departments`: 5/5 — D-110.
  - `job_titles`: 5/5 — D-110.
  - `auth/login_employee.php`: 1/1 — D-110/D-111.

The delivered client-facing inventory is **125 literal `/apis/**` routes**.

The three frozen endpoints still outstanding after Wave 12 are:

- `/apis/api/attendance/overall_report.php`
- `/apis/api/attendance/export.php`
- `/apis/api/payslips/export.php`

## Correction -- three open endpoints, not three exclusions

This audit as first written called all three "exclusions by recorded scope, not missing
JSON-route implementation". Both halves of that sentence are wrong, in two different ways, and
the errors are corrected here rather than left in place.

### `overall_report.php` is a JSON endpoint, not a binary one

`apis/api/attendance/overall_report.php` at frozen `d113204` builds `$report` through
`overall_attendance_report_build()`, strips the internal `_period_from`/`_period_to` keys, and
ends at:

```php
ok(LangKey::OK, $report, 200);
```

`ok()` (`apis/helpers/functions.php:380`) is the same D-074 envelope helper every delivered route
uses. The file contains no streaming path, no `: never` helper, and no binary response of any
kind. It is an ordinary JSON read endpoint.

Why it was conflated with `export.php`: they were blocked *together*, on the broad Wave-12.6 J.2
payroll boundary, because both reach the same six DB-backed payroll functions. That shared
blocker is real. It is not the same thing as `export.php`'s binary-response rationale, and
applying one file's rationale to the other collapsed "blocked" into "excluded".

### None of the three is excluded -- they are open

`attendance/export.php` and `payslips/export.php` genuinely are binary: both terminate in a
streaming helper declared `: never` (`data_export_attendance_csv`, `api_xlsx_export_send`). That
is a true statement about how much work they are. It is **not** a decision that Phase 1 need not
serve them, and the owning decisions say so in their own words:

- **D-101 Follow-up**: "`overall_report.php` and `export.php` remain **blocked** on the broader
  D-09x payroll boundary and are not part of this slice."
- **D-106 Follow-up**: "`payslips/export.php` (XLSX) **remains open**, to be picked up alongside
  or after Wave 12.R depending on whether binary-export support is prioritized before the
  retrofit audit."

Blocked and open are wave-scheduling states. Only an owner decision can turn one into an
exclusion, and none has been recorded. Legacy serves all three to real clients today.

### Consequences

- Item 12 is **not** closed. Three live endpoints remain owed, and Wave 12.6.6 stands at 0 of 2.
- The completion plan's ledger keeps its **198** live total and its **one**-row exclusion list
  (`time/now.php`, O-3); only the bucket distribution changes. See C9 in section 6 there.
- `LegacyPhpRouteInventoryTest.intentionallyDeferredBinaryReportsStayUnmapped` encoded the
  misclassification in its name and assertion set. It is split into
  `theTwoBinaryExportEndpointsAreStillUnmapped` and
  `theUnimplementedOverallReportEndpointIsStillUnmapped`, whose javadocs record that both
  assertions are to be **deleted**, not amended, when the endpoints are delivered.

Broad J.2 was recorded as "answerable only after Wave 12.7". Wave 12.7 has now landed, so the
question `overall_report.php` waits on is open for decision rather than blocked.

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
- payroll calculation performs its expensive read fan-out outside the locked transaction, then retries if a concurrent batch-period update committed before its row lock; this preserves pool capacity without overwriting the newer period (D-117/D-118).
- payslip create, update, and enrichment remain three distinct calculations rather than being incorrectly unified.
- concurrent write races closed in the final review round: employee advance edits lose to a
  concurrent approval, penalty mutations lose to batch finalization, payslip mutations are
  serialized with the batch lifecycle, and payroll-batch creation is serialized per company so
  one period cannot be inserted twice. Each folds its qualifying predicate into the write or
  takes the existing lifecycle lock, and resolves to an error code the endpoint could already
  return -- see `PR120_REVIEW_REMEDIATION.md`.
- the live `weekly_off_days` key-case defect was fixed separately under D-103 and merged to `main`.

## Validation outcome

Wave 12 merged to `main` on 2026-08-27 as squash commit `4caff98` (PR #120), and this section now
records what happened rather than the gate that preceded it.

Both required workflows were green on the final PR head `60dbfc6`:

- `Backend Validate` (run `33068895642`), including `./gradlew clean test compilePhase2TestJava`;
- `Phase 0 Bootstrap Validate` (run `33068895631`), including structural, ADR, markdown, YAML,
  shell, action, secret, and local-link checks.

Both were re-run green on the merge commit itself (`33070076202` and `33070076163`), reporting
151 classes and 1837 tests with no failures. That re-run matters independently of the branch
result: the squash combined this work with `b507f55` for the first time, a tree no branch build
had exercised.

The merge proceeded on the repository owner's own approval. The separate independent review that
`AGENTS.md`'s workflow places before human merge, and that
`PR120_REVIEW_REMEDIATION.md` lists under required validation, was not exercised as a distinct
gate. This is recorded as a factual deviation, not a retrospective objection: a green CI result
proves the branch validation gate and does not by itself constitute that review.

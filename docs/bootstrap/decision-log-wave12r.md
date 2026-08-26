# WorkIn Decision Log — Wave 12.R Continuation

This file continues `docs/bootstrap/decision-log.md` after D-107. Earlier decisions remain authoritative except where a later entry explicitly corrects or supersedes a Phase-1 detail.

## D-108: Retrofit `branches` onto the PHP wire contract

**Status:** Accepted 2026-08-25.

All six `branches` endpoints moved from the transitional `/api/legacy/branches` REST surface to the literal `/apis/api/branches/*.php` surface required by D-074. The retrofit preserves the already-reviewed D-056 through D-060 business behavior while restoring PHP method order, envelope, snake-case body keys, query `id`, and fresh lexical database reads for the `branches.created_at` `TIMESTAMP` column. D-060's company-scoped 404 remains the accepted security divergence instead of reproducing the legacy cross-tenant disclosure.

Evidence: frozen `hr-legacy` branch endpoints, schema inspection, `LegacyBranchEndToEndTest`, route inventory, and full backend validation.

## D-109: Whole-test-tree sweep is mandatory for every retrofit slice

**Status:** Accepted 2026-08-25.

A retrofit can break unrelated tests that borrowed an old `/api/legacy/**` route as a generic guarded endpoint. D-107 exposed this in authentication tests. Every Wave 12.R slice therefore has to account for old-route and DTO dependencies outside its own package and preserve the regression intent rather than deleting tests merely to make the route migration green.

For the final organization/login aliases, the production controllers are retired while equivalent pre-D-074 regression controllers live under `src/test` only. This keeps the historical business assertions without shipping the transitional routes. D-112 records that closure mechanism.

Evidence: D-107 regression history, shared auth tests, department/job-title end-to-end suites, and final full-suite validation on PR #120.

## D-110: Final Wave 12.R public-route boundary

**Status:** Accepted 2026-08-26.

The final public compatibility surface adds five literal `departments` routes, five literal `job_titles` routes, and `auth/login_employee.php`. The organization adapters reuse the already-reviewed Wave 12.3 business services and own PHP wire concerns: method order, query/body coercion, snake-case fields, envelope rendering, and fresh lexical database rows.

The bidirectional `/apis/**` inventory is **125 routes**. The three deliberate binary/report exclusions remain `attendance/overall_report.php`, `attendance/export.php`, and `payslips/export.php`.

The earlier draft of this decision incorrectly allowed the new-platform refresh-token design to remain on the Phase-1 employee-login route. D-111 supersedes that detail: the frozen PHP login and token behavior is authoritative for Phase 1.

The production `/api/legacy/departments/**`, `/api/legacy/job_titles/**`, and `/api/legacy/auth/login_employee` handlers are retired under D-112. They are not client contract aliases and are not included in the production application artifact.

## D-111: Phase 1 is a zero-client-change PHP-to-Java replacement

**Status:** Accepted 2026-08-25.

Phase 1 has one compatibility invariant: replacing PHP with Java must require no changes to the existing mobile application, desktop application, or web administration client. The frozen PHP application at `d113204c8a2cf83b997c5e65c6c86e4f59b3f8f6` is authoritative for the Phase-1 client-visible contract.

Java must preserve the PHP route, HTTP method behavior and guard order, query/body coercion, status codes, response envelope and fields, authentication token shape, session side effects, and legacy business quirks unless a separately recorded exception is required to prevent a concrete critical vulnerability.

For employee login, `auth/login_employee.php` returns the PHP `token` plus public `employee` payload and does not add a refresh token. Login increments `employees.token_version`, deletes the employee's existing push-token rows, re-reads the new token version, and issues the PHP-compatible HS256 payload containing `type`, `employee_id`, `company_id`, `role`, `token_version`, and `exp`. The Phase-1 lifetime remains 87,600 hours.

The compatibility chain also accepts the frozen company JWT used by desktop/company login: `type=company`, `company_id`, `role`, and `exp`. The employee session-version check applies only to `type=employee`, matching PHP.

Short-lived access tokens and rotating refresh tokens remain the target for a later modernization phase. They are not permitted to alter the literal Phase-1 `/apis/**` contract.

Evidence: frozen login/desktop auth source and helpers plus `LegacyLoginEndToEndTest` and Phase-1 security-chain regressions.

## D-112: Retire transitional production aliases without deleting regression coverage

**Status:** Accepted 2026-08-26.

Wave 12.R closes the transitional REST surface instead of leaving duplicate production APIs. The following controller classes were removed from `src/main` after their literal PHP replacements were established:

- the `/api/legacy/departments/**` controller;
- the `/api/legacy/job_titles/**` controller;
- the `/api/legacy/auth/login_employee` controller.

The same pre-D-074 controller behavior is retained under `src/test` solely as a regression harness for the broad Wave 12.3/auth suites. Test classes are not packaged into the production application, so this preserves coverage while ensuring deployed clients can only rely on the frozen PHP-compatible surface.

This is the D-109 closure pattern for large historical tests: preserve their business assertions, remove the obsolete production handler, and validate the real literal PHP routes independently through their compatibility tests and the bidirectional 125-route inventory.

Wave 12.R is complete when both required GitHub workflows are green on the final PR head. The PR can then move from Draft to Ready for Review, but remains subject to human merge approval.

## D-113: Request-scoped memoization for the payroll attendance read fan-out

**Status:** Accepted 2026-08-26.

PR #120 review identified that `payslips/list.php`'s enrichment loop -- `LegacyPayslipService.enrich()`, called once per payslip on the page -- drives `LegacyWeeklyOffDays.forCompany(companyId)` and `LegacyAttendanceCalendar.shiftForEmployeeOnDate(employeeId, date)` once per day of the pay period, directly and via `LegacyAttendanceCalendar.isWeeklyRestDay`'s own internal call to the former. For a 30-day period and a full page of payslips this is hundreds of avoidable round trips per HTTP request.

This is not a parity defect: the JSON response is byte-identical either way, and frozen PHP has the exact same per-day query shape. It is a production-risk divergence in what that shape costs. Legacy PHP ran one process per request against a short-lived connection; the Java backend holds a pooled HikariCP connection and a Tomcat thread for the entire enrichment loop, so the same query fan-out that was harmless per-process in PHP can exhaust the connection pool under a handful of concurrent `list.php` calls in Java. Silently reproducing the query count was judged the wrong default given that gap, so this is recorded as a decision rather than folded into the PR as an uncommented fix.

**Fix:** `LegacyWeeklyOffDays` and `LegacyAttendanceCalendar` are now request-scoped (`@RequestScope(proxyMode = ScopedProxyMode.TARGET_CLASS)`, the same mechanism and the same invariant `LegacyClock` and `LegacyPhoneCountries` already rely on: nothing writes `company_settings` or `employee_shift_assignments` mid-request, so a value read once is safe to reuse for the rest of that request). `forCompany` is memoized per company id; `shiftForEmployeeOnDate` per (employee id, date) pair, including the no-shift-assigned (`null`) case. Existing singleton-scoped callers across the attendance module are unaffected: `ScopedProxyMode.TARGET_CLASS` gives them an ordinary field reference that Spring transparently routes to the current request's instance.

**Scope boundary:** only these two specific read paths were changed. No other shared attendance/calendar behavior was touched, and no caching was added anywhere queries aren't already repeated identically within one request.

Evidence: `LegacyAttendanceQueryCachingTest` proves the memoization directly against a connection-budget-limited `DataSource` (a repeated cache key that is not actually served from cache exhausts the budget and throws), and was confirmed to fail against the pre-fix code before being confirmed to pass against the fix. Full backend suite green after the change (1795 tests).

## D-114: Close the concurrent finalize/reopen double-application race

**Status:** Accepted 2026-08-26.

`payroll_batches/finalize.php` and `reopen.php` each read the batch's current status on the pooled connection, then apply their side effects (advance deduction / restoration, penalty marking) inside a separately-opened single-connection transaction moments later. The read and the guarded write were never the same atomic operation, so two genuinely concurrent calls for the same batch could both observe the pre-transition status and both proceed -- reproduced directly against real MariaDB with two threads released by a shared barrier: both `finalize` calls returned 200, and the advance's `remaining` balance reflected the deduction being applied twice.

This is not a parity concern -- frozen PHP has the same read-then-write shape and the same theoretical race under a threaded SAPI, but Wave 12.9's own transactional plumbing (D-100's open/flip-autocommit/commit-or-rollback pattern) was already going further than a literal PHP port to guarantee atomicity for the write; leaving the precondition check outside that guarantee undid part of the point. A double-applied advance deduction is a real payroll-correctness defect, not a byte-for-byte PHP quirk worth preserving.

**Fix:** `LegacyPayrollBatchStore.finalizeBatchIfNotAlready`/`updateStatusIfCurrently` replace the unconditional status writes with an atomic compare-and-set (`UPDATE ... WHERE id=? AND status<>?` / `AND status=?`), run first inside the existing transaction. A losing concurrent call sees 0 rows changed and aborts with the same `batch_already_finalized`/`batch_not_finalized` error the pre-transaction check already used, instead of silently re-applying the side effects. No change to the single-caller behavior or the wire contract.

Evidence: a two-thread `CyclicBarrier`-synchronized test against real MariaDB in `LegacyPayrollBatchCalculateEndToEndTest` (`concurrentFinalizeCallsForTheSameBatchApplyTheAdvanceDeductionAtMostOnce`), confirmed to fail against the pre-fix code (both calls returned 200, advance balance showed a double deduction) and to pass reliably (3 consecutive runs) against the fix. Full backend suite green after the change (1808 tests).

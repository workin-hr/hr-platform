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

The bidirectional `/apis/**` inventory is **125 routes**. `attendance/overall_report.php`, `attendance/export.php` and `payslips/export.php` are unmapped.

**Corrected 2026-08-27 (C9, completion plan section 6).** This entry as first written called those three "deliberate binary/report exclusions". Two corrections. First, `attendance/overall_report.php` is not binary at all: it ends at `ok(LangKey::OK, $report, 200)` and returns the ordinary D-074 JSON envelope. Second, none of the three is excluded -- D-101 records its two as "blocked" and D-106 records `payslips/export.php` as "open", and neither is an owner disposition removing an endpoint from the Phase-1 obligation. All three are unimplemented live endpoints. **Their disposition was recorded 2026-08-28 as D-120: all three are delivered**, with Java reproducing PHP's response contract per endpoint. D-110's route boundary for the retrofit itself is unaffected -- none of the three was ever in Wave 12.R's 22.

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

## D-115: Second PR #120 review round -- shared-service field_required fidelity, and a fourth un-memoized attendance read

**Status:** Accepted 2026-08-26.

Codex's second review pass on PR #120 (head `f8c0f16`) found two real defects, both fixed here.

**P2 -- `LegacyJobTitleService`/`LegacyDepartmentService` never carried `{field}` replacements.** Both services still threw `com.workin.backend.i18n.ApiException` for their business-logic errors, the exact bug D-107 already found and fixed for `LegacyExceptionTypeService`: `LegacyWireExceptionHandler.handlePlatform()` always calls `messages.translate(locale, ex.getCode(), null)` with a hardcoded `null` replace map, so `field_required`'s `{field}` placeholder could never substitute for either service -- the client saw the literal text `Field '{field}' is required` for a missing `department_id`/`work_hours` (job titles) or `name`/`branch_ids` (departments). Both services are now `LegacyApiException`-based throughout, matching every other Phase-1 module. The frozen PHP source was re-read to get the replace-map behavior byte-exact, and it is inconsistent by design, not by an oversight here: `job_titles/create.php`'s `required()` gate names each missing field, but its later blank-name check (`if ($name === '') fail(LangKey::FIELD_REQUIRED, 400);`) passes no fourth argument at all, so that one path still renders the unsubstituted placeholder -- verified against the frozen source and preserved exactly.

One additional, deliberate divergence from literal PHP: both services' database-failure catch blocks (`create.php`'s `catch (Throwable $e) { ... fail(LangKey::FIELD_REQUIRED, 500, $e->getMessage()); }`) put the raw exception message in the wire response's `data` field in frozen PHP. This is CWE-209 information disclosure and is not reproduced -- D-111 permits deviation from literal PHP to prevent a concrete vulnerability, and this codebase already has standing policy against exception-detail leakage (the D-084 global-fallback precedent: "no exception text... The real exception is logged here instead"). The exception is preserved as the Java cause for logging, never sent to the client.

Fixing this exposed a real architectural tension worth recording: both services are shared between their production `/apis/api/**` controller (needs the PHP envelope) and a test-only `/api/legacy/**` regression alias retained under `src/test` per D-112 (needs the platform `{code,message}` `ApiErrorBody` shape for its own pre-D-074 assertions). Reverting the service's exception type was not an option -- that reintroduces the placeholder bug for the real client-facing route -- so each test-only alias controller (`LegacyJobTitleController`, `LegacyDepartmentController`, both under `src/test`) gained a local `@ExceptionHandler(LegacyApiException.class)` translating back to `ApiErrorBody`. This keeps the shared service's exception type uniform across all of Phase 1 while letting each caller own its own wire contract, and is the intended long-term shape for D-112's dual-controller pattern generally, not a one-off hack.

**P1 -- `LegacyAttendanceCalendar.holidaysByDate` was the one read D-113 missed.** D-113 memoized `LegacyWeeklyOffDays.forCompany` and `LegacyAttendanceCalendar.shiftForEmployeeOnDate`, both reached from `payslips/list.php`'s per-day enrichment loop, but `holidaysByDate` -- reached the same way, once per day via `expectedForDay`'s `holidaysByDate(companyId, date, date)` call -- was left unmemoized, still running one query per day per payslip. Fixed with the same per-key (`companyId|from|to`) `HashMap` cache already established for `shiftForEmployeeOnDate` on the same request-scoped bean; the returned map is never mutated by any caller (checked across the whole `com.workin.legacy` tree), so caching and returning the same instance is safe.

No production route, envelope, status code, or business behavior changed for either fix; both are wire-invisible.

Evidence: `hr-legacy/apis/api/job_titles/create.php`/`update.php`, `apis/api/departments/create.php`/`update.php` (all four, re-read in full to get the replace-map/no-replace-map distinction exact). `LegacyAttendanceQueryCachingTest` extended with `holidaysByDateIsMemoizedPerCompanyAndRangeNotJustPerCompany`, the same connection-budget-exhaustion proof technique the other two memoized methods already use. `LegacyOrganizationPhpFieldRequiredEndToEndTest` (new, 3 tests): proves the `{field}` substitution now works against the real production `/apis/api/job_titles/create.php` and `/apis/api/departments/create.php` routes -- neither module had *any* end-to-end coverage of its production PHP-route controller before this, only the pre-D-074 regression alias and the route-inventory's route-exists check. `LegacyJobTitleEndToEndTest`/`LegacyDepartmentEndToEndTest` (the pre-D-074 regression suites) re-verified green after adding the local exception translators. Full backend suite green locally under `TZ=UTC` (matching the `ubuntu-24.04` CI runner, per D-109's own lesson about this machine's timezone hiding regressions).

## D-116: Third PR #120 review round -- calculate/finalize race closed, a transitional-JWT identity-confusion bug, and two verified false positives

**Status:** Accepted 2026-08-27.

Codex's third review pass on PR #120 found six candidate issues. Two were real and are fixed here; two were real but already fixed by an earlier commit on this same branch and needed a stale-comment correction, not a code change; two were re-verified against frozen PHP and are recorded here as false positives so they are not re-flagged blind in a future round.

**P1 -- `calculate.php` raced `finalize.php`/`reopen.php` for the same batch.** `LegacyPayrollBatchService.calculate()` read the batch status via the pooled, autocommit `store` and then deleted and reinserted every payslip in the batch, entirely outside any transaction or lock -- confirmed directly from the pre-fix diff (`store.scoped(...)`, then five more calls through the same pooled `store`, no `inTransaction` anywhere in the method). A concurrent `finalize.php`/`reopen.php` call for the same batch -- itself transactional since D-100 -- could apply its advance/penalty side effects against an empty (mid-delete), partial, or stale payslip set. **Fix:** `calculate()` now runs inside the same single-connection transaction shape `finalize()`/`reopen()` already use (`LegacyPayrollBatchService.inTransaction`), taking a `SELECT ... FOR UPDATE` row lock on the batch (`LegacyPayrollBatchStore.scopedForUpdate`, new) as its very first statement. `finalize()`/`reopen()` needed no change at all: their existing CAS `UPDATE` (D-114) already takes the same row's lock when it runs, so calculate holding that lock for its own read-then-recompute-then-write is enough to serialize the two operations against each other.

**P1 -- a transitional Java token decoded as a zero-identity PHP token.** `LegacyPhpJwtService.decode()` verified the HMAC signature and `exp` claim but never checked for a `type` claim before this fix. A transitional Java token (`com.workin.backend.identity.JwtService`, claims `sub`/`membership_id`/`tenant_id`/`token_version`, no `type`) is signed with the exact same `app.jwt.secret` HS256 key `issueEmployeeToken()`/`issueCompanyToken()` use, so it passed signature verification cleanly and decoded to a non-null `DecodedToken` with every legacy field empty or zero. `LegacyPhpJwtAuthenticationFilter.doFilterInternal` takes any non-null `decode()` result as proof of a PHP-authenticated principal and never falls through to `setTransitionalAuthentication()` -- so a valid transitional caller was silently authenticated as identity/company `0`, type `""`, instead of their real transitional identity. **Fix:** `decode()` now returns `null` when the payload has no `type` claim, which routes the filter to the (correct) transitional-token branch. Confirmed real and necessary by tracing the full filter, not just the token codec in isolation -- see `LegacyPhpJwtAuthenticationFilter`.

**Stale-comment-only, not a bug -- `LegacyCompanyController.companySettingsRole()`'s company-token permission check.** The Codex finding here restated `companySettingsRole()`'s own comment, which claimed (D-042, written before any company-typed token existed in Phase 1) that `can_company_settings` was "checked unconditionally... exactly as PHP's own type === 'employee' branch does once that branch is the only reachable one." That premise is stale: `LegacyHrPermissionEnforcer.hasPermission()` already has its own "Company-type bypass" (`if ("company".equals(principal.legacyAuthType())) return true;`, added by commit `10880fc`, itself part of this same PR's security-review history and already on this branch before this round started), returning `true` unconditionally for a company-typed session *before* the `hr_permissions` table is ever queried. Calling `permissionEnforcer.has(...)` unconditionally, with no employee/company branch in the controller at all, therefore already matches PHP for both session types -- there was no reachable 403 for a company session to find. An initial fix attempt added a redundant `context.employeeId() > 0` guard around the same call; this was reverted (`git diff` against the pre-round commit for this file is comment-only) once tracing `LegacyHrPermissionEnforcer` end to end showed the guard could never change behavior. Only the stale D-042 comment was corrected.

**P2 -- zero-byte uploads were silently dropped instead of failing `invalid_file_type`.** Frozen PHP's `uploadFile()` (`functions.php:636-664`) gate is `!isset($_FILES[$input_name]) || $_FILES[$input_name]['error'] !== UPLOAD_ERR_OK` -- byte count plays no part in it. A part with a real (non-empty) filename and zero bytes still has `error === UPLOAD_ERR_OK` in PHP, so it falls through to `mime_content_type()`, which then fails allowlist validation like any other unrecognized type, returning 400 `invalid_file_type`. `LegacyFileUploads.store()`'s pre-fix gate was `file == null || file.isEmpty()` -- `MultipartFile#isEmpty()` is `true` for zero bytes regardless of whether a real filename was submitted, so a genuinely empty upload was silently treated as "nothing to upload" (returned `null`, no error) instead of being rejected. **Fix:** the gate is now `file == null || file.getOriginalFilename() == null || file.getOriginalFilename().isBlank()`, mirroring PHP's actual distinction -- an empty/missing filename is the browser's "no file chosen" shape (PHP's `UPLOAD_ERR_NO_FILE`); a filename with zero bytes is a genuine, error-free PHP upload that must reach MIME validation and fail there.

**False positive, re-verified -- `penalty_days: null` handling.** Codex assumed `computeEmployeePayslip`'s path used PHP `??` null-coalescing semantics for a null `penalty_days` value. Frozen PHP actually uses `array_key_exists($key, $row) ? (float) $row[$key] : 0.0`-shaped access, and `(float) null === 0.0` in PHP -- exactly what `store.unappliedPenaltyDays(...)`'s current Java implementation already does. No divergence exists; not changed.

**False positive, re-verified -- "batch calculation inserts `days_absent` as 0."** Traced the full `PayslipComputation`/`insertPayslip()` chain from `computeEmployeePayslip()` through to the SQL insert. `Math.max(0, attendance.daysAbsent())` correctly flows through at every step; no hardcoded zero exists anywhere on that path. `LegacyPayrollBatchCalculateEndToEndTest#calculateProducesTheHandVerifiedPayslipForACompletePresentPeriod` already asserts a non-zero `days_absent` for employee 2 and passes. Not changed.

**Test-suite consequence of the calculate() fix worth recording:** `calculate()`'s `batch_not_found`/`batch_already_finalized` checks now run inside the locked transaction, against a `LegacyPayrollBatchStore` the method builds around its own connection -- not the injected, mockable `store` field. The two `LegacyPayrollBatchServiceTest` unit tests that asserted the old pre-transaction-check shape (`calculateRefusesAnAlreadyFinalizedBatchBeforeTouchingTheTransaction`, `calculateThrowsBatchNotFoundForAForeignOrMissingId`) became unreachable with a mocked store and were removed; equivalent coverage now lives in `LegacyPayrollBatchCalculateEndToEndTest` (`calculateReturns404ForAForeignOrMissingBatchId`, `calculateRefusesAnAlreadyFinalizedBatch`) against the real database.

Evidence: `hr-legacy/apis/helpers/functions.php:636-664` (`uploadFile()`, re-read in full for the upload fix); `LegacyHrPermissionEnforcer.java` and its own "Company-type bypass"/"Trust boundary" javadoc, plus `git log`/`git show 10880fc` confirming that fix predates this review round on the same branch; `LegacyPhpJwtAuthenticationFilter.doFilterInternal`/`setPhpAuthentication`/`setTransitionalAuthentication`, traced in full for the JWT fix. New/extended tests: `LegacyPayrollBatchCalculateEndToEndTest#calculateBlocksOnTheSameRowLockFinalizeAndReopenAlreadyUse` (proves the new lock is real by holding it externally and asserting a concurrent `calculate()` call blocks until released, then succeeds), `#calculateReturns404ForAForeignOrMissingBatchId`, `#calculateRefusesAnAlreadyFinalizedBatch`; `LegacyLoginEndToEndTest#aTransitionalJavaTokenDoesNotDecodeAsAZeroIdentityPhpToken`; `LegacyFileUploadsTest` (new, 5 tests: null file, no-file-chosen, genuinely-empty-with-filename, unrecognized MIME, valid upload). Full backend suite green locally under `TZ=UTC`.

## D-117: Fourth PR #120 review round -- one missed P1 closed, delete/pay races closed, is_active/N+1 fixed, advance/penalty finalize drift disclosed and left as PHP parity

**Status:** Accepted 2026-08-27.

This round covered a finding missed out of the very first Codex pass (never actioned in D-116) plus two fresh Codex passes (7 more comments) triggered by the D-116 push. Five real defects fixed; one is byte-exact frozen PHP behavior deliberately left unchanged after explicit user sign-off; one earlier-round finding is confirmed a false positive on closer reading.

**False positive, re-verified -- "closed-period `days_absent` discards the live attendance result" (missed from round one).** `LegacyPayslipService.enrich()`'s closed-period branch (`daysAbsent = stored days_absent + void weekly rest` rather than the freshly recomputed value) is a byte-for-byte, comment-for-comment port of frozen PHP's `payroll_enrich_payslip_row()` (`payroll_calculation.php:545-561`), whose own comment reads: "While the month is still open, always recompute absence... After period end, prefer the stored value (may include HR edits / final batch calculate)." Preferring the frozen, possibly-HR-edited stored value for a closed period is the documented PHP design, not a bug reproduced by accident. Not changed.

**P1 -- `delete.php` raced `finalize.php`/`reopen.php` for the same batch, the same shape D-114/D-116 already found and fixed for `finalize`/`reopen`/`calculate`.** `delete()` read status via the pooled, unlocked `store` and then deleted the batch and its payslips unconditionally; a concurrent `finalize.php` could commit its status transition and advance/penalty side effects a moment later, and `delete()` would remove the finalized batch anyway -- leaving advances reduced and penalties marked applied with no batch left to account for or reopen them. **Fix:** `delete()` now takes the same `LegacyPayrollBatchStore#scopedForUpdate` row lock `calculate()` does, re-checking status and deleting inside one transaction.

**P1 -- `advances/pay.php` had a classic lost-update race.** `pay()` read `remaining`, computed the new balance in Java, and wrote it back unconditionally -- byte-exact to frozen PHP's own `pay.php`, which has the identical read-then-write shape. Two genuinely concurrent payments against the same advance could both read the same stale `remaining`, both pass the overpayment check, and the second write would silently clobber the first's result, losing one payment. This is the same class of bug D-114 already established precedent for closing even though PHP has it too (a lost payment is a real payroll-correctness defect, not a quirk worth preserving). **Fix:** `LegacyAdvanceStore.payIfSufficientBalance` folds the read, the overpayment check, and the write into one atomic `UPDATE advances SET remaining = remaining - ? WHERE id=? AND remaining >= ?`; 0 rows affected means the balance was insufficient (by original amount or by a concurrent payment) and `pay()` rejects with `payment_exceeds_remaining`.

**P2 -- connection-pool exhaustion risk from holding a pooled connection open across `calculate()`'s per-employee fan-out.** A second Codex pass on the D-116 push found that `calculate()`'s transaction (opened by `inTransaction`, holding one pooled connection for its whole duration) also called `LegacyPayrollFiscalSettings`/`LegacyPayrollOvertimeSettings`/`LegacyPayrollAttendanceFigures` -- each with its own independently-constructed `JdbcTemplate` wrapping the *same* pooled `DataSource` -- once per calculate() call, and the attendance figures once per active employee. Under the default ten-connection pool, a realistic month-end payroll rush (a handful of companies calculating around the same time) could have every connection held by an in-flight `calculate()` while each also waits on the same pool for another, timing out and rolling back. **Fix:** `calculate()` now runs its entire per-employee computation (`computeBatch`, fiscal bounds, overtime settings, and every employee's attendance-figures fan-out) on the pooled connection *before* opening the locked transaction; the transaction itself only re-verifies the lock/status and does the fast delete-then-insert with the already-computed values, so the slow, many-round-trip fan-out never overlaps with a held transaction connection. This trades away one guarantee, disclosed in `calculate()`'s own javadoc: the computed values reflect the fiscal period read at precheck time, not the one the lock re-reads a moment later, so a concurrent `payroll_batches/update.php` changing the same batch's month/year in that exact window could leave a stale-period calculation -- narrow, and self-healing the same way `calculate()`'s own idempotency already relies on (call it again). It does not weaken the finalize/reopen/delete race guarantees this method exists to close.

**P2 -- `branches/update.php` collapsed a nonzero, non-1 `is_active` to `0` instead of storing it verbatim.** Frozen PHP's `branches/update.php` binds every updatable column's raw JSON-decoded value directly into its `UPDATE`, with no cast at all -- confirmed by re-reading the source. A client sending `is_active: 2` therefore has MariaDB's own non-strict `TINYINT` coercion store the literal `2` in PHP, while the Java service coerced through `toBoolean()`/`setActive(boolean)`, always collapsing to `0` or `1`. **Fix, partial and disclosed:** added `LegacyBranch#setActiveRaw(Integer)`, reusing the class's existing `toInteger()` D-071-style raw-numeric coercion (already used for `radius_meters`) instead of the stricter `toBoolean()`. This corrects the column-storage divergence. It does **not** fix `LegacyBranchView`/`LegacyBranchListItem`'s own `is_active` response field, which stays a Java `boolean` (`branch.active()`, strict `== 1`) across every branch endpoint -- widening every branch (and by extension every other Phase-1 module's) `is_active`-shaped response field to a raw passthrough type was judged disproportionate to a case no legitimate client produces (a boolean-semantic UI toggle, not free-form input), matching this file's own existing, accepted D-071 gap for `radius_meters`.

**P2 -- `job_titles/list.php`'s N+1 wire-row re-fetch.** `wireRow(LegacyJobTitleView)` re-fetches its row with one `SELECT * FROM job_titles WHERE id=?` per call, needed for wire-faithful `created_at` formatting (the same TIMESTAMP-vs-DATETIME fresh-read pattern documented elsewhere in this codebase) -- but `list.php` called it once per row on an unpaginated list, one extra round trip per job title. **Fix:** `wireRowsByCompany` fetches every one of the company's job-title wire rows in a single company-scoped query; `list.php` looks each view up from that map instead of re-querying. `one.php`/`create.php`/`update.php` are unaffected (still single-row fetches).

**Deliberately unchanged, user-confirmed -- advance-deduction/penalty-marking drift between `calculate.php` and `finalize.php`.** Two related P1 findings: `finalize.php` re-derives advance-deduction items and the penalty-applied sweep from *live* `advances`/`penalties` rows at finalize time, not from a snapshot of what `calculate.php` actually stored on the payslip. An advance approved/edited, or a penalty created, in the window between the two calls can therefore be deducted/marked without ever having appeared in the payslip HR reviewed. Verified byte-exact against frozen PHP's `payroll_finalize_batch_side_effects()` (`payroll_calculation.php:1021-1059`) -- same live re-read, same range-wide `UPDATE ... SET applied_to_payroll=1`. Three remedies were considered and put to the user: (1) leave as documented PHP parity; (2) have `finalize()` recompute the batch atomically before applying side effects -- rejected, since `payslips/update.php` explicitly allows HR to manually edit a payslip's stored values up until finalization (verified: `update.php` rejects only once `batch_status === 'finalized'`), so a silent recompute would overwrite those edits, trading one correctness bug for a different, worse one; (3) snapshot itemized deduction/penalty items at calculate time -- most correct, but needs a schema change (new table or JSON column) disproportionate to this review round. User chose (1): documented in code (`LegacyPayrollBatchService.applyAdvancePaymentsAndMarkPenalties`'s own javadoc) and here, not changed.

Evidence: `hr-legacy/apis/api/payroll_batches/{delete,finalize}.php`, `apis/api/advances/pay.php`, `apis/api/branches/update.php`, `apis/helpers/payroll_calculation.php` (`payroll_enrich_payslip_row`, `payroll_finalize_batch_side_effects`), all re-read in full. New/extended tests: `LegacyPayrollBatchCalculateEndToEndTest#deleteRemovesADraftBatchAndItsPayslips`, `#deleteRefusesAnAlreadyFinalizedBatchAndLeavesItIntact`, `#deleteBlocksOnTheSameRowLockCalculateFinalizeAndReopenAlreadyUse` (same externally-held-lock proof technique as the calculate() lock test); `LegacyAdvancePayEndToEndTest` (new file, 2 tests: a `CyclicBarrier`-synchronized two-thread concurrent-payment test proving both 60.00 payments against a 1000.00 balance land as 880.00 not 940.00, and an overpayment-rejection test). `LegacyPayrollBatchServiceTest`/`LegacyAdvanceServiceTest` updated for the `delete()`/`pay()` restructuring the same way D-116 updated `calculate()`'s tests. Full backend suite green locally under `TZ=UTC` (1822 tests).

## D-118: Calculate retries when a concurrent batch-period update wins before the row lock

**Status:** Accepted 2026-08-27.

Moving `calculate.php`'s expensive attendance/settings fan-out before its
transaction in D-117 closed a connection-pool exhaustion path, but introduced
a lost-update window. `calculate()` read and computed month/year before taking
the batch-row lock, then unconditionally wrote that stale period after acquiring
the lock. If `update.php` committed a new period while calculate waited, the
calculation silently restored the old month/year and inserted payslips for it.
The D-117 statement that a later calculate call would self-heal was incorrect:
the concurrent update itself had already been overwritten.

The fix preserves D-117's pool-safety boundary. Computation remains outside the
transaction. Once the existing `SELECT ... FOR UPDATE` lock is acquired,
calculate compares the live month/year with the period used for the computed
payslips. A mismatch performs no writes, releases the transaction, and retries
from the newly committed period. An update that begins after this comparison
linearizes after calculate and retains legacy's existing behavior that changing
a period does not itself recalculate payslips.

Evidence: `LegacyPayrollBatchCalculateEndToEndTest#calculateRetriesWhenTheBatchPeriodChangesBeforeItsRowLock`
holds the row lock, starts a September calculation, changes the locked row to
February, commits, and then asserts both the February batch period and February
attendance-derived payslip. It failed before the fix (`month` returned `1` in
the initial January reproduction, then the unique-period fixture was moved to
September for whole-class isolation) and passes after the optimistic retry.

## D-119: The legacy row-count contract is enforced at startup, not only documented

**Status:** Accepted 2026-08-27.

D-113/D-114's advance and penalty guards fold their qualifying predicate into
the write itself -- `AND status='pending'` in `LegacyAdvanceStore.updateEmployee`,
`AND applied_to_payroll=0` in `LegacyPenaltyStore.updateFields`/`deleteById` --
and resolve a lost race from an affected-row count of zero. That inference is
only sound while the connection reports rows *matched* by the `WHERE` clause
rather than rows actually *changed*, i.e. while `CLIENT_FOUND_ROWS` is in
effect. MariaDB Connector/J controls this with `useAffectedRows`.

The dependency was recorded in `docs/legacy/PR120_REVIEW_REMEDIATION.md` but
nothing enforced it. A deployment that added `useAffectedRows=true` to
`LEGACY_DB_JDBC_URL` would make every edit resubmitting already-stored values
change no rows, and the guards would reject those legal edits with
`400 cannot_edit_non_pending_advance` or `403 forbidden`. Nothing in the build
or the runtime would have objected; the first signal would have been users
unable to edit their own pending advances.

`LegacyRowCountStartupCheck` closes that vector by failing closed: under the
`phase1-mysql` profile it inspects `app.legacy-db.jdbc-url` and refuses to
start when the option is enabled. This deliberately converts a silent
data-correctness regression into a boot failure, on the same
`ApplicationRunner` pattern as `JwtSecretStartupCheck` and
`SuperuserStartupCheck`. Rollback is removing the option from the URL, which is
the same action the error message names.

Scope is the deployment-configuration vector only. A change in the *driver's*
default is already caught by
`LegacyAdvancePayEndToEndTest#employeeEditResubmittingStoredValuesSucceedsInsteadOfLookingLikeALostRace`
and
`LegacyPayrollBatchCalculateEndToEndTest#penaltyEditResubmittingStoredValuesSucceedsInsteadOfLookingLikeALostRace`,
which exercise the semantics against real MariaDB on every build. A runtime
probe was considered and rejected: proving the semantics live requires a write,
and the least invasive form (a temporary table) would make startup depend on
`CREATE TEMPORARY TABLES` privilege, trading a documented configuration risk for
an undocumented privilege one.

Evidence: `LegacyRowCountStartupCheckTest` (16 cases) covers the enabling forms
(`true`/`TRUE`/`1`/`yes`/valueless/mixed-case key/among other parameters), the
non-enabling forms (`false`/`0`/empty/absent), near-miss keys that merely
contain the option name, and the unset URL the default profile leaves empty.
Wiring was verified by falsification rather than assumed: appending
`?useAffectedRows=true` to `LegacyAdvancePayEndToEndTest`'s container URL now
fails context startup with this check's message, where before the same patch
booted and produced the `400` at request time.

## D-120: All three remaining Item-12 endpoints are delivered -- Java reproduces PHP's response contract, binary included

**Status:** Accepted 2026-08-28.

C9 recorded that three live endpoints stood between the repository and Item 12's
closure, and that the disposition -- deliver, formally exclude, or defer -- was
the owner's to make. The owner's disposition is **deliver all three**:

- `/apis/api/attendance/overall_report.php`
- `/apis/api/attendance/export.php`
- `/apis/api/payslips/export.php`

None is excluded and none is deferred out of Phase 1. The exclusion bucket keeps
its single row (`time/now.php`, O-3) and the live obligation stays at 198.

The governing rule the owner stated is that **Java does what PHP does**. Applied
to these three, that means the response contract is taken from each endpoint's
own PHP file rather than from a repository-wide default:

- `overall_report.php` terminates in `ok(LangKey::OK, $report, 200)`, so Java
  answers D-074's JSON envelope, as every delivered route already does.
- `attendance/export.php` and `payslips/export.php` both emit an **XLSX
  workbook**, despite their `_csv`-named row builders: `data_export_attendance_csv()`
  and `data_export_payslips_csv()` each end in the single terminator
  `api_xlsx_export_send()` (`xlsx_writer.php:318`), declared `: never`. Java emits
  the same **reader-observable workbook** -- sheet name, rows, cell values, merges,
  styles, widths, freeze -- with the same content type
  (`application/vnd.openxmlformats-officedocument.spreadsheetml.sheet`), attachment
  disposition and sanitized `.xlsx` filename, a `Content-Length` matching its own
  body, and that helper's `fail()`-with-500 path when the workbook cannot be built.
  Wrapping either in a JSON envelope would be a client-visible divergence, which
  D-111's zero-client-change invariant forbids. There is one binary mechanism to
  port, not two.

  **Parity is semantic, not byte-for-byte.** D-085 settled this for the one XLSX
  generator Phase 1 has shipped: "ZIP timestamps, compression metadata and entry
  CRC representation are archive incidentals, not compatibility requirements, and
  no binary invariant is promised." `LegacyXlsxWriter`'s javadoc records the same.
  A byte-equality requirement would be unsatisfiable against `java.util.zip` and
  would gate on something no client can observe.

This makes binary-response support a Phase-1 implementation obligation rather
than a reason to defer. It does not create a new decision about *how* the report
is computed: broad J.2's dependency question closed on its own evidence
(`docs/migration/2026-08-27-broad-j2-settlement-discovery.md`), so both
attendance endpoints are unblocked and what remains for them is ordinary slice
work.

This decision **selects delivery; it does not implement anything.** All three
endpoints remain in `ITEM12_REMAINING` and unmapped. Completion plan §1.6 assigns
each an owning slice: Wave 12.6.6 for the two attendance endpoints, **Wave 12.9**
for `payslips/export.php` -- which makes that wave 15 of 16 rather than complete,
since it was only ever "complete" on a count that treated its sixth endpoint as
excluded.

Impact: §5 G2 loses its "or formally excluded" branch and closes only at
`FINAL_COMPATIBLE = 198`; §5 G3 is restated per-endpoint rather than
envelope-for-everything, and scoped to reader-observable parity rather than
archive bytes; `LegacyPhpRouteInventoryTest`'s two unmapped-route assertions are
to be deleted, not amended, when the endpoints ship. O-6's engineering order was
overtaken by events -- Wave 12.R landed before this remaining Item-12 work -- and
that deviation is recorded in §1.6 with both of O-6's invariants shown to still
hold.

Evidence: frozen `hr-legacy@d113204` -- `apis/api/attendance/overall_report.php`,
`apis/api/attendance/export.php`, `apis/api/payslips/export.php`, and the two
`: never` helpers in `apis/helpers/`. Recorded in
`docs/migration/2026-08-23-phase1-completion-plan.md` (§1.2, §3.2, §5 G2/G3, §6
C9, §8 O-8, §8.1), `docs/legacy/WAVE12_COMPLETION_AUDIT.md`, and
`docs/bootstrap/open-questions.md`.

## D-121: Codex is the independent reviewer of record, and its quota is a gate on merging

**Status:** Accepted 2026-08-28.

`AGENTS.md`'s workflow places an independent review between automated
verification and human merge but never named who performs it. PR #120 merged
without that gate being exercised as a distinct step, because no named reviewer
existed to exercise it.

**`chatgpt-codex-connector[bot]` is that reviewer.** Its review of a pull request
satisfies the independent-review gate, under two conditions the owner set:

1. **It reviews everything in the pull request** -- the whole diff, not a sample
   and not only the newest commit. A review round that only covers an
   intermediate head does not discharge the gate for commits pushed after it;
   re-request review (`@codex review`) so the final head is the reviewed one.
2. **Its quota is part of the gate.** Codex review is billed on an account
   outside this repository (R-009). When that quota is exhausted, the gate is
   **not** satisfied -- it is unavailable. Merging past it is not permitted
   because a check is missing; the merge waits, exactly as the R-009 contingency
   already says.

This does not relax anything else in the workflow. Codex is a review agent and
is read-only; a green CI run still proves branch validation only and never
constitutes the review; and the human owner still performs the merge. Findings
are addressed or answered on the thread before merge -- a P1 or P2 left with no
reply and no fix means the gate has been read, not passed.

**Enforced, not only declared.** The reviewer is an external GitHub App, so it has
no `.claude/agents` file and `validate_agent_matrix_consistency()` -- which binds a
Claude agent's matrix row to its real `tools:` frontmatter -- silently skips it.
`validate_independent_reviewer_declaration()` closes that gap instead. It fails if
`AGENTS.md` has no `## Mandatory Workflow` section at all, if that section has no
independent-review step, if it does not name the reviewer **within the section**
(a mention elsewhere in the file does not staff the gate), if the responsibility
matrix carries no row for that name, or if the row is widened from read-only.
It parses the workflow's live `-f context=` arguments with comment lines removed,
rather than searching the file, so a commented-out example naming the required
context cannot stand in for the argument the job actually passes --
`check-branch-protection.sh` strips comments before its own read for the same
reason. It matches the heading as a **whole line** (so `### Mandatory Workflow` does not
satisfy a level-two-heading requirement), checks that the independent-review step **precedes**
the human-merge step rather than merely appearing somewhere, matches the matrix
row by **exact identity** once its backticks and human annotation are stripped
(so `impersonator-chatgpt-codex-connector[bot]` is a different agent, not this
one), evaluates **every** matching row rather than the first, and treats more than
one such row as a failure in its own right. Eleven fixture cases in
`scripts/test_validate_phase0.py` cover it — wholesale removal of the section, a
demoted heading, review placed after merge, an out-of-section mention, a look-alike
identity, the widening case, the duplicate-row case, and a sanity check against the
real repository.

**The mechanical gate this needs is not a required approving review**, and it is
not one setting. This reviewer cannot approve, so a human approval satisfies that
count while a round is still in flight — which is how PR #126 merged ten seconds
after its final round posted (R-008's second realization).

Two signals are required, because each is blind where the other sees:

| Signal | Proves | Blind to |
|---|---|---|
| `required_conversation_resolution` | no thread is left open | **whether anything was actually addressed** -- resolution is a state a human can set without acting; also a head with **no** round yet, and a head whose predecessor's threads were resolved before the new commits |
| `independent-review` status check (`.github/workflows/independent-review-gate.yml`) | the named reviewer submitted a round for **this head SHA** | whether that round's findings were addressed |

**One way it still could, and why it is not yet closed.** The workflow triggers on
`pull_request`, so the run executes the *pull request's* copy of the workflow while
holding `statuses: write` — a revision could keep the literals the validator binds
and publish success unconditionally. The remedy is the privileged `_target`
variant, which runs the base branch's trusted copy and is safe here because this
job has no `actions/checkout`; `validate_workflow_safety()` forbids that variant
outright, and relaxing a standing security rule is not a change to make in passing.
Recorded in R-008 with the consequence that matters: **the status must not become
a required context until this is resolved**, which puts it alongside D-013's
branch-protection decision rather than ahead of it. `validate_phase0.py` now fails
if this workflow ever gains a checkout, since that is what would turn the
limitation into an escalation.

Three ways the gate could go green over an unreviewed diff, each closed: a
**dismissed** review is excluded from the count rather than counted by login and
SHA alone; a **retarget** changes the diff while the head SHA stays, so a base
change publishes `failure` outright and demands a fresh round; and because the
status is shared across every open pull request at a commit, the workflow also
runs on **close**, so a survivor is not left blocked by a departed sibling's
`failure`.

The check publishes an explicit **commit status** against `pull_request.head.sha`
rather than relying on its own job conclusion, so the push event and the
review-submitted event converge on one context on the right commit instead of
depending on how two workflow runs of the same name supersede one another. A
commit status carries no pull-request identity, so it is computed across **every
open pull request pointing at that commit** — otherwise one pull request's round
would hand a sibling with a different base a green gate over a diff the reviewer
never saw. Its three moving parts are bound together rather than left to drift:
`scripts/check-branch-protection.sh` reads the required context out of the
workflow that publishes it, and `validate_phase0.py` fails if the workflow is
deleted, publishes a different context, or runs its gate against a different
account — matched on the `REVIEWER:` assignment itself, since the workflow's own
comments name the reviewer and a whole-file search would be satisfied by those.

**What this does not enforce.** Neither signal proves a finding was *addressed*.
Thread resolution is a state a human can set without acting, so both can be
satisfied with every finding ignored. Step 7 of the merge sequence remains a human
obligation; whether to build a qualifying-answer check is an open owner decision
recorded in R-008.

`scripts/check-branch-protection.sh` requires conversation resolution, so the
protection applied whenever D-013's deferral is revisited is verified against the
failure that occurred. The status check is **advisory until a human adds it to
`main`'s required contexts** — which cannot happen while branch protection itself
is Deferred, and is recorded as the outstanding owner step in R-008.

Neither replaces reading the findings. A green `independent-review` proves a round
happened, not that anyone acted on it.

**Propagated into the executable procedure, not just the policy.** Step 5 of the
Human Approval And Merge Sequence in `docs/bootstrap/manual-setup-checklist.md`
previously offered the Claude `bootstrap-auditor` and/or the Codex
`independent-verification-reviewer` as the independent audit. A pull request could
satisfy every documented step there while skipping this gate — which is what
happened at PR #120. Step 5 now names this reviewer and requires whole-PR coverage
of the final head; the other two audits are explicitly supplementary. Step 7 now
requires every finding to be fixed or answered on its thread before merge.

Impact: R-008's mitigation gains a named reviewer and R-009's impact widens from
"unreviewable" to "unmergeable" while Codex quota is out.

Evidence: `AGENTS.md` mandatory workflow; PR #120's merge record in
`docs/legacy/WAVE12_COMPLETION_AUDIT.md`; PR #126's four Codex review rounds,
whose findings drove this change and which showed the per-head reviewing
behavior condition 1 addresses.

## D-122: The review gate runs on the privileged trigger, under one condition

**Status:** Accepted 2026-08-28.

`validate_workflow_safety()` has banned `pull_request_target` outright since Phase
0. The ban is right in general and was wrong for exactly one workflow.

`.github/workflows/independent-review-gate.yml` holds `statuses: write` because it
publishes the `independent-review` gate. On the ordinary `pull_request` trigger a
run executes the workflow file **from the pull request**, so a revision could keep
the reviewer and status-context literals `validate_phase0.py` binds, replace the
counting logic with an unconditional success, and turn the gate green before the
named reviewer had seen that very change. D-121's gate would then certify its own
bypass.

**The exception, and the premise it rests on.** The hazard the ban exists to stop
is privileged credentials running *pull-request code*. This job runs none: it has
no `actions/checkout`, reads no file from the head tree, and passes only a pull
request number and a commit SHA to the GitHub API. Nothing attacker-controlled --
no title, body, branch name or commit message -- reaches a shell.

**It is conditional, not a carve-out.** `validate_workflow_safety()` permits the
trigger for this one path and fails immediately if that file gains a checkout,
with a message naming this decision. Every other workflow keeps the blanket ban.
Four fixtures hold the boundary: an unrelated workflow using the trigger still
fails; the gate passes without a checkout; the gate **fails** with one; and a
checkout named in a comment is not a checkout -- the file's own header explains
that it has none, and saying so must not read as having one.

**What this unblocks.** R-008 recorded that `independent-review` must not become a
required context while the gate ran pull-request-controlled code. That precondition
is now cleared, so making it required is once more purely the branch-protection
question D-013 defers -- one decision instead of two.

**What it does not change.** The gate still proves only that a round happened on
the final head. Whether findings were addressed remains step 7's human obligation,
with nothing in this repository verifying it mechanically (R-008).

Evidence: the workflow's own header records the premise; `validate_workflow_safety()`
enforces it; `scripts/test_validate_phase0.py` covers all four boundary cases.
Raised by independent review on PR #127 as "Publish the gate from trusted workflow
code", declined there as an owner decision rather than taken unilaterally, and
accepted by the owner the same day.

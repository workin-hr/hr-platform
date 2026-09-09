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

**A bootstrap condition to expect, measured on PR #127.** The privileged trigger
resolves the workflow from the **base** branch, so it fires only once this file
exists there — while the pull request introducing it is still open, zero
push/synchronize runs occur and the status is maintained by review events alone.
It fails safe rather than open: a freshly pushed head has *no* status, leaving a
required check unsatisfied, rather than inheriting a stale green from an earlier
head. It resolves itself at merge, and the first push afterwards should be checked
for a run.

**What it does not change.** The gate still proves only that a round happened on
the final head. Whether findings were addressed remains step 7's human obligation,
with nothing in this repository verifying it mechanically (R-008).

Evidence: the workflow's own header records the premise; `validate_workflow_safety()`
enforces it; `scripts/test_validate_phase0.py` covers all four boundary cases.
Raised by independent review on PR #127 as "Publish the gate from trusted workflow
code", declined there as an owner decision rather than taken unilaterally, and
accepted by the owner the same day.

## D-123: Merge record for the four pull requests that completed Item 12

**Status:** Accepted 2026-08-28.

Step 9 of the Human Approval And Merge Sequence
(`docs/bootstrap/manual-setup-checklist.md`) requires each merge to `main` to be
recorded with its pull-request URL, the approving human's identity, the merge
commit SHA, and a link to the validation evidence. Four merges had landed without
that record; this entry supplies it, following D-014's precedent.

| PR | Merge commit | Merged (UTC) | Merged by | Approving human |
|---|---|---|---|---|
| [#126](https://github.com/workin-hr/hr-platform/pull/126) | `2f67c47` | 2026-08-28T06:49:26Z | `karimtismail` | **pending — see below** |
| [#127](https://github.com/workin-hr/hr-platform/pull/127) | `b5f4820` | 2026-08-28T19:58:01Z | `karimtismail` | **pending** |
| [#128](https://github.com/workin-hr/hr-platform/pull/128) | `d4be20e` | 2026-08-28T20:15:43Z | `karimtismail` | **pending** |
| [#129](https://github.com/workin-hr/hr-platform/pull/129) | `a4825a3` | 2026-08-28T20:33:22Z | `karimtismail` | **pending** |

**The approver field is deliberately not filled in.** Step 9 asks for the
approving human, which is step 6's actor, and step 6 is not the same act as step
8's merge. GitHub records **zero `APPROVED` reviews** on all four pull requests —
every review on them is `COMMENTED`, including all fourteen from
`chatgpt-codex-connector[bot]`, which is read-only by D-121 and cannot approve.
`karimtismail` is recorded as the **merger**, because that is what GitHub shows;
recording the same account as the approver would be an inference, and a
governance record is the wrong place to infer. The field stays open until a human
states who performed step 6, or until the merges are acknowledged as having been
made without a separate recorded approval.

**This is R-008's territory, and it is recorded rather than smoothed over.** That
risk already carries two realized instances. Whether these four are a third
depends on the answer above: a merge by the repository owner who had read the work
is a different thing from a merge with no approval step at all, and the register
should not guess between them. What is certain is that the *evidence* for step 6
is missing, which is exactly the gap R-008 exists to make visible.

**Validation evidence, measured on the final merge commit `a4825a3`:**

- `./gradlew check` — **BUILD SUCCESSFUL**, 13m01s
- **160 test classes, 1930 tests, 0 failures, 0 errors, 0 skipped** (G7's format)
- `python3 scripts/validate_phase0.py` — passed
- `python3 scripts/test_validate_phase0.py` — 80/80
- `npx markdownlint-cli2` — clean

For the trend G7 asks to be recorded: the baseline at `85bb216` was 117 classes /
1415 tests, PR #120's merge 151 / 1837, and this commit 160 / 1930. Waves 12.6.6
and 12.9 therefore added 9 classes and 93 tests, all green — the run was made
specifically to rule out a cross-wave interaction, since three branches merged
within forty minutes and each had touched `LegacyPhpRouteInventoryTest`.

**What these four merges delivered.** `ITEM12_REMAINING` is empty:
`FINAL_COMPATIBLE` stands at 128 and Item 12's endpoint obligation is met. This
is not G2 — that gate covers all 198 live endpoints and Item 13's 70 remain.

## D-124: The overall report's two dead columns are dropped, on a measurement

**Status:** Accepted 2026-08-29.

`overall_attendance_report_build()`'s employee query aggregates
`total_duration_minutes` and `total_expected_minutes`, the second through a
correlated per-row lookup of the employee's shift. **The builder reads neither.**
Its loop uses only `present_days` and `exception_days`; the row's own duration
comes from `attendance_period_work_minutes()`, and its expected minutes never
leave that helper. Wave 12.6.6c ported them faithfully anyway, because dropping
them is an optimisation rather than a fidelity fix and D-058 puts the burden of
proof on the change, not on the port.

**The burden is discharged by measurement.** Against real MariaDB on the vendored
legacy schema, 60 employees x 60 days = 3,600 attendance rows, best-of-7 after
three warm-ups, two independent runs:

| | run 1 | run 2 |
|---|---|---|
| with the dead columns | 11.6 ms | 9.2 ms |
| without them | 3.2 ms | 3.0 ms |

Roughly **three times faster**, and the shape of the saving is the correlated
subquery: one shift lookup per attendance row, discarded immediately. Production
volumes are larger than this fixture, so the absolute saving grows with the
period and the headcount, which is exactly the direction that matters for a
report run over a month of a full company.

**Nothing client-visible changes**, which is the other half of the test the owner
set. No response field was fed by either column: `total_duration_minutes` on the
row comes from the helper, `overtime_minutes` from that helper's two figures, and
the two aggregates the loop does read — `present_days` and `exception_days` —
are untouched. `theDurationAndOvertimeFieldsSurviveTheDroppedColumns` pins that
as a property rather than an argument: employee A punches three full 8-hour days,
so a row whose duration had come from the dropped column would read zero.

**This is a deliberate divergence from the frozen tree and is recorded as one**
(G4). Java issues a narrower query than PHP does. It is not observable through
the API, the response shape, the row ordering, or the row count — only in the SQL
the endpoint emits.

Evidence: the benchmark harness was written, run, recorded here, and deleted
rather than kept — it is slow, environment-sensitive, and its value was the
number, not a standing assertion. The standing assertion is the regression above.
`com.workin.legacy.attendance.*` and `LegacyPhpRouteInventoryTest` green after
the change.

## D-125: Branch protection is applied to `main`, superseding D-013's deferral

**Status:** Accepted 2026-08-29. Supersedes **D-013**.

D-013 deferred branch-protection enforcement for a specific, checked reason:
`workin-hr` is a GitHub Free organization **and `hr-platform` was private**, and
GitHub Free does not offer branch protection on private repositories. Its
follow-up said to revisit "if the organization's plan changes ... or if GitHub
changes free-plan branch-protection availability".

**Neither happened; a third route did.** `hr-platform` is now a **public**
repository, and GitHub Free has always supported branch protection on public
repositories. D-013's premise was the repository's visibility as much as the
plan, and that half is no longer true. The organization remains on Free and was
not upgraded — the constraint D-013 was protecting against is untouched.

### What is applied

| Setting | Value | Why |
|---|---|---|
| `required_status_checks.contexts` | `validate`, `independent-review` | Phase 0's check and D-121's gate -- exactly what `check-branch-protection.sh` requires |
| `required_status_checks.strict` | `true` | A branch must be current with `main` before merging |
| `required_pull_request_reviews.required_approving_review_count` | `0` | Not a relaxation -- **`1` is unsatisfiable here**; see below |
| `dismiss_stale_reviews` | `true` | An approval does not survive a new push |
| `required_conversation_resolution` | `true` | Step 7 — no thread left open at merge |
| `enforce_admins` | `true` | The owner is not exempt; R-008's realizations were all owner merges |
| `allow_force_pushes` / `allow_deletions` | `false` | Both already forbidden by policy, now mechanically |

Verified with the repository's own `scripts/check-branch-protection.sh`, which
had been "built and regression-tested but pending" since D-013 and now passes
against the live configuration for the first time.

**A required context must be one that always runs.** `Backend Validate`'s `test`
job was in the first configuration applied, on the reasoning that R-009 names it
a required check. That was wrong and was corrected within minutes: the workflow
is **path-filtered** to `backend/**` and its own file, so on a docs-only pull
request it never runs, and a required context that never reports blocks the merge
for ever rather than failing. PR #132 -- docs and one workflow file -- deadlocked
on it immediately.

The rule this leaves: **only a check that runs unconditionally on every pull
request may be a required context.** `Phase 0 Bootstrap Validate` qualifies (no
`paths:` filter); `Independent Review Gate` qualifies (it runs on
`pull_request_target` for every pull request); `Backend Validate` does not, and
its real protection is that it must pass when it *does* run, which is a review
obligation rather than a branch-protection one.

### The approving-review count is `0` because `1` cannot be satisfied

The first configuration applied set the count to `1`, which is what step 6 asks
for. It made every pull request in the repository unmergeable, including #132
itself.

**GitHub forbids a pull request's author from approving it.** `karimtismail` is
the only human with write access to `hr-platform` and is therefore the author of
every pull request in it. With `required_approving_review_count: 1` and
`enforce_admins: true`, no pull request could be approved by anyone, ever --
the setting blocked merges rather than requiring review of them.

The count is `0` until a second maintainer holds write access. **Nothing real
was given up**, because the requirement was never satisfiable in the first
place: what is lost is a control that could not fire, not one that could.
Everything that *can* be enforced still is -- `validate`, `independent-review`,
conversation resolution, `enforce_admins`, and the force-push/deletion bans.
Step 6's human approval keeps its full force as a procedural obligation in the
mandatory workflow, exactly the standing it had before this decision.

**The check now derives this rather than hardcoding it.**
`scripts/check-branch-protection.sh` counts non-bot collaborators with push
access and requires an approving review only when a peer approval is possible.
It is bidirectional, which is what makes it worth having:

| Write-access humans | Required count | Rejected as |
|---|---|---|
| 1 | `0` | `>= 1` — unsatisfiable, blocks every merge for ever |
| >= 2 | `>= 1` | `0` — a possible peer approval was dropped |

So the requirement is **alerted, not applied**, the day a second maintainer is added: `check-branch-protection.sh` increments `failures` and exits nonzero, and nothing changes the repository setting. Until an operator runs the check *and* separately updates protection, merges remain possible without a required human approval. The check is a detector, not a remediation, and
**no workflow invokes it** — so this is a standing manual obligation that
somebody does have to remember, and saying otherwise would present an
uninvoked script as an automatic control. Closing it properly means either
running the check in CI or applying the setting, and neither exists today.
Bots are excluded from the count
deliberately: `chatgpt-codex-connector[bot]` holds no approval right (D-121)
and its review arrives through the `independent-review` context instead.

This is the second configuration error in this decision, and it has the same
shape as the `Backend Validate` one above: **a required control that cannot
report is not a strong control, it is a broken one.** Both were caught by
applying the setting and observing a real pull request, not by review of the
intent.

### The human approval and the independent review are two requirements, not one

The approving-review count, whenever it is above zero, is satisfied only by a
human: the named independent reviewer is read-only (D-121) and posts
`COMMENTED`, never `APPROVED` — measured across all fourteen of its reviews on
PRs #126-#129. The `independent-review` context is what carries step 5. Neither
substitutes for the other, which is exactly the confusion D-123 had to leave an
open field for. That separation is why setting the count to `0` does not hand
step 6 to the bot: the bot could never have satisfied that count anyway.

### What this does and does not close

**Closes** R-008's central gap: review and merge governance is no longer purely
conventional. All three of that risk's realizations were merges that procedure
alone did not stop; `enforce_admins: true` means the same sequence would now be
blocked rather than regretted.

**Does not close** step 7's substance. `required_conversation_resolution` proves
no thread is open, never that a finding was addressed — resolution is a state a
human can set without acting. The qualifying-answer check remains owed and is
recorded in R-008.

Evidence: `gh api repos/workin-hr/hr-platform/branches/main/protection` returned
`404 Branch not protected` immediately before this change and the full
configuration above immediately after; `bash scripts/check-branch-protection.sh`
passes against the live configuration, reporting `1` write-access human. The
unsatisfiable state was observed directly: PR #132 sat at
`mergeStateStatus=BLOCKED` with `reviewDecision=REVIEW_REQUIRED` and no
reviewer able to clear it. `scripts/test_validate_phase0.py` covers both
directions of the derived rule and is 83/83.

## D-126: `configs/get.php` is ported as-is, unauthenticated, in Item 13.0

**Status:** Accepted 2026-08-29. First endpoint delivered outside Item 12.

`configs` is one endpoint and the completion plan's §2.4 puts it first in Item
13 rather than last with the other single-endpoint reference modules. The
reason is a circularity: the Flutter refresh-token gap (`hr-platform#18`) can
only be closed by shipping new client builds, and new client builds are forced
through the version gate this endpoint serves (`hr-platform#21`). Built last, it
would leave no way to tell clients a cutover had happened.

### It stays unauthenticated, and that is a decision rather than an omission

The PHP calls no `requireAuth()` at all — it checks the method and queries. A
client must read the maintenance flag and version gate **before** it can log in,
so a 401 here would break the endpoint's purpose, and D-111 forbids changing
what an existing client sees. What makes it safe to expose is the data, not the
routing: `configs` has no `company_id` column (`mysql_workin.schema.sql:373-377`),
carries no personal data, and holds global operational configuration only. It is
therefore the second entry in `LegacyPhpRoutes` that is public because *legacy*
enforces nothing, rather than because its controller enforces authentication
itself — a distinction now stated in that class, because conflating the two
categories is how a real hole would be added later.

`LegacyEmployeeReadEndToEndTest#noMappedPhpRouteAnswersAnUnauthenticatedRequest`
previously asserted that **every** mapped route answers 401 or 405 to an
unauthenticated GET. It now carries a closed literal list of public routes and
asserts this one serves 200 in the envelope. A list rather than a predicate, so
that widening it is a visible diff.

### Four legacy behaviours preserved under D-058

| Behaviour | PHP | Why it is not "fixed" |
|---|---|---|
| An unknown key is **200 with `config_value: null`** | `$row[...] ?? null` — no 404 branch | A client distinguishing "absent" from "error" today would break |
| The echoed `config_key` is the **requested** one, not the row's | read from `$_GET` | Observable on exactly the missing-key path |
| `?config_key=` (empty) falls through to the **all-configs** branch | `$key !== null && $key !== ''` | Tests the exact empty string, so `?config_key=%20` is a real one-character key that misses |
| A row keyed `server_time` is **overwritten** by the clock, keeping its position | assignment into an existing array key | Reachable — the dashboard's configs editor writes arbitrary keys — and a client reading `server_time` receives the clock today |

Key order is insertion order in both languages, so the store folds rows into a
`LinkedHashMap`. That is a wire property, not a style choice: PHP has no
`ORDER BY` and `json_encode` emits an associative array in insertion order, so a
hash-ordered map serialises the same pairs into a different response body. The
regression asserts the exact key sequence — an earlier two-key relative-position
assertion passed under `HashMap` by luck, and was replaced once falsification
showed it.

### `server_timezone` is a POSIX zone name whose sign is inverted

`date_default_timezone_set('Etc/GMT-3')` makes `date_default_timezone_get()`
return that literal string, so the field is `Etc/GMT-3` or `Etc/GMT-2` — never
`+03:00` and never an IANA city zone. **`Etc/GMT-3` is UTC+3**: these zones count
west-positive, so the name reads like the opposite of what it means, and
"correcting" the sign would move every client's clock six hours. The rendering
lives in `LegacyRuntimeOffset` beside the existing offset grammar so that the
endpoint and `LegacyClock` cannot disagree about which profile is active. The
PHP's `?: 'UTC'` fallback is unreachable and is not reproduced.

The desktop version-gate fields F-07 names (`min*BuildNumberKey`,
`*UnderMaintenanceKey`) need no mapping: the endpoint is key-agnostic and serves
whatever rows exist, so those keys are data rather than code.

### A gap found by building it, now covered by a drift test

`LegacyWireExceptionHandler` is scoped by an explicit `basePackages` allowlist.
A controller in an unlisted package maps and serves normally — until it throws,
at which point `LegacyApiException` escapes and the client gets a **500 with the
platform error body** instead of PHP's status and envelope. Nothing in the build
caught that: the route was mapped, the security boundary covered it, and the
happy path was green. It surfaced only because a POST to this endpoint answered
500 where PHP answers 405 `invalid_method`.

`everyLegacyRouteHandlerIsCoveredByTheD074ExceptionHandler` now derives the
check from the live handler mappings and the annotation itself, and names the
package to add when it fails. Item 13 adds seventeen more modules, so this is
worth a drift test rather than a one-line fix.

### Ledger after Item 13.0

`FINAL_COMPATIBLE` 128 → **129**; `ITEM13_REMAINING` 70 → **69**; the response-shape
partition 123/4/1 → **124/4/1**. The live total of 198 is unchanged, as it must
be — this moves an endpoint between buckets rather than discovering one.

Evidence: `./gradlew check` — 1943 tests, 13 of them new. The one failure is
`LegacyEmployeeStatsAndTeamEndToEndTest#myTeamCarriesTheOrgLabelsAndTheCheckedInMarker`,
which fails identically on a clean tree at this time of night and is **not**
caused by this change: it compares `checked_in_today` against the database's
`CURRENT_DATE` while the run crosses the midnight boundary between legacy's
+02:00 profile and the JVM's local zone. That is the D-083 gap the test's own
comment describes, realised as a time-of-day flake. Recorded here rather than
fixed inside Item 13.0, and owed as its own issue.

## D-127: Step 7 gets a disposition check, and an explicit limit

**Status:** Accepted 2026-08-29. Partially closes the gap **R-008** records.

Step 7 of the merge sequence — every finding fixed, or answered on its thread —
was the one step with no mechanical component at all. `independent-review`
proves a round happened on the final head; `required_conversation_resolution`
proves no thread is left open. Neither proves a finding was *answered*, because
resolution is a state anyone with write access can set without changing a line
or replying. R-008's third realization is that gap: six findings, four of them
P1, posted ten seconds before a squash merge with both signals green.

`scripts/check-review-dispositions.sh <pr>` requires every review thread the
named reviewer **opened** to carry a reply declaring one of four dispositions:

| Disposition | Asserts |
|---|---|
| `fixed` | the code changed; say which commit |
| `declined-with-evidence` | the finding is wrong, and the reply says why with a reference someone else can check |
| `accepted-risk` | the finding is right and is not being fixed now; say who accepted it and where it is tracked |
| `superseded` | a later change or finding replaced this one |

### What it deliberately does not do

**It does not judge whether the disposition is right.** "Declined" with a bad
reason passes exactly as "declined" with a good one. That is not a gap to close
later — it is not decidable, and a check implying otherwise would be worse than
no check, because R-008 already records a merge that went wrong through a green
box being read as more than it said. The tool narrows what a human must verify;
it does not replace the verification.

### Six properties that make it non-trivial to satisfy

*(Four at acceptance; two added 2026-08-31 after an independent review of the
D-142 batch found the check could report success without having read every
finding — see the last two bullets.)*

- **A resolved thread with no reply fails.** This is the whole point: it is
  exactly the state `required_conversation_resolution` reports as clean.
- **A finding cannot disposition itself.** The declaration must come from a
  reply, not from the reviewer's own comment — otherwise the check would be
  satisfiable by the party being checked.
- **The vocabulary is closed.** `Disposition: wontfix` fails rather than
  becoming a silent fifth category.
- **Threads a human opened are not findings.** Requiring a disposition on those
  would train people to type the token to clear noise, which is how a control
  decays into a formality.
- **Every thread and every comment is read, not the first page of each.**
  `reviewThreads` and each thread's `comments` are both paginated. Unpaginated,
  the check reported success on any pull request with more than 100 threads —
  failing open precisely on the longest, most-reviewed ones it exists for — and
  could not see a disposition posted past a thread's hundredth comment, which
  fails the other way and blocks a merge whose finding *was* answered. If a
  thread still cannot be fully fetched, the check refuses by name rather than
  judging on partial data.
- **The vocabulary matches whole terms, not prefixes.** `Disposition: fixed-later`
  and `Disposition: supersededness` no longer discharge a finding by matching an
  allowed prefix. The original closed-vocabulary case used `wontfix`, which
  shares no prefix with any allowed value and passed either way — so this
  property was asserted but never actually tested.

The reviewer login is read from `independent-review-gate.yml`'s `REVIEWER:`
**assignment**, not from anywhere else in the file — the same binding rule
`check-branch-protection.sh` uses, for the same reason: this repository has
twice written a validator that a comment could satisfy.

### What is still owed

It is a script run against a pull request, **not a required status**. Wiring it
to a published context is a separate decision, and a heavier one than it looks:
it needs `statuses: write`, which under D-122's reasoning means the privileged
`pull_request_target` trigger and the safety conditions `validate_workflow_safety()`
enforces. Until then step 7 is a human obligation *supported by* a tool rather
than one enforced by the platform, and R-008 stays open on that basis.

Evidence: thirteen regression cases in `scripts/test_validate_phase0.py`.
Falsified in four directions — opening the vocabulary to any token, allowing a
finding's own text to discharge it, removing the whole-term anchor (which lets
`fixed-later` pass), and truncating a thread's comments (which must refuse
rather than judge) — with each break caught by the case written for it.

## D-128: Wave 13.5 delivers the five reference endpoints, and is taken before 13.1

**Status:** Accepted 2026-08-29.

### The ordering, and why it changed

The completion plan sequences Item 13 as 13.0 → 13.1 (auth) → … → 13.5. Wave
13.5 was taken second instead. The reason is the state of the review gate, not
a change of view about value: **R-009 was realized while this wave was
written** — the independent reviewer's quota was exhausted and the owner decided
not to add credits, so at that point no wave had a reviewer.

R-009's owning entry in `docs/bootstrap/risk-register.md` is updated in a
**separate pull request** (the D-125 branch-protection one), because maintaining
that entry is what that change exists to do. Until the two merge, this tree's
copy of the register still reads "both quotas restored". The register is the
authoritative record of the risk's state and this decision defers to it rather
than duplicating it — the quota did in fact recover later the same night, which
is precisely why that state belongs in one place instead of being asserted
here.

13.1 is the wave where that matters most. It is thirteen endpoints covering OTP
issuance, password reset and company registration, plus an outbound WhatsApp
integration, and it is the largest security surface in Item 13. Delivering the
five low-risk reference endpoints first banks progress, establishes the
per-wave recipe (security boundary, exception-handler scope, route inventory,
response-shape partition), and leaves the security-critical wave for a point
where review is available. The plan's order is a recommendation about value;
this is a judgement about risk under a degraded gate, and it is recorded rather
than made silently.

### What was delivered

| Endpoint | Auth | Note |
|---|---|---|
| `phone_countries/list.php` | **none** | public by design — a client needs dial codes to render the login form |
| `app_content/one.php` | **none** | public by design — pre-login marketing and legal copy |
| `banners/list.php` | any role | no `requireCompanyActive()`: platform content, not company data |
| `faqs/list.php` | any role | same |
| `dashboard/stats.php` | COMPANY_ADMIN / HR | the only one reading company data, and the only one calling `requireCompanyActive()` |

That takes `LegacyPhpRoutes`' public category from two entries to four. The
class now states that all four are safe for a reason about the **data** — a
login handler, global operational config, a dial-code list, pre-login copy —
rather than about the routing, because that is the property a future addition
has to satisfy.

### Two locale rules in one wave, and both are correct

`app_content/one.php` resolves through `app_locale()`, which looks for
`\bar\b` in `Accept-Language` and returns **English** when it finds nothing.
`phone_countries/list.php` bypasses that helper: `phone_countries_public_rows(null)`
defaults `$lang` to the literal `'ar'` and tests `str_starts_with($lang, 'en')`.

So a client sending no header gets **English copy and Arabic country names in
the same session**, and `Accept-Language: ar,en;q=0.8` is Arabic under both
rules but for different reasons. Asserted side by side in the regression,
because either rule alone reads like a bug in the other's light and
"harmonising" them would change what a real client renders on its first screen.

### Preserved quirks

- **An unrecognised `platform` widens rather than narrows.** `banners` and
  `faqs` both apply an `if/elseif` with no `else`, so `?platform=web` returns
  *everything*. A client sending a platform the server does not know receives
  more rows, not fewer.
- **A FAQ category with no matching items disappears** rather than arriving
  empty, so the set of section headers differs per platform.
- **`dashboard/stats.php` sums every contract row** for an active employee, not
  the effective one, so contract history inflates the salary totals.
- **Its map-valued keys are keyed by department *name* while the SQL groups by
  *id*.** Two departments sharing a name collapse into one key and the last row
  wins, silently. `workforce_planning_stats` is a list and does not collide,
  which is why the same data appears twice in the payload under two different
  collision behaviours.
- **`{}` and `[]` are not interchangeable.** PHP casts the empty map-valued keys
  with `(object)[]` precisely so the value's *type* does not change under a
  client; the two list-valued keys carry no such cast and correctly stay `[]`.
  The regression asserts the type of all eight map keys, not just their
  emptiness.

### `strtotime('-3 months')` is not `LocalDate.minusMonths(3)`

PHP keeps the day-of-month and lets it **roll**: 31 May minus three months is
31 February, which resolves to **3 March**. Java **clamps** to 28 February. The
two disagree by up to three days, which moves the 90-day cohort window's start
and changes who is counted in `new_employee_turnover_rate`. Reproduced by
landing on the first of the target month and adding `day - 1` days, and pinned
by a test that asserts both the correct value and what Java would have done.

### One extraction

`penalties_total_amount()` was inlined as a private method in
`LegacyPenaltyService` while `penalties/stats.php` was its only caller.
`dashboard/stats.php` is a second, so it is now `LegacyPenaltyAmounts`. Two
copies of a money calculation that must agree is exactly the shape the
change-propagation rule exists to prevent. The existing unit test now builds a
**real** `LegacyPenaltyAmounts` over its mocked store rather than stubbing the
arithmetic — mocking the collaborator under question would leave those tests
asserting nothing about the figures they produce.

### Ledger after Wave 13.5

`FINAL_COMPATIBLE` 129 → **134**; `ITEM13_REMAINING` 69 → **64**; response-shape
partition 124/4/1 → **129/4/1**. Live total 198 unchanged.

Evidence: `./gradlew check` — **1973 tests, 0 failures**, 30 new. The turnover
month-step and headcount floor were each falsified against a deliberately broken
implementation.

## D-129: Wave 13.3 delivers the eight settings endpoints

**Status:** Accepted 2026-08-29. Continues D-128's risk-last ordering.

Eight endpoints over the EAV settings model: the six `company_settings`
routes -- `list`, `one`, `options`, `create`, `update`, `delete` -- plus `setting_definitions/list.php` and
`setting_allowed_values/list.php`. D-4 had flagged `company_settings` as
schema-incompatible with the existing Phase-2 entity (EAV against five typed
columns); this wave ports the **legacy** shape, which is the EAV one, and does
not touch that entity.

### Three authority levels, and the odd one is real

| Endpoint(s) | Requires |
|---|---|
| the six `company_settings` routes | COMPANY_ADMIN/HR **+** `can_company_settings` **+** active company |
| `setting_definitions/list.php` | COMPANY_ADMIN/HR only — no permission gate, no company-active check |
| `setting_allowed_values/list.php` | **nothing** |

The allowed-value catalogue is world-readable while the definitions that
*name* those same values need an administrative role. Both tables are platform
configuration with no `company_id`, so nothing tenant-scoped leaks either way,
but the asymmetry is preserved rather than harmonised. That takes
`LegacyPhpRoutes`' public category from four entries to five.

### The bug this wave's falsification found in the port

The first implementation used one shared item-builder for `one.php`,
`create.php` and `update.php`. **That was wrong, and legacy is inconsistent
here in a way that is externally visible.**

- `list.php` and `one.php` derive `company_setting_id` and `updated_at`
  **only from the selection join**.
- `create.php` and `update.php` use `build_company_setting_item()`, which runs
  its own `SELECT id, updated_at FROM company_settings`.

A `company_settings` row can exist with **no** values — `create` with an empty
value list produces exactly that — and then the join returns nothing. So a
client creates a setting, receives a real id, re-reads it through `one.php`,
and gets `company_setting_id: 0` with a null timestamp, **without anything
having been deleted**. Two shapes, separately observable, and unifying them
would change a live response.

It was found by falsifying the rollback regression, not by reading the code:
the original assertion went through `list.php`, which reports `0` for "no row"
and for "row with no values" alike and therefore could not tell a rollback from
a partial write. Re-pointing it at the table made it fail correctly — and made
the two shapes' disagreement visible.

### A validation failure is a 400, not the catch block's 500

PHP wraps its writes in `try { beginTransaction(); … } catch (Throwable) {
rollBack(); fail(ERROR_WITH_MESSAGE, 500) }`, and it would be easy to read that
as "any rejected value is a 500". It is not: `fail()` ends in `exit`, so it
never reaches the catch, the client sees the 400 the validation raised, and PDO
rolls the open transaction back at shutdown. Reproduced by letting
`LegacyApiException` propagate out of the `@Transactional` method — Spring rolls
back on it and the wire handler renders the carried status.

### Other preserved behaviours

- **`create` and `update` reach opposite end states from an empty value list.**
  `create` inserts a parent row with no values; `update` deletes the setting
  entirely.
- **`create` is not an upsert** — a second create is `already_exists`.
- **A required definition cannot be deleted**, and the check runs on the
  definition resolved from *either* identifier.
- **Deleting a setting that was never set is `ok`**, not 404.
- **The id beats the key** whenever it is positive, so a request carrying both
  a valid id and a contradictory key silently uses the id. `update.php` alone
  also falls back from body to query string.
- **`options.php` echoes an unknown `setting_key`** with an empty option list
  rather than 404ing, and its map form orders definitions by `setting_key` —
  the only place in the module that is not `sort_order` order.
- **`pick_label` crosses languages before it reaches the fallback**, and null
  and blank are different inputs: a *null* `label_ar` skips to `label_en`, while
  a *blank* one is chosen, trims to empty, and lands on the setting key.

### Two defects the review found in this wave's port

Both were caught by Codex on #139 and are worth recording, because neither is
visible in a single-threaded test.

**The permission refusal sent a message key instead of a message.**
`LegacyHrPermissionEnforcer` threw the platform's `ApiException` with
`error.forbidden`. `LegacyMessages` loads only `legacy/lang/*.properties`, which
defines `forbidden` and has no `error.` namespace at all, and its lookup falls
back to returning the key unchanged — so clients received the literal string
`"error.forbidden"` where PHP's `fail(LangKey::FORBIDDEN, 403)` sends
`"Forbidden"`. **This affected every already-delivered legacy endpoint that
gates on a permission**, not only this wave's, so the fix is at the enforcer.

**`SELECT LAST_INSERT_ID()` is not a port of `PDO::lastInsertId()`.** PHP holds
one connection per request; a `JdbcTemplate` borrows one per call. The insert
and the id read could therefore run on *different* pooled connections, and
`LAST_INSERT_ID()` is session-scoped — so under concurrency the second call
returns another request's id, or `0` on a connection that has inserted nothing.
The caller then re-reads "its" row by that id, which is how a lost id becomes a
response carrying **another tenant's** row.

It is invisible single-threaded and invisible inside a transaction, which pins
one connection — which is exactly why it survives review. `LegacyGeneratedKeys`
now asks JDBC for the key the insert statement itself generated, so there is no
second round trip to misroute, and a twelve-thread regression asserts every
caller reads back its own row. Reverting to the two-call form fails both cases.

### Ledger after Wave 13.3

`FINAL_COMPATIBLE` 134 → **142**; `ITEM13_REMAINING` 64 → **56**; partition
129/4/1 → **137/4/1**. Live total 198 unchanged.

Evidence: `./gradlew check` — **0 failures**. The test count is not restated
here for the reason given under D-130: a figure that must be re-measured on
every push cannot stay true in a durable record, and this one had already drifted
across several review rounds. The
transactional rollback was falsified by removing `@Transactional`, which is what
exposed the item-shape bug above.

## D-130: Wave 13.4a delivers `assets` and `administrative_decisions`, and records the client-only authorization on `assets`

**Status:** Accepted 2026-08-29. The endpoint-specific evidence record for the
C3/C8 pass's `assets` finding, filed **under** D-044 and D-045 rather than as a
new risk acceptance. An earlier revision of this line called it an
"owner-approved disposition", which contradicted the body below: the behaviour
was already governed by those two decisions, so no additional owner sign-off was
owed and none was taken. Nothing here re-opens or re-grants that acceptance.

Ten endpoints across two modules that agree on almost nothing. Each difference
below is legacy's, is separately observable, and would be erased by the shared
abstraction the two modules invite.

| | `assets` | `administrative_decisions` |
|---|---|---|
| permission gate | **none, anywhere** | `can_employees` on every route but `list` |
| `list` admits | ADMIN, HR, MANAGER, EMPLOYEE (self-scoped) | any role, hand-written checks, EMPLOYEE sees `is_active=1` only |
| `one` admits | ADMIN, HR, MANAGER | ADMIN, HR |
| boolean parsing | `filter_var(FILTER_VALIDATE_BOOLEAN)` | `(int) $v === 1` |
| delete body | `{"deleted": true}` | no `data` key at all |

So `"is_active": "true"` **deactivates** a decision while `"is_returned": "true"`
**marks an asset returned** — the same JSON value, opposite outcomes, in one
wave. And MANAGER can list decisions but not read one; an employee can list
assets but not read one; an HR user without `can_employees` is refused the
decisions list while an ordinary employee is served it.

### The `assets` permission gap was already decided — D-044 and D-045 govern it

The bounded C3/C8 pass established that the desktop client hides the Assets
screen behind `hrPermission: HrPermissionFlag.assets`, while the server enforces
**no** `hr_permissions` check on any of the five routes.

**Scope, stated precisely — an earlier revision of this entry overstated it.**
The three write routes are `requireAuth([COMPANY_ADMIN, HR])`, so MANAGER and
EMPLOYEE sessions are refused with 403. What is unenforced is only the narrower
flag: an **admin or HR user whose `can_assets` is unset** is hidden the screen by
the client and served by the server. That is a privilege gap *within* an
already-privileged role, not open access — and the difference matters, because a
port written against the wider claim would have granted MANAGER and EMPLOYEE a
mutation capability legacy does not give them.

The gap itself is **already tracked upstream as `hr-legacy#8`**; what this wave
adds is the client evidence and the decision to reproduce it.

**This is not a new acceptance, and an earlier revision of this entry wrongly
presented it as one.** Two accepted decisions already bind it:

- **D-044** — "Phase 1 also reproduces `hr-legacy#8`'s confirmed enforcement
  gap … Enforcement is added explicitly, per legacy-side controller method,
  matching exactly which endpoints legacy itself checks and which it doesn't."
- **D-045** — "no `hr_permissions.can_*` flag is enforced on a legacy endpoint
  unless that endpoint's PHP demonstrably enforces it", and it enumerates
  `can_assets` among the fifteen flags **never read as a gate anywhere**.

So the port's behaviour here follows from decisions already taken, and this
wave owes **evidence**, not another owner sign-off. Treating it as a blocking
decision would have stalled Wave 13.4 for a question that was answered before
it started.

What this entry adds is the endpoint-specific record, so that:

- the behaviour is traceable to D-044/D-045 rather than looking like an
  accident of faithfulness;
- anyone later "hardening" the module knows they are changing a live contract
  and which client depends on the current one;
- the eventual fix is a legacy change first, ported second — not a divergence
  introduced in the Java.

Custody records are not payroll or personal data, the exposure is bounded to a
single tenant, and it is reachable only by roles already trusted with the rest
of that tenant's HR data — which together are why this is acceptable as a
Phase-1 residual rather than a blocker. It is **not** closed, and it does not
become closed by being written down. It is registered as **R-010** so that a
risk-based release review has an owner, a trigger and a contingency for it
rather than only a decision-log paragraph.

### Smaller preserved behaviours

- **Two filter guards in one handler.** `assets/list.php` uses `!empty()` for
  `employee_id` and `isset()` for `is_returned`, so `?employee_id=0` silently
  lists *everyone* while `?is_returned=0` really filters.
- **`list` and `one` return different columns.** The list selects
  `photo_url`; `one` does not.
- **An explicit `is_returned: false` beats the `returned_at` inference**,
  because `array_key_exists` is tested before the date is consulted.
- **A foreign employee id is `employee_not_found` (404)**, the same answer a
  genuinely missing id gives — so it confirms nothing about another tenant.
- **`administrative_decisions/create` can answer 201 with `data: {}`**:
  `public_row($row ?? [])` renders an empty object if the re-read comes back
  empty rather than failing.
- **A non-positive id 404s before any query**, because
  `administrative_decision_assert_company_row()` returns null for `id <= 0`.

### Ledger after Wave 13.4a

`FINAL_COMPATIBLE` 142 → **152**; `ITEM13_REMAINING` 56 → **46**; partition
137/4/1 → **147/4/1**. Live total 198 unchanged.

Evidence: `./gradlew check` — **0 failures**. The two module-disagreement
regressions were falsified by harmonising the boolean rule and by dropping the
employee row filter; each break was caught by the case written for it.

**The test count is deliberately not restated here.** An earlier revision said
"2014 tests, 14 new", which was already wrong when written — the wave added
sixteen `@Test` methods — and went further out of date with every review round
that added another regression. A figure that must be re-measured on each push to
stay true does not belong in a durable decision record; the suite's own output is
the evidence, and `LegacyPhpRouteInventoryTest` is what pins the delivered
route count.

## D-131: Wave 13.4b delivers `workforce_planning`, reproducing a cross-tenant disclosure and filing it upstream

**Status: ACCEPTED 2026-08-30 by the repository owner — see D-141.** It stood as
`PROPOSED` from 2026-08-29 until then, and the paragraphs below were written
while it was still open; they are kept unedited as the record of what was put to
the owner, and of the fact that no agent decided it. The answer was parity, on
both surfaces, without holding Item 13.

<!-- markdownlint-disable-next-line MD036 -->
**This entry was written and implemented in the same change, by the same
author, and no human has approved it.** An earlier revision marked it
"Accepted", which it was not: filing an upstream issue is not approval, and
AGENTS.md is explicit that no agent may silently make a decision of this kind.
The distinction matters more here than anywhere else in Item 13, because what is
being decided is whether to knowingly ship a reproduction of a **cross-tenant
information disclosure**.

**What approval means here, precisely.** The port is faithful and the default
governing it is D-058 — Phase 1 reproduces legacy, and diverging in Java alone
would make the two systems answer differently for the same request. The question
put to the owner is narrower: *given that this specific defect crosses a tenant
boundary, is parity still the right default, or should Wave 13.4b wait for
`hr-legacy#33` to land first?* Either answer is defensible; neither is the
agent's to pick.

Until that is recorded, this entry stands as a proposal and the pull request
should not merge on a green gate alone.

Seven routes, six handlers -- `summary.php` is literally
`require __DIR__ . '/list.php'`, so the two URLs are one endpoint and are mapped
as two paths on a single method rather than duplicated.

### The finding

Only one of the three write paths validates the foreign ids it stores:

| Endpoint | Validates `branch_id` / `department_id` / `job_title_id`? |
|---|---|
| `create.php` | **yes** — three explicit ownership checks |
| `save_target.php` | **no** |
| `update.php` | **no** — all three sit in the `whitelist_update_fields()` allowlist |

And the read path's three `LEFT JOIN`s match on id alone, with **no tenant
predicate**. Together those mean a `company_admin` or `hr` user of company A can
`POST save_target.php` with company B's `branch_id`, then `GET list.php` and
read **company B's branch name** back. The same works for departments, job
titles, and through `update.php`. Iterating ids enumerates a competitor's
organizational structure.

Only names leak, not employee or payroll data — but it is a cross-tenant read by
an authenticated user of a different tenant, reachable in two ordinary API calls
with no special conditions.

### The same defect is on a second surface, already delivered in Wave 13.5

`apis/api/dashboard/stats.php:91-99` runs the same unscoped join:

```sql
FROM workforce_planning wt
JOIN departments s ON s.id = wt.department_id
WHERE wt.company_id = ?
```

`workforce_planning.department_id` carries no foreign key
(`mysql_workin.schema.sql:939-948`; the table's only indexes are its primary key
and `uq_workforce_target`), so a row this company owns may reference another
company's department. `stats.php` then returns that department's **name** and,
through the correlated subquery, its **active headcount** — one field more than
the 13.4b path leaks. It needs no write of its own: a single authenticated `GET`
is enough once such a row exists, and `save_target.php` is how it gets there.

**This changes what the decision buys.** Holding Wave 13.4b was the obvious way
to keep the disclosure out of the cutover; it no longer is. `stats.php` is
delivered in **Wave 13.5 (PR #138)**, which sits *below* 13.4b in the stack, so
every option that merges 13.5 ships the leak whatever happens to 13.4b:

| Option | 13.4b's `list.php` leak | `stats.php` leak |
|---|---|---|
| Merge the stack | ships | ships |
| Hold 13.4b only | held | **ships** |
| Hold from 13.5 up | held | held — but this holds Waves 13.5, 13.3, 13.4a and everything stacked above them, which is all of Item 13 |

So the question is no longer "ship 13.4b or wait". It is: accept the disclosure
across both surfaces for Phase 1, or hold **Item 13 as a whole** for
`hr-legacy#33`. The third option — fix it in Java only — remains the one this
entry argues against, and doing it on `stats.php` alone would be worse than
either, because the two surfaces would then disagree with each other as well as
with PHP.

`hr-legacy#33` must be updated to name `dashboard/stats.php` alongside the
`workforce_planning` routes; the upstream fix has to cover both, or the port
cannot follow it.

The exposure is now carried in the risk register as **R-012**, recorded as open
and undecided rather than as an accepted residual — a cutover or security review
starting from the register has to be able to find it.

This surface was missed when D-131 was first written. It was found by review on
PR #138, not by the wave that introduced it.

### Why it is reproduced rather than fixed here

Phase 1's contract is parity (D-058), the defect exists in production today, and
the Java port does not make it worse. **Fixing it in the port alone would be a
silent divergence** — the two systems would answer differently for the same
request, which is exactly what the phase exists to prevent, and it would mask
the problem rather than resolve it.

So: reproduced exactly, **filed upstream as `hr-legacy` issue #33** with a
proposed fix and a note that existing rows may already carry foreign ids, and
recorded here. The port changes when legacy changes, in the same direction.

### The regression asserts the vulnerable behaviour on purpose

`saveTargetLeaksAnotherCompanysBranchNameThroughTheUntenantedJoin` performs the
attack and asserts that the victim's branch name comes back. That is deliberate,
it is commented as such in the test, and the comment instructs that the test be
**inverted, not deleted**, once legacy is fixed — so the fix cannot land without
someone consciously changing this assertion.

**Nothing in this entry should be read as an endorsement of the behaviour.**
Writing a defect down does not close it, and this one is open.

### Other preserved behaviours in this module

- `save_target.php` upserts on the `uq_workforce_target` unique key over
  `(company_id, branch_id, department_id, job_title_id)` and answers
  `{"saved": true}` — never the row — so a caller cannot tell whether it created
  or updated.
- A negative `planned_count` is **floored to 0** by `max(0, (int) ...)` rather
  than rejected.
- The department check in `create.php` is guarded by `if ($section_id > 0)`, so
  it is skipped for **0 and for any negative id alike** — an earlier revision of
  this entry said only 0 bypassed it, which understated the guard. The schema
  defaults `department_id` to 0 and legacy reads that as "no department" rather
  than as a foreign key; a negative id simply never reaches a lookup and is
  stored as supplied. The port matches the guard exactly, so tightening it to
  `!= 0` would reject a create legacy accepts.
- `job_title_belongs_to_company()` additionally requires `is_active = 1`, so an
  inactive job title is `job_title_not_found`.
- The list's search matches the **job title's** name only, though the row also
  carries the branch and department names.
- `update.php`'s post-write re-read drops the `company_id` filter its own
  `UPDATE` carried.

### Security and inventory artifacts corrected alongside this entry

Four documents described `workforce_planning` as company-scoped without
qualification, which the evidence above disproves. All four now state that the
module's **name joins carry no tenant predicate** and that two of its three
write paths accept unvalidated foreign ids:

- `docs/api/existing-endpoint-inventory.md` — the module section, whose own
  caveat ("scoping depth not traced further in this pass") is where the gap
  lived;
- `docs/api/three-frontend-api-usage-matrix.md` — the client contract row;
- `docs/legacy/existing-php-module-inventory.md` — which read "consistently
  company-scoped";
- `docs/security/threat-model.md` — a new **tenant ↔ tenant** row, because a
  cross-tenant read belongs in the artifact security triage and cutover review
  actually consult, not only in a decision log.

The threat-model row records D-131 as **proposed**, so the model does not imply
an acceptance that has not happened.

### Ledger after Wave 13.4b

`FINAL_COMPATIBLE` 152 → **159**; `ITEM13_REMAINING` 46 → **39**; partition
147/4/1 → **154/4/1**. Live total 198 unchanged.

Evidence: `./gradlew check` — **0 failures**, 10 new regressions. The suite-wide count is deliberately omitted: every review round adds regressions, so an aggregate recorded here is stale by the next commit (the same reason given two entries above).

## D-132: Wave 13.4c completes Item 13.4, and ports the public complaints write as-is

**Status:** Accepted 2026-08-29. Owner-approved disposition of the C3/C8 pass's
`complaints` finding. Completes Item 13.4 (28 endpoints across 13.4a–c).

Eleven endpoints: `employee_docs` (4), `complaints` (4) and
`company_join_requests` (3).

### `complaints/create.php` is the sixth public route and the first that writes

Auth is optional — `if ($auth = getAuth())` attaches the employee and company
when a token is present and leaves both null when it is not. That required a new
`LegacyRequestGuard#optionalAuth()`, and **the boundary is not where the obvious
summary puts it.** An earlier revision of this entry said a present token is
always validated. It is not:

- `getAuth()` ends in `jwtDecode()`, which returns `null` for a **malformed
  token, a bad signature, or an expired `exp`** (`functions.php:435-453`).
  `if ($auth = getAuth())` is then false and the request proceeds
  **anonymously**. An undecodable token is invisible on this route in legacy,
  and the port matches.
- What survives a successful decode *is* enforced: PHP follows `getAuth()` with
  `requireEmployeeSessionValid()`, so a validly-signed token whose
  `token_version` has been bumped is **refused with 401** rather than downgraded
  to anonymous.

So: a token that cannot be decoded is invisible; a token that decodes but has
been revoked is refused. Stating it the other way round would have justified
failing a request legacy accepts.

**An anonymous complaint is written and then unreachable.** It is stored with
`company_id = NULL`, and `list.php` filters `c.company_id = ?`, so no company's
list can return it and there is no other read path. The regression asserts both
halves: the row exists in the table, and it is absent from the list.

The same structural exclusion hits an authenticated **admin's** own submission,
which is tagged `source = 'company_support'` while the list filters
`source = 'employee'`. Two different reasons, same outcome: written, invisible.

Whether that is a defect or a deliberate inbox read outside the API is the open
question C3-a raised. **It is not filed upstream on this evidence** — unlike
hr-legacy #31, #32 and #33, where the code contradicts itself on its own terms.
The owner's decision was to port as-is and ask later. The questions the route
does raise — rate limiting, spam, PII retention on an anonymous public write —
are recorded in their **owning registers**, not only here:
`docs/bootstrap/open-questions.md` holds the questions themselves and
**R-011** holds the exposure, with an owner, a trigger and a contingency. A
decision log entry that leaves questions open without registering them makes
the authoritative open-question and risk views silently incomplete. `LegacyPhpRoutes` now says
explicitly that the data argument covering the other five public routes does
**not** cover this one.

### `employee_docs` grants MANAGER a role it does not honour

All four endpoints authenticate `[COMPANY_ADMIN, HR, MANAGER, EMPLOYEE]`, and
then the scope checks split those roles two different ways:

| Endpoint | Check | MANAGER |
|---|---|---|
| `list`, `upload` | `role === EMPLOYEE` | **passes** — may act for any employee in the company |
| `update`, `delete` | `role not in [ADMIN, HR]` | **blocked** — own documents only |

So a manager can upload a document to another employee's file and then cannot
update or delete it. Found by the bounded C3/C8 pass (finding C3-b), pinned by a
regression, because a port that tidied the two checks into one shape would
change behaviour for exactly the role that sits between them.

`employee_docs` has **no `company_id`** of its own, so every path reaches a row
only after its owning employee's company has been checked. That ordering is
enforced in the service and stated in the store, because losing it would make
the table globally readable.

### Two definitions of "pending" in one module

`company_join_requests/list.php` matches the literal string `'pending'`, while
`reject.php`'s `join_request_is_pending()` treats an **empty** status as pending
too. A provisional row with a blank status is therefore **invisible to the list
and still rejectable through the endpoint beside it**. Asserted, because it
reads like a fixture bug until you check both helpers.

**Rejection deletes the employee row** so the phone number becomes reusable;
acceptance only flips two columns. The two are not inverse operations and a
rejection is not recoverable. `reject.php` also notifies *before* deleting,
which is the only order that works.

**Accept has no pendingness check**, so accepting an already-accepted request
succeeds and re-notifies; only `reject` checks.

### Smaller preserved behaviours across the three modules

- `complaints` guards its id with `(int) (... ?? 0)` and a `<= 0` test answering
  `invalid_id` — not the `required()` / `field_required` pair the rest of the
  wave uses.
- `complaints/update.php` reads `reply` with `array_key_exists` (so an empty
  string **clears** the column) and `status` with `!empty` (so an empty status is
  silently ignored, making it indistinguishable from supplying nothing).
- The complaints list filters to `pending` **by default**; `?status=all` is the
  escape hatch, and an unrecognised status is *wider* than the default.
- `employee_docs/update.php` **and `complaints/update.php`** are **POST**, not
  PUT. They are the only two: every other `update.php` across the whole legacy
  API is PUT (24 update routes, 22 of them PUT). An earlier revision of this
  line named `employee_docs` alone and said it was unlike "the rest of Item 13",
  which reads as though `complaints/update.php` were PUT. It is not
  (`apis/api/complaints/update.php:6`), and a reviewer took that wording as
  evidence the port had the wrong verb. Both are POST in Java because both are
  POST in PHP.
- `doc_type` defaults to the literal `"other"` and is neither trimmed nor
  validated against any list.

### Ledger after Wave 13.4c

`FINAL_COMPATIBLE` 159 → **170**; `ITEM13_REMAINING` 39 → **28**; partition
154/4/1 → **165/4/1**. Live total 198 unchanged. **Item 13.4 is complete**; only
Waves 13.1 (auth, 13) and 13.2 (profile 9 + notifications 6) remain.

Evidence: `./gradlew check` — **0 failures**, 14 new regressions. The suite-wide count is omitted for the reason given under the preceding entries: it is stale by the next commit.

## D-133: Wave 13.2 delivers `notifications` (6) and seven of the nine `profile` endpoints

**Status:** Accepted
**Date:** 2026-08-29
**Context:** Item 13's remaining 28 endpoints are Wave 13.1 (auth, 13) and Wave
13.2 (profile 9 + notifications 6).

### What is delivered

All six `notifications/*.php` routes, and seven of the nine `profile/*.php`
routes. `request_phone_change.php` and `confirm_phone_change.php` are **not**
in this wave, and the reason is sequencing rather than scope reduction: both
are OTP flows and their entire helper set —
`otp_issue_and_send_whatsapp()`, `otp_verify_latest_for_phone()`,
`otp_clear_for_phone()`, `otp_has_recent_for_phone()`, plus
`phone_country_resolve_code()`, `phone_normalize_local()`,
`phone_is_valid_local()`, `phones_are_equivalent()` and
`phone_sql_match_clause()` — is shared with Wave 13.1's auth endpoints
(`verify_otp.php`, `forgot_password.php`, `reset_password.php`,
`complete_company_registration.php`). Porting that layer twice, once per wave,
is how the two copies diverge. They ship with 13.1.

### `notifications`: two inboxes that never overlap

`notification_inbox_filter()` is the whole tenant boundary for the module and
it branches on the **auth type**, not on which id happens to be non-zero. A
`type=company` session reads `recipient_kind = 'company'` rows for its company;
an employee session reads `recipient_kind = 'employee'` rows addressed to
itself. Both filters pin the kind, so the two inboxes are disjoint even though
every row carries the same `company_id` — a company admin does not see their
employees' notifications and an employee does not see the company's.

The company branch additionally requires `company_id > 0`, so a company-type
token with no company falls **through** to the employee test rather than
matching an unscoped company inbox. That fallthrough is why
`LegacyRequestContext` gained an `authType` component in this wave instead of
inferring the type from a zero employee id.

### The falsy-id rule, ported deliberately

`mark_read.php` and `delete.php` both read the id as
`isset($_GET[ID]) ? (int) $_GET[ID] : null` and then test `if ($id)`. So
`?id=abc` casts to `0`, which is falsy, and the request takes the **all**
branch. `DELETE /apis/api/notifications/delete.php?id=abc` empties the caller's
entire inbox instead of answering 400.

This is ported as-is under D-058 and asserted in
`anUnparseableIdDeletesTheWholeInbox`, so that a later reader who thinks it is
a bug has to change a test that says it is intentional. It is bounded to the
caller's own inbox — the ownership probe and the delete are both inbox-scoped —
so it destroys the caller's data and nobody else's.

`one.php` is the one route whose id is `required()`, and it is also a **GET
that writes**: reading a notification marks it read, and the body returns the
in-memory `is_read = 1` PHP assigns rather than a re-read.

### `profile/employee.php` authenticates before it checks the method

Every other route in the module — and in every module ported so far — checks
`$_SERVER['REQUEST_METHOD']` first, which is why an anonymous request with the
wrong method is a 405 rather than a 401. `profile/employee.php` does the
opposite: `requireAuth()` runs at the top and the method dispatch follows. An
anonymous `DELETE` to it is therefore **401**, and the same request to
`profile/logout.php` next door is **405**. Reproduced in the controller and
asserted in `employeeProfileAuthenticatesBeforeItChecksTheMethod`.

Its role list admits all four roles, so what actually blocks a company-type
session is the `if (!$employee_id)` below it — answering **401**, not 403.

### The PUT's ordering, which is observable at three points

1. An **empty body** is `nothing_to_update` *before* the employee is looked up,
   so a nonexistent employee with an empty body gets 400 and not 404.
2. The **phone block** runs before the allow-list is walked. `?phone` with no
   digits normalises to null, which **clears** the phone and nulls
   `country_code` with it — whether or not the body mentioned the country code.
   The country-code check is an `elseif`, so it only fires when a phone
   survived normalisation *and* the body carried the key; a phone supplied with
   no country code at all is accepted here, unlike
   `resolve_employee_phone_and_country_code()` elsewhere in legacy.
3. A body of keys that are none of the five self-service columns reaches a
   **second** `nothing_to_update` — it passed the first test by being non-empty
   and produced no assignments.

The password is guarded by three tests, `!empty && is_string && trim !== ''`,
and the middle one is load-bearing: `{"password": 12345678}` is silently
ignored rather than hashed, and when it is the only field the request ends as
`nothing_to_update`.

### `logout.php` is not a logout

For an employee session it **deactivates the account**, notifies the company,
and drops every push token the employee owns; re-joining needs the company code
again. The notification fires only when the row was active *before* the update,
so a repeated logout notifies once, and the name falls back display-name →
phone → `#id`, each after a trim. None of it is transactional, so a failing
notification leaves the account deactivated (D-089).

The notification text renders in the **departing employee's** locale, because
`t()` reads the current request's language and this is that request. The
company reads its inbox later in whatever language the leaver's app was set to.
That is true of every notification legacy writes, not a quirk of this route.

### `register_push_token.php` is ported and cannot succeed — R-013

The endpoint inserts into `push_tokens (employee_id, company_id, token,
platform)`. The frozen table has **no `company_id` column** and **no unique
key** for its `ON DUPLICATE KEY UPDATE` to fire on. Every call is a database
error, for both session types, and the port reproduces that rather than
repairing the statement.

This is not speculation: `mysql_workin.schema.sql:802-808` defines the table
and `:1247-1249` its only indexes. It corroborates F-08 (push never worked end
to end), mobile's commented-out call, and the ETL decision to drop the table.
Recorded as **R-013** with three questions in `docs/bootstrap/open-questions.md`
— whether production drifted, whether a company-owned token is intended, and
what the upsert key should be — and left for `hr-platform#22`, which owns push
delivery, rather than answered here.

### `delete_account.php` hard-deletes a tenant, with no rollback path

The company branch runs `company_cascade_delete()`: one transaction, fifteen
count queries for the pre-delete summary, then reference-breaking updates
(`notifications.from_employee_id = NULL`, `departments.manager_id = NULL`)
before the rows they point at go. There is **no soft delete and no archive**;
recovery means a database backup. Several sub-deletes sit inside
`catch (Throwable $ignored)` in legacy and do here too, so a partial cascade
can commit — but the final `DELETE FROM companies` must affect exactly one row
or the whole transaction rolls back. All of that is transcribed, not
re-derived, because the order is what makes it work.

### Two parity fixes to already-merged code

- `LegacyPhpLoginService` carried its own `attachAttendanceLocationFlag()` that
  tested the cross-branch flag as `toPhpLong(...) != 0`.
  `employee_can_check_in_any_branch()` tests a **literal set**,
  `true|1|'1'|'true'`, so a `can_check_in_any_branch` of `2` is false in PHP and
  was true in the copy. Both callers now go through
  `LegacyAttendanceLocation.attachBranchLocationConfiguredFlag()`, which was
  already the faithful port. One helper, as PHP has one.
- `LegacyNotifications.toCompany()` hard-coded `reference_type` and
  `reference_id` to NULL because its only caller passed neither.
  `notification_employee_left_company_to_company()` passes `'employee'` and the
  departing employee's id, so an overload carrying them was added rather than a
  second insert.

`employee_row_attach_hr_permissions()` also needed its **query** branch for the
first time: `profile/employee.php` joins no permission columns, so the helper
looks them up. `LegacyHrEmployeeService`'s private copy only ever had the
row branch, because its own module always joins. The shared
`LegacyHrPermissionRows` carries both, keyed on `array_key_exists('can_branches')`
exactly as PHP is.

### Ledger after Wave 13.2

`FINAL_COMPATIBLE` 170 → **183**; `ITEM13_REMAINING` 28 → **15**; partition
165/4/1 → **178/4/1**. Live total 198 unchanged. Remaining: Wave 13.1, which is
the 13 auth endpoints plus the two OTP-dependent `profile` phone-change routes.

Evidence: `./gradlew check` — **0 failures**. The suite-wide count is omitted deliberately: it is stale by the next commit.

## D-134: Wave 13.1a delivers the OTP layer, the four public OTP routes, and the two `profile` phone-change routes

**Status:** Accepted
**Date:** 2026-08-29
**Context:** D-128 deliberately scheduled Wave 13.1 last, calling it "the
largest security surface in Item 13" and reserving it for a point where review
is available. Wave 13.2 then deferred `profile/request_phone_change.php` and
`profile/confirm_phone_change.php` into it, because both are OTP flows sharing
their whole helper set with `auth` (D-133).

### The split, and why it is not arbitrary

13.1 is fifteen endpoints. **13.1a** is the six that are OTP flows end to end —
`auth/{verify_otp,resend_otp,forgot_password,reset_password}` plus the two
`profile` phone-change routes — together with the layer they all sit on:
`otp_helper.php`, `whatsapp_helper.php`, and the two phone helpers Wave 12
had not yet needed (`phone_sql_match_clause()`, `phones_are_equivalent()`).
**13.1b** is the nine account-lifecycle endpoints: registration, joining, and
the three logins.

The line is the OTP layer. Everything in 13.1a either issues or verifies a
code; nothing in 13.1b does except through the same layer, which is why the
layer ships first and separately.

### The OTP code does not reach the response, and that is not a new decision

Legacy answers `ok(OTP_SENT, AppConfig::DEBUG ? [Response::OTP => $code] : [])`
on `resend_otp`, `forgot_password` and `register_company`. The threat model
records the consequence in full: with `DEBUG` on, anyone who knows a phone
number reads the real code straight back and completes `reset_password.php` —
a complete authentication bypass, not scoped to one tenant.

That was **confirmed live on 2026-08-04 and the production value was changed to
`false` on 2026-08-05** by the repository owner. PMR-05 and `hr-legacy#4`
already record "no `DEBUG`-gated secret exception at all" as a mandatory
requirement of this rewrite. So the false branch is the only branch here: the
response carries PHP's empty array (`"data": []`, an array and not an object),
and `theIssuedCodeIsNeverPutOnTheWire` asserts it by reading the code out of
the *delivered message* rather than being handed it.

Legacy's other `DEBUG` branch — `sendWhatsAppText()` returning **true** when
WhatsApp is unconfigured, so an undelivered OTP counts as sent — is not ported
for the same reason and a stronger one: it would let an unconfigured production
issue codes nobody receives while reporting success.

### R-014 is now asserted, not just described

`otp_assert_can_send()`'s third check reads as a per-IP hourly cap. Against the
frozen schema it is not one. `otp_count_recent_sends()` drops any predicate
whose column is absent, and all three are: `otp_request_logs` does not exist,
and `otp_codes` has neither `ip_address` nor `purpose`. Called as
`otp_count_recent_sends(null, $ip, '', 3600)` — no phone argument either — what
executes is:

```sql
SELECT COUNT(*) FROM otp_codes WHERE created_at > NOW() - INTERVAL 3600 SECOND
```

Every OTP the platform issued in the last hour. At twenty, **everyone** is
refused, and the rows accumulate because `otp_clear_for_phone()` soft-invalidates
by design. `thePerIpCapIsActuallyAPlatformWideCap` seeds twenty rows for twenty
unrelated phones and shows a twenty-first, previously-unseen phone refused —
then recovering when the global count drops. Ported as-is; the register entry
was written before the code so the finding could not be lost inside the wave.

The other degradation is harmless: the per-purpose cap collapses to a per-phone
cap across all purposes, which is stricter than intended.

### Preserved behaviours a reasonable person would have normalised

- **`resend_otp.php`'s cooldown is 400, not 429.** `fail()` is called with no
  status argument, so it takes the default. Every other cooldown in the system
  is 429, including the one inside the limiter that checks the same 60-second
  window immediately after. The local check always wins, so the observable
  status is 400.
- **`resend_otp.php` does not check that the phone belongs to anybody.** Any
  number can be sent a WhatsApp message through it, once a minute.
- **A pending employee can log in but cannot reset their password.**
  `login_employee.php` lets a *single* pending account through;
  `resolve_single_employee_auth_by_phone()` rejects any pending account with
  `joined_company_wait_hr`. The two functions look alike and differ exactly
  here, which is why `LegacyPhoneAuthResolver` is its own class rather than
  `LegacyLoginResolver` with the password filter removed.
- **`reset_password.php` has no minimum password length**, unlike
  `profile/change_password.php`'s six. A one-character password is accepted.
- **`reset_password.php`'s company branch updates every matching row**, not
  one — two companies sharing a number both have their password replaced.
- **`verify_otp.php`'s company update matches the phone exactly**, not through
  `phone_sql_match_clause()` like everything around it. A company whose stored
  phone carries a `+` or a space verifies successfully and is never marked
  `otp_verified`, leaving it stuck at `login_company.php`'s verify-first branch.
- **The two phone-change routes refuse a non-company session with 403** where
  `delete_account_preview.php` refuses the same condition with 401. One module,
  two statuses, both preserved.
- **Confirming a phone change sets `otp_verified = 1`**, so a company that
  never verified its original number becomes verified by changing it.
- **The `X-Forwarded-For` family is trusted ahead of the socket address**, so a
  caller chooses the IP the per-IP limit sees. Given R-014 that limit is not
  per-IP anyway, but the trust order is ported and recorded rather than
  quietly hardened.

### The WhatsApp integration is real, and unconfigured means 503

`LegacyWhatsAppSender` is a seam and `LegacyWhatsAppHttpSender` is the Whats360
call behind it: 15-second timeout, primary-then-fallback instance ordering, and
a fifteen-minute skip for an instance that answered "not connected". PHP keeps
that skip map in a temp file because each request is a fresh process; a JVM
shares memory, so it lives in the process. Same scope, different mechanism.

Failures never throw — legacy logs and returns false, and the caller turns
false into **503 `otp_delivery_failed`**. Throwing would turn a 503 into a 500.
A deployment with no WhatsApp credentials therefore cannot issue an OTP at all,
which is legacy's production behaviour and is asserted
(`aFailedDeliveryIs503AndStillConsumedTheSlot`) — including the part that
matters operationally: **the OTP row is written before the send**, so a failed
delivery still puts the caller in cooldown for a code they never received.

### Ledger after Wave 13.1a

`FINAL_COMPATIBLE` 183 → **189**; `ITEM13_REMAINING` 15 → **9**; partition
178/4/1 → **184/4/1**. Live total 198 unchanged. Remaining: Wave 13.1b's nine
account-lifecycle endpoints.

## D-135: Wave 13.1b completes Item 13 — the nine account-lifecycle `auth` endpoints

**Status:** Accepted
**Date:** 2026-08-29
**Context:** Wave 13.1a delivered the OTP layer and the six endpoints built on
it. The nine that remained are registration, joining and the three logins.

**With this wave `FINAL_COMPATIBLE` reaches 198 — the whole live endpoint
surface. `ITEM13_REMAINING` is 0.**

### Three ways to find a company by phone, all preserved

`register_company.php`, `register_employee.php` and `login_company.php` match
the `phone` column **exactly** against the submitted value.
`join_company.php` and `forgot_password.php` match through
`phone_sql_match_clause()`, which accepts every stored spelling. The
consequence is asymmetric and real: a company stored as `+201012345678` can be
joined and can reset its password, but **cannot log in** unless the client
sends exactly that string — and a second registration under `01012345678` is
not detected as a duplicate. Asserted in
`registerCompanyDuplicateCheckIsExactSoAVariantSlipsThrough`.

### Two endpoints that both mean "join", and only one works

- `register_employee.php` keys off the company's **phone**, writes no
  `join_request_status` (so the column default makes the employee immediately
  `accepted`), and writes no `branch_id`.
- `join_company.php` keys off the public **code**, resolves the company's first
  active branch, and creates a `pending`, inactive row.

The first **cannot succeed**: `branch_id` is `NOT NULL` with no default, takes
an implicit `0` under `sql_mode=''`, and `fk_employee_branch` rejects it. Every
call is a 500. Recorded as **R-017** and asserted. This was found by running
the port, not by reading it — the first test run returned the constraint
violation, and the test now pins it.

### R-016: `complete_company_registration.php` hands out a company-admin session

The endpoint is unauthenticated — `grep -c 'requireAuth\|requireCompanyActive\|getAuth'`
over the file returns **0** — takes `company_id` straight from `$_POST`, and
returns `jwtEncode([type => company, company_id => <caller-supplied>, role =>
company_admin])`. Its only gates are that the row exists, `otp_verified = 1`
and `profile_completed ≠ 1`.

**The threat model rated this Medium on the explicit reasoning that it "does
not grant login access to the hijacked company".** The token block four lines
from the end of the file shows that it does. The row was corrected to Critical
with that evidence, and **R-016** records it. The regression is the proof, not
a description: it presents no credential, names a company it does not own, and
then uses the returned token successfully against `profile/company.php`.

Ported in parity form because that is Phase 1's contract. Recording it in three
places is what makes shipping it a visible decision rather than a silent one.

### The `ALTER TABLE` in `register_company.php` is deliberately not ported

PHP wraps its insert in an `ensureColumn()` helper that runs
`ALTER TABLE companies ADD COLUMN ...` **at request time, from a public
unauthenticated endpoint**, and only errors if the column is still missing
afterwards.

Every column it guards exists in `hr-legacy@d113204`, so all seven gates take
their early return and the DDL never executes. The branch is unreachable
against the frozen schema, and running schema migrations from an anonymous HTTP
request is a line this repository's production standards do not cross. The
observable half **is** reproduced: the columns are still required and their
absence would still be an error rather than a silent skip. This is the wave's
one deliberate divergence and it is recorded here rather than left implicit.

### The two logins disagree in three places

`login_company.php` and `login_desktop.php`'s company branch are almost
identical and differ exactly here:

| Condition | `login_company.php` | `login_desktop.php` |
|---|---|---|
| Unknown phone | 401 `invalid_phone_password` | 401 `company_not_registered` |
| Wrong password | 401 `invalid_phone_password` | 401 `incorrect_password` |
| Profile incomplete | 403 `complete_company_profile_first` | **200** with the company and no token |
| On success | no onboarding notifications | writes the two onboarding notifications |

So desktop tells an anonymous caller whether a phone is registered and mobile
does not. All four differences are preserved and asserted.

`login_desktop.php`'s HR branch is **HR only** and says so in SQL — `role =
'hr' AND is_active = 1` — so a company admin with the same phone is
`user_not_found` 401 there even though `login_employee.php` would admit them.
It orders `e.id ASC`, oldest first, where every other login path orders newest
first.

### Smaller preserved behaviours across the nine

- A `pending` company **can** log in; only a status that is neither pending nor
  active reaches `company_pending_admin`.
- `check_status.php` answers **200 for all four outcomes** — it routes the
  client's next screen, it does not guard — and matches the phone exactly, so a
  differently-formatted number is "not found".
- `lookup_company.php` falls back to the legacy id only when the code is
  **empty**; a supplied-but-invalid code is `company_code_invalid` and never
  falls back. With neither, the error names `company_code`.
- `complete_company_registration.php` uploads **both files before** validating
  the three foreign keys, so a bad `company_title_id` still leaves two files on
  disk. Its three foreign-key checks share one `field_required` with **no field
  name**, so the client cannot tell which was wrong. And `first_name`/`last_name`
  are written only when non-empty, so step two cannot blank step one's names.
- `resolve_employee_name_from_body()`'s `name`-splitting fallback is
  **unreachable from `join_company.php`** — `first_name` is `required()`, and a
  body supplying only `name` is a 400. **Corrected 2026-08-30 after review:**
  the `Pending-<phone>` fallback is *not* unreachable, and the very next bullet
  said why without the connection being made. `required()` is
  `isset() && !== ''`, so `"first_name": "  "` **passes** it; `fromBody()` then
  trims to empty and assigns `Pending-<phone>`. One input shape reaches it, and
  the regression now asserts the stored name rather than only the 201 — the
  earlier version submitted exactly that body and checked only the status,
  which is how an invariant that the same decision contradicts two bullets
  later survived being written down.
- `required()` rejects `""` but not `"  "` — it is `isset() && !== ''`, not a
  trim. `register_company.php` is the exception and trims first, because it
  rebuilds the array it validates.
- `join_company.php`'s duplicate probe is company-scoped and treats `rejected`
  as absent, so a rejected applicant passes it — and is then stopped by the
  **global** `UNIQUE KEY phone` on `employees`. That is what the `try/catch`
  around the INSERT is for, and why the answer is 409 rather than the probe's
  400. Reproduced including PHP's inspection of the driver message for the word
  "phone" to choose between two message keys.
- A caller whose phone **is the company's own** bypasses both global-uniqueness
  checks, so an owner may create an employee account for themselves.
- `notification_ensure_company_onboarding()` is idempotent by **query**, not by
  constraint, so two concurrent logins can both insert. Harmless and preserved.

### Shared rather than copied

`LegacyPeopleController`'s private `$_POST`/`$_FILES` helpers moved to
`LegacyPostFields`, because `complete_company_registration.php` needs the
identical rules and PHP has one `$_POST`, not two. A second copy would be free
to drift while each module's own tests agreed with its own copy.

### Ledger after Wave 13.1b

`FINAL_COMPATIBLE` 189 → **198**; `ITEM13_REMAINING` 9 → **0**; partition
184/4/1 → **193/4/1**. **Item 13 is complete and every live legacy endpoint is
delivered.**

## D-136: Review dispositions for Wave 13.1 — what the independent gate caught

**Status:** Accepted
**Date:** 2026-08-29
**Context:** `chatgpt-codex-connector[bot]` reviewed PR #144 and raised seven
findings. D-128 had deferred this wave specifically so that review would be
available for it; this is what that bought.

Two were defects in the port, two were latent gaps the port made reachable, one
was an error in **this repository's own documentation**, one was an artifact
desync, and one was a deployment gap. None was rejected.

### The two real port defects

**The employee country code was being discarded.**
`forgot_password.php`'s no-`company_id` branch carries
`COUNTRY_CODE => $employee[COUNTRY_CODE] ?? null` out of
`resolve_single_employee_auth_by_phone()` and into the WhatsApp send. The port
passed null and let `otp_resolve_country_code_for_phone()` re-derive it — and
that helper matches the phone column **exactly**, so a number the
variant-aware account query found (`+966 50…`) is not found again, delivery
falls back to `+20`, and the JID is wrong. Fixed by reading the row's own
`country_code` alongside its phone;
`theResolvedEmployeesCountryCodeIsUsedForDelivery` asserts it and was falsified
before being trusted.

**`InetAddress.getByName()` resolved hostnames.** `otp_client_ip()` ports
`filter_var(..., FILTER_VALIDATE_IP)`, which never resolves names. The port
screened characters and then called `getByName()`, so a hostname made only of
hex letters and dots — `bad.cafe` — passed the screen and triggered a
**blocking DNS lookup of a name an unauthenticated caller chose**, on the
request thread, through `X-Forwarded-For`. Two problems in one: a divergence
from PHP and anonymous-input-driven outbound resolution. Replaced with a
literal-only IPv4/IPv6 parser, with `LegacyClientAddressTest` covering both
families and the motivating case.

### One error in our own documentation

The reviewer read `LegacyPhoneAuthResolver`'s javadoc — *"a set containing one
pending row reports pending regardless of what the other rows say"* — and
correctly flagged that the code does not do that. **The code was right and the
comment was wrong**: PHP's entire rejection block sits inside
`if ($login_ready === [])`, so a phone owning both a ready and a pending
account resolves to the ready one. The javadoc is corrected, says plainly that
an earlier draft stated it backwards, and
`aPhoneOwningBothAReadyAndAPendingAccountResolvesToTheReadyOne` now pins the
real behaviour so the wrong version cannot come back through a comment.

Worth recording because the failure mode is instructive: a confident,
well-written comment that inverts its code is harder to catch than absent
documentation, and it nearly became the specification.

### Two latent gaps the port made reachable

**A credential could reach the logs.** The Whats360 token travels in the
request URL — legacy's own shape — and several failures embed that URL in their
exception message; the port logged `ex.toString()`. Now only the exception's
class name is logged. AGENTS.md's "never print, log, commit, or otherwise
expose production credentials" is unambiguous, and this was one malformed
`api-base` away from writing the token to disk.

**Password reset did not revoke refresh sessions.** ADR-0005 states that
"logout and password change/reset revoke the relevant session(s) — closing the
gap where `hr-legacy` password resets never invalidate existing sessions", and
`#7`'s row names this wiring as remaining. `revokeAllForEmployee()` existed and
was called from **nowhere**. Now wired into all three legacy credential-changing
routes — `auth/reset_password.php`, `profile/change_password.php` and
`profile/logout.php` — rather than only the one the reviewer pointed at, because
fixing one of three would have been arbitrary.

It is **a no-op today**, and saying so matters. The first version of this
paragraph gave the weaker reason — that the only issuer, `LegacyLoginService`,
is reached solely by a test-only controller. **Corrected after the second review
round:** it is a no-op *by design of the phase*. D-111 states that short-lived
access tokens and rotating refresh tokens "are not permitted to alter the
literal Phase-1 `/apis/**` contract", so no route on that surface issues a
refresh token at all, and none is meant to. The calls are still worth having --
they cost nothing and are correct the moment a route outside this surface issues
one for these identities -- but the reason is the phase boundary, not an
unmapped bean.

### F-27 was stale, and the fix was not to change the code

F-27 says the reset-password and change-password endpoints "don't exist in the
rewrite yet" and blocks until they enforce a minimum length. They now exist —
as **Phase-1 parity ports**, which deliberately reproduce legacy's rules (no
minimum on reset, six characters on change). Enforcing a minimum in the ports
would make the two systems accept different passwords for the same request,
which is exactly the divergence Phase 1 exists to prevent.

So the row is synchronized rather than satisfied: the requirement attaches to
the **native** endpoints that replace these at cutover, and the row now says so
explicitly instead of leaving a delivered route sitting under a trigger
condition that had already fired.

### The deployment gap

R-015 recorded that no WhatsApp credentials are configured, but the properties
appeared nowhere in `application.properties` and nothing told an operator what
to do. Now: declared with their environment mappings and a comment saying why
they are empty, plus a runbook section covering configuration, four pre-cutover
validation steps, a symptom-to-log-line table, and a warning that R-014's
platform-wide cap will start refusing unrelated callers during a high-volume
smoke test on this path.

### What this says about the gate

Every one of the seven was actionable and none needed arguing down. The two
port defects were both in the same shape of code — a value quietly re-derived
instead of carried, and a standard-library call that does more than the PHP
function it replaces — which is the shape a second reader catches and an author
does not.

## D-137: Second review round on Wave 13.1 — four more defects, and the "complete" ambiguity

**Status:** Accepted
**Date:** 2026-08-29
**Context:** The 13.1b push drew a second round of six findings. Four were
defects in the port; two were the same artifact-conflict shape as D-136's F-27
finding, and together they exposed a real ambiguity in how this repository's
ledger is read.

### Four defects, three of them locale- or platform-shaped

**Uploads ran before every gate.** `complete_company_registration.php` reaches
`uploadFile()` only after the scalar checks, the company lookup and both state
gates. The port passed the stored URLs as **constructor arguments**, and Java
evaluates arguments eagerly — so a public request naming `company_id=0` wrote
both files to disk and then returned 400. That is a divergence from the order
this class's own javadoc documents, *and* a cheap way for an unauthenticated
caller to accumulate orphaned files. Fixed by passing an `UploadedFiles`
supplier invoked in PHP's position. The regression asserts the **file count**,
not just the status: four rejections above the uploads, and not one byte
written.

Note the shape: for the second time in this wave, the code contradicted a
comment that described the correct behaviour. The comment was right and the
code was wrong; in D-136's case it was the reverse. Both were caught by a
reader comparing the two.

**`toUpperCase()` is not `strtoupper()`.** `company_code_normalize()` is
byte-wise and touches only `a-z`. Java's applies Unicode folding, and some
foldings lengthen the string: `"abcß1"` becomes `"ABCSS1"` in Java and stays
`"abcß1"` in PHP. So Java would turn input PHP **rejects** into a valid code
that might match a real company's. Replaced with ASCII-only folding.

**`String.format("%04d")` is locale-dependent.** Under a default locale with
non-ASCII digits — `ar_EG` among them, which is not a hypothetical for this
product — it renders 7 as `٠٠٠٧`. That value would be stored and delivered, and
a client submitting the ordinary `0007` could never verify it. Every OTP in the
system would silently stop working on a host configured that way. `Locale.ROOT`
now, with a comment saying why it is load-bearing.

**Multipart field names skipped PHP's normalization.** `LegacyPostFields`
normalized dots and spaces on the urlencoded branch and used an exact
`getPart(name)` on the multipart one — so a part named `company.name`, which
PHP sees as `$_POST['company_name']`, was invisible. Both branches now iterate
and match on the normalized name.

### The ambiguity worth fixing once

Two P1s said, in effect: *the matrix declares this module's cutover blocked, and
you are marking it complete.* Both were right about the conflict and neither was
right that the port should change.

`#9` (the onboarding endpoint's guessable `company_id`) and `#10` (no rate
limiting on OTP verification) are **legacy defects the ports faithfully
reproduce**. Fixing either in Java alone would make the two systems answer
differently for the same request — the divergence Phase 1 exists to prevent.

The real problem was that `FINAL_COMPATIBLE` was being read as "cutover-ready",
and nothing said otherwise. The completion plan now states plainly that it
counts endpoints reproducing the frozen PHP and **nothing more**, lists the four
rows that remain cutover blockers over delivered endpoints (`#8`, `#9`, `#10`,
`F-27`), and says a parity port neither satisfies nor waives any of them. Rows
`#9` and `#10` carry the same statement from their side.

Two reviewers reaching the same wrong conclusion from the same document is a
documentation defect, not two reviewer errors.

### R-018

`#10`'s defect is now characterised precisely enough to be actionable, and it is
more serious than "no rate limiting" conveys: the issuance limiter guards only
issuance, so an unauthenticated caller can submit all 10,000 four-digit values
against an active code — and `verify_otp.php` with `purpose=password_reset`
deliberately leaves a correct guess **active** for `reset_password.php` to
consume. A successful brute force is therefore directly usable to set a password
the attacker chooses. Recorded as **R-018**, Critical, with the ten-minute
expiry noted as the only real limit.

## D-138: Third review round — the first two findings declined, on the decision record

**Status:** Accepted
**Date:** 2026-08-29
**Context:** The second round's fixes drew two more findings. Both were the
first in this wave where the port was already right, and both are recorded here
because *declining* a finding needs at least as much evidence as accepting one.

### D-042 does not govern the `/apis/**` token model — D-111 supersedes it

The finding read D-042 ("Phase 1 Keeps Legacy Login Semantics But Not Legacy's
Token Model", Accepted 2026-08-16) as forbidding the 10-year PHP JWT these
routes issue, and asked for short-lived access tokens plus refresh rotation.

**D-111 (Accepted 2026-08-25) supersedes D-042 on exactly this point, and says
so in the decision log itself:**

> The earlier draft of this decision incorrectly allowed the new-platform
> refresh-token design to remain on the Phase-1 employee-login route. **D-111
> supersedes that detail**: the frozen PHP login and token behavior is
> authoritative for Phase 1.

D-111 then requires the opposite of the finding: Java must preserve the
"authentication token shape"; `auth/login_employee.php` "does not add a refresh
token"; the compatibility chain "also accepts the frozen company JWT used by
desktop/company login" — a sentence written in anticipation of the very routes
Wave 13.1b delivers; and short-lived access tokens with rotating refresh tokens
"are not permitted to alter the literal Phase-1 `/apis/**` contract".

So the port stands. The 10-year lifetime remains a recorded defect
(`hr-legacy#7`) owned by the modernization phase, and
`app.legacy-jwt.expiry-hours` is already a property, so the lifetime is an
operational lever without a code change — though shortening it is client-visible
and needs the decision D-111 deferred.

**The finding did improve one thing.** It observed that the
`revokeAllForEmployee()` calls added in D-136 "only touch a refresh-token store
that they never populate". D-136 explained that as an accident of wiring; the
better reason is the phase boundary — D-111 forbids issuing refresh tokens on
this surface at all, so the calls are a no-op *by design* rather than by
oversight. D-136 is corrected.

### `join_company.php` really does discard the dial code — R-019

The finding said the insert omits `country_code` and asked for it to be added.
It is right that the column is omitted and right about the consequence. It is
wrong that this is the port's doing: PHP's INSERT names nine columns and
`country_code` is not among them, so legacy resolves the code, **validates the
phone against it**, and then throws it away.

Adding it in Java would make a joined employee's row differ between the two
systems on a column other endpoints read. So the defect is recorded as **R-019**
and pinned by `aNonEgyptianJoinerHasNoCountryCodeStored`, which asserts the NULL
and was falsified by writing the column and watching it fail.

The consequence is worth restating because it surfaces far from its cause: a
non-Egyptian joiner has no stored dial code, so a later `forgot_password.php`
falls back to `+20`, builds an Egyptian JID from a Saudi number, and the OTP
goes nowhere — while the logs record a successful send. The *same* failure mode
arrived in round one as a genuine port defect on a different route and was fixed
there (D-136); this one is legacy's and is not.

The fixture now seeds `phone_countries`, because the frozen dump ships it empty
and without a `+966` row the non-default-country case silently resolves to `+20`
and tests nothing.

### On declining findings

Two of fifteen findings across three rounds were declined, and both needed the
decision log to settle rather than the code. That is the ratio one would hope
for: the reviewer is reading the implementation without the full decision
history, so a finding that contradicts an accepted decision is more likely to
have found a *stale or ambiguous decision* than a wrong implementation — which
is what happened with F-27, `#9` and `#10`, where the artifacts were the thing
that changed.

## D-139: Fourth review round — three defects, one corrected decision, one honest limit

**Status:** Accepted
**Date:** 2026-08-30
**Context:** The third round's fixes drew five more findings. Four were valid;
the fifth re-raised a finding already declined with evidence, having read a
different thread's "Fixed" as covering it.

### Two half-done fixes from the previous round

**`file()` never got the treatment `field()` did.** Round three normalized
dot-and-space field names on the `$_POST` lookup and left `$_FILES` on an exact
`getFile(name)`. PHP normalizes both, so a part named `commercial.reg` is
`$_FILES['commercial_reg']` there and was null here — and on
`complete_company_registration.php` that means the logo is stored and *then*
the request is rejected for a missing commercial register. An orphaned file and
a misleading error, from a fix that stopped one method short. Both lookups now
follow the same rule, which is the entire point of them living in one class.

**`required()` ran in the wrong order.** `confirm_phone_change.php` checks all
three fields in a single call — `[PHONE, COUNTRY_CODE, OTP]` — before any
validation, so a body missing both the phone and the code is told about the
**phone**. The port checked `otp` first and reported `otp` for that body, which
sends a compatibility client down the wrong recovery flow.

### A decision that contradicted itself two bullets apart

D-137 stated that `resolve_employee_name_from_body()`'s fallbacks are
unreachable from `join_company.php`. Only the **splitting** one is. The
`Pending-<phone>` fallback is reachable, and **the very next bullet of the same
decision said why**: `required()` is `isset() && !== ''` rather than a trim, so
`"first_name": "  "` passes the guard and is then trimmed to empty by the
resolver.

The regression submitted exactly that body and asserted only the 201 — so the
test that should have caught the false invariant was what let it stand.
Corrected in three places: the decision, the helper's javadoc, and the test,
which now asserts the stored `Pending-01000033079` and was falsified by
disabling the fallback.

This is the second documentation defect this wave (D-136 was the first, in the
opposite direction). Both were confident prose next to code that disagreed with
it, and neither would have been caught by a reader looking only at the code or
only at the docs.

### A test that promised more than it delivered — and still does, now honestly

The cross-company duplicate test used a phone belonging to the same company it
was joining, so the company-scoped probe answered 400 and the 409 branch was
never reached: it asserted 400 under a name promising 409. The fixture now
seeds another company and the test asserts 409.

**Falsifying it produced a better result than the fix.** Disabling
`employee_phone_exists_globally()` left the test green, because
`employees.phone` is *globally* UNIQUE — the INSERT then fails and the
duplicate-entry catch answers with the same status and the same message key.
The explicit check and the index are redundant for this case and
indistinguishable over HTTP.

So the test pins the outcome, which is the contract, and cannot pin the branch.
It now says that, rather than leaving a reader to assume coverage it does not
have. A companion test covers the branch that *is* distinguishable: the
company's own phone bypasses both global checks.

### The re-raised finding

The fifth asked again for `join_company.php` to persist `country_code`, reading
another thread's "Fixed" as having covered it. That "Fixed" was round one's
`forgot_password.php` defect, where the port genuinely discarded a value PHP
carries. This route is the opposite: PHP's INSERT names nine columns and
`country_code` is not among them. Declined again with the column list, and
R-019 already records the legacy defect with a regression.

The fixture gained a `phone_countries` seed in the process — the frozen dump
ships that table empty, so without a `+966` row the non-default-country case
resolved to `+20` and the regression tested nothing.

### A sixth finding, declined on an inverted premise

A later finding asked for the logo to be checked *between* the two uploads,
stating that "the ported PHP ordering aborts after the failed logo upload
before attempting the second upload". It does not: both `uploadFile()` calls
are unconditional statements and both `if (!$url)` checks come after them
(`complete_company_registration.php:68-76`). Legacy therefore does store the
commercial register before discovering the logo is missing, and the port
matches.

The orphan the finding describes is real and is legacy's. Making the change
would have made the port write one fewer file than legacy for that input, on an
endpoint where the file count is observable on disk.

It is worth separating from the round-three finding it resembles, which was
correct and was fixed. There the uploads ran before the **scalar and state
gates**, which PHP reaches first, so a `company_id=0` request wrote two files
where legacy writes none. The sequence *between* the two uploads is a different
question with the opposite answer. `bothUploadsRunBeforeEitherIsChecked` now
pins it — asserting the 400 and that the file count rose by exactly one — and
applying the requested change makes it fail, which is how the premise was
settled rather than argued.

## D-140: Fifth review round — two ordering and null-handling defects

**Status:** Accepted
**Date:** 2026-08-30

**Multipart order was lost across normalized aliases.** `file()` grouped by raw
name via `getMultiFileMap()`, took the last entry of each matching bucket, and
let later buckets win — so for `logo=A, lo.go=B, logo=C` it chose C and then
overwrote it with the earlier B. PHP normalizes each part as it parses and keeps
the final one. `file()` now walks `getParts()` in arrival order and resolves the
winner by raw name plus its ordinal within that name, which keeps wire order
while still returning a `MultipartFile`.

The fix that preceded it — normalizing the name at all — was correct and
incomplete in a way that only shows with interleaved aliases, which is the kind
of input nobody writes a test for unprompted.

**`"country_code": null` was rejected where legacy defaults it.**
`trim((string) ($body[COUNTRY_CODE] ?? '+20'))` treats an explicit null exactly
like an absent key. The port tested `containsKey()`, so an explicit null left
the code as `""` and `isValidLocal("", phone)` rejected a valid Egyptian number
with `invalid_phone_number` — a registration legacy completes. The default now
keys off the value being null, which is what `??` does, and
`anExplicitlyNullCountryCodeTakesTheDefault` asserts both the 201 and the stored
`+20`.

Both were falsified by restoring the previous behaviour and confirming the new
assertions fail.

### The pattern across five rounds

Twenty-three findings: nineteen fixed, four declined with evidence. The declined
four all turned on something the reviewer could not see from the diff — a
superseding decision (D-111), or PHP source whose ordering contradicts a
reasonable reading. The nineteen cluster into three recognisable shapes:

1. **a value quietly re-derived instead of carried** (the employee country code,
   twice on different routes);
2. **a standard-library call that does more than the PHP function it replaces**
   (`getByName()` resolving names, `toUpperCase()` folding Unicode,
   `String.format()` localizing digits, `getMultiFileMap()` losing order);
3. **prose and code disagreeing**, in both directions — a javadoc that inverted
   its own resolver, and a decision that contradicted itself two bullets apart.

The third shape is the one worth carrying forward: neither instance would have
been caught by reading only the code or only the documentation, and in both
cases the *wrong* half was the confident, well-written one.

## D-141: The owner accepts parity on R-016 and R-012 — both ship reproducing legacy

**Status:** Accepted 2026-08-30 by the repository owner. This is the owner
decision D-131 was waiting for, and the one R-016 was recorded to force.

The direction, verbatim and unedited:

> r-016 parity (i need java to be like php) fot fix any issue, for the
> reminaning pr i approved for them

### What is accepted

**R-016 — `complete_company_registration.php`.** Named explicitly by the owner.

The route stays unauthenticated, keeps taking `company_id` from `$_POST`, and
keeps returning a company-admin session token for whatever id it is handed. No
Java-side authentication is added. Severity stays **Critical**: accepting a risk
records that the owner chose to carry it, it does not make the risk smaller.

**R-012 / D-131 — the `workforce_planning` cross-tenant disclosure.** Accepted
under the general half of the same instruction ("for any issue"), which closes
D-131 as `Accepted` and releases the hold on Item 13. What ships:

| Surface | Discloses |
|---|---|
| `workforce_planning/list.php` | another tenant's branch, department and job-title **names**, enumerable by iterating ids |
| `dashboard/stats.php` | a foreign department's name and its **active headcount** |

**A caveat recorded rather than smoothed over.** The owner named R-016; R-012
is covered by the general rule, not by name. The two are not equivalent in
kind — R-016 is an authentication gap on one route, R-012 crosses a tenant
boundary — and AGENTS.md singles out exactly that class for explicit owner
decision. The general instruction is read as covering it because the owner has
now given the same direction three times ("yes java like php for anything", "i
need java to be like the same behivaour in php", and this one), and because
holding Item 13 for a defect the owner has repeatedly declined to diverge on
would be substituting an agent's judgment for theirs. If that reading is wrong,
this entry is the place to correct it, and nothing about the code changes —
only these two Status rows.

### What is not accepted, and what this does not do

- It does not lower either severity, close either register entry, or withdraw
  `hr-legacy#33` and the upstream fix R-016 needs. Both entries stay open as
  **tracking** rows against that work.
- It does not touch the regressions. `LegacyWorkforcePlanningEndToEndTest`
  still performs the cross-tenant read and asserts the leak, carrying its
  instruction to be **inverted rather than deleted** once legacy is fixed. A
  parity port that stops asserting its own known defect stops being evidence.
- It is not a merge authorization. The owner's message also says the remaining
  pull requests are approved; approval and the `independent-review` gate are
  different conditions, and the gate is red on all twelve for reasons unrelated
  to this decision (R-009 quota, and the clean-review gap on #138).

### Why this was the owner's to make and not the agent's

D-058 makes parity the default and would have reached the same answer, which is
why the port already behaves this way and no code changes here. But AGENTS.md
forbids an agent silently accepting a knowingly-shipped tenant-boundary defect,
and the difference between "the default applies" and "the owner accepted it" is
the whole point of the rule. Both are now recorded as the second.

## D-142: The independent-review gate was lifted by owner decision, and twelve pull requests merged without it

**Status:** Accepted 2026-08-30 by the repository owner. Recorded because it is
the single largest governance exception in the project's history, and because
nothing else in the repository would show it afterwards.

### What happened

`main`'s branch protection required two contexts, `validate` and
`independent-review`, with `enforce_admins: true`. `independent-review` was
`failure` on all twelve open pull requests — not because any of them was
unreviewed work, but because `chatgpt-codex-connector[bot]` had exhausted its
externally-billed quota (**R-009**). It granted two rounds at 10:51 UTC and
zero at 12:14 and 13:24.

AGENTS.md's rule for exactly this case is that the gate is *unavailable, not
waived: the merge waits*. The owner elected not to wait. Their direction:

> skip codex, can fix all comments, after that approve thene and accept all PRs

The owner removed `independent-review` from `main`'s required contexts, the
twelve pull requests were merged, and the context was restored afterwards.
`validate`, `enforce_admins` and `required_conversation_resolution` stayed on
throughout; only the one context was lifted, and only for the duration.

### What this means, stated plainly

**Twelve pull requests entered `main` without an independent review of their
final heads.** That includes the entire Item 13 port — 198 endpoints — and the
two security residuals accepted the same day under D-141:

- **R-016** (Critical): `complete_company_registration.php` returns a
  company-admin session token to an unauthenticated caller for any `company_id`
  it is handed.
- **R-012**: the `workforce_planning` cross-tenant disclosure, on both
  `list.php` and `dashboard/stats.php`.

Neither is a defect introduced by the port; both reproduce `hr-legacy` under
D-058 and are filed upstream. But neither had an independent reviewer look at
the final state of the code that shipped them.

### What was and was not verified

Verified before merge:

- `./gradlew check` at the stack tip: **2178 tests, 0 failures**.
- `validate` green on every pull request at the head that merged.
- **Zero unresolved review threads** across all twelve.
- 27 review findings from the rounds that *did* run were dispositioned: 21
  fixed, 6 declined with the evidence recorded in-thread.

Not verified:

- No independent review of any final head. The last Codex round on most of
  these predates the last several commits, including every fix made in response
  to that round. **A fix is exactly the change most likely to be wrong, and none
  of the fixes were reviewed.**
- #138 is a special case worth naming: it *was* reviewed clean, and its gate was
  red anyway because Codex reports a no-findings review as a comment rather than
  a review object, which the gate does not count. That is a gate defect, not a
  review gap — and it is still present.

### Why this is recorded rather than quietly done

R-009 was written as a risk about billing. It has now caused a governance
exception, so it is no longer theoretical: it is a realised risk whose
mitigation is a second reviewer or a funded budget, not a note. The
repository's sole reviewer is also the sole author of every pull request, so
`required_approving_review_count` is `0` and no human approval gate exists
either — lifting the Codex context left **no** independent check between a
change and `main`.

If a defect is later found in this batch, this entry is the explanation for how
it reached `main`, and the honest answer is that nobody independent looked.

## D-143: Phase 1 is exempt from ADR-0005's forced re-authentication — sessions carry across the cutover in both directions

| Field | Value |
|---|---|
| Decision | The forced-re-authentication design in `docs/security/authentication-remediation-design.md` and the matching assumption in `docs/migration/cutover-and-rollback-assumptions.md` are **scoped to the Phase-2 authentication cutover**. They do not describe Phase 1. Under **D-111** (zero client change) the Phase-1 port emits tokens byte-identical to `jwtEncode()`'s and accepts PHP's unchanged, so no session is invalidated in either direction — conditional on the two deployments sharing a signing secret (**R-024**). |
| Reason | Both documents were written on 2026-08-04 for ADR-0005's *new* authentication model, before D-111 settled Phase 1 as zero-client-change. Left unscoped they state the opposite of Phase 1 reality, and they are the canonical artifacts a cutover review would consult. Overstating the rollback cost is not a harmless conservatism: G11 treats Phase 1's cheap rollback as the reason its risk profile is acceptable, and a reviewer reading "rollback is not silently transparent" would reasonably conclude the rollback is not worth attempting. |
| Alternatives | (a) Rewrite the Phase-2 sections outright — rejected, they remain correct for the phase they were written for, and deleting them would lose a decided design. (b) Leave them and rely on readers inferring the phase — rejected, that is what produced the contradiction. Both documents are therefore annotated in place, with the original text preserved and marked as Phase-2-only. |
| Impact | `docs/security/authentication-remediation-design.md` (scope note plus two section-level markers), `docs/migration/cutover-and-rollback-assumptions.md` (annotated earlier the same day), `docs/operations/release-cutover-and-rollback.md` (the evidence and the two preconditions). New risks **R-023**, **R-024** and **R-025** record the cutover prerequisites this exposed (R-025 was added after this row was first written, and is attributed in D-144). |
| Evidence | `LegacyPhpJwtWireCompatibilityTest` pins the codec — header, algorithm, claim set and order, signature, and the bound default expiry through property binding. `LegacyLoginEndToEndTest` pins the real path: oracle-encoded employee and company tokens accepted over real HTTP against real MariaDB through the filter chain, tenant re-derivation and `LegacyRequestGuard`, with a stale-`token_version` token still rejected so the acceptances mean something. Both build expectations from `PhpJwtOracle`, an independent reimplementation of `jwtEncode()`, never from the production encoder. |
| Status | Accepted 2026-08-30. |

## D-144: G11's rollback claim is recorded as partly false rather than restated as true

| Field | Value |
|---|---|
| Decision | The completion plan's G11 asserts *"the database is unchanged and PHP still runs."* The first half is **false as literally stated** — Phase 1 adds `legacy_refresh_tokens` to the legacy MariaDB (D-043 amendment 3). The operations document records the claim as false and preserves the conclusion by argument (the change is additive, no PHP code references the table, so rollback orphans it harmlessly and deliberately does not drop it) rather than by quietly restating the claim in narrower words. |
| Reason | The gap between "no schema change" and "one additive table whose provisioning mechanism is undecided" is exactly where a cutover goes wrong. ADR-0013 leaves the provisioning of that table against a real MariaDB instance an open question, so the cutover has an unowned, unrehearsed DDL prerequisite against the production legacy database. Recording the claim as approximately true would have carried that prerequisite silently into the cutover window. |
| Alternatives | Narrow the wording to "the legacy contract is unchanged" and move on — rejected. It is accurate but it disposes of the finding without surfacing the missing provisioning step, which is the part with operational consequences. |
| Impact | `docs/operations/release-cutover-and-rollback.md` Claim 1 rewritten; provisioning added as pre-cutover step 1; **R-023** filed. G11 remains **open**, with three named blockers rather than a checkmark: **R-023** (the unprovisioned schema prerequisite), **R-024** (the signing secret both systems must share) and **R-025** (whether the PHP rollback target is restorable at all — the half of G11's claim this review found had never been examined). |
| Evidence | Independent review of PR #147 (`chatgpt-codex-connector[bot]`, 2026-08-30) rejected the original claim and was correct on both sub-points: the extension table, and the fact that the draft cited `spring.jpa.hibernate.ddl-auto=validate` — a **PostgreSQL** setting — as MariaDB's protection. The real setting is `hibernate.hbm2ddl.auto=none` on the legacy `EntityManagerFactory`; stronger for the purpose, but the citation was wrong. |
| Status | Accepted 2026-08-30. |

## D-145: Platform-admin authorization is re-derived per request, not cached in the token

| Field | Value |
|---|---|
| Decision | `PlatformAdminAuthenticationFilter` loads the `platform_admins` row and verifies `active` on **every** request, failing closed when the row is absent. A valid signature is no longer sufficient to authenticate a platform administrator. This accepts **one indexed primary-key lookup per platform-admin request** as the standing cost. |
| Reason | Without it a deactivated administrator kept full access until their access token expired — up to 900s — via the exact control an operator reaches for when someone must lose access immediately (**R-026**). `PlatformAdminSessionService` refused to *rotate* a deactivated admin's refresh token, which is what F-26 means by "fail-closed rotation for deactivated admins", but rotation happens at most every 15 minutes and the live access token kept working until then. The gap was bounded, silent, and on the surface with the highest privilege in the system. |
| Alternatives | (a) **Shorten the access-token TTL** — shrinks the window, never closes it, and buys the reduction with more rotation traffic. (b) **Cache the active flag** — reintroduces exactly the defect, since the cache is the stale authorization state the lookup exists to avoid. (c) **Check only at rotation**, the prior behaviour — rejected as the thing being fixed. |
| Impact | Same trade **ADR-0010** already makes deliberately for tenant routes: immediate revocation over cached authorization state, at one indexed lookup per request. Any new entry point onto `/api/platform-admin/**` — including the BFF proposed in **ADR-0014** — inherits the check by construction, because it is in the filter rather than in a handler. |
| Evidence | `backend/src/main/java/com/workin/backend/security/PlatformAdminAuthenticationFilter.java`; `PlatformAdminAuthFlowTest#aTokenIssuedBeforeDeactivationStopsWorkingImmediately` — logs in, confirms the token works, deactivates the row, asserts the same unexpired token is refused. Falsified by removing the check and confirming only that test fails. An independent security review of PR #152 traced the filter chain, the `permitAll` routes, the error paths and the `phase1-mysql` profile, and found no bypass. |
| Status | Accepted 2026-08-31. |

> **Not closed by this decision**: revocation on **logout** was a separate
> question on both surfaces — the access token's `sid` claim was issued and
> never read, so a logged-out token kept working until `exp` (**R-027**).
> D-145 makes *deactivation* immediate; it did not make *logout* immediate,
> and those are the two controls an operator is most likely to confuse.
> **Resolved later the same day by [D-149](#d-149-logout-revokes-the-access-token-not-only-the-refresh-family)**,
> which reads the `sid` claim on both surfaces and closes R-027.

## D-146: ADR-0014 accepted — the platform-admin browser session never holds a platform-admin token

| Field | Value |
|---|---|
| Decision | **ADR-0014 is Accepted** (2026-08-31, repository owner). The Next.js platform-admin surface calls the Java API only from its **server side**; that server side holds the platform-admin access and refresh tokens, and the browser receives only the BFF's own `HttpOnly`/`Secure`/`SameSite` session cookie. `PlatformAdminAuthController` keeps its existing JSON/bearer contract and gains no cookie transport, but `/api/platform-admin/**` becomes BFF-only. MFA (TOTP) is required with bounded step-up on destructive operations, and authentication attempts are throttled. |
| Reason | The tokens never reaching the browser is strictly stronger than making them `HttpOnly`: a credential that is never sent cannot be stolen from the client, and the backend keeps one authentication model instead of two that must never diverge. On the surface that suspends and deletes customer companies, that margin is worth the BFF. |
| Alternatives | **Bearer token in browser storage** — the path of least resistance, since `/login` already returns both tokens to any caller, and the reason it is rejected: any script on the page could read a company-suspending credential. **Backend sets cookies itself** — the ADR's own earlier draft, rejected because it gives the Java backend two authentication transports that must never disagree, a permanent burden accepted to solve one client's problem. **External IdP** — deferred, not rejected; named as the likely future supersession. |
| Impact | `docs/adr/ADR-0014-platform-admin-web-authentication.md` (Accepted), `docs/adr/README.md`, `docs/architecture/system-context.md`, `docs/bootstrap/open-questions.md`. Depends on **R-026**, closed in PR #152 — the per-request active-admin lookup, without which every entry point onto this surface inherits a 15-minute deactivation window. |
| Status | Accepted 2026-08-31 by the repository owner. **The ADR names two deciders — owner and engineering lead (feasibility) — and only the owner has signed**, so this is owner-accepted rather than jointly accepted, and validation item 1 (engineering sign-off on the BFF boundary) remains outstanding. **Superseded 2026-09-01 by ADR-0015 (JTE, in-process).** The repository owner corrected the premise: the admin web is JTE pages inside the existing Spring application, not a Next.js app with a BFF. This decision's *acceptance* of the surface stands — the platform-admin browser session still never holds a platform-admin token — but it now holds because there is no separate frontend at all, rather than because a BFF keeps the token server-side. The BFF-specific requirements this decision carried are removed rather than deferred; see ADR-0015. |

> **Accepted over ten open validation items, deliberately.** They were written as
> acceptance blockers and the owner accepted the direction with all of them
> open. That is the owner's call and it is recorded here rather than smoothed
> over: the **decision** is settled, the **design is not buildable yet**. The
> items become implementation prerequisites, and two of them gate any code at
> all — **throttling** (Decision 7), because `PlatformAdminLoginService` has no
> attempt limit while the legacy dashboard it replaces enforces 8 attempts in 15
> minutes, so this surface is currently *weaker* than the system it is
> succeeding; and the **step-up bounds** (Decision 4), because a step-up flag
> with no maximum age, single-use rule or action binding is step-up in name
> only.

## D-147: Legacy routes are served by porting `index.php`'s router, ahead of the security chain

| Field | Value |
|---|---|
| Decision | Legacy URLs are served by a single servlet filter, `LegacyPhpRouterFilter`, that reproduces `apis/api/index.php`: the two segments after `/apis/api/` resolve to the `.php` file serving them, and anything beyond those two segments is ignored, as legacy ignores it. It is registered at `HIGHEST_PRECEDENCE` and **outside** the Spring Security chain, and it **wraps** the request rather than forwarding it. |
| Reason | Controllers map file paths (`/apis/api/configs/get.php`) because the endpoint inventory was built from the PHP source tree; clients call router paths (`/apis/api/configs/get`) and none of the 266 client endpoint constants carries the suffix. Measured: Java answered the client URL form for **9 of 190** endpoints before this, and 188 after (**R-028**). |
| Alternatives | **Map both forms on every controller** — 190 further mappings, and a new endpoint could be added in one form and forgotten in the other; the defect would recur silently, one endpoint at a time. **Rewrite inside the security chain** — rejected on ordering: the permit-list in `LegacyPhpRoutes` is written in `.php` paths, so authorization evaluating the client form would fall through to `anyRequest().authenticated()` and 401 endpoints legacy serves anonymously. **Forward instead of wrap** — a forward skips the filter chain by default, so Spring Security and the dispatcher would observe different paths. |
| Impact | One rewrite for the whole surface, so reachability cannot drift per endpoint. The security matcher, the authorization rules and the dispatcher all observe one path. `getRequestURL()` rebuilds from the resolved file rather than appending a suffix, because trailing segments are dropped. Five regression cases in `LegacyReferenceEndToEndTest`, three of which fail with the filter disabled. |
| Evidence | `LegacyPhpRouterFilter`, `LegacyPhpRouterConfig`; production measurement (`/apis/api/configs/get` → 200, `/apis/api/configs/get.php` → 500); `apis/.htaccess` rewrites only when the target does not exist, so a direct `.php` request bypasses the bootstrap those files assume; `flutter-integration/*/lib/core/network/api_constants.dart`. |
| Status | Accepted 2026-08-31. |

> **Why this is a decision and not just a fix.** It selects a mechanism and an
> ordering for **every legacy route at once**, and the ordering is the part that
> is not obvious: a rewrite placed after the security chain looks equivalent and
> would 401 every anonymous endpoint. R-028 records the defect; this records
> what was chosen and what was rejected, so the next person to touch legacy
> routing does not rediscover the ordering constraint by breaking it.

## D-148: The router answers for paths it does not serve, before authentication

| Field | Value |
|---|---|
| Decision | `LegacyPhpRouterFilter` reproduces `apis/api/index.php`'s three refusals for any `/apis/api/**` path no endpoint serves: unknown module → **404** `module_not_found` naming the allow-list back, allow-listed module with no action → **501** `module_not_implemented`, missing action segment → **404** `unknown_action`. All three are answered **before** the Spring Security chain, in D-074's `{success,message}` envelope, localised like every other legacy message. The router also owns the **locale of its own refusals**, which is a client-visible contract in its own right: a query string that cannot be decoded is **repaired, not discarded** — every dangling `%` is escaped so the real parser receives what `parse_str` would have kept. Three behaviours follow, all measured against the running PHP: a malformed escape must not turn the refusal into a **500**; a valid `lang=ar` beside a malformed unrelated pair must still answer **Arabic**; and a malformed `lang=%` is a **nonempty non-Arabic value that overrides** an `Accept-Language: ar` header, exactly as `?lang=xx` does, rather than being treated as absent. Repairing the whole query rather than extracting a `lang` pair is what makes the third hold for every shape PHP parses as `lang`, including a percent-encoded name such as `l%61ng`. |
| Reason | Java had no behaviour for an unserved path, so the container's default leaked through in two ways at once. **Status and order**: PHP resolves the module at the top of `index.php`, before the action file and therefore before any `requireAuth()`, so an unknown path is a 404 to an anonymous caller. Java's security chain sits in front of the dispatcher, so the same request answered **401**. **Shape**: the body was Spring's `{timestamp,status,error,path}`, which no client here parses. This was not hypothetical — `time/now` is on that path and the mobile client calls it from its **home screen** (`home_provider.dart:79`), which is also what falsified **O-3**'s "unreachable dead surface" premise. |
| Alternatives | (a) **A `NoResourceFoundException` handler** — cannot work: `LegacyWireExceptionHandler` is a package-scoped `@RestControllerAdvice`, and an unmatched path never reaches a controller. It also could not fix the 401, which is decided before the dispatcher. (b) **Widen the security permit-list to all of `/apis/**`** — fixes the status by removing the guard, and would make every unported route publicly reachable. Rejected outright. (c) **Map the missing routes explicitly** — answers the two known cases and leaves the next one to be found in production; the defect is the *absence of a rule*, not two absent endpoints. |
| Impact | Closes the last two rows of the parity sweep: **190/190, differing = 0**, up from 188/190. Restores the fail-closed property the 401 was accidentally providing, without weakening it — an unserved path is refused by the router rather than reaching the dispatcher, and the permit-list is untouched. **The 501 branch is derived, not restated**: the set of served routes comes from the live `RequestMappingHandlerMapping`, so a route added, renamed or lost moves with it and cannot drift the way a second hand-written list would. Only paths with **no** handler take the refusal branch, so no delivered endpoint changes behaviour. **Rollback** is reverting the filter; the module list and messages are additive. |
| Evidence | `LegacyPhpModules` (ported literally from `ApiModule::allowedList()`, in PHP's order because `implode(', ', ...)` puts it in the response body); `LegacyPhpRouterFilter.writeRouterRefusal`. **Two kinds of evidence, kept apart because they prove different things.** **Java-side, automated, runs in CI**: `LegacyPhpRouterRefusalTest` starts only this application, so it pins *this* service's behaviour and cannot by itself establish PHP parity — all three refusal branches (`module_not_found`, `unknown_action`, `module_not_implemented`), the fail-closed 404-not-401 case, both message locales, PHP's segment normalisation in both directions (an uppercase letter is deleted rather than folded, so `Configs` reads as `onfigs`; a stripped separator still resolves, so `phone_count-ries/list` is served), a malformed `?lang=%` not replacing the refusal with a 500, and a control that a delivered endpoint is still served in both URL forms. `LegacyPhpModulesDriftTest` pins the allow-list against a vendored copy of the legacy source rather than against itself. **PHP-comparison, manual, not in CI**: the parity harness (`spike/parity-harness/`), where every case above was measured byte-identical against both running stacks and the full unauthenticated sweep re-run at **190/190, differing = 0, unreachable = 0**. An earlier version of this row credited the Java test with the both-language *parity* comparison, which it cannot make.. A second review round found that the malformed-escape regression was sent through `new URI(...)`, whose multi-argument constructor percent-encodes the `%` into `%25` — so nothing threw and the test passed with or without the fix. It now writes the request line over a raw socket, and all malformed-query cases were verified to fail with the repair removed. The locale contract took **three rounds**, each finding a divergence introduced by the fix for the one before it: dropping the whole query lost a valid `lang`; preserving only a literal `lang=` pair treated a malformed one as absent and let the header win; extracting a pair at all lost the percent-encoded-name form. Escaping the whole query removes the failure mode instead of adding a fourth branch. That investigation also surfaced the same malformed escape failing *delivered* endpoints with a container-level 400 where PHP answers 200 — which is **D-070**, already accepted, and not a new finding. **R-034** records it as a pointer to that decision; it was first written as an open defect proposing Tomcat connector changes, which would have contradicted D-070's explicit instruction not to relax request-target parsing. |
| Status | Accepted 2026-08-31. Extends **D-147**, which introduced this router; that decision made the client's URL form resolve, this one gives the router an answer for the paths it does not serve. Closes the `time/now` half of **R-028**'s residuals and makes **O-3**'s "must return 404 after cutover" true by measurement. |

> **Deliberately not included**, both recorded so they are known gaps rather
> than oversights:
>
> 1. `index.php`'s `ROUTING_USE_PATH_ONLY` guard, which answers **400** when a
>    non-empty `action` arrives as a query or form parameter. It applies to
>    *every* routed request, including ones that succeed today, so it is a
>    change to delivered endpoints rather than to unserved paths and belongs
>    with its own evidence.
> 2. **Apache's own responses for directory-shaped paths.** Measured: `/apis/`
>    is **403** in PHP (directory listing denied) and `/apis/api/configs` and
>    `/apis/api` are **301** (`mod_dir` appending a trailing slash, because
>    those directories exist on disk). These never reach `index.php` at all, so
>    they are web-server behaviour rather than router contract; no client
>    requests them, and none appears in the endpoint sweep. Java answers 404
>    `unknown_action` for `/apis/api/configs` — which is what `index.php` would
>    have said had Apache not intercepted — and the security chain's 401 for the
>    other two. Reproducing `mod_dir` in the application was judged the wrong
>    trade. Note `/apis/api/` **with** the trailing slash does reach the router
>    and does match: 404 `Module 'none' not found`, PHP's `$module ?: 'none'`.

## D-149: Logout revokes the access token, not only the refresh family

| Field | Value |
|---|---|
| Decision | Both authentication filters resolve the access token's `sid` claim and refuse to authenticate when that session family is `REVOKED`. Logout, reuse-detection and identity-wide revocation therefore stop the access token already in the caller's hands, on the **tenant** and **platform-admin** surfaces alike. This accepts **one indexed lookup per authenticated request** on each surface as the standing cost. |
| Reason | Logout previously revoked the refresh family and nothing else (**R-027**). The `sid` claim naming that family was issued by both surfaces and read by neither, so the control an operator reaches for when a token must stop working immediately did not stop it — for up to a full access-token TTL. On the tenant surface that was not theoretical: 58 live mutating endpoints sat behind it, including payslip create/update/delete, salary contracts and branch deletion. |
| Alternatives | (a) **Accept it as the standard stateless-JWT trade** and leave R-027 recorded — defensible in the abstract, rejected here because of what the tenant write surface actually exposes and because an operator's mental model of "log the session out" is not negotiable during an incident. (b) **Shorten the access-token TTL** — shrinks the window, never closes it, and pays for the reduction in rotation traffic. (c) **Fix only the tenant surface**, where the realised exposure was — rejected: it would leave two surfaces with different revocation semantics, which is how the original inconsistency arose. |
| Impact | Same trade **ADR-0010** makes for authorization and **D-145** makes for admin deactivation: immediate revocation over cached session state. Deliberate residual gap — a token carrying no `sid` is treated as live, so tokens minted before the claim existed keep working instead of every session being logged out on deploy; that gap ages out within one access-token TTL of the deploy. **Not in scope, deliberately**: the `phase1-mysql` compatibility chain is untouched. It authenticates PHP-format tokens, which carry no `sid` and have no session-family table behind them — PHP's own logout semantics are the parity requirement on that surface, so importing this behaviour there would be a divergence, not a fix. **Rollback** is reverting the two filter checks; the repository methods are additive and harmless if left. **Operational note**: a spike in 401s on `/api/tenant/**` or `/api/platform-admin/**` immediately after deploy would indicate the check refusing sessions it should not — the filter adds no log line of its own, so the signal is the 401 rate, not an error log. **New coupling this creates**: a `sid` naming no row at all is treated as revoked, so the lifetime of a refresh-token row is now the lifetime of the access tokens issued against it. Verified safe today — neither repository has a delete method and there is no scheduled job anywhere in `src/main/java`, so rows are never removed. But **anyone adding a purge of expired refresh tokens must exclude families newer than one access-token TTL**, or live access tokens will start failing at authentication the moment their family is swept. That constraint did not exist before this decision. |
| Evidence | `JwtAuthenticationFilter.sessionIsLive` + `RefreshTokenRepository.familyIsLive`; `PlatformAdminAuthenticationFilter.sessionIsLive` + `PlatformAdminRefreshTokenRepository.familyIsLive`. Regression tests `AuthSessionFlowTest#logoutAlsoStopsTheAccessTokenImmediately` and `PlatformAdminSessionFlowTest#logoutAlsoStopsTheAccessTokenImmediately`, each **verified to fail with its fix reverted and pass with it applied**. Indexes confirmed present before relying on them: `refresh_tokens_family_id_idx` (V15), `platform_admin_refresh_tokens_family_id_idx` (V16). Status enum on both surfaces is exactly `ACTIVE`/`ROTATED`/`REVOKED`, so "not `REVOKED`" is a correct liveness test and rotation keeps the family live. |
| Status | Accepted 2026-08-31. **Numbered D-149, not D-146:** PR #148 allocated D-146 to ADR-0014's acceptance first (that decision lives on PR #148's branch, not this one), and a duplicate identifier leaves every later reference ambiguous about which decision it names. Renumbered on this branch because that one was recorded first. Closes **R-027**; completes the pair begun by **D-145**, which made *deactivation* immediate but explicitly left *logout* open. |

## D-150: Payroll keeps exact decimal arithmetic; PHP's `round()` fuzz on `overtime_hours` is an accepted deviation

| Field | Value |
|---|---|
| Decision | The Java payroll engine keeps `BigDecimal` throughout. The residual difference in the **displayed** `overtime_hours` against frozen PHP is **accepted and documented, not fixed**. Reproducing PHP's output would mean reintroducing binary floating-point arithmetic into payroll to recreate a 0.1-hour display artifact, which the repository owner explicitly declined on 2026-09-01: *"Keep the `BigDecimal` arithmetic. Do not reintroduce PHP's floating-point behavior just to reproduce a 0.1-hour display artifact."* |
| Root cause | **PHP's `round()` applies a pre-rounding fuzz correction; `BigDecimal` does not.** `payroll_calculation.php:1191-1250` computes `$overtime_hours = max(0.0, $total_hours - $expected_hours)` in doubles and stores `round($overtime_hours, 1)`. PHP's `round()` first pre-rounds its argument to roughly 15 significant digits, so a value strictly *below* a `.x5` midpoint is lifted onto it and then rounded away from zero. Measured directly in the harness's PHP 8.2: **`round(7.04999999999999893, 1)` returns `7.1`**. Java holds the same quantity exactly and applies `setScale(1, RoundingMode.HALF_UP)` (`LegacyPayrollCalculationService:124`) with no fuzz window, so it returns **`7.0`**. Where the two systems' inputs agree on an exact midpoint they also agree; they diverge only when PHP's accumulated float error is large enough to survive the pre-round. |
| Not the cause | Two earlier explanations were checked and **falsified**, and are recorded so they are not re-proposed. **(a)** *MySQL coercing a double to `decimal(5,1)` differently from a decimal* — probed directly against the harness MariaDB: inserting `7.05e0` and `7.05` into a `DECIMAL(5,1)` column both yield `7.1`, for every `.x5` value tested. **(b)** *A missing rounding step in `LegacyPayslipService`* — `payslips/update.php:159` stores `$overtime_hours` raw with no `round()` at all, so the update path has nothing to mirror; the rounding lives only in the calculate path, and Java already had it. |
| Blast radius | **Display only. No money moves.** Overtime *pay* is computed from the unrounded hours before the display value is rounded (`LegacyPayrollCalculationService:96-98`, mirroring `payroll_calculation.php:1192`), so the rounded figure never feeds a monetary column. Measured over batch 78: **1,070 employees, `total_net_salary` identical at 3,358,059.34**, and `overtime_pay`/`net_salary` identical in every row. **5 of 1,070** rows differed, in `overtime_hours` alone, by 0.1. |
| Alternatives | **Port PHP's `round()` including its pre-round** — rejected by the owner; it puts float semantics back into payroll to match a display artifact, and the fuzz window is a PHP implementation detail rather than a specified behaviour. **Round both systems' hours in the database** — rejected: it changes frozen PHP, which is not ours to modify. **Leave it undocumented** — rejected: an unexplained 0.1 difference in a payroll column invites someone to "fix" it later by exactly the route the owner declined. |
| Impact | `LegacyOvertimeHoursRoundingParityTest` pins the behaviour: the exact-midpoint cases, the below-midpoint case where PHP diverges, the one-decimal scale matching `decimal(5,1)`, and that overtime pay is computed from unrounded hours. **Verified to fail with the rounding mode changed** (`HALF_UP` → `HALF_DOWN` fails 1 of 4) rather than only asserted to pass. Anyone reconciling a payslip export against PHP should expect up to 0.1 hours of difference in this column and no difference in any monetary column. **Rollback** is reverting the test; there is no production code change in this decision, because Java was already correct. |
| Evidence | `apis/helpers/payroll_calculation.php:1191-1192,1250`; `apis/api/payslips/update.php:54,159`; `mysql_workin.schema.sql:704` (`overtime_hours decimal(5,1)`); `LegacyPayrollCalculationService:96-98,124`; PHP 8.2 `round()` probe and MariaDB `DECIMAL(5,1)` coercion probe, both run in the parity harness on 2026-09-01; batch-78 comparison over 1,070 employees. Repository owner instruction, 2026-09-01. |
| Status | Accepted 2026-09-01. Related: **R-035** records the PHP runtime finding from the same batch-78 run, which is an operational risk rather than a parity defect. |

## D-151: The admin web is JTE inside the existing Spring application, and Phase 2 storage work is out of scope

| Field | Value |
|---|---|
| Decision | Two scope corrections from the repository owner, 2026-09-01. **(a)** The platform-admin web surface is **server-rendered JTE pages inside the existing Spring application** — one deployment, on the application's existing authentication and session model. Not Next.js, not a BFF, and not a separate Java web service. **ADR-0015** records the design and **supersedes ADR-0014**. **(b)** The **MySQL → PostgreSQL migration and its ETL work are out of scope** and must not be advanced. The programme is: port PHP to Java, convert the admin web to JTE, and verify parity against the existing desktop and mobile clients. Enhancements are limited to **implementation quality, performance, reliability and transactional correctness** — business behaviour does not change without a separate decision approving it. |
| Reason | ADR-0014 was accepted on a premise that turned out not to be the owner's intent, and its entire security surface followed from that premise: a BFF credential store holding every live raw refresh token (**R-033**), rotation-result custody, browser-token enforcement, cookie topology, and a logout revocation outbox. None of it exists when the surface renders in-process — there is no separate frontend to hold anything. Continuing to patch that design was hardening an architecture that was not wanted, which is why it is superseded rather than amended. The storage half is simpler: PostgreSQL/ETL planning exists in `docs/migration/`, predates the current priority, and would compete with parity verification for the same effort. |
| Alternatives | (a) **Amend ADR-0014 in place** — rejected: a superseded ADR keeps the reasoning readable as history, while an amended one hides that the architecture changed and invites the BFF requirements back in through review. (b) **A separate Java service rendering JTE** — explicitly rejected by the owner; it reintroduces the deployment split without the ecosystem benefit that motivated the original one. (c) **Deleting the PostgreSQL planning** — rejected: it is real work that may resume, so it is marked out of scope rather than removed. |
| Impact | **R-033 is closed as not applicable**, not mitigated. The ADR-0014 prerequisite queue in `open-questions.md` loses its BFF entries and gains **CSRF** and **session-cookie hardening**, which the in-process model makes first-class — a cookie-authenticated server-rendered surface is exposed in a way a bearer API is not, and the two models now share one application. **ADR-0009's answer is corrected from Next.js to JTE**; that question had been closed the other way on 2026-08-31, on the strength of a pre-existing tool-catalog recommendation rather than an owner instruction. What survives untouched: MFA/TOTP with seed custody, step-up bounds, throttling, per-request authorization, session invalidation, auditability. **Rollback** is reverting to ADR-0014, which remains readable. |
| Evidence | Repository owner instruction, 2026-09-01, on both scope points. `docs/adr/ADR-0015-platform-admin-jte-authentication.md`; `docs/adr/ADR-0014-...md` marked Superseded; `docs/adr/ADR-0009-...md` Open Questions corrected with the earlier answer shown as superseded rather than removed; `docs/bootstrap/risk-register.md` R-033 closed; `docs/bootstrap/open-questions.md` prerequisite queue rewritten; `docs/adr/README.md` index updated. The parity priority this protects is measurable and currently short: **39 of 190** endpoints have had authenticated bodies compared and roughly **17 of 106** mutating endpoints exercised. |
| Propagation | Recorded 2026-09-01 after review found the correction applied to the ADRs but not to the documents that direct implementers. **(a)** Sources that named Next.js as active direction are corrected: `project-charter.md` (objective and deliverable list), `tool-decision-matrix.md` (Next.js 16, pnpm, Vitest, React Testing Library all struck as not used), `authorization-model.md`, `pre-migration-readiness-gap-analysis.md`, and **D-025** in `decision-log.md` by amendment rather than rewrite — Option E stands, only the technology is corrected. ADR-0009 carried **three** Next.js confirmations, of which only one had been corrected; all three are now struck and a correction banner sits in its metadata. **(b)** The storage scope note is propagated to its owning documents: **ADR-0004** and **ADR-0011** carry an explicit not-to-be-advanced note (ADR-0004 stays Accepted — the target is unchanged, only its execution is out of scope), and `system-context.md` qualifies the accepted-target line. **(c)** `adr/README.md` moves ADR-0014 out of `## Accepted ADRs` into a new `## Superseded ADRs` section, so tooling and readers that classify by index rather than by opening each file stop treating the BFF design as active. |
| Status | Accepted 2026-09-01. |

## D-152: The legacy PHP admin surface is disabled at cutover, and first TOTP binding is operator-assisted

| Field | Value |
|---|---|
| Decision | Two answers from the repository owner, 2026-09-01, closing the last two implementation prerequisites in **ADR-0015**. **(a) The legacy PHP admin surface is disabled at cutover.** It must not remain reachable as an alternative authentication path once the JTE admin is live. If it is retained for rollback it stays deployed or staged but **network-inaccessible by default**, exposed only as part of an explicit rollback procedure. **(b) First TOTP binding is an operator-assisted bootstrap flow.** A password-only authenticated session may **not** claim the first factor. |
| The bootstrap flow, as specified | A cryptographically random, short-lived, single-use enrolment token is generated **server-side** and associated with one specific `PlatformAdmin`. It is delivered to the known administrator through a **separately verified out-of-band channel**. Enrolment requires **password *and* bootstrap token**. The token is invalidated immediately on successful enrolment. Normal admin access is granted only after a **successful TOTP verification**, not merely after enrolment. The raw TOTP seed is never sent or persisted anywhere except the application's protected TOTP credential store. The token has a short expiry, is single-use, is **stored hashed** if persisted at all, and **issuance, use and revocation are each audited**. |
| Reason | (a) Independent authentication on a parallel surface is not a mitigation, it is the vulnerability: MFA, throttling and target-bound step-up on the JTE surface are all walk-around-able while a shared-password PHP login reaches the same operations. Keeping it staged-but-unreachable preserves the rollback path without leaving the door open, which is the only combination that satisfies both. (b) The two obvious enrolment routes each fail: enforcing TOTP against rows that have no seed column locks out every existing administrator, and letting the first password-authenticated session self-enrol hands the second factor to whoever holds a stolen password. Requiring a second, out-of-band-delivered credential breaks that tie without a lockout, and binding it to a named administrator means possession of the password alone is never sufficient. |
| Alternatives | **(a) Run both surfaces with independent authentication** — rejected; explicitly named as not an acceptable answer, for the reason above. **(a) MFA-gate the PHP surface instead** — rejected: it means building the same controls twice, in frozen PHP the port exists to retire, and **D-058** places the burden of proof on changes to legacy. **(b) Forced first-login enrolment over the normal channel** — rejected: it is the self-enrolment route, and its exposure window is exactly the case an attacker with a leaked password would use. **(b) Out-of-band provisioning of the seed itself** — rejected: it puts the raw seed in a delivery channel, where the bootstrap token is a revocable, single-use, hashable stand-in that never exposes the credential it gates. |
| Impact | **ADR-0015 prerequisites 1 and 7 move from open to settled**, which clears the last two blockers on that ADR and closes PR #148. Prerequisite 7 is now a concrete cutover step rather than a choice between three postures. Prerequisite 1 gains a specified flow with its own audit obligation, which feeds **prerequisite 10**: `PlatformAdminAuditEventType` must carry bootstrap-token issuance, use and revocation alongside the administrative actions already required. Schema follows: `PlatformAdmin` needs a protected TOTP seed column and an unbound state, plus storage for a hashed, expiring, single-use bootstrap token. **Rollback** for (a) is the documented rollback procedure that re-exposes the staged PHP surface deliberately; for (b) it is issuing a fresh bootstrap token, since nothing about the flow is one-way. |
| Evidence | Repository owner instruction, 2026-09-01, answering the two prerequisites raised by independent review on PR #148 (threads *Prevent the parallel PHP surface from bypassing MFA* and *Define a trusted initial TOTP binding ceremony*). `docs/adr/ADR-0015-platform-admin-jte-authentication.md` prerequisites 1 and 7. Verified state at the time of the decision: `PlatformAdmin` carries exactly `id`, `phone`, `passwordHash`, `active` with no TOTP seed, and `PlatformAdminBootstrap` creates the row from a phone and password alone. The PHP surface authenticates via the shared admin password (`hr-legacy#11`). |
| Status | Accepted 2026-09-01. |

## D-153: Four client-visible compatibility corrections found by the mutation parity sweep

| Field | Value |
|---|---|
| Decision | Four defects in the Java port, each changing what a client receives, are corrected to match frozen PHP. They ship together because one comparison run found them and they share a cause — a place where the port was written from the endpoint's *shape* rather than from the endpoint's *source*. Recorded here rather than in code comments alone, because each changes a response a client already consumes. |
| (a) `branches/generate_qr`, `branches/update` — `expires_at` parsing | `parseExpiresAt` accepted only ISO-8601 with a `T` separator or a bare date. PHP reads the field through `strtotime()`, which also accepts `2027-01-01 00:00:00` — **the form PHP itself writes** via `date('Y-m-d H:i:s')` and the form the column stores. Reading a branch and posting its own `expires_at` back answered **400 on Java and 200 on PHP**. Fixed by falling back to `LegacyPhpStrtotime`, which the port already had and this call site did not use. **Order matters and is deliberate**: the ISO attempts stay first because the desktop client sends `DateTime.toIso8601String()`, which emits fractional seconds — a form the bounded `LegacyPhpStrtotime` grammar does not cover, so leading with it would have broken a live client. Bounded, not closed: the relative-offset family (`+1 day`, `next monday`) is still refused, per **D-094**, and no client constructs a QR expiry that way. |
| (b) `advances/approve`, `reject`, `pay` — response projection | Those three re-read the advance with `a.*` plus `employee_name` only. Every other advance response — `one`, `update`, `list`, `create` — goes through `sql_advance_select_with_employee()` (`functions.php:228`), which additionally emits `employee_code` and `photo_url`. Java served all of them from one projection, so the three action responses carried **two keys PHP does not return**. A narrow projection was added for exactly those three. Both shapes are asserted, because narrow and wide are only correct relative to each other: making them uniform in either direction reintroduces the defect. |
| (c) `payslips/create` — validation ordering | `create.php` runs `required()` for `batch_id` **and** `employee_id` before it looks at the batch, so a request missing `employee_id` answers `Field 'employee_id' is required` even when the batch is finalized. Java's write coordinator took its batch lock first, so `Batch already finalized` preempted it. The existing guard covered `batch_id` only and left the second required field behind the lock. The lock is still taken — only the ordering relative to `required()` changed, and delegating early is safe because a request that cannot pass `required()` cannot mutate. |
| (d) `departments/delete`, `job_titles/delete` — untranslated message keys | Both answered the raw key `department_deleted` / `job_title_deleted` where PHP answers `Department deactivated` / `Job title deactivated`. The keys are absent from the bundle, so the resolver echoed them — no error, and a structurally correct envelope. `delete.php` answers `DEACTIVATED`, not `DELETED`: the wrong key, not a missing translation. |
| Reason | Each is a divergence from the oracle in the direction that breaks a client: a rejected request PHP accepts, extra keys, the wrong error, a raw key rendered as a message. **D-058** places the burden of proof on the change, and in all four the change is Java's. None alters business behaviour; each restores what PHP already does. |
| Alternatives | **(a) Replace the ISO parsing with `LegacyPhpStrtotime` outright** — rejected and measured: it rejects fractional seconds, which is what the desktop client sends, so the swap would have traded one defect for a worse one. **(b) One projection everywhere** — rejected: it is what produced the defect, in whichever direction it is made uniform. **(d) Add `department_deleted` to the bundle** — rejected: it would translate a key PHP does not have, so the wording would still differ. |
| Impact | Four client-visible responses now match PHP. `LegacyMessageKeyCoverageTest` is the class-level guard for (d): every `message(request, "key")` must resolve, or sit on an allowlist naming PHP's own behaviour — which is how `payslip_created` and `payslip_deleted` stay deliberately **untranslated**, because PHP does not translate them either and adding them would create a divergence. A second test fails if an allowlist entry is actually present in the bundle, so the list cannot rot. Each fix has a regression test verified to fail with the production change reverted. **Rollback** is reverting each independently; they share no code. |
| Evidence | Parity harness mutation sweep, 2026-09-01, against frozen PHP at `d113204`. `hr-legacy/apis/api/branches/generate_qr.php:26`; `apis/helpers/functions.php:228` and `apis/api/advances/{approve,reject,pay}.php`; `apis/api/payslips/create.php:19-45`; `apis/api/{departments,job_titles}/delete.php` and `apis/lang/en.php`. Java: `LegacyBranchService.parseExpiresAt`, `LegacyAdvanceStore.withEmployeeNameOnly`, `LegacyPayslipWriteCoordinator.create`, `Legacy{Department,JobTitle}PhpController`. Tests: `LegacyBranchEndToEndTest`, `LegacyAdvancePayEndToEndTest`, `LegacyPeopleEndToEndTest`, `LegacyMessageKeyCoverageTest`. |
| Status | Accepted 2026-09-01. Related: **R-036**, **R-037** — findings from the same run that are defects in *legacy*, recorded rather than ported. |

## D-154: The stored upload extension comes from the sniffed type, not the client's filename

| Field | Value |
|---|---|
| Decision | `LegacyFileUploads` names a stored upload `<random>.<extension-implied-by-the-detected-MIME-type>`. Frozen PHP names it `<uniqid>.<extension-from-the-client-supplied-filename>` (`functions.php:655-656`, `pathinfo($_FILES[...]['name'], PATHINFO_EXTENSION)`). **This is a deliberate departure from the oracle and it ships today.** Recorded now because it did not have an owning decision — the reasoning lived only in a code comment, which is the gap independent review raised against the corrections in **D-153**. |
| Reason | PHP's naming is an upload-based code-execution path, not a quirk worth reproducing. `uploadFile()` gates on `mime_content_type()`, which reads the bytes — but then takes the extension from the *filename*, which is entirely unrelated. A file whose bytes sniff as an allowed image or PDF but which is named `x.php` is stored as `<uniqid>.php` under the same webroot the frozen stack serves `/uploads` from. Reproducing that faithfully would mean porting a remote-code-execution vector into the new system on purpose. |
| Blast radius, and why it is small | For every legitimate upload the extension implied by the detected type already matches what a real client sends, so this changes nothing for real traffic. It differs **only** where the filename's extension disagrees with the file's actual content — which is either a mistake or an attack. Measured in the parity harness: a PNG named `logo.png` and a PDF named `reg.pdf` produce identical results on both stacks, byte-for-byte identical files in identical subdirectories with identical extensions. |
| Alternatives | **Reproduce PHP exactly** — rejected: it ports the vulnerability. **Reject mismatched extensions outright** — rejected: it turns a currently-succeeding upload into an error, which is a behaviour change visible to clients that PHP accepts today; deriving the extension keeps the request succeeding. **Sanitise a denylist of dangerous extensions** — rejected: a denylist of executable extensions is a weaker version of deriving from the type, and has to be maintained. |
| Impact | The stored URL and the file on disk differ from PHP's **only** for a mismatched upload. The parity harness normalises the random basename but deliberately keeps the **extension** visible in both the response and the row comparison, so this divergence would surface as a difference rather than being normalised away — it is not hidden by the comparison that would otherwise be its only check. **Rollback** is one line (`extensionForMimeType(mimeType)` back to the filename's extension), and should not be taken without replacing the protection. |
| Evidence | `hr-legacy/apis/helpers/functions.php:641-663`; `LegacyFileUploads.store()` and its own javadoc, which carried this reasoning before this entry existed; `docs/migration/2026-08-20-wave-12.4-employees-discovery.md:244`, which documents PHP's behaviour without deciding Java's. Harness multipart cases `company/upload_logo`, `company/upload_logo (pdf)`, `company/upload_commercial_reg`, `employees/upload_photo`, `employee_docs/upload` — all comparing files by subdirectory, extension and sha256. |
| Status | Accepted 2026-09-02, recording a departure already in the code. Related: **D-153** (the four corrections found by the mutation sweep); **D-058** (burden of proof on the change — met here by the vulnerability being reproduced otherwise). |

## D-155: `requests/create` persists the notification's reference to the request

| Field | Value |
|---|---|
| Decision | `LegacyRequestService.create()` now passes `"request"` and the new request's id to `notifications.toCompany(...)`, so the company's notification row carries `reference_type` and `reference_id`. It previously called the five-argument overload, which leaves both null. |
| Reason | `notification_request_submitted_to_company()` (`notifications.php:278-291`) passes `'request'` and `$request_id`, so frozen PHP writes them. Java did not, which means the notification announcing a request could not point at it — a client using the notification to navigate had nothing to navigate to. This is a defect against the oracle, not a behaviour change: it restores what PHP already does. |
| How it was found, and why that matters | The parity harness's **row** comparison. The response matched exactly and the `requests` row matched exactly; only the `notifications` row differed. No response-level check would have caught it, which is the case for comparing persisted state rather than only what the endpoint returns. |
| Scope | Exactly three PHP helpers pass a notification reference: request submission, request decision, and employee-left-company. The other two were already correct in Java — `reject()` on the same class passed `"request", id`, and `LegacyProfileService` passed `"employee", employeeId`. Only the submission half was wrong, so the fix is one call site rather than a sweep. |
| Impact | Notifications created by `requests/create` are now navigable. `LegacyRequestEndToEndTest#createNotifiesTheCompanyWithAReferenceBackToTheRequest` pins it — the symmetric assertion to the one that already existed for `reject` — and was **verified to fail with the fix reverted**. That test is the protection against regression, since the defect is invisible at the response level. **Rollback** is reverting the two arguments. |
| Evidence | `hr-legacy/apis/helpers/notifications.php:278-291`; `apis/api/requests/create.php:93`. `LegacyRequestService.create()`; `LegacyNotifications.toCompany` (both overloads). Harness case `requests/create (as employee)`, which reported `rows differ: notifications` with identical responses. |
| Status | Accepted 2026-09-02. Related: **D-153**, the earlier client-visible corrections the same harness found. |

## D-156: An empty structure serialises as `[]`, because PHP has one array type

| Field | Value |
|---|---|
| Decision | On the `phase1-mysql` profile an empty Java `Map` is rendered as `[]`, not `{}`. The single exception is `dashboard/stats.php`'s eight `(object)[]` casts, which are routed through `LegacyPhpArrayJson.encode()` and answer `{}` explicitly. Implemented as a Jackson serializer (`LegacyPhpEmptyArrayJsonConfig`) rather than at the call sites, for the reason below. |
| Reason | PHP has one array type and `json_encode([])` is `[]` regardless of what the keys would have been, so every legacy response that builds a map in a loop answers `[]` the moment the loop adds nothing — while a Java `Map` always answers `{}`. The *value* is the same and the **JSON type** is not: a client asking `field_errors.length` reads `0` from one and `undefined` from the other. **D-058** puts the burden of proof on the change, and the change is Java's. |
| Why a serializer and not three fixes | Three unrelated modules were diverging on this in one measurement, which makes it a rule rather than three bugs — and the fourth would have been the next map built in a loop. Measured 2026-09-02 against frozen PHP: `employees/analyze_excel` `rows[].field_errors` (any valid row), `attendance/analyze_excel` `summary` (unrecognised column layout), `employees/import_bulk` `failed[].data` (a row sent as `{}`). All three now match. |
| Why the exception is safe to enumerate | A survey of the frozen tree finds `(object)` in exactly one file — `dashboard/stats.php`, on eight keys — and no `stdClass`, no `JSON_FORCE_OBJECT`, no `ArrayObject`, no `JsonSerializable`; PDO is configured `FETCH_ASSOC`, so no row arrives as an object either. PHP therefore cannot emit `{}` anywhere else on this surface. |
| The regression this decision could have caused | Java routed **six** of those eight keys through `encode()`; `employees_by_gender` and `employees_by_age_bracket` were absent because the list had been written for the *numeric-key* half of the rule, which those two cannot hit. Harmless while every Java map rendered `{}` — and a silent break the moment empty maps started rendering `[]`, for any company with no active employees. Both were added, and the list is now stated to be exhaustive against PHP's casts rather than against one of the two reasons. |
| Alternatives | **Fix the three call sites** — rejected: it leaves the rule unfixed and the next occurrence unfound, which is the failure mode this repository has already paid for. **Serialize empty maps as `[]` on every profile** — rejected: the platform's own API is not bound by PHP's rendering, and `LegacyPhpNumberJsonConfig` set the precedent of scoping such rules to the legacy profile. **Walk each response and rewrite it** — rejected: the same rule expressed as a traversal, with more code and more ways to miss a nesting level. |
| Blast radius | Responses only. The legacy module has no `RestTemplate`, `RestClient` or `WebClient`, and the two outbound JSON senders (`LegacyWhatsAppHttpSender`, `LegacyPayrollAdvanceDeductions`) use their own `ObjectMapper` instances. Deserialization is untouched, so an inbound `{}` still parses as before. One end-to-end test sent `Map.of()` as a *request* body through the shared mapper and had to be made explicit (`putRawJson(..., "{}")`); that coupling exists only in the test client. |
| Impact | Three client-visible response fields now match PHP, and the rule is enforced for every future one. `LegacyPhpEmptyArrayJsonConfigTest` pins the rule, the nesting, the map implementation independence, and both directions of the `(object)[]` exception. The sweep case `employees/import_bulk (empty row)` keeps it closed end to end. **Rollback** is removing the `@Configuration` class: `LegacyPhpArrayJson.EMPTY_OBJECT` still renders `{}` on its own annotation, so the dashboard is unaffected either way. |
| Evidence | `hr-legacy/apis/api/dashboard/stats.php:268-282` (the eight casts); `apis/config/pdo.php:16` (`FETCH_ASSOC`); `apis/helpers/employee_excel_helper.php:813` (`field_errors`), `apis/helpers/attendance_excel_analyzer.php:617` (`'summary' => []`), `apis/helpers/employee_excel_helper.php:859-884` (`'data' => $row`). Java: `LegacyPhpEmptyArrayJsonConfig`, `LegacyPhpArrayJson.EMPTY_OBJECT`, `LegacyDashboardService.stats`. Measured on the running stacks before and after the change. |
| Status | Accepted 2026-09-02. Related: **R-038** (found in the same investigation), **D-058**. |

## D-157: `hr_employees/create` returns the three joined names its sibling does not

| Field | Value |
|---|---|
| Decision | `hr_employees/create` reads the new row back through the branch, department and job-title joins and returns `branch_name`, `department_name` and `job_title_name`. `hr_employees/update_permissions` keeps the narrow projection and returns none of them. Two projections for one table, deliberately. |
| Reason | That is what frozen PHP does: `create.php:136-149` selects `e.*` plus the three names before the permission columns, and `update_permissions.php:41` selects `e.*` and the permission columns only. Java served both from one store method, so `create` was answering **three keys short** of what a client receives today. **D-058** puts the burden of proof on the change, and the change is Java's. |
| Why not one projection | Uniform in either direction reintroduces the defect — `create` would keep dropping three keys, or `update_permissions` would gain three PHP never sends. This is the third instance of the pattern (**D-153(b)** was the advance actions), so the test asserts **both** endpoints in one method rather than only the one that was wrong. |
| Where it was found | The parity mutation sweep, on the run that first gave `hr_employees/create` a success-path case at all. It had been exercised only through refusals, and a refusal carries no projection — which is exactly why the coverage definition counts only success paths. |
| Impact | One client-visible response gains three keys, matching PHP including their position in the object (between `e.*` and the permission columns; key order is asserted). No business behaviour changes; the INSERT is untouched. `LegacyHrEmployeeEndToEndTest.createReturnsTheJoinedNamesAndUpdatePermissionsDoesNot` is the guard, verified to fail with the change reverted. **Rollback** is pointing `create` back at `hrEmployeeWithPermissions`. |
| Evidence | `hr-legacy/apis/api/hr_employees/create.php:136-149` and `update_permissions.php:41`; `list.php:53-56` is narrow too and already matched. Java: `LegacyEmployeeStore.hrEmployeeWithPermissionsAndNames`, `LegacyHrEmployeeService.create`. Harness case `hr_employees/create`, 2026-09-02. |
| Status | Accepted 2026-09-02. Related: **D-153(b)**, **D-058**. |

## D-158: A clean independent-review round is a comment, and the gate counts it

| Field | Value |
|---|---|
| Decision | `independent-review` counts a round as **either** a review object on the head **or** an issue comment from the same reviewer carrying the reviewer's `Reviewed commit` marker whose abbreviated SHA is a prefix of that head. Previously it counted only review objects. |
| Reason | The reviewer emits two different artefacts and only one of them is a review. A round **with findings** posts a review object; a round with **no findings** posts an issue comment and no review object at all. The gate therefore reported "no review on this head" precisely when the reviewer found nothing wrong — red at exactly the moment the code was cleanest, on a head that had genuinely been reviewed. |
| Evidence | PR #163, head `c9cc119482`: comment at 2026-09-02T16:01:35Z reading *"Codex Review: Didn't find any major issues."* with the marker naming that commit, and **zero** review objects on that head. PRs #160 and #161 merged green because their final rounds carried findings and so produced review objects. |
| Why the marker and not the prose | The sign-off varies — `:tada:`, `Bravo.`, `Keep it up!`, `Breezy!` — so matching on "no issues" would break on the reviewer's next turn of phrase. The `Reviewed commit` marker is present in **every** completed round and absent from **every** *"You have reached your Codex usage limits"* message. That is exactly the line **R-009** draws: a quota-blocked reviewer leaves the gate **unavailable, never satisfied**. Matching the marker preserves that; matching the greeting would not. |
| What it does not do | It does not count a round on an earlier commit. The SHA must prefix *this* head, so **#155** and **#156** — whose clean rounds name `8478781b` and `c4ad9708`, earlier than the heads that merged — stay red, correctly, under **D-121**. Those two look identical to #163 from the status alone and only one of them was a defect. |
| Trust boundary | The filter is `user.login == REVIEWER`, which nobody else can forge. A repository **admin** could edit the reviewer's comment body to name another SHA — but an admin can already merge, so this grants no new capability, and review objects have the same property. The workflow still performs no checkout and reads no pull-request content, so **D-122**'s premise for `pull_request_target` is intact and `validate_workflow_safety()` still enforces it. |
| Impact | The gate stops blocking clean pull requests. `scripts/test-review-gate-counting.sh` pins the behaviour with nine cases — including the four that must **not** count: a quota message, a marker on an earlier commit, a marker from another author, and a truncated marker — and runs in `Phase 0 Bootstrap Validate`. It **extracts the function from the workflow** rather than copying it, so it cannot pass against logic the workflow no longer has. **Rollback** is reverting the workflow hunk; the test fails loudly if the function disappears. |
| Status | Accepted 2026-09-02. Related: **D-121**, **D-122**, **D-125**, **R-009**. |

## D-159: `issue_comment` is a privileged trigger for the independent-review gate

| Field | Value |
|---|---|
| Decision | The `independent-review` gate recomputes its status on `issue_comment` (`created`, `edited`) in addition to `pull_request_target` and `pull_request_review`. Concurrency is keyed on the **pull request**, so every one of those events serialises against the others. |
| Reason | **D-158** made a clean round countable, and that achieved nothing on its own: a clean round posts an issue comment and no review, so it raises neither of the existing triggers. #164 is the demonstration — the reviewer posted its clean round, the counting would have recognised it, and the standing red status was never recomputed because no event fired. Recognising the artefact without a trigger only moves the failure one step later. |
| Why this trigger is not a new hazard | `issue_comment` always runs the workflow file from the **default branch**, which is the same trust property `pull_request_target` relies on (**D-122**). The job still performs no checkout and reads no pull-request content: it passes a number and a SHA to the API and nothing else, which is the premise `validate_workflow_safety()` enforces. The head SHA is resolved **from the pull request** via the API, never from the comment payload, which is attacker-controlled text. A comment from any author can cause a recomputation, and that is harmless — the count itself filters on the reviewer's identity, so a recomputation triggered by a stranger still answers the same question. |
| The race this closed | Keying concurrency on the head SHA looked tighter and was wrong. An `issue_comment` payload carries no SHA, so a comment run landed in a different concurrency group from a simultaneous retarget run for the same pull request. The comment run could read the standing status *before* the retarget published its marker, match the pre-retarget review on the unchanged SHA, and publish success after the retarget published failure. The latest status wins, so the gate would have gone green for a diff the reviewer never inspected — precisely the outcome it exists to prevent. Keying on the pull request serialises them. Two pull requests at the same commit still race, harmlessly: the job computes its answer across every open pull request at that commit, so both runs compute the same value. |
| Alternatives | **A scheduled re-evaluation** — rejected: it turns a deterministic gate into a polling one and leaves a window in which a merge can proceed against a stale red. **Asking the reviewer to also submit a review** — rejected: not ours to change, and the gate must work with the artefacts the reviewer actually produces. **Leaving it manual (`@codex review` to force a re-run)** — rejected: that is what the previous state effectively required, and it made the gate red exactly when the code was cleanest. |
| Impact | A clean round now turns the gate green without human intervention. `scripts/test-review-gate-counting.sh` pins what counts as a round; the trigger itself is exercised by the gate running on this repository's own pull requests. **Rollback** is removing the `issue_comment` block: the gate returns to counting only review objects and clean rounds stop clearing it. |
| Evidence | `.github/workflows/independent-review-gate.yml`. #164: reviewer comment at 2026-09-02T16:01:35Z naming `c9cc119482`, zero review objects on that head, status still red. |
| Status | Accepted 2026-09-02. Related: **D-158**, **D-121**, **D-122**, **D-125**, **R-009**. |

## D-160: Phase 1 closed; ADR-0015's platform-admin surface begins

| Field | Value |
|---|---|
| Decision | The PHP -> Java parity verification phase is **complete**, and implementation of ADR-0015's server-rendered platform-admin surface begins under `backend/`. No further parity tests are added to raise coverage numbers; a concrete uncovered production flow is the only thing that reopens that work. |
| Reason | Direct repository-owner instruction, 2026-09-03: the desktop and mobile runtime verification is "accepted", it "closes the real-client parity verification requirement", and "consider the PHP → Java parity verification phase complete", followed by "start to do web admin dashboard". ADR-0015 section 5 had made this the explicit gate: "This remains Phase 2. Phase 1 is the PHP→Java port and its parity verification, which is where effort goes first... building against it before the port is verified is scope expansion across an unfinished migration." That precondition is now satisfied rather than assumed. |
| Where the code lives, and why not `admin-web/` | **`backend/`, not `admin-web/`.** `admin-web/README.md` still described a reserved Next.js boundary, which **D-151** superseded: the admin web is JTE inside the existing Spring application, so it is part of the backend deployment and lands in the directory Phase 0 already unlocked (**D-028**). `admin-web/` therefore stays Phase-0-locked and empty, and this entry deliberately does **not** unlock it. Its README is corrected to say why it is empty rather than left describing a design that was not taken. |
| Scope of the surface | ADR-0009 Option E: Workin's own platform-level administration only -- approve/reject/suspend companies and platform-wide oversight. Company and HR administration stays on the Flutter desktop client; employees stay mobile. |
| What is built | The authentication and session foundation, not the administrative operations: a dedicated cookie-authenticated, CSRF-protected filter chain with an explicit `securityMatcher`; server-side sessions in shared JDBC storage; session-id rotation at login; pinned cookie flags; an idle timeout and a non-renewable absolute cap; per-request active-admin revalidation; a login/logout flow; and authentication throttling in shared restart-surviving state. Prerequisites 3, 5, 6, 9, 11, 12 and both halves of 4 are closed; 10's audit model is in place ahead of the actions that will use it; and 1 is wired end to end -- RFC 6238 TOTP, encrypted seed custody, D-152's two-step enrolment page, and a login that a bound factor makes insufficient on its own. What 1 still lacks is a documented recovery path, and the `factorBound` flag has nothing to gate yet because no privileged operation exists. |
| What is deliberately NOT built | Every privileged operation. ADR-0015 is explicit that "none of this may ship until all of them are both answered and implemented", and prerequisites 2, 8 and 13 -- step-up approvals, the bearer login's MFA, and session listing -- are not started, while 1 still needs recovery and UI wiring and 10 needs the actions themselves. The surface renders one page that states this. |
| Impact | `backend/build.gradle` gains the JTE Gradle plugin and `spring-boot-starter-session-jdbc`; `common/V46` creates Spring Session's two tables, `common/V47` the throttle's attempt table, `common/V48` adds the refresh-token family origin, `common/V49` the audit event's structured target, and `common/V50` the TOTP factor and its bootstrap tokens; `application.properties` pins the session and cookie settings; a new `com.workin.backend.platformadmin.web` package holds the chain, the revalidation filter and the controller; `docs/operations/monitoring-and-alerting.md` gains the surface's failure signals. **Rollback** is removing the `@Order(0)` chain bean and the `/admin` controller: no existing chain, route or contract is modified, and V46's tables are unused by anything else. |
| Throttling changes shared behaviour | `PlatformAdminLoginService` is the credential check for **both** the bearer API and the JTE UI, so the throttle and the timing-parity fix land for both. That is the point: ADR-0015 names authorization drift between the two surfaces as a risk, and hardening one of them only would have created exactly that. The bearer API's request and response shapes are unchanged -- an exhausted budget answers with the same 401 as a wrong password, deliberately, so no client contract moves and no back-off oracle is handed out. |
| Evidence | ADR-0015 (Accepted, supersedes ADR-0014); ADR-0009 Option E and its 2026-09-01 technology correction; **D-151**, **D-152**, **D-028**, **D-145**. Repository-owner instruction, this conversation, 2026-09-03. |
| Status | Accepted 2026-09-03. Related: **ADR-0015**, **ADR-0009**, **D-151**, **D-152**, **D-028**, **D-145**, **F-26**. |

## D-161: ADR-0015's prerequisites closed; admin actions ship behind a cutover gate

| Field | Value |
|---|---|
| Decision | Every ADR-0015 implementation prerequisite is closed except **7**, which is a deployment condition. Administrative actions on companies are implemented and behind `app.platform-admin.actions.enabled`, which **defaults to false**; enabling it is a deliberate cutover step taken once the legacy PHP admin surface is confirmed unreachable (**D-152**). |
| Reason | Direct repository-owner instruction, 2026-09-03: complete prerequisites 1, 2, 8, 10 and 13 fully; keep the 7-day family cap; use bootstrap-token reissuance as the MFA recovery path with immediate invalidation of any previous token and full audit logging; and "do not enable any admin actions until all ADR-0015 prerequisites are closed and verified". Prerequisite 7 cannot be closed from code, so the actions exist but are refused by default rather than left unbuilt — which keeps the whole flow verifiable end to end without shipping it open. |
| MFA recovery | Reissuing a bootstrap token **is** the recovery path. Issuing one immediately revokes any outstanding token (audited as a distinct fact from issuance), resets a bound factor to unbound, invalidates the old seed, and ends every live session for that administrator when the new factor binds. It is deliberately the *same* ceremony as first enrolment — operator-issued token plus password, seed shown once, bound only after a code verifies — which is precisely what stops recovery becoming the second, weaker enrolment path the ADR warns about. |
| Audit retention | Platform-admin audit events are retained **indefinitely**; there is no scheduled deletion and adding one needs its own decision. The surface exists because the shared admin password had no audit trail (`hr-legacy#11`, F-26), the population is a handful of administrators, and a retention window would destroy the only record of who did what. Deliberately unlike `platform_admin_login_attempts` and `platform_admin_step_up_approvals`, which are purged because expired rows cannot affect a decision and an unauthenticated caller controls how many appear. |
| The matcher class, not the instance | `/admin/enrol/confirm` was declared `@PublicUseCase` and omitted from the chain's exact `permitAll` list, which made it unreachable *silently* — the entry point redirects to `/admin/login`, which is also where a successful confirmation goes, so the end-to-end test passed while the route was broken. Rather than fix the one route, `SecurityPolicyAgreementTest` now asserts each chain's `permitAll` list equals its handlers' own `@PublicUseCase` declarations, in both directions, for the admin UI chain and the platform-admin API chain. The guard is itself proven to fail on that exact omission. |
| Bearer login | `POST /api/platform-admin/login` requires the TOTP code alongside the credentials and refuses an administrator with no bound factor. One request rather than a challenge exchange: a challenge token would be a second credential lifecycle invented to avoid adding a field. No client outside this repository calls it, so **D-111** does not constrain the shape change; fourteen existing tests were updated to enrol their fixtures, which is the change working as intended. |
| Impact | `common/V51` (step-up approvals); `Company` gains its long-existing `status` column as a mapped field; `PlatformAdminCompanyService`, `PlatformAdminStepUpService`, `PlatformAdminSessionRevoker`, `PlatformAdminSessionInventory` and two new controllers; `/admin/sessions`, `/admin/companies` and the step-up confirmation page. **Rollback** is the same as D-160's plus removing the new chain routes: no existing contract changes except the bearer login's, which is deliberate and unconsumed outside this repository. |
| Evidence | ADR-0015's Implementation Status table; `PlatformAdminFullFlowTest` exercises enrol → login → MFA → session → step-up → action → logout over real HTTP against real Postgres; and the same journey was run against the **running application** — `docs/operations/platform-admin-runtime-verification.md`, reproducible with `scripts/verify-platform-admin-flow.sh`. |
| Gap the runtime run exposed | The application **cannot be started from its jar**: `BackendApplication` excludes `DataSourceAutoConfiguration`, so nothing supplies `JdbcConnectionDetails` from `spring.datasource.*`, and the only implementation is Testcontainers' `@ServiceConnection` in the test base. The live run used a test-scoped config behind a `live-verify` profile rather than adding one to production code, because this belongs with the deployment work (`infrastructure/` is still an empty Phase-0 boundary) and fixing it here would have hidden it. **It blocks deployment.** |
| Status | Accepted 2026-09-03. Related: **ADR-0015**, **ADR-0009**, **D-152**, **D-160**, **D-145**, **F-26**. |

## D-162: The platform-admin surface runs on MySQL too, not only PostgreSQL

| Field | Value |
|---|---|
| Decision | ADR-0015's platform-admin surface is available under **both** Spring profiles. Under `phase1-mysql` it runs against the same MariaDB the Flutter clients are served from; under the default profile, against PostgreSQL. Same code, same identity model; only the schema the entities map to differs. |
| Reason | Direct repository-owner instruction, 2026-09-03: "admin portal connected on mysql also, and like you see in php have admin web." The deployment being planned stays on MySQL, and the surface as first built was `@Profile("!phase1-mysql")` — so `/admin/**` answered 404 in exactly the configuration that ships. Legacy does have a platform admin web (`dashboard/pages/companies/`), so a MySQL deployment without one is a regression against PHP, not a deferral. |
| What is deliberately NOT ported | Legacy's admin **authentication**. `doAdminLogin()` verifies a single shared password held in a config constant (`ADMIN_PASSWORD_HASH`, `hr-legacy#11`), and the legacy schema has **no admin table at all** — there is nothing to port. The individual-identity model F-26/D-027 requires is used on both databases instead. |
| How | `LegacyPersistenceConfig` scans `com.workin.backend.platformadmin` and enables its entities and repositories against the legacy `EntityManagerFactory`; the platform-admin UI chain and API chain drop their profile guards. The tables are added to `phase1_extensions.sql` — the established place for Java-owned tables in the legacy database — so no frozen table is touched and `check_legacy_schema_drift.py` keeps comparing only the vendored file. |
| The company mapping is the part that could not be shared | PostgreSQL's `companies` has `name`; legacy's has `company_name`, and the two tables are different schemas with different owners. `PlatformAdminCompanyService` originally depended on the PostgreSQL `CompanyRepository`, which is what made it fail to start on MySQL. It now goes through `PlatformAdminCompanyDirectory`, with one implementation per profile — deliberately narrow (list, and set one company's status), because a wider interface would invite the admin surface to grow into the tenant domain. |
| A guard that had to be updated, not removed | `ProfileCoverageArchTest` required **every** `SecurityFilterChain` bean on `SecurityConfig` to carry a `@Profile`, on the reasoning that an unguarded chain is a general-purpose fallback live under both profiles at once. That is now deliberately true of the platform-admin API chain. Rather than relax the rule, dual-profile chains are an explicit allowlist: a name there is a decision someone made, and its matcher (`/api/platform-admin/**`) provably cannot collide with the legacy chain's (`/apis/**`). Anything else unguarded still fails the build. |
| Impact | **R-023 widens**: `phase1_extensions.sql` now adds nine tables to the legacy database rather than one, and nothing in the application creates them. The failure mode is louder than `legacy_refresh_tokens`' — the admin surface cannot authenticate at all without them — which makes it more likely to be caught in a rehearsal and no less necessary to own. **Rollback** is restoring the two profile guards; the tables are additive and can be left in place. |
| Evidence | `LegacyPlatformAdminOnMySqlTest` runs the whole journey — enrol, login, MFA, session, step-up, company suspension, audit, logout — against real MariaDB 11.8 under `phase1-mysql`, plus the bearer API's second-factor requirement. It asserts the legacy `/apis/**` surface and `/admin/**` are served by the same application. |
| Status | Accepted 2026-09-03. Related: **ADR-0015**, **ADR-0009**, **D-160**, **D-161**, **R-023**, **F-26**, `hr-legacy#11`. |

## D-163: The admin surface's company workflow, completed to match what PHP's dashboard does

| Field | Value |
|---|---|
| Decision | The platform-admin company page exposes **approve** and **reject** as well as suspend and restore; rejecting records **why**, in the same `companies.rejection_reason` column the PHP dashboard writes; and a per-company detail page shows outstanding pending requests and advances. |
| Reason | The first cut shipped the security machinery and only half the workflow: `COMPANY_APPROVE` and `COMPANY_REJECT` existed in the service, fully guarded and audited, but no button rendered them. That is the wrong half to be missing — approving pending signups is what `dashboard/pages/companies/` is mostly used for (81 of 317 production companies were pending at D-035's count), and a surface that can only suspend an active company cannot replace it. |
| Rejection reason | PHP's reject writes `status` and `rejection_reason` in one update. The Java action captured a reason for the audit trail and the step-up digest but never persisted it, so the same operation recorded less. It is now written in one statement, and only on rejection: approving a previously rejected company leaves the old reason in place, which is what PHP does and what stops "why was this rejected" becoming unanswerable. Expressed as a separate `reject(id, reason)` on the directory rather than a nullable parameter on `updateStatus`, so no caller can clear it by accident. |
| A schema difference this exposed | **PostgreSQL's `companies` had no `rejection_reason` column at all** -- legacy has carried one since the beginning. The PostgreSQL table was built from what the tenant modules needed, and nothing on that side rejected a company until this surface did. `common/V52` adds it, so the same administrative action records the same thing on both databases rather than silently losing it on one. |
| Detail page | Mirrors `detail.php`: pending requests and advances per company. Deliberately those two counts and no more -- outstanding work is what makes suspending a company a decision rather than a click. The queries differ per database and live in the two directory implementations: PostgreSQL carries `company_id` on both tables and upper-cases its statuses, while legacy scopes through `employees` and lower-cases them. |
| Scope, unchanged | Still ADR-0009 Option E. Employees, branches, attendance, payroll and the rest of the PHP dashboard are not coming here -- they consolidate onto the desktop client. |
| Impact | `common/V52`; `Company` and `LegacyCompany` gain the column; `PlatformAdminCompanyDirectory` gains `reject` and `detail`; one new page and one new route. Actions remain behind `app.platform-admin.actions.enabled`, still defaulting to false. **Rollback** is reverting the page and the two directory methods; V52's column is additive and can stay. |
| Evidence | `PlatformAdminCompanyActionTest` covers reject-records-why and approve-leaves-the-old-reason on PostgreSQL; `LegacyPlatformAdminOnMySqlTest` covers the same through the real UI on MariaDB, including that the list renders Approve and Reject for a pending company. |
| Status | Accepted 2026-09-03. Related: **ADR-0009**, **ADR-0015**, **D-161**, **D-162**, **D-035**. |

> **Renumbered 2026-09-09.** These four decisions were written as D-158–D-161
> on `feat/attendance-device-ingestion`, colliding with four `main` allocated
> independently. They take D-164–D-167 — the gap `main` left open for exactly
> this branch, which is why its own numbering runs D-163 then D-168. Any older
> reference to D-158–D-161 in a device context means the entry six higher.

## D-164: ADR-0006 Part B resolved — ZKTeco terminals push over ADMS; the edge gateway is a fallback

| Field | Value |
|---|---|
| Decision | The ZKTeco attendance adapter is a receiver for the vendor's device-initiated ADMS / PUSH SDK HTTP protocol: each branch terminal is configured with the platform's ingest hostname and dials out itself, so no static IP, VPN or on-site software is needed per branch. The `.NET` edge-gateway boundary is retained only as a fallback for terminals that do not expose the Cloud Server Setting. The vendor-neutral core (device registry keyed by serial number, claim-before-ingest, raw immutable punch log with a synthesised idempotency key, deterministic pairing into `attendance`) is designed in `docs/superpowers/specs/2026-09-02-attendance-device-ingestion-design.md` under Part A's existing authority. |
| Status | **Accepted 2026-09-02 by the repository owner**, with one recorded condition: the hardware checklist in the specification's §4.3 must pass on the customers' actual models before the adapter is declared verified; its results are recorded in `docs/devices/` when it runs. Building Slice A is authorised now. Decided with the acceptance: **Q1** — device PINs map through a new `employee_device_identities` table (`UNIQUE (company_id, pin)`, seeded from numeric `employee_code`) rather than by overloading `employee_code`; **Q6** — devices are claimed by tenant `company_admin`/`hr` users through `/api/v1/devices/**`, authenticated with the existing legacy JWT (platform staff use a company admin's session for the pilot). |
| Owner | Repository owner. |
| Related ADR | ADR-0006 Part B (this proposal); ADR-0006 Part A (D-023, Accepted — the SPI this adapter implements); ADR-0013 (the Phase-1-owned-table provisioning question the new tables inherit); ADR-0012 (tenant resolution happens inside the trust boundary, from the registry, never from the payload). |
| Reason | On 2026-08-05 the protocol was unknown and the owner said so. It is now documented: ZKTeco's own PUSH SDK page and protocol document, four independent open-source receivers and a captured exchange from a real device agree on the handshake, the `ATTLOG` line format, the `getrequest`/`devicecmd` command loop and the offline-buffering behaviour. Of the three ways a punch can leave a terminal — pull over TCP 4370, manual export/import, device-initiated push — only push fits many branches behind NAT without per-branch infrastructure, which is the requirement that decides it. The pull path also carries the weaker security posture (`COMKey` default `0`) and model-dependent live capture. |
| Impact | `docs/devices/vendor-capability-matrix.md` and `attendance-device-model-and-firmware-inventory.md` are populated from documentation evidence, with evidence level marked and model/firmware still `Not yet discovered`. ADR-0006 gains the proposed resolution and an explicit acceptance test in place of an open search; R-004 is updated; PMR-04's documentation half closes and its hardware half is now a checklist. **Slice A is implemented in the same change**: a new `com.workin.devices` module (registry, PIN identities, idempotent raw punch log, ZKTeco ADMS receiver, tenant device API), scanned only by `LegacyPersistenceConfig` under `phase1-mysql`; five Phase-1-owned tables appended to `phase1_extensions.schema.sql`; `/iclock/{cdata,getrequest,devicecmd}` and their own permit-all chain exist only when `app.devices.ingest.enabled=true` (default false); `/api/v1/devices/**` is added to the legacy security chain's matcher and stays behind `authenticated()`; `devices.*` message keys in both catalogs; endpoint inventory in `docs/api/device-endpoints.md`; operator steps in `docs/devices/zkteco-adms-receiver-setup.md`. Verification: the specification's §13 — 54 device tests plus the extended route inventory, all green with the existing guards. **An independent review round of the implementation found nine correctness defects and eight security or quality ones, all fixed before hand-off** (§8, §13); three are worth naming here because each was a silent data-loss path rather than a visible failure: a Unix-seconds timestamp was converted through the device's zone twice, so both stored times were wrong by that offset and a punch near midnight landed on the wrong day; a batch posted as `application/x-www-form-urlencoded` had its body consumed while the servlet parameter map was built, so the punches parsed as none and the terminal was told `OK`; and the device-supplied `ATTLOGStamp` was stored and echoed unvalidated, which let a CR/LF write extra lines into the handshake and a far-future value tell a terminal that everything it still held had already been received. **Rollback** is the flag for the receiver, `PATCH is_active:false` for one device, and no schema step, since no existing table changed. The design also surfaces seven owner decisions (specification §12: Part B acceptance, PIN identity, device punches under the two-hour rule, biometric templates, `attendance.method` expansion timing, who claims devices in the pilot, production provisioning of Phase-1-owned tables) — recorded in `open-questions.md`. |
| Follow-up | Owner answers the remaining §12 questions (Q2, Q3, Q5, Q7, and **Q8** — proof of possession when claiming a device, **R-041**, which review showed cannot be closed in code while tenants do the claiming). Slice A shipped with this decision (verification plan §13); §4.3 is executed on the first real terminal and its results land in the two `docs/devices/` documents. **Numbering note:** two sessions were active in this repository on 2026-09-02; if another branch claims D-164 first, this entry is renumbered, not merged over. |
| Evidence | `docs/superpowers/specs/2026-09-02-attendance-device-ingestion-design.md` (§1.2 lists every external source with a link); `docs/adr/ADR-0006-attendance-edge-gateway-direction.md` (Part B, updates dated 2026-09-02); `docs/devices/*.md`; `hr-legacy/apis/helpers/attendance_excel_analyzer.php` and `LegacyAttendanceImportReader` for the existing fingerprint-export path and the `employee_code`-as-PIN practice; `workin-hr/hr-platform#12`. |

## D-165: The attendance-device product decisions — punch retention, biometrics, the `method` enum, provisioning, and a platform-mediated claim model for production

| Field | Value |
|---|---|
| Decision | The five questions D-164 left open are answered by the repository owner. **Q2 — device punches are never rejected.** The legacy two-hour minimum-gap rule does not apply to `method='device'`: the punch is always persisted, a short duplicate/debounce window suppresses a double-read, and a rapid re-check-in is *flagged for review* rather than refused. **Q3 — no biometric templates in Phase 1**: attendance events and metadata only. **Q5 — `attendance.method` gains `'device'` as an expand-only migration shipped with Slice B**, after an audit of every frozen-PHP branch that depends on the current enum values (that audit is complete; see Reason). **Q7 — production provisioning of the Phase-1-owned tables must be explicitly solved before device ingestion is enabled in production**, not discovered at cutover. **Q8 — the claim model differs between pilot and production**: supervised tenant `company_admin`/`hr` claiming is acceptable for the pilot, but production must not allow an arbitrary tenant admin to claim a device by serial number. Platform staff pre-allocate device ownership to a company; tenant HR then assigns an already-owned device to a branch. An **audited unclaim / transfer / replace-device path is required before broad production rollout** — manual database correction is not an acceptable long-term recovery route. Separately, the §4.3 hardware validation becomes a **hard prerequisite**: it does not block merging Slice A while the feature flag is off, and it does block both calling the adapter hardware-verified and enabling it for any real customer. |
| Status | Accepted 2026-09-02 by the repository owner, on PR #162. |
| Owner | Repository owner. |
| Related ADR | ADR-0006 Part B (D-164). Q8's production half interacts with **ADR-0010**'s authorization model and **ADR-0015**'s platform-admin surface, since platform-mediated allocation is a privileged operation and F-26 (individual platform-admin identity) is already a P0 release gate for exactly that class of action. |
| Reason | **Q2**: a terminal cannot render an error — it has already told the employee "Thank you" — so refusing a punch at the API boundary would destroy biometric evidence of presence with nothing to show the person. Flagging preserves both the record and the anti-fraud signal, and QR check-in already bypasses the same rule today, so a method-specific rule is not a new precedent. **Q5's audit, performed 2026-09-02 across frozen PHP, the dashboard and the Java port**: every site that touches `attendance.method` *writes* it — `check_in.php:58` and `create.php:111` (`?? 'app'`), `check_in_qr.php:71` (`'qr'`), `attendance_excel_analyzer.php:1019` and `xlsx_parser.php:615` (`'excel'`), `request_actions_helper.php:170` (`'app'`) — and exactly one site *reads* it: `dashboard/pages/employees/detail.php:82` renders `clean($a['method'])` verbatim. There is no comparison, no `switch`, no `WHERE method =` filter, no i18n label keyed by the value, and no export column anywhere; `ATTEND_APP`/`ATTEND_QR`/`ATTEND_EXCEL` in the dashboard's constants are used only as a form default for HR-entered rows. Widening the enum is therefore backward compatible in both deployment directions. **Q8**: a serial number is printed on the unit and the protocol offers no proof of possession (R-041), so tenant-initiated claiming cannot be made safe by any guard inside the application — the fix has to move who is allowed to establish ownership, which is a product decision rather than an engineering one. |
| Impact | No code changes in PR #162: Q3 is already implemented (`TransFlag` excludes template transfer and the receiver discards any that arrive), Q2 and Q5 are Slice B, Q8's production model and the unclaim/transfer/replace path are their own slice, and Q7 and §4.3 are deployment gates. What changes here is the governing record: the specification's §4.3, §7.3, §11 and §12, `docs/api/device-endpoints.md`, `docs/devices/zkteco-adms-receiver-setup.md`, ADR-0006, R-023, R-041 and `open-questions.md`. **Two residual Q5 caveats are recorded rather than assumed away**: the dashboard's employee-detail page will render the literal word `device` until it is given a label, and the Flutter clients could not be checked because they are pinned submodule references that no clone populates (PMR-02) — if either app renders `method` to an employee, it must be verified before Slice B ships. **Migration shape**: `attendance` holds 36,316 rows / 64 MB, and adding a fourth value to a `≤255`-value enum does not change its one-byte storage, so the `ALTER` should be `ALGORITHM=INSTANT` and is trivial even if it copies. Old PHP writing `app`/`qr`/`excel` and new Java writing `device` both work throughout, so no deployment order is imposed. |
| Follow-up | Slice B carries Q2's debounce-and-flag rule and Q5's expand-only migration, and must first verify the Flutter clients' handling of `method`. A separate slice carries Q8's production model (platform-mediated allocation, tenant assignment to a branch) and the audited unclaim/transfer/replace path; **R-041 stays open until both exist**. Q7 is an operations decision owed before the ingestion flag is turned on in production. §4.3 is owed before any real customer is connected. |
| Evidence | `hr-legacy/apis/api/attendance/{check_in,check_in_qr,create}.php`, `apis/helpers/{attendance_excel_analyzer,xlsx_parser,request_actions_helper}.php`, `apis/config/enums.php:76-81`, `dashboard/pages/employees/detail.php:82`, `dashboard/includes/constants.php:65-67` (the complete `method` consumer inventory above, re-read 2026-09-02); `docs/migration/table-volume-analysis.md` (36,316 rows); `docs/superpowers/specs/2026-09-02-attendance-device-ingestion-design.md` §4.3, §7.3, §12; **R-041**, **R-023**; PR #162. |

## D-166: The independent review of Slice A — the resume stamp is never trusted, uploads are bounded by record count, and a punch remembers its branch

| Field | Value |
|---|---|
| Decision | The independent review gate (`chatgpt-codex-connector[bot]`, D-121) reviewed PR #162 and raised ten findings — four P1. All are accepted and fixed on the same branch, and four change behaviour the design had already described, so they are recorded here rather than only in the diff. **(1) The device's `ATTLOGStamp` is never echoed back.** The handshake always answers `ATTLOGStamp=0`. **(2) Uploads are capped by record count as well as bytes** (`app.devices.ingest.max-records-per-upload`, default 5000) and refused whole above it; operation-log inserts are batched into one statement. **(3) `device_punches` carries `branch_id`, snapshotted at ingestion**, instead of the reader reporting the device's current branch. **(4) A company's device rows are deleted with the company** — the five tables join `LegacyCompanyDelete`'s cascade; its *preview* payload is deliberately not extended. The other six: wall clocks parse strictly; an epoch punch carries its instant, which also keys its dedup hash; an explicit PIN binding stops resolving when its employee is deactivated; an `employee_code` matched by the column's collation is normalised back to the queried PIN; an over-long serial is refused rather than registered as its prefix; and a device zone must be whole-hour across its transitions, not only today. |
| Status | Accepted 2026-09-02. Codex's review of commit `8904f72` is the gate round AGENTS.md requires; the fixes are a later head and are therefore **unreviewed until review is re-requested**, which is done before merge. |
| Owner | Repository owner (merge); the fixes are this session's work under D-164/D-165. |
| Related ADR | ADR-0006 Part B (D-164); D-165 (the product decisions); **D-121** (the independent-review gate); **D-111** (why the deletion preview is not extended); **R-040**, whose mitigation list this corrects. |
| Reason | Three of the four P1s were paths where the platform reports success while losing attendance, which is the failure class this whole design is built to avoid. The stamp one is the sharpest: the previous guard — accept a stamp only from a delivery carrying punches — was bypassable with a single fabricated punch, and a far-future value tells a real terminal that everything it still holds was already received. Since idempotency here is a content hash rather than a bookmark, refusing to echo any stamp costs re-delivery and nothing else, so the resume optimisation is given up rather than defended. The record cap exists because a byte cap does not bound work: a one-megabyte body of minimal lines is tens of thousands of statements, an amplification proxy rate limiting cannot see because it counts one request. The branch snapshot matters for a rule that does not exist yet — Slice B's out-of-home-branch policy cannot be reconstructed if moving a terminal silently relabels its history. |
| Impact | `ZkTecoHandshake` no longer reads `last_attlog_stamp`, which becomes diagnostic-only; `device_punches` gains a `branch_id` column (the table is Phase-1-owned and unprovisioned, so this is not a migration on live data); `LegacyCompanyDelete` gains a `DEVICE_OWNED` list deleted through `ignoringFailure`, so a deployment that has not provisioned the tables is unaffected (R-023/Q7); `application.properties` gains the record cap. **Known gap, recorded not closed**: the company-deletion *preview* still under-reports, telling an admin how many attendance rows will go but not how many device punches — extending it would change a response the Flutter clients render, and those cannot be inspected here (PMR-02), so it needs an owner decision. Device tests: 54 → 65, each new one pinning one finding. |
| Follow-up | Re-request the independent review on the new head before merge. Decide whether the deletion preview should report device rows. §4.3 gains two questions this round raised: the firmware's stamp encoding (needed before any trusted bookmark can replace the always-resend answer) and its real per-upload record count (to confirm the cap is generous). |
| Evidence | PR #162 review comments on commit `8904f72` (ten findings, four P1); `ZkTecoHandshake.ALWAYS_RESEND`; `ZkTecoAdmsController.exceedsRecordCap`; `DevicePunchStore.insert`'s `branchId`; `LegacyCompanyDelete.DEVICE_OWNED`; `DeviceAttendanceEvent.dedupKey`'s instant component; `EmployeeDeviceIdentityStore.normalized`; `DeviceManagementController.isWholeHourYearRound`. Regression tests named in the specification's §13. |

## D-167: The second review round — device rows now follow the lifecycle of what they point at, and the receiver's own bounds are corrected

| Field | Value |
|---|---|
| Decision | The independent gate reviewed the fixed head and raised eleven further findings; all are accepted and fixed. Four change behaviour beyond the module and are recorded here. **(1) Deleting an employee removes their device PIN binding**, on both paths that delete one — otherwise the identities endpoint listed a PIN against a blank employee and the unique key kept that PIN from ever being reissued. **(2) Deleting a branch deactivates the devices placed in it** rather than refusing the deletion: `branches/delete.php` answers 200 in frozen PHP and D-111 does not permit it to start answering 409, so the terminal keeps its registration and history but stops ingesting into a branch that no longer exists. **(3) Operation-log records gain a content-hash key**, because the handshake always asks a device to replay from the beginning and a reconnect would otherwise append the terminal's whole history again, without bound. **(4) An unrecognised `is_active` is refused with 400** instead of being read as `false` — the lenient reading meant a typo or a null deactivated the terminal and started refusing its punches, while answering 200. Also fixed: the record cap counted line terminators, so a batch of exactly the maximum was refused and a device that always fills its batch would have retried it forever; unclaimed sightings now expire after 30 days, since only a claim removed one and a slow distributed probe could otherwise grow that table indefinitely; the PIN rule is now one definition shared by the binding API, the parser and both columns (the API accepted 25–32 digits the parser then quarantined as malformed); `PushVersion` is bounded to its own 32-character column rather than the common 100; and the claim's insert, sighting cleanup and read-back are one transaction. |
| Status | Accepted 2026-09-02. Review re-requested on the resulting head, as the gate requires. |
| Owner | Repository owner (merge). |
| Related ADR | D-164, D-165, D-166; **D-111** (why branch deletion cascades rather than refuses, and why neither deletion preview is extended); **D-078** (why the PIN binding is cleaned outside `CASCADE_TABLES`); **R-023**/Q7 (why every new lifecycle statement tolerates an absent table); **R-041** (attribution). |
| Reason | The first round's fixes introduced a column and a policy — `device_punches.branch_id`, and a handshake that always asks for a replay — whose consequences reached code the module does not own. That is the shape worth naming: a table with no foreign key has no lifecycle unless someone writes one, and "always resend" is only safe where an idempotency key already exists. The punches had one; the operation logs did not. |
| Impact | `LegacyEmployeeStore` and `LegacyBranchService` each gain one tolerant statement, neither changing its route's response; `device_operation_logs` gains a `dedup_key` and is written with `INSERT IGNORE`; `unclaimed_device_sightings` prunes on a genuinely new serial; `DeviceInput.isValidPin` is the single PIN rule; `devices.is_active_invalid` is a new message key in both catalogs. **Neither deletion preview is extended** — company or employee — for the D-111 reason already recorded in D-166. **R-041's attribution claim is corrected rather than defended**: legacy issues a `type=company` token for a company admin, which identifies the company and not a person, so `registered_by_employee_id` is null for those claims; an individual actor is recorded only for employee tokens, and real per-person attribution depends on F-26, which production claiming is gated on anyway. Device tests 65 → 72. |
| Follow-up | Unchanged from D-165/D-166: the §4.3 hardware checklist, Q7 provisioning, and slice B′ (platform-mediated allocation plus the audited unclaim/transfer/replace path) all gate production. |
| Evidence | PR #162 review comments on commit `3f6d5d5` (eleven findings); `LegacyEmployeeStore.deleteDeviceIdentity`; `LegacyBranchService.deactivateDevicesOfDeletedBranch`; `DeviceAttendanceEvent.contentKey`; `UnclaimedDeviceSightingStore.RETENTION_DAYS`; `DeviceInput.isValidPin`; `DeviceManagementService.requiredBoolean` and its `TransactionTemplate`; `ZkTecoAdmsService.exceedsRecordCap`. Regression tests named in the specification's §13. |

## D-168: The Phase 1 MariaDB DDL becomes a shipped provisioning artifact, and the application reports what is missing

| Field | Value |
|---|---|
| Decision | `phase1_extensions.schema.sql` moves from `backend/src/test/resources/legacy/` to `backend/src/main/resources/db/phase1-mysql/phase1_extensions.sql`, and a `Phase1SchemaCheck` running under `phase1-mysql` logs, at startup, every owned table the connected database lacks and the capability it disables. |
| Reason | **R-023** is not that the DDL is hard to write; it is that the only copy lived in test resources, so the file an operator would run against production was one nothing but a test container had ever executed, reachable only from a git checkout of whatever branch they happened to have. Moving it makes the schema the suite proves the adapter against byte-identical to the schema an operator applies, and shipping it in the jar means the DDL an operator extracts matches the code they deployed rather than a branch that has moved on. |
| Why a startup report and not a startup failure | Refusing to start would let a missing platform-admin table take `/apis/**` down for every employee — the opposite of the containment the surrounding code is deliberately built for, where `LegacyBranchService` and `LegacyEmployeeStore` both swallow an absent device table so a partially-provisioned deployment still serves. R-023's own Impact entry is that *"nothing fails at cutover time, which is what makes this dangerous"*: login succeeds without `legacy_refresh_tokens` and the failures surface later, scattered across logout and password reset. The answer to a silent gap is a loud signal, not a new outage. |
| Deliberately not idempotent | No `CREATE TABLE IF NOT EXISTS`. A table that already exists with the wrong columns would satisfy that check silently, which is the exact failure the file exists to prevent. The runbook verifies first, then applies, and a second run failing loudly on an already-provisioned database is the correct outcome. |
| What keeps it honest | `Phase1SchemaCheckTest` parses the shipped DDL and asserts the check's table list matches it exactly, so a table added to one and not the other fails the build. The report itself is proven against a real MariaDB — a scratch database with none of the tables, then the same database with the DDL applied — because the collaborator whose behaviour is in question is the driver's catalog lookup, which a mock would not exercise. Case folding is covered too: `SPRING_SESSION` is upper case because Spring Session's own MySQL schema declares it so, while MariaDB's folding depends on `lower_case_table_names` and therefore on the host filesystem. |
| Impact | One file moved (56 test references repointed), one new class, one new test, one new runbook (`docs/operations/provisioning-phase1-tables.md`). No behaviour change on any request path. **Rollback** is reverting the commit; the DDL's own rollback is a `DROP TABLE` per name, and legacy PHP references none of them. |
| What this does not close | R-023 stays **open**. The tables still do not exist in production, and only the repository owner can run DDL there. What changed is that the mechanism is now decided, shipped, and verifiable, and that a deployment which skipped it announces itself in the first seconds of startup instead of days later in a password-reset failure. |
| Status | Accepted 2026-09-04. Related: **ADR-0013**, **R-023**, **D-043** amendment 3, **D-050**/**D-051**. |

## D-169: The signing secret is compared by fingerprint, not by reading it

| Field | Value |
|---|---|
| Decision | Both stacks print `HMAC-SHA256(secret, "workin-jwt-secret-fingerprint-v1")` truncated to 16 hex characters — Java from `JwtSecretStartupCheck` at every startup, PHP from a documented one-liner. Equal fingerprints mean identical secrets. Runbook: `docs/operations/verifying-the-signing-secret.md`. |
| Reason | **R-024** is the risk that Java and PHP sign with different HS256 secrets, which would force-log-out every live session at cutover and the same population a second time on rollback — turning the cheap rollback Phase 1 was approved on into the most disruptive part of the release. The obstacle was never difficulty; it was that the only way to compare two secrets was to look at them, and neither may be printed, pasted into a ticket, or read aloud. A fingerprint removes the reason nobody had checked. |
| Why logging it is safe | A digest of a secret under a label published in this repository is, on its face, an offline oracle for testing candidate secrets -- a background security review flagged exactly that, correctly. It costs nothing here because **every access token the application issues is already a stronger oracle over the same key**: HMAC-SHA256 over `header + "." + payload`, both halves known, handed to every client. Anyone able to brute-force the secret from the fingerprint could do it faster from a token they already hold, without log access. The first version of this entry cited the SSH host key fingerprint as the precedent, which is **wrong** -- those digest a *public* key, where confidentiality is not a property at all. Corrected rather than deleted, because a wrong reason reaching the right conclusion is worth being able to tell apart later. Truncating to 64 bits is a legibility choice, not a security one. The label is versioned so that a future scheme change cannot have an old fingerprint compared against a new one and read as a key mismatch. |
| Not sufficient on its own | The fingerprint proves the configured *values* match, not that both stacks **use** them identically — an encoding or claim-handling difference would pass it and still reject the token. The pre-cutover token exchange (mint in Java, present to PHP, reverse) stays in the procedure as the end-to-end proof; the fingerprint is what makes a mismatch cheap to catch first. |
| The trap it pins | PHP's `hash_hmac($algo, $data, $key)` takes the message second and the key third, the opposite way round from most APIs. Getting it backwards yields a stable, plausible, entirely wrong value on one side only — a mismatch that does not exist, sending an operator after the wrong problem during a cutover window. `JwtSecretFingerprintTest` pins the Java side to a vector computed by an independent implementation so the two cannot silently diverge. |
| Impact | One method and one log line in an existing startup check; one new test class; one new runbook. No request path changes. The fingerprint is logged at `INFO` on every boot, which is intended — it is the artifact an operator compares. **Rollback** is reverting the commit. |
| Status | Accepted 2026-09-04. Related: **R-024**, **D-111**, **R-025**. |

## D-170: The whole PHP dashboard ports to JTE, all three audiences, same design

| Field | Value |
|---|---|
| Decision | The PHP dashboard is reproduced in JTE inside the backend -- same pages, same design, and all three login audiences (platform admin, company owner, HR/Manager). Recorded in full as **ADR-0016**, which supersedes **ADR-0009 Option E** in scope. |
| Reason | Two things converged on 2026-09-04. The owner decided the production VPS runs **Java and MySQL only, no PHP and no rollback** -- which turns every unported dashboard capability from "later" into "gone". And checking what that costs surfaced a gap nothing had recorded: nine dashboard pages are gated by `isAdmin()`, only `companies` exists here, and four of the rest write data reachable no other way. `faqs` (6 writes), `banners` (3), `phone_countries` (3) and `notifications` (1) have **read-only** API endpoints and their write side lives in the dashboard alone. Switching PHP off would have frozen the app's banners, FAQs and dial codes permanently, short of hand-editing MySQL. |
| What I got wrong | Not that `companies` was built first -- that was right, and D-163 records why. The error was reporting the admin web as done while knowing ADR-0009 said `pages/companies/` **"and equivalents"**, without ever establishing what the equivalents were. The owner found it by asking. Under the earlier plan that would have been a scheduling miss; under a no-PHP deployment it is a capability that cannot be recovered. |
| Design by copy, not by redesign | The dashboard's stylesheets and scripts are copied verbatim and the templates reproduce its class names, so "the same design" is a property of the artifact rather than an aspiration. A template that invents classes gets no styling, which keeps the copy honest. Its 772 translation keys are converted from `lang.php` by `scripts/convert_dashboard_lang.py` into `i18n/admin-messages` -- separate from `i18n/messages`, which is the API's wire-visible catalog the parity work pins. |
| Unported pages are shown, not hidden | `AdminNav` lists every dashboard page and marks which exist; the sidebar renders the rest disabled. A sidebar that omits half the product looks finished when it is not, and during a migration the gap is the most useful thing on the screen. |
| Impact | Assets, i18n bundles, `AdminNav`, `AdminIcons`, `AdminViewModelAdvice`, a rebuilt layout and sidebar, the login page rebuilt to the dashboard's own design, and `/admin/assets/**` permitted with `PlatformAdminAssetsExposureTest` bounding it. **Rollback** is reverting the branch; nothing on the request path for `/apis/**` changes. |
| The risk it creates | **R-044** -- two new authentication paths, ~30 new authenticated routes, and a deliberate cross-tenant mode (the admin acting as a company, PHP's `$_SESSION['company_id']`). Filed before the surface exists so the control is designed rather than retrofitted. |
| Status | Accepted 2026-09-04. Related: **ADR-0016**, **ADR-0009**, **ADR-0015**, **D-163**, **R-044**. |

## D-171: The four write-only-in-PHP admin pages, ported first

| Field | Value |
|---|---|
| Decision | `phone_countries`, `faqs`, `banners` and `notifications` are ported ahead of the other twenty-six dashboard pages, under **ADR-0016**. |
| Reason | Not size, and not how often they are used. These four are the only dashboard pages whose **write side exists nowhere else**: `banners/list`, `faqs/list`, `phone_countries/list` and `app_content/one` are read-only endpoints, so the clients can display this content and never change it. Every other page has a company-scoped equivalent on the Flutter desktop client. Switching PHP off with these unported would have frozen the app's banners, FAQs, dial codes and broadcasts at whatever rows they held, recoverable only by editing MySQL by hand. |
| Gates, and what is deliberately absent | Three: the surface flag, a bound second factor, and an audit row written in the same transaction. **No per-edit step-up**, unlike the company lifecycle actions -- a TOTP prompt per FAQ edit makes the page unusable and pushes an operator back to hand-editing the database, which is the outcome with no audit trail at all. The broadcast carries a fourth gate instead: an explicit confirmation showing the recipient count, because that number is what makes it a decision. |
| Broadcast: not reproduced | The dashboard inserts one row per recipient in a loop, untransacted -- 2,838 round trips for one all-employees send against the reference snapshot, cut off partway by PHP's execution limit (**R-045**). The port uses a single `INSERT ... SELECT`: the same rows, one statement, cannot half-commit. Proven against a real MariaDB rather than a mock, because the change *is* the SQL and a mock would assert only that the test knows what it wrote. |
| Uploads: the fix inherited, not re-implemented | The banner image goes through `LegacyFileUploads`, the component the ported API endpoints already use, so the stored extension comes from the sniffed content type (**D-154**). The dashboard's own `company_dashboard_upload()` takes it from the client's filename -- the second instance of **R-039**, found by sweeping the class while porting this page. Writing a new uploader here is precisely how that defect would have been copied forward. |
| The allowlist is load-bearing | `banners/list.php` returns `button_action_value` to the clients **unsanitised**; the API's own `sanitize_banner_internal_route()` and `sanitize_banner_external_url()` are defined and called from nowhere. Write-time validation is the entire control, so the 20-key route allowlist and the `^https?://` scheme check are carried across whole and pinned by tests that assert `javascript:`, `data:` and `file://` cannot be stored. |
| A schema claim corrected | An earlier draft of `FaqStore` asserted that `faq_items` had no foreign key and that PHP therefore orphaned items on category delete, and deleted them explicitly to compensate. Checking the schema showed `fk_faq_items_category` is `ON DELETE CASCADE`: the claim was wrong and the code redundant. Both removed. Recorded because the comment would have outlived the code and misled the next reader. |
| Impact | Four pages, four stores, four services, four templates, 44 tests. `AdminPageAvailability` now derives the sidebar's live entries from the handler mapping instead of a hand-kept boolean. **Rollback** is reverting the branch; no `/apis/**` path changes. |
| Status | Accepted 2026-09-04. Related: **ADR-0016**, **D-170**, **D-154**, **R-039**, **R-044**, **R-045**. |

## D-172: The client-declared request fields the port had not read

| Field | Value |
|---|---|
| Decision | Four request fields the current Flutter clients send, and that the port ignored, are implemented: `employees/template_excel?purpose=update`, `sheet_layout` on `attendance/analyze_excel` and `attendance/import_excel`, and `platform` on `profile/logout`. `attendance/export?type` was on the same list and needed nothing -- it was already ported, and the entry was stale in the committed contract rather than missing from Java. |
| Reason | Each was a field a client sends today and Java silently discarded. Discarding a request field is the quiet failure mode: the endpoint answers 200 with a correct-looking body for a different question. `purpose=update` returns the create template, so an operator downloads a sheet whose example row would overwrite a real employee's name. `sheet_layout` returns "unrecognised layout" for a sheet the client just previewed. `platform` deactivates a desktop user's own account on logout. |
| The logout is the one that mattered | PHP now gates the deactivation on three conditions where it used to have one. It needs an employee-app session, the `EMPLOYEE` **role**, and a mobile `platform` or none at all. Java had only the session type, so an HR or admin session logging out of the desktop app deactivated its own user's account -- and the account then needed another HR to reactivate it. The same PHP change reworded `employee_account_not_active` to say exactly that, which is how the two were found together. The deactivating update also bumps `token_version`, so the leaver's outstanding JWT stops authenticating at once instead of at its expiry; `delete_account.php` still does not, so the two deactivations stay separate store methods. |
| An empty sheet is decided on rows, not on labels | `attendance_import_prepare_records()` is new shared ground between the analyse and import paths, and it settles emptiness before any layout branch runs -- on `rows === []`, not on the format label. That changes one measured behaviour: an encrypted XLS, which `SimpleXLS` reports as a successful parse of no sheet, used to reach the punch-column resolver and come back as the two-column message. It is now the unsupported-format answer. The more honest answer for a file nothing could be read out of, and PHP's current one. |
| The update template drops its examples | Only `employee_code` keeps one. An example left in a cell of the bulk-edit sheet is read as an edit, so a template that shipped "مثال: محمد" in the first-name column would rename whoever the operator forgot to clear. |
| Impact | `LegacyEmployeeSpreadsheetColumns`, `LegacyEmployeeTemplate`, `LegacyEmployeeService`, `LegacyAttendanceImportReader` (+`normalizeSheetLayout`, `resolveDailyColumns`, `extractDailyRecords`, `prepareRecords`), `LegacyAttendancePunchDateTimeParser` (+`parsePunchDate`, `parsePunchTimeParts`), `LegacyAttendanceAnalyzer`, `LegacyAttendanceImporter`, `LegacyAttendanceImportService`, `LegacyAttendanceController`, `LegacyProfileService`, `LegacyProfileStore`. 30 new tests. **Rollback** is reverting the commit; every new field is additive and an absent one behaves as before. |
| Status | Accepted 2026-09-05. Related: **D-074**, **D-085**, **D-111**, **D-173**. |

## D-173: The message catalog gets the same drift gate the routes have

| Field | Value |
|---|---|
| Decision | `scripts/check_legacy_message_drift.py` compares this application's `legacy/lang/{en,ar}.properties` against hr-legacy's `apis/lang/{en,ar}.php`, keys **and values**, through a committed inventory at `contracts/legacy-php-messages.txt`. Wired into `validate_phase0.py` and the CI workflow beside the route gate, with `test_check_legacy_message_drift.py` pinning what it catches. |
| Reason | `employees/update_bulk` shipped answering `"employees_updated"` -- the raw key, on the wire, to the user's screen. The endpoint was ported, wired, reviewed and tested, and nobody had added the two strings it names. Both catalogs fall back to `getOrDefault(key, key)`, which is PHP's own `t()` fallback, so a missing key is not an error anywhere. `MessageCatalogSyncTest` compares `en` against `ar` -- Java against Java, the same blind spot `check_legacy_route_drift.py` was written to close for routes -- and both files were equally missing it. |
| What it found immediately | Five missing keys (`employees_updated`, `employees_update_failed` and the three new attendance-import failures) and one reworded value: `employee_account_not_active` now tells the employee that HR reactivates the account from the desktop app. That last one is a string a client shows verbatim, and nothing else would have caught it -- a key-only comparison would have passed. |
| Values, not just keys | A reworded message is a contract change for any client that matches on text, and a key whose value silently diverges is the harder half of this defect to find. The cost is that the committed inventory is 825 lines rather than 411; that is the point of it. |
| The parser refuses to guess | The first draft read only single-quoted PHP strings and reported the six double-quoted messages -- the ones carrying a `\n` -- as Java-only additions. It now counts every line that *starts* an entry and fails loudly when it cannot read one, rather than shrinking the inventory by six and looking healthy. That case is a test. |
| Impact | Two scripts, one committed inventory, two CI steps, one validator hook. Five keys added and one value corrected in both catalogs. **Rollback** is reverting the commit; the gate is additive and blocks nothing that was previously passing. |
| Status | Accepted 2026-09-05. Related: **D-172**, **R-007**. |

## D-174: The dashboard's authorization model, ported before the pages that need it

| Field | Value |
|---|---|
| Decision | `HrAccess` and `org_helper.php` are ported as `DashboardAccess`, `DashboardSession` and `DashboardOrgScope` before any more dashboard pages, and `AdminNav` is rebuilt from `sidebar_build_menu()` to consult them. |
| Reason | Twenty-one dashboard pages remain, and nineteen of them serve **three audiences** from one codebase: the platform administrator across all companies, a company owner, and an HR or manager employee whose visibility is a row of `can_*` flags. Porting a page at a time would mean re-deciding that model per page, and the copies would disagree. `HrAccess::canViewPage()` is one function in PHP and is now one method here, which the sidebar and every controller ask. |
| One enum, not three booleans | PHP keeps the audiences apart with `admin_logged_in`, `company_logged_in` and `hr_logged_in`, and each login path `unset()`s the other two. Three booleans that must be mutually exclusive is a state machine written as flags: a missing `unset()` yields a session that is two audiences at once. `DashboardSession.Audience` makes that state unconstructible. |
| The static nav was two things wrong | `AdminNav` was a fixed list with an `adminOnly` flag -- a second copy of a rule `canViewPage` already owned, and a shape that cannot express what PHP does. The comms group holds **different pages** per audience: an administrator gets FAQs, guide videos, dial codes and settings; an owner or HR gets company settings instead. The static list had also drifted: it carried `app_content`, `setting_templates` and `change_password`, none of which the dashboard's sidebar renders, and was missing `guide_videos`, which it does. |
| The filter is session state, and it is R-044's widening | `?company_id=` sets a **session** filter for the administrator, `0` meaning all companies; a later request without the parameter keeps it, and only a present-and-empty value clears it. PHP distinguishes those three cases with `array_key_exists`, not `isset` -- with `isset`, "show me every company again" would be unreachable. A scoped session never reads the parameter at all, so an owner appending `?company_id=9` changes nothing: it is not ignored for safety, it is never on a path where it could mean anything. |
| A third catalog drift, and its gate | The five `guide_video*` strings hr-legacy added after `convert_dashboard_lang.py` last ran were missing, so the new sidebar entry would have rendered as the literal `nav_guide_videos`. Running a converter is not a gate -- it is right on the day it runs and silent afterwards. `check_dashboard_message_drift.py` is the API gate's sibling for `dashboard/includes/lang.php`, kept separate because the two catalogs have different shapes and folding them together would let a change to either break the other's check. |
| Impact | Three new classes, `AdminNav` rebuilt, the sidebar and eleven page templates threaded with the session instead of an `isAdmin` boolean, one missing icon added, 36 new tests, 192 green across the admin surface. **Rollback** is reverting the commit; the only audience this build issues is still the administrator, so no behaviour reaches a user that did not before. |
| Status | Accepted 2026-09-05. Related: **ADR-0016**, **D-170**, **D-173**, **R-044**. |

## D-175: The first org page, and the machinery the other three inherit

| Field | Value |
|---|---|
| Decision | `branches` is ported with the shared list machinery it needs -- `DashboardPage`, `DashboardListFilters` -- rather than as a self-contained page, and the org writes are gated by the surface flag, a bound second factor and an audit row. |
| Why branches first | The four org pages (`branches`, `departments`, `job_titles`, `shifts`) are the same shape over the same helpers. Building one properly makes the other three small; building the small one first would have meant discovering the pagination and filter semantics on a page with less to prove. Branches also has the check-in code, which is the only part of the four with its own failure modes. |
| Two legacy quirks reproduced rather than corrected | `dbPaginate()` clamps the page number **after** taking the offset, so page 99 of a 3-page list returns no rows while the pager highlights page 3. And `org_list_read_filters()` does not validate `filter`: an unrecognised value falls through both status tests and behaves as `all`. Both are visible on a URL a user can type, so correcting them here would make the port disagree with the system it replaces. |
| Stricter than PHP in one place, deliberately | The dashboard's only gate is the section permission. These writes also require the surface flag and a bound second factor, and record an audit row -- because an administrator writing **inside a customer's company** is at least as sensitive as editing a FAQ (D-171), and this surface has those controls for every other write. New event types `ORG_CREATED/UPDATED/DELETED`, kept apart from `CONTENT_*` so an auditor asking "what changed inside this customer" does not also get the FAQ edits. |
| Where the tenant check is, and where it is not | `org_verify_post_row()` runs only for a **scoped** session: the administrator is allowed to edit any company's rows, which is the point of the filter (**R-044**). `org_branch_generate_qr()` is the exception -- it runs the check unconditionally, so a branch id from another company is refused for everyone. Both reproduced, and named here because "the check is skipped for one audience" should be deliberate rather than discovered. |
| A CSRF defect the tests found | Four already-shipped pages -- FAQs, banners, notifications, dial codes -- never exposed the CSRF token to their templates, so their forms carried an empty hidden field and every POST would have been refused with a 403 that reads as a permissions problem. `PlatformAdminWebCsrf.expose()` is a call each controller has to remember; it is now a model attribute on `AdminViewModelAdvice` instead, for the same reason the other four attributes are, with a test asserting a non-empty token on every page that renders a form. |
| A fourth catalog gate | A template asking for a key no bundle has renders the key on screen with nothing failing -- the same defect as the two API catalogs and the dashboard one, one layer further in, and invisible to a drift gate because the key is invented on the Java side. `AdminTemplateMessageKeyTest` scans every JTE template and resolves each key against the bundle chain, in both locales. |
| Impact | `Branch`, `BranchStore`, `BranchAdminService`, `AdminBranchesController`, two templates, `DashboardPage`, `DashboardListFilters`, three audit event types, one icon, the CSRF move. 21 new tests; 218 green across the admin surface. **Rollback** is reverting the commit; the page is new and nothing else changes behaviour except the CSRF fix, which only replaces an empty token with a real one. |
| Status | Accepted 2026-09-05. Related: **ADR-0016**, **D-174**, **D-171**, **R-044**. |

## D-176: On an edit, the row's own tenant is authoritative

| Field | Value |
|---|---|
| Decision | **Tenant ownership of an existing row is never writable from request data.** Two halves. A column that *is* the ownership -- `company_id` -- is not among an edit's updated fields at all. A foreign key that *decides* ownership indirectly is validated against the **existing row's** company: never the session's scope, and never the `company_id` the form posted. |
| Reason | A session-based check can be widened by how the operator is looking at the page. An administrator with no company filter has `companyId == 0`, which satisfies every "is this visible to you" test, so a posted foreign key from another company passed. The row's own company is the one thing no filter setting can widen -- which is also why it must not be re-derived from the request on the way back in. |
| Where it was open | Five places, found by asking the same question of each page -- the four already ported, and `administrative_decisions` by putting the question to it **before** writing it. **`assets`**: the edit writes `company_id` from the chosen employee, so posting a foreign employee moved the row. **`penalties`**: no `company_id` column at all -- the row *is* whichever company its employee is in -- so reassigning the employee moved it with nothing to give it away. **`departments`**: the branch set was validated against the posted `company_id`, so an edit could link a company-A department to company-B branches. **`job_titles`**: the same through `department_id`. **`administrative_decisions`**: the strongest of the five -- `company_id` is itself among the updated columns and comes from the posted value, so an edit transfers the record between tenants outright rather than leaving it mis-pointed. An earlier reading called this page clean because its visibility guard is correct; the guard is, and the update is not. |
| Where it was not | `branches` and `shifts` have no editable foreign key, and `branches` deliberately keeps `company_id` out of its updated columns -- which is this decision's other half, arrived at before it had a name. `leave_balances` and `requests` do not accept an employee change on an edit. Checked rather than assumed. |
| What stays possible | Reassignment **within** a company: moving an asset to another person, changing which branches a department spans, giving a job title a different department. That is what these pages are for, and each has a test so the rule cannot later be over-tightened into uselessness. |
| Relationship to the legacy behaviour | Stricter than the PHP, which validates against the posted company throughout. This is the same class of divergence as **R-046** and rests on the same reasoning: a missing authorization check is not something to reproduce. Unlike R-046 the PHP hole here needs an administrator session, so it is not in the patch shipped for `hr-legacy`. |
| Impact | `CompanyAssetAdminService`, `PenaltyAdminService`, `DepartmentAdminService`, `JobTitleAdminService`, plus `companyOf()` on two stores. Six new tests, three of them proving same-company reassignment still works. **Rollback** is reverting the commit; the only behaviour removed is a cross-tenant write. |
| Applied going forward | The question is asked of each remaining page **before** it is written, covering both halves: is any column that carries ownership among the updated fields, and can any editable foreign key change ownership indirectly. That is how `administrative_decisions` was caught with no defective code to fix. |
| Status | Accepted 2026-09-05 at the owner's direction, widened the same day. Related: **R-046**, **R-047**, **R-044**, **D-175**. |

## D-177: The Workforce Planning Port, And What The D-176 Pre-Check Found There

| Field | Value |
|---|---|
| Decision | Port `workforce_planning` with the row's own company authoritative for every write, scope the form's option lists server-side, and refuse a duplicate target rather than reproducing legacy's crash. Reproduce the `actual_count` arithmetic exactly, including the part that is wrong. |
| Why it is recorded separately | It is the page where the **D-176** pre-check paid for itself three times over, and where the check that had twice been done by pattern-matching was finally done by reading. Worth a decision of its own so the next page's check is done the same way. |
| What the pre-check found | Four things, none of which a grep would have surfaced. **(1)** `delete_wp` has no tenant check of any kind -- not even the `$cid > 0` one its sibling `edit_wp` carries -- which makes this the seventh page in **R-046**, not the "guarded" page two earlier sweeps had called it. The sweeps had matched `org_branch_belongs_to_company()` on the page and read it as a row guard; it is a foreign-key check inside `$validateWpPayload()` and says nothing about who owns the row. **(2)** The fullest D-176 case on the surface: `company_id` **and** all three foreign keys are written from one posted company, so a row moves tenant with everything it references and arrives self-consistent (**R-047**). **(3)** The form's cascade ships every company's branches, departments and job titles to every company (**R-051**) -- 3,671 rows across 283 companies, measured. **(4)** The table's unique key is written straight through, so a duplicate target is an uncaught `PDOException` (**R-050**). |
| What the port does | `company_id` is absent from the update. All three foreign keys are validated against the company read from the **existing row**, never the session filter and never a posted value -- so a same-company re-point succeeds and a cross-company one is refused whole, even when only one of the three keys is foreign. The option lists take a `company_id` predicate instead of being filtered in the browser. A duplicate target is refused with `already_exists`, an existing catalogue key, with the unique constraint still the real guarantee. |
| What was deliberately not changed | The `actual_count` join, which can never match a department-less plan because the two tables spell "no department" differently -- 0 on one, NULL on the other (**R-052**). It is reachable, currently untriggered on 16 live rows, and changing it would change a number a company sees. Recorded for a product decision rather than fixed while porting, and pinned by a test that states the reasoning so it is not later "corrected" by someone reading it as an oversight. |
| Evidence | `AdminWorkforcePlanningEndToEndTest`, 23 tests over real HTTP against a real MariaDB: same-company re-point, cross-company refusal by posted `company_id` and by each foreign key separately, one-foreign-key-of-three, delete scoped to the filter, delete unfiltered still allowed, duplicate refused, self-save allowed, option lists scoped, negative count floored, archived branch refused. |
| Impact | Six new files plus a path constant. The legacy patch in `contracts/legacy-fixes/` gained an eighth file and five assertions (38, all passing), and **R-046**'s count moved from six of eight pages to seven. **Rollback** is reverting the commit; the route is new, so nothing existing changes behaviour. |
| Status | Accepted 2026-09-05. Related: **D-176**, **R-046**, **R-047**, **R-050**, **R-051**, **R-052**. |

## D-178: The Employees Port, And Which Legacy It Was Ported From

| Field | Value |
|---|---|
| Decision | Port `employees` against `hr-legacy` **HEAD**, not the working tree. Require a branch, refuse a duplicate code with a key that exists, and hold all four foreign keys to the row's own company. |
| Why the source needed deciding | `hr-legacy`'s working tree carries uncommitted edits that change behaviour this port depends on: the opening leave balance drops from 21 days to 15 on the `leave_balances` page, and the automatic `dbInsert('leave_balance', ...)` inside `add_employee` is **removed entirely**. HEAD (`d113204`) still has both. |
| Resolved | The repository owner confirmed on 2026-09-05 that the frozen baseline is the contract: `d113204` is the reproducible source of truth, the port keeps creating the 21-day opening balance, and its test keeps asserting it. Uncommitted working-tree state is not to be adopted silently. The discrepancy is tracked on its own as **R-056**, which counts the two edits separately -- 21-to-15 and removing automatic creation are independent changes, and the second may moot the first -- and states that an explicit decision on the target behaviour is needed before the port changes. |
| What the pre-check found | **R-053**, the worst defect on the surface, recorded and patched separately. Beyond it: **R-054**, a duplicate code flashes `employee_code_already_exists`, which the dashboard catalogue does not define, so the user reads the key itself; and **R-055**, `branch_id` is `NOT NULL` but neither the guard nor the form requires it, so an add with no branch is error 1048 -- confirmed against the production copy's own non-strict `sql_mode`, which does not rescue an explicit `NULL` the way it rescues a bad enum. |
| What the port does | All four actions resolve the row's company first. The three org keys and the shift are validated against it, so an employee can be re-pointed within their company and never out of it -- legacy checks none of the four. A branch is required on both paths. A duplicate code refuses with `already_exists`, which `lang.php` defines; adding legacy's own key was not available, because `check_dashboard_message_drift.py` compares the catalogues in both directions and a Java-only key is itself a failure. |
| Parity kept deliberately | Four behaviours that look like defects and are reproduced. A last name is not required but a first name is. A password is hashed on create only when a phone was given, and on edit regardless. The stored phone is `phone_digits_only()` of what was typed, while validation ran against a normalised form -- so an Egyptian number typed without its leading zero is accepted as `010...` and stored as `10...`. And the list's count query omits the `companies` join the list itself uses, so an employee whose company row is gone is counted and never shown. |
| Reuse | `LegacyPhoneNumbers` is reused -- a pure value rule, and the same one the PHP calls. `LegacyHrEmployeeService` is **not**: it is the HR-role and permissions surface, with its own uniform-403 rule, and shares only a table with this page. |
| Evidence | `AdminEmployeesEndToEndTest`, 29 tests over real HTTP against a real MariaDB, including the cross-tenant password write that is R-053's headline, the cross-tenant delete, same-company re-pointing, the shift-assignment no-op, the four contract-duration shapes, and the option lists following the edited row's company. |
| Impact | Six new files plus a path constant. **Rollback** is reverting the commit; the route is new. |
| Status | Accepted 2026-09-05, with the leave-balance question open. Related: **D-176**, **D-177**, **R-051**, **R-053**, **R-054**, **R-055**. |

## D-179: The Salary Calculator Port, And Reproducing PHP's Arithmetic Rather Than Improving It

| Field | Value |
|---|---|
| Decision | Port `salary_calculator` as a pure computation with no persistence, and reproduce PHP's `round()` and `number_format()` exactly -- including their inaccuracies -- rather than computing the figures more correctly in `BigDecimal`. |
| Context | `dashboard/pages/salary_calculator/page.php` is the first ported page that touches no table at all: three numbers from the request, ten out, nothing read and nothing written. There is no row to own, so the **D-176** pre-check is clean vacuously rather than by construction -- recorded because a clean pre-check is still a pre-check, and because "no ownership column" is a different finding from "the ownership column is protected". |
| Why the arithmetic needed deciding | The page's entire output is numbers. Java has no equivalent of PHP's `round()`: `Math.round` is `floor(v + 0.5)`, which rounds `-2.5` to `-2` where PHP gives `-3`, and was deliberately fixed in Java 7 so that `0.49999999999999994` gives `0` where PHP still gives `1`. `BigDecimal` with `HALF_UP` gets the sign right and then disagrees for a different reason: PHP **pre-rounds** to `14 - floor(log10(abs(v)))` places before rounding to the requested precision, a window of roughly `1.1e-15` relative, so `round(1234.4999999999998)` is `1235`. A more accurate implementation is a wrong one here. |
| Evidence | The model was checked against PHP 8.3 over **20,160 cases exchanged as raw IEEE-754 bit patterns** -- the `.5` edge of ten decades walked three ULPs either side, the products this calculator forms, and the extremes -- with **zero disagreements** on both `round()` and `number_format($v, 0, '.', ',')`. The boundary half of that corpus ships as `legacy-parity/php-round.txt`. Separately, **547 whole-page vectors** covering the insurance band's edges, every tax bracket boundary, thousands separators, scientific notation and malformed input were generated by running the legacy page's own parsing and `EgyptMonthlySalaryCalculator::compute()` in a `php:8.3-cli` container; all ten returned figures match bit for bit, and so does every rendered label. |
| One correction found by that comparison | `number_format` hands its rounded value to C's `printf`, which breaks ties to **even**; Java's `Formatter` breaks them away from zero. Reachable only above `1e15`, where `round()` returns the value untouched and a fraction survives -- caught by the corpus, not by inspection. |
| Legacy behaviours preserved | The query string wins over the posted body (`$_GET[x] ?? $_POST[x]`), and an empty query value wins over a populated body one because `??` falls through on null and not on `""`. `reset` is the presence of the key in the query string, so `?reset=0` resets. A gross of `0.5` produces a **negative** net, because the insurance base is clamped up to a floor of 2,700 and the employee's 297 is charged against it; a **negative** gross produces no result at all, because the guard is `$gross > 0` and not `>= 0`. The "total monthly deductions" row is a heading with an empty cell beside it: the figure is computed and never printed. |
| Rates are copied, not recomputed | Every rate, bracket and threshold is the legacy file's constant. If Egyptian tax law moves, this class is wrong in exactly the way the PHP is wrong until someone changes both. During the cutover the contract is the frozen page, not the tax code. |
| Rollback | Delete the controller: `AdminPageAvailability` reads the handler mapping, so the sidebar entry goes back to muted and unclickable on its own. Nothing persists, so there is no data to unwind. |
| Related | **D-176** (the pre-check, clean here), **R-044** (the audiences the section guard is waiting for), **R-058** (the shell and stylesheet defect this port surfaced on the pages already shipped). |

## D-180: Completion Is Measured By Enumerating Mappings, Not By Grepping Sources

| Field | Value |
|---|---|
| Decision | Both surfaces' completion inventories are derived from what the application really serves -- Spring's `RequestMappingHandlerMapping` for the Java side, the filesystem plus `.htaccess` for the legacy side -- and compared against a committed manifest by a test that runs in the suite. A source grep is not an inventory and is not to be used as one. |
| What prompted it | A grep-derived count reported the `/apis/**` surface as **76 of 202** routes ported. It was wrong: the pattern matched only routes written as one full string literal, so every controller using a class-level `@RequestMapping("/apis/api/<resource>")` composed with a method-level `"/list.php"` was invisible. A corrected grep then reported **200 of 202**, and was also wrong -- it expected a quote after the opening parenthesis and so missed `@RequestMapping({"/list.php", "/summary.php"})`. The true figure is **202 of 202**, and `LegacyPhpRouteInventoryTest` had been asserting exactly that, from the handler mapping, the whole time. |
| Why it mattered rather than being a tidy-up | The wrong number was reported to the repository owner and used to choose what to work on next. It produced a sequencing plan -- `leave_balances`, `advances`, `penalties`, `workforce_planning`, then the organization resources -- every item of which was already complete. Two rounds of work were planned against a measurement that a test in the same repository already contradicted. |
| The rule | Spring composes class-level and method-level paths, resolves arrays of patterns, and applies profile conditions. A regular expression does none of that, and its failures are silent and one-directional: it under-reports, which reads as work remaining. Ask the framework what it serves. |
| The legacy side has the same trap | The dashboard's routable set is not its directory listing. Three directories under `dashboard/pages/` carry no `page.php` and are asset directories (`home`, `org`, `reports`); `index.php` is the home page and lives outside `pages/`; and two routes reach a `detail.php` only through a rewrite in `dashboard/.htaccess`. Counting directories -- which an earlier inventory did -- reported `reports` as an unported page that does not exist. |
| What was built | `contracts/legacy-dashboard-pages.txt`, a generated manifest of the 35 pages the dashboard serves; `scripts/check_dashboard_page_drift.py` to generate and drift-check it, with `scripts/test_check_dashboard_page_drift.py` as its fixture-based sibling; and `AdminDashboardPageInventoryTest`, which enumerates `/admin/**` from the handler mapping under `phase1-mysql` and partitions the surface into ported, declared-Java-only, and an explicit not-yet-ported list. |
| A divergence the new inventory immediately surfaced | Legacy reaches one company's detail through a rewrite of `company_detail.php`; the port serves it as `/admin/companies/{companyId}`. Employee detail did the opposite and kept legacy's `/admin/employee_detail`. Neither is wrong, but the two details of the same surface disagree, and that is now recorded in `SERVED_UNDER_ANOTHER_PATH` rather than absorbed silently. |
| Profile dependence is part of the answer | The test is pinned to `phase1-mysql`. Most of these pages are backed by tables that exist only in the legacy schema, so their controllers carry that profile; under the default profile the surface is three pages, which is a true answer to a different question. |
| Current state, measured | `/apis/**`: **202 of 202**. Dashboard: **23 of 35**, with twelve named in `NOT_YET_PORTED` -- `activities`, `app_content`, `attendance`, `change_password`, `company_settings`, `content`, `guide_videos`, `join_requests`, `payroll`, `profile`, `setting_templates`, `settings`. |
| Rollback | The manifest and both checks are additive; deleting them restores the previous state, in which the surface's size was whatever the last grep said. |
| Related | **D-074** (the envelope inventory this follows), **R-060** and **R-056** (the deferrals that remain open regardless of what is ported). |

## D-181: The Activity Feed, Its Collation Requirement And Its Two Clocks

| Field | Value |
|---|---|
| Decision | Port `activities` as a read-only feed, reproducing legacy's explicit collation normalisation, both of its time formats, and the branch of its presenter that cannot fire. |
| D-176 pre-check | **Clean, vacuously.** The page writes nothing and takes no row id. Its guard is present -- `HrAccess::can(PERM_RECENT_ACTIVITIES)` inline rather than through `hr_require_section()`, but the correct permission -- so there is no **R-057**-shaped gap either. |
| The collation is a correctness requirement, not legacy noise | Every text column on both sides of the `UNION ALL`, and both `NULL` placeholders, are wrapped in `CAST(... AS CHAR CHARACTER SET utf8mb4) COLLATE utf8mb4_unicode_ci`. `employees`, `attendance` and `requests` need not share a collation, and MariaDB **refuses** a `UNION` whose corresponding columns disagree -- "Illegal mix of collations" is an error, not a degraded result. An untyped `NULL` takes the connection's collation and is equally capable of not matching, which is why the placeholders are cast too. Dropping any one of these casts turns the page into a 500 on some deployments and not others. |
| Two clocks on one row, deliberately | The description says "Check-out at 5:30 PM" and the column beside it says "2026-09-04 17:30". They come from different functions: `home_format_time()` is a 12-hour clock that is also language-dependent (`ص`/`م` in Arabic, `AM`/`PM` in English, no leading zero on the hour, and both midnight and noon rendering as 12), while `hr_format_datetime_cell()` simply truncates the timestamp to sixteen characters. The port keeps both rather than agreeing with itself. |
| Three permission decisions, not one | Opening the page needs `can_recent_activities`. The attendance half additionally needs `can_attendance` and the request half `can_requests`, so a session holding one and not the other sees half the feed -- and the filter's dropdown offers only the halves it may see. With neither, legacy returns an empty result rather than an error, and so does this. |
| A branch that cannot fire | `home_present_activity()` falls back to a plain "Request" label when `COALESCE(t.name, '')` is empty. `requests.request_type_id` is `NOT NULL` behind a RESTRICT foreign key, so the `LEFT JOIN` never misses and a missing type is unreachable. The fallback is reachable only through a request type whose **name** is empty, which is how the test exercises it. Kept, because the source keeps it. |
| Its pager is not the shared one | `dbPaginate()` caps at `PAGE_SIZE_MAX` and reports `pages: 0` for an empty list. This page floors the size at 10, caps it at 100, floors the page count at 1, and clamps the page number **after** taking the offset -- so asking for page 99 of a one-page list reports page 1 above an empty table. Reproduced rather than unified. |
| Formatting the three `%s` messages | `activity_checkin_at`, `activity_departure_at` and `activity_request_of` carry PHP's `%s` verbatim and are the only three in the catalogue that do, with no previous Java consumer. They are formatted with `String.format` in a record method that takes the translator; passing them through `MessageSource` arguments would leave the `%s` in the output and silently drop the value. |
| Rollback | Delete the controller; `AdminPageAvailability` reads the handler mapping, so the sidebar entry mutes itself. Nothing persists. |
| Related | **D-180** (the inventory this deletes a line from), **R-060** (ruled out: the feed reads `attendance` but never calls the worked-minutes engine being rewritten). |

## D-182: The Settings Hub, Its Integrity Rules, And Why company_settings Is Not Here

| Field | Value |
|---|---|
| Decision | Port `settings` and its two redirect aliases (`app_content`, `setting_templates`) as one domain. Do **not** port `company_settings`, which legacy gates to a company owner or an HR employee. |
| D-176 pre-check | **Clean, vacuously.** All three tables behind the page -- `app_content`, `setting_definitions`/`setting_allowed_values`, `configs` -- are platform-level and carry no `company_id`. There is no tenant to own a row, which is precisely why the page is administrator-only: the guard is the tenancy model rather than an addition to it. |
| Why `company_settings` is excluded | Its guard is `if (!isCompany() && !isHr()) { redirect; }`. Every session this surface issues is a platform administrator's, so the page would be either unreachable code or a relaxed guard. Neither is a port. It joins `profile` and `change_password` in the audience ADR-0016 and **R-044** still have open, and the inventory now says so in the file rather than in someone's memory. |
| Four integrity rules, reproduced | The content key is checked against a three-entry allowlist before any write, so a posted field cannot create an arbitrary content row. An option's stored `value` is pinned once any company has selected it -- the posted value is replaced by the stored one before validation and again before the update -- while its labels and sort order stay editable, so an option can be renamed but never re-coded. An option that any company depends on cannot be deleted. And `setting_definition_id` is absent from the option update entirely, so an edit cannot move an option to another definition: D-176's shape applied to a key that is not a tenant. |
| Two config behaviours worth naming | A boolean is stored as the **word** `true`/`false` but read back accepting `1`, `true` or `yes`, so rows written before that format still display correctly -- the two directions deliberately do not share one vocabulary. And `configs_save_from_post()` writes **every** defined key on every save, using the empty string for one the form omitted; that is how an unchecked box becomes false, and it also means a partial post blanks what it leaves out. The page always submits the whole tab, so the behaviour is safe where it is used, and it is reproduced rather than guarded against. |
| Value length is counted in characters | `mb_strlen($value) > 120` counts code points. `String.length()` counts UTF-16 units and would call an emoji two, so the port uses `codePointCount`. Arabic is unaffected either way; the difference is only visible outside the BMP. |
| The layout gained `pageScripts` | Mirroring the `pageStyles` mechanism D-179 added, because two of these tabs carry their own JavaScript. Defaulted, so no existing template changes. |
| A defect this port surfaced in an already-shipped page | Two controllers -- both written today, one already in `61b26866` -- emitted `actions_disabled` and `factor_not_bound`, neither of which exists in either catalogue. The translator answers a missing key with the key itself, so those refusals reached the user as the literal string `actions_disabled` and every test stayed green. The established names are `admin_actions_disabled` and `mfa_required_for_actions`, in `i18n/admin-own.properties`. Both are corrected, and `AdminLayoutWiringTest` now fails when a controller emits a key no catalogue defines -- verified by reintroducing the defect and watching it fail. |
| One thing that was not a defect | Arabic content posted over HTTP arrived corrupted in the first test run. The cause was the test harness, not the application: `FormHttpMessageConverter` writes ISO-8859-1 when the content type carries no charset, where a browser posts UTF-8 because the page declares it. The harness now sends the charset a real client would. |
| Rollback | Delete the three controllers; `AdminPageAvailability` reads the handler mapping, so the sidebar entries mute themselves. Nothing schema-level changes. |
| Related | **D-179** (`pageStyles`, extended here), **D-180** (the inventory these three are deleted from), **R-044** (the audience `company_settings` waits for). |

## D-183: The Migration Contract Is Derived From A Commit, Not A Working Tree

| Field | Value |
|---|---|
| Decision | Generate both committed inventories from `git show HEAD:` / `git ls-tree HEAD` in `hr-legacy` rather than from its filesystem, and fail when this application serves a route the committed contract does not contain. A working-tree-only legacy surface does not define the migration contract. |
| Why | **R-063.** Reading the filesystem let four untracked surfaces into the committed inventories and then into this application, where their parity claims cannot be re-derived from any checkout. A manifest generated from uncommitted files describes one machine rather than a contract, and the completion figures it produces -- "202 of 202", "35 pages" -- are about that machine. |
| The direction that was missing | `check_legacy_route_drift.py` compared *committed minus Java* and never *Java minus committed*. So a route this application served that the contract omitted produced no output at all. That silence is precisely how three routes ported from untracked files went unnoticed, and it is now a failure. |
| Effect on the numbers | The route inventory is **199** and the page manifest **34**. Neither is a regression: they are the first figures measured against something a second machine can reproduce. The application still serves 202 routes; the three beyond the contract are named in `AWAITING_BASELINE` with the decision each waits on. |
| Why a named list rather than an exemption | An unexplained exemption would restore the silence this change removes. Each entry carries its reason and names R-063, and the sibling test asserts both -- so the list cannot quietly become a place to hide unported or over-ported routes. |
| `guide_videos` needs no entry | It is absent from the HEAD-derived page manifest, so it is simply not a surface this application owes. It was deliberately not ported for the same reason its API route should not have been. |
| Testing | Both fixture suites build real git repositories instead of bare directories, which is closer to what the scripts do and lets each cover the originating case directly: a file present on disk and in no commit is not a surface. The route suite's "a route only Java has does not fail" case was inverted to assert the new, stricter rule, with a comment saying why the premise changed. |
| Rollback | Revert the two scripts to their filesystem readers and refresh; the inventories return to describing whatever is on disk. |
| Related | **R-063** (the risk), **D-178** (which made `d113204` the source of truth), **D-180** (which made inventories enumeration-derived in the first place). |

## D-184: The Message Catalogues Are Derived From A Commit Too

| Field | Value |
|---|---|
| Decision | Extend **D-183** to the two message inventories. `check_legacy_message_drift.py` and `check_dashboard_message_drift.py` now read `git show HEAD:` rather than the filesystem, and each carries a named `DIVERGES_FROM_BASELINE` list for what this application ships that `HEAD` does not define. No shipped message changed. |
| Why | D-183 fixed the route and page inventories and claimed the mechanism closed. It was not: the same generator pattern produced both message catalogues, and both were working-tree-derived. Fixing one instance of a defect and declaring the class closed is how the same review round repeats. |
| What was actually in them | Eleven divergences. Ten are new keys that arrived with surfaces already named elsewhere -- five `guide_video*` labels for a page that exists in no commit, and five for the two untracked `employees` routes already in `AWAITING_BASELINE`. The eleventh is different in kind and is why this was worth chasing: `employee_account_not_active` is not a new key but a **changed value**, so this application answers a real 403 on the login path with wording that appears in no `hr-legacy` commit. |
| Why nothing was reverted | Both directions are decisions, not cleanups. `HEAD` says "Your employee account is not active. Contact HR."; the working tree says the longer wording this application ships, which is plainly the more useful of the two. Silently keeping either would be the mistake **R-056** already established -- do not adopt the working tree, and do not quietly retain the old behaviour either. The gate now names it and the owner picks. |
| The lists are self-policing | A named divergence that stops diverging fails the gate. Without that an exemption list rots into a hiding place: an entry outlives its reason and nobody notices. Both were verified by deliberately naming a message that does not diverge and watching the gate turn red. |
| Scope, honestly stated | Seven scripts read `hr-legacy`. Four are now `HEAD`-derived: the route and page inventories (D-183) and these two catalogues. Three are not -- `check_legacy_lang_drift.py`, `check_legacy_schema_drift.py` and `check_legacy_spreadsheet_columns_drift.py` -- and all three currently track the working tree. They **vendor** legacy artifacts rather than describe a contract, so hardening them would rewrite shipped files, and for the latter two that collides directly with the **R-060** work that is deliberately deferred. They are recorded in R-063 rather than changed. |
| Testing | Both fixture suites build real git repositories, and each gained the originating case -- a message on disk and in no commit is not a baseline message -- plus a case proving a stale exemption fails. The one case that checks the real repository keeps the real list; every fixture case swaps in its own, because the production list names real messages that a two-key fixture would report as settled. |
| Rollback | Revert the two scripts to their filesystem readers and refresh; the catalogues return to describing whatever is on disk. Nothing shipped depends on this. |
| Related | **D-183** (which this completes), **R-063** (the risk), **R-056** (the precedent for deferring rather than silently choosing), **R-060** (what blocks the remaining three). |

## D-185: The Baseline Moves To 505004f

| Field | Value |
|---|---|
| Decision | The port's contract is now `hr-legacy` **`505004f`**, not `d113204`. The owner committed that repository's working tree on 2026-09-06, so the 73 files that had existed only on one machine became a reproducible baseline. All four inventories were refreshed against it: **202 routes** (was 199) and **35 pages** (was 34). |
| Why this was the unblocking move | Three separate risks were all waiting on the same thing. **R-063** needed a committed source for four surfaces; **R-056** needed the leave-policy change committed before it could be ported; **R-060** needed the fiscal-period feature committed. One commit resolved all three, and no further decision was required from anyone. |
| The gates behaved as designed | Every one of them went red against the new baseline and green after refresh. More usefully, the self-policing guard added in D-184 failed all eleven named divergences as *no longer divergent* -- the exemption lists emptied themselves rather than having to be remembered. That is the whole point of making a list refuse to outlive its reason. |
| What Java already matched | Most of it. Verified individually rather than assumed: logout's account deactivation, `server_unix` on `configs/get` and `time/now`, `employee_row_attach_company_fiscal_month` on login, the four fiscal helpers, `employee_monthly_attendance`'s period metadata, and all three vendored artifacts (schema, lang, spreadsheet columns). The port had tracked the working tree for behaviour even while its contracts tracked `d113204`, which is the same inconsistency R-063 named from the other side. |
| The three real gaps | Each is a deliberate deferral now unblocked, not a defect. **(1)** Leave entitlement: Java reads `monthly_leave_accrual` with a 21.0 fallback in four places; the baseline uses a fixed `AppConfig::DEFAULT_ANNUAL_LEAVE_DAYS`. **(2)** `LegacyEmployeeService` still seeds a leave-balance row on employee creation; the baseline removed that write entirely. **(3)** `attendance/stats.php`: the baseline added a fiscal-period branch and a current-fiscal-month branch, and Java resolves only the calendar-month path -- its sibling `employee_monthly_attendance.php` already matches. |
| Surfaces still unported | Six of 35. `attendance` and `payroll` are unblocked by this commit; `guide_videos` became portable for the first time; `company_settings`, `profile` and `change_password` remain behind the **R-044** audience decision, which this commit does not touch. |
| Two defects recorded, not fixed | `AppConfig::DEFAULT_ANNUAL_LEAVE_DAYS` is defined in no tracked file, so the value the port must match is still not reproducible from a clone. And both `constants.example.php` templates were deleted with no replacement, which removes the only tracked record of required configuration. Both are named in the `hr-legacy` commit message rather than silently corrected. |
| Rollback | The inventories regenerate from any commit; pointing them at `d113204` restores the previous contract. Nothing in the application depends on which baseline is current. |
| Related | **D-178** (which made `d113204` the source of truth this replaces), **D-183** and **D-184** (the HEAD-derived mechanism that made this refresh a one-command operation), **R-056**, **R-060**, **R-063** (all resolved here), **R-044** (untouched). |

## D-186: The Leave Entitlement Becomes A Checked Constant

| Field | Value |
|---|---|
| Decision | Port the two leave-policy changes in `hr-legacy` `505004f` together: the annual entitlement becomes a fixed product default, and employee creation stops opening a balance at all. The value is not transcribed into Java -- `check_legacy_product_defaults_drift.py` reads it out of `hr-legacy`'s tracked template at `HEAD` and fails if `LegacyLeavePolicy` disagrees. |
| Why the template came first | `AppConfig::DEFAULT_ANNUAL_LEAVE_DAYS` lived only in a git-ignored `constants.php`, so there was nothing to port from and nothing to check a port against -- R-063 again, in a value rather than a surface. `a2dd5d7` restored both `constants.example.php` files (deleted with no replacement in `505004f`, apparently by accident) and added the constant to the API one. It belongs there rather than in a deployment note because it sits with `DEFAULT_LIMIT` and `MAX_LIMIT`, carries a real value rather than a placeholder, and is not per-company. |
| What changed in Java | Three sites read `monthly_leave_accrual` with a `21.0` fallback and now read `LegacyLeavePolicy`. Two of them were SQL lookups against the setting chain and are deleted outright, because at HEAD the PHP does not query anything. `RequestService` no longer needs `CompanySettingsService` at all. The setting itself is untouched: it is still stored, still editable, and still returned by the settings API -- it simply no longer decides the annual figure. |
| The creation change | **Three** sites, not two. `LegacyEmployeeService` and `LegacyEmployeeCreateHelper` match `employees/create.php` and `employee_create_helper.php`; `EmployeeAdminService` matches the dashboard page, which deleted its own `dbInsert('leave_balance', ...)` in the same commit. The third was missed on the first pass because it used the integer `21` rather than `21.0` and lives outside the `legacy` package -- the full suite caught it. `LegacyEmployeeStore`'s three leave-balance methods and `EmployeeStore.insertOpeningLeaveBalance` lost their last callers and are gone. |
| How completeness was established | Not by grep-and-hope: every `leave_balance` write on both sides was enumerated. At HEAD, PHP writes it in exactly four places -- request approval (API), the Excel import helper, the `leave_balances` CRUD page, and the dashboard request deduction -- and **none** in employee creation. The two surviving defaults were checked to agree: the dashboard deduction hardcodes 15 and Java matches, `generate.php` reads the constant and Java now does too. |
| A failure mode disappeared with it | `date('Y', strtotime($hire_date))` was the only `strtotime()` in `create.php`, and it fed the opening balance. Under `strict_types=1` an unparseable hire date raised a TypeError from inside the transaction, so create answered 500 and the batch importer rolled the row back. That expression is gone, so the same input now answers **201** and stores the zero date. This is a real behaviour change, not a port defect, and the tests were inverted to assert it. |
| Tests: premise changed, not weakened | Three tests documented the vanished leave-year grammar and were removed with the helpers they used. Two bulk-import tests proved per-row transaction isolation using the vanished failure as their trigger; the invariant still holds, so the trigger moved to a `@MockitoSpyBean` rather than the coverage being dropped. A new end-to-end test seeds `monthly_leave_accrual` to a conspicuous 30, calls `generate.php`, and asserts every generated row opens at 15 -- verified to fail when the old value is restored. |
| Rollback | Revert this commit; the constant, the gate and the four call sites move together. The `hr-legacy` template change is independent and should stay regardless. |
| Related | **D-185** (the baseline this ports), **R-056** (closed by this on the implementation side), **R-063** (the reproducibility rule this follows), **D-183**/**D-184** (the HEAD-derived mechanism the new gate reuses). |

## D-187: Claude Runs In CI As An Implementer, Never As A Reviewer

| Field | Value |
|---|---|
| Decision | Add `.github/workflows/claude-assistant.yml`, running the official `anthropics/claude-code-action` pinned to `9c5ddab2e6d17b83ea679153b31f1d5f023cf636` (v1.0.217). It is invoked only by a human writing `@claude` or assigning an issue, and it is an implementer under **D-121**, not a second reviewer. |
| Why the official action | The proposal was a third-party fork, `nicholaslee119/claude-code-github-action@0.1.1`. Three things ruled it out: an unaffiliated maintainer (28 stars, one owner); a **mutable tag**, which the owner can repoint at any time, against a repository where every existing action is pinned to a 40-character SHA; and the fact that whatever runs holds `ANTHROPIC_API_KEY` and write access here. The official action is the same capability without any of those. |
| Why it cannot become a gate | The workflow's `permissions:` block omits `statuses: write`, so it cannot publish any commit status -- `independent-review` included. D-125 makes that status a required check, and nothing in this workflow can reach it. |
| And the guarantee is enforced | Prose would rot, so `validate_workflow_safety()` now fails any workflow but the review gate that grants `statuses: write`. Verified by injecting the permission and watching the gate go red. It strips comments before matching -- two workflows explain in prose that they do *not* take this permission, and a raw substring match would fail them for saying so, which is the same false positive the trigger ban produces. |
| Trigger choice | `issue_comment`, `pull_request_review_comment` and `issues: assigned`. All three execute the workflow file from the default branch, so a pull request cannot rewrite this file and have its own version run with these permissions. The privileged `*_target` variant would additionally expose untrusted pull-request code to these credentials, which `validate_workflow_safety()` forbids everywhere except the review gate (**D-122**). |
| One settings file, not two | The action receives `.claude/settings.json` -- the same deny-list terminal sessions run under, covering credentials, keys and keystores, and the destructive git operations `scripts/git_guard.py` blocks. A second copy would drift, which is the failure mode this repository's inventory rules exist to prevent. Both hooks behave in CI: the guard is if anything more valuable there, and the audit log writes to a git-ignored path. |
| A false positive worked around, not silenced | `validate_workflow_safety()` matches the forbidden trigger name against raw file text, so naming it even inside an explanatory comment failed the build. The check already has a `_without_comments()` helper and uses it two lines below, so it could have been made comment-aware -- but loosening a security check to accommodate one's own file is the wrong instinct, and erring toward a false positive is the right bias for that check. The comment was reworded instead, and says so. |
| What it costs an operator | `ANTHROPIC_API_KEY` must exist as a repository secret. Without it the job fails on authentication and posts nothing, so the symptom is an `@claude` mention that never gets a reply; the Actions run log says why. Nothing else in CI depends on this workflow, so a failure here never blocks a merge. Dependabot already watches the `github-actions` ecosystem at `/`, so the SHA pin is maintained rather than frozen. |
| Rollback | Delete the workflow. Nothing references it, no gate consumes it, and no other job changes behaviour in its absence. |
| Reversed 2026-09-06 | The owner removed it. The workflow, its `docs/agents/responsibility-matrix.md` row and prose, and `AGENTS.md`'s ownership bullet naming it are all deleted; the rollback row above was accurate, and nothing else moved. **`ANTHROPIC_API_KEY` is no longer needed as a repository secret** -- it was this workflow's only consumer, and it was never added. Two things deliberately stay: `.claude/settings.json`, which predates the workflow and governs terminal sessions, and `validate_workflow_safety()`'s `statuses: write` check, which is a general rule about who may publish the `independent-review` status and was never specific to this workflow. Dependabot's `github-actions` watch stays too, for the four workflows that remain. |
| Related | **D-121** and **D-125** (the review gate this must never satisfy), **D-122** (the trigger reasoning it follows), `docs/agents/responsibility-matrix.md` (the row this adds), **AGENTS.md** (the global rule making the implementer/reviewer split explicit for automation). |

## D-188: Attendance Stats Reads Fiscal Periods

| Field | Value |
|---|---|
| Decision | Port the two branches `hr-legacy` `505004f` added to `attendance/stats.php`. `month`/`year` now resolve through `payroll_fiscal_period_bounds()`, and a named employee with no explicit range falls back to the period containing today. The calendar path survives as the third branch, unchanged. |
| Branch order is the contract | The source checks them in a specific order and the port keeps it: an explicit `month`/`year` wins **even when an employee is named**, and only then does the employee branch apply. An explicit `date_from` or `date_to` disables both. Reordering these would be invisible in most queries and wrong in exactly the case a fiscal company cares about. |
| Why it mattered | A company whose month runs 26th-to-25th is inside February's period on 3 March. The calendar reading answered for March, so its dashboard totals silently covered the wrong days. Its sibling `employee_monthly_attendance.php` already resolved periods correctly, so the two endpoints disagreed with each other. |
| A wrong assumption, caught by the test | The first fixture set `month_start_day` alone and expected a period spanning two calendar months. It does not: `payroll_fiscal_period_bounds()` defaults an unset end day to the month's own last day, so `start <= end` and it takes the same-month branch -- 26 alone yields 2020-01-26 to 2020-01-31. A 26th-to-25th month needs **both** settings. The test failed, the source was read, and the fixture now configures both with the reason recorded beside it. |
| Testing | Two cases against a genuinely non-calendar company, so a calendar reading cannot pass by coincidence: January's period covers the 2020-01-09 holiday where today's month covers none, and March's period starts 2020-02-26, so `total_days_in_month` is February's 29 rather than March's 31. The first fails when the branches are disabled -- verified. The second case, precedence, passes either way by construction: it guards the order rather than detecting the change. |
| Rollback | Revert this commit; the third branch is the previous behaviour in full. |
| Related | **D-185** (the baseline), **R-060** (closed by this), **D-186** (the other gap that baseline opened). |

## D-189: The Attendance Page Is Ported Parity-First, Including Where It Disagrees With Payroll

| Field | Value |
|---|---|
| Decision | Port `dashboard/pages/attendance/page.php` — the 30th of 35 dashboard pages, unblocked by **D-185**. The page renders one date range twice and the two tables do not agree on worked time. Both readings are reproduced exactly as the source computes them; neither is normalised toward the other. |
| The shortcut that was refused | `LegacyPayrollAttendanceFigures.attendanceDisplay()` returns this aggregate's field names exactly, and routing the page through it would have been a two-line implementation. It computes different numbers. Three of them: absence there is capped by an as-of notion and adds void weekly-rest days, while here it is a plain `expected - present - leave - holidays` floored at zero; the holiday credit there is `official_holidays_working_credit_for_employee()`, which weighs working days, while here it is `dashboard/includes/`'s `official_holidays_credit_for_employee()`, a plain count of in-range holidays the employee did not attend; and that credit is suppressed there below a minimum coverage, never here. |
| What was reused, and why only that | Three helpers whose behaviour is genuinely identical: `approvedLeaveDays`, `employeeWorkHoursPerDay` and `expectedWorkDays`. `expectedWorkDays` was widened from private to public for this and says so at its declaration. Everything else in the aggregate is the page's own arithmetic, written against the page's own source rather than borrowed. |
| The two tables stay in disagreement | The detail list runs each punch through `attendance_row_worked_minutes()`; the aggregate sums raw `TIMESTAMPDIFF(MINUTE, check_in, check_out)` in SQL and never consults that engine. For an employee whose punches the engine adjusts — a capped shift, an exception day — the same range reports two different totals. Reconciling them would change what the page reports to its users, so it is preserved and documented at both `AttendanceRecord` and `AttendanceStore`. |
| Pinned so it cannot be re-merged | Three regression fixtures assert the divergence rather than the values: the aggregate's hours are raw SQL where the detail's are the engine's; the holiday credit is the dashboard count and not payroll's working credit; absence uses the period length with no company and `expectedWorkDays` with one. A future refactor that "unifies" these fails all three. |
| Authorization needed no divergence | Unlike the payroll page next door, this one does not require Java to be stricter than its source. `505004f` closed **R-059** upstream: `hr_verify_post_row('attendance', ...)` guards the write block and both foreign keys are checked against the row's own company. The port matches that rather than diverging from it. |
| D-176 holds by construction, not vacuously | `edit_attendance` never writes `employee_id`, so the column that decides ownership is unreachable from an edit — D-176(a). `exception_type_id` **is** editable and is validated against the company of the row already stored, never against anything the request supplied — D-176(b). Both are asserted, in both directions. |
| Testing | 18 tests. Rendering, the four write actions, `check_out`'s empty-string-to-NULL, `strtotime` range parsing and a reversed range; the three parity fixtures above; and five tenant guards — a foreign exception type refused on edit, a crafted cross-company row id refused on both edit and delete with **every column of the victim row asserted unchanged afterwards**, and a punch against another company's employee refused with nothing written. A control sits beside the two refusals: the same request, under the same filter, against the session's **own** row, which succeeds. Without it a refusal could be coming from the filter rather than from the ownership check, and the pair would prove less than it looks. That last one is the insert side, which is what made R-059 more than another R-046 page: `employee_id` is attacker-chosen and it *decides* the new row's owner, so there is no existing row to check against. |
| Corrected after the first commit | The port called `DashboardOrgScope.rememberAfterWrite()` after every write, which is what its sibling HR pages do because theirs call `hr_redirect($page, hr_post_company_id())` — they move an unfiltered administrator's filter to the company just written to. This page does not: all four of its redirects are `payroll_redirect('attendance', $cid)`, passing the filter **already in force**. The difference is invisible on a single write and shows up on the second: an administrator who added a punch for one company was silently confined to it. Fixed, with a regression test that writes to two companies in a row. The payroll page has the same shape and was built without the call from the start. |
| Rollback | Delete the five new files and revert the three one-line edits (`ATTENDANCE_PATH`, `expectedWorkDays`'s visibility, the inventory list). The page returns to `NOT_YET_PORTED`; nothing else consumes it. |
| Related | **D-185** (the baseline that unblocked this), **D-176** (the invariant), **R-059** (closed upstream, not by divergence), **R-064** (the payroll page, where a divergence *is* required), **D-188** (the fiscal branches in the same domain). |

## D-190: The Payroll Page Is Ported With An Authorization-Only Divergence, And Two Legacy Findings Left Alone

| Field | Value |
|---|---|
| Decision | Port `dashboard/pages/payroll/page.php` — the 31st of 35 dashboard pages. Every one of its five id-driven write actions gains the tenant guard the source does not have (**R-064**). Nothing else about the page's behaviour changes, including two places where the source disagrees with itself. |
| The divergence, stated narrowly | `calculate`, `finalize`, `reopen`, `delete_run` and `edit_detail` each take `id` from the request and reach `dbUpdate`/`dbDelete`/`dbFind`, all three of which are a plain `WHERE id = ?`. `create_run` resolves its company server-side and is correct, which is what shows the distinction was understood and simply not applied to the other five. The port guards all five with the same shape `hr_verify_post_row()` uses on the attendance page, which `505004f` fixed and this page was not included in. **Authorization only** — no amount, no status transition and no side effect is changed. |
| What was reused, and what was refused | `calculate` goes through `LegacyPayrollBatchService.calculate()`, because `payroll_calculate_batch()` is literally the same PHP function the API endpoint calls: parity and a scoped batch lookup in one move. `finalize` and `reopen` do **not** go through the service methods of those names, even though they exist, are scoped, and would have been the short path. Those are the API's semantics: they rewrite the fiscal period, apply advance payments, mark or unmark penalties, and refuse a second finalize. The dashboard's are bare status writes. Calling them would have started applying financial side effects this page has never applied — a behaviour change wearing an authorization fix's clothing. |
| Two legacy findings recorded rather than fixed | **R-066**: the dashboard's finalize skips `payroll_finalize_batch_side_effects()`, so advances are not deducted from their remaining balance and penalties are not marked applied. Since the calculation counts only unapplied penalties and deducts against current remaining, the next month deducts both a second time. **R-065**: the page's per-batch CSV export takes `run_id` from the query string with no company predicate and ships every payslip in it — the read side of the same page, which a fix scoped to R-064's description would not touch. Both are left as they are, under the owner's 2026-09-06 instruction to change nothing in PHP. |
| The detail table is recomputed, not read | PHP hands `payroll_paginate_payslips()`'s rows to `payroll_enrich_payslip_rows()` before rendering them, and that recomputes `days_present`, `days_absent`, `days_leave`, `penalties_total`, `total_entitlements`, `total_deductions` and `net_salary` from live attendance and contract data, adding four derived salary figures the table also shows. The port reuses the same enrichment — `payroll_enrich_payslip_row()` is one function with callers on both sides, so unlike `finalize` this reuse is the faithful reading rather than a shortcut. `LegacyPayslipService.enrichRows()` is the entry point, mirroring PHP's own bare loop. |
| Which leaves the page disagreeing with itself, on purpose | The totals strip and the batch list's `total_net` sum the **stored** columns; the detail table shows the **recomputed** ones. So a `net_salary` typed into the edit form is stored, moves the strip, and never appears in the row beneath it. That reads like a bug and is the source's behaviour, so it is reproduced and pinned by `theTableIsRecomputedWhileTheStripSumsWhatIsStored`. The edit form itself is deliberately **not** enriched: it edits the stored columns, so it must show them. |
| The partial write is preserved and asserted | `edit_detail` posts twelve fields into a twenty-five column table. `gross_salary`, `total_entitlements`, `total_deductions` and the seven itemised allowance and deduction columns are not among them, so a hand-edited payslip's net stops reconciling with its own stored totals. The test asserts **all twenty-five**: the twelve hold exactly what was posted, the thirteen still hold what the calculation wrote. The second half is the point — it stops a future change from "helpfully" recomputing the totals on edit. |
| One 500 avoided without widening the divergence | `create_run` in PHP is wrapped in `$co = dbFind('companies', $cid); if ($co)`, so an unknown company writes nothing and says nothing. Dropping that check would not have been a silent difference: `payroll_batches.company_id` is a foreign key, so the insert would fail and the operator would meet a 500 where legacy gives them a quiet redirect. The guard is reproduced exactly — no write, no message — rather than improved into an error, which would have spent divergence budget this page does not have. |
| Three test classes, on purpose | A red build should name the broken contract. `AdminPayrollTenantIsolationTest` is access control, `AdminPayrollCalculationParityTest` is the business numbers, `AdminPayrollEndToEndTest` is what the page does for the operator who is allowed to use it. Every refusal is asserted twice — the request is rejected **and** the victim's row is unchanged column for column — because a guard that refuses the response while half-applying the write would pass the first assertion alone. A control case runs the same request under the same filter against the session's own row, so a refusal cannot be coming from the filter instead of the ownership check, and R-044's unfiltered cross-company reach is asserted still to work. |
| Rollback | Delete the eight new files and revert the three edits (`PAYROLL_PATH`, one message key, the inventory list). The page returns to `NOT_YET_PORTED`. |
| Related | **R-064** (the finding this closes in Java), **R-065** and **R-066** (opened by this work), **D-189** (the attendance page, where no divergence was needed), **D-176** (the invariant `edit_detail` satisfies by not carrying `batch_id` or `employee_id`), **D-117** (the other disclosed drift in the finalize path), **R-044** (the reach this does not narrow). |

## D-191: Guide Videos Is Ported, And A Filter Ordering Tie Is Fixed Behind It

| Field | Value |
|---|---|
| Decision | Port `dashboard/pages/guide_videos/page.php` — the 32nd of 35 dashboard pages, and the last one not blocked by **R-044**. Three pages remain: `company_settings`, `profile` and `change_password`, all behind the audience decision this does not touch. |
| The simplest page on the surface, for one reason | `guide_videos` has no `company_id`. It is platform-wide content, the page is administrator-only in PHP (`if (!isAdmin())`) and in `DashboardAccess`, and so it carries none of the tenant guards the HR pages needed. That absence is stated at the record rather than left as a silence, because "no guard here" and "guard forgotten here" look identical six months later. |
| What is guarded instead | The `video` column is a filename typed in by hand that later reaches a filesystem path when a client resolves the clip. PHP passes it through `faq_parse_video_filenames()` — `basename()`, then an allow-list of `^[A-Za-z0-9._-]+\.(mp4\|webm\|mov\|m4v)$`, which admits no separator at all. The port calls the **same method the read path already uses**, made public for it, rather than restating the rule: a second copy of a security control is a second thing to get wrong. Ten form tests and two end-to-end ones drive traversal attempts through it. |
| One divergence, in a message | PHP's `edit` updates by id with no existence check and flashes `saved_ok` regardless, so editing a row that is not there reports success. The port returns `error_not_found`, which is what its sibling `FaqAdminService` already does for the same shape. No row is written that PHP would not write and none is skipped that PHP would; only the message differs. |
| Runtime DDL not reproduced | `guide_videos_ensure_table()` runs on every PHP request and will `CREATE TABLE` and migrate from `faq_items` if the table is missing. The table is in the vendored schema, the Java deployment has no PHP to run that migration a second time, and issuing DDL from a page render is not a behaviour to carry onto a surface that has a schema contract. An absent table now fails loudly instead of being silently rebuilt. |
| The find behind the port | Its end-to-end test is the first on this surface to post a **non-ASCII** value, and it failed: `دليل` was stored as `Ø¯ÙÙÙ`. The cause is **R-067** — `LocaleResolutionFilter` sat at `HIGHEST_PRECEDENCE`, the same order as Spring Boot's character-encoding filter, and calls `request.getParameter()`, which makes the container parse the body before the encoding can be set. Thirty-one pages had been ported without noticing because none of their tests posted anything but ASCII. Fixed by ordering the filter one step later. |
| Two wrong turns worth recording | The failure was first assumed to be the test client's default charset; the test was rewritten to post exactly what a browser posts, and it failed again, which is what turned an assumption into a measurement. Then, after the real fix, the test still failed and the new failure was assumed to be the same one instead of read — it was `tinyint(1)` mapping to `Boolean`, and the encoding was already fixed. Both were caught by looking at the assertion text rather than the word FAILED. |
| Rollback | Delete the six new files and revert the three edits (`GUIDE_VIDEOS_PATH`, the inventory list, `parseVideoFilenames`'s visibility). The filter ordering fix in **R-067** should **not** be reverted with them: it is independent of this page and the rest of the surface depends on it. |
| Related | **R-067** (the encoding defect this uncovered), **D-185** (which made this page portable by committing its source), **R-044** (the three pages still blocked), **ADR-0016**. |

## D-192: The Dashboard Port Ends At The Platform Administrator, And R-044 Closes

| Field | Value |
|---|---|
| Decision | **ADR-0016 narrows to the platform-admin audience.** The company-owner and HR web logins are withdrawn from scope, returning that audience to ADR-0009 Option E's answer — the Flutter desktop client. `company_settings`, `profile` and `change_password` are not ported. The dashboard port is complete at **32 of 35 pages**, with the other three out of scope rather than outstanding. |
| Why those three and no others | They are the only pages legacy gates *away from* a platform administrator: `profile` and `change_password` open with `if (isAdmin()) redirect`, and `company_settings` with `if (!isCompany() && !isHr()) redirect`. Every session on this surface is a platform administrator, so porting them meant either relaxing a guard legacy applies or building the two audiences. There was no third option, which is why they were the last three left. |
| The reasoning that changed | ADR-0016's scope was taken against a specific danger on 2026-09-04: four pages whose write side lived in the PHP dashboard **and nowhere else**, which switching PHP off would have frozen permanently. That test does not reach these three. Every capability on them has a ported API route — `company_settings/*`, `attendance_exception_types/*`, `company_official_holidays/*`, `request_types/*` and `hr_employees/{create,list,update_permissions}`; `profile/company.php` and `profile/employee.php`; `profile/change_password.php` — all 202 routes are ported and parity-verified, and the desktop client already carries the screens. Nothing is lost at cutover. |
| What was declined, and why it is worth naming | Porting them for the admin audience by relaxing the guard. It would have been the cheapest of the three options and is the one to refuse: it hands a platform administrator a page legacy denies them, including changing a company owner's password. An authorization change is not a port, and this programme's whole claim is that it replaces PHP without changing behaviour. |
| R-044's third control, and where it differs from the sketch | The risk asked for "a coverage test asserting that every admin page's queries carry a company predicate". Measured against the ported surface, that is the **wrong invariant**: of the statements touching a tenant-owned table, most legitimately carry no predicate — they are the ownership lookups themselves, or writes by row id that are safe precisely *because* the service resolved and checked that row's company first. Enforcing it would have produced a wall of exemptions and told nobody anything. `AdminTenantGuardCoverageTest` enforces D-176 one level up instead: a service method taking a `DashboardSession` must reach a tenant guard, and a service writing a tenant-owned table must take a session at all unless declared cross-tenant with a reason. |
| The guard proves itself | Three of its six cases feed it synthetic sources: one written to fail (the R-046 shape — a posted id as the whole authorization), one that reaches its guard only through two helpers (payroll's shape, which a one-level rule would have wrongly failed), and one with no session at all. A coverage test that has never been shown to fail is a coverage test nobody should trust. |
| One deliberate cross-tenant write, now declared | `BroadcastAdminService` writes a `notifications` row for every employee of every company and takes no session, because there is no single owning company for a guard to compare against — it is the platform announcement, one of the four capabilities that drove ADR-0016's original scope. It is the sole entry in `DELIBERATELY_CROSS_TENANT`, with its reason, and the list is self-policing: an entry that stops writing tenant-owned tables, or starts taking a session, fails the test. |
| Rollback | The scope decision reverses by reinstating the ADR's original audience list; nothing in the code depends on the narrowing, because the withdrawn audiences were never built. The coverage test stands on its own and should not be reverted with it. |
| Related | **ADR-0016** (amended by this), **ADR-0009** Option E (restored for this audience), **R-044** (closed), **D-176** (the invariant enforced), **R-046**/**R-053**/**R-059**/**R-064** (legacy instances of it missing), **R-010** (why the HR permission flag no longer needs a decision here). |

## D-193: Phase 1 Ships As A Container, And The Development Seed Is Sanitised Production

| Field | Value |
|---|---|
| Decision | The backend is packaged as **one image for all three environments**, with `local`, `integration` and `prod` Spring profiles differing only in configuration. Mobile and desktop developers get a working backend and database from `docker compose up` in a clone, months before a VPS exists; the same image later runs the VPS under `prod`. The `deploy/` Phase-0 boundary is opened for it. |
| Why one image | An image built differently for production is an image production is the first to run. Each profile used to pull in `phase1-mysql` through a `spring.profiles.group`; **ADR-0017 removed the profile split**, so a profile now selects configuration only and there is no pair to get half of. |
| The seed is sanitised production, at the owner's choice | Offered synthetic, schema-only, or sanitised production; the owner chose sanitised production, having been told it is the most work and the only option where a miss is a live PII leak. What survives is row counts, foreign-key topology, dates and the order of magnitude of every amount -- 386 companies, 3,783 employees, 44,756 attendance rows, so pagination, list performance and payroll arithmetic behave as they will in production. What does not is every name, phone, email, address, coordinate, credential, document URL and exact amount. |
| The seed is committed, which is why the gate came first | Developers build from a clone, so the seed has to be in the repository, so it is in git history permanently and the sanitisation gets **one** chance. `scripts/check_dev_seed_sanitised.py` was therefore written and tested **before** the seed was generated, and it is the gate -- not the build script's exit code -- that decides whether the output may be committed. |
| Three checks, because each is blind where another sees | **(1)** Value shapes: bcrypt hashes, dialable numbers, real email domains, JWTs, external upload URLs. **(2)** Column coverage re-derived from the vendored schema: every identity-bearing and every free-text column must be declared with a `-- covers:` line or listed as structural with a reason, so a column added later fails the build rather than leaking -- which a value scan cannot catch, because a column absent from the dump leaves no trace in it. **(3)** Sentinels: the sanitiser's own markers must be present, or the file is not its output and nothing else the gate says about it means anything. Both exemption lists are self-policing. |
| It caught a real leak on its first run | Nineteen real Egyptian mobile numbers survived in `assets.asset_text` -- a SIM-card asset records the number on it, and the column name mentions no identity, so coverage could not ask for it and only the value scan saw them. The response was to widen coverage from identity-*named* columns to **every** free-text column, so the class cannot recur rather than that instance being patched. |
| And a second defect, of a different kind | The sentinel password hash was bcrypt-*shaped* rather than a real hash. It satisfied every check in the gate and then failed at the only thing that mattered: a developer logging in. It is now generated with a fixed salt and verified to accept `devpassword` and reject anything else. A gate can only check what it was told to check; running the thing is what found this. |
| Phone numbers are format-valid and undialable | `010000NNNNN`. The application validates against `phone_countries.phone_prefixes`, so a number it rejects would break every login flow the seed exists to exercise -- and a number that is merely plausible might reach a real person. The gate permits exactly that block and reports anything one digit outside it. |
| A startup race, found by running it | `docker compose up` failed intermittently. MariaDB's entrypoint runs a **temporary** server with `--skip-networking` while the seed loads: a socket-based healthcheck passes against it, compose declares the database ready, the temp server shuts down for the real start, and the application connects into that gap and dies. The healthcheck now runs over TCP and requires a seeded table, which fails for the whole of that phase. A developer's very first command would have flaked. |
| Two controls deliberately not relaxed for convenience | The admin session cookie stays `Secure` unconditionally, so `http://localhost` cannot hold an admin session -- `/apis/**` is bearer-authenticated and needs no cookie, which is what the local stack is for. And WhatsApp stays unconfigured everywhere but production, because the habit of running with live messaging credentials is the one that eventually sends a real message. |
| `deploy/` is opened, not worked around | `Dockerfile` and `docker-compose.yml` are forbidden filenames outside `spike/` and `backend/` precisely so that shipping one is a recorded decision. Naming a file `compose.yaml` would have slipped past the check while appearing to honour the rule. The boundary is unlocked in `PHASE1_UNLOCKED_DIRS` with its reason beside `backend`'s, and `/deploy/` gets a CODEOWNERS entry. |
| The build context is a security control | `/.dockerignore` is an **allowlist**, not an exclusion list: the repository root holds a production dump, and without it that dump sits in every build context, one careless `COPY . .` from a published layer. An exclusion list would have to anticipate every future secret; an allowlist only has to anticipate the build. |
| Verified, not asserted | The jar starts (`Started BackendApplication in 6.48 seconds`, two profiles active, Phase 1 schema check passing). The stack comes up from a clean volume, the app reports healthy, `/actuator/health` is UP, `/admin/login` and a legacy route answer 200, the seeded volumes are 386/3,783/44,756, and `POST /apis/api/auth/login_company.php` with `devpassword` returns `{"success":true,...}`. That last one is the whole developer experience in a single call. |
| Rollback | Delete `deploy/`, remove `"deploy"` from `PHASE1_UNLOCKED_DIRS`, and drop the three profile files. Nothing in the application depends on any of it. *(Amended 2026-09-08: the sentence that followed said the default profile "still means the PostgreSQL Phase-2 path" -- ADR-0017 deleted that path, and a profile now selects a property file and nothing else.)* |
| Related | **R-040** (re-scoped to Phase 2 by the jar actually starting), **R-049** (why both prod ports bind to loopback), **R-024** (the signing secret the prod env file leads with), **ADR-0015** prerequisite 7 (why admin actions default off in prod), **R-051** (why org names are rewritten though they name no person), **D-028** (the precedent for opening a Phase-0 boundary by decision). |

## D-194: The API Description Is Generated And Corrected, Not Hand-Written And Not Committed

| Field | Value |
|---|---|
| Decision | springdoc publishes an OpenAPI document and Swagger UI from the controllers, in two groups — the 202 client routes and the platform-admin API. It is **on under `local` and `integration`, off under `prod`**. No snapshot is committed to `contracts/openapi/`; the served document is the artifact. |
| Why generate rather than write | There is no API to design. Phase 1 reproduces 202 PHP endpoints (D-111), so a hand-written document would be a second description of a surface that already has one — the code — and would drift from it silently. Generating it means the document is wrong only where the code is. |
| Two things the generated document said that were false | **Verbs:** every legacy route is mapped with a bare `@RequestMapping` and no `method =`, on purpose, so the handler answers PHP's own `405 invalid_method` in the legacy envelope; letting Spring answer 405 first in its own shape would be a client-visible change. springdoc reads that as "accepts everything" and published `GET /apis/api/auth/login_company.php` as valid. **Paths:** the mappings carry the PHP *file* path, because the inventory was built from the PHP source tree. |
| Why the suffix is not cosmetic | `LegacyPhpRouterFilter` records the measurement against production on 2026-08-31: `GET /apis/api/configs/get` answers **200** and `/apis/api/configs/get.php` answers **500** — `.htaccess` rewrites only when the target does not exist on disk, and a directly-requested PHP file runs without its helpers. This port serves both forms, so a document showing the file form would hand a client developer a URL that works here and fails against the system being replaced. Neither Flutter client's `api_constants.dart` uses the suffix. |
| The correction is one pass, because the two halves are entangled | The method inventory is keyed on the `.php` path, so a verb can only be looked up while the key still carries the suffix, and the path can only be rewritten once that lookup has happened. Written as two ordered customizers this is a latent bug the day someone reorders them; written as one pass over each path it cannot be got wrong. |
| It is registered twice, and that is why it must be idempotent | Measured in springdoc 2.8.6: `MultipleOpenApiResource` builds each group's resource from **that group's own** customizer set, so a plain `OpenApiCustomizer` bean reaches the ungrouped `/v3/api-docs` and no group. The customizer is therefore both a bean and attached to the client group, and it runs more than once over the same document. It is written so a second pass is a no-op — the lookup misses on an already-rewritten path — and `OpenApiConfigTest` pins that. |
| The verbs come from a gate, not from a comment | `scripts/check_openapi_route_methods_drift.py` extracts the true method from the handlers' four guard idioms into `backend/src/main/resources/legacy/route-methods.txt`, cross-checks the result against `contracts/legacy-php-routes.txt` — two inventories derived from different things, so agreement is evidence — and fails when the file goes stale. **202 routes, 200 with a specific method, 2 `ANY`.** The two are the `template_excel` downloads, which have no method check in PHP either. |
| The extractor's own falsification cases | `scripts/test_check_openapi_route_methods_drift.py` feeds it synthetic controllers: a guard that must not leak across a method boundary (an unguarded route published as POST-only is the failure that would look most plausible), a negated guard that must not read as the verb it rejects, an unreachable branch that must not widen a definitive guard, and an absolute path that must not be prefixed twice. |
| Why nothing is committed under `contracts/openapi/` | Two committed artifacts already describe this surface and each has its own gate. A third, generated, would only drift — and drift in a *description* is the failure mode that matters, because a client developer has no reason to doubt it. `contracts/openapi/README.md` now says that, and what a Phase-2 designed API would put there instead. |
| Off in production, not access-controlled | An unauthenticated map of every endpoint on a live system is reconnaissance. `springdoc.api-docs.enabled=false` has no bypass; a permit rule does. |
| Reachability, and one wiring trap | `BackendApplication` anchors component scanning at an empty package, so `com.workin.backend.openapi` is only reachable because it was added to `LegacyPersistenceConfig`'s scan list. Without that the beans never registered, the groups never appeared, and `/v3/api-docs/client-api` answered **404** while `/v3/api-docs` answered 200 — a failure that looks like a springdoc incompatibility and is not. |
| Verified against the running container | `swagger-config` lists both groups; `client-api` serves **202 paths, 0 ending `.php`**; `auth/login_company` shows `POST` alone, `profile/employee` shows `GET` and `PUT`, `employees/template_excel` keeps every verb; `platform-admin` serves its 4; the ungrouped document serves 206 and is corrected the same way; `/swagger-ui.html` redirects to a 200; and `/apis/api/advances/list` still answers **401** unauthenticated, so nothing was permitted open to make the description reachable. |
| Rollback | Remove the springdoc dependency, `OpenApiConfig`, its test, the gate and its sibling, and the `com.workin.backend.openapi` scan entry. No application behaviour depends on any of it; the description is additive. |
| Related | **D-111** (why the routes carry no method restriction, which is the whole cause), **D-193** (the profiles this switches on and off with), **R-049** (why production stays minimal-surface), `contracts/openapi/README.md`, `docs/operations/flutter-local-integration.md`. |

## D-195: A Nullable Unsigned Column Is Read With A Typed Getter, Never A Cast

| Field | Value |
|---|---|
| Decision | `EmployeeStore` reads `contract_duration_months` with `getLong` plus `wasNull` instead of `(Integer) rs.getObject(...)`, and `Employee.contractDurationMonths` becomes a `Long`. `JdbcBoxedCastCoverageTest` makes the rule general: **no source may cast a `getObject()` result to a box type.** |
| Rollback | Revert the commit **as a whole**, guard included. Restoring the cast while keeping the guard leaves the build red on the guard's own assertion -- which is the guard working, not a rollback. If the typed getter must stay while some other part of the commit is reversed, reverse that part alone; nothing else in it depends on the cast. |
| The defect | `employees.contract_duration_months` is `int(10) unsigned`, whose range does not fit a signed `int`, so MariaDB Connector/J boxes it as a `Long`. The cast threw `ClassCastException` for every row where the column is not null — **HTTP 500 on the whole `/admin/employees` page**, not a degraded cell. |
| Why 2,959 tests did not catch it | Not one fixture in `AdminEmployeesEndToEndTest` ever set the column, so the cast never ran. The regression test sets it to 12 and fails without the fix. **1,448 of the development seed's 3,783 employees carry a value**: the page failed on the first real data it was shown, which is the whole reason a seed derived from production exists. |
| Why the rule is general, not the fix | The box type the driver chooses is a property of the *column* — its width and its signedness — and no reader of Java source can see it. Every such cast is a runtime failure waiting for a column that happens to be unsigned, and it passes review because the Java reads correctly. `getLong`/`getInt`/`getBigDecimal`/`getString` cannot be wrong, and `LegacyJdbcValues` already reads every numeric column that way for the legacy surface. |
| What the rule permits | Probing for null with an untyped `Object x = rs.getObject(...)` and then reading through a typed getter, which is what `AttendanceStore` already does. The cast is the thing that cannot be verified from the source. |
| The guard's own two lessons | It fails on the shape it exists to catch (three positive cases, two negative), and it blanks comments before scanning — the first run flagged **its own javadoc**, which quotes the defect. `check_client_endpoints_drift.py` had already learned that lesson once, where a commented-out constant counted as a live endpoint. |
| How it was found | `deploy/e2e`'s dashboard sweep, which visits every page in the committed manifest against the sanitised seed (**D-196**). No unit test, no review and no manual check had opened that page with real data in it. |
| Rollback | Revert the two source files; the regression test and the coverage guard stand on their own and should not be reverted with them. |
| Related | **D-096** / `LegacyJdbcValues` (the same reading rule, established for the legacy surface), **D-193** (the seed that made the defect visible), **D-196** (the suite that found it), **R-053** (the page's legacy write-side finding, unrelated to this). |

## D-196: The Deployed Stack Is Tested As A Deployment, In A Browser, Over TLS

| Field | Value |
|---|---|
| Decision | `deploy/e2e` drives the **running container** — a real database, a real browser, real TLS — across all three profiles. It covers what a unit or slice test structurally cannot see: a profile that switches the wrong thing on, a cookie the browser refuses, a page that renders a translation key, a refusal that still writes a row, a store that only fails on real data. |
| Why it is not more unit tests | The suite it complements is 2,959 tests and 0 failures, and `/admin/employees` still answered **500** on the first real data it was shown (**D-195**). The defect was invisible to every fixture because no fixture ever set the column. The gap is not coverage of code paths; it is coverage of *values*, *configuration* and *the browser*, and the only honest way to close it is to run the thing. |
| Over TLS, not around the cookie | The admin session cookie is `secure` unconditionally (ADR-0015 prerequisite 6). Relaxing that flag to make a browser test convenient is precisely what the flag prevents, so the integration run puts a real nginx in front with a per-run self-signed certificate and Playwright trusts that one certificate. The connection is TLS; only the certificate is disposable. |
| One configuration property this needed | `server.forward-headers-strategy` becomes settable on `integration`, defaulting to **`none`**. Any shared box that serves the dashboard has TLS in front, and without this every redirect Tomcat builds carries the scheme it sees. It defaults off for R-049's reason: it makes the application trust `X-Forwarded-*`, which is only safe when a proxy is the sole route to the port. |
| Refusals are checked against the row, not the status code | Every case expecting a refusal fingerprints the target row before and after. A 404 proves the caller was told no; it does not prove nothing was written, and those are different claims. |
| The page list is not written here | The dashboard sweep reads `contracts/legacy-dashboard-pages.txt` — the same committed manifest the port is measured against — minus the three pages D-192 put out of scope. A page ported later is covered the day it lands. The client sweep reads `spike/parity-harness/client-endpoints.txt` and `legacy/route-methods.txt`, both of which have their own gates, so neither list can be quietly narrowed to make a run pass. |
| What it does not do | It does not re-check PHP↔Java response parity. `spike/parity-harness/` already does that for every endpoint in the Flutter clients' constants, against both stacks on one database, which is a stronger comparison than this suite could make alone. |
| Findings, all four from running it | **(1)** `/admin/employees` answered 500 on real data (**D-195**). **(2)** On an unprovisioned database the `prod` profile died in `PlatformAdminBootstrap` before `Phase1SchemaCheck` could run, so the operator got a Hibernate stack trace instead of "apply `phase1_extensions.sql`" — the check is now ordered first, with a test. **(3)** Spring Boot was auto-configuring a `user` account and printing its password in the startup log; measured unreachable, and now removed rather than relied upon to stay unreachable. **(4)** The suite's own first configuration set WhatsApp credentials on a box holding a seed derived from real customer data, and its own deployment-shape check caught it. |
| Not wired into CI | CI has no Docker daemon with the image built, and a full run is minutes rather than seconds. This is a pre-cutover and pre-release check a human starts, in the same category as `scripts/verify-platform-admin-flow.sh`. |
| Rollback | Delete `deploy/e2e/` and the one `integration` property. Nothing in the application depends on either. |
| Related | **D-193** (the profiles and the seed it runs against), **D-195** (what it found), **D-194** (the API description it checks is published where it should be and absent where it should not), **R-023** (provisioning), **R-049** (why the forwarded-headers default is off), **ADR-0015** prerequisite 6 (the cookie that forced the TLS proxy). |

## D-197: The Dashboard Uses The Components Its Own Stylesheets Already Carry

| Field | Value |
|---|---|
| Decision | The three list-page components the port re-implemented under invented class names -- the pager, the data table and the row-actions menu -- are replaced by the ones `app-ui.css` and `style.css` already define and that legacy renders. Plus four enhancements the PHP does not have, and a self-hosted Arabic typeface. |
| How it was found | Looking at a screenshot of `/admin/branches`. A 486-row list printed **49 bare page numbers** in a row; the employees list printed **378**. The stylesheets copied verbatim from hr-legacy have carried a windowed pager with ellipses, nav buttons and a page-size selector all along. Nothing used it. |
| The four, and what each cost | **Pager**: 16 templates emitted `<div class="pager"><a class="pager__link">`, a class name no stylesheet defines. **Table**: 19 templates emitted `<table class="data-table">` inside `<div class="data-table-wrap">`; legacy's `tableStart()` emits `class="tbl"` inside `class="table-wrap"`, and `.data-table`/`.data-table-wrap` are styled by **nothing** -- so every list table rendered at browser defaults, 813px wide inside a 1,130px card, with no header band, no row separators and no horizontal scroll container. **Row actions**: 17 cells stacked two or three buttons per row where legacy calls `row_actions_menu()` from 26 call sites; the employees list was 83px per row instead of 52. **Forms**: legacy wraps its form rows in `org-form-grid` -- two columns, collapsing to one below 520px -- and marks the wide ones `org-form-span-2`; the port used **neither class anywhere**, so every field on every form was a full-width band. |
| Why this is fidelity, not taste | Legacy's own `branches/page.php` calls `org_branch_table_row_actions()`, which calls `row_actions_menu()`. The stacked buttons were neither the legacy behaviour nor the better one. The templates already carry the rule that was broken: *"Class names are the dashboard's own; a template that invents one gets no styling, which keeps the copy honest."* |
| Two shared templates, not thirty copies | `pager.jte` and `rowActions.jte`. The pager was the same eighteen-line loop in eighteen places; the menu's trigger, ARIA wiring and the element `row-actions.js` looks for are the part a copy gets wrong. `rowActions` takes its items as a slot rather than a data model, because they are links and forms with different hidden fields and flattening those into a record buys nothing. |
| Four enhancements over the PHP | **First and previous** buttons, which legacy omits (it has only next and last). **A row count** -- "عرض 1–10 من 486" -- so the reader does not have to work out which rows these are. **`per_page` carried in page links**: legacy drops it, so choosing 100 per page and turning the page silently returned you to 10. **Tabular numerals**, so a column of money does not shift as you page. |
| The typeface | `style.css` asks for `'Segoe UI', Tahoma, Arial` -- a stack with **no Arabic face in it**. Off Windows the browser falls back per glyph, which is why the Arabic rendered thin and unevenly beside the Latin. IBM Plex Sans Arabic (SIL OFL 1.1) draws both scripts to one skeleton. It is **self-hosted**, 272 KB for four weights in the arabic and latin subsets: an admin panel should not need a third party to be legible, an air-gapped or blocked deployment renders the same as any other, and no viewer's IP reaches a font CDN. |
| What was deliberately not converted | Four action cells whose content is an inline form rather than a set of commands -- advances' rejection reason, complaints' reply, leave-balances' day count, requests' comment. A text field inside a dropdown is a worse control, not a better one, and turning them into modals is a redesign rather than a repair. |
| Three things the tooling taught | JTE's parser answers a **comment inside a content block** with a `NullPointerException` and no file name, so the notes are hoisted above each call. A **backtick inside a content block** is that block's terminator, so a comment quoting a class name in backticks silently ends the enclosing body and the parser then reads past the end of the file -- reported as "error at line 215" in a 202-line template. And a first attempt at the row-actions conversion treated a cell's first `@if` as the permission guard, which put an opening tag outside the content block and its `@endif` inside; the depth-aware version checks the structure rather than hoping. |
| Verified | Against the running container, page by page, with `deploy/e2e/shoot.mjs`. The employees list went from 1,350px of table to 1,000px; the branches list from 900px to 370px. The platform-admin test package passes. |
| Rollback | Revert the templates; `pager.jte`, `rowActions.jte`, `fonts.css` and the font files are additive and can stay. |
| Related | **D-196** (the suite whose screenshots exposed this), **ADR-0016** (the surface), `docs/legacy/` (the copied stylesheets' provenance). |

## D-198: The Home Page Is An Overview, And A Row Action That Needs Typing Opens A Dialog

| Field | Value |
|---|---|
| Decision | `/admin` renders the overview legacy's `index.php` renders -- counts, two panels and charts, all scoped to the session's company filter -- instead of a "Signed in" panel. And the four list pages that kept a text or number box inside the actions column move that field into a modal, which is what lets them use the ⋮ menu like every other page (**D-197**). |
| Why the home page was a placeholder | It was ported as a route, not as a page: the handler returned a template that stated the session was established. Legacy's home is the first thing anybody sees and shows sixteen counts, a recent-activity feed, the complaint queue and ten charts. The CSS for all of it -- `home-stat-card`, `home-panel`, `home-charts-grid` -- was already copied into this repository and used by nothing, which is the same finding as D-197 one page over. |
| What it shows | Sixteen counts (companies total/active/pending, employees, branches, checked in today, pending requests, open complaints, pending advances, penalties and unapplied penalties, draft payrolls, gross/basic salary totals, this month's net, resignations this year), the last five activities, the four oldest pending complaints, and seven charts. |
| Scoped, and the guard can see it | Every query takes the company from `DashboardSession.companyId()` -- the administrator's filter, `0` meaning every company at once. The service takes the session rather than a company id precisely so `AdminTenantGuardCoverageTest` recognises it as tenant-relevant. Each block also asks for the permission of the page it summarises, which `hasFullAccess` short-circuits today and would not the day the audience widens. |
| Two enhancements over the PHP | Every count with a page behind it is a **link** to that page, filtered -- pending requests goes to the pending requests, unapplied penalties to the unapplied ones. And the daily-attendance chart is bounded at **both** ends: legacy bounds only the lower one, and the development seed's 3,381 future-dated punches drew eighteen months under a title that says "daily". No real attendance is excluded by saying a punch cannot be in the future. |
| Chart.js is self-hosted | Legacy loads it from `cdn.jsdelivr.net`. Same reasoning as the typeface in D-197: an admin panel should not need a third party to draw, and a blocked or air-gapped deployment renders the same as any other. 206 KB, pinned at 4.4.7. |
| The series travel on attributes, not in a script | Each chart card carries `data-labels` and `data-values` as JSON. JTE escapes an attribute value, so a company named `</script>` or `"` is a string in an array and nothing else -- there is no templated JavaScript on the page at all. |
| The dialog, and why those four pages needed one | Advances (a required rejection reason), complaints (a reply and a status), leave balances (two day counts) and requests (an optional comment) each rendered a form **inside every row**, which made the row three lines tall whether or not anyone was editing and is why D-197 could not convert them. Legacy solves it the same way: `hr_list_helper`'s advance and asset menus open a dialog rather than submitting inline. |
| One dialog per page, not one per row | The trigger carries the row's values as `data-dialog-*` and the script copies them into the fields. A 200-row page has one dialog rather than 200. Native `<dialog>`, so the focus trap, Escape, the backdrop and the inertness of the page behind it are the browser's rather than ours. |
| The POST is unchanged | Same action, same hidden fields, same endpoint, same confirm on the destructive ones. What moved is where the field is typed. |
| Rollback | Revert the four templates and `home.jte`; the service, the dialog partial and its script are additive. |
| Related | **D-197** (the components this completes), **D-196** (the suite that renders and checks it), **R-048** (why the complaint status has exactly three choices), **ADR-0016** (the audience). |

## D-199: A Column The Dashboard Shows Is A Column This Port Shows

| Field | Value |
|---|---|
| Decision | Every list page renders the columns production renders, and the cell formatting legacy's helpers apply. Where a column needs a value the store already selected, the value is rendered; where it needs a value the store did not select, the query is widened. |
| How it was found | Not by reading the PHP and not by counting patterns in the source -- both had already given wrong answers. By logging into `workin.company/dashboard`, screenshotting all 32 pages, and then diffing the **rendered `<th>` list** of every table against the same page served by this application. A page is "complete" when the set of headers production shows is a subset of the set this port shows. |
| The measurement | Before: **13 of 24** comparable pages carried every production column. After: **21 of 24**. The remaining three are named below. Five of production's pages have no table at all (`index`, `settings`, `content`, `app_content`, `salary_calculator`) and three are per-admin pages the capture skips (`profile`, `change_password`, `company_settings`). |
| The pattern, and why it is the same finding as D-197 | The port **fetched data and threw it away**. `EmployeeStore` selected `photo_url`, `basic_salary`, `hire_date`, `created_at` and `contract_duration_months`; `employees.jte` rendered none of the five. The row object was right, the query was right, and the cells were missing -- which is the D-197 shape (a component existed and nothing used it) one layer up. |
| What each page gained | **employees** six columns (avatar, basic salary, hire date, created date, work tenure, contract duration) and legacy's two-line cells -- code under the name, department and job title under the branch, which is how fourteen columns fit without scrolling. **requests** four (`from_time`, `to_time`, notes, reply). **banners** three (row number, English title, created date). **faqs** three. **phone_countries** three (row number, flag, sort order). **attendance** three (weekday, row number, signed overtime/shortfall). **advances** two (reason, rejection reason). **complaints** two (reply, date). **administrative_decisions**, **penalties**, **payroll**, **assets**, **shifts** one each. |
| Three queries widened, not worked around | `EmployeeRequest` gained `from_time`/`to_time` and `Banner` gained `created_at`, each read by the store's existing `SELECT *`/`SELECT r.*`. Nothing was computed in the template that the database already knows. |
| The formatting is legacy's, spelled out | `EmployeeDisplay` reproduces `employee_helper.php`: `number_format($n, 0)` for the salary, `substr(..., 0, 10)` for the dates, whole months between hire date and today for the tenure -- decremented when the day of the month has not come round, and a dash rather than a negative for a future hire date, both of which are reachable in the data. `AttendanceDisplay` reproduces `hr_format_attendance_day_cell()`. |
| Why the Arabic inflection is not decoration | `employee_tenure_unit_label()` inflects for the dual and the 3--10 plural: سنة / سنتين / 3 سنين / 11 سنة. A `DayOfWeek` plus a JVM locale would produce whatever the running JDK's CLDR data says, which is neither necessarily these words nor stable across a JDK upgrade -- so the weekday names are a literal array indexed by PHP's `w` (Sunday first), as legacy's are. Getting this wrong is what a native reader notices immediately and a test never does. |
| One enhancement over the PHP | Attendance's overtime column is **signed** and coloured: it is overtime *and* shortfall in one column, and the negative half is the one a payroll clerk is looking for. |
| Free text needed a width | A reason, a reply or a decision body is as long as somebody typed. `.tbl-clip` clips the cell and puts the whole of it in the `title`, as legacy does; without a width they pushed every other column off the table. |
| What is still missing, and why it is a build rather than a repair | **`companies`** shows 10 fewer columns (logo, account manager, phone, activity, title, size, and the employee/branch/department counts, and the created date): the port built ADR-0015's minimal platform-actions list, and production's page is a company **directory** -- three aggregate counts it has no query for. **`notifications`** shows 5 fewer: production lists one row *per recipient* with a read flag, and this port lists one row per notification -- a different data model, not a missing `<th>`. **`payroll`** is a labelling difference only: production prints one "start → end" column where this port prints two, and no value is missing. |
| Verified | The full deployment E2E suite against the integration stack over TLS: 53 tests, 51 passed, 2 skipped, 0 failed. The header diff above was re-measured against the rebuilt image, not against the source. The platform-admin test package passes. |
| Rollback | Revert the templates; `EmployeeDisplay`, `AttendanceDisplay` and the three widened record components are additive and inert without them. |
| Related | **D-197** (the components), **D-198** (the home page and the dialog), **D-192** (the 32/35 page port these columns complete), **ADR-0016** (the surface). |

## D-200: The Two Pages The Column Sweep Could Not Fix, And The Home Page's Last Third

| Field | Value |
|---|---|
| Decision | **D-199** closed 21 of 24 pages by rendering columns whose data was already fetched. The three it could not are closed here by fetching more: `companies` becomes the dashboard's directory, `notifications` lists the table rather than a summary of it, and the home page gains the banner carousel, the three turnover rates and the three charts production draws and this port did not. |
| `companies`: a directory, not a shorter list | The page showed **4** columns against production's **14**. It was not a port of the dashboard page at all -- it was ADR-0015's minimal actions list, built for **D-163**'s workflow (approve, reject, suspend, restore) before ADR-0016 ported the dashboard. It now shows the logo, account holder, phone, activity, title, size, the employee/branch/department counts and the registration date, with legacy's five filters and a pager. |
| Why the narrow interface stayed narrow | `PlatformAdminCompanyDirectory` is deliberately minimal because it is the interface the **lifecycle writes** run through, and it has to be satisfiable over both databases (**D-162**). Widening it would have owed the PostgreSQL profile ten columns and three aggregate counts its schema has no answer for. So the directory is a separate `phase1-mysql` store reached through an `ObjectProvider`, exactly as **D-198** reaches `HomeService`, and the other profile keeps the list it had. |
| The one thing not reproduced | Legacy posts approve, reject and delete **straight from the row menu**, with no second factor. Here every one of them still goes to `/admin/companies/confirm`, which collects a TOTP code and mints an approval bound to that company before anything is applied. The columns are legacy's; the ceremony is ADR-0015's, and that is the deliberate difference. |
| `notifications`: the table, not a summary of it | The page listed this surface's **own** sends, grouped by title (`WHERE notification_type = 'system_broadcast'`). Useful, and not what the dashboard shows: it hid every notification the apps and the HR pages write, and with them the recipient, the read flag and the delete. It now lists the rows, one per recipient, with legacy's four filters, its pager and its delete -- and the delete is behind the same two gates as a send, because a small write to another tenant's data is still a write from a platform session. |
| Three charts and three rates | `chart_att_dept`, `chart_pen_dept` and `chart_workforce` -- the last being the only two-series chart on the page, so it travels beside the single-series map rather than widening it for one caller. The rates are `home_get_turnover_rates()`: monthly, annual, and the 90-day rate for the last three months' hires, which has a different denominator (the cohort, not the workforce) and is the one that says whether new people are staying. They render in their own row: a percentage sitting in a grid of sixteen headcounts reads as one more headcount. |
| The turnover arithmetic is legacy's, including its approximation | A departure is a row whose `is_active` went to 0 inside the window, dated by `updated_at` -- the only date the schema has for it, so a row edited after it was deactivated moves. Reproduced rather than improved: the number on the page has to be the same number. |
| The banner carousel | `home.css` has carried `.home-banner-wrap` and eighteen sibling rules since the stylesheets were copied, and nothing used them -- **D-197**'s finding on the one page D-198 had already rewritten. The rows are the ones `/admin/banners` administers, so an operator sees what the clients see. The CTA is refused rather than rendered dead for every case legacy refuses, which matters here because `banners/list.php` returns the action value **unsanitised** and write-time validation is the only other control. |
| The second factor moved to the topbar (and a regression fixed) | D-198 replaced the "Signed in" panel with the overview and, with it, dropped both the factor state and the enrolment notice. **D-152 migrates existing rows unbound**, so an administrator who lands anywhere but the home page had no way to see why their writes were refused and no link to enrolment. It is now in the topbar on every page -- `.topbar-util` and `.admin-badge`, two more classes the copied stylesheet defines and nothing used -- and links to `/admin/enrol` when the factor is missing. |
| Why that needed a test, not just a fix | A JTE template's parameters come from the model **only at the top level**: a nested `@template` call gets exactly what its caller passes. A page that declares `factorBound` and does not forward it renders a topbar saying "unbound" while the page itself knows otherwise, and both halves look right in isolation. `AdminLayoutWiringTest` now reads every template and fails on a page that does not forward it -- the same shape as R-058's stylesheet rule, and for the same reason. |
| Two assertions re-pointed, and which one changed | `PlatformAdminWebMfaFlowTest` asserted `contains("bound")` and `contains("Set up two-factor authentication")` -- both substrings of the placeholder page D-198 removed, and the second is an English literal on a surface that defaults to Arabic. The **test's premise** changed, not the behaviour: they now assert `data-factor-bound="true"/"false"` and the presence of `href="/admin/enrol"`, which is language-independent and is what "must be able to reach enrolment" actually means. |
| Verified | Full backend suite. The rendered-`<th>` diff re-measured against the rebuilt image: **24 of 24** comparable pages now carry every column production shows. `payroll` prints the period as two columns where production prints one -- no value is missing, and splitting a "start → end" cell is the improvement. |
| Rollback | Revert the templates. `CompanyDirectoryStore`, `CompanyListFilters`, `CompanyRow`, `companyActions.jte` and the new store/service methods are additive; the `factorBound` model attribute is inert without the layout that reads it. |
| Related | **D-199** (the columns this finishes), **D-197**/**D-198** (the components and the page), **D-163** (the company workflow this keeps), **D-162** (why the shared interface stays narrow), **D-152** (the enrolment path), **R-045** (why a broadcast is one statement). |

## D-201: The Stored Uploads Are Served, And Only The Types This System Writes

| Field | Value |
|---|---|
| Decision | This application serves `/uploads/**` from the configured upload path, publicly, as the frozen stack does -- and refuses any extension it does not itself write. |
| Why it needed deciding at all | It did not look like a decision. `LegacyFileUploads` wrote the file and returned the right URL; nothing answered it (**R-068**). Every logo, photo, banner and document in the system was a 404, which breaks **D-111** on the most visible surface there is. |
| Public, and not by omission | The clients fetch these URLs with **no session**, from URLs the API hands them in ordinary response bodies. Requiring authentication would break every client -- the change D-111 forbids. What changes is that it is now written down: an explicit `permitAll` chain with a `securityMatcher` on the prefix, rather than a path that matched no chain and so had neither a rule nor a security header. |
| The extension allowlist | `jpg`, `jpeg`, `png`, `webp`, `pdf` -- the five **D-154** derives from the sniffed content type. Frozen PHP names a stored file from the client-supplied filename, so its `/uploads` tree can hold a file whose extension has nothing to do with its bytes; serving that inline from this origin would make a planted `.html` script on the admin's own origin. Nothing legitimate is refused, because every file this port writes has one of the five. |
| Not registered when the files are elsewhere | `app.legacy-uploads.url` may be an absolute URL -- a CDN, or a separate media host. Then the files are not this application's to serve and it claims no route, rather than registering a handler for a prefix that will never be requested. |
| The fallback is client-side, and why | Legacy checks `is_file()` before using a stored logo and draws initials when it is missing. That check is not available here: the stored value is a URL which may not be this application's to stat. So `img-fallback.js` swaps a failed image for the placeholder it would have had -- one capturing listener, since `error` on an `<img>` does not bubble, and it runs only for images that actually fail. |
| Verified | `LegacyUploadServingTest` over real HTTP: served without a session, a planted `.html` refused, three traversal shapes contained, a missing file a 404. Confirmed to fail without the handler -- the four tests were run against a disabled registration and the serving one failed, which is the only way to know a route test is testing the route. |
| Rollback | Delete the configuration class and the security chain; nothing else reads them. That restores the 404s. |
| Related | **R-068** (the defect), **D-111** (the invariant), **D-154** (the naming rule the allowlist rests on), **D-200** (the sweep that found it). |

## D-202: What The Independent Review Found On PR 176, And What Each One Cost

| Field | Value |
|---|---|
| Decision | All fifteen findings from `chatgpt-codex-connector[bot]`'s two rounds on PR 176 are fixed in code, except one that a later commit in the same PR had already fixed and which is answered on its thread. None was waved off. |
| Why this is a log entry | Two of them were **defects the port introduced and every test passed over**, and both are the kind that recur. Recording the finding without recording why the tests missed it leaves the second half of the lesson on a pull request. |
| **The one I introduced, and the claim that was false** | `LegacyUploadServing` (D-201) served five extensions and its javadoc said "nothing legitimate is refused: every file this system writes has one of these five." Wrong. `LegacyGuideVideoService` references `mp4`, `webm`, `mov` and `m4v`, so **every guide video the API listed answered 404** -- the same defect D-201 had just fixed, one directory over. The fix is not a longer list: the served set is now **composed** from the two places that produce a URL, and `LegacyUploadServingTest` fails if a writer gains a type the handler cannot serve. A hand-kept third list is what came apart. |
| **The clock, in nine places** | `date('Y-m-d')` in this product is neither UTC nor the JVM default: PHP sets the timezone from `configs.is_daylight_saving`, and `LegacySessionDataSource` sets the same offset on every legacy connection, so `CURDATE()` answers in it too (**D-083**, **D-099**). Nine admin sites read `LocalDate.now()` and therefore disagreed with their own SQL for two or three hours a day -- an employee hired late in the evening filed under yesterday, a tenure ticking over early, a turnover window starting on the wrong date. Templates now take `today` from `AdminViewModelAdvice`; services inject `LegacyClock`; `AdminClockUsageTest` fails on a new one. |
| Arithmetic that already existed | The turnover rates re-derived `rateFromCounts`, the two-place rounding and the three-month step, and got the last one wrong: `strtotime('-3 months')` keeps the day and lets it **roll** -- 31 May becomes 3 March, where `minusMonths` clamps to 28 February and quietly widens the cohort by three days. `LegacyTurnover` had all three, tested. They are now shared; only the SQL is duplicated, because that class requires a company and the platform view has none -- which is why legacy duplicates it too. |
| A menu nobody could open without a mouse | `row-actions.js` binds opening to `mousedown`, which a keyboard never fires: Enter on a `<button>` dispatches `click` alone, and the `click` handler returned early for triggers. **D-197** converted 26 stacked-button cells to that component, so the port regressed keyboard access against its own previous state while matching legacy exactly. The copied file is now one branch longer -- `detail === 0`, the browser's own signal for a keyboard activation -- so the pointer path is untouched. |
| Three that were correctness, not polish | A dialog reused across rows was **not reset**, so a rejection reason typed for one employee and cancelled was still in the box for the next. The FAQ page's two tables emit ids from independent sequences and both used the default prefix, so `row-actions-menu-1` appeared twice and each trigger's `aria-controls` named the other's menu as often as its own. And the page-size form carried only the shared filters, so changing the size on a filtered complaints, employees or payroll list silently widened the result. |
| Two of presentation | The pager printed `showing 981–22 of 22` under an empty table, because `dbPaginate` keeps the requested offset while clamping only the reported page (**DashboardPage**'s own note) -- the range now shows only when there are rows, and the total always. And the size selector listed four presets while the server accepts 1 to 200, so a bookmarked `?per_page=200` served 200 rows under a box reading "10"; the active size joins the list when it is not a preset. |
| Empty menus | Six pages rendered a ⋮ trigger whose only item was guarded, so a read-only administrator opened a popover onto nothing -- and three more put a lone em dash **inside** the menu, which is a dash the reader has to open a menu to see. The trigger now takes the same guard its items have, and the dash sits in the cell. |
| One shared signal instead of two hacks | `setting-templates.js` hijacks clicks in the capture phase and calls `stopPropagation`, so `row-actions.js` never closed the menu and the modal opened over one still portaled to `<body>`. `row-dialog.js` had the same problem and solved it with a synthetic `document.body.click()`. Both now dispatch `row-actions:close`, which the file that owns the portaling listens for. |
| Two licences | The vendored fonts shipped a notice **naming** OFL 1.1 and linking to it; the SIL licence permits redistribution only with the licence text included. Chart.js shipped its banner and no MIT text. Both are now vendored in full beside the files they cover. |
| The one answered rather than changed | Inline editors clipped in the 52px actions column, on complaints, advances, leave balances and requests. True of **D-197**'s commit and fixed by **D-198** two commits later, which moved all four into a dialog; measured as zero remaining inputs in those cells. |
| What the review cost, and what it was worth | Fifteen findings, thirteen of them real defects in code that had a green full suite and a green browser suite behind it. Two were mine from this session. The gate is `AGENTS.md`'s for a reason. |
| Guarded by | `AdminClockUsageTest` (no page reads the JVM clock), `LegacyUploadServingTest.everyExtensionAWriterCanProduceIsServed`, and two new browser tests -- the pager's windowing, carried filters, out-of-range summary and size selector, and the menu's keyboard operation, unique ids and absence of empty popovers. |
| Related | **D-197**, **D-198**, **D-199**, **D-200**, **D-201**, **D-083**/**D-099** (the clock), **D-121** (the gate). |

## D-203: MySQL Is The Production Database; The PostgreSQL Half Is Removed

| Field | Value |
|---|---|
| Decision | ADR-0017. The owner's instruction of 2026-09-07 -- "we don't use PostgreSQL, so remove it from any place it exists; we use normal MySQL, because it's my prod DB now and I will convert it to be on a VPS instead of the Hostinger DB" -- is carried out in full: the PostgreSQL domain, Flyway, the ETL, the tenant chain and the profile split are deleted, not disabled. |
| Why deleted rather than disabled | A dormant half is a half somebody has to keep compiling, keep scanning for, and keep explaining. The profile split existed only to keep the two apart -- `BackendApplication` anchored its scan at an empty package for it -- and with one substrate that machinery guards nothing. The history holds every file; a revert restores all of it. |
| What went | Eleven business packages under `com.workin.backend` (the Phase 2 JPA domain, zero importers from the MySQL side), `identity` bar `JwtService`, `tenancy` bar the four types the legacy tenant filter uses, `authorization`'s enforcement half, `PostgresPersistenceConfig`, `SuperuserStartupCheck`, the tenant `SecurityFilterChain` and its filter, `db/migration/**`, the `phase2Test` source set, `scripts/etl/`, three Phase 2 guard scripts and their tests, five CI steps, and 79 `@Profile("phase1-mysql")` annotations that gated the only mode there is. |
| What stayed, and why | `JwtService`: the legacy filter accepts a transitional Java-issued token beside PHP's format and `LegacyLoginService` issues it -- client contract, D-111. `TenantScope` and `NoTenantScopeException`: the legacy Hibernate tenant filter is built on them. `PermissionKeys`/`RequiresPermission`: referenced by the i18n catalogue and the use-case annotations every controller declares. `docs/migration/**`: history, left in place. |
| The tests | 49 classes extended `AbstractIntegrationTest`, whose database was PostgreSQL. The base now takes a fresh MariaDB database from `LegacyMariaDb` (PR 177); the 15 that injected `flywayDataSource` inject `legacyDataSource`, and their SQL needed no change -- `INSERT ... RETURNING id` and `TRUE` are MariaDB 11.8's too. Deleted with their domain: the identity, RLS and membership tests, `PermissionEvaluationTest`, `ResourceScopeServiceTest`, `AuthorizationEnforcementFlowTest`, `ProfileCoverageArchTest`, `SuperuserStartupCheckTest`, `PermissionCatalogSyncTest` (its catalogue was a PostgreSQL table), and `I18nFlowTest`, whose every case began by registering a company in the deleted domain -- the i18n behaviour it covered is asserted by the legacy end-to-end tests that post Arabic through real forms. `PlatformAdminDomainSeparationTest` is rewritten against the client domain that remains: a PHP-format token on the admin API, an admin token on a client route, both 401. |
| Rollback | `git revert` of the carrying commit. No data moved. |
| Removed with it | `LegacyAdapterIsolationTest`, which asserted that the legacy adapter stayed outside the PostgreSQL application's component-scan root. With one scan root (`com.workin`) covering both, the invariant it guarded no longer exists; a narrowed scan root would fail every integration test's context, not pass silently. `scripts/verify-platform-admin-flow.sh`, whose fixtures were PostgreSQL SQL; `PlatformAdminFullFlowTest` exercises the same journey against MariaDB. |
| Related | **ADR-0017**, **ADR-0004** and **ADR-0011** (superseded), **ADR-0012** (its controls are now permanent), **ADR-0013** (amended), **R-040** (closed as moot), **D-043**, **D-111**. |

## D-204: A Step-Up Approval Is Locked On Read, Because MariaDB Answers A Race With An Error

| Field | Value |
|---|---|
| Decision | `PlatformAdminStepUpService.consume()` reads the approval row `FOR UPDATE`. The conditional `UPDATE ... WHERE consumed_at IS NULL` stays as the last word on single use; it simply never conflicts any more. |
| How it was found | Moving the platform-admin tests from PostgreSQL to MariaDB (ADR-0017). `concurrentConsumptionSpendsTheApprovalExactlyOnce` fires eight consumptions of one approval at once and expects one success and seven refusals. On PostgreSQL the seven losers' `UPDATE` matched zero rows. On MariaDB 11.8, under its default snapshot isolation, a loser's `UPDATE` raised *"Record has changed since last read in table 'platform_admin_step_up_approvals'; try restarting transaction"* instead. |
| Why it is a production defect and not a test artefact | The service ran that way in production too. Two submits of the same approval within the same instant -- a double-click on "apply", a retried request -- would have given the second a 500 rather than a refusal. The invariant held (exactly one consumption), but the loser failed loudly inside a transaction it shared with the action, which is the worst of the available outcomes. |
| Why a lock rather than catching the exception | Catching it inside `consume()` cannot help: the method joins the caller's transaction (`MANDATORY`, so that an action and its approval commit or roll back together -- ADR-0015 prerequisite 2), and the conflict has already marked that transaction rollback-only; the caller would fail at commit with an `UnexpectedRollbackException` instead. Running the consumption in its own transaction would fix the error by breaking the property the joined transaction exists for. The lock keeps both: a loser waits on the read until the winner commits, reads the consumed row, and refuses at the existing check. |
| Verified | `PlatformAdminStepUpServiceTest` on MariaDB, all cases including the eight-way race; `PlatformAdminFullFlowTest` and `PlatformAdminCompanyActionTest`, which spend approvals through the real endpoints. |
| Related | **ADR-0015** prerequisite 2, **ADR-0017**, **D-203**. |

## D-205: The Dashboard Signs In The Way PHP's Does

| Field | Value |
|---|---|
| Decision | ADR-0018. One administrator, one password from `APP_PLATFORM_ADMIN_PASSWORD`, PHP's login page, Java's guards behind it. TOTP, enrolment, step-up approvals, the bearer API and the `factorBound` gate are removed. |
| The question, and the answer | "For authentication, see in PHP -- why login with phone and password? I need to log in to the web admin like PHP but with Java guard." Read PHP first: it has **no TOTP anywhere**; production's login is one shared password and no phone. So "like PHP" could mean three things, and the owner was offered all three -- password then TOTP (recommended), password only, or keep accounts and restyle the page -- and chose **password only**. |
| What "Java guard" means here | Everything PHP's login lacks that does not change its shape: a bcrypt hash instead of `hash_equals` on a constant; a miss budget per **client address** rather than PHP's per-session lock, which a new session walks past; session rotation; the Secure/HttpOnly/SameSite cookie; CSRF on every write; per-request revalidation; 30-minute idle and 8-hour absolute limits where PHP allows thirty days; an audit row for every login, miss and logout. |
| Why per client, not per account | There is one account. A budget on it would be a fifteen-minute lockout anyone could impose from anywhere, indefinitely. |
| Why the password is a row and not a constant | The audit trail and the session index hang off `platform_admins.id`, and a hash in a row is what lets the login run a comparison even when nothing is provisioned, so "not set up" costs the same as "wrong". The bootstrap re-encodes on a restart where the configured value changed; rotation is a change and a restart, as in PHP. |
| Removed, and how much | `mfa` and `stepup` packages, the bearer API (`PlatformAdminAuthController`, JWTs, refresh-token families, `PlatformAdminController`), the enrolment and MFA pages, the company confirm/apply ceremony, four tables from `phase1_extensions.sql`, 76 `gate(factorBound)` call sites in 20 services, the flag in 24 templates and 25 controllers, and 40 test classes' worth of ceremony -- 23 of them by one migration that replaces the enrol-login-code sequence with the password. |
| The cost, stated | The audit records the role, not the person. **R-069.** |
| A defect the merge produced, and its gate | Bringing the dashboard (PR 176) together with the PostgreSQL removal (PR 178) left **three beans still annotated `@Profile("phase1-mysql")`** -- `HomeService`, `HomeStore`, `CompanyDirectoryStore` -- while nothing activates that profile any more. In a real deployment the home page's summary and the company directory would have been absent, each falling back to a branch that renders an empty panel and says nothing about why. The suite did not catch it because **82 test classes still declared `@ActiveProfiles("phase1-mysql")`**, so the beans existed in tests and nowhere else. Fixed by removing the annotations, the dead fallbacks in both controllers and both templates, and the profile from every test; `ProfileFreeWiringTest` asserts both halves, because either half alone lets it back in. |
| Verified | Full backend suite on the merged line (dashboard + MySQL-only + this). The login classes -- full flow, session, throttle, audit, bootstrap, the MySQL admin journey -- rewritten against the new model. |
| Related | **ADR-0018**, **ADR-0015** (superseded in part), **D-152** (its unbound-row concern is moot: there is no factor to bind), **R-049**, **R-069**. |

## D-206: Production Terminates TLS At Caddy, And The Application Port Stops Being Published

| Field | Value |
|---|---|
| Decision | `deploy/compose.tls.yaml` adds Caddy in front of the production stack and **removes the application's published port** (`ports: !override []`). Production is brought up as `-f compose.prod.yaml -f compose.tls.yaml`; the pair is the deployment, not an option. |
| The gap it closes | D-193 shipped the image and the three profiles and then said TLS "terminates at a reverse proxy on the host" -- a proxy that did not exist in any file. Two things depended on it and neither was runnable: the dashboard's session cookie is `Secure` unconditionally (ADR-0015 prerequisite 6), so a browser on plain HTTP accepts the login and discards the cookie; and `application-prod.properties` sets `server.forward-headers-strategy=native`, which makes the application believe `X-Forwarded-For`. |
| Why unpublishing the port is half the decision | `native` is safe only when the proxy is the **sole** route. `compose.prod.yaml` binds the port to loopback, which is not the internet but is still a second door -- and anything on the host, including a container on a shared network, can knock on it with a forged header and spend another caller's rate limit or the login's miss budget (**R-049**). The overlay closes it, and `!override` rather than a second entry because compose merges port lists. |
| Why Caddy rather than the nginx the E2E run uses | Certificates. The E2E proxy serves a self-signed certificate generated per run, which is right for a test and wrong for a VPS. Caddy obtains and renews from a certificate authority by itself, so a production deployment has no renewal for an operator to remember -- the failure mode being a site that goes dark months later, on a weekend. `TLS_EMAIL` is required rather than defaulted for the same reason: it is where renewal failures are sent. |
| Rehearsed, not asserted | Brought up on this machine from `compose.prod.yaml + compose.tls.yaml` with `APP_DOMAIN=localhost` (Caddy's internal CA), the seed restored **by hand** as production takes it, then: `/actuator/health`, `/admin/login` and `/apis/api/configs/get` all 200 over HTTPS; `http://` answers 308 to `https://`; the application's own port answers nothing; and `scripts/verify-admin-login.sh` passes **14 checks** through the proxy, including the three cookie flags that only hold behind TLS. |
| What the rehearsal caught | Three things, each of which would have been a first-deployment failure: a stale local image reused because compose was not given `--build`; the rehearsal adopting an existing `workin-prod` project's containers and volume, which is the isolation defect the review raised against the E2E runner, met in person; and `email {$TLS_EMAIL}` failing to parse when compose passed the variable as empty rather than leaving it unset. |
| Not in scope | Rate limiting at the edge, a WAF, and HTTP/3 tuning. R-049's per-IP cap stays an application concern; what changes here is that the address it reads is now set by something the operator controls. |
| Related | **D-193** (the packaging this completes), **R-049**, **ADR-0015** prerequisite 6, **ADR-0017** (one database, one profile meaning), `deploy/README.md`, `docs/operations/running-the-backend.md`. |

## D-207: The Cutover Stack Performs Administrative Actions; Prerequisite 7 Is The Operator's, Not The Application's

| Field | Value |
|---|---|
| Decision | `deploy/compose.remote-db.yaml` defaults `APP_PLATFORM_ADMIN_ACTIONS_ENABLED` to **true**. The dashboard approves, rejects, suspends and restores exactly as `dashboard/pages/companies/` does in PHP. `ADMIN_ACTIONS_ENABLED=false` still renders those pages read-only for anyone who wants that. |
| What changed and what did not | Only a compose default. `app.platform-admin.actions.enabled` still defaults to **false** in `application.properties` and `application-prod.properties`, still pinned there by a test, and the `admin_actions_disabled` banner and every service-side refusal stay exactly as they were. ADR-0015 is not amended: turning the flag on once the PHP surface is unreachable is the cutover step prerequisite 7 always described, and this is a deployment taking it. |
| Why | The stack it belongs to exists to run the port against the live database, and the operator's procedure is to close the PHP panel when they open this one. A read-only dashboard against real data was the right default for *looking*; it is the wrong one for a surface that is replacing PHP's panel rather than shadowing it, and it made the port look like a downgrade on the pages where legacy has always been able to act. |
| The risk it accepts, stated plainly | Nothing in the application can verify that PHP is unreachable, and both surfaces write the same rows with no knowledge of each other. Two panels open at once means two administrators can act on one company with no interlock -- last write wins, and the audit trail records only this side. The precondition is the operator's to hold; the compose header and `env.remote-db.example` now say so where the value is set rather than only in an ADR. |
| Rollback | `ADMIN_ACTIONS_ENABLED=false` and a restart. The pages return to read-only; nothing needs undoing in the database, because a refused action never wrote and `recordAction(...)` is `MANDATORY`, so an action that committed has its audit row. |
| Related | **ADR-0015** prerequisite 7 and its Implementation Status section, **ADR-0018** (one administrator, one password), **D-163** (what the company workflow covers), **D-206**, `docs/operations/checking-against-the-live-database.md`. |

## D-208: The Dashboard Shell Is Responsive; The Sidebar Becomes A Drawer Below 1024px

| Field | Value |
|---|---|
| Decision | `app-responsive.css` and `nav-drawer.js` join the shared set in `layout.jte`. Below **1024px** the sidebar leaves the flow and becomes an off-canvas drawer over a backdrop, opened by a button in the topbar; below **768px** the topbar and content give up desktop padding; below **560px** grids collapse to one column and controls reach 44px. Three breakpoints, and no page template changes. |
| The defect | The sidebar was `width: 248px; position: sticky; height: 100vh` with **no width-based media query anywhere in `sidebar.css`**, and `sidebar.js` only persisted group state and scroll -- there was no toggle in the codebase at all. On a 375px phone the navigation took two thirds of the viewport and could not be dismissed, on all forty pages. Every page inherited it from the shell, which is also why one stylesheet fixes all of them. |
| Why not a framework | The complaint reads as "the UI is dated", and the temptation is a JavaScript frontend. The dashboard is server-rendered forms behind a Spring Session cookie and CSRF tokens, with no JSON API for the admin surface at all; replacing JTE means inventing that API and a second authentication model, reopening ADR-0015's ground, and discarding `AdminLayoutWiringTest`, `AdminNavTest`, `DashboardAccess` and the tenant guards. `layout.jte` is class-for-class with `dashboard/includes/layout.php` so the copied stylesheets apply unchanged, which is what makes parity checkable. The gap here was CSS, and CSS is what closes it. |
| Three breakpoints, deliberately | The copied sheets carry twelve different ad-hoc widths between them (1100, 1024, 1000, 960, 900, 768, 720, 640, 560, 520). This adds none: 1024 / 768 / 560, named at the top of the file, and `nav-drawer.js` asks `matchMedia` for the 1024 one rather than measuring the window, so the script and the sheet cannot disagree. |
| Accessibility, not decoration | A skip link ahead of forty navigation links; `aria-expanded`/`aria-controls` on the toggle; Escape and backdrop-click to close; a focus trap while the drawer is modal, with focus restored to the button only when the user closed it deliberately; `:focus-visible` rings on the shell's controls; 44px targets under `pointer: coarse`; `100dvh` so mobile browser chrome does not clip the drawer; and every transition dropped under `prefers-reduced-motion`. |
| RTL | The drawer leaves by the edge it lives on, which swaps with `dir`. `inset-inline-start` positions it and a `html[dir="rtl"]` rule flips the translate; the open state is a three-class selector so it outranks that flip in both directions. Arabic is the default language of this surface, so the RTL case is the one that had to be right first. |
| Related | **R-058** (the shell is the one place cross-cutting view concerns belong), **ADR-0009**, `AdminLayoutWiringTest`, `AdminTemplateMessageKeyTest` (the two new keys), `docs/operations/checking-against-the-live-database.md`. |

## D-209: A Page's Stylesheets Are Checked Against The Classes It Renders, Not Only Against Existing

| Field | Value |
|---|---|
| Decision | `AdminPageStylesheetTest` asserts that every statically-resolvable class an admin page renders is defined by a stylesheet that page loads. Six pages were wrong about that and all six were green. The classes legacy does not style either are listed explicitly, one at a time, rather than silently tolerated. |
| The gap it closes | `AdminLayoutWiringTest` proves a page names **a** stylesheet and that the file exists. Neither fact decides how a page looks. Nothing compared the sheets a page names against the classes its markup actually uses, and unstyled markup renders rather than throwing -- so a page missing two of its three stylesheets looks like a page with an opinion about spacing. |
| What it found | `attendance` named one of the three `pages/attendance/page.php:134` names. Both detail pages named none, on a recorded rationale that legacy named none either -- legacy serves them under a `page.php` naming four (`employees/page.php:234`) and two (`companies/page.php:139`). `branches` reached for `account-card`, which belongs to `change_password`'s `account-pages.css` and was never copied, while `org-form.css` -- which that page already loads -- carries the whole `branch-qr-*` vocabulary `_branch_qr_modal.php` uses. Five list pages wore `login-remember`, a class legacy uses only on its login page. `sessions` and `company-detail` were built from `card`/`sub`/`facts`/`notice`/`linkish`/`primary`, which no stylesheet defines here **or in legacy**. |
| Why an allowlist rather than a fix | Ten classes -- `account-form`, `org-form`, `pager`, `row-dialog__form` among them -- are undefined in legacy too. They are structural hooks the PHP carries for its own scripts and markup shape, and copying the dashboard faithfully includes copying the fact that nothing styles them. Each was checked against `hr-legacy/dashboard/**/*.css` individually. Removing one would be a divergence, not a cleanup. |
| The hole, stated | A `class` attribute holding an expression is skipped, because its value is not knowable from the template. The alternative is a test that guesses. `class="btn btn-${variant}"` is therefore unchecked, and that is the known limit of this gate. |
| How the pages were fixed | Two ways, and never by writing new CSS: name the stylesheet legacy names (`attendance`, both detail pages), or use the class legacy uses (`branches`' QR panel onto `branch-qr-*`, the five list pages onto the bare `<label>` inside `.form-row` that legacy has, `sessions` and `company-detail` onto the shared `data-table-card`/`stats-grid`/`stat-card`/`btn` vocabulary `companies/detail.php` itself uses). |
| Related | **D-208**, **R-058**, `AdminLayoutWiringTest` (whose `NO_PAGE_STYLES` rationale was wrong about the detail pages and is corrected), `AdminTemplateMessageKeyTest` (the same shape of gate for labels). |

## D-210: An Action A Controller Handles Must Be One Its Page Can Trigger

| Field | Value |
|---|---|
| Decision | `AdminActionReachabilityTest` asserts that every `action` an admin controller switches on is named by the template it renders. Eleven were not, across eight pages; they are listed by name in `UNREACHABLE` rather than counted, so offering one is a visible deletion and none can quietly be added. `phone_countries` was fixed rather than listed. |
| The gap it closes | A dashboard page posts an `action` and the controller switches on it. Nothing checked the two agree, and they had drifted in exactly one direction: the controller implements `case "edit"` -- guarded, audited, tested by posting the action directly -- and the template renders no way to trigger it. The row could be deleted but never corrected. Invisible from both sides: the controller's tests pass because they post the action themselves, and the page renders without complaint because nothing is missing from it, only absent. |
| What it found | `advances` (`edit_advance`), `assets` (`edit_asset`), `attendance` (`delete_range`, `edit_attendance`), `banners` (`edit`), `complaints` (`set_status`), `faqs` (`edit_category`, `edit_item`), `penalties` (`edit_penalty`), `settings` (`edit_option`) and `phone_countries` (`edit`). Nine of the eleven are edits legacy offers on the same page, which is why they read as a gap and not a decision -- legacy's complaints page posts `set_status` twice, and this one never posts it at all. |
| How `phone_countries` was fixed | Entirely in the template. `AdminPhoneCountriesController` has had `case "edit" -> this.service.update(...)` all along; what was missing was a trigger and a form. The row action now carries the row's values into the surface's own `rowDialog`, which is the mechanism four other pages already use. No service, store or controller change -- which is the shape most of the remaining ten have too. |
| Why the reverse direction needs no gate | An action a template posts and no controller handles falls to the `default` branch, which every one of these controllers answers with "not found" rather than throwing. That failure is visible the first time anyone clicks it; this one never is. |
| What this says about the other gates | `AdminDashboardPageInventoryTest` proves every page is served and `AdminPageStylesheetTest` proves its classes are styled. Neither asks whether the page can do what the page is for, and all eleven of these passed both. Page coverage and capability coverage are different measurements, and only the first had been taken. |
| Closed | All eleven are offered now and `UNREACHABLE` is empty. Ten were template-only -- the trigger and the form, no service, store or controller change -- and `banners` needed the controller to load the row being edited, because `crudOpenEdit` fills a field named `title_ar` from `data-title-ar` and that form's fields are camelCase. The three guards legacy applies are carried with them: a penalty deducted by payroll, a returned asset and a decided advance stay uneditable. |
| The gate's own two bugs, found by trusting it too far | `settings`' `edit_option` was never unreachable. `settings-templates.jte` renders `value="add_option"` and `setting-templates.js:54` switches that field to `edit_option`, which is exactly how legacy does it -- but the gate read only `.jte` files, and compared against `"..."` while scripts quote with `'...'`. Two mistakes stacked into a false positive that would have produced a second control for a working capability. It reads each page's declared scripts now, and accepts either quote. A gate that has only ever been run against known-bad input has not been tested. |
| Related | **D-209** (the stylesheet gate, and its blind spot), **D-192** (page coverage complete at 32 of 35), **ADR-0016**, `AdminDashboardPageInventoryTest`. |

## D-211: The Dashboard Creates And Edits Companies, Which Provisions A Login

| Field | Value |
|---|---|
| Decision | `companies` gains add and edit, reproducing `company_admin_create()` and `company_admin_update()` (company_helper.php:234 and :371). This closes the last capability legacy's companies page has and this one did not; **D-192's three unported pages are unaffected** -- those are gated away from an administrator, and this one never was. |
| Why it was left until last | It is the only administrative write on this surface that **provisions a credential**: a created company is `status=active`, `otp_verified=1`, `profile_completed=1` with a bcrypt password its owner logs in with. Verified end to end rather than assumed -- a company created through the dashboard was logged in through `auth/login_company`, which is also what proves the hash parity: Spring writes `$2a$` and legacy's `password_verify` accepts it. |
| The order is the specification | `CompanyForm` reproduces legacy's rule order, not merely its rules. The country code is checked before the names, the phone before the password, the password before the company name, and each returns a different message -- so a submission breaking two rules must be told about the first. Ten tests assert **which** key comes back; a test that only asserted rejection would pass with the rules in any order. |
| Two details that are easy to get backwards | The phone is stored **local**: `company_normalize_phone()` is `phone_digits_only()` and does not prepend the dial code, so `01099887766` is what the row holds and what the client logs in with. And an absent `company_code` field stays null rather than becoming empty -- PHP's `array_key_exists`, which is the difference between leaving a column alone and clearing it. |
| What an edit must not destroy | A blank password leaves the stored hash; no upload leaves the stored logo. Both were verified after an edit that changed only the name and address: the login still succeeded and the logo column was still set. The main branch follows the company's name and address, and is inserted when the company has none -- `company_admin_update()` does the same, taking the lowest branch id as the main one. |
| Audited, unlike legacy | `COMPANY_CREATED` and `COMPANY_UPDATED` carry the structured target the lifecycle actions already use. Legacy performs both writes with no record at all; creating a login with no audit row is the gap `hr-legacy#11` describes one level up. |
| The deployment condition, and how it is met | Uploads are written to `app.legacy-uploads.path` and referenced by `app.legacy-uploads.url`. Where those name different storage a logo lands on one host and is served from another, and it fails silently: the write succeeds and only a later GET 404s. `compose.remote-db.yaml` had the URL and no path at all, so files went into the container and did not outlive it. It now takes `UPLOADS_PATH` and bind-mounts it at `/app/uploads`, so the operator points it at the directory the PHP host serves -- reachable from the Java host however they choose to mount it. The check is one upload and one GET of the URL the row then holds. |
| Found by opening it | The edit form first rendered empty: correct action and id, logo and password correctly optional, and every text field blank, so changing an address meant retyping the company. `_company_form.php` prefills from the row and this now does too, selects included. No test would have caught it -- the form was well-formed and posted correctly. |
| Related | **D-192** (the three pages that stay unported, and why this is not one of them), **D-163** (the lifecycle actions this joins), **ADR-0015** prerequisite 7, **ADR-0016**, `AdminPageStylesheetTest` (which caught `modal--org-form` on a page that loads `company-add.css`). |

## D-212: The Content Pane Stops Being A Containing Block, And The Modals Fit A Phone

| Field | Value |
|---|---|
| Decision | `.content`'s entrance animation loses its translate, the modal footer becomes sticky, and the modals, form grids and filter bars gain the small-screen rules the copied sheets never had. All of it additive, in `admin-extra.css` and `app-responsive.css`; no copied stylesheet is edited. |
| The defect, and why nothing saw it | `appFadeUp`'s last keyframe is `transform: translateY(0)` rather than `none`, and `animation-fill-mode: both` keeps it. A transformed ancestor is the containing block for every `position: fixed` descendant, so `.modal-bg` -- written `position: fixed; inset: 0` -- was laid out against the content pane. Measured on the company form: the backdrop covered 74px→961px of a 927px viewport instead of all of it, so **the sidebar and topbar were never dimmed and stayed clickable behind a modal dialog**; the modal's bottom sat 34px below the fold; and its save button was off-screen at 934. Every modal on the surface, and no test can see a containing block. |
| An improvement on legacy, not a parity fix | Legacy carries the same animation, with `transform: translateY(0)` in the same last keyframe, so its dashboard has the same mispositioned modals. This diverges deliberately. The fade stays; only the translate goes, because the translate is the part that costs a containing block, and overriding `animation-name` alone leaves the duration, easing, delay and fill exactly as the copy set them. |
| The footer, and a mistake worth recording | `.modal` is the scroll container (`max-height: 90vh; overflow-y: auto`), so a long form pushed its own save button below the fold -- reachable only by scrolling a box nobody expects to scroll. The footer is `position: sticky` now, which needs no structure and so holds for every form regardless of what it wraps its fields in. It was first written `bottom: -28px`, to "clear the modal's padding"; sticky resolves against the padding box, so that pushed it *below* the visible edge. `bottom: 0`. |
| Small screens | Modals become sheets (padding 28px→20px: on a 390px screen the old padding left about 330 for the field). Every form grid collapses to one column -- `org-form-grid`, `company-add-grid`, `emp-salary-grid`, `dept-branches-grid`, `app-content-lang-grid` -- and `span-2` stops spanning. Filter fields take the full width, because a date input squeezed to 90px shows neither the date nor its picker. File inputs are capped so the company form's two do not push the sheet sideways. |
| A wide table now says so | `.table-wrap` scrolls sideways and gave no sign of it; the employees table is fourteen columns in a 390px window. Edge shading painted by the wrap itself, `background-attachment: local` for the pair that scroll, so it fades exactly when there is nothing further that way. No script, no markup, and nothing at all on a table that already fits. |
| Verified by measuring, not by looking | Before: backdrop top 74 height 887, modal bottom 961, footer bottom 934, viewport 927. After: backdrop top 0 height 927, modal bottom 907, footer bottom 880. The small-screen rules were confirmed by lifting the 768px block out of its media query, because the harness reports a viewport resize and does not perform one -- so the breakpoint itself is still unproven, and that is the known gap. |
| Related | **D-208** (the responsive shell this extends), **D-209**, `admin-extra.css`'s own header on why the port's additions live apart from the copies. |

## D-213: The Open-Session Deadline Follows The Baseline Commit, Not The One Before It

| Field | Value |
|---|---|
| Decision | `LegacyAttendanceSessions` is corrected to hr-legacy `505004f`, the commit this port is measured against (**D-185**: the baseline moved to that SHA on 2026-09-06, 202 routes / 35 pages). Three divergences, all introduced by that one PHP commit and all missed together: the open-session maximum is **16 hours, not 18**; it is a **hard cap**, not a fallback; and the stale-session auto-close **writes nothing**. |
| How it surfaced | Punch → attendance pairing decides check-in versus check-out by asking whether a live open session exists, which is `openSessionDeadline`. Reading it against today's PHP before building on it showed the two did not agree. Nothing else would have looked: no test pinned the wrong values, because the two tests whose comments mentioned "18 hours" used a two-hour-old session, which is inside both 16 and 18. |
| 1. Sixteen hours | `attendance_open_session_max_hours()` returned 18 and now returns 16. The port still had 18, with the javadoc citing the same function. |
| 2. A cap, not a fallback | PHP computes `min(next working day's shift start, check_in + 16h)`; the port returned the first shift start after the check-in **however far away**, and reached the duration only when the eight-day scan found nothing. Concretely: an employee with Friday and Saturday off, checking in Thursday evening, stayed check-out-able **all weekend** in the port where PHP closes the window 16 hours later. |
| 3. The auto-close writes nothing | `attendance_auto_close_stale_open_sessions()` was emptied to `return 0` with its call sites kept — PHP's own comment says "kept so existing call sites stay valid". The port still UPDATEd every stale open row with a synthetic `check_out` at `check_in + (expected − 120)`. The signature is preserved rather than deleted, because matching the baseline means the call still happens and still answers 0, and `LegacyAttendanceReportService` branches on that count. |
| The rule did not disappear, it moved | Expected-minus-120 is still what a one-punch row reports; it is now computed on read by `attendance_row_worked_minutes` (`LegacyAttendanceWorkedMinutes`, covered by `LegacyAttendanceReportDetailsTest`) rather than written on the next request. Same hours on screen. The difference is that the database now keeps saying "this punch was never closed" instead of storing a time nobody recorded. |
| Why this is not scope creep | Pairing asks this exact function whether a punch is an arrival or a departure. Building on the old rule would have written the divergence into `attendance` rows permanently, where a later correction cannot tell a real check-out from a synthetic one. |
| Tests | `aSessionOlderThanTheSixteenHourCapIsStaleEvenWithNoShiftToCloseIt` is the discriminating case — every day a rest day, so only the cap can close a 17-hour-old session, which is past 16 and inside 18. **Verified red before green**: reinstating 18 and dropping the cap fails that test and only that test. Three tests that pinned the removed auto-close were rewritten, not deleted: their premise was changed by the baseline, and the assertion each still supports is kept. |
| The gap D-185 left | Moving the baseline to `505004f` was followed by decisions porting particular changes it contained -- the leave-policy defaults, `attendance/stats.php`'s fiscal-period branches. `attendance_session_helper.php` was not among them, and no entry in this log mentions it. The baseline moved; this file was never swept against it. Worth noting because the same is likely true of other files that commit touched. |
| Related | **D-185** (the baseline SHA), **D-164** (device ingestion, whose pairing depends on this), `LegacyAttendanceWorkedMinutes`, `LegacyAttendanceReportDetailsTest`. |

## D-214: A Punch Is Paired Against The Moment It Happened, Not The Moment It Arrived

| Field | Value |
|---|---|
| Decision | `PunchPairingService` turns `device_punches` into `attendance` rows, using legacy's own open-session deadline but evaluating it **as of the punch's timestamp**. It lives in `com.workin.legacy.attendance.pairing`, on the HR side of the ownership boundary: the device module owns the wire and writes a punch; deciding which punch is an arrival and which a departure is attendance interpretation and belongs beside the rules `check_in.php` follows (D-164). |
| The question the existing helper answers is the wrong one | `LegacyAttendanceSessions.findOpenSession` asks whether a session is live **now**, which is exactly right for a person standing at a phone. A terminal offline for two days delivers hundreds of records the instant it reconnects, every one of them hours or days old. Asked "live now", the answer for the whole backlog is no, so pairing would open a fresh check-in for every punch and turn two days of arrivals and departures into a column of unclosed rows. The deadline is therefore computed the same way -- `openSessionDeadline`, the earlier of the next working day's shift start and check-in + 16 hours -- and compared against the punch. Pairing becomes a function of the punches and the schedule alone, which is what lets a late punch be replayed and reach the answer it would have reached on time. |
| Ordering | By `punched_at_local`, never by arrival, with `id` breaking ties: two punches can share a second, and an unstable sort would pair them one way on the first pass and another on a retry. |
| Crash safety, and how it is actually proven | One punch, one transaction: the attendance write and the punch's state change commit together, so a punch is either `PAIRED` and names the row it produced or still `RECEIVED` and produced nothing. **A test asserting that a failed punch stays `RECEIVED` proves almost nothing** -- a foreign-key rejection throws before anything is written, so it would pass with no transaction at all. The dangerous ordering is the reverse: attendance written, punch marking then fails, leaving an orphan row and a punch the next pass writes a *second* row for. `aFailureBetweenTheTwoWritesRollsBackTheAttendanceRowAsWell` injects the failure exactly between the two writes, and **fails when the transaction is removed**. |
| A `TransactionTemplate`, not `@Transactional` | The per-punch boundary is entered from `pairCompany` in the same class. A self-invocation never reaches the proxy, so the annotation would have been silently inert and every punch would have committed outside any transaction -- the one property this design depends on. It was written that way first and corrected; the template makes the boundary explicit and matches the seven other places in this codebase that do the same. |
| The two-hour rule becomes a flag | Legacy refuses a check-in within 120 minutes of the last one and tells the app. A terminal cannot be told: it has already displayed "Thank you" and dropped the record from a finite buffer. Refusing would destroy the only evidence the person was present and show them nothing. So the punch is always stored and paired, and flagged `RAPID_RECHECKIN` for a human (D-165). The visible consequence -- the day split into two rows -- is what the reviewer is being asked to look at. |
| A double read is `IGNORED`, not paired | Within one minute of the row it would close, a punch is the same finger read twice. Closing on it records a zero-length day; opening a new row records two arrivals. `IGNORED` is terminal on purpose, so later passes stop reconsidering it. |
| What is not written | No coordinates. A terminal is at a fixed place and reports none, and filling in the branch's location would make a device punch indistinguishable from a geofenced one. `check_in` is the punch's own time, never `NOW()`: a backlog paired on reconnection would otherwise record the reconnection rather than the arrival. |
| Schema | `device_punches` gains `attendance_id`, `paired_at`, `review_flag` -- folded into the `CREATE`, since the table is in no production database yet. `attendance_id` is deliberately not a foreign key: PHP paths delete attendance rows, and a cascade would erase the record of what a device observed. A dangling id means "the attendance row was deleted", which is worth keeping. The `method` enum's fourth value ships as `slice_b_attendance_method.sql`, separate because it *alters* a vendored table -- `phase1_extensions.sql` is applied to databases holding no legacy tables at all, as `Phase1SchemaCheckTest` proves by doing exactly that. |
| Tests | 10, against the real MariaDB schema. The load-bearing ones: a late-delivered backlog pairs into one day rather than two unclosed arrivals; a second pass changes nothing; a failure between the two writes rolls both back. |
| What is deliberately not here | **Nothing calls `pairCompany` yet.** The engine and its rules are implemented and tested; no scheduler, endpoint or listener triggers a pass. Until one does, punches accumulate as `RECEIVED` and no attendance is written — safe, because the evidence is kept and pairing is replayable by construction, but not yet a working feature. The trigger is its own decision and carries its own questions: how often, per company or across all, and what prevents two passes overlapping on the same company. Recorded here rather than left to be discovered, and noted in `docs/operations/monitoring-and-alerting.md` with the query that shows the backlog. |
| Related | **D-164** (the ownership boundary), **D-165** (the two-hour decision), **D-213** (the deadline this depends on being correct), **R-023** (provisioning both DDL files). |

> **Numbering note.** D-213 and D-214, and R-041/R-042 and R-071, are allocated
> on `feat/device-ingestion-and-pairing` (PR #182), which is open and based on
> the same `main` as this branch. This branch therefore starts at **D-215**. If
> #182 merges first, its shorter R-071 and this branch's fuller one conflict:
> **this branch's version is the superset and wins.**

## D-215: The Sensitive-Response Key List Is One List, And It Includes `ip`

| Field | Value |
|---|---|
| Decision | `LegacyPublicRow.SENSITIVE_KEYS` gains `ip` and becomes the single authority. The three other copies — `LegacyEmployeeStore`, `LegacyCompanyStore`, and `LegacyPhpLoginService`'s two hand-written `remove()` calls — now read it. `scripts/check_legacy_sensitive_keys_drift.py` holds it to `sensitive_response_keys()`. |
| The defect | `505004f` added `Column::IP` to `sensitive_response_keys()` and added the backing column to both `employees` and `companies`. All four copies in the port kept `password_hash, token_version`. `LegacyJdbcValues.rowMapper()` strips nothing and the projections are unrestricted — `LegacyEmployeeStore` runs `SELECT * FROM employees WHERE company_id=?` and two `SELECT e.*` for the HR employee list — so **an HR session received the last-login IP of every employee in the company**, an approximate location per person, for a field PHP had just classified as a secret. On `profile/company.php` it was the company's. |
| Also a shape change | The port emitted `"ip": null` where PHP omits the key, on the most-called objects in the API. A dual-run diff would have shown a difference on every employee and company object. |
| Why four copies is the root cause | `LegacyPublicRow`'s own javadoc already warned that a second copy "silently protects nothing when a different module runs its own `SELECT *`". A list duplicated four ways cannot be kept honest by review, so the fix is not four edits — it is one list. |
| Why the tests agreed | Nine tests asserted `doesNotContainKeys("password_hash", "token_version")` by name. None mentioned `ip`, so all nine passed against all four stale copies. They now assert `keySet()` against `LegacyPublicRow.SENSITIVE_KEYS`, so they cannot go stale independently of the thing they check. |
| The test that actually proves it | The nine above would still pass if `ip` were served, because the shared fixture leaves the column NULL and a NULL column is simply absent from the row — which is exactly how this shipped. `aStoredLastLoginAddressIsStrippedFromTheEmployeeList` writes `203.0.113.47` first, then asserts the key is absent **and** that no value in the row equals it. Verified red: with `ip` removed from the list it fails, and the new gate fails too. |
| Related | **R-071** (the audit), **R-072** (nothing writes the column), **R-049** (whose "exactly one consumer" claim this audit corrected). |

## D-216: The Vendored Module Allow-List Is Refreshed, And Its Gate Was Reporting Green While Stale

| Field | Value |
|---|---|
| Decision | `allowed_modules.txt` is refreshed from `hr-legacy@a2dd5d7` (38 → 40 modules, adding `guide_videos` and `time`) and `LegacyPhpModules.ALLOWED` with it. |
| The defect, and the worse defect behind it | The vendored file's own header read `vendored from hr-legacy@d113204` — **the commit before the baseline**. It was never refreshed when D-185 moved the contract. Running the real check reported `missing from vendored: ['guide_videos', 'time']` and exited 1: **the gate was already red and nobody was running it.** |
| Why CI stayed green | The script's docstring records that the real comparison "cannot run in CI" — CI has no `hr-legacy` checkout. What CI runs is `LegacyPhpModulesDriftTest`, which compares the Java constant against the **vendored file**. Both were stale, so they agreed, so it passed. A vendored authority that nothing refreshes is not an authority; it is a second copy of the mistake. |
| Consequence | Routes still worked — `LegacyPhpRoutes` maps both — but the refusal ladder was wrong: `module_not_found` bodies listed 38 modules where PHP lists 40, and an unknown action under `time/` or `guide_videos/` returned `404 module_not_found` where PHP returns `501 module_not_implemented`. |
| A test whose premise the baseline changed | `aModuleThatIsNotOnTheListIsNotAllowed` used `"time"` as its example of an absent module. `505004f` made `time` a real one, so the assertion had quietly started proving the opposite of what it said. It now uses a name that cannot become a module. |
| Related | **R-071**. |

## D-217: A Stored Zero Is A Real Figure, And Allowances Are More Than Housing

| Field | Value |
|---|---|
| Decision | Two corrections to `payroll_batches/stats.php`'s aggregate query. The `NULLIF(col, 0)` wrappers are removed from the entitlements and deductions expressions, and `total_allowances` sums all five allowance columns via `505004f`'s new `sql_payslip_total_allowances()` rather than the housing column alone. |
| The `NULLIF`, and why it mattered | `505004f` dropped it and said why in the source: "Keep stored 0 (mid-month unpaid) — only fall back when the column is NULL." `total_entitlements` is `NOT NULL DEFAULT 0.00`, so PHP's `COALESCE(col, sum)` **always** returns the stored column and its sum branch is unreachable. The port's `COALESCE(NULLIF(col, 0), sum)` instead treated every legitimate zero as unset. An employee absent the whole period stores `total_entitlements = 0` while `basic_salary` and the allowances stay populated — so the port reported that employee's **full gross as though it had been paid**, inflating `total_entitlements`, `total_net_salary` and all four net aggregates on the batch. |
| The allowances | PHP now rounds `transport + food + risk + incentives + housing` **per row** and then sums; the port summed `ps.allowances` alone. Ten employees on transport 500 / food 300 / incentives 200 / housing 1,000: PHP reports 20,000, the port reported 10,000 — half. The per-row rounding is preserved deliberately: summing first and rounding once is a different number. |
| Why the suite passed | No existing fixture stores a zero total, and none uses an allowance column other than housing — the two places the right and wrong expressions agree. `LegacyPayrollBatchStatsParityTest` seeds exactly those two shapes and was **verified red** against both old expressions before being taken green. |
| Related | **R-071**, **D-190** (`edit_detail` writes `other_deductions` but never `total_deductions`, which is what makes the deduction side of this reachable after a hand edit). |

## D-218: Weekly Rest And Holidays Are Not Days Present

| Field | Value |
|---|---|
| Decision | `days_present` counts punches and exceptions only, at all three places the port computed it: the write (`LegacyPayrollCalculationService`), the read (`LegacyPayslipService`) and the hover breakdown (`LegacyPayrollAttendanceFigures.presentDetails`). |
| The upstream change | `505004f` moved earned weekly rest and credited official holidays out of "days present" and into their own payslip fields, deleting ~40 lines from `payroll_payslip_present_details()` and rewriting its docblock to "punch / exception only. Earned weekly rest and official holidays are separate payslip fields." They are still earned and still paid — only no longer reported as days attended. |
| One decision, three places | The port kept them merged in all three, so they had to be fixed together. 22 punches + 4 earned rests + 1 credited holiday displayed as **27**. The read path was the worst of the three: it recomputed and overwrote the stored value, so the inflated figure appeared even on payslips that had been calculated correctly. It propagated to the payslip screen, the XLSX "أيام الحضور" column and `SUM(ps.days_present)` in the batch stats — roughly +500 days on a 100-employee batch. |
| Not a money defect | Pay is driven by `days_absent`, so no salary figure moves. Every attendance figure a user reads does. |
| Why the suite passed | `LegacyPayrollBatchCalculateEndToEndTest` asserts `days_present` on fixtures with no weekly rest and no holidays, so it passed under either rule. `LegacyPayrollCalculationServiceTest` did pin the old rule explicitly — `isEqualTo(26); // 22 punch + 4 earned rest` — and was rewritten to 22 with the reason recorded, not deleted. |
| Related | **R-071**. |

## D-219: Two Smaller Divergences The Baseline Had Already Decided

| Field | Value |
|---|---|
| Decision | Both are behaviour `505004f` determined, so the port simply has to agree: `fiscal_period.php` resolves rather than refuses, and the employee-import error catalog renders the four codes it was missing. |
| `fiscal_period.php` | An out-of-range `year`/`month` no longer fails; PHP resolves the fiscal month containing today, which is what makes the **no-parameter** call — "what period am I in?" — the endpoint's ordinary use. The port answered it `400 invalid_input`. The response also gained `month_start_day` and `month_end_day` (with `0` resolved to the last day of the period's own month, not left for the client to guess), and the roles widened to include `MANAGER` and `EMPLOYEE`, whom the port was answering 403. The building block was already present and correct — `LegacyPayrollFiscalSettings.fiscalMonthContainingDate()` — and simply never called from here; D-188 ported the same helper into `attendance/stats.php` and stopped there. |
| The error catalog | `505004f` added `gender_invalid`, `employee_not_found`, `nothing_to_update` and `employee_update_failed`. The port **produced all four** and fed them into a table with no arm for any of them, so they fell through `default -> code`. An Arabic-speaking HR user saw the bare token `nothing_to_update`, and `field_errors` stayed empty so the per-cell highlight never fired. Not an edge case: the update template ships with every example cell blank except `employee_code`, so "filled in only the code" is the default careless outcome. |
| The gate that existed and did not cover it | `decision-log-wave12r.md` records the *identical* failure mode on this very endpoint — `employees_updated` shipping as a raw key — fixed by adding `check_legacy_message_drift.py`. That gate closed the `t()` catalog. `employee_excel_error_message()` is a **second, hard-coded catalog in the same feature**, deliberately outside `t()`, and was left open. `check_legacy_excel_error_codes_drift.py` closes it, comparing the codes each side handles rather than the rendered text. |
| Three tests whose premise changed | Two pinned `fiscal_period`'s refusal and were rewritten to assert the fallback; the third gained the two new response fields. |
| Related | **R-071**, **D-188**. |

## D-220: G2's Denominator Is Re-Counted From The Baseline, Not Carried Forward

| Field | Value |
|---|---|
| Decision | Gate **G2** reads **202 live + 0 excluded = 202** physical endpoint files, replacing `198 live + 1 excluded = 199`. The number was **re-counted from `hr-legacy` at `HEAD`**, not adjusted arithmetically. |
| What made the old one wrong | O-3 excluded `/apis/api/time/now.php` from the live obligation *precisely because the router could not expose it* — `time` was absent from `ApiModule::allowedList()`, so the route 404'd and implementing it would have added a route legacy does not serve. `505004f` added the module. Legacy serves the route today and so does this port (`LegacyTimeController`, `route-methods.txt:209`, `LegacyPhpRoutes.java:153`), so the exception described nothing and the exclusion ledger has no remaining rows. |
| The count, with its working | 203 `.php` files under `apis/api/**`, less `apis/api/index.php` which is the router rather than an endpoint, gives **202**; all 202 belong to modules in `allowedList()`, so **0** are excluded. The delta from 199 is fully accounted: `505004f` added exactly three endpoint files — `employees/analyze_excel_update.php`, `employees/update_bulk.php`, `guide_videos/list.php` — and removed none. 199 + 3 = 202. |
| Why re-counted rather than adjusted | Carrying a total forward and patching it is how the stale exception survived a baseline move in the first place. A derived number can be checked against its source; an adjusted one can only be checked against the person who adjusted it. |
| Three independent sources agree | The file count above; the port's own inventory (`route-methods.txt` holds 202 distinct endpoint files); and `check_legacy_route_drift.py --legacy-api ../hr-legacy/apis/api`, which reports *"hr-legacy present: its 202 routes match the committed inventory"*. **That third one has been printing the correct number all along** — it was read as a pass/fail signal rather than as an accounting statement, which is the whole reason a stale ledger could sit beside a green gate. |
| Scope | This settles the **denominator** and the exclusion bucket. The §3.2 ledger's own bucket accounting is the completion plan's to maintain; nothing here reclassifies an endpoint's implementation status. |
| Historical entries left alone | Earlier decision-log rows saying "live total 198 unchanged" were accurate when written and are not rewritten — the log is a record, not a current-state document. This entry supersedes them. |
| Related | **R-071**, **D-185** (the baseline move), **O-3** (the exclusion this retires), **D-120/O-8**. |

## D-222: The Independent-Review Gate Has One Override, And It Leaves A Record

| Field | Value |
|---|---|
| Decision | **D-121's gate may be overridden by the repository owner, and by nobody else.** AGENTS.md previously described only one way the gate could fail to be satisfied — Codex's quota being exhausted (R-009) — and said the merge waits. It said nothing about a merge proceeding anyway, which is what happened on **#183**. An undocumented bypass with no stated conditions is precedent by accident, so this defines the conditions rather than leaving the next one to improvisation. |
| Who | The repository owner, explicitly, in the conversation or on the pull request. **Not** an implementer, and not an agent inferring consent from impatience or from a prior approval of different work. An agent asked to merge past this gate states the gate's actual condition and asks; it does not decide. |
| When it is legitimate | Only when **waiting is itself the harm** — a defect live in production, a security exposure, a data-integrity fault still running. Convenience, a slow queue, and schedule pressure are not qualifying reasons. If the change is not making something worse every hour it sits, it waits. |
| What must be recorded, in the pull request, before the merge | The head SHA. Every gate's actual state, including the failing one, quoted rather than summarised. What review **did** happen and on which heads — a round on an earlier head is evidence about that head and no other. What is therefore unconfirmed. The specific harm that justifies not waiting. The follow-up that closes the gap. |
| What it is not | Not a waiver of the findings. #183's three rounds produced nine findings; every one was reproduced against the code and fixed before the merge, and the threads were resolved with a comment mapping each finding to its fix. What the override skipped was a **confirming round on the final head** — a narrower thing than "merged unreviewed", and the record has to say which of the two it was. |
| The overrides taken so far | **Two, both 2026-09-09, both on owner instruction after the situation was put to them.** They are not equivalent, and recording them as though they were would be the first step to normalising the second. |
| — **#183**, `930ea394` | `validate` and `test` green; `independent-review` failing because no round had landed on that head; GitHub reporting `BLOCKED`. **Three rounds had run** on earlier heads and produced nine findings; every one was reproduced against the code and fixed before the merge, and the threads were resolved with a comment mapping each finding to its fix. What was skipped was a **confirming** round. Justification: a PII disclosure serving every employee's last-login IP to any HR session, two money defects in payroll batch statistics, and a drift gate reporting green while stale — all live. |
| — **#184**, `3e3ce8cd` | Same gate state, **but no review of any kind had run** — zero rounds, zero findings, after `@codex review` was requested twice about forty minutes apart with no response. This is the stronger override of the two. Justification: `main` carried the pre-`505004f` open-session rule, so an employee resting Friday and Saturday who checked in Thursday evening stayed check-out-able all weekend, and stale sessions were being closed with a synthetic `check_out` nobody recorded — on the live `check_in`/`check_out` path, every employee, every day it sat. What stood in place of the round: 2,658 tests green, red-before-green verified standalone on the branch, a diff confined to one class plus its tests and grep-verified free of device references, and a pre-push self-review. |
| The pattern that is now visible, and is not acceptable as a habit | Two overrides in one day, on consecutive pull requests, both because the connector went silent rather than because it objected. That is a **reviewer-availability problem being paid for with governance**, and D-222's bar — *waiting is itself the harm* — is doing real work only while the queue is the exception. If a third arises for the same reason, the answer is to fix the review pipeline (R-009's territory) or to define a documented timeout with a named substitute reviewer, not to keep spending the override. |
| Why an agent must ask rather than infer | On #183 the instruction was "i approved can merge them", and three facts contradicted the natural reading: no approval was recorded on the PR, the round had not landed on that head, and GitHub reported the PR blocked. Merging on the plain reading would have silently bypassed a gate the owner may not have known was still failing. Surfacing the three facts cost one exchange; the override then happened knowingly. That exchange is the requirement, not a courtesy. |
| Related | **D-121** (the gate), **R-009** (quota exhaustion, which makes the gate *unavailable* rather than overridable), `AGENTS.md` Mandatory Workflow. |

## D-223: Repository Visibility, CI Execution, And Merge-Governance Enforcement Are Three Decisions, Not One

| Field | Value |
|---|---|
| Status | **Answered 2026-09-09.** Q2 accepted. **Q3 answered: Option B** — GitHub-enforced merge governance is preserved and Option A is explicitly rejected as the permanent private-repository model. Q1 is therefore **approved in principle but blocked on Team funding**: until the subscription exists, `hr-platform` **stays public**. |
| Owner | Repository owner |
| Why one entry, three questions | The request arrived as one sentence — make the repository private without incurring cost — and collapsing it into a single decision hides the only hard part. Privacy is a commercial requirement. Cost is an arithmetic problem with a clean solution. Enforcement is a genuine loss with no free substitute, and it is the one that has to be decided deliberately rather than absorbed as a side effect of the other two. |
| Related | **D-013** (the original private-on-Free deferral), **D-125** (protection applied once public), **D-142** (`independent-review` lifted from required contexts), **R-008**, **R-009**, **D-222**, **D-224**. |

### Q1 — Repository privacy

| Field | Value |
|---|---|
| Decision | `hr-platform` becomes **private**. |
| Reason | The product is being prepared for sale. Source that is publicly readable cannot be sold as exclusive, and the repository is public today. This is a commercial requirement, not a technical or cost one. |
| What this is **not** | It is **not** a response to an Actions limit being hit. Public repositories get unlimited free GitHub-hosted runner minutes; the account is currently paying nothing and is not near any cap. The 2,000-minute allowance is **created by** going private. Any framing that treats privacy as a cost saving is backwards, and Q2 exists because privacy has a cost, not a saving. |
| Confidentiality check performed | `deploy/seed/dev-seed.sql` (80,708 lines) was audited before this entry because a production-derived seed in a public repository would have made privacy urgent rather than commercial. It is **properly sanitised**: all 442 e-mail addresses use the RFC 2606 reserved `@example.invalid` domain, the 4,185 mobile numbers occupy a narrow synthetic block (`0100000XXXX`–`0100009XXXX`, 4.6% density over its own span), and every 14-digit identifier begins `200` with a single governorate code. These are generator artefacts, not real people. **No disclosure incident exists and none of this is urgent.** |
| Blocked on | **Team funding, per Q3's answer.** The repository stays **public** until GitHub Team is in place. Flipping it private before then would knowingly trade platform-enforced governance for procedure, which Q3 rejects. Privacy remains approved in principle — this is a sequencing constraint, not a reversal. |

### Q2 — CI cost and execution strategy

| Field | Value |
|---|---|
| Decision | Reduce Actions consumption by **trimming workflow triggers**, **while keeping an automatic backend check on every pull request's final head**. Do **not** introduce self-hosted runners. |
| Superseded in practice by Q3's answer | Q3 chose Option B, so the repository **stays public** until Team is funded — and public repositories have **no minute limit at all**. The cost pressure this question was written to answer therefore **does not currently exist**, and the trimming survives only as a **latency** improvement, not a budget necessity. The 30-day figures below are retained because the budget question returns in full the day visibility flips, and must be re-derived then **with** the backend check retained rather than by reusing the 670-minute figure, which assumed removing it. |
| Measured consumption | 30 days to 2026-09-09, from run history: `Backend Validate` 592 runs × 10.6 min = 6,275; `Phase 0 Bootstrap Validate` 716 × ~2 = 1,432; `Independent Review Gate` 894 executed × 1 (per-job round-up) = 894; nightly and Dependabot ≈ 60. **Total ≈ 8,660 min/month.** Billable minutes read zero from the API today because the repository is public; each run is a single job, so `ceil(run_duration)` is the correct proxy. |
| Cost if unchanged when private | 6,660 minutes over the allowance at **$0.006**/min (ubuntu 2-core; GitHub cut hosted-runner prices effective 2026-01-01 — an earlier $0.008 figure used in discussion was stale) ≈ **$40/month**, with the free allowance exhausted in about seven days. |
| Where the trimming comes from | `Phase 0 Bootstrap Validate` has **no `concurrency` group**, so superseded runs are never cancelled — a pure waste with no enforcement value. The review gate's largest event source is `pull_request_review` (645 of 1,146 runs), not `issue_comment` (69). `Backend Validate` may skip **draft** pull requests, since a draft is not a merge candidate. |
| **Correction — `Backend Validate` does not "gate nothing"** | An earlier draft of this entry reasoned that because `test` is not a *platform-required status* (D-125 dropped it: the workflow is path-filtered and would deadlock docs-only pull requests), the workflow could move to an on-demand trigger at no cost to enforcement. **That conflates two different things.** `AGENTS.md`'s Mandatory Workflow makes **automated verification** a required *stage* independent of what branch protection enforces, and `backend-validate.yml` is the **only** workflow in the repository that runs `./gradlew test` — `nightly.yml` runs `validate_phase0.py` and no Gradle at all, verified by grep across `.github/workflows/`. Moving it off an automatic trigger would let a backend pull request reach human merge with **no backend suite having run on its final head**. It therefore stays automatic on `pull_request`; only draft-skipping and cancellation of superseded runs are taken. |
| Facts that removed two assumed blockers | **GHCR is not a cost problem.** Container storage and bandwidth on `ghcr.io` are *"currently free"* per GitHub's own documentation, with a commitment to at least one month's notice before that changes. The 500 MB / 1 GB Packages quotas govern npm, Maven, NuGet and Gradle — not container images. The backend image is ~500 MB, which had been read as disqualifying; it is not. #186's `docker compose pull` workflow therefore survives the move to private unchanged, except that client developers must `docker login ghcr.io` with a token carrying `read:packages`. |
| Why not self-hosted runners | They would remove the minute limit entirely — GitHub bills no minutes for them and places no plan restriction on GitHub Free organizations. **R-009's Contingency forbids it in this repository by name**: *"Do not attempt to work around either quota in-repo (no self-hosted-runner fallback, …)"*. That prohibition stands and is not reopened here. Independently, GitHub scopes the residual risk to *"anyone who can fork the repository and open a pull request (generally those with read access)"* — an empty set with one maintainer, but not empty the moment client developers are granted read access, which is a live possibility under #186. Trimming reaches the same goal with no new infrastructure and no security trade-off. |
| Sequencing | Lands as a focused pull request **after** this record is accepted, together with the Q3 hook if Q3 accepts it. |

### Q3 — Enforcement of merge governance (owner decision owed)

| Field | Value |
|---|---|
| The constraint | GitHub Free offers **neither** branch protection **nor** rulesets on private repositories. This is not an inference: D-013 recorded both endpoints returning `403 Upgrade to GitHub Pro or make this repository public` when `hr-platform` was last private on Free, and GitHub's current documentation gates the two features identically — *"Rulesets are available in public repositories with GitHub Free … and in public and private repositories with GitHub Pro, GitHub Team…"*. **Rulesets are not an escape hatch.** D-125 applied protection only because going public removed D-013's premise; going private restores it. |
| What is mechanically enforced on `main` today | Verified live against the API for this entry: required status check `validate` (strict); `enforce_admins=true`; `allow_force_pushes=false`; `allow_deletions=false`; `required_conversation_resolution=true`; `dismiss_stale_reviews=true`; `required_approving_review_count=0`. Two contexts are deliberately absent: `test` (D-125, path-filter deadlock) and **`independent-review`, lifted by the owner under D-142** — so that gate is already procedural rather than mechanical, and Q3 does not lose it because it is not currently held. |
| What going private on Free costs, exactly | All seven settings above cease to exist. There is no partial mode and no downgraded enforcement — the protection object is simply unavailable. |
| What the pre-push hook **can** enforce | Refusal of a direct push to `main`; refusal of a non-fast-forward (force) push to `main`; refusal of a branch deletion; and running the full `validate` equivalent locally before any push succeeds. Drafted and tested against this worktree; `shellcheck` clean. |
| What the pre-push hook **cannot** enforce, and this is the crux | **It never runs on the path that actually merges.** Pull requests are merged on github.com, through the UI or API; that operation does not pass through any local hook. So conversation resolution at merge, a required green check at merge, `enforce_admins`, and protection against a force-push or branch deletion performed through the web interface or API all lose their enforcement entirely and gain **no** substitute. The hook covers the *push* boundary; branch protection covered the *merge* boundary. They are not the same boundary, and describing the hook as a replacement for branch protection would be false. |
| Second limitation, stated plainly | The hook is client-side and `--no-verify` bypasses it. With one maintainer this is tolerable — it prevents accidents and binds automated agents, which is most of its value — but it is not a control that survives an actor who does not want to be bound, and it is weaker than `enforce_admins`, which bound the owner too. |
| Option A — accept the loss (**rejected**, recorded for completeness) | Go private on Free. Merge governance becomes **entirely procedural**: no required check, no conversation-resolution gate, no admin enforcement — the exact condition R-008's own history says procedure alone failed to hold, PR #126 having lost a ten-second race to it. Cost: $0. **The state transition this would require, stated precisely because "reopen R-008" is not one:** R-008's Status is *already* `Open — Accepted Residual Risk`, so there is nothing to reopen. What would actually be required is **replacing its Mitigation**, which currently opens *"Mechanically enforced as of 2026-08-29 (D-125)"* — a sentence that becomes false the moment protection disappears — with the procedural-only state, and recording a **fourth realisation** if a merge then bypasses the process. Leaving that Mitigation standing while the mechanism is gone would be the worst outcome available: a register asserting enforcement that no longer exists. |
| Option B — buy enforcement first | GitHub **Team** at $4/user/month restores branch protection and rulesets on private repositories and raises included minutes from 2,000 to 3,000. At one seat this is **$4/month**, and with Q2's trimming nothing exceeds the allowance, so $4/month is the whole cost. R-008's mitigation survives intact. |
| **Decision: Option B** (owner, 2026-09-09) | GitHub-enforced merge governance is preserved. **Option A is rejected as the permanent private-repository model** — the repository will not be flipped private with branch protection knowingly downgraded to procedure-only enforcement. |
| Consequence while Team is unfunded | The repository **stays public**. This is the one outcome that keeps every enforcement setting intact at zero cost, and it costs only the confidentiality that Q1 wants — deferred, not abandoned. **R-008's mitigation therefore survives unchanged** and is not reopened. |
| What unblocks Q1 | Funding one GitHub Team seat ($4/month). At that point visibility flips and every setting in the row above is re-applied and verified with `scripts/check-branch-protection.sh` before the change is called done. |
| Explicitly not authorised | Flipping visibility private on the Free plan, in any circumstance, without Team in place. If that is ever proposed again it is a **new** decision superseding this one, not an implementation detail of it. |

## D-224: Degraded-Review Procedure For A Reviewer-Service Outage

| Field | Value |
|---|---|
| Status | **Accepted 2026-09-09 by the repository owner**, subject to the bootstrap condition below. Acceptance changes who may satisfy D-121's gate, which R-009 requires be *"a separately approved policy change that updates the canonical workflow first"* — so `AGENTS.md` and R-009 are amended in the same branch as this entry. |
| **Bootstrap condition — this procedure may not authorise its own adoption** | **D-224 does not apply to the pull request that introduces it.** #188 must receive a **normal independent review from `chatgpt-codex-connector[bot]` on its exact final head** before this entry becomes canonical policy. A degraded-review procedure that admits itself under its own terms would have no independent check on the one change that weakens the gate — the failure mode this entry exists to prevent. Until #188 merges after a normal round, D-224 is accepted-but-not-in-force and **no pull request may rely on it**. |
| When the 14-day clock starts | At **#188's merge**, not at acceptance — a procedure that is not yet in force cannot expire. Recorded explicitly because "14 days from acceptance" would otherwise burn the window while waiting for the bootstrap review. |
| Owner | Repository owner |
| Why this exists | D-222 closed with a condition on itself: *"If a third arises for the same reason, the answer is to fix the review pipeline (R-009's territory) or to define a documented timeout with a named substitute reviewer, not to keep spending the override."* Two overrides were taken on 2026-09-09 because the reviewer produced nothing. Three pull requests (#182, #186, #187) are now in the same state. This entry is that condition being discharged rather than a third override being spent. |
| What this is **not** | **It is not D-222 and must never be used as one.** D-222 is for a merge whose *delay is itself a harm* — a defect live in production, a security exposure, a data-integrity fault still running. Reviewer unavailability is **not** a qualifying reason under D-222 and this entry does not make it one. D-222 overrides the gate for a specific merge; D-224 **degrades** the gate for a stated outage window and demands a substitute in the gate's place. A pull request merged under D-224 was not merged past the gate — it was merged under a weaker gate, and it must say so. |
| Correction that shaped this entry | The three pull requests were repeatedly described in working discussion as having received *no response* from the reviewer across six requests. **That was wrong, and the error was mine: I counted review objects and comment totals without reading the comment bodies.** The connector answered every request. Between 10:30:36Z and 11:17:11Z on 2026-09-09 it posted **seven** comments across the three pull requests, each the literal *"You have reached your Codex usage limits for code reviews."* This is not an unexplained outage. It is **R-009 realised again**, matching that risk's stated trigger word for word. |

### Criterion 1 — declaring the reviewer unavailable

| Path | Condition | Evidence |
|---|---|---|
| **(a) Self-declared exhaustion, still current** | A usage-limit comment exists **and is the reviewer's most recent artefact of any kind** — no review object and no clean-round comment has landed after it. A historical usage-limit comment proves the outage *happened*, never that it *persists*: quota recovers on its own, and on 2026-09-09 it recovered roughly 80 minutes after the last such comment. **The moment any round lands anywhere, path (a) is closed** — see Criterion 5. | Line 2 of the command below reading `PASS`. |
| **(b) Silence** | All of: `@codex review` requested at least **twice**, the requests at least **60 minutes** apart, the first at least **4 hours** ago; **and** no round on the current head — counting **both** review objects **and** D-158 clean-round comments, because a round that finds nothing posts a comment and no review object at all, so counting reviews alone reports "silence" on a head that was reviewed clean. | Lines 1 and 3 of the command below reading `PASS`. |

Neither path may be declared by inference, impatience, or a summary. **Every line the command prints must read `PASS` for the path being claimed; a `FAIL` or `n/a` on any required line means the gate is not degraded and the merge waits.** The command proves each prerequisite rather than reporting two counts a reader must interpret — an earlier draft returned only `reviews_on_head` and a repository-wide quota-comment count, either of which could read `0`/`n` for an unrequested, too-recent, already-reviewed, or already-recovered head.

```bash
PR=<number>; BOT=chatgpt-codex-connector; REPO=workin-hr/hr-platform
SHA=$(gh pr view "$PR" --repo "$REPO" --json headRefOid --jq .headRefOid); SHORT=${SHA:0:7}
J=$(gh pr view "$PR" --repo "$REPO" --json reviews,comments)
now=$(date -u +%s); ep(){ date -u -d "$1" +%s 2>/dev/null || echo 0; }

# 1. no round on THIS head -- review objects AND D-158 clean-round comments
rounds=$(jq -r --arg b "$BOT" --arg s "$SHA" --arg p "$SHORT" '
  [(.reviews[]|select(.author.login==$b and .commit.oid==$s)),
   (.comments[]|select(.author.login==$b and (.body|test("Reviewed commit.{0,4}"+$p))))]|length' <<<"$J")
[ "$rounds" -eq 0 ] && echo "PASS  no round on head $SHORT" \
                    || echo "FAIL  $rounds round(s) on head $SHORT -- the gate is SATISFIED, not degraded"

# 2. path (a): the reviewer's LATEST artefact is a usage-limit notice (current, not historical)
last=$(jq -r --arg b "$BOT" '[(.comments[]|select(.author.login==$b)|{createdAt,body}),
   (.reviews[]|select(.author.login==$b)|{createdAt:.submittedAt,body:"<review object>"})]
   |sort_by(.createdAt)|last // empty' <<<"$J")
if [ -z "$last" ]; then echo "n/a   path (a) not claimable -- reviewer has posted nothing"; else
  jq -e '.body|test("usage limits";"i")' >/dev/null <<<"$last" \
    && echo "PASS  current exhaustion ($(jq -r .createdAt <<<"$last"))" \
    || echo "FAIL  quota RECOVERED -- latest artefact $(jq -r .createdAt <<<"$last") is not a usage-limit notice"
fi

# 3. path (b): two requests >=60min apart, first >=4h ago
reqs=$(jq -r '[.comments[]|select(.body|test("@codex review"))|.createdAt]|sort|.[]' <<<"$J")
n=$(grep -c . <<<"$reqs")
if [ "$n" -ge 2 ]; then f=$(ep "$(head -1 <<<"$reqs")"); l=$(ep "$(tail -1 <<<"$reqs")")
  [ $(( l - f )) -ge 3600 ] && [ $(( now - f )) -ge 14400 ] \
    && echo "PASS  path (b): $n requests, $(( (l-f)/60 ))min apart, first $(( (now-f)/3600 ))h ago" \
    || echo "FAIL  path (b): needs >=2 requests >=60min apart and >=4h elapsed"
else echo "n/a   path (b) not claimable -- $n request(s)"; fi
```

A round on an earlier head is evidence about that head and no other.

### Criterion 2 — the remedy is attempted before the substitute

| Field | Value |
|---|---|
| Mandatory first step | Under path (a) the cause is known and the remedy is documented: R-009 states the mitigation is *"restoring or funding the named reviewer, not monitoring."* **The owner must either restore the quota or explicitly decline to, and the declining is recorded.** Building a substitute reviewer while an unattempted paid fix exists is precisely the *"reviewer-availability problem being paid for with governance"* that D-222 named and refused to normalise. |
| Why this clause is load-bearing | Without it, D-224 becomes a standing alternative to funding the reviewer, and the gate quietly degrades permanently. The substitute is for the window between the outage and the remedy — not instead of the remedy. |

### Criterion 3 — the replacement independent verification

All five are required. Any one missing means the gate is not degraded but simply unmet, and the merge waits.

| # | Requirement |
|---|---|
| 1 | **A read-only review pass on the frozen final head** by a reviewer with **no authorship, implementation, generation, or repository-write involvement in the change under review, on any branch** — not merely one that personally ran no `git commit`. An agent that wrote or generated the diff is an implementer under `AGENTS.md` whoever committed it, and a later read-only pass by that same agent does not launder it into a reviewer. The head must not move during or after the pass. |
| 2 | **Findings posted to the pull request verbatim**, not summarised, including findings the implementer disputes — with the dispute stated as a reply rather than by omission. |
| 3 | **Red-before-green evidence for every behavioural change**: the test failing without the fix and passing with it, run standalone, linked. |
| 4 | **The full suite green on the final head**, linked by run or local summary. |
| 5 | **A written statement of what the substitute did not cover** relative to a real round — at minimum that it is a different reviewer of unmeasured comparability, and that no independent party verified the implementer's dispositions. |

### Criterion 4 — the record required on each pull request

Posted as a single comment before the merge, containing: the head SHA; which unavailability path was declared, with its evidence; the quota-restoration decision and who made it; the substitute reviewer's identity and confirmation it holds no write access to the branch; every finding and its disposition; what remains unverified; and a link to this entry. A merge under D-224 without this comment is a policy breach, on the same terms D-222 sets for its own record.

### Criterion 5 — returning to the normal gate

| Trigger | Effect |
|---|---|
| **Any evidence that quota has recovered** — a round on any head, or any reviewer artefact that is not a usage-limit notice | The degraded procedure **lapses immediately** and the normal D-121 gate resumes for every open pull request, including those mid-flight. Recovery is the trigger, **not** the arrival of a round on the specific pull request in hand: waiting for a per-PR round would leave a window in which a stale usage-limit comment still authorised the substitute after the outage had ended. Line 2 of Criterion 1's command is what closes that window. |
| **14 days** from acceptance | Lapses automatically. Extension requires a new owner decision, not a renewal by silence — this is the clause that stops a temporary procedure becoming the standing one. |
| Either way | Every pull request merged under D-224 is listed in the extension or closure record, and each gets a retrospective round or an explicit, recorded owner decision not to seek one. |

| Field | Value |
|---|---|
| Amendments required on acceptance | D-224 changes which agent may satisfy the independent-review gate, so the amendment set is everything that encodes the current answer, not only the policy file: (1) **`AGENTS.md`** Mandatory Workflow — the *"unavailable, not waived"* sentence gains D-224 as its documented, time-boxed exception; (2) **R-009** — its *"not substitutable"* clause gains the same reference, which that clause explicitly anticipates; (3) **`docs/agents/responsibility-matrix.md`** — its reviewer note ends *"the gate is unavailable, not waived"* and must carry the exception; (4) **`docs/bootstrap/manual-setup-checklist.md`** step 5 — it states *"no other reviewer substitutes for it"*, which contradicts an accepted D-224 as written. All four are amended in the same branch as this entry. |
| Reviewed and found not to need amendment, with the reason | **`scripts/validate_phase0.py`'s `validate_independent_reviewer_declaration()` and its regression tests in `scripts/test_validate_phase0.py`.** The property they enforce is that `AGENTS.md` names the independent reviewer inside the workflow it gates and that `docs/agents/responsibility-matrix.md` carries a matching read-only row. **D-224 does not change that property**: `chatgpt-codex-connector[bot]` remains *the* named reviewer, and the substitute is a degraded fallback for its unavailability, not a second reviewer of record. Widening the validator to accept a substitute name would weaken exactly the binding D-121 relies on. Verified green against this branch. |
| Application to #182, #186 and #187 | **None by this entry.** D-224 defines a procedure; it merges nothing and authorises nothing retroactively. Whether to apply it to the three open pull requests, restore the Codex quota, or continue waiting is the owner's decision, taken after this record is accepted. |
| Related | **D-121** (the gate), **D-222** (the override, deliberately distinct), **D-142** (what happened last time the gate was worked around at scale — twelve pull requests merged without a round), **R-008**, **R-009**, `AGENTS.md` Mandatory Workflow. |

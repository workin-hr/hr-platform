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

**Status:** Accepted 2026-08-29. Owner-approved disposition of the C3/C8 pass's
`assets` finding.

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

**Status: PROPOSED — owner approval required before merge.** Security-relevant;
**not** closed by this entry.

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

Evidence: `./gradlew check` — **2024 tests, 0 failures**, 10 new.

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
- `employee_docs/update.php` is **POST**, not PUT, unlike the rest of Item 13.
- `doc_type` defaults to the literal `"other"` and is neither trimmed nor
  validated against any list.

### Ledger after Wave 13.4c

`FINAL_COMPATIBLE` 159 → **170**; `ITEM13_REMAINING` 39 → **28**; partition
154/4/1 → **165/4/1**. Live total 198 unchanged. **Item 13.4 is complete**; only
Waves 13.1 (auth, 13) and 13.2 (profile 9 + notifications 6) remain.

Evidence: `./gradlew check` — **2038 tests, 0 failures**, 14 new.

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

Evidence: `./gradlew check` — see the wave's PR body for the count.

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
- `resolve_employee_name_from_body()`'s `name`-splitting and `Pending-<phone>`
  fallbacks are **unreachable from `join_company.php`**, because `first_name` is
  `required()`. Pinned, because the helper is shared and a later caller could
  reach them.
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

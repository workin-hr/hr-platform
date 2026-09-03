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
| How | `LegacyPersistenceConfig` scans `com.workin.backend.platformadmin` and enables its entities and repositories against the legacy `EntityManagerFactory`; the platform-admin UI chain and API chain drop their profile guards. The tables are added to `phase1_extensions.schema.sql` — the established place for Java-owned tables in the legacy database — so no frozen table is touched and `check_legacy_schema_drift.py` keeps comparing only the vendored file. |
| The company mapping is the part that could not be shared | PostgreSQL's `companies` has `name`; legacy's has `company_name`, and the two tables are different schemas with different owners. `PlatformAdminCompanyService` originally depended on the PostgreSQL `CompanyRepository`, which is what made it fail to start on MySQL. It now goes through `PlatformAdminCompanyDirectory`, with one implementation per profile — deliberately narrow (list, and set one company's status), because a wider interface would invite the admin surface to grow into the tenant domain. |
| A guard that had to be updated, not removed | `ProfileCoverageArchTest` required **every** `SecurityFilterChain` bean on `SecurityConfig` to carry a `@Profile`, on the reasoning that an unguarded chain is a general-purpose fallback live under both profiles at once. That is now deliberately true of the platform-admin API chain. Rather than relax the rule, dual-profile chains are an explicit allowlist: a name there is a decision someone made, and its matcher (`/api/platform-admin/**`) provably cannot collide with the legacy chain's (`/apis/**`). Anything else unguarded still fails the build. |
| Impact | **R-023 widens**: `phase1_extensions.schema.sql` now adds nine tables to the legacy database rather than one, and nothing in the application creates them. The failure mode is louder than `legacy_refresh_tokens`' — the admin surface cannot authenticate at all without them — which makes it more likely to be caught in a rehearsal and no less necessary to own. **Rollback** is restoring the two profile guards; the tables are additive and can be left in place. |
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

## D-164: ADR-0006 Part B resolved — ZKTeco terminals push over ADMS; the edge gateway is a fallback

| Field | Value |
|---|---|
| Decision | The ZKTeco attendance adapter is a receiver for the vendor's device-initiated ADMS / PUSH SDK HTTP protocol: each branch terminal is configured with the platform's ingest hostname and dials out itself, so no static IP, VPN or on-site software is needed per branch. The `.NET` edge-gateway boundary is retained only as a fallback for terminals that do not expose the Cloud Server Setting. The vendor-neutral core (device registry keyed by serial number, claim-before-ingest, raw immutable punch log with a synthesised idempotency key, deterministic pairing into `attendance`) is designed in `docs/superpowers/specs/2026-09-02-attendance-device-ingestion-design.md` under Part A's existing authority. |
| Status | **Accepted 2026-09-02 by the repository owner**, with one recorded condition: the hardware checklist in the specification's §4.3 must pass on the customers' actual models before the adapter is declared verified; its results are recorded in `docs/devices/` when it runs. Building Slice A is authorised now. Decided with the acceptance: **Q1** — device PINs map through a new `employee_device_identities` table (`UNIQUE (company_id, pin)`, seeded from numeric `employee_code`) rather than by overloading `employee_code`; **Q6** — devices are claimed by tenant `company_admin`/`hr` users through `/api/v1/devices/**`, authenticated with the existing legacy JWT (platform staff use a company admin's session for the pilot). |
| Owner | Repository owner. |
| Related ADR | ADR-0006 Part B (this proposal); ADR-0006 Part A (D-023, Accepted — the SPI this adapter implements); ADR-0013 (the Phase-1-owned-table provisioning question the new tables inherit); ADR-0012 (tenant resolution happens inside the trust boundary, from the registry, never from the payload). |
| Reason | On 2026-08-05 the protocol was unknown and the owner said so. It is now documented: ZKTeco's own PUSH SDK page and protocol document, four independent open-source receivers and a captured exchange from a real device agree on the handshake, the `ATTLOG` line format, the `getrequest`/`devicecmd` command loop and the offline-buffering behaviour. Of the three ways a punch can leave a terminal — pull over TCP 4370, manual export/import, device-initiated push — only push fits many branches behind NAT without per-branch infrastructure, which is the requirement that decides it. The pull path also carries the weaker security posture (`COMKey` default `0`) and model-dependent live capture. |
| Impact | `docs/devices/vendor-capability-matrix.md` and `attendance-device-model-and-firmware-inventory.md` are populated from documentation evidence, with evidence level marked and model/firmware still `Not yet discovered`. ADR-0006 gains the proposed resolution and an explicit acceptance test in place of an open search; R-004 is updated; PMR-04's documentation half closes and its hardware half is now a checklist. **Slice A is implemented in the same change**: a new `com.workin.devices` module (registry, PIN identities, idempotent raw punch log, ZKTeco ADMS receiver, tenant device API), scanned only by `LegacyPersistenceConfig` under `phase1-mysql`; five Phase-1-owned tables appended to `phase1_extensions.schema.sql`; `/iclock/{cdata,getrequest,devicecmd}` and their own permit-all chain exist only when `app.devices.ingest.enabled=true` (default false); `/api/v1/devices/**` is added to the legacy security chain's matcher and stays behind `authenticated()`; `devices.*` message keys in both catalogs; endpoint inventory in `docs/api/device-endpoints.md`; operator steps in `docs/devices/zkteco-adms-receiver-setup.md`. Verification: the specification's §13 — 54 device tests plus the extended route inventory, all green with the existing guards. **An independent review round of the implementation found nine correctness defects and eight security or quality ones, all fixed before hand-off** (§8, §13); three are worth naming here because each was a silent data-loss path rather than a visible failure: a Unix-seconds timestamp was converted through the device's zone twice, so both stored times were wrong by that offset and a punch near midnight landed on the wrong day; a batch posted as `application/x-www-form-urlencoded` had its body consumed while the servlet parameter map was built, so the punches parsed as none and the terminal was told `OK`; and the device-supplied `ATTLOGStamp` was stored and echoed unvalidated, which let a CR/LF write extra lines into the handshake and a far-future value tell a terminal that everything it still held had already been received. **Rollback** is the flag for the receiver, `PATCH is_active:false` for one device, and no schema step, since no existing table changed. The design also surfaces seven owner decisions (specification §12: Part B acceptance, PIN identity, device punches under the two-hour rule, biometric templates, `attendance.method` expansion timing, who claims devices in the pilot, production provisioning of Phase-1-owned tables) — recorded in `open-questions.md`. |
| Follow-up | Owner answers the remaining §12 questions (Q2, Q3, Q5, Q7, and **Q8** — proof of possession when claiming a device, **R-042**, which review showed cannot be closed in code while tenants do the claiming). Slice A shipped with this decision (verification plan §13); §4.3 is executed on the first real terminal and its results land in the two `docs/devices/` documents. **Numbering note:** two sessions were active in this repository on 2026-09-02; if another branch claims D-164 first, this entry is renumbered, not merged over. |
| Evidence | `docs/superpowers/specs/2026-09-02-attendance-device-ingestion-design.md` (§1.2 lists every external source with a link); `docs/adr/ADR-0006-attendance-edge-gateway-direction.md` (Part B, updates dated 2026-09-02); `docs/devices/*.md`; `hr-legacy/apis/helpers/attendance_excel_analyzer.php` and `LegacyAttendanceImportReader` for the existing fingerprint-export path and the `employee_code`-as-PIN practice; `workin-hr/hr-platform#12`. |

## D-165: The attendance-device product decisions — punch retention, biometrics, the `method` enum, provisioning, and a platform-mediated claim model for production

| Field | Value |
|---|---|
| Decision | The five questions D-164 left open are answered by the repository owner. **Q2 — device punches are never rejected.** The legacy two-hour minimum-gap rule does not apply to `method='device'`: the punch is always persisted, a short duplicate/debounce window suppresses a double-read, and a rapid re-check-in is *flagged for review* rather than refused. **Q3 — no biometric templates in Phase 1**: attendance events and metadata only. **Q5 — `attendance.method` gains `'device'` as an expand-only migration shipped with Slice B**, after an audit of every frozen-PHP branch that depends on the current enum values (that audit is complete; see Reason). **Q7 — production provisioning of the Phase-1-owned tables must be explicitly solved before device ingestion is enabled in production**, not discovered at cutover. **Q8 — the claim model differs between pilot and production**: supervised tenant `company_admin`/`hr` claiming is acceptable for the pilot, but production must not allow an arbitrary tenant admin to claim a device by serial number. Platform staff pre-allocate device ownership to a company; tenant HR then assigns an already-owned device to a branch. An **audited unclaim / transfer / replace-device path is required before broad production rollout** — manual database correction is not an acceptable long-term recovery route. Separately, the §4.3 hardware validation becomes a **hard prerequisite**: it does not block merging Slice A while the feature flag is off, and it does block both calling the adapter hardware-verified and enabling it for any real customer. |
| Status | Accepted 2026-09-02 by the repository owner, on PR #162. |
| Owner | Repository owner. |
| Related ADR | ADR-0006 Part B (D-164). Q8's production half interacts with **ADR-0010**'s authorization model and **ADR-0015**'s platform-admin surface, since platform-mediated allocation is a privileged operation and F-26 (individual platform-admin identity) is already a P0 release gate for exactly that class of action. |
| Reason | **Q2**: a terminal cannot render an error — it has already told the employee "Thank you" — so refusing a punch at the API boundary would destroy biometric evidence of presence with nothing to show the person. Flagging preserves both the record and the anti-fraud signal, and QR check-in already bypasses the same rule today, so a method-specific rule is not a new precedent. **Q5's audit, performed 2026-09-02 across frozen PHP, the dashboard and the Java port**: every site that touches `attendance.method` *writes* it — `check_in.php:58` and `create.php:111` (`?? 'app'`), `check_in_qr.php:71` (`'qr'`), `attendance_excel_analyzer.php:1019` and `xlsx_parser.php:615` (`'excel'`), `request_actions_helper.php:170` (`'app'`) — and exactly one site *reads* it: `dashboard/pages/employees/detail.php:82` renders `clean($a['method'])` verbatim. There is no comparison, no `switch`, no `WHERE method =` filter, no i18n label keyed by the value, and no export column anywhere; `ATTEND_APP`/`ATTEND_QR`/`ATTEND_EXCEL` in the dashboard's constants are used only as a form default for HR-entered rows. Widening the enum is therefore backward compatible in both deployment directions. **Q8**: a serial number is printed on the unit and the protocol offers no proof of possession (R-042), so tenant-initiated claiming cannot be made safe by any guard inside the application — the fix has to move who is allowed to establish ownership, which is a product decision rather than an engineering one. |
| Impact | No code changes in PR #162: Q3 is already implemented (`TransFlag` excludes template transfer and the receiver discards any that arrive), Q2 and Q5 are Slice B, Q8's production model and the unclaim/transfer/replace path are their own slice, and Q7 and §4.3 are deployment gates. What changes here is the governing record: the specification's §4.3, §7.3, §11 and §12, `docs/api/device-endpoints.md`, `docs/devices/zkteco-adms-receiver-setup.md`, ADR-0006, R-023, R-042 and `open-questions.md`. **Two residual Q5 caveats are recorded rather than assumed away**: the dashboard's employee-detail page will render the literal word `device` until it is given a label, and the Flutter clients could not be checked because they are pinned submodule references that no clone populates (PMR-02) — if either app renders `method` to an employee, it must be verified before Slice B ships. **Migration shape**: `attendance` holds 36,316 rows / 64 MB, and adding a fourth value to a `≤255`-value enum does not change its one-byte storage, so the `ALTER` should be `ALGORITHM=INSTANT` and is trivial even if it copies. Old PHP writing `app`/`qr`/`excel` and new Java writing `device` both work throughout, so no deployment order is imposed. |
| Follow-up | Slice B carries Q2's debounce-and-flag rule and Q5's expand-only migration, and must first verify the Flutter clients' handling of `method`. A separate slice carries Q8's production model (platform-mediated allocation, tenant assignment to a branch) and the audited unclaim/transfer/replace path; **R-042 stays open until both exist**. Q7 is an operations decision owed before the ingestion flag is turned on in production. §4.3 is owed before any real customer is connected. |
| Evidence | `hr-legacy/apis/api/attendance/{check_in,check_in_qr,create}.php`, `apis/helpers/{attendance_excel_analyzer,xlsx_parser,request_actions_helper}.php`, `apis/config/enums.php:76-81`, `dashboard/pages/employees/detail.php:82`, `dashboard/includes/constants.php:65-67` (the complete `method` consumer inventory above, re-read 2026-09-02); `docs/migration/table-volume-analysis.md` (36,316 rows); `docs/superpowers/specs/2026-09-02-attendance-device-ingestion-design.md` §4.3, §7.3, §12; **R-042**, **R-023**; PR #162. |

## D-166: The independent review of Slice A — the resume stamp is never trusted, uploads are bounded by record count, and a punch remembers its branch

| Field | Value |
|---|---|
| Decision | The independent review gate (`chatgpt-codex-connector[bot]`, D-121) reviewed PR #162 and raised ten findings — four P1. All are accepted and fixed on the same branch, and four change behaviour the design had already described, so they are recorded here rather than only in the diff. **(1) The device's `ATTLOGStamp` is never echoed back.** The handshake always answers `ATTLOGStamp=0`. **(2) Uploads are capped by record count as well as bytes** (`app.devices.ingest.max-records-per-upload`, default 5000) and refused whole above it; operation-log inserts are batched into one statement. **(3) `device_punches` carries `branch_id`, snapshotted at ingestion**, instead of the reader reporting the device's current branch. **(4) A company's device rows are deleted with the company** — the five tables join `LegacyCompanyDelete`'s cascade; its *preview* payload is deliberately not extended. The other six: wall clocks parse strictly; an epoch punch carries its instant, which also keys its dedup hash; an explicit PIN binding stops resolving when its employee is deactivated; an `employee_code` matched by the column's collation is normalised back to the queried PIN; an over-long serial is refused rather than registered as its prefix; and a device zone must be whole-hour across its transitions, not only today. |
| Status | Accepted 2026-09-02. Codex's review of commit `8904f72` is the gate round AGENTS.md requires; the fixes are a later head and are therefore **unreviewed until review is re-requested**, which is done before merge. |
| Owner | Repository owner (merge); the fixes are this session's work under D-164/D-165. |
| Related ADR | ADR-0006 Part B (D-164); D-165 (the product decisions); **D-121** (the independent-review gate); **D-111** (why the deletion preview is not extended); **R-041**, whose mitigation list this corrects. |
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
| Related ADR | D-164, D-165, D-166; **D-111** (why branch deletion cascades rather than refuses, and why neither deletion preview is extended); **D-078** (why the PIN binding is cleaned outside `CASCADE_TABLES`); **R-023**/Q7 (why every new lifecycle statement tolerates an absent table); **R-042** (attribution). |
| Reason | The first round's fixes introduced a column and a policy — `device_punches.branch_id`, and a handshake that always asks for a replay — whose consequences reached code the module does not own. That is the shape worth naming: a table with no foreign key has no lifecycle unless someone writes one, and "always resend" is only safe where an idempotency key already exists. The punches had one; the operation logs did not. |
| Impact | `LegacyEmployeeStore` and `LegacyBranchService` each gain one tolerant statement, neither changing its route's response; `device_operation_logs` gains a `dedup_key` and is written with `INSERT IGNORE`; `unclaimed_device_sightings` prunes on a genuinely new serial; `DeviceInput.isValidPin` is the single PIN rule; `devices.is_active_invalid` is a new message key in both catalogs. **Neither deletion preview is extended** — company or employee — for the D-111 reason already recorded in D-166. **R-042's attribution claim is corrected rather than defended**: legacy issues a `type=company` token for a company admin, which identifies the company and not a person, so `registered_by_employee_id` is null for those claims; an individual actor is recorded only for employee tokens, and real per-person attribution depends on F-26, which production claiming is gated on anyway. Device tests 65 → 72. |
| Follow-up | Unchanged from D-165/D-166: the §4.3 hardware checklist, Q7 provisioning, and slice B′ (platform-mediated allocation plus the audited unclaim/transfer/replace path) all gate production. |
| Evidence | PR #162 review comments on commit `3f6d5d5` (eleven findings); `LegacyEmployeeStore.deleteDeviceIdentity`; `LegacyBranchService.deactivateDevicesOfDeletedBranch`; `DeviceAttendanceEvent.contentKey`; `UnclaimedDeviceSightingStore.RETENTION_DAYS`; `DeviceInput.isValidPin`; `DeviceManagementService.requiredBoolean` and its `TransactionTemplate`; `ZkTecoAdmsService.exceedsRecordCap`. Regression tests named in the specification's §13. |

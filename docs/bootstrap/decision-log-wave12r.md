# WorkIn Decision Log — Wave 12.R Continuation

This file continues `docs/bootstrap/decision-log.md` after D-107. It is split only to keep the Wave 12.R decisions independently reviewable; earlier decisions remain authoritative except where a later entry explicitly corrects or supersedes a Phase-1 detail.

## D-108: Retrofit `branches` onto the PHP wire contract

**Status:** Accepted 2026-08-25.

All six `branches` endpoints move from the transitional `/api/legacy/branches` REST surface to the literal `/apis/api/branches/*.php` surface required by D-074. The retrofit preserves the already-reviewed D-056 through D-060 business behavior while restoring PHP method order, envelope, snake-case body keys, query `id`, and fresh lexical database reads for the `branches.created_at` `TIMESTAMP` column. D-060's company-scoped 404 remains the accepted security divergence instead of reproducing the legacy cross-tenant disclosure.

`LegacyWireExceptionHandler` targets `LegacyBranchController` by assignable type while `departments` and `job_titles` still share its parent package and retain their transitional response contract. The two D-071-shaped coercion gaps for uncast numeric branch fields remain disclosed measurement gaps rather than being silently normalized.

Evidence: the frozen `hr-legacy` branch endpoints, schema inspection, `LegacyBranchEndToEndTest`, the route inventory, and the full backend suite on the Wave 12.R branch.

## D-109: Whole-test-tree sweep is mandatory for every retrofit slice

**Status:** Accepted 2026-08-25.

A retrofit may delete a transitional `/api/legacy/**` route that unrelated tests borrowed as a generic guarded business endpoint. D-107 exposed this by breaking three authentication tests outside the exception-type module. Therefore every remaining Wave 12.R slice must search the complete test tree for both the old route and its DTO types before it is declared complete, then rerun the affected shared-auth tests in addition to the module's own tests.

The D-107 regression reproduced under the CI runner environment and was fixed by repointing the shared tests to the literal PHP route and asserting the D-074 envelope rather than the old `ApiErrorBody` shape. The rule applies especially strongly to `auth/login_employee`, which is reused as test setup by other modules.

Evidence: CI run 32880837978, the affected `LegacyHrPermissionEnforcerEndToEndTest`, `LegacyLoginEndToEndTest`, and `LegacyTenantContextIsolationTest`, plus the corrected full-suite run recorded on PR #120.

## D-110: Final Wave 12.R public-route boundary

**Status:** Implemented on PR #120, pending final full-suite validation.

The remaining public compatibility surface is implemented as five literal `departments` routes, five literal `job_titles` routes, and `auth/login_employee.php`. The organization adapters reuse the already-reviewed Wave 12.3 business services and own the PHP wire concerns: method order, original query/body coercion, snake-case fields, envelope rendering, and fresh lexical database rows. The employee-login endpoint is a direct compatibility port of the frozen PHP source; D-111 below supersedes the earlier draft of this decision that had incorrectly retained the new-platform refresh-token design on this Phase-1 route.

The bidirectional `/apis/**` inventory is raised from 114 to 125 routes. The three binary/report endpoints remain deliberately excluded: `attendance/overall_report.php`, `attendance/export.php`, and `payslips/export.php`.

The transitional `/api/legacy/**` aliases are not part of the client contract or the 125-route inventory. They may remain temporarily as regression-harness compatibility while broad pre-D-074 tests are repointed; their presence must never be used by mobile, desktop, or web clients and must not change the literal `/apis/**` behavior.

## D-111: Phase 1 is a zero-client-change PHP-to-Java replacement

**Status:** Accepted 2026-08-25.

Phase 1 has one compatibility invariant: replacing PHP with Java must require no changes to the existing mobile application, desktop application, or web administration client. The frozen PHP application at `d113204c8a2cf83b997c5e65c6c86e4f59b3f8f6` is therefore authoritative for the Phase-1 client-visible contract. Java must preserve the PHP route, HTTP method behavior and guard order, query/body coercion, status codes, response envelope and fields, authentication token shape, session side effects, and legacy business quirks unless a separately recorded exception is required to prevent a concrete critical vulnerability.

For employee login this means `auth/login_employee.php` returns the same `token` plus public `employee` payload and does not add a refresh token. Login increments `employees.token_version`, deletes that employee's existing push-token rows, re-reads the new token version, and issues the same hand-written HS256 JWT payload as PHP: `type`, `employee_id`, `company_id`, `role`, `token_version`, and `exp`. The configured lifetime remains the PHP value of 87,600 hours, or ten years, for Phase 1.

The compatibility security chain also accepts the frozen company JWT used by desktop/company login: `type=company`, `company_id`, `role`, and `exp`. PHP only applies the employee session-version check when `type=employee`; Java reproduces that distinction so a company token is not incorrectly required to contain an employee id or token version.

The short-lived access-token and rotating-refresh-token design remains valid as the target authentication architecture for a later modernization phase. It is not permitted to alter the literal Phase-1 PHP-compatible `/apis/**` contract. Any migration to that design must be a separately planned client-compatible rollout after the PHP-to-Java replacement is proven complete.

Evidence: frozen `apis/api/auth/login_employee.php`, `apis/api/auth/login_desktop.php`, `apis/helpers/functions.php`, `apis/config/auth.php`, and `apis/config/constants.example.php`, plus `LegacyLoginEndToEndTest` and the Phase-1 security-chain tests.

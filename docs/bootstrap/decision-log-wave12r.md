# WorkIn Decision Log — Wave 12.R Continuation

This file continues `docs/bootstrap/decision-log.md` after D-107. It is split only to keep the append-only Wave 12.R decisions independently reviewable; earlier decisions remain authoritative in the primary log.

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

**Status:** Implemented on PR #120, pending full CI validation.

The remaining public compatibility surface is implemented as five literal `departments` routes, five literal `job_titles` routes, and `auth/login_employee.php`. The organization adapters reuse the already-reviewed Wave 12.3 business services and own only PHP wire concerns: method order, original query/body coercion, snake-case fields, envelope rendering, and fresh lexical database rows. The employee-login adapter reuses `LegacyLoginService` so the accepted short-lived access-token plus rotating-refresh-token security model remains intact while restoring the legacy route, required-field behavior, `login_successful` envelope, token field, and employee payload.

The bidirectional `/apis/**` inventory is raised from 114 to 125 routes. The three binary/report endpoints remain deliberately excluded: `attendance/overall_report.php`, `attendance/export.php`, and `payslips/export.php`.

The transitional `/api/legacy/**` aliases are retained only until the replacement routes pass the complete regression suite; alias retirement is the final closure action for Wave 12.R and is not considered complete merely because the new routes compile.

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

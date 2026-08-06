# Authorization Runtime Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Permission evaluation with ADR-0010 precedence, role bundles, overrides schema, and runtime `@RequiresPermission` enforcement, per `docs/superpowers/specs/2026-08-06-authorization-runtime-design.md`.

**Architecture:** Effective permissions computed inside `establishContext`'s transaction (the `SET LOCAL` RLS scope lives and dies with it); a `HandlerInterceptor` enforces `@RequiresPermission` and stashes the validated context as a request attribute; the F-23 freeze rule is replaced by a platform-package confinement rule in the same PR.

**Tech Stack:** as established (Java 25 / Spring Boot 4.1 / Flyway / ArchUnit / Testcontainers via WSL).

## Global Constraints

- Evaluation must run on the same connection/transaction as `establishContext`'s `SET LOCAL` (shared `EntityManager`), or overrides are invisible and the wrong thing fails closed.
- Precedence exactly: effective = (roleGranted ∪ allows) − denies; absence = deny.
- No cross-request caching (Dimension 5); request-attribute stash only.
- 403 denials carry no explanatory detail (§8).
- Freeze-rule removal and enforcement land in the same PR (the rule's own condition).
- Tabs in Java; plan code blocks use 4-space indent for markdownlint only.

---

### Task 1: Schema + evaluation service + context wiring (service-level TDD)

**Files:**

- Test: `backend/src/test/java/com/workin/backend/authorization/PermissionEvaluationTest.java`
- Create: `db/migration/common/V18__create_membership_permission_overrides.sql`, `db/migration/rls/V19__enable_overrides_row_level_security.sql`, `db/migration/common/V20__seed_role_permission_defaults.sql`
- Create: `authorization/OverrideEffect.java`, `authorization/MembershipPermissionOverride.java` (entity: id, membershipId, companyId, permissionId, `@Enumerated(STRING)` effect), `authorization/MembershipPermissionOverrideRepository.java` (`List<MembershipPermissionOverride> findByMembershipId(Long)`), `authorization/PermissionEvaluationService.java`
- Modify: `tenancy/AuthorizationContext.java` (add `Set<String> permissions`, `hasPermission`), `tenancy/TenantContextService.java` (compute permissions before constructing context), `tenancy/TenantController.java` (five-arg record — pass through), any other `AuthorizationContext` constructors.

**Interfaces:**

- `PermissionEvaluationService.effectivePermissionsFor(Long membershipId, List<TenantRole> roles)` → `Set<String>`; must be called inside an active tenant-scoped transaction. Role-granted keys via `EntityManager` native query: `SELECT p.permission_key FROM role_permissions rp JOIN permissions p ON p.id = rp.permission_id WHERE rp.role IN (:roles)` (names as strings); overrides via the JPA repository; permission_id → key resolution for overrides via native query join (`SELECT p.permission_key, o.effect FROM membership_permission_overrides o JOIN permissions p ...  WHERE o.membership_id = :membershipId`) — one query, skip the entity round-trip if simpler (entity still exists for future admin endpoints; keep repository minimal).
- `AuthorizationContext(Long identityId, Long membershipId, Long companyId, List<TenantRole> roles, Set<String> permissions)` + `boolean hasPermission(String key)`.

Steps: write `PermissionEvaluationTest` (fixtures via flywayDataSource SQL — company/identity/membership/role rows, override rows joined by permission key) asserting: COMPANY_ADMIN gets `employees.read` but never `platform.companies.read`; DENY override beats COMPANY_ADMIN role grant; ALLOW grants `employees.read` to an HR membership; HR with nothing → empty-of-that-key. Call `tenantContextService.establishContext(...)` directly (it is `@Transactional`, self-contained) and assert on `context.permissions()`. Run red (compile) → migrations + code → green → commit.

V20 seed SQL:

```sql
INSERT INTO role_permissions (role, permission_id)
SELECT 'COMPANY_ADMIN', p.id FROM permissions p
WHERE p.permission_key NOT LIKE 'platform.%';
```

(HR/MANAGER/EMPLOYEE: no rows — see spec §1 rationale, restated in the migration comment.)

---

### Task 2: Enforcement interceptor + arch-rule swap (HTTP-level TDD)

**Files:**

- Test: `backend/src/test/java/com/workin/backend/authorization/AuthorizationEnforcementFlowTest.java` (with nested `@TestConfiguration` probe: `@RestController` bean, `@RequiresPermission(PermissionKeys.EMPLOYEES_READ)` `@GetMapping("/test/authorization/employees-read-gated")` returning the stashed context's membershipId)
- Create: `authorization/AuthorizationPolicyInterceptor.java`, `authorization/AuthorizationPolicyWebConfig.java` (WebMvcConfigurer registering it)
- Modify: `AuthorizationPolicyArchTest` (drop `REQUIRES_PERMISSION_IS_FROZEN` + its fixture test; add `REQUIRES_PERMISSION_STAYS_OUT_OF_THE_PLATFORM_DOMAIN` — `noMethods().that().areDeclaredInClassesThat().resideInAPackage("com.workin.backend.platformadmin..").should().beAnnotatedWith(RequiresPermission.class).allowEmptyShould(true)` — plus a fixture test)
- Replace fixture: delete `RequiresPermissionUsageFixture`, add `archfixtures/platformadmin/PlatformRequiresPermissionFixture.java` (package must match the rule's predicate — put class in `com.workin.backend.platformadmin.archfixtures` under test sources with a handler carrying `@RequiresPermission`; adjust rule/fixture packages so the fixture matches `..platformadmin..`)

**Interceptor core:**

```java
@Override
public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
    if (!(handler instanceof HandlerMethod handlerMethod)) {
        return true;
    }
    RequiresPermission required = handlerMethod.getMethodAnnotation(RequiresPermission.class);
    if (required == null) {
        return true;
    }
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedPrincipal principal)) {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        return false;
    }
    try {
        AuthorizationContext context = tenantContextService.establishContext(
                principal.identityId(), principal.claimedMembershipId(), principal.claimedCompanyId());
        if (!context.hasPermission(required.value())) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            return false;
        }
        request.setAttribute(AuthorizationContext.class.getName(), context);
        return true;
    } catch (TenantContextException ex) {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        return false;
    }
}
```

Flow test cases: COMPANY_ADMIN 200; HR-fixture identity 403; ALLOW override → 200 then flip to DENY → same-token next request 403 (F-19); `UPDATE tenant_memberships SET status='DISABLED'` → 403 (F-20); no token → non-2xx. Fixture SQL mirrors `PermissionEvaluationTest`/`AuthFlowTest` patterns (login via HTTP for tokens).

Steps: write test → red → implement → arch-test updates → green → commit.

---

### Task 3: Full verification + docs

- Full suite `--rerun` green; record totals.
- Matrix: F-15 row — mechanism/table/precedence live, remaining = legacy-data conversion run near cutover; F-17 row — matrix tests exist for shipped roles/keys, completes per-module as endpoints arrive; F-19 row — done (test named); F-20 row — done (disabled-membership case now covered; cross-ref ADR-0010 task 7 note); F-23 row — note the freeze swap to platform-confinement rule.
- markdownlint clean; commit; PR via gh.

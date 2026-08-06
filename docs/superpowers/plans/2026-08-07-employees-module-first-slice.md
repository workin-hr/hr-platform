# Employees Module First Slice Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Tenant-scoped employee administration (list/get/create/update-names) gated by catalog permissions, per `docs/superpowers/specs/2026-08-07-employees-module-first-slice-design.md`.

**Architecture:** First application-service layer: `@Transactional` methods re-apply the RLS session variable from the validated `AuthorizationContext` before querying (the interceptor's `SET LOCAL` died with its own transaction), with explicit `company_id` filters as defense in depth. No credential surface by construction.

**Tech Stack:** as established; tests via WSL.

## Global Constraints

- Cross-tenant and nonexistent ids are indistinguishable 404s (§8).
- `Employee` entity never maps `password_hash`; DTOs never carry credentials.
- Every service query filters by `context.companyId()` even though RLS scopes it.
- `active`/`role` are read-only in this surface.
- Tabs in Java; 4-space indent in this plan's code blocks (markdownlint).

---

### Task 1: TenantSessionVariable extraction (no behavior change)

**Files:** Create `tenancy/TenantSessionVariable.java` (`@Component`, `apply(Long companyId)` running the existing `set_config` native query via `EntityManager`); Modify `TenantContextService` and `RegistrationService` to inject and use it, deleting their private copies.

Steps: extract → run `--tests 'com.workin.backend.identity.*' --tests 'com.workin.backend.tenancy.*'` green → commit `refactor(backend): extract TenantSessionVariable from the two set_config copies`.

### Task 2: Employees module (TDD)

**Files:**

- Test: `backend/src/test/java/com/workin/backend/employees/EmployeeModuleFlowTest.java`
- Create: `employees/Employee.java`, `employees/EmployeeRepository.java`, `employees/EmployeeService.java`, `employees/EmployeeController.java`, `employees/CreateEmployeeRequest.java` (`@NotBlank firstName`, `lastName` default "", optional `phone`), `employees/UpdateEmployeeRequest.java` (`@NotBlank firstName`, `lastName`), `employees/EmployeeView.java` (`id, firstName, lastName, phone, role, active`)

**Interfaces:**

- `EmployeeService` (all `@Transactional`, all take `AuthorizationContext context` first arg, all start with `tenantSessionVariable.apply(context.companyId())`):
  - `List<EmployeeView> list(AuthorizationContext)` — `findByCompanyIdOrderById`
  - `Optional<EmployeeView> get(AuthorizationContext, Long employeeId)` — `findByIdAndCompanyId`
  - `EmployeeView create(AuthorizationContext, CreateEmployeeRequest)` — catches `DataIntegrityViolationException` → 409 (phone unique), same as RegistrationService
  - `Optional<EmployeeView> updateNames(AuthorizationContext, Long employeeId, UpdateEmployeeRequest)`
- `EmployeeController` (`/api/tenant/employees`): GET list + GET `/{id}` `@RequiresPermission(PermissionKeys.EMPLOYEES_READ)`; POST (201) + PUT `/{id}` `@RequiresPermission(PermissionKeys.EMPLOYEES_MANAGE)`; empty `Optional` → `ResponseStatusException(NOT_FOUND)` with no detail; context read from `request.getAttribute(AuthorizationContext.class.getName())`, throwing `IllegalStateException` if absent.

**Test cases** (fixtures reuse `AuthorizationEnforcementFlowTest` patterns — register admin via HTTP, HR member + overrides via SQL):

1. Admin round-trip: POST 201 (body echoes names, `role=EMPLOYEE`, `active=true`, no password field in JSON) → GET list contains it → GET by id 200 → PUT 200 changes names → GET reflects.
2. Duplicate phone → 409.
3. HR without grants: GET list 403, POST 403.
4. HR + `employees.read` ALLOW override: GET list 200; POST still 403 (read/manage separation).
5. F-18 cross-tenant: two companies; B's admin GET A's employee id → 404; PUT A's employee → 404; B's list does not contain A's employee.
6. Unauthenticated GET → non-2xx.
7. Response JSON never contains `"password"` (assert on raw body string).

Steps: write test → red (compile) → implement → green → commit `feat(backend): employees module first slice -- permission-gated tenant-scoped CRUD (F-18 negatives included)`.

### Task 3: Full verification + docs

- Full suite `--rerun` green; record totals.
- Matrix: F-18 row — first module covered (employees), pattern set, closes per-module; hr-legacy#2/#3 rows — acceptance criterion now has real tests for the employees read/mutation shapes (cross-tenant 404s; no credential surface); F-17 note — employees.read/manage combinations covered.
- markdownlint; commit; PR via gh after human push.

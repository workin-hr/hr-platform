# Manager Resource-Scoping First Slice Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The first `membership_resource_scopes` enforcement (branch/department manager scoping) with attendance as the proof surface, per `docs/superpowers/specs/2026-08-07-manager-resource-scoping-first-slice-design.md`.

**Architecture:** `ResourceScopeService` + entity/repo in `com.workin.backend.authorization` (enforcement boundary 3, beside `PermissionEvaluationService`); V31 table + V32 RLS; scope administration extends the members module; `AttendanceService` gains one scope guard per method.

**Tech Stack:** Spring Boot, JPA (native queries via `EntityManager`, the `PermissionEvaluationService` precedent), Flyway, Testcontainers via WSL.

## Global Constraints

- Everything the prior module plans' constraints say (tenant scoping; `tenantSessionVariable.apply(...)` first; records; foreign/nonexistent → the same 404; no Lombok).
- Scope-limited iff roles include `MANAGER` and neither `COMPANY_ADMIN` nor `HR` (company-wide roles trump). Scope-limited + no scope rows → reaches zero employees.
- Reachable set = employees whose `branch_id` ∈ BRANCH scope ids OR `department_id` ∈ DEPARTMENT scope ids. Scope rows are permission-agnostic in this slice.
- `ResourceScopeService` reads per-request, no cross-request cache (Dimension 5).
- Run `markdownlint-cli2@0.23.2` on `**/*.md` before any docs push.

---

### Task 1: Schema (V31/V32) + ResourceScopeService (TDD)

**Files:**

- Create: `backend/src/main/resources/db/migration/common/V31__create_membership_resource_scopes.sql`, `backend/src/main/resources/db/migration/rls/V32__enable_membership_resource_scopes_row_level_security.sql`
- Create: `authorization/ResourceScope.java`, `authorization/ResourceScopeType.java`, `authorization/ResourceScopeRepository.java`, `authorization/ResourceScopeService.java`
- Test: `backend/src/test/java/com/workin/backend/authorization/ResourceScopeServiceTest.java`

**Interfaces:**

- `enum ResourceScopeType { BRANCH, DEPARTMENT }`.
- `ResourceScope` entity maps V31 (`id`, `membershipId`, `companyId`, `scopeType` `@Enumerated(STRING)`, `scopeId`); constructor `(membershipId, companyId, scopeType, scopeId)`.
- `ResourceScopeRepository`: `List<ResourceScope> findByMembershipId(Long)`, `Optional<ResourceScope> findByMembershipIdAndScopeTypeAndScopeId(Long, ResourceScopeType, Long)`.
- `ResourceScopeService` (methods take `AuthorizationContext`, run in the caller's tenant transaction; `EntityManager` for the native reachable-set query):
  - `boolean isScopeLimited(AuthorizationContext c)` → `c.roles().contains(MANAGER) && !c.roles().contains(COMPANY_ADMIN) && !c.roles().contains(HR)`.
  - `Set<Long> reachableEmployeeIds(AuthorizationContext c)` → native query: `SELECT e.id FROM employees e WHERE e.company_id = :companyId AND (e.branch_id IN (:branchIds) OR e.department_id IN (:deptIds))`, where branchIds/deptIds come from this membership's scope rows; **if both id lists are empty, return an empty set without querying** (deny-by-default). Guard the SQL against empty `IN ()` by branching per non-empty list.
  - `boolean canReachEmployee(AuthorizationContext c, Long employeeId)` → `reachableEmployeeIds(c).contains(employeeId)` (fine for single checks at this scale).

- [ ] **Step 1: V31**:

```sql
-- ADR-0010 enforcement boundary 3 (docs/architecture/authorization-model.md
-- section 3). scope_id references a branch or department by scope_type
-- (no FK -- it points at two possible tables; the service validates the
-- reference in-tenant). BRANCH/DEPARTMENT only this slice; DIRECT_REPORT
-- and EMPLOYEE (F-25) deferred, excluded by the CHECK. company_id
-- denormalized for RLS.
CREATE TABLE membership_resource_scopes (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    membership_id BIGINT NOT NULL REFERENCES tenant_memberships (id),
    company_id BIGINT NOT NULL REFERENCES companies (id),
    scope_type VARCHAR(16) NOT NULL CHECK (scope_type IN ('BRANCH', 'DEPARTMENT')),
    scope_id BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT membership_resource_scopes_unique UNIQUE (membership_id, scope_type, scope_id)
);
CREATE INDEX membership_resource_scopes_membership_id_idx ON membership_resource_scopes (membership_id);
CREATE INDEX membership_resource_scopes_company_id_idx ON membership_resource_scopes (company_id);
```

- [ ] **Step 2: V32** — V14's enable+force+policy pattern for `membership_resource_scopes`.
- [ ] **Step 3: Failing test** — `ResourceScopeServiceTest extends AbstractIntegrationTest`, autowiring `ResourceScopeService` and `@Qualifier("flywayDataSource") DataSource`. Because the service methods require an active tenant transaction + `AuthorizationContext`, drive them through a tiny `@Transactional` test helper that calls `tenantSessionVariable.apply(companyId)` then the service (mirror how `PermissionEvaluationTest` exercises `PermissionEvaluationService`; read that test first for the transaction-wrapping idiom). Build `AuthorizationContext` records directly with the role lists under test. Cases: `isScopeLimited` true for `[MANAGER]`, false for `[MANAGER, HR]`, `[COMPANY_ADMIN]`, `[EMPLOYEE]`; `reachableEmployeeIds` for a MANAGER membership with one BRANCH scope returns exactly the employees in that branch (seed 2 branches × employees via SQL); with a DEPARTMENT scope returns that department's; with no scope rows returns empty; union when both a branch and a department scope are present.
- [ ] **Step 4: Red** → **Step 5: Implement** → **Step 6: Green** (via WSL) → **Step 7: Commit** — `feat(backend): membership_resource_scopes schema + ResourceScopeService (enforcement boundary 3)`.

### Task 2: Scope administration on the members surface (TDD)

**Files:**

- Test: extend `backend/src/test/java/com/workin/backend/members/MemberAdminFlowTest.java`
- Create: `members/AssignScopeRequest.java`, `members/ResourceScopeView.java`
- Modify: `members/MemberAdminService.java` (add scope list/assign/revoke + reference validation + audit), `members/MemberController.java` (3 endpoints), `members/TenantAuditService.java` is reused as-is (new action strings are just parameters)

**Interfaces:**

```java
public record AssignScopeRequest(@NotNull ResourceScopeType scopeType, @NotNull Long scopeId) {}
public record ResourceScopeView(ResourceScopeType scopeType, Long scopeId) {}
```

- `MemberAdminService` additions (all `@Transactional`, `tenantSessionVariable.apply` first, target membership resolved via `TenantMembershipRepository.findByIdAndCompanyId` → `NotFound`; self-target → `SelfMutation`):
  - `listScopes(context, membershipId)` → `Optional<List<ResourceScopeView>>` (empty → 404).
  - `assignScope(context, membershipId, AssignScopeRequest)` → `MutationResult`: validate the `scopeId` is a tenant-owned row for its type (`BranchRepository.findByIdAndCompanyId` / `DepartmentRepository.findByIdAndCompanyId`) → `NotFound`; duplicate (repo `findByMembershipIdAndScopeTypeAndScopeId`) → `Duplicate`; else save `ResourceScope` + `tenantAuditService.record(context, membershipId, "SCOPE_ASSIGNED", null, scopeType + ":" + scopeId)`.
  - `revokeScope(context, membershipId, ResourceScopeType, Long scopeId)` → `MutationResult`: no row → `NotFound`; else delete + audit `"SCOPE_REVOKED"`.
- `MemberController`: `GET /{membershipId}/resource-scopes` (`members.read`); `POST /{membershipId}/resource-scopes` (201, `members.manage`); `DELETE /{membershipId}/resource-scopes/{scopeType}/{scopeId}` (204, `members.manage`). Reuse the existing `toResponse(MutationResult)` mapping (404/409/409/2xx) and `contextFrom`.

- [ ] **Step 1: Failing test** — add to `MemberAdminFlowTest`: admin assigns a BRANCH scope to a member (201) → GET lists it → assign again → 409 → revoke (204) → revoke again → 404; foreign/nonexistent `scopeId` (company B's branch; a department id passed as BRANCH type that isn't a branch) → 404; self-target → 409; audit rows `SCOPE_ASSIGNED`/`SCOPE_REVOKED` asserted via SQL with actor+target. (Add a branch/department created via the org APIs to the fixtures.)
- [ ] **Step 2: Red** → **Step 3: Implement** → **Step 4: Green** (via WSL) → **Step 5: Commit** — `feat(backend): resource-scope administration on the members surface (audited)`.

### Task 3: Attendance scope enforcement (TDD)

**Files:**

- Test: `backend/src/test/java/com/workin/backend/attendance/ManagerScopeFlowTest.java`
- Modify: `attendance/AttendanceService.java` (inject `ResourceScopeService`; guard every method)

**Interfaces:**

- Consumes `ResourceScopeService.isScopeLimited` / `reachableEmployeeIds` / `canReachEmployee` from Task 1.
- `AttendanceService` guards (all after `tenantSessionVariable.apply`, before returning/mutating):
  - `list`: if `isScopeLimited(context)`, compute `reachable = reachableEmployeeIds(context)` and filter the returned rows to `reachable.contains(row.getEmployeeId())` (compose with the existing employeeId/date filters; an out-of-scope `employeeId` param then simply yields empty).
  - `get`: if scope-limited and `!canReachEmployee(context, row.getEmployeeId())` → return `Optional.empty()` (controller → 404).
  - `create`: after the existing employee-existence check, if scope-limited and `!canReachEmployee(context, request.employeeId())` → `MutationResult.NotFound` (uniform 404).
  - `update`/`delete`: after loading the row, if scope-limited and `!canReachEmployee(context, row.getEmployeeId())` → `MutationResult.NotFound`.

- [ ] **Step 1: Failing test** — `ManagerScopeFlowTest extends AbstractIntegrationTest`. Fixtures: register a company admin; create 2 branches + a department via the org APIs; create employees placed in branch A, branch B, and the department (via the employees API's attribution). Seed attendance rows for each (via the attendance API as admin). Create a pure-`MANAGER` membership (SQL: identity + membership + `membership_roles` MANAGER only) and grant it `attendance.read` via a `membership_permission_overrides` ALLOW. Cases:
  - manager with a BRANCH-A scope: `GET /api/tenant/attendance` returns only branch-A employees' rows; a branch-B row's `GET /{id}` → 404; `?employeeId=<branch-B emp>` → empty.
  - manager with a DEPARTMENT scope: sees only the department's employees' attendance.
  - manager with **no** scope rows: list empty, every `get` → 404 (deny-by-default).
  - `COMPANY_ADMIN` (the registrant) sees all rows regardless — unaffected.
  - a `MANAGER`+`HR` membership sees all — role trumps.
  - liveness: assign a BRANCH-B scope via the members API → the same manager's next `GET` now includes branch-B rows; revoke → excluded again.
  - `create` with `attendance.correct` granted: a scoped manager creating attendance for an out-of-scope employee → 404; in-scope → 201.
- [ ] **Step 2: Red** → **Step 3: Implement** → **Step 4: Green** (via WSL); confirm the existing `AttendanceModuleFlowTest` still passes unchanged (admin is never scope-limited) → **Step 5: Commit** — `feat(backend): attendance scope enforcement -- managers confined to assigned branches/departments (hr-legacy#17/#18)`.

### Task 4: Full verification + docs

- [ ] Full suite `--rerun` via WSL; totals from XML.
- [ ] Matrix: F-16 → mechanism live (enforcement exists; per-manager assignment moot today); F-17 note (manager-scope cases added); F-25 → **first slice done** (branch/department covered by `ManagerScopeFlowTest`; `DIRECT_REPORT`/`EMPLOYEE` tracked); F-18 row adds resource-scope admin; `hr-legacy#17`/`#18` rows note attendance over-reach is now structurally closed for the attendance surface.
- [ ] `python3 scripts/validate_phase0.py` exit 0; `markdownlint-cli2@0.23.2` clean.
- [ ] Commit `docs(migration): record manager resource-scoping first slice (F-16/F-25, hr-legacy#17/#18)`; PR after human push.

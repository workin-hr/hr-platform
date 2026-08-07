# Manager Scope Enforcement: Requests & Penalties Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extend the merged `ResourceScopeService` guard to requests and penalties (closing `hr-legacy#18`), per `docs/superpowers/specs/2026-08-07-manager-scope-requests-penalties-design.md`.

**Architecture:** Promote the two reach helpers into `ResourceScopeService`; refactor `AttendanceService` to use them (no behavior change); add one guard per employee-bound method in `PenaltyService` and `RequestService`; extend `ManagerScopeFlowTest`.

**Tech Stack:** Spring Boot, JPA, Testcontainers via WSL.

## Global Constraints

- Out-of-scope employee → uniform 404, **before** any other result (Locked/WrongState/InsufficientBalance).
- Non-scope-limited callers unaffected (single `isScopeLimited` branch).
- Run `markdownlint-cli2@0.23.2` on `**/*.md` before any docs push.

---

### Task 1: Reusable guard in ResourceScopeService (refactor)

**Files:**

- Modify: `backend/src/main/java/com/workin/backend/authorization/ResourceScopeService.java` (add 2 public methods), `backend/src/main/java/com/workin/backend/attendance/AttendanceService.java` (delegate to them, drop the private helpers)

**Interfaces — Produces:**

```java
// null = not scope-limited (unrestricted); else the reachable set.
public Set<Long> scopedEmployeeIdsOrNull(AuthorizationContext context) {
    return isScopeLimited(context) ? reachableEmployeeIds(context) : null;
}
public boolean isEmployeeInScope(AuthorizationContext context, Long employeeId) {
    return !isScopeLimited(context) || canReachEmployee(context, employeeId);
}
```

- [ ] **Step 1: Add the two methods** to `ResourceScopeService`.
- [ ] **Step 2: Refactor `AttendanceService`** — replace its private `scopedReachOrNull(context)` with `resourceScopeService.scopedEmployeeIdsOrNull(context)` and its private `canReach(context, id)` with `resourceScopeService.isEmployeeInScope(context, id)`; delete the two private helpers.
- [ ] **Step 3: Green (no behavior change)** — run `ResourceScopeServiceTest` + `ManagerScopeFlowTest` + `AttendanceModuleFlowTest` via WSL; all pass unchanged.
- [ ] **Step 4: Commit** — `refactor(backend): promote resource-scope reach helpers into ResourceScopeService`.

### Task 2: Penalties scope enforcement (TDD)

**Files:**

- Modify: `backend/src/main/java/com/workin/backend/penalties/PenaltyService.java` (inject `ResourceScopeService`; guard each method)
- Test: extend `backend/src/test/java/com/workin/backend/attendance/ManagerScopeFlowTest.java`

**Interfaces:** consumes Task 1's `scopedEmployeeIdsOrNull` / `isEmployeeInScope`.

- Guards (after `tenantSessionVariable.apply`):
  - `list`: `Set<Long> reach = resourceScopeService.scopedEmployeeIdsOrNull(context)`; filter `reach == null || reach.contains(p.getEmployeeId())`.
  - `get`: `.filter(p -> resourceScopeService.isEmployeeInScope(context, p.getEmployeeId()))` before `.map`.
  - `create`: change the employee lookup to also require scope — `employeeRepository.findByIdAndCompanyId(...).filter(e -> resourceScopeService.isEmployeeInScope(context, e.getId()))` then map (empty → 404).
  - `update`/`delete`: after `found`, if `found.isEmpty() || !resourceScopeService.isEmployeeInScope(context, found.get().getEmployeeId())` → `NotFound` — **before** the `isAppliedToPayroll()` Locked check.

- [ ] **Step 1: Failing test** — add `penaltiesRespectManagerScope` to `ManagerScopeFlowTest` (reuse its branch/employee/manager fixtures; grant the manager `penalties.read`+`penalties.manage` overrides; seed penalties for an in-branch and an out-of-branch employee via SQL). Assert: branch-scoped manager's `GET /api/tenant/penalties` returns only the in-branch penalty; `GET /{outOfScopeId}` → 404; `PUT`/`DELETE` on it → 404; create for an out-of-scope employee → 404; admin sees both.
- [ ] **Step 2: Red** → **Step 3: Implement** → **Step 4: Green** (via WSL; `PenaltyModuleFlowTest` unchanged) → **Step 5: Commit** — `feat(backend): penalties manager scope enforcement`.

### Task 3: Requests scope enforcement (TDD) — closes hr-legacy#18

**Files:**

- Modify: `backend/src/main/java/com/workin/backend/requests/RequestService.java` (inject `ResourceScopeService`; guard each method)
- Test: extend `ManagerScopeFlowTest`

**Interfaces:** consumes Task 1's methods.

- Guards:
  - `list`: filter rows on `scopedEmployeeIdsOrNull` like penalties.
  - `get`: `.filter(r -> isEmployeeInScope(context, r.getEmployeeId()))`.
  - `create`: extend the L94 employee-existence check — `... .isEmpty() || !resourceScopeService.isEmployeeInScope(context, request.employeeId())` → `NotFound`.
  - `update`/`delete`/`approve`/`reject`: after loading the request, if `!isEmployeeInScope(context, request.getEmployeeId())` → `NotFound`, **before** the pending-state / balance checks.

- [ ] **Step 1: Failing test** — add `requestsRespectManagerScopeIncludingApproval` to `ManagerScopeFlowTest`: a branch-scoped manager granted `requests.read`+`requests.approve` (and `requests.manage` for create) sees only in-branch requests; `approve`/`reject`/`get` on an out-of-scope request → 404 (the hr-legacy#18 scenario); admin unaffected. Seed a request type + requests for in/out-of-branch employees via the admin API / SQL.
- [ ] **Step 2: Red** → **Step 3: Implement** → **Step 4: Green** (via WSL; `RequestModuleFlowTest` unchanged) → **Step 5: Commit** — `feat(backend): requests manager scope enforcement -- closes hr-legacy#18 by construction`.

### Task 4: Full verification + docs

- [ ] Full suite `--rerun` via WSL; totals from XML.
- [ ] Matrix: `hr-legacy#18` → closed-by-construction (requests approve/reject scoped, `ManagerScopeFlowTest`); F-25 note (requests + penalties now enforced; advances/payslips/leave/salary/employees the remaining one-guard copies).
- [ ] `python3 scripts/validate_phase0.py` exit 0; `markdownlint-cli2@0.23.2` clean.
- [ ] Commit `docs(migration): record requests/penalties manager scope enforcement (hr-legacy#18)`; PR after human push.

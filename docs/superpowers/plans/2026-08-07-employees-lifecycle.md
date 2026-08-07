# Employees Lifecycle Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The activate/deactivate status endpoint per `docs/superpowers/specs/2026-08-07-employees-lifecycle-design.md`.

**Architecture:** No schema change; one endpoint on the existing employees module, template shapes throughout.

**Tech Stack:** Spring Boot, JPA, Testcontainers via WSL.

## Global Constraints

- `active` stays absent from create/update DTOs — lifecycle changes only through the status endpoint; idempotent re-set is a 200 no-op.
- Run `markdownlint-cli2@0.23.2` on `**/*.md` before any docs push.

---

### Task 1: Status endpoint (TDD)

**Files:**

- Create: `backend/src/main/java/com/workin/backend/employees/UpdateEmployeeStatusRequest.java`
- Modify: `backend/src/main/java/com/workin/backend/employees/Employee.java` (add `deactivate()`/`activate()` mutators), `EmployeeService.java` (add `updateStatus`), `EmployeeController.java` (add the PUT mapping)
- Test: `backend/src/test/java/com/workin/backend/employees/EmployeeModuleFlowTest.java` (add 3 tests)

**Interfaces:**

```java
public record UpdateEmployeeStatusRequest(@NotNull Boolean active) {}
// EmployeeService:
@Transactional
public Optional<EmployeeView> updateStatus(AuthorizationContext context, Long employeeId, boolean active)
// -> findByIdAndCompanyId; empty -> 404 at the controller; else deactivate()/activate() and return the view.
// EmployeeController:
@RequiresPermission(PermissionKeys.EMPLOYEES_MANAGE)
@PutMapping("/{employeeId}/status")
```

- [ ] **Step 1: Failing tests** — (a) `lifecycleToggleRoundTripsAndIsIdempotent`: create employee → PUT `{active:false}` → view inactive → PUT `{active:false}` again → 200, still inactive → PUT `{active:true}` → active; (b) `aDeactivatedEmployeeIsSkippedByPayrollCalculateUntilReactivated`: employee with a MONTHLY salary contract (via `/api/tenant/salary-contracts?employeeId=`, the `PayrollBatchLifecycleTest` fixture shape) → deactivate → create batch + calculate → SQL-assert zero payslips for the batch → reactivate → recalculate → one payslip; (c) `logoutNeverTouchesEmployeeLifecycle` (hr-legacy#15's structural proof): create employee → admin calls `POST /api/auth/logout` exactly the way `AuthSessionFlowTest` does (read that class first for the request shape, using the registration `AuthResponse.refreshToken()`) → SQL-assert the employee's `active` is still true. Also: cross-tenant status PUT → 404; `EMPLOYEES_READ`-only member → 403 (fold into (a)'s test or the existing permission test, implementer's choice — both assertions must exist).
- [ ] **Step 2: Red** — `compileTestJava` fails (missing record/method).
- [ ] **Step 3: Implement** per Interfaces; **Step 4: Green** — `EmployeeModuleFlowTest` fully green via WSL, XML counts; **Step 5: Commit** — `feat(backend): employee lifecycle status endpoint -- payroll skip proof and hr-legacy#15 structural regression`.

### Task 2: Full verification + docs

- [ ] Full suite `--rerun` via WSL; totals from XML.
- [ ] Matrix: `hr-legacy#15` row gains "**Progress 2026-08-07**: the new-system criterion has a real regression test — logout never mutates employee lifecycle (`EmployeeModuleFlowTest.logoutNeverTouchesEmployeeLifecycle`); full closure at auth-module cutover"; `hr-legacy#2`/#3 rows' employee-edit-surface notes gain "lifecycle surface shipped 2026-08-07 with the same 404 guarantees".
- [ ] `python3 scripts/validate_phase0.py` exit 0; `markdownlint-cli2@0.23.2` clean.
- [ ] Commit `docs(migration): record employees-lifecycle coverage and hr-legacy#15 progress`; PR after human push.

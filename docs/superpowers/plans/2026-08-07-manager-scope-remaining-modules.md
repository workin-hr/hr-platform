# Manager Scope Enforcement: Remaining Modules Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Apply the merged `ResourceScopeService` guard to advances, salary-contracts, leave-balances, payslips, and employees, per `docs/superpowers/specs/2026-08-07-manager-scope-remaining-modules-design.md`.

**Architecture:** Each service injects `ResourceScopeService` and gains one guard per employee-bound method — `scopedEmployeeIdsOrNull` for list filtering, `isEmployeeInScope` for single-row/reference checks; out-of-scope → 404/empty before any other result. `employees.create` deliberately unscoped.

**Tech Stack:** Spring Boot, JPA, Testcontainers via WSL.

## Global Constraints

- Out-of-scope employee → uniform 404/empty, before Locked/WrongState/etc.
- Non-scope-limited callers unaffected (single `isScopeLimited` branch).
- `employees.create` not scope-gated (recorded decision).
- Run `markdownlint-cli2@0.23.2` on `**/*.md` before any docs push.

---

### Task 1: advances + salary-contracts (TDD)

**Files:**

- Modify: `advances/AdvanceService.java`, `payroll/SalaryContractService.java`
- Test: extend `backend/src/test/java/com/workin/backend/attendance/ManagerScopeFlowTest.java`

**Guards** (inject `ResourceScopeService` into each; `import com.workin.backend.authorization.ResourceScopeService;` + `java.util.Set`):

- `AdvanceService`: `list` → `Set<Long> reach = resourceScopeService.scopedEmployeeIdsOrNull(context)`, filter `reach == null || reach.contains(a.getEmployeeId())`; `get` → `.filter(a -> resourceScopeService.isEmployeeInScope(context, a.getEmployeeId()))`; `create` → `.filter(e -> resourceScopeService.isEmployeeInScope(context, e.getId()))` on the employee lookup; `transition` (approve/reject) → after `found`, `if (found.isEmpty() || !resourceScopeService.isEmployeeInScope(context, found.get().getEmployeeId()))` → NotFound.
- `SalaryContractService`: `listForEmployee(context, employeeId)` → if `!resourceScopeService.isEmployeeInScope(context, employeeId)` return `List.of()`; `get` → `.filter(c -> resourceScopeService.isEmployeeInScope(context, c.getEmployeeId()))`; `create(context, employeeId, ...)` → guard the employee lookup with `.filter(e -> resourceScopeService.isEmployeeInScope(context, e.getId()))`; `update`/`delete` → after loading the contract, `if (contract not found || !isEmployeeInScope(context, contract.getEmployeeId()))` → empty/false.

- [ ] **Step 1: Failing test** — add `advancesRespectManagerScope` and `salaryContractsRespectManagerScope` to `ManagerScopeFlowTest` (branch-scoped manager granted `advances.read`/`advances.manage`/`advances.approve` resp. `payroll.read`/`payroll.run`; seed rows for in/out-of-branch employees via SQL): list returns only in-branch; out-of-scope `get`/`approve`/`update` → 404; admin sees both.
- [ ] **Step 2: Red** → **Step 3: Implement** → **Step 4: Green** (via WSL; `AdvanceModuleFlowTest`, `PayrollModuleFlowTest` unchanged) → **Step 5: Commit** — `feat(backend): advances + salary-contracts manager scope enforcement`.

### Task 2: leave-balances + payslips (TDD)

**Files:**

- Modify: `requests/LeaveBalanceService.java`, `payroll/PayslipService.java`
- Test: extend `ManagerScopeFlowTest`

**Guards:**

- `LeaveBalanceService`: `list(context, employeeId, year)` → filter rows on `scopedEmployeeIdsOrNull`; `get` → `.filter(b -> isEmployeeInScope(context, b.getEmployeeId()))`; `create` → guard the employee lookup; `update` → after loading the balance, `if (not found || !isEmployeeInScope(context, balance.getEmployeeId()))` → the NotFound path.
- `PayslipService`: `list` → filter on `scopedEmployeeIdsOrNull`; `get` → filter on the payslip's `employeeId`; `create(context, batchId, employeeId, ...)` → after the employee lookup, add `|| !isEmployeeInScope(context, employeeId)` to the NotFound branch; `update`/`delete` → after loading the payslip, guard on its `employeeId`.

- [ ] **Step 1: Failing test** — add `leaveBalancesRespectManagerScope` and `payslipsRespectManagerScope` (branch-scoped manager granted `leave_balances.read`/`leave_balances.manage` resp. `payroll.read`/`payroll.run`; seed rows): list only in-branch; out-of-scope get/update → 404; admin unaffected.
- [ ] **Step 2: Red** → **Step 3: Implement** → **Step 4: Green** (via WSL; `LeaveBalanceFlowTest`, `PayrollModuleFlowTest` unchanged) → **Step 5: Commit** — `feat(backend): leave-balances + payslips manager scope enforcement`.

### Task 3: employees surface (TDD)

**Files:**

- Modify: `employees/EmployeeService.java`
- Test: extend `ManagerScopeFlowTest`

**Guards** (the employee *is* the scoped entity; `create` NOT gated):

- `list` → filter to `scopedEmployeeIdsOrNull` on the employee's own id.
- `get` → `.filter(e -> isEmployeeInScope(context, e.getId()))`.
- `updateNames`/`updateStatus` → the `findByIdAndCompanyId(...).map(...)` becomes `.filter(e -> isEmployeeInScope(context, e.getId())).map(...)` so an out-of-scope target is an empty `Optional` → 404.

- [ ] **Step 1: Failing test** — add `employeesRespectManagerScope`: a branch-scoped manager granted `employees.read`/`employees.manage` lists only in-branch employees; `GET`/`PUT`/`PUT /status` on an out-of-branch employee → 404; admin sees all. (Note in the test that `create` is intentionally not scope-gated.)
- [ ] **Step 2: Red** → **Step 3: Implement** → **Step 4: Green** (via WSL; `EmployeeModuleFlowTest` unchanged) → **Step 5: Commit** — `feat(backend): employees surface manager scope enforcement (create deliberately unscoped)`.

### Task 4: Full verification + docs

- [ ] Full suite `--rerun` via WSL; totals from XML.
- [ ] Matrix: F-25 → enforcement complete across all employee-data modules (only `DIRECT_REPORT`/`EMPLOYEE` scope types remain); `hr-legacy#6` note that the manager-scope dimension is now closed across the whole rewrite surface.
- [ ] `python3 scripts/validate_phase0.py` exit 0; `markdownlint-cli2@0.23.2` clean.
- [ ] Commit `docs(migration): record employee-data scope enforcement complete (F-25)`; PR after human push.

# Manager Scope Enforcement: Remaining Employee-Data Modules — Design (2026-08-07)

## Purpose And Authority

Completes the F-25 enforcement track: the third and final mechanical
application of the merged `ResourceScopeService` guard (PRs #60/#61,
D-029) to every remaining employee-data module — **advances,
salary-contracts, leave-balances, payslips, and the employees surface
itself**. Decision-free; the model and pattern were settled in the
first manager-scope slice and are unchanged.

## Scope

**In — the same guard, per employee-bound operation:** when the caller
is scope-limited, an out-of-scope employee is a uniform **404** (or an
empty list / empty `Optional`), taking precedence over any other
result. Applied via `ResourceScopeService.scopedEmployeeIdsOrNull`
(list filtering) and `isEmployeeInScope` (single-row / reference
checks) — exactly as attendance/requests/penalties.

- **advances** (`AdvanceService`): `list` filtered; `get` /
  `create` (on `employee_id`) / `approve` / `reject` (on the advance's
  `employee_id`) scoped.
- **salary-contracts** (`SalaryContractService`): `listForEmployee`
  and `create` scoped on the requested `employeeId`; `get` / `update`
  / `delete` scoped on the contract's `employee_id`.
- **leave-balances** (`LeaveBalanceService`): `list` filtered;
  `get` / `create` / `update` scoped on the balance's / request's
  `employee_id`.
- **payslips** (`PayslipService`): `list` filtered; `get` / `create`
  (on the request's `employeeId`) / `update` / `delete` (on the
  payslip's `employee_id`) scoped. (Payroll *batches* are company-wide
  entities, not employee-bound — unchanged.)
- **employees** (`EmployeeService`): the employee *is* the scoped
  entity. `list` filtered to reachable employees; `get` / `updateNames`
  / `updateStatus` scoped on the employee's own id.

**Recorded decision — `employees.create` is not scope-gated:** scope
answers "which *existing* employees can this membership reach"; a
brand-new employee has no branch/department at creation and so is not
yet reachable-or-not — gating create by scope is ill-defined. Creation
stays governed by the `employees.manage` permission alone (an
admin-granted, unusual capability for a manager); the new employee
then appears in that manager's scoped views only once it is placed in
an in-scope branch/department. All *other* employee operations are
scoped.

**Out (tracked):** the `DIRECT_REPORT` and `EMPLOYEE`
`membership_resource_scopes` types (F-25's last two).

## Testing

Extend `ManagerScopeFlowTest` with one compact case per module: a
branch-scoped manager (granted the module's read/manage/approve
permissions via overrides) sees only in-branch employees' rows and
gets a 404 on an out-of-scope row; admin/company-wide is unaffected.
The existing per-module flow tests
(`AdvanceModuleFlowTest`/`PayrollModuleFlowTest`/`LeaveBalanceFlowTest`/
`EmployeeModuleFlowTest`) pass unchanged — their actors are never
scope-limited.

## Consequences

Every tenant module that exposes employee-associated data now enforces
manager resource scope by the same reusable guard. `hr-legacy#6`'s
dashboard-IDOR class is structurally closed across the whole rewrite
surface for the manager-scope dimension; F-25's enforcement is
complete except its two deferred scope types.

# Manager Scope Enforcement: Requests & Penalties — Design (2026-08-07)

## Purpose And Authority

A decision-free mechanical extension of the approved manager
resource-scoping design
(`2026-08-07-manager-resource-scoping-first-slice-design.md`), which
explicitly tracked "enforcement on the other employee-data modules
(each a follow-up slice reusing `ResourceScopeService`)" as out of the
first slice. No new decision — the model (role-based fallback,
branch/department, deny-by-default) and the enforcement pattern were
settled and merged in that slice (D-029 + PR #60). This slice applies
the same guard to **requests** and **penalties**.

**Requests first because it directly closes `hr-legacy#18`** by
construction: a scoped `MANAGER` can no longer approve/reject (or read/
edit) a leave request for an employee outside its branch/department.
Penalties is included because legacy's *positive* manager
branch-scoping precedent lived there (`sql_manager_same_branch_scope`)
— the new system now enforces it structurally.

## Scope

**In — reusable guard (refactor, no behavior change):** promote the
two private helpers currently in `AttendanceService` into
`ResourceScopeService` so every module shares one implementation:

- `Set<Long> scopedEmployeeIdsOrNull(AuthorizationContext)` — `null`
  when not scope-limited (unrestricted), else the reachable set (for
  list filtering).
- `boolean isEmployeeInScope(AuthorizationContext, Long employeeId)` —
  `!isScopeLimited || canReachEmployee` (the single-row guard).

`AttendanceService` is refactored to call these; its behavior and its
`ManagerScopeFlowTest` are unchanged.

**In — enforcement (both modules, every employee-bound operation):**
when the caller is scope-limited, an out-of-scope employee is a uniform
**404** (existence-preserving), taking precedence over any other
result (e.g. a penalty's `Locked` 409, a request's `WrongState`/
`InsufficientBalance`) — a scoped manager must not learn the row
exists at all.

- **Penalties** (`PenaltyService`): `list` filtered; `get`/`create`/
  `update`/`delete` scoped on the penalty's / request's `employee_id`.
- **Requests** (`RequestService`): `list` filtered; `get`/`create`/
  `update`/`delete`/`approve`/`reject` scoped on the request's
  `employee_id`. Approve/reject is the `hr-legacy#18` case.

Non-scope-limited callers (admin, HR, MANAGER+HR) are unaffected —
one `isScopeLimited` branch, exactly as in attendance.

**Out (tracked):** the same guard for advances, payslips,
leave-balances, salary-contracts, and the employees surface itself
(each a further one-guard slice); the `DIRECT_REPORT`/`EMPLOYEE` scope
types (F-25).

## Testing

Extend `ManagerScopeFlowTest` (attendance already covered) with a
requests case and a penalties case: a branch-scoped manager, granted
the relevant read + approve/manage permissions via overrides, sees and
acts only on in-branch employees' rows; an out-of-scope `get`/
`approve`/`update` is a 404; an unscoped manager sees nothing;
admin/company-wide is unaffected. The existing
`RequestModuleFlowTest`/`PenaltyModuleFlowTest` pass unchanged (their
actors are never scope-limited).

## Consequences

`hr-legacy#18` is closed by construction (requests), the penalties
manager-scoping precedent is realized, and the reusable
`ResourceScopeService` guard is proven across three module shapes —
every remaining module is now a two-line copy.

# Manager Resource-Scoping — First Slice Design (2026-08-07)

## Purpose And Authority

Realizes ADR-0010's resource-scope layer (enforcement boundary 3,
`docs/architecture/authorization-model.md` §3/§4) — the first working
`membership_resource_scopes` enforcement, unblocking F-16/F-25 and
closing the `hr-legacy#17`/`#18` manager over-reach at its root.
Assigned + shaped by the repository owner 2026-08-07: D-029 chose
**branch/department-scoped** managers; two follow-up choices this same
day picked **role-based fallback** (only `MANAGER` consults scopes)
and **attendance read/list** as the first enforced surface.

The full subsystem (four F-25 scope types across every employee-data
module) is deliberately decomposed: this slice ships the schema, the
scope-administration surface, the enforcement service, and enforcement
on the attendance module only. Later slices extend the same service to
the remaining surfaces one at a time.

## Scope

**In — schema (V31/V32):**

- V31: `membership_resource_scopes` — `id`, `membership_id` FK,
  `company_id` (denormalized for RLS), `scope_type` VARCHAR CHECK IN
  (`'BRANCH'`, `'DEPARTMENT'`), `scope_id` BIGINT (the branch or
  department id — no FK, since it references two possible tables by
  type; validated in the service instead), `created_at`, and
  `UNIQUE (membership_id, scope_type, scope_id)`. The two remaining
  F-25 types (`DIRECT_REPORT`, `EMPLOYEE`) are deferred — the CHECK
  admits only the two this slice enforces, so an unsupported type
  cannot be stored.
- V32: RLS enable+force (V14 pattern).
- No new permission keys — scope administration is gated by the
  existing `members.manage`.

**In — the scope-limited rule (role-based fallback, recorded):**

- A membership is **scope-limited** iff its roles include `MANAGER`
  and include **neither** `COMPANY_ADMIN` **nor** `HR` (a company-wide
  role always trumps — a MANAGER+HR membership is company-wide).
- A scope-limited membership's **reachable employee set** = employees
  whose `branch_id` is in its `BRANCH` scope ids **or** whose
  `department_id` is in its `DEPARTMENT` scope ids. Empty scope rows →
  empty set (**deny-by-default**, matching the permission layer).
- Non-scope-limited memberships (admin, HR, and — until self-service
  exists — everyone else) are unaffected: current company-wide
  behavior stands. Scope rows are **permission-agnostic** in this
  slice (they gate all scoped operations, not one permission) — the
  model's optional per-permission scoping is deferred (recorded
  simplification).

**In — enforcement service:**

- `ResourceScopeService` (boundary 3), called inside the caller's
  validated-context transaction like `PermissionEvaluationService`:
  - `boolean isScopeLimited(AuthorizationContext)` — the rule above.
  - `Set<Long> reachableEmployeeIds(AuthorizationContext)` — the
    scoped employee set (only meaningful when scope-limited; a native
    query over `membership_resource_scopes` + `employees`, RLS-scoped).
  - `boolean canReachEmployee(AuthorizationContext, Long employeeId)`
    — convenience for single-row checks.
  - Per-request, no cross-request cache — a scope change bites on the
    next request (Dimension 5, like F-19/F-20).

**In — attendance enforcement (the proof surface):**

- `AttendanceService`: when `isScopeLimited(context)`, every operation
  is additionally confined to `reachableEmployeeIds`:
  - `list` — results filtered to reachable employees (an
    out-of-scope `employeeId` filter yields an empty list).
  - `get` — an out-of-scope row's id is a **404** (indistinguishable,
    §8 uniform-404).
  - `create`/`update`/`delete` — an out-of-scope `employeeId` (or the
    target row's employee) is a 404, so the surface is fully
    scope-correct, not half-enforced. (Managers hold no
    `attendance.correct` by default; this matters only when an admin
    grants it via override.)
- Non-scope-limited callers hit none of this — the check is a single
  early `isScopeLimited` branch.

**In — scope administration (extends the members module):**

- `GET /api/tenant/members/{membershipId}/resource-scopes`
  (`members.read`) — list a member's scopes.
- `POST /{membershipId}/resource-scopes`
  `{scopeType: BRANCH|DEPARTMENT, scopeId}` (`members.manage`) → 201;
  the `scopeId` must be a tenant-owned branch/department for its type
  (foreign/nonexistent → the same 404); duplicate → 409; self-target
  → 409 (the members module's self-mutation rule); audited
  (`SCOPE_ASSIGNED`).
- `DELETE /{membershipId}/resource-scopes/{scopeType}/{scopeId}` →
  204; absent → 404; audited (`SCOPE_REVOKED`).

**Out (tracked):** enforcement on the other employee-data modules
(requests, penalties, advances, payslips, leave-balances, employees —
each a follow-up slice reusing `ResourceScopeService`); the
`DIRECT_REPORT` and `EMPLOYEE` scope types (F-25); per-permission
scope rows; the per-manager scope *assignment* at migration time
(F-16 — moot today, 0 live managers, `hr-legacy#26`).

## Design

`ResourceScopeService` + entity/repository live in
`com.workin.backend.authorization` (alongside
`PermissionEvaluationService` — same enforcement layer). The scope
admin endpoints extend `MemberController`/`MemberAdminService` and
reuse `TenantAuditService` (new actions `SCOPE_ASSIGNED`/
`SCOPE_REVOKED`). `AttendanceService` gains a `ResourceScopeService`
dependency and one guard per method; the branch/department reference
validation reuses the organization repositories. No
`AuthorizationContext` change — it already carries `roles` (its Javadoc
names this as the F-16/F-25 work).

## Testing

`ManagerScopeFlowTest`: a pure `MANAGER` membership (empty bundle +
`attendance.read` override) with a `BRANCH` scope sees only in-branch
employees' attendance and gets 404 on an out-of-scope row; with a
`DEPARTMENT` scope, only in-department; with **no** scope rows, sees
nothing (deny-by-default); a `MANAGER`+`HR` membership is company-wide
(role trumps); `COMPANY_ADMIN`/`HR` are unaffected (see all). Scope
admin: assign/remove branch + department scopes via the members
surface; foreign/nonexistent/ wrong-type `scopeId` → 404; duplicate →
409; self-target → 409; audit rows (`SCOPE_ASSIGNED`/`SCOPE_REVOKED`)
asserted. Liveness: adding a scope makes a previously-hidden
employee's attendance visible on the very next request; removing it
hides it again. Plus the members module's existing F-18/permission
tests still pass unchanged.

## Consequences

The resource-scope enforcement layer exists and is proven end-to-end
on attendance; `hr-legacy#17`/`#18`'s over-reach is structurally
closed for that surface and the pattern is a one-guard copy for every
following module. F-16's model is realized (assignment-at-migration is
the only remainder, moot today); F-25's branch/department cases are
covered, its two other scope types tracked.

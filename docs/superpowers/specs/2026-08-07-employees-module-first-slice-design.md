# Employees Module — First Slice Design (2026-08-07)

## Purpose And Authority

The first business module on top of the authorization runtime:
tenant-scoped employee administration (list, read, create, update
names) gated by real catalog permissions, with the F-18 cross-tenant
negative tests modeled on `hr-legacy#2`/`#3`'s confirmed bug shapes.
Anchored to ADR-0010 (all dimensions now implemented for what this
uses), ADR-0002 Part B (RLS), and the accepted module sequencing
(employees is the anchor the payroll group FKs already point at).
Assigned by the repository owner 2026-08-07 ("ok proceed" on the
recommendation naming the employees module).

## The Architectural Point This Slice Establishes

`SET LOCAL app.current_company_id` lives and dies with a transaction.
The authorization interceptor's context establishment runs in its own
transaction, so a handler's business queries would otherwise run with
no tenant scope — RLS fails closed and every read returns nothing.
This slice therefore introduces the pattern every future module
follows: an **application service** whose `@Transactional` methods
first re-apply the tenant session variable from the already-validated
`AuthorizationContext`, then run business queries inside that same
transaction — RLS scoping plus an explicit `company_id` filter
(defense in depth, enforcement layers 4 and 5). A shared
`TenantSessionVariable` component replaces the two existing private
`set_config` copies (`TenantContextService`, `RegistrationService`) —
same behavior, one implementation.

## Scope

**In**:

- `GET /api/tenant/employees` — list the caller's company's employees
  (`@RequiresPermission(employees.read)`).
- `GET /api/tenant/employees/{id}` — read one (`employees.read`);
  a cross-tenant or nonexistent id is **404, indistinguishably** (§8:
  denials never reveal resource existence — `hr-legacy#2`'s read shape).
- `POST /api/tenant/employees` — create (`employees.manage`);
  `firstName` (required), `lastName`, `phone` (optional, globally
  unique → 409 on conflict, same pattern as registration).
- `PUT /api/tenant/employees/{id}` — update `firstName`/`lastName`
  (`employees.manage`); cross-tenant → 404 (`hr-legacy#3`'s mutation
  shape).
- **No credential surface, structurally**: the `Employee` entity does
  not map the legacy-fidelity `password_hash` column and no DTO carries
  credential fields — employee-admin editing *cannot* touch
  credentials, which is `hr-legacy#3`'s root fix (credentials belong
  to `identities`, reachable only through auth flows).
- F-18 tests for this module: company B's admin cannot list, read, or
  update company A's employees; read/manage separation proven (HR with
  only an `employees.read` override can list but not create).

**Out (tracked, not dropped)**:

- Employee↔identity/membership linkage and the `join_company` flow
  (F-01) — the mobile self-service side; needs its own slice with the
  membership model.
- Deactivation/deletion lifecycle — `hr-legacy#20`'s retention
  question is an open product decision; `active`/`role` fields stay
  read-only in this surface (role mutation also triggers §8's
  escalation rules, F-24's territory).
- Bulk import, photo upload, remaining legacy profile fields
  (`branch_id` etc. — reference tables don't exist yet).
- The legacy desktop CRUD contract adapter (ADR-0003) — cutover work;
  this slice's endpoints are the new-shape admin API.
- The service-layer ArchUnit rule extension — one module is not yet a
  convention; revisit at the second module.

## Design

- Package `com.workin.backend.employees`: `Employee` entity (maps
  `id`, `company_id`, `first_name`, `last_name`, `phone`, `role`,
  `active` — not `password_hash`), `EmployeeRepository`
  (`findByCompanyIdOrderById`, `findByIdAndCompanyId`),
  `EmployeeService`, `EmployeeController`, request/response records.
- `EmployeeService` methods are `@Transactional`, take the validated
  `AuthorizationContext` (from the interceptor's request attribute),
  call `TenantSessionVariable.apply(context.companyId())` first, and
  filter every query by `context.companyId()` explicitly even though
  RLS also scopes it.
- `EmployeeController` handlers carry `@RequiresPermission` and read
  the stashed context; a missing stash is a programming error (the
  interceptor always populates it for gated handlers) and fails loudly.
- Responses expose `id`, `firstName`, `lastName`, `phone`, `role`,
  `active` — never `password_hash` (unmapped, so impossible by
  construction).

## Testing

`EmployeeModuleFlowTest` (integration, HTTP): admin create→list→get→
update round-trip; duplicate phone → 409; HR default-deny on both
read and manage; HR + `employees.read` override → list works, create
still 403 (read/manage separation); company B admin gets 404 on
company A's employee (get and update) and B's list never contains A's
rows; unauthenticated → non-2xx. Existing suite stays green.

## Consequences

The module template is set: schema (already present) → entity/repo →
tenant-scoped application service → gated controller → F-18 negatives.
Payroll-group modules can now follow it mechanically, and the
employees/identity linkage slice has a real employee surface to build
on.

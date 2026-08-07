# Penalties Module — First Slice Design (2026-08-07)

## Purpose And Authority

Third business module by the employees template; V13 schema (merged),
V4's two-key split (`penalties.read`, `penalties.manage`). This module
carries one real documented business rule beyond CRUD: **a penalty with
`applied_to_payroll = true` is locked from further update/delete**
(V13's own comment — the application-level immutability that protects
payroll-applied financial records; the flag itself is only ever set by
the payroll module's finalize side effect, never by this surface).
Assigned by the repository owner 2026-08-07 ("proceed").

## Scope

**In** (`/api/tenant/penalties`, template service pattern, §8's 404s):

- `GET` list + `GET /{id}` — `penalties.read`.
- `POST` — `penalties.manage`: `employeeId` (tenant-scoped lookup;
  foreign/nonexistent → the same 404), `penaltyType` (required),
  `penaltyDays` (required, ≥ 0 — legacy's schema default is 0.0, so
  zero stays legal rather than inventing a stricter rule),
  optional `reason`, optional `penaltyDate` (default: server date).
- `PUT /{id}` — `penalties.manage`: type/days/reason/date. Locked row
  (`applied_to_payroll`) → 409; cross-tenant → 404.
- `DELETE /{id}` — `penalties.manage`, 204. Locked row → 409;
  cross-tenant → 404; idempotent-404 on nonexistent (no oracle).
  Delete is included here (unlike employees, where `hr-legacy#20`'s
  retention question defers it) because the `applied_to_payroll` lock
  *is* this record type's documented retention protection: once a
  penalty has touched payroll it is immutable; before that, deletion
  is routine legacy behavior.
- `applied_to_payroll` is **never settable through this surface** — no
  request field exists; it appears read-only in the view.

**Out (tracked)**: penalty-days→money conversion (payroll's job, per
V13's comment); the report/export endpoint (`hr-legacy#23`'s CSV/XLSX
mislabel needs the desktop-tolerance answer first); employee
self-service visibility.

## Design

Package `com.workin.backend.penalties`, exactly the advances shapes:
entity (`id`, `employee_id`, `company_id`, `penalty_type`,
`penalty_days`, `reason`, `penalty_date`, `applied_to_payroll`),
repository (`findByCompanyIdOrderById`, `findByIdAndCompanyId`),
service returning result objects (`NotFound` | `Locked` | `Done`),
controller mapping to 404/409/2xx.

## Testing

`PenaltyModuleFlowTest`: admin create→list→get→update→delete
round-trip; zero days allowed, negative rejected (400); locked row →
update 409 and delete 409 (lock set via SQL, standing in for payroll's
future finalize); foreign/nonexistent employee create → 404;
cross-tenant get/update/delete → 404 and list exclusion; read-without-
manage → 403 on create; unauthenticated → non-2xx; view carries
`appliedToPayroll` and no request can set it.

## Consequences

Payroll-group prerequisites keep landing: when the payroll module's
finalize flips `applied_to_payroll`, the immutability the legacy rule
promises is already enforced and tested on this side.

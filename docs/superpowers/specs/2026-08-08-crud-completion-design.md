# CRUD Completion — Request-Types Delete, Exception-Types Delete, Complaints Module (2026-08-08)

## Purpose And Authority

Three small, mechanical gaps grouped into one document because none
needs new architecture — each is either a missing verb on an existing
module, or a module whose only implemented piece is a permission key.
Grouping avoids three near-empty specs for work that shares one
pattern (hard-delete with reference cleanup) and one open-question
shape (permission/status-code granularity not fully inferable from
existing convention).

**This document is planning output only** (hr-platform `CLAUDE.md`:
Claude's role here is planning/analysis/review; implementation is a
separate, explicitly assigned step).

Evidence: `apis/api/request_types/{delete,list}.php`,
`apis/api/attendance_exception_types/delete.php`,
`apis/helpers/exception_types_helper.php`,
`apis/api/complaints/{create,list,update,delete}.php`,
`dashboard/pages/complaints/page.php` read in full from `hr-legacy`
`main`, commit `d113204` (`git show d113204 --stat`: `complaints/delete.php`
is new in this commit; the other two `delete.php` files are modified;
`request_types/list.php` also changed, unrelated to delete — see that
subsection). `complaints` DDL from `mysql_workin.schema.sql:327-339`.
hr-platform current state read in full: `RequestTypeController/Service/
Repository.java`, `ExceptionTypeController/Service/Repository.java`,
`ShiftService.java` (the `delete()` precedent), `RequestController.java`,
`PermissionKeys.java`, `Attendance.java`, migrations
`V21__create_attendance.sql` and
`V25__create_requests_and_leave_balances.sql`.

## Scope

**In:**

- `DELETE /api/tenant/request-types/{id}` — hard delete, blocked if
  any `requests` row references the type.
- `DELETE /api/tenant/exception-types/{id}` — hard delete, nulling
  out `attendance.exception_type_id` and
  `request_types.exception_type_id` references first (no blocking).
- A full complaints module: schema, `list`/`get`/`create`/`update`/
  `delete`, scoped to legacy's `source = 'employee'` rows only (the
  `company_support` direction is named and deferred below, not
  silently dropped).

**Out (tracked, blockers named):**

- `GET`/`PUT` on request-types and exception-types — the assigning
  task named delete specifically for both; get/update were not
  flagged as missing and are a separate addition of the same shape if
  wanted.
- Complaints' `company_support` direction (company/admin complaining
  *to* the platform, reviewed on the `PlatformAdminController` side).
  Legacy's `source` enum serves both directions from one table, but
  `PlatformAdmin` (JWT-based, no `AuthorizationContext`) is a
  disjoint identity domain from the tenant module this document
  scopes into. Mixing both in one slice would mix two authorization
  models; out until the owner decides where `company_support`
  complaints belong.
- Complaint creation by an unauthenticated or employee-session caller
  (legacy: `getAuth()` is optional in `create.php`) — the same
  employee-self-service pattern the requests module already deferred
  platform-wide (`V25...sql:1-6`: "legacy request creation is
  EMPLOYEE-only self-service, which the rewrite defers
  codebase-wide"). Recorded here as the same deferral, not reopened —
  see the Complaints subsection.
- Notification-on-status-change — no notification infrastructure
  surfaced in hr-platform research, same "out" call the
  schedule-foundation spec made elsewhere.

## Design

### Request-Types Delete

Legacy (`apis/api/request_types/delete.php:33-44`) counts `requests`
rows (joined through `employees`) referencing the type and returns a
typed `409` (`LangKey::REQUEST_TYPE_IN_USE`) if any exist — an
app-level pre-check with a specific error code, not a caught
constraint error. hr-platform's schema already makes this a real,
non-nullable FK with no `ON DELETE` clause:
`requests.request_type_id BIGINT NOT NULL REFERENCES request_types
(id)` (`V25...sql:35`). That means the organization-structure spec's
pattern applies directly — `ShiftService.delete()` catches
`DataIntegrityViolationException` → `CONFLICT`
(`docs/superpowers/specs/2026-08-07-organization-structure-first-slice-design.md`
Design) — a plain `delete()` + `flush()` inside a try/catch
reproduces legacy's blocking behavior without an app-level
pre-check. **Open question, not decided here**: legacy's `409` carries
a distinguishing code (`REQUEST_TYPE_IN_USE`); no typed-error-code
convention exists anywhere in hr-platform's backend — every prior
delete-conflict (`ShiftService.java:69`) returns a bare `CONFLICT`
with a free-text reason string. Recommend the same bare-`409`
convention over inventing the first typed error code, but flagging
the fidelity gap from legacy's response shape.

Interface: `RequestTypeService.delete(AuthorizationContext, Long
requestTypeId)` returns `boolean` (tenant-scoped find-or-false, same
as `ShiftService`), throwing `ResponseStatusException(CONFLICT)` on
`DataIntegrityViolationException`. Controller:
`@RequiresPermission(PermissionKeys.REQUESTS_MANAGE)` (same key
`create` already uses, `RequestTypeController.java:38`),
`@DeleteMapping("/{requestTypeId}")`, `204`/`404`, matching
`RequestController.delete` (`RequestController.java:73-78`). The
controller's current comment ("Update/delete deferred per the slice
spec", `RequestTypeController.java:21`) becomes stale and should drop
the "delete" half once this lands.

Aside: `request_types/list.php`'s change in this same legacy commit
drops role-based filtering for a uniform "active by default,
`is_active` overrides" rule — unrelated to delete.
`RequestTypeService.list` (`RequestTypeService.java:36-42`) already
returns all rows to any `REQUESTS_READ` caller with no role branch and
no `is_active` filter at all. Flagging only so it isn't mistaken for
a second gap; no change recommended here unless the owner separately
wants an `is_active` filter.

### Exception-Types Delete

Legacy (`apis/api/attendance_exception_types/delete.php:28-52`) nulls
two references before deleting, in order: (1)
`attendance.exception_type_id` for rows whose employee belongs to the
company, (2) `request_types.exception_type_id` scoped by
`company_id`, then hard-deletes. The comment is explicit: "Clear FKs
so hard delete does not fail under RESTRICT constraints" — this is
**succeed-by-clearing**, the opposite of request-types'
**block-if-referenced**. The two gaps are not the same shape and
should not be templated off each other.

hr-platform's schema already has both references as real, *nullable*
FKs with no `ON DELETE` clause:
`attendance.exception_type_id BIGINT REFERENCES exception_types (id)`
(`V21...sql:30`; the `CHECK (exception_type_id IS NULL OR check_out IS
NULL)` at line 32 does not block nulling — it only constrains the
non-null case) and `request_types.exception_type_id BIGINT REFERENCES
exception_types (id)` (`V25...sql:26`). Because both are nullable,
catching `DataIntegrityViolationException` would produce the *wrong*
behavior (blocking a delete legacy allows) — this must be an explicit
app-level null-out, not a caught-constraint pattern.
`Attendance.exceptionTypeId` is a plain `Long` field, not a
`@ManyToOne` (`Attendance.java:51`), so the null-out is a
straightforward bulk update: two `@Modifying` queries (`UPDATE
attendance SET exception_type_id = NULL WHERE exception_type_id = ?
AND company_id = ?`; same on `request_types`) run inside the same
`@Transactional` service method, before the delete. hr-platform's
`attendance` table already denormalizes `company_id`
(`V21...sql:24`), so step (1) needs no join through `employees` the
way legacy's does — a direct `company_id` filter does the same
scoping.

Interface: `ExceptionTypeService` currently has only `list`/`create`
(`ExceptionTypeService.java:24-37`) and no `Attendance`/`RequestType`
repository dependency — `delete` needs both injected (or small
`@Modifying` queries added to each repository).
`ExceptionTypeService.delete(AuthorizationContext, Long
exceptionTypeId)` returns `boolean`; null both references, then
delete, inside one transaction. No exception handling needed if the
null-outs are exhaustive — but that exhaustiveness is easy to get
subtly wrong if a third reference to `exception_types` is added later
without updating this path; worth a comment pointing at both call
sites, as legacy's own comment does. Controller: `create` gates on
`ATTENDANCE_CORRECT` (`ExceptionTypeController.java:37`) — no
`ATTENDANCE_MANAGE` key exists (`PermissionKeys.java:41-42`), so
delete should reuse `ATTENDANCE_CORRECT` rather than invent a
single-consumer key. `@DeleteMapping("/{exceptionTypeId}")`,
`204`/`404`.

### Complaints Module

hr-platform has only `PermissionKeys.COMPLAINTS_MANAGE`
(`PermissionKeys.java:40`) — no entity, repository, service,
controller, or migration exists. "Add delete" is not a coherent unit
of work against nothing; this subsection recommends building full
CRUD for the `employee`-source half of legacy's module
(create/list/get/update/delete) — an explicit, named scope decision,
not an obvious given, but the only scope that makes "delete"
meaningful.

**Schema** mirrors `complaints` (`mysql_workin.schema.sql:327-339`)
with the same normalizations every prior slice applied: real FKs
(`employee_id` nullable, matching legacy), `company_id` nullable in
legacy — whether hr-platform narrows this to `NOT NULL` given the
module is tenant-only here is an **open question, not decided here**
(a real behavior decision, not a mechanical translation); `source`
kept as a column even though only `'employee'` is written by this
slice, so no breaking change is needed when `company_support` lands;
`status` as `VARCHAR` with `CHECK (status IN ('PENDING','DONE',
'CLOSED'))`, following the requests module's uppercase-enum
normalization (`V25...sql:41-42`). RLS enable+force, V14's pattern.

**Endpoints** (`com.workin.backend.complaints`, new package):
`GET /api/tenant/complaints` (list, `status`/search filters mirroring
`complaints_list_filters()`), `GET .../{id}`, `POST` (create — see
below), `PUT .../{id}` (reply/status, mirrors `update.php`'s
partial-update-by-presence-of-field shape), `DELETE .../{id}`.

**Delete's "employee-source only" rule, read precisely**
(`apis/api/complaints/delete.php:20-30`): not a role/permission
distinction — `list`/`delete.php` are already `COMPANY_ADMIN`/`HR`
only. It is a *row-shape* filter: the lookup adds `AND source =
'employee'` to the `id`/`company_id` match, so a `company_support`
row simply isn't found and the endpoint 404s as if it didn't exist,
rather than 403ing. Translate directly: hr-platform's delete (and
get/update) should filter `WHERE id = ? AND company_id = ? AND
source = 'employee'`, not add a separate branch — this slice never
writes `company_support` rows, so the filter is currently a no-op
invariant, but keeping it explicit avoids a schema/endpoint change
when that source is eventually added.

**Permission key sufficiency**: `COMPLAINTS_MANAGE` alone matches
legacy's reviewing-side access shape (role-only, no finer split).
Every other module here uses a read/write split
(`REQUESTS_READ`/`_MANAGE`, `ATTENDANCE_READ`/`_CORRECT`) with no
legacy precedent for complaints specifically. **Open question, not
decided here**: add `COMPLAINTS_READ` for consistency with every
other module, or keep the single key legacy's shape actually
supports? Recommend adding `COMPLAINTS_READ`, for the same
consistency reasoning the schedule-foundation spec used for its own
new-keys-vs-reuse question — but this is the owner's call.

**Create's authorization is the harder question.** Legacy's
`create.php` requires no authentication at all (`getAuth()`, not
`requireAuth()`); absent auth, `employee_id`/`company_id` are `null`
and `source` stays `'employee'`. This is the same
employee-self-service pattern already deferred platform-wide for
requests (`V25...sql:1-6`) — `RequestController.create` requires
`REQUESTS_MANAGE` with no employee-session or anonymous path
(`RequestController.java:57-63`), and no employee-session/mobile
identity concept exists anywhere in hr-platform's authorization model
(`AuthorizationContext` is exclusively membership-based) — so a
literal port has no identity model to attach to yet. **Recommended
here** (a proposed decision, not an inferred fact): `POST
/api/tenant/complaints` requires `COMPLAINTS_MANAGE`, same shape as
`REQUESTS_MANAGE` — staff creating a complaint record on a tenant
employee's behalf — with true self-service tracked as the same class
of codebase-wide-deferred item requests already is, not a new gap.

## Testing

Request-types delete addition: unreferenced → `204`; referenced by a
`requests` row → `409`; cross-tenant → `404`; without
`REQUESTS_MANAGE` → `403`; unauthenticated → non-2xx.

Exception-types delete addition: referenced by `attendance` → that
row's `exception_type_id` reads `null` after delete, not blocked;
referenced by `request_types` → same; referenced by both
simultaneously → both null, type gone; unreferenced → `204`;
cross-tenant → `404`; without `ATTENDANCE_CORRECT` → `403`.

`ComplaintsModuleFlowTest` (new): admin/HR CRUD round trip
(create → list → get → update reply+status → delete); list default
filters to `status = pending` unless overridden; search matches
name/phone/message; update rejects an out-of-enum status → `400`;
delete of a nonexistent id → `404`; cross-tenant get/update/delete →
`404` + list exclusion; without the relevant permission key(s) →
`403` per whichever key split the owner picks; unauthenticated →
non-2xx. No coverage proposed for anonymous/employee-session create,
since this document proposes not building that path.

## Consequences

Request-types and exception-types reach parity with legacy's
company-settings management surface without any schema change — both
are pure service/controller work against tables that already have
the right FK shape (`V21`, `V25`). Complaints goes from a single
unused permission key to a real, deliberately narrowed module: the
`employee`-source surface HR/Admin already use in legacy, minus the
`company_support` direction (needs its own platform-admin-side
design) and minus anonymous/employee-session creation (deferred
alongside the same gap in requests, not a new one). Three decisions
are named as open above — request-types' error-body shape,
complaints' permission-key split, and complaints' `company_id`
nullability — and are not resolved here, per `CLAUDE.md`'s
requirement to separate confirmed facts from open questions rather
than inventing an answer.

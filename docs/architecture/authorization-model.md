# Authorization Model

**Status: Accepted, 2026-08-05** — this document is the detailed
architecture reference for `docs/adr/ADR-0010-authorization-model.md`,
which is the governing decision record. This document does not itself
carry ADR status; if the two ever appear to disagree, ADR-0010 is
authoritative and this document should be corrected to match it, not
the other way round.

**This document describes an accepted architecture, not an existing
implementation.** No production backend code, database schema,
migration, or authorization catalog exists yet as a result of this
document. It defines what must be built and how, so that implementation
can proceed without re-deriving these decisions.

## 1. Principal And Membership Model

Two authorization domains exist, deliberately separate:

- **Platform domain**: platform administrators, scoped to Workin's own
  operation of the system itself (approving/suspending companies,
  platform-wide oversight). A platform principal's privileges never
  imply tenant business-data access.
- **Tenant domain**: every company's own users. A single human identity
  may hold **multiple, independent tenant memberships** (e.g. one person
  who is `COMPANY_ADMIN` at company A and also an `EMPLOYEE` at company
  B) — roles, permissions, and resource scopes belong to the
  **membership**, never globally to the identity.

```mermaid
erDiagram
    IDENTITY ||--o{ TENANT_MEMBERSHIP : "holds"
    IDENTITY ||--o{ PLATFORM_PRINCIPAL : "may also be"
    TENANT_MEMBERSHIP }o--|| TENANT : "belongs to"
    TENANT_MEMBERSHIP ||--o{ MEMBERSHIP_ROLE : "has"
    TENANT_MEMBERSHIP ||--o{ MEMBERSHIP_PERMISSION_OVERRIDE : "has"
    TENANT_MEMBERSHIP ||--o{ MEMBERSHIP_RESOURCE_SCOPE : "has"
    MEMBERSHIP_ROLE }o--|| ROLE : "references"
    ROLE ||--o{ ROLE_PERMISSION : "grants by default"
    ROLE_PERMISSION }o--|| PERMISSION : "references"
    MEMBERSHIP_PERMISSION_OVERRIDE }o--|| PERMISSION : "references"
    MEMBERSHIP_RESOURCE_SCOPE }o--o| PERMISSION : "optionally scopes"
```

**Fixed tenant role categories** (business-level defaults, not the
authorization source of truth by themselves):

| Role | Legacy equivalent |
|---|---|
| `COMPANY_ADMIN` | `company_admin` (`UserRoleEnum`), dashboard `doCompanyLogin()` |
| `HR` | `hr` (`UserRoleEnum`), dashboard `doHrLogin()` |
| `MANAGER` | `manager` (`UserRoleEnum`) — see §3 on why this role alone is not sufficient for scope |
| `EMPLOYEE` | `employee` (`UserRoleEnum`) |

**Employee self-service is not a separate security domain.** It is the
`EMPLOYEE` role's default resource scope: restricted to the caller's own
employee record. No parallel enforcement path exists for it — it uses
the same membership/role/permission/scope machinery as every other
role, just with a narrower default scope.

**Manager visibility is never inferred from the `MANAGER` role alone.**
It is represented by one or more explicit `membership_resource_scope`
rows, each with a `scope_type` of:

- `COMPANY` (rare, only if a manager is deliberately given company-wide
  visibility)
- `BRANCH`
- `DEPARTMENT`
- `DIRECT_REPORTS`
- `EMPLOYEE` (specifically assigned individuals)

Legacy `hr-legacy` grants the `manager` role unscoped company-wide
attendance visibility and request-approval rights today
(`hr-legacy#17`, `#18`) — confirmed to be zero real employees with
`role='manager'` in production data at the time this was investigated,
and the mobile client's own "Manager Mode" is an unimplemented stub
(`profile_manager_mode_button.dart`). **Migration does not preserve
this unscoped access by default.** Each manager's intended scope must
be resolved individually before migration (see Required Implementation
Task 3) and expressed as explicit `membership_resource_scope` rows —
never as a blanket "managers see everything" rule baked into code.

## 2. Tenant-Membership Validation

RLS (`docs/adr/ADR-0002-modular-monolith-baseline.md` Part B, Accepted)
is the mandatory **data-layer** isolation mechanism. It is not a
substitute for **application-level** membership validation — a caller
proving *who* they are is not the same as proving *which membership,
in which tenant, they are validly acting as* right now.

### Required per-request sequence

```mermaid
sequenceDiagram
    participant C as Client
    participant A as App (Service Layer)
    participant D as Database (RLS)
    C->>A: Request + JWT (sub, membership_id, tenant_id)
    A->>A: 1. Authenticate identity/session (ADR-0005)
    A->>A: 2. Resolve requested tenant_id + membership_id
    A->>D: 3. Load membership server-side (by membership_id)
    A->>A: 4. Verify: membership belongs to identity; belongs to\n   requested tenant; is active; tenant/employee active
    A->>A: 5. Build immutable AuthorizationContext
    A->>D: 6. BEGIN transaction; SET LOCAL app.current_company_id = tenant_id
    A->>D: 7. Execute business operation (RLS enforces final boundary)
    D-->>A: Result (already tenant-filtered by RLS)
    A-->>C: Response
```

1. Authenticate the identity and session (per ADR-0005).
2. Resolve the requested `tenant_id` and `membership_id` from the
   request (header/URL/JWT claim — see §6, these are **selectors**, not
   proof).
3. Load the membership server-side, from the database, not from the
   token.
4. Verify, all four, every request:
   - the membership belongs to the authenticated identity
   - the membership belongs to the requested tenant
   - the membership is active
   - the tenant and employee account are active where applicable
5. Build an **immutable** `AuthorizationContext` from the validated
   server-side data (membership, effective roles, effective
   permissions, resource scopes) — nothing downstream re-derives or
   trusts client-supplied claims again for this request.
6. `SET LOCAL app.current_company_id` inside the **same** database
   transaction as the business operation (matches the H2 spike's proven
   pattern exactly — transaction-scoped, resets automatically).
7. Let PostgreSQL RLS enforce the final data boundary.

### Fail-closed requirement

The mechanism **must** fail closed — deny, not silently pass — when:

- no tenant is selected
- the membership does not exist
- the membership is disabled
- the identity and membership do not match
- the RLS session variable is absent (this is F-13's exact test —
  see `docs/migration/technical-spike-plan.md`'s "Full Spike Findings")
- the operation executes outside a valid tenant transaction

The dedicated non-superuser runtime database role (`app_runtime`,
proven in the H2 spike) remains a **hard architectural requirement** —
not optional hardening. A startup check must fail loudly if the
runtime DataSource ever connects as a superuser (Required Implementation
Task 9).

## 3. Roles And Permissions

**Hybrid RBAC + capability-permission model.** Roles give
understandable business-level defaults; permissions give granular,
independently assignable capabilities. This explicitly does **not**
reproduce the legacy `hr_permissions` design (17 hardcoded boolean
columns, one table, one row per HR-role employee — see §7 for the
corrected, evidence-confirmed count and the full legacy mapping).

### Schema (normalized)

| Table | Purpose |
|---|---|
| `permissions` | The canonical catalog. One row per stable, namespaced capability key (e.g. `employees.read`). Single source of truth — no permission string may be introduced anywhere else in the codebase without a corresponding row here. |
| `role_permissions` | Default permission bundle per fixed role (`COMPANY_ADMIN`/`HR`/`MANAGER`/`EMPLOYEE`). |
| `tenant_memberships` | One row per (identity, tenant) pair — the anchor for everything else in this section. |
| `membership_roles` | Role(s) held by a specific membership. |
| `membership_permission_overrides` | Per-membership explicit `ALLOW`/`DENY` on a specific permission — used to migrate the legacy per-employee `hr_permissions` matrix and to support legitimate individual exceptions going forward. |
| `membership_resource_scopes` | Per-membership resource-scope assignments (`COMPANY`/`BRANCH`/`DEPARTMENT`/`DIRECT_REPORTS`/`EMPLOYEE`), optionally tied to a specific permission. |

### Permission key naming

Stable, namespaced, lowercase, dot-separated: `<domain>.<capability>`,
e.g. `employees.read`, `employees.manage`, `attendance.read`,
`attendance.correct`, `payroll.read`, `payroll.run`,
`requests.approve`, `company.settings.manage`. The full canonical
catalog derived from real legacy evidence is in §7.

### Precedence (deterministic, evaluated in this order)

1. **Explicit deny** (a `membership_permission_overrides` row with
   effect `DENY`) — wins over everything below.
2. **Explicit allow** (a `membership_permission_overrides` row with
   effect `ALLOW`) — wins over role-granted.
3. **Role-granted permission** (via `membership_roles` →
   `role_permissions`).
4. **Deny by default** — no matching rule anywhere means no access.

### Permission possession ≠ resource scope

These are two separate checks, always. Holding `attendance.read` does
**not** by itself allow reading attendance for every employee in the
tenant — the caller's `membership_resource_scopes` rows determine
*which* employees' attendance that permission actually reaches. A
`COMPANY_ADMIN`'s default scope is typically `COMPANY`-wide; a
`MANAGER`'s scope is whatever was explicitly assigned per §1.

### No external policy engine for this MVP

OPA, Cedar, OpenFGA, Keycloak Authorization Services, or an equivalent
external policy engine are **explicitly not adopted**. The real,
confirmed complexity of this system (4 roles, a low-double-digit
permission catalog, resource scopes bounded to 5 categories) does not
justify the added operational and conceptual overhead of an external
policy engine — see ADR-0010's Alternatives Considered.

## 4. Structural Enforcement Boundaries

**Layered enforcement, one authoritative layer.** No layer is ever
documented or relied upon as a replacement for another.

```mermaid
flowchart TD
    A["1. HTTP boundary (Spring Security request authorization)\ncoarse only: authenticated vs public, platform route vs tenant route"] --> B
    B["2. Application-service method authorization\n(AUTHORITATIVE business-authorization boundary)"] --> C
    C["3. Resource-scope / ownership policy\n(centralized policy services, validated AuthorizationContext)"] --> D
    D["4. Repository-layer tenant scoping\n(defense in depth, applied where practical)"] --> E
    E["5. PostgreSQL RLS\n(final mandatory tenant boundary, ADR-0002 Part B)"]
```

1. **HTTP boundary**: Spring Security request authorization for coarse
   controls only — authenticated vs. public, platform route vs. tenant
   route, broad principal type. Controller route checks are **never**
   sufficient business authorization on their own.
2. **Application-service boundary — the authoritative layer.** Spring
   Security method authorization on application use cases. Use
   framework-native method security, centralized authorization-policy
   components, custom composed annotations or typed permission
   constants, and explicit resource-scope evaluation — not raw
   permission strings or complex authorization expressions scattered
   across the codebase. **Every externally reachable application-service
   operation must declare either its required authorization policy or
   an explicit marker that it is intentionally public/authentication-only.**
   An architecture test (ArchUnit, per `docs/testing/test-strategy.md`'s
   stack) must fail the build when an externally reachable use case has
   no authorization declaration (Required Implementation Task 10).
   Controllers delegate authorization-sensitive decisions to this layer
   — they never reproduce business checks themselves.
3. **Resource-scope and ownership policy**: resource ownership and
   manager scope checked through centralized policy services using the
   already-validated `AuthorizationContext` — never re-derived ad hoc
   per controller/service.
4. **Repository-layer tenant scoping**: retained where practical, as
   defense in depth — consistent with the H2 spike's recommendation
   (`docs/adr/ADR-0002-modular-monolith-baseline.md` Decision, condition
   2).
5. **PostgreSQL RLS**: the final, mandatory tenant boundary, including
   protection against an accidentally unscoped repository query — this
   is what the H2 spike proved and what condition 1/3 of ADR-0002's
   Decision require to hold in practice.

## 5. Effect Of Membership, Role, And Permission Changes

**Membership deactivation, role changes, permission revocation, and
resource-scope changes take effect on the next request** — never
dependent on access-token expiry, refresh, logout, or re-login.

For the MVP:

- Load the active membership, effective permissions, and effective
  resource scopes **server-side, on each request** (step 3–5 of the
  §2 sequence).
- **No cross-request authorization cache** initially — correctness and
  immediate revocation are prioritized over an optimization nothing has
  yet measured a need for.
- **Request-local memoization is allowed** — the same authorization data
  should not be loaded repeatedly within a single request, only across
  requests.

A distributed cache may be introduced later, **only** when real
measurements justify it, and only with all of: an authorization
version, explicit invalidation on every authorization change, a bounded
TTL, multi-instance invalidation, and tests proving stale privileges
cannot survive a revocation. None of this is built now — this paragraph
is a future gate, not a current task.

**Relationship to ADR-0005**: authentication-session revocation remains
governed by ADR-0005 (short-lived access tokens, rotating refresh
tokens, server-side revocation) — that mechanism is unchanged by this
ADR. Authorization changes must **not** require revoking the complete
login session unless the membership itself is disabled or a security
incident requires it — a permission or scope change alone is handled by
the next request's server-side load, not by forcing re-authentication.

## 6. Authorization Data In Access Tokens

Access tokens remain **minimal identity and session credentials**. They
may carry context **identifiers**:

`sub`, `sid`, `jti`, `membership_id`, `tenant_id`, `iss`, `aud`, `iat`,
`exp`.

`membership_id` and `tenant_id` are **context selectors only** — they
tell the server which membership the caller *claims* to be acting as;
they are never treated as proof of active membership on their own (see
§2, steps 3–4, which always re-verify server-side).

**Never embedded in the token**: effective role list, permission list,
manager scope, branch scope, department scope. All effective
authorization data is loaded and validated server-side on every
request.

This deliberately accepts **one indexed authorization lookup per
request** in exchange for: immediate revocation, no stale permission
claims, simpler security reasoning, and consistent behavior across web
(future Next.js platform-admin surface), mobile, and desktop clients —
three real, independent frontends per
`docs/api/three-frontend-api-usage-matrix.md`, none of which can be
allowed to diverge in how fresh their authorization view is.

## 7. Legacy Permission Migration Table

**Corrected count, confirmed directly from `mysql_workin.schema.sql`
(`CREATE TABLE hr_permissions`), 2026-08-05: exactly 17 `can_*` boolean
columns, not 18.** The earlier "18 total... and one more" wording in
this repository's Discovery-stage notes was an overcount — a real
counting error, not a hedge to preserve now that it can be checked
directly. Real usage evidence for each flag was confirmed by reading
`dashboard/includes/hr_access.php` (`HrAccess` class — the single
enforcement point every flag routes through), `dashboard/includes/org_helper.php`
(`org_can_manage()`, confirmed to be a thin wrapper over the same
`HrAccess::canViewOrgSection()` check — legacy never distinguished
"view" from "manage" within a section, one flag granted both), and each
flag's real call sites.

| # | Legacy `hr_permissions` column | Real legacy scope (confirmed by code) | Canonical permission key(s) |
|---|---|---|---|
| 1 | `can_dashboard` | Gates only the `reports` nav item/page (`HrAccess::navPermissions()['reports']`) — despite the name, not general dashboard-home access (the home page itself has no permission gate) | `reports.read` |
| 2 | `can_recent_activities` | Gates the dashboard home "recent activities" widget and the standalone `activities` page | `activities.read` |
| 3 | `can_branches` | `pages/branches/` — legacy bundles view+manage in one flag | `branches.read`, `branches.manage` |
| 4 | `can_departments` | `pages/departments/` | `departments.read`, `departments.manage` |
| 5 | `can_job_titles` | `pages/job_titles/` | `job_titles.read`, `job_titles.manage` |
| 6 | `can_shifts` | `pages/shifts/` | `shifts.read`, `shifts.manage` |
| 7 | `can_leave_balances` | `pages/leave_balances/` | `leave_balances.read`, `leave_balances.manage` |
| 8 | `can_assets` | `pages/assets/` | `assets.read`, `assets.manage` |
| 9 | `can_advances` | `pages/advances/`, including approve/reject/pay actions (the exact module with the confirmed cross-tenant IDOR, `hr-legacy#5`) | `advances.read`, `advances.manage`, `advances.approve` |
| 10 | `can_workforce_planning` | `pages/workforce_planning/` | `workforce_planning.read`, `workforce_planning.manage` |
| 11 | `can_salary_calculator` | `pages/salary_calculator/` — confirmed standalone, read-only, no database writes (see `docs/adr/ADR-0009-dashboard-vs-desktop-admin-client.md`'s Validation Evidence) | `salary_calculator.read` (no `.manage` — nothing to manage) |
| 12 | `can_company_settings` | `pages/company_settings/` | `company.settings.read`, `company.settings.manage` |
| 13 | `can_employees` | `pages/employees/`, **and reused for** `pages/administrative_decisions/`, `pages/notifications/`, `pages/complaints/` — four capabilities gated by one flag historically | `employees.read`, `employees.manage` — **kept as one bundle, by direct product decision (2026-08-05), not split into independently assignable keys.** `employees.manage` covers employee records, administrative decisions, notifications, and complaints together, exactly matching legacy's own grouping — no artificial granularity introduced beyond what `hr-legacy` actually distinguished |
| 14 | `can_attendance` | `pages/attendance/` (payroll section) | `attendance.read`, `attendance.correct` |
| 15 | `can_requests` | `pages/requests/` | `requests.read`, `requests.approve` |
| 16 | `can_payroll` | `pages/payroll/` | `payroll.read`, `payroll.run` |
| 17 | `can_penalties` | `pages/penalties/` | `penalties.read`, `penalties.manage` |

**Migration rule**: legacy never distinguished read from manage within
a section — one flag granted both. The migration therefore maps
`can_X = 1` to granting **both** the `.read` and `.manage` (or
equivalent named) keys for that row, as `membership_permission_overrides`
`ALLOW` rows (Required Implementation Task 2). For rows 1–12 and 14–17,
the finer-grained `.read`/`.manage` split this catalog defines is
available for **new** assignments going forward; migration does not
retroactively narrow anyone's access without a separate, deliberate
product decision. **Row 13 (`can_employees`) is the one deliberate
exception**: by direct product decision, its four legacy-bundled
capabilities stay bundled under `employees.manage` going forward too,
not just at migration time — no independent `notifications.manage`/
`complaints.manage`/decisions-only key is introduced for this MVP.

**Platform-domain starter catalog** (legacy has no granular platform
permissions — `dashboard/includes/auth.php`'s `doAdminLogin()` is a
single shared password with unconditional full access,
`hr-legacy#11`): `platform.companies.read`, `platform.companies.approve`,
`platform.companies.suspend`, `platform.companies.delete`, confirmed
directly from `dashboard/pages/companies/page.php`'s real action
handlers.

**Platform-admin identity model — decided 2026-08-05, by direct product
decision**: the new platform **keeps the single shared platform-admin
password model for the MVP**, unchanged from `hr-legacy`'s current
behavior (`hr-legacy#11`) — no per-platform-admin individual account,
no MFA, no distinguishable per-admin audit trail is built now.
**Independent per-admin accounts (individual identity, MFA, distinct
audit trail per admin) is an explicit backlog enhancement**, not
in-scope for MVP — tracked as
`docs/migration/consolidated-task-matrix.md` F-26. This is a deliberate
scope-narrowing decision, not an oversight: the platform-admin surface
is small, low-traffic (Workin's own staff, not customer-facing), and
the real, confirmed risk this session found (`hr-legacy#11`) is
explicitly accepted as a known, deferred trade-off rather than gated on
before MVP delivery. The privilege-escalation and auditing requirements
in §8/§9 still apply to whatever platform-admin actions occur under the
shared credential — audit records identify the *action*, even though
they cannot yet distinguish *which individual* performed it while the
shared-password model remains in place.

## 8. Privilege-Escalation Protections

- A tenant administrator cannot grant a permission they are not
  themselves permitted to administer.
- Tenant users cannot assign platform privileges — platform and tenant
  permission namespaces are structurally separate (`platform.*` vs.
  everything else), never overlapping accidentally.
- A user cannot change their own role or permissions unless a
  separately approved workflow explicitly allows it.
- The last active `COMPANY_ADMIN` membership for a tenant cannot be
  removed without assigning a replacement first.
- Permission-management operations are always audited (§9).
- All access-denied responses avoid revealing whether an inaccessible
  cross-tenant resource exists (uniform 404, not a distinguishable
  403-vs-404 signal).

## 9. Auditing Requirements

Security audit events recorded for: membership creation, activation,
suspension, and deletion; role assignment and removal; permission
grants and revocations; resource-scope changes; support access or
impersonation; privileged authorization failures; and attempts to
access another tenant's resources.

Each audit record includes: actor, target identity or membership,
tenant, action, timestamp, correlation ID, and before/after
authorization state where applicable. **Known MVP limitation**: while
the platform-admin identity model remains the shared-password design
(§7), `actor` for platform-admin actions identifies the generic
platform-admin principal, not a distinguishable individual — this is
the direct, accepted consequence of deferring per-admin identity to the
backlog (F-26), not a gap in the auditing design itself.

## 10. Failure And Revocation Behavior Summary

| Scenario | Required behavior |
|---|---|
| No tenant selected | Fail closed — deny |
| Membership does not exist | Fail closed — deny |
| Membership disabled | Fail closed — deny |
| Identity/membership mismatch | Fail closed — deny |
| RLS session variable unset | Fail closed — zero rows (F-13) |
| Operation outside a valid tenant transaction | Fail closed — deny |
| Permission revoked mid-session | Effective on the **very next request** — no dependency on token expiry/refresh/logout |
| Role changed mid-session | Same — next request |
| Resource scope narrowed mid-session | Same — next request |
| Runtime DB connection is a superuser | Startup check fails loudly (Required Implementation Task 9) — never silently degrades to unprotected |

## Evidence

`mysql_workin.schema.sql` (`hr_permissions` table definition, 17
`can_*` columns, confirmed directly 2026-08-05);
`dashboard/includes/hr_access.php` (`HrAccess` class, all real
enforcement points); `dashboard/includes/org_helper.php`
(`org_can_manage()`); `dashboard/includes/company_hr_helper.php`;
`dashboard/includes/payroll_helper.php`; `dashboard/pages/companies/page.php`
(platform-admin action handlers); `dashboard/pages/salary_calculator/`
(confirmed read-only); `apis/config/enums.php` (`UserRoleEnum`);
`docs/adr/ADR-0002-modular-monolith-baseline.md` (RLS mechanism, H2
spike); `docs/adr/ADR-0005-authentication-direction.md` (token
lifetime/revocation mechanism this model builds on);
`docs/adr/ADR-0010-authorization-model.md` (the governing ADR this
document details); `hr-legacy#2`, `#3`, `#5`, `#6` (tenant-isolation bug
class), `#8` (permission-enforcement gap), `#11` (shared platform-admin
password), `#17`, `#18` (Manager-role scope-drift); direct
repository-owner architecture decision, this conversation, 2026-08-05.

# ADR-0010: Authorization Model

## Metadata

| Field | Value |
|---|---|
| ADR ID | ADR-0010 |
| Title | Authorization Model |
| Status | Accepted |
| Date | 2026-08-04 (accepted 2026-08-05 — see `docs/bootstrap/decision-log.md` D-026) |
| Owners | Solution Architect |
| Deciders | Repository owner (human requester), recorded directly in this conversation, 2026-08-05 |
| Related Issues | `hr-legacy#2`, `#3`, `#5`, `#6`, `#8`, `#11`, `#17`, `#18` |
| Supersedes | The authorization half of the original "Authentication And Authorization Direction" scope (see `docs/adr/ADR-0005-authentication-direction.md`'s Scope Correction) |
| Superseded By | None |

## Context

Split out of `docs/adr/ADR-0005-authentication-direction.md` on
2026-08-04 because authentication (proving who a caller is) and
authorization (what that caller may do) are different concerns with
different failure modes and, in `hr-legacy`, different confirmed bug
classes.

`hr-legacy`'s current authorization model, as directly confirmed by
Discovery:

- **Role model**: `UserRoleEnum` defines four roles —
  `company_admin`, `hr`, `manager`, `employee`
  (`apis/config/enums.php`). The dashboard additionally has a
  platform-level `admin` session type with no company scope at all
  (`dashboard/includes/auth.php`'s `doAdminLogin()`), a single shared
  password (`hr-legacy#11`).
- **Granular permissions**: a separate `hr_permissions` table carries
  **17 hardcoded boolean columns** per HR-role employee — the exact
  count confirmed directly from `mysql_workin.schema.sql`
  (`CREATE TABLE hr_permissions`), correcting an earlier "18... and one
  more" placeholder count from this repository's own Discovery-stage
  notes. Full enumeration and real usage evidence for every column:
  `docs/architecture/authorization-model.md` §7.
- **Confirmed enforcement gap** (`hr-legacy#8`): this permission matrix
  is actually checked on roughly 21 endpoints and silently ignored on
  the other 130+ — a per-endpoint opt-in pattern, not a structural
  guarantee.
- **Confirmed tenant-isolation gaps** (`hr-legacy#2`, `#3`, `#5`, `#6`):
  several endpoints/pages fail to verify that a requested resource
  belongs to the caller's own company at all.
- **Confirmed scope-inconsistency findings** (`hr-legacy#17`, `#18`):
  the `manager` role's actual enforced scope (company-wide for
  attendance visibility and request approval) doesn't match what
  doc-comments in the legacy code claim (branch-scoped).

## Decision

**Accepted 2026-08-05, in full, covering all six dimensions below, by
direct instruction from the repository owner** (`docs/bootstrap/decision-log.md`
D-026): *"Treat the following as my human architecture decision for
ADR-0010."* This is a genuine architecture decision, not a status flip
on unchanged text — every dimension below now has a real, evidence-
grounded answer. Full mechanical detail (schemas, sequence diagrams,
the complete legacy permission mapping) lives in
`docs/architecture/authorization-model.md`, which this ADR treats as
its detailed reference — that document is not itself an ADR and carries
no independent authority beyond what is recorded here.

**This acceptance approves the architecture and constraints only.** It
does not claim that production implementation, database schema,
migrations, tests, or the authorization catalog already exist — all of
that is real, tracked, future work (see Validation Evidence and
`docs/migration/consolidated-task-matrix.md` rows F-14 through F-25).

### Dimension 1 — Platform-admin, tenant-admin, and employee scopes

**Decided**: two structurally separate authorization domains — platform
and tenant. Platform administrators are platform-scoped principals whose
privileges never imply automatic tenant business-data access. Any future
support access into a tenant must go through an explicit, time-bounded,
fully audited support-access/impersonation workflow — never ambient
platform-admin power. Platform and tenant APIs have separate route and
authorization boundaries. Within the tenant domain, four fixed role
categories: `COMPANY_ADMIN`, `HR`, `MANAGER`, `EMPLOYEE`. An identity may
hold memberships in multiple tenants; roles/permissions/scopes belong to
the membership, never globally to the identity. Employee self-service is
not a separate domain — it is the `EMPLOYEE` role's default resource
scope (own-data-only), using the same machinery as every other role.
Full detail: `docs/architecture/authorization-model.md` §1.

**Update 2026-08-05 (later same day, corrected same day)**:
platform-admin identity model decided — `hr-legacy`'s shared
platform-admin password (`hr-legacy#11`) is **not accepted** as the new
platform's architecture, not even for MVP. Platform administrators must
have individual identities, credentials, sessions, revocation, and
audit attribution. This is a **P0 production-readiness requirement**
(`docs/migration/consolidated-task-matrix.md` F-26) — it does not block
unrelated tenant-module development, but it blocks production readiness
and the release of any privileged platform-admin operation. Full
detail: `docs/architecture/authorization-model.md` §7.

### Dimension 2 — Tenant-membership validation

**Decided**: RLS (`docs/adr/ADR-0002-modular-monolith-baseline.md` Part
B, Accepted) remains the mandatory data-layer isolation mechanism, but
it is explicitly **not** a substitute for application-level membership
validation. Every tenant request: authenticate identity/session →
resolve requested `tenant_id`/`membership_id` → load the membership
server-side → verify identity match, tenant match, active status (both
membership and, where applicable, tenant/employee) → build an immutable
server-side `AuthorizationContext` → `SET LOCAL app.current_company_id`
inside the same transaction as the business operation → let RLS enforce
the final boundary. A tenant identifier from a header, URL, or JWT claim
is never trusted without this server-side validation. The mechanism
fails closed when: no tenant is selected, the membership doesn't exist,
the membership is disabled, identity/membership mismatch, the RLS
session variable is absent, or the operation runs outside a valid tenant
transaction. The dedicated non-superuser runtime database role remains a
hard architectural requirement. Full sequence diagram and fail-closed
matrix: `docs/architecture/authorization-model.md` §2, §10.

### Dimension 3 — Roles and permissions

**Decided**: a hybrid RBAC + capability-permission model. Roles provide
business-level defaults; a normalized permission catalog provides
granular capabilities. The legacy 17-boolean-column design is **not**
reproduced. Normalized schema: `permissions`, `role_permissions`,
`tenant_memberships`, `membership_roles`,
`membership_permission_overrides`, `membership_resource_scopes`.
Permission keys are stable, namespaced strings (`employees.read`,
`employees.manage`, `attendance.correct`, `payroll.run`,
`requests.approve`, `company.settings.manage`, etc.) held in one
canonical catalog — never scattered as string literals across
controllers/services. Fixed roles provide default permission bundles;
per-membership overrides handle the legacy HR-permission-matrix
migration and legitimate individual exceptions. Deterministic
precedence: explicit deny → explicit allow → role-granted → deny by
default. Permission possession and resource scope are always separate
checks. **No external policy engine** (OPA, Cedar, OpenFGA, Keycloak
Authorization Services) for this MVP — see Alternatives Considered. Full
catalog and legacy mapping: `docs/architecture/authorization-model.md`
§3, §7.

**Update 2026-08-05 (later same day, corrected same day)**:
`can_employees`'s four legacy-bundled capabilities (`employees`,
`administrative_decisions`, `notifications`, `complaints`) **remain
four separate canonical permissions** in the new schema — an earlier
same-day decision to collapse them into one `employees.manage`
permission was corrected before merge. Legacy's `can_employees=1` maps
to granting all four **only as a migration-compatibility rule**,
preserving current behavior at cutover without permanently coupling the
four capabilities or forcing a future schema redesign to separate them.
Full detail: `docs/architecture/authorization-model.md` §7.

### Dimension 4 — Authorization enforcement boundaries

**Decided**: layered enforcement, one authoritative layer. (1) HTTP
boundary — Spring Security request authorization for coarse controls
only (authenticated vs. public, platform vs. tenant route); never
sufficient business authorization alone. (2) **Application-service
method authorization is the authoritative business-authorization
boundary** — Spring Security method security, centralized policy
components, composed annotations/typed permission constants, explicit
resource-scope evaluation. Every externally reachable application-service
operation must declare its required policy or an explicit
public/authentication-only marker; an architecture test must fail the
build on any use case missing this declaration. Controllers delegate to
this layer, never reproduce checks. (3) Resource-scope/ownership policy
via centralized services using the validated `AuthorizationContext`. (4)
Repository-layer tenant scoping, retained where practical as defense in
depth. (5) PostgreSQL RLS, the final mandatory boundary. No layer may be
documented as a replacement for another. Full diagram:
`docs/architecture/authorization-model.md` §4.

### Dimension 5 — Immediate effect of permission or role changes

**Decided**: membership deactivation, role changes, permission
revocation, and resource-scope changes take effect on the **next
request** — never dependent on token expiry, refresh, logout, or
re-login. For the MVP: load active membership/effective
permissions/resource scopes server-side on every request; no
cross-request authorization cache; request-local memoization only. A
distributed cache is deferred until real measurements justify it, and
only with an authorization version, explicit invalidation, bounded TTL,
multi-instance invalidation, and tests proving stale privileges cannot
survive revocation. Authentication-session revocation remains governed
by ADR-0005 and is not conflated with authorization changes — a
permission/scope change does not require forcing re-authentication
unless the membership itself is disabled or a security incident
requires it. Full detail: `docs/architecture/authorization-model.md`
§5.

### Dimension 6 — Authorization data: embedded in tokens or loaded server-side

**Decided**: server-side, not token-embedded. Access tokens remain
minimal identity/session credentials, carrying only context selectors
(`sub`, `sid`, `jti`, `membership_id`, `tenant_id`, `iss`, `aud`, `iat`,
`exp`) — `membership_id`/`tenant_id` are selectors only, never proof of
active membership on their own. The effective role list, permission
list, and every resource-scope value are never embedded in the token —
always loaded and validated server-side on every request. This
deliberately accepts one indexed authorization lookup per request in
exchange for immediate revocation, no stale permission claims, simpler
security reasoning, and consistent behavior across all three real
frontends (web, mobile, desktop). Full detail:
`docs/architecture/authorization-model.md` §6.

### Additional decided constraints (privilege escalation and auditing)

Also decided, not open: a tenant admin cannot grant a permission they
cannot themselves administer; tenant users cannot assign platform
privileges; a user cannot change their own role/permissions outside a
separately approved workflow; the last active `COMPANY_ADMIN` membership
cannot be removed without a replacement; permission-management
operations are always audited; platform and tenant namespaces never
overlap; cross-tenant access-denied responses never reveal resource
existence. Full list and audit-event schema:
`docs/architecture/authorization-model.md` §8, §9.

## Alternatives Considered

- **External policy engine (OPA, Cedar, OpenFGA, Keycloak Authorization
  Services)**: rejected for this MVP. The confirmed real complexity of
  this system — 4 roles, a low-double-digit permission catalog, 5
  resource-scope categories — does not justify the operational and
  conceptual overhead of an external policy engine. Revisit only if the
  model's real complexity grows materially beyond what this decision
  scoped.
- **Reproducing the legacy 17-boolean-column `hr_permissions` design
  directly**: rejected — it is what produced `hr-legacy#8`'s
  per-endpoint opt-in enforcement gap in the first place; a normalized,
  centrally-cataloged permission model with a single authoritative
  enforcement layer directly addresses that root cause.
- **Token-embedded authorization data**: rejected — see Dimension 6;
  the immediate-revocation requirement (Dimension 5) is incompatible
  with claims that are only as fresh as the token's own lifetime.
- **Preserving the `MANAGER` role's current unscoped company-wide
  legacy access as the new default**: rejected — this is exactly the
  scope-drift pattern `hr-legacy#17`/`#18` found (enforced scope
  diverging from intended scope). Migration resolves each manager's
  actual intended scope individually rather than carrying the drift
  forward.

## Consequences

- A normalized authorization schema (6 new tables/concepts) must be
  designed and migrated — real implementation work, not yet done (see
  Required Validation And Implementation Tasks below).
- Every existing `hr_permissions` row and every `manager`-role
  employee's real intended scope must be resolved before cutover — a
  real, bounded migration task, not automatic.
- The application-service layer becomes the single place all real
  authorization logic must live; an architecture test enforces this
  going forward rather than relying on code review alone.
- No authorization data may be cached across requests for the MVP —
  a deliberate simplicity/correctness trade-off, revisited only with
  real measurement later.
- `docs/adr/ADR-0006-attendance-edge-gateway-direction.md`'s note that
  Dimension 2 depends on ADR-0002 Part B's acceptance is now fully
  resolved — Part B was accepted 2026-08-05
  (`docs/bootstrap/decision-log.md` D-018) and this ADR's Dimension 2
  builds directly on it.

## Risks

- **Risk of the migration task (mapping every `hr_permissions` row and
  every manager's real scope) being treated as mechanical when it is
  not**: some legacy permission combinations may not map cleanly onto
  the new catalog's split read/manage keys; some managers' real intended
  scope may be genuinely ambiguous from data alone and need a product
  decision. Mitigation: Required Implementation Tasks 1–3 are scoped as
  real investigation-plus-migration work, not a scripted 1:1 copy.
- **Risk of the "no cache" MVP choice becoming a real performance
  problem at scale**: one indexed lookup per request is cheap at
  current confirmed data volume (~62K total rows,
  `docs/migration/technical-spike-plan.md`'s DB evidence) but should be
  monitored, not assumed safe forever. Mitigation: the caching escape
  hatch in Dimension 5 is deliberately pre-specified, not designed from
  scratch under pressure later.
- **Risk of the architecture test (Required Implementation Task 10)
  being under-scoped and missing real gaps**: an ArchUnit rule that only
  checks for *a* declaration, not a *correct* one, would not fully
  close `hr-legacy#8`'s failure mode. Mitigation: pair the architecture
  test with the permission-matrix test suite (Required Implementation
  Task 4), which checks actual behavior, not just presence of a
  declaration.

## Validation Evidence

Real evidence exists for both the *problem* (Context above — role
model, the corrected 17-column permission table structure, the
confirmed enforcement gap, the confirmed scope-drift finding) and the
*chosen model* (this Decision section, backed by
`docs/architecture/authorization-model.md`'s full mechanical detail,
itself derived from direct reads of `hr_access.php`, `org_helper.php`,
`company_hr_helper.php`, `payroll_helper.php`, and
`mysql_workin.schema.sql`). What does **not** yet exist, and this
acceptance does not claim exists: the actual database schema/migrations
for the six new tables, the populated permission catalog, the
implemented enforcement layers, or any of the tests listed below — all
tracked as real, separate implementation work.

## Open Questions

None remaining at the architecture-decision level — all six dimensions
have a recorded decision. What remains is **implementation**, not
further architecture decision-making:

## Required Validation And Implementation Tasks

Tracked individually in `docs/migration/consolidated-task-matrix.md`
(rows F-14 through F-25):

1. Map every legacy `hr_permissions` column to a canonical permission
   key — **done as part of this acceptance**, see
   `docs/architecture/authorization-model.md` §7 for the full,
   corrected 17-row table (not the earlier vague "18... and one more").
2. Migrate legacy roles and permission flags into memberships, roles,
   permissions, overrides, and resource scopes.
3. Resolve the intended scope of every existing manager before
   migration (per `hr-legacy#17`/`#18`'s scope-drift finding) —
   real investigation, not a mechanical copy of current (drifted)
   behavior.
4. Add a permission-matrix test suite covering every role and
   capability.
5. Add negative cross-tenant tests for every sensitive module (modeled
   on `hr-legacy#2`/`#3`/`#5`/`#6`'s real bug shape).
6. Add tests proving revoked permissions stop working on the very next
   request (Dimension 5's core guarantee).
7. Add tests for inactive memberships and mismatched identity/membership
   combinations (Dimension 2's fail-closed matrix).
8. Implement F-13 (already specified,
   `docs/adr/ADR-0002-modular-monolith-baseline.md` Decision condition
   3): prove an unset RLS tenant session variable returns no rows.
9. Add a startup check that fails when the runtime PostgreSQL
   connection is a superuser or otherwise bypasses RLS.
10. Add an architecture test that detects application use cases without
    explicit authorization-policy declarations.
11. Add privilege-escalation tests for role and permission
    administration (§8's rules).
12. Add manager-scope tests for branch, department, direct-report, and
    specifically-assigned-employee scope assignments.

None of these 12 tasks are implemented by this ADR's acceptance —
acceptance approves the architecture and constraints they must satisfy,
not their completion.

**A 13th requirement, added 2026-08-05, tracked as F-26**: individual
platform-admin identity (credentials, sessions, revocation, audit
attribution), replacing `hr-legacy`'s shared password. Unlike the 12
tasks above, this one is a **P0 production-readiness gate**, not a
per-module blocker — it does not block unrelated tenant-module
development, but no privileged platform-admin operation may reach
production before it is complete.

## Evidence

`apis/config/enums.php` (`UserRoleEnum`); `mysql_workin.schema.sql`
(`hr_permissions`, 17 `can_*` boolean columns, recounted and confirmed
directly 2026-08-05); `dashboard/includes/hr_access.php`,
`org_helper.php`, `company_hr_helper.php`, `payroll_helper.php` (real
enforcement-point evidence for every permission flag);
`dashboard/includes/auth.php` (three login functions, confirming the
platform/tenant/employee tier structure); `dashboard/pages/companies/page.php`
(platform-admin action handlers); `hr-legacy#2`, `#3`, `#5`, `#6`
(tenant-isolation gaps); `hr-legacy#8` (permission-enforcement gap);
`hr-legacy#11` (shared platform-admin password); `hr-legacy#17`, `#18`
(Manager-role scope-drift findings);
`docs/adr/ADR-0002-modular-monolith-baseline.md` (Part B, Accepted —
the tenant-isolation-mechanism dependency this ADR's Dimension 2 builds
on); `docs/adr/ADR-0005-authentication-direction.md` (the
authentication-side constraint Dimension 6 references);
`docs/architecture/authorization-model.md` (this ADR's detailed
mechanical reference); direct repository-owner architecture decision,
this conversation, 2026-08-05, recorded in full in
`docs/bootstrap/decision-log.md` D-026.

# Member Administration — First Slice Design (2026-08-07)

## Purpose And Authority

The authorization module's administration surface: assign/remove
tenant roles, grant/revoke permission overrides, and
activate/disable memberships — the operations
`docs/architecture/authorization-model.md` §8 protects and §9 audits,
and the missing precondition for F-24's privilege-escalation tests
(`docs/migration/consolidated-task-matrix.md`). Until now
`membership_roles` and `membership_permission_overrides` are written
only by test SQL — no API surface exists. Assigned by the repository
owner 2026-08-07 ("proceed with the next steps"); the two scope
choices below (new catalog keys; status administration included)
confirmed by the owner the same day.

## Scope

**In — schema (V23/V24):**

- V23: two new catalog rows — `members.read`, `members.manage` — plus
  `role_permissions` rows granting both to `COMPANY_ADMIN` (V20's
  full-catalog seeding predates these keys). Legacy's `hr_permissions`
  has no member-administration flag (role administration was implicit
  company-admin dashboard behavior), so these are new-platform keys,
  documented as such in their descriptions. `PermissionKeys` gains
  matching constants in the same commit — `PermissionCatalogSyncTest`
  enforces the pairing. Also `tenant_audit_events` (`id`,
  `company_id`, `actor_membership_id` NOT NULL,
  `target_membership_id`, `action`, `permission_key` NULL, `detail`
  NULL, `created_at`), mirroring `platform_admin_audit_events`' role
  for the tenant domain — §9's actor-attribution requirement, with
  `detail` carrying before/after context where applicable.
- V24: RLS enable+force for `tenant_audit_events` (V14's pattern).
  `membership_roles` and `membership_permission_overrides` already
  have RLS.

**In — endpoints** (`/api/tenant/members`, package
`com.workin.backend.members`, template service/result-object shapes):

- `GET` list (`members.read`): membershipId, identity phone (the only
  human-identifying field memberships have — no employee link exists),
  roles, status. `GET /{membershipId}` adds that member's overrides.
- `POST /{membershipId}/roles` `{role}` (`members.manage`) → 201;
  duplicate role → 409.
- `DELETE /{membershipId}/roles/{role}` → 204; role not assigned →
  404.
- `POST /{membershipId}/permission-overrides`
  `{permissionKey, effect: ALLOW|DENY}` → 201; duplicate
  (membership, permission) → 409.
- `DELETE /{membershipId}/permission-overrides/{permissionKey}` →
  204; no such override → 404.
- `PUT /{membershipId}/status` `{status: ACTIVE|DISABLED}` → 200
  (V3's existing status vocabulary — no new states invented). Setting
  the status a membership already has is an idempotent 200 no-op and
  writes no audit row.

**§8 rules enforced in the service (all inside the request's
validated-context transaction):**

- **Self-mutation → 409** on every mutation whose target membership is
  the caller's own — roles, overrides, and status alike. §8 allows an
  explicitly approved self-service workflow; none exists, so the rule
  is flat.
- **Last-admin → 409**: removing the `COMPANY_ADMIN` role from, or
  disabling, the only ACTIVE membership holding `COMPANY_ADMIN` in
  the company. Both paths guarded (suspension is an escalation hole
  too, per the owner-confirmed scope choice).
- **Namespace confinement → 404**: a `platform.*` or
  unknown-to-the-catalog `permissionKey` is treated as nonexistent for
  tenant administration — the same uniform 404 §8 requires for
  cross-tenant probes, revealing nothing about the platform namespace.
- **"Permitted to administer" (MVP interpretation, recorded
  decision)**: holding `members.manage` permits administering every
  tenant-namespace key. Finer per-key administration rights are
  deferred until a real requirement exists.
- **F-18 shapes**: foreign/nonexistent target membership → the same
  404 for every operation; foreign rows never appear in lists.

**Audit (§9, mandatory per §8):** every successful mutation writes
one `tenant_audit_events` row through a single-write-path
`TenantAuditService` — actions `ROLE_ASSIGNED`, `ROLE_REMOVED`,
`OVERRIDE_GRANTED`, `OVERRIDE_REVOKED`, `MEMBERSHIP_DISABLED`,
`MEMBERSHIP_REACTIVATED`, with actor and target membership ids.
Recorded decision: auditing of *denied* attempts (§9's "privileged
authorization failures") is deferred — it belongs at the
authorization-interceptor layer, not per-module.

**Out (tracked):** membership creation/invite (onboarding's domain,
`hr-legacy#9`/`#19`); `membership_resource_scopes` administration
(F-16/F-25, blocked on the manager-scoping product decision);
per-key administration granularity; denial auditing; correlation IDs
(no such infrastructure exists yet in any domain); the platform-admin
parallel surface (F-26's per-endpoint audit criterion already governs
it).

## Design

Package `com.workin.backend.members`: `MemberController`,
`MemberAdminService` (result objects `NotFound` | `SelfMutation` |
`LastAdmin` | `Duplicate` | `Done`, mapped to 404/409/409/409/2xx —
the three 409 variants exist so tests can assert the right rule fired
via the response body reason), `TenantAuditService` +
`TenantAuditEvent` entity/repository, JPA entities/repositories for
`membership_roles` and `membership_permission_overrides` if none
exist yet (they are currently reached only via SQL and
`PermissionEvaluationService`'s queries — verified at plan time).
Existing `PermissionEvaluationService` is untouched: per-request
recomputation already makes changes bite on the next request (F-19).

## Testing

`MemberAdminFlowTest` (F-24's deliverable): role assign/remove and
override grant/revoke round-trips, with an end-to-end proof that an
override granted through the real endpoint takes effect on the
target's next request and stops after revocation (F-19's guarantee,
now via the administration surface instead of SQL); self-role,
self-override, and self-status mutations → 409; last-admin role
removal and last-admin disable → 409 (attempted by a non-admin
holding `members.manage`, so the self rule doesn't mask the
last-admin rule); `platform.*` and unknown-key grants → 404;
duplicate role and duplicate override → 409; disabling a member fails
that member closed on their next request; cross-tenant target
membership → 404 for every operation, list exclusion; read-without-
manage → 403 on every mutation; unauthenticated → non-2xx; every
successful mutation's audit row asserted via SQL (actor, target,
action).

## Consequences

F-24 closes with real endpoints behind it, F-19/F-20's guarantees get
end-to-end coverage through the administration surface, and the
authorization module's remaining open rows narrow to the
manager-scoping family (F-16/F-25), which is product-gated.

# Member Administration First Slice Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The authorization module's administration surface (roles, permission overrides, membership status) with §8's escalation rules, §9's audit trail, and F-24's test suite, per `docs/superpowers/specs/2026-08-07-member-administration-first-slice-design.md`, by the established module template.

**Architecture:** New flat package `com.workin.backend.members`; two migrations (V23 catalog keys + `tenant_audit_events`, V24 its RLS); `MemberAdminService` returns sealed results (`NotFound` | `SelfMutation` | `LastAdmin` | `Duplicate` | `Done`) mapped to 404/409/409/409/2xx with distinct reason strings; `TenantAuditService` is the single audit write path. `PermissionEvaluationService` is untouched — per-request recomputation already makes every change bite next request.

**Tech Stack:** Spring Boot, JPA (+ two native queries via `EntityManager`, the `PermissionEvaluationService` precedent), Flyway, Testcontainers via WSL, `Instant` for TIMESTAMPTZ.

## Global Constraints

- Everything the prior module plans' constraints say (tenant scoping before state checks; `tenantSessionVariable.apply(...)` first line of every `@Transactional` service method; foreign/nonexistent targets → the same 404; no Lombok; records for DTOs).
- Self-mutation → 409 on every mutation targeting the caller's own membership, checked **before** any other rule.
- Last-admin → 409 on both paths: removing `COMPANY_ADMIN` from, or disabling, the only ACTIVE membership holding `COMPANY_ADMIN` in the company.
- `platform.*` and unknown permission keys → 404, indistinguishable.
- Every successful mutation writes exactly one `tenant_audit_events` row; the idempotent status no-op writes none.
- Run `markdownlint-cli2@0.23.2` on `**/*.md` before any docs push (PR #50 lesson).

---

### Task 1: Schema (V23 + V24) and PermissionKeys constants

**Files:**

- Create: `backend/src/main/resources/db/migration/common/V23__create_member_administration.sql`
- Create: `backend/src/main/resources/db/migration/rls/V24__enable_tenant_audit_events_row_level_security.sql`
- Modify: `backend/src/main/java/com/workin/backend/authorization/PermissionKeys.java` (add two constants)

**Interfaces:** Produces catalog keys `members.read`/`members.manage`, constants `PermissionKeys.MEMBERS_READ`/`MEMBERS_MANAGE`, and table `tenant_audit_events` exactly as below.

- [ ] **Step 1: V23** — new keys seeded with `COMPANY_ADMIN` bundle rows (V20's full-catalog seeding predates them), audit table mirroring V17's shape plus tenant/actor/target columns:

```sql
-- members.read / members.manage are new-platform keys: legacy's
-- hr_permissions has no member-administration flag (role administration
-- was implicit company-admin dashboard behavior). COMPANY_ADMIN gets
-- both, matching V20's full-tenant-catalog rule for that role.
INSERT INTO permissions (permission_key, description) VALUES
    ('members.read', 'View tenant members, their roles, and permission overrides (new platform; no legacy flag)'),
    ('members.manage', 'Assign/remove roles, grant/revoke permission overrides, activate/disable memberships (new platform; no legacy flag)');

INSERT INTO role_permissions (role, permission_id)
SELECT 'COMPANY_ADMIN', p.id FROM permissions p
WHERE p.permission_key IN ('members.read', 'members.manage');

-- docs/architecture/authorization-model.md section 9: tenant-domain
-- audit with individual actor attribution -- the tenant counterpart of
-- V17's platform_admin_audit_events. detail carries before/after
-- context where applicable. RLS in rls/V24.
CREATE TABLE tenant_audit_events (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    company_id BIGINT NOT NULL REFERENCES companies (id),
    actor_membership_id BIGINT NOT NULL REFERENCES tenant_memberships (id),
    target_membership_id BIGINT REFERENCES tenant_memberships (id),
    action VARCHAR(64) NOT NULL,
    permission_key VARCHAR(128),
    detail TEXT,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX tenant_audit_events_company_id_idx ON tenant_audit_events (company_id);
CREATE INDEX tenant_audit_events_actor_idx ON tenant_audit_events (actor_membership_id);
```

- [ ] **Step 2: V24** — V14's exact enable+force+policy pattern for `tenant_audit_events` only.
- [ ] **Step 3: Red** — add the two constants is NOT yet done; run `./gradlew test --tests "com.workin.backend.authorization.PermissionCatalogSyncTest" --rerun` via WSL. Expected: FAIL — two rows without constants.
- [ ] **Step 4: Green** — add to `PermissionKeys`: `public static final String MEMBERS_READ = "members.read";` and `MEMBERS_MANAGE = "members.manage";` re-run the sync test. Expected: PASS.
- [ ] **Step 5: Commit** — `feat(backend): members.read/members.manage catalog keys and tenant_audit_events schema (V23/V24)`.

### Task 2: Members module (TDD) — F-24's deliverable

**Files:**

- Test: `backend/src/test/java/com/workin/backend/members/MemberAdminFlowTest.java`
- Create: `members/MemberController.java`, `members/MemberAdminService.java`, `members/TenantAuditService.java`, `members/TenantAuditEvent.java`, `members/TenantAuditEventRepository.java`, `members/MembershipPermissionOverride.java`, `members/MembershipPermissionOverrideRepository.java`, `members/AssignRoleRequest.java`, `members/UpsertOverrideRequest.java`, `members/UpdateStatusRequest.java`, `members/MemberView.java`, `members/MemberDetailView.java`, `members/OverrideView.java`, `members/OverrideEffect.java`
- Modify: `backend/src/main/java/com/workin/backend/tenancy/TenantMembership.java` (add `disable()`/`activate()` mutators — no setter exists today), `backend/src/main/java/com/workin/backend/tenancy/MembershipRoleRepository.java` (add finders), `backend/src/main/java/com/workin/backend/tenancy/TenantMembershipRepository.java` (add `findByIdAndCompanyId` if absent — check first)

**Interfaces:**

- DTOs/views:

```java
public record AssignRoleRequest(@NotNull TenantRole role) {}
public enum OverrideEffect { ALLOW, DENY }
public record UpsertOverrideRequest(@NotBlank String permissionKey, @NotNull OverrideEffect effect) {}
public record UpdateStatusRequest(@NotNull MembershipStatus status) {}
public record MemberView(Long membershipId, String phone, List<TenantRole> roles, MembershipStatus status) {}
public record OverrideView(String permissionKey, OverrideEffect effect) {}
public record MemberDetailView(Long membershipId, String phone, List<TenantRole> roles, MembershipStatus status, List<OverrideView> overrides) {}
```

- `MembershipPermissionOverride` entity maps V18 (`id`, `membership_id`, `company_id`, `permission_id`, `effect` as String); repository: `findByMembershipIdAndPermissionId(Long, Long)`, `findByMembershipId(Long)`.
- `MembershipRoleRepository` additions: `Optional<MembershipRoleAssignment> findByMembershipIdAndRole(Long membershipId, TenantRole role)`, `List<MembershipRoleAssignment> findByCompanyId(Long companyId)` (match `MembershipRoleAssignment`'s actual field types — read the entity first; if `role` is a String column, take String and pass `role.name()`).
- `TenantAuditService.record(AuthorizationContext context, Long targetMembershipId, String action, String permissionKey, String detail)` — inserts one `TenantAuditEvent`; actions: `ROLE_ASSIGNED`, `ROLE_REMOVED`, `OVERRIDE_GRANTED`, `OVERRIDE_REVOKED`, `MEMBERSHIP_DISABLED`, `MEMBERSHIP_REACTIVATED`.
- `MemberAdminService` (all methods `@Transactional`, `tenantSessionVariable.apply` first; every mutation: resolve target via `TenantMembershipRepository.findByIdAndCompanyId` → `NotFound`; then `target.getId().equals(context.membershipId())` → `SelfMutation`; then rule-specific checks):
  - `list(context)` → `List<MemberView>` — native query joining `tenant_memberships m JOIN identities i ON i.id = m.identity_id WHERE m.company_id = :companyId ORDER BY m.id` (the `PermissionEvaluationService` EntityManager precedent), roles merged from `findByCompanyId`.
  - `get(context, membershipId)` → `Optional<MemberDetailView>` — overrides resolved to keys via a native query joining `permissions`.
  - `assignRole(context, membershipId, TenantRole role)` → duplicate assignment (existing `findByMembershipIdAndRole` hit) → `Duplicate`; else save + audit.
  - `removeRole(context, membershipId, TenantRole role)` → assignment absent → `NotFound`; if `role == COMPANY_ADMIN` and no OTHER ACTIVE membership in the company holds `COMPANY_ADMIN` (native count query joining `membership_roles` and `tenant_memberships` on status = 'ACTIVE', excluding the target) → `LastAdmin`; else delete + audit with `detail` naming the removed role.
  - `grantOverride(context, membershipId, UpsertOverrideRequest r)` → `r.permissionKey().startsWith("platform.")` → `NotFound`; permission row absent (native `SELECT id FROM permissions WHERE permission_key = :key`) → `NotFound`; existing override for (membership, permission) → `Duplicate`; else save + audit with `permission_key` set.
  - `revokeOverride(context, membershipId, String permissionKey)` → same namespace/unknown-key handling → `NotFound`; no override row → `NotFound`; else delete + audit.
  - `updateStatus(context, membershipId, MembershipStatus s)` → same status already → `Done` (no-op, **no audit row**); disabling a membership whose roles include `COMPANY_ADMIN` with no other ACTIVE `COMPANY_ADMIN` (same count query) → `LastAdmin`; else mutate + audit (`MEMBERSHIP_DISABLED`/`MEMBERSHIP_REACTIVATED`, `detail` = prior status).
- `MemberController` (`/api/tenant/members`): GET `""` and GET `/{membershipId}` `@RequiresPermission(PermissionKeys.MEMBERS_READ)`; POST `/{membershipId}/roles` (201), DELETE `/{membershipId}/roles/{role}` (204), POST `/{membershipId}/permission-overrides` (201), DELETE `/{membershipId}/permission-overrides/{permissionKey}` (204), PUT `/{membershipId}/status` (200) — all `@RequiresPermission(PermissionKeys.MEMBERS_MANAGE)`. Result mapping: `NotFound` → 404; `SelfMutation` → `ResponseStatusException(CONFLICT, "own membership cannot be administered")`; `LastAdmin` → `(CONFLICT, "last active company admin")`; `Duplicate` → `(CONFLICT, "already present")`. The `contextFrom(request)` static helper as in every controller.

- [ ] **Step 1: Failing test** — `MemberAdminFlowTest` extends `AbstractIntegrationTest`, penalties-test fixture helpers (phone prefix `+2018`). The fixture's key actor: `loginHrMember` + `allowPermission(hr, PermissionKeys.MEMBERS_MANAGE)` gives a **non-admin manager-of-members**, so self/last-admin rules can be tested without the actor being the admin in question. Cases:
  - admin assigns HR role to a fresh member (201), listed in GET; assigns again → 409; removes (204); removes again → 404;
  - override end-to-end: admin grants `penalties.read` ALLOW to a member (201) → that member's `GET /api/tenant/penalties` succeeds on their next request; admin revokes (204) → the same request is 403 — F-19's guarantee through the real surface;
  - self rules: the `members.manage` HR actor targeting their own membership — role assign, override grant, status change → all 409 with reason containing "own membership";
  - last-admin: company has exactly one `COMPANY_ADMIN` membership; the HR actor removes its `COMPANY_ADMIN` role → 409; disables it → 409 (reason containing "last active"); after the admin assigns `COMPANY_ADMIN` to a second member, removing the first's role succeeds (204);
  - namespace/unknown: granting `platform.companies.read` → 404; granting `no.such.key` → 404 — byte-identical status to the foreign-membership 404;
  - disable-bites: admin disables a member (200) → that member's next request with their existing token fails closed (non-2xx); reactivates (200) → member works again;
  - status no-op: setting ACTIVE on an ACTIVE membership → 200 and the audit table gained no row (SQL count before/after);
  - F-18: company B admin targeting company A's membershipId → 404 for every operation; B's list excludes A's memberships;
  - read≠manage: `MEMBERS_READ`-only member → list 200, every mutation 403;
  - unauthenticated → non-2xx;
  - audit: after the happy-path mutations, SQL-assert rows exist with the right (actor_membership_id, target_membership_id, action), and `OVERRIDE_GRANTED` rows carry `permission_key`.
- [ ] **Step 2: Red** — `./gradlew compileTestJava` via WSL fails on missing symbols.
- [ ] **Step 3: Implement** per Interfaces above (read `MembershipRoleAssignment` first for exact field types).
- [ ] **Step 4: Green** — `./gradlew test --tests "com.workin.backend.members.MemberAdminFlowTest"` via WSL; verify counts from `build/test-results` XML.
- [ ] **Step 5: Commit** — `feat(backend): member administration first slice -- roles, overrides, status with section-8 escalation rules and tenant audit (F-24)`.

### Task 3: Full verification + docs

- [ ] Full suite `--rerun` via WSL; totals from XML.
- [ ] Matrix updates: F-24 row → Done citing `MemberAdminFlowTest` (self-role-change, last-admin removal AND disable, namespace crossing — all §8 rules with real endpoints); F-18 row adds member administration; F-19/F-20 rows gain a one-line "now also proven end-to-end through the administration surface" note.
- [ ] `python3 scripts/validate_phase0.py` in WSL exits 0; `markdownlint-cli2@0.23.2` clean.
- [ ] Commit `docs(migration): close F-24 and record member-administration coverage`; PR after human push.

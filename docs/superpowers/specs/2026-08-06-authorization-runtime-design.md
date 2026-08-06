# Authorization Runtime — Permission Evaluation, Role Bundles, Enforcement (2026-08-06)

## Purpose And Authority

The slice that makes ADR-0010's authorization model executable in the
tenant domain: the `membership_permission_overrides` schema, default
role bundles, a permission-evaluation service implementing Dimension
3's precedence chain, runtime enforcement of `@RequiresPermission`
(unfreezing F-23's tripwire in the same PR, as that rule requires), and
the F-17/F-19/F-20 behavior test suites. Anchored to:

- ADR-0010 Dimension 3 (precedence: explicit deny → explicit allow →
  role-granted → deny by default), Dimension 5 (changes effective on
  the next request; server-side load per request; request-local
  memoization only; no cross-request cache), Dimension 2 (evaluation
  happens inside the validated-context transaction).
- `docs/architecture/authorization-model.md` §3 (schema), §7 (legacy
  mapping and migration rule).
- Matrix rows F-15 (mechanism half), F-17, F-19, F-20.

Assigned by the repository owner 2026-08-06 ("proceed").

## Scope

**In**: overrides schema + RLS; `role_permissions` default bundles;
effective-permission computation inside `AuthorizationContext`;
`@RequiresPermission` runtime enforcement for tenant-domain handlers;
freeze-rule replacement; F-17 (permission-matrix behavior), F-19
(revocation next-request), F-20 (disabled membership fail-closed) test
coverage for everything shipped here.

**Out (tracked)**: the legacy-data migration run itself (converting
real `hr_permissions` rows to override rows — mechanism's *input* is a
production snapshot, executed near cutover per F-15); resource scopes
(`membership_resource_scopes`, F-16/F-25 — possession ≠ scope, and no
scoped endpoint exists yet); platform-domain permission evaluation
(no `platform.*` business endpoint exists; `@RequiresPermission` stays
structurally confined to the tenant domain by a new arch rule until
that arrives); the service-layer arch-rule extension (first business
module).

## Design

### 1. Schema

- **V18 (common)** `membership_permission_overrides`: `id`,
  `membership_id` FK → `tenant_memberships`, `company_id` FK →
  `companies`, `permission_id` FK → `permissions`, `effect
  VARCHAR(8) CHECK IN ('ALLOW','DENY')`, `UNIQUE (membership_id,
  permission_id)`, indexes on `company_id` and `membership_id`.
  Tenant-owned data.
- **V19 (rls)**: ENABLE + FORCE row level security with the same
  `company_id = current_setting('app.current_company_id')` policy shape
  as V5.
- **V20 (common)** seeds `role_permissions`:
  - `COMPANY_ADMIN` → every catalog permission whose key does not start
    with `platform.` (via `INSERT ... SELECT`, so it tracks the catalog
    as of this migration). Grounded in confirmed legacy behavior:
    legacy `company_admin` had unconditional full tenant access — the
    `hr_permissions` matrix only ever constrained `hr`-role employees.
  - `HR`, `MANAGER`, `EMPLOYEE` → **no default rows**, deliberately.
    Legacy HR capability was entirely per-employee (the matrix →
    migrated as ALLOW overrides per §7's rule); manager capability is
    explicit resource scopes, never role-implied (§1); employee
    self-service is the own-data default scope, not catalog
    permissions. Least privilege with exact legacy fidelity — an
    implementation-level mapping derived from confirmed legacy
    behavior, not a new architecture decision.

### 2. Effective permissions inside `AuthorizationContext`

`AuthorizationContext` gains `Set<String> permissions` (the effective
allowed keys) and a `hasPermission(String)` helper.
`TenantContextService.establishContext` computes it **inside its
existing transaction** — this is load-bearing, not stylistic: the RLS
session variable is `SET LOCAL`, so the overrides read is only scoped
(and only *visible*) on that same connection/transaction. A new
`PermissionEvaluationService` (package `authorization`) does the
computation via the shared `EntityManager`/JPA repositories:

- role-granted keys: `role_permissions ⋈ permissions` for the
  membership's roles (global tables, no RLS);
- override rows for the membership (RLS-scoped native query on the
  shared `EntityManager`; no JPA entity — nothing else reads or writes
  the table from Java yet, and an unused entity would be dead code.
  The entity arrives with the first admin surface that manages
  overrides);
- effective = (roleGranted ∪ explicitAllows) − explicitDenies —
  precisely Dimension 3's precedence, since a DENY subtracts last and
  absence of any rule yields nothing (deny by default).

Per Dimension 5 there is no caching: every `establishContext` call
recomputes, so any role/override/membership change is live on the next
request by construction.

### 3. Runtime enforcement of `@RequiresPermission`

A Spring MVC `HandlerInterceptor` (`AuthorizationPolicyInterceptor`,
registered for all paths) inspects the target `HandlerMethod` in
`preHandle`:

- No `@RequiresPermission` → pass through (public/authentication-only
  policies are enforced by the existing security filter chains).
- With `@RequiresPermission`: the principal must be a tenant
  `AuthenticatedPrincipal` (else 403 — the annotation is
  tenant-domain-only for now); the interceptor runs
  `TenantContextService.establishContext(...)` (fail-closed exactly as
  everywhere else — a disabled membership or identity/tenant mismatch
  never reaches evaluation) and returns 403 when
  `context.hasPermission(key)` is false, with no detail about why
  (§8: denials never reveal resource existence). On success the
  validated context is stashed as a request attribute
  (`AuthorizationContext.class.getName()`) so the handler can reuse it
  instead of re-establishing — Dimension 5's permitted request-local
  memoization.

### 4. Architecture-rule change (the F-23 unfreeze, done honestly)

The freeze rule and its fixture are **replaced in this same PR** (as
the rule's own message requires) by a narrower structural rule:
`@RequiresPermission` may not appear on handlers in
`com.workin.backend.platformadmin..` — the platform domain has no
evaluation path yet, so a platform usage would be decorative. A new
fixture proves the new rule fails on a platform-package usage. The
completeness and placement rules are unchanged.

### 5. Proof endpoint strategy

Production gains no business endpoint in this slice (none is designed
yet). End-to-end HTTP proof uses a test-context-registered probe
controller (`@TestConfiguration` bean; Spring's handler mapping
registers any `@RestController` bean, and test classes are never
component-scanned into production), gated by
`@RequiresPermission(PermissionKeys.EMPLOYEES_READ)`. The interceptor
and evaluation path exercised are production code end to end.

## Testing

1. `PermissionEvaluationTest` (integration, service level): role grant
   works (`COMPANY_ADMIN` → `employees.read`); platform keys are never
   tenant-granted; DENY beats role grant; ALLOW grants beyond role;
   no rule → deny (F-17's matrix core for the roles/keys shipped).
2. `AuthorizationEnforcementFlowTest` (integration, HTTP, probe
   controller):
   - `COMPANY_ADMIN` token → 200 (role-granted).
   - HR-role membership (SQL fixture identity) → 403 (default deny).
   - ALLOW override inserted → next request 200; override flipped to
     DENY → **very next request** 403 with the same token (F-19).
   - membership disabled → fail-closed non-2xx via the established
     `TenantContextException` path (F-20's missing disabled case).
   - no/invalid token → 403.
3. Arch tests updated: freeze fixture replaced by platform-package
   fixture; all still proven-to-fail.
4. Full suite green.

## Consequences

`@RequiresPermission` becomes real: the first business module can gate
endpoints by catalog key with enforcement, per-membership overrides,
and next-request revocation already tested. The remaining F-15 work is
the legacy-data conversion run (needs a production snapshot) — the
tables, precedence, and override semantics it targets are now live.

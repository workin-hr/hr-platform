# ADR-0012: Phase 1 Tenant Isolation Without Row-Level Security

## Metadata

| Field | Value |
|---|---|
| ADR ID | ADR-0012 |
| Title | Phase 1 Tenant Isolation Without Row-Level Security |
| Status | Accepted |
| Date | 2026-08-16 (accepted 2026-08-16 — see `docs/bootstrap/decision-log.md` D-041) |
| Owners | Solution Architect, Security |
| Deciders | Repository owner — recorded in `docs/bootstrap/decision-log.md` D-041 |
| Related Issues | `hr-platform#74` (the one tenant-attributable table already outside RLS) |
| Supersedes | None — scopes ADR-0002 Part B and ADR-0010 Dimension 7 to Phase 2 |
| Superseded By | None |

## Context

ADR-0002 Part B and ADR-0010 Dimension 7 make PostgreSQL row-level security the
tenant-isolation backstop, and the codebase implements that seriously: 12
migrations under `db/migration/rls/` put `ENABLE` + `FORCE ROW LEVEL SECURITY`
on 24 tenant-owned tables; `RlsDataSourceConfig` runs Flyway as superuser and the
application as a non-superuser role so policies actually apply;
`SuperuserStartupCheck` fails startup if that is ever misconfigured, because
PostgreSQL bypasses RLS for superusers silently.

ADR-0011 moves Phase 1 onto the legacy MySQL contract. **MySQL and MariaDB have
no row-level security.** There is no equivalent feature, no partial substitute at
the database layer, and — per ADR-0011's Database Rule — changing production
storage to compensate is not available.

So the question is not whether to port RLS. It is what replaces it, and how the
replacement is held to account.

Two facts bound the answer. First, this is **not** a regression against
production: the PHP system enforces tenant scoping entirely in application code
today, with no database backstop, and that is the system Phase 1 replaces.
Second, it **is** a regression against what the Java system currently has, and
`#74` already demonstrates the failure mode — one table with a `NOT NULL
company_id` sitting outside RLS, not exploitable today, kept safe only by the
shape of the queries that happen to be written against it.

## Decision

**Accepted 2026-08-16** (`docs/bootstrap/decision-log.md` D-041).

For Phase 1, tenant isolation is **enforced in the application and verified by
tests**, with the following mandatory parts. All four are load-bearing; adopting
the filter without the controls is explicitly not this decision.

**1. A single enforcement point.** Tenant scoping is applied by one mechanism —
a Hibernate filter activated once per transaction from the authenticated
`AuthorizationContext`'s company id — not by remembering to add a predicate at
each call site. The structural analogue of `SET LOCAL`, in one place, so there is
one thing to review and one thing to break.

**2. The context is the only source of the tenant id.** A company id from a
request, a path variable, a body field or an unvalidated JWT claim never reaches
the filter. It is re-derived from the database against the authenticated
principal first, as `TenantContextService` already does. Phase 1 changes the
*enforcement mechanism*, not the *trust rule*.

**3. Fail closed, and prove it.** Absence of a tenant scope must deny, never
return unscoped rows. This is the property RLS gave for free and the one most
easily lost, so it is the one most heavily tested:

- Every scenario in `RlsFailClosedTest` and `TenantContextIsolationTest` is
  ported assertion-for-assertion to the filter. Same expectations, different
  mechanism.
- A build-failing architecture test asserts that every repository method reading
  a tenant-owned entity is covered by the filter or takes an explicit
  `companyId`. Precedent exists: `AuthorizationPolicyArchTest` already enforces
  a rule of this shape in this codebase.
- A test asserts that a query issued with **no** tenant scope established
  returns nothing and raises, rather than returning all tenants' rows.

**4. The gap is stated, not implied.** Phase 1 runs with a weaker isolation
guarantee than Phase 2 will. That is recorded here, in the threat model, and in
the Phase 1 exit criteria — not left for a reader to infer from a missing
migration.

**Scope.** This applies only while the application runs against MySQL. The RLS
migrations, the two-DataSource split and `SuperuserStartupCheck` are **frozen,
not deleted** — profile-gated so they stay compiled and tested — and Phase 2
returns to them. This decision does not reopen the tenant-isolation pattern; it
scopes ADR-0002 Part B and ADR-0010 Dimension 7 to the phase that can run them.

## Alternatives Considered

- **Emulate RLS with database views or a per-tenant connection user.** MySQL has
  no policy mechanism; view-based emulation requires every access to go through
  views and is bypassed by any direct table reference, so it buys ceremony
  rather than a guarantee. Per-tenant database users do not scale to a tenant
  table and would change production storage besides.
- **Per-tenant schema or database.** A storage redesign, forbidden in Phase 1 by
  ADR-0011's Database Rule, and a substantial operational change to a system
  that must remain rollback-compatible with PHP.
- **Keep PostgreSQL for Phase 1 and accept the schema redesign.** This is the
  status quo ante the strategy reset rejected: it forfeits single-variable
  parity testing and rollback-to-PHP, which are the entire point of the
  sequencing.
- **Adopt the filter without the architecture test.** Rejected explicitly. A
  convention that "queries should be scoped" is exactly what `#74` shows
  decaying — the mechanism has to be checkable by the build, or the guarantee
  is a habit.
- **Ship Phase 1 with application scoping and no recorded posture change.**
  Rejected: an undocumented reduction in a security control on a multi-tenant HR
  system is the failure this ADR exists to prevent.

## Consequences

- **Defence in depth is reduced for the duration of Phase 1.** A query written
  without scope is caught by the architecture test rather than refused by the
  database. If the test is wrong or is bypassed, nothing else stops it.
- **The blast radius of a mistake is cross-tenant data exposure** in an HR system
  holding salaries, national ids and attendance. Every compensating control
  above is justified by that, not by tidiness.
- The security review standard gains a standing item: any new repository method
  on a tenant-owned entity must be scoped, and the test must cover it.
- `docs/security/threat-model.md` must record the Phase 1 posture and its
  expiry — it currently describes RLS as the control.
- Phase 2 restores the database backstop, at which point the filter becomes
  redundant defence rather than the only defence. It should be kept, not removed
  — two independent controls is the end state, not one replacing the other.
- `#74` becomes moot for Phase 1 (no table has RLS) and returns in Phase 2
  unchanged.

## Risks

- **The architecture test under-approximates.** It can only check shapes it knows
  about; a scoping bug expressed some other way passes. Mitigation: the
  differential harness (ADR-0011) compares database deltas between PHP and Java,
  so a cross-tenant write shows up as a row appearing where PHP put none.
- **The filter is silently inactive.** A Hibernate filter that is never enabled
  fails open, which is the worst available failure mode. Mitigation: the
  no-scope-established test asserts denial rather than emptiness — a passing
  suite with a disabled filter is what that test exists to prevent.
- **The port of the RLS tests loses assertions in translation.** Mitigation:
  port scenario-for-scenario and keep the original test names, so the mapping is
  reviewable rather than reconstructed.
- **Phase 1 outlives its expected duration** and the temporary posture becomes
  permanent by default. Mitigation: recorded here with an explicit expiry
  condition (Phase 2 cutover) and surfaced in the Phase 1 exit criteria.

## Validation Evidence

- RLS surface measured directly: 12 migrations under
  `backend/src/main/resources/db/migration/rls/` (V5, V6, V14, V19, V22, V24,
  V26, V28, V30, V32, V34, V39); `RlsDataSourceConfig.java:24-59`;
  `SuperuserStartupCheck.java:13-38`; `TenantSessionVariable.java:29-34`.
- The one PostgreSQL-only statement in the application is that filter's
  activation, `SELECT set_config('app.current_company_id', :companyId, true)` —
  confirmed by inspection of all 11 `createNativeQuery` sites, the other 10
  being ANSI.
- MariaDB 11.8 confirmed as the Phase 1 engine and exercised in CI (`#100`,
  `cfef222`): 298 tests, 0 failed, 0 skipped. MariaDB provides no row-level
  security feature to target.
- The legacy system's own posture read from source: tenant scoping in
  `hr-legacy` is applied per query in PHP with no database enforcement, so Phase
  1 matches production's actual guarantee rather than weakening it.
- `hr-platform#74` documents the decay mode this ADR's controls target: a
  `NOT NULL company_id` table outside RLS, safe only because of how its queries
  happen to be written.
- Precedent that a build-failing architecture test works in this codebase:
  `AuthorizationPolicyArchTest`, with fixtures under
  `backend/src/test/java/com/workin/backend/authorization/archfixtures/`.

## Open Questions

- Whether the architecture test should also fail on a *service* method that
  takes a raw `companyId` parameter without deriving it from
  `AuthorizationContext` — stricter, and possibly too strict for legacy-shaped
  code paths.
- Whether Phase 1 should additionally log every query executed without a tenant
  scope, as a detection control alongside the preventive ones. Cost is unknown
  until the filter exists.
- Whether the filter is kept in Phase 2 as redundant defence (the position this
  ADR takes) or retired once RLS returns. A Phase 2 decision, noted so it is not
  settled by omission.
- Whether any legitimate cross-tenant read exists in the legacy contract that the
  filter must accommodate — the login-by-phone lookup is deliberately
  pre-tenant, and whether anything else is has not been exhaustively confirmed.

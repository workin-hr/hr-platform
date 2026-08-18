# Phase 1 — Legacy Login Endpoint Design (Punch-List Item #9)

## Decided

The repository owner confirmed the recommended option on all four
judgment calls raised by the research proposal
(`/tmp/.../legacy-login-endpoint-proposal.md`, not committed — scratch
work; this doc is the durable record):

1. **URL path**: `/api/legacy/auth/login_employee`, a new path scoped
   under `/api/legacy/**`. D-040 requires behavioural parity (status
   codes, outcomes), not URL parity — `AuthController`'s whole
   `/api/auth/*` family is itself new relative to legacy.
2. **JWT claims**: reuse `JwtService.issueAccessToken` as-is, unmodified.
   `identityId` and `membershipId` both carry the legacy employee id
   (legacy has no separate membership concept); `companyId` carries the
   resolved company id.
3. **Response body**: session material only — `accessToken`,
   `refreshToken`, `employeeId`, `companyId`. Legacy's `public_row(employee)`
   payload is not reproduced; the client fetches the profile separately
   after authenticating, the same pattern `AuthController.register`/`login`
   already use.
4. **Architecture**: parallel, not shared. A new controller, a new
   `@Order(3)` security chain scoped to `/api/legacy/**`, and a new
   tenant-context service — not a branch inside the existing
   `/api/auth/login` path or `TenantContextService`.

## Built so far

**`LegacyTenantContextService`** (`com.workin.legacy.auth`) — the actual
trust-boundary logic decision 2 depends on. `TenantScopeFilter`'s
resolver contract requires deriving the tenant id from the authenticated
principal, not trusting a claim outright; this service re-derives the
claimed employee from `LegacyEmployeeRepository`, cross-checks it
against the authenticated identity and the claimed company, and throws
`NoTenantScopeException` on any mismatch — mirroring
`TenantContextService.establishContext`'s re-derive-then-cross-check
order on the PostgreSQL side.

It must call `TenantFilterActivator.deactivateForPreTenantLookup()`
before querying: it runs before any `TenantScope` is established (that
is its job), and `TenantAwareJpaTransactionManager` binds every fresh
transaction to the `NO_TENANT` sentinel by default, which would
otherwise make the re-derivation query itself return zero rows
unconditionally — the same problem the pre-tenant phone lookup already
solves, applied here for the first time to a *post-authentication*
lookup rather than a pre-authentication one.

Tested against real MariaDB (`LegacyTenantContextServiceTest`,
`com.workin.legacy.auth`), through a real Spring Data
`LegacyEmployeeRepository` proxy built with `JpaRepositoryFactory`
rather than a full Spring context — the same reason
`TenantBindingEndToEndTest` builds its own `EntityManagerFactory`: the
application still boots against PostgreSQL until auth/authz is
reworked, so there is no MySQL context to inject a repository from yet.
This is also the first proof that `LegacyEmployeeRepository`'s derived
query methods work through Hibernate against the real legacy schema —
`LegacyEmployeeAdapterTest`'s javadoc names that as deferred work; this
is that step, scoped to the one method this service calls. 5 tests: a
legitimate claim is accepted and returns the employee's real company; a
claimed company that does not match the employee's real company is
rejected; a claimed employee id that belongs to someone else is
rejected; a claimed employee id that does not exist is rejected; and the
lookup is proven to run correctly inside a fresh, `NO_TENANT`-bound
transaction (i.e. the deactivation call is proven load-bearing, not
decorative).

## Blocked — not built

**Token issuance for the login controller.** `RefreshTokenService`
cannot be reused as-is for legacy sessions, contrary to the research
proposal's tentative assumption. Two independent findings, either one
sufficient on its own:

- **Schema**: `V15__create_refresh_tokens.sql` declares real foreign
  keys — `identity_id REFERENCES identities(id)`,
  `membership_id REFERENCES tenant_memberships(id)`,
  `company_id REFERENCES companies(id)` — all three PostgreSQL tables. A
  legacy employee id has no row in `identities` or
  `tenant_memberships`; inserting a refresh token for one would violate
  the FK constraints outright.
- **Behaviour**: `RefreshTokenService.rotate()` calls
  `identityRepository.findById(...)` and
  `membershipIndexService.findMembershipsForIdentity(...)` to re-check
  liveness on every rotation. Both are PostgreSQL-identity-model
  specific; called with a legacy employee id, `identityActive` resolves
  `false` and the very first rotation attempt revokes the family as a
  reuse-detection false positive.

So this is not a schema-only gap a new FK-free table would necessarily
fix — rotation's liveness re-check is coupled to the PostgreSQL identity
model at the service-method level. Reusing `RefreshTokenService` for
legacy sessions would require either a legacy-specific rotation path
with its own liveness check (a real design decision, not a mechanical
port) or a decision that legacy sessions don't get rotation-with-reuse-detection
at all, which conflicts with D-042's "outcomes are parity, token
lifetime is the recorded exception" stance — token lifetime already
changed once (dropping the 10-year JWT); dropping rotation too would be
a second, uncosted divergence.

**Not built as a result**: the login controller (its success path needs
a refresh token to return), the `@Order(3)` security chain, and the
end-to-end integration test. All three are mechanical once the token
question is answered — the blocking piece is exclusively token
issuance.

## Decided — 2026-08-17

**Option (a).** A parallel `legacy_refresh_tokens` table with its own
rotation-with-reuse-detection state machine, liveness re-checked via
`LegacyEmployeeRepository` (employee still active) rather than the
PostgreSQL identity/membership lookups — keeping the rotation model for
legacy sessions rather than dropping it, consistent with D-042's stance
that only token *lifetime* (not the rotation model) is a recorded
exception.

## Built — refresh-token state machine

- **`legacy_refresh_tokens`**, `backend/src/test/resources/legacy/phase1_extensions.schema.sql`
  — a *new* file, deliberately not folded into the vendored
  `mysql_workin.schema.sql`: this table is Phase 1's own infrastructure,
  not part of the legacy contract, and mixing the two would make
  `check_legacy_schema_drift.py`'s comparison meaningless.
  `AbstractLegacyMySqlTest` now applies both files to the shared MariaDB
  instance. **Deliberately omits `company_id`** — unlike Postgres's
  `refresh_tokens`, which stores it as a load-bearing but explicitly
  unfiltered exception (`RefreshTokenRepository`'s own javadoc), a legacy
  session can always re-derive its company from `employee_id` via
  `LegacyEmployeeRepository`, and Phase 1 already established
  re-deriving rather than caching a tenant value as the standing policy
  (`LegacyTenantContextService`). Omitting the column also means the
  table needs no exemption from `TenantFilterCoverageTest`'s
  `company_id`-implies-filtered rule — adding one would itself be a
  security-control change, not a side effect of a refresh-token table.
- **`LegacyRefreshToken`/`LegacyRefreshTokenRepository`/`LegacyRefreshTokenService`**
  (`com.workin.legacy.auth`) — mirrors
  `com.workin.backend.identity.RefreshToken`/`RefreshTokenRepository`/`RefreshTokenService`'s
  issue/rotate/logout/revoke-all shape. The one real divergence:
  `rotate()`'s liveness re-check queries `employees`, a tenant-filtered
  table, from a call site that — like login — runs before any
  `TenantScope` is established. Without
  `TenantFilterActivator.deactivateForPreTenantLookup()` first, the
  `NO_TENANT` sentinel bound to every fresh transaction would make the
  liveness lookup itself return zero rows unconditionally, turning every
  legitimate rotation into a false-positive revocation. Caught by
  building the test first: the initial version of `rotate()` did not
  call it and the honest-path test failed for the wrong reason before
  any code was fixed.
- **`LegacyRefreshTokenServiceTest`** — real MariaDB, same
  `JpaRepositoryFactory`-without-Spring-context pattern
  `LegacyTenantContextServiceTest` established. 4 tests: issue-then-rotate
  succeeds; reusing an already-rotated token revokes the whole family,
  including links issued after the reused one; a deactivated employee's
  token is refused on rotation; logout revokes the family so a
  subsequent rotation is refused.
- **Fixed as a side effect**: `LegacyEmployeeAdapterTest`'s
  `theVendoredLegacySchemaAppliesToARealMariaDbUnmodified` hardcoded an
  exact global `information_schema.tables` count (42) against the shared
  MariaDB instance, to prove the vendored legacy schema applies
  unmodified. `phase1_extensions.schema.sql` legitimately adds a 43rd
  table to that same shared instance, which a raw count can't
  distinguish from the legacy contract itself changing. Rewritten to
  check the vendored schema's own 42 `CREATE TABLE` names specifically
  (parsed from the vendored file), so it still catches the contract
  drifting and no longer breaks when Phase 1 adds its own, separately
  tracked tables.

Full suite: 54 classes, 345 tests, 0 failed, 0 skipped, confirmed via
JUnit XML.

## Blocked — not built: the controller, the security chain, the end-to-end HTTP test

Building these next surfaced a blocker one level up from the
refresh-token question, and a harder one: **no Phase 1 code has ever run
inside a live Spring application context.** Everything built on this
branch so far — `TenantScope`, `TenantScopeFilter`,
`TenantAwareJpaTransactionManager`, `LegacyTenantContextService`, and now
`LegacyRefreshTokenService` — is proven correct, but only by hand-wiring
a `DataSource`/`EntityManagerFactory`/`TransactionTemplate` directly
inside each test (`AbstractLegacyMySqlTest` + the pattern
`LegacyTenantContextServiceTest` established). `LegacyEmployeeAdapterTest`
names this explicitly: *"The Spring/JPA half of the adapter is not
exercised yet on purpose: the application still boots against
PostgreSQL until auth/authz is reworked, so there is no MySQL context to
load it into. That step comes next, and this test is what it will build
on."*

Concretely: there is no `DataSource`/`EntityManagerFactory`/
`PlatformTransactionManager` bean for MariaDB anywhere in
`backend/src/main/java`, `com.workin.legacy` is deliberately excluded
from the main application's component-scan root
(`LegacyAdapterIsolationTest` pins exactly this, so Hibernate never
tries to validate a legacy entity against the PostgreSQL schema), and
`application.properties` configures Flyway and JPA only for the
PostgreSQL datasource. A real `@RestController` at
`/api/legacy/auth/login_employee` and a real `SecurityConfig` chain
would need to inject `LegacyLoginResolver`, `LegacyTenantContextService`
and `LegacyRefreshTokenService` as Spring beans reachable from the main
context — which means either standing up a second, simultaneous
datasource/JPA context alongside the PostgreSQL one, or the full
PostgreSQL-to-MySQL context swap `LegacyEmployeeAdapterTest`'s own
wording gestures at ("when the Spring context is later pointed at
MySQL"). ADR-0011 says only that Phase 1 was "added alongside the
PostgreSQL substrate, not replacing it. The swap happens when the
replacement exists" — it does not say whether that swap is a hard cutover
(one profile replaces the other) or a transition period with both
contexts live at once, and does not say when it happens relative to the
rest of the login/tenant-isolation work.

**This is not this task's decision to make.** Whichever shape is chosen
determines how *every* future Phase 1 HTTP endpoint gets built, not just
this one — it is a foundational piece of infrastructure worth its own
explicit call (and likely its own ADR-level record, the same way
ADR-0011/ADR-0012 recorded the sequencing and tenant-isolation postures
before code followed), not something to default into as an incidental
side effect of wiring one login endpoint.

**Not built as a result**: the login controller, the `@Order(3)`
security chain, and the end-to-end HTTP integration test. All three are
mechanical once the datasource/context question is answered — every
piece of logic they would call (`LegacyLoginResolver`,
`LegacyTenantContextService`, `LegacyRefreshTokenService`,
`TenantScopeFilter`, `TenantAwareJpaTransactionManager`) already exists
and is already tested.

## Decided — 2026-08-17, later the same day

**Full profile swap, once — not a simultaneous dual datasource.** The
app boots against either PostgreSQL or MySQL, not both at once. Scope
for the login endpoint specifically: stand up a distinct,
explicitly-activated Spring profile that boots against MySQL/MariaDB,
prove the login endpoint works end to end under it, and leave the
default PostgreSQL profile and its test suite completely untouched.
Flipping the *default* profile once enough modules exist is a separate,
later decision.

## Blocked — not built: the profile mechanism itself

Investigating what "stand up a profile-scoped config class" actually
requires surfaced a larger blocker than that phrase implies, and this
document stops here rather than forcing a partial version of it.

**`BackendApplication` is a single `@SpringBootApplication` with no
existing `@Profile` usage anywhere in `backend/src/main/java` (checked
directly — zero hits).** Its implicit `@ComponentScan` and Spring Boot's
default JPA autoconfiguration together reach all of `com.workin.backend`:
31 `@Entity` classes, every `@Repository`, and every `@Service`/
`@RestController` that depends on one — which, transitively, is nearly
all of it. `LegacyAdapterIsolationTest`'s own javadoc anticipates "an
explicit `@EntityScan`/`@EnableJpaRepositories` under the MySQL profile,"
but that only addresses entity/repository *scanning*. It does not
address:

- **Postgres-specific beans that run unconditionally today** —
  `RlsDataSourceConfig` (`applicationDataSource`/`flywayDataSource`,
  built with the Postgres JDBC driver via `JdbcConnectionDetails`) and
  `SuperuserStartupCheck` (an `ApplicationRunner` that queries Postgres's
  `pg_user` catalog against `applicationDataSource` on every boot, ADR-0002
  condition 1). Both are plain `@Component`/`@Configuration` with no
  `@Profile` guard; under a MySQL-pointed context they would either fail
  outright (wrong SQL dialect) or need to not exist at all.
- **`spring.flyway.locations=classpath:db/migration/common,classpath:db/migration/rls`**
  in `application.properties` — Postgres DDL, unconditional, would need to
  not run under this profile.
- **Every existing controller/service with a JPA-repository dependency**
  (the 20 already-mapped modules' worth) would fail Spring context
  startup with `NoSuchBeanDefinitionException` the moment the default
  entity/repository scan of `com.workin.backend` is disabled or redirected
  — which restricting scanning to `com.workin.legacy.**` under the new
  profile necessarily does. `@EnableJpaRepositories`/`@EntityScan` are
  *additive*, not a replacement for the default scan; suppressing the
  default requires excluding Spring Boot's JPA/DataSource/Flyway
  autoconfiguration for this profile specifically (e.g. profile-scoped
  `spring.autoconfigure.exclude`), which in turn means hand-building the
  JPA infrastructure (`LocalContainerEntityManagerFactoryBean`,
  `PlatformTransactionManager`) the way `RlsDataSourceConfig` does for
  Postgres today, plus deciding what happens to every controller/service
  that can no longer be instantiated.

So "the mechanism" is not one profile-scoped `@Configuration` class — it
is excluding or reworking the boot path of most of the existing
application under the new profile, correctly, without breaking the
default profile it currently serves. That is a repository-wide
restructuring decision (which packages are cross-cutting infrastructure
reachable from both profiles — `JwtService` and the security scaffolding
the login controller needs are themselves under `com.workin.backend.identity`
— versus which are Postgres-domain-specific and must be excluded), not
something this task's scope covers, and forcing a narrower proof (e.g. a
hand-built `ApplicationContext` wiring only the new beans, bypassing
`BackendApplication` entirely) would validate the classes' own wiring
but not the real question: how the deployed application actually boots
in MySQL mode. That would be a different, lesser proof than what was
asked, presented as if it answered the same question.

**Not built**: the login controller, the `@Order(3)` security chain, the
profile itself, and the end-to-end HTTP test. Every piece of logic they
would call — `LegacyLoginResolver`, `LegacyTenantContextService`,
`LegacyRefreshTokenService`, `TenantScopeFilter`,
`TenantAwareJpaTransactionManager` — already exists and is already
tested in isolation (54 classes, 345 tests, 0 failed, unchanged from the
prior entry; nothing in this investigation touched production code).

## Next

A decision on how much of `com.workin.backend`'s existing boot path gets
excluded or reworked under the new MySQL profile, and how cross-cutting
infrastructure (`JwtService`, the security-config scaffolding, anything
else the legacy login path needs that currently lives inside
`com.workin.backend`) is shared between both profiles without pulling
Postgres-specific beans (`RlsDataSourceConfig`, `SuperuserStartupCheck`,
the RLS Flyway migrations) along with it. Likely its own ADR, given the
size of the restructuring and that it determines how every future
Phase 1 HTTP endpoint gets built, not just this one. Once resolved, the
controller and security chain are the mechanical step this document
already specified, and punch-list item #10 (the forged-claim isolation
attack) can follow immediately after.

**Update, 2026-08-17**: `docs/adr/ADR-0013-phase1-mysql-profile-bootstrap.md`
proposed a concrete mechanism — a Spring profile, inactive by default, with
Boot's single-context JPA/DataSource/Flyway autoconfiguration replaced by two
explicit profile-gated configuration classes.

**Update, 2026-08-17 (later the same day)**: **ADR-0013 is now `Accepted`**
(`docs/bootstrap/decision-log.md` D-043), with four owner-required
amendments: the profile is named **`phase1-mysql`**, not `legacy`; the
ArchUnit profile-coverage guard must be built first or in the same commit as
the bootstrap wiring, not after; no Flyway ownership of any MariaDB schema,
including `legacy_refresh_tokens` (external contract, drift-checked, not
application-migrated); and platform-admin auth is excluded from
`phase1-mysql` unless legacy-PHP discovery proves it belongs to the
strict-parity contract. Login-endpoint work (the ArchUnit guard, the
profile-gated bootstrap, the controller, the `@Order(2)` security chain, the
end-to-end test) resumes against this accepted design.

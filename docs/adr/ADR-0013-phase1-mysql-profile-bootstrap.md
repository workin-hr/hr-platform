# ADR-0013: Phase 1 MySQL-Profile Application Bootstrap

## Metadata

| Field | Value |
|---|---|
| ADR ID | ADR-0013 |
| Title | Phase 1 MySQL-Profile Application Bootstrap |
| Status | Proposed |
| Date | 2026-08-17 |
| Owners | Solution Architect |
| Deciders | Repository owner |
| Related Issues | None yet |
| Supersedes | None — makes concrete what ADR-0011 left open ("the swap happens when the replacement exists," not its shape) |
| Superseded By | None |

Valid `Status` values: `Proposed`, `Accepted`, `Rejected`, `Superseded`,
`Deferred`. New ADRs must start `Proposed`.

## Context

Building the Phase 1 legacy login endpoint
(`docs/migration/2026-08-17-phase1-legacy-login-endpoint-design.md`) surfaced
that no Phase 1 code has ever run inside a live Spring application context.
Every piece built so far — `TenantScope`, `TenantScopeFilter`,
`TenantAwareJpaTransactionManager`, `LegacyTenantContextService`,
`LegacyRefreshTokenService`, `LegacyEmployeeRepository` — is proven only by
hand-wiring a `DataSource`/`EntityManagerFactory` directly inside each test
(`AbstractLegacyMySqlTest`'s pattern). A real `@RestController` needs these as
Spring beans reachable from the running application, which requires deciding
how the MySQL/MariaDB substrate becomes live.

The repository owner already decided the shape at the datasource level: **a
full profile swap, once — not a simultaneous dual datasource.** The app boots
against either PostgreSQL or MySQL, not both at once. What remained
undecided, and is this ADR's subject, is the mechanism — because it turns out
to be substantially bigger than "add a profile-scoped `@Configuration`
class":

- **`BackendApplication` has zero existing `@Profile` usage** (checked
  directly — grep for `@Profile` under `backend/src/main/java` returns no
  hits). Its implicit `@ComponentScan`/`@EntityScan`/`@EnableJpaRepositories`,
  all rooted at `com.workin.backend` by `@SpringBootApplication`'s default,
  reach all 31 `@Entity` classes and all 31 `@Repository` interfaces
  unconditionally.
- **Two Postgres-only components run on every boot today with no guard**:
  `RlsDataSourceConfig` (builds the Postgres `applicationDataSource`/
  `flywayDataSource` beans — note it only supplies custom `DataSource` beans;
  Spring Boot's `HibernateJpaAutoConfiguration`/`JpaRepositoriesAutoConfiguration`
  still run automatically against them) and `SuperuserStartupCheck` (an
  `ApplicationRunner` that queries Postgres's `pg_user` catalog on every
  boot, per ADR-0002 condition 1 — this would either fail outright against
  MariaDB or silently mean nothing).
- **`application.properties` sets `spring.flyway.locations=classpath:db/migration/common,classpath:db/migration/rls`
  unconditionally** — Postgres DDL that cannot run against MariaDB.
- **`LegacyAdapterIsolationTest`'s own javadoc anticipated the scanning half
  of this** ("an explicit `@EntityScan`/`@EnableJpaRepositories` under the
  MySQL profile") but not that Spring Boot's implicit scan of
  `com.workin.backend` would need to stop reaching the ~20 already-mapped
  modules' controllers/services under that same profile, or they fail
  Spring context startup with `NoSuchBeanDefinitionException` the moment
  `@EnableJpaRepositories` no longer covers `com.workin.backend`.

A full inventory of `backend/src/main/java/com/workin/backend/**` for this
ADR found the package layout is **not** cleanly split between
"infrastructure" and "domain" today. Twelve packages are entirely
Postgres-domain and self-contained (`advances`, `attendance`,
`companysettings`, `employees`, `holidays`, `members`, `organization`,
`payroll`, `penalties`, `platformadmin`, `requests`, `schedule` — every
`@Entity`/`@Repository`/`@Service`/`@RestController` inside each is
Postgres-only, confirmed by grep). Five packages are **mixed** — genuinely
cross-cutting classes sit alongside Postgres-specific ones in the same
package:

| Package | Cross-cutting (needed under both profiles) | Postgres-only (needs guarding) |
|---|---|---|
| `identity` | `JwtService` (zero JPA dependency — confirmed by reading it directly, only needs `app.jwt.secret`) | `AuthController`, `AuthResponse`, `Company`, `CompanyRepository`, `Identity`, `IdentityRepository`, `LoginRequest`, `LoginService`, `RefreshToken`, `RefreshTokenRepository`, `RefreshTokenRequest`, `RefreshTokenService`, `RefreshTokenStatus`, `RegisterCompanyRequest`, `RegistrationService` (14) |
| `security` | `JwtAuthenticationFilter` (not a bean — constructed manually in `SecurityConfig`), `ApiSecurityErrorHandler`, `OpaqueTokens` (static utility, not a bean), `AuthenticatedPrincipal`/`AuthenticatedPlatformAdminPrincipal` (records, not beans) | `PlatformAdminAuthenticationFilter` (not a bean, but its only caller is `SecurityConfig`'s platform-admin chain, which depends on `platformadmin.PlatformAdminJwtService`); `SecurityConfig` itself needs restructuring, see Decision §3 |
| `tenancy` | `TenantScope`, `TenantScopeFilter`, `NoTenantScopeException`, `TenantContextException` (already reused as-is by the legacy code built so far) | `AuthorizationContext`, `IdentityMembershipIndexService`, `MembershipRoleAssignment`, `MembershipRoleRepository`, `MembershipStatus`, `TenantContextService`, `TenantController`, `TenantMembership`, `TenantMembershipRepository`, `TenantRole`, `TenantSessionVariable` (11) |
| `config` | `JwtSecretStartupCheck` | `RlsDataSourceConfig`, `SuperuserStartupCheck` (2) |
| `authorization` | `RequiresPermission`, `PermissionKeys`, `AuthenticatedUseCase`, `PublicUseCase`, `ResourceScopeType` (pure annotations/marker types, not beans) | `AuthorizationPolicyInterceptor`, `AuthorizationPolicyWebConfig`, `PermissionEvaluationService`, `ResourceScope`, `ResourceScopeRepository`, `ResourceScopeService` (6) |
| `i18n` | Entire package (`Messages`, `LocaleResolutionFilter`, `LocaleResolverConfig`, `ApiExceptionHandler`, `ApiErrorBody`, `ApiException`, `ApiValidationErrorBody`, `FieldViolation`, `MessageKeys`, `SupportedLocales`) — confirmed zero `Repository`/`@Entity` references | None |

Thirty-three individual classes across five packages need a per-class guard;
twelve packages need no per-class treatment at all because every class in
them is uniformly Postgres-only.

## Decision

**Approval status: Proposed — this decision has not been approved.** This
section describes a candidate direction; it must not be read as an accepted
architecture decision until `Status` above changes to `Accepted`.

**1. Introduce a `legacy` Spring profile, inactive by default.** The
default (no profile, or any profile other than `legacy`) preserves today's
behavior byte-for-byte: PostgreSQL, RLS, all 20 mapped modules, all existing
tests. `legacy` is explicitly activated (`SPRING_PROFILES_ACTIVE=legacy`) —
never the implicit default — until a later, separate decision flips that,
once module coverage justifies it (tracked as an Open Question below, not
decided here).

**2. Disable Spring Boot's single-context JPA/DataSource/Flyway
autoconfiguration globally**, on `BackendApplication`:
`exclude = {DataSourceAutoConfiguration.class, JpaRepositoriesAutoConfiguration.class, HibernateJpaAutoConfiguration.class, FlywayAutoConfiguration.class}`.
Replace what it did with two explicit, mutually exclusive, profile-gated
`@Configuration` classes — never both active at once, which is exactly what
"full profile swap, not simultaneous" means at the bean-definition level:

   - **`PostgresPersistenceConfig`** (`@Profile("!legacy")`) — moves
     `RlsDataSourceConfig`'s existing `applicationDataSource`/
     `flywayDataSource` beans here unchanged, adds an explicit
     `LocalContainerEntityManagerFactoryBean`/`PlatformTransactionManager`
     pair and `@EnableJpaRepositories(basePackages = "com.workin.backend", ...)`
     / `@EntityScan("com.workin.backend")` — i.e., today's implicit behavior,
     made explicit rather than changed. Flyway locations
     (`db/migration/common`, `db/migration/rls`) move from
     `application.properties` into this class's own Flyway configuration,
     also `@Profile("!legacy")`-gated.
   - **`LegacyPersistenceConfig`** (`@Profile("legacy")`, new) — a MariaDB
     `DataSource` built the same way `AbstractLegacyMySqlTest` already proves
     out (promoting that test-only connection logic to production
     configuration, not inventing a new one), `@EnableJpaRepositories(basePackages = "com.workin.legacy", transactionManagerRef = "legacyTransactionManager", ...)`
     wired to the already-built `TenantAwareJpaTransactionManager` as the
     `PlatformTransactionManager`, `@EntityScan("com.workin.legacy")`. No
     Flyway against the vendored legacy schema itself (Phase 1 never
     migrates legacy's own tables — `check_legacy_schema_drift.py` is what
     keeps it honest instead); Phase-1-owned tables
     (`legacy_refresh_tokens`, currently applied only inside
     `AbstractLegacyMySqlTest` from `phase1_extensions.schema.sql`) get
     their own Flyway location, `db/migration/legacy`, active only under
     this profile — mirroring `db/migration/rls`'s existing precedent of a
     domain-scoped migration folder alongside `common`.

**3. Component scanning stays `com.workin.backend`'s full tree under the
default profile** (`PostgresPersistenceConfig`'s `@Profile("!legacy")`
carries `@ComponentScan("com.workin.backend")` alongside its `@EntityScan`,
so nothing about default-profile behavior changes). Under `legacy`,
`LegacyPersistenceConfig` carries `@ComponentScan("com.workin.legacy")` —
`com.workin.legacy` is scanned **only** here, never unconditionally, so
`LegacyAdapterIsolationTest`'s existing package-placement assertions need no
change: they prove a source-code fact (legacy classes live outside
`com.workin.backend`) that stays true regardless of which profile is active,
and remain the prerequisite that makes this profile-gating possible at all,
not the runtime mechanism itself.

**4. Guard the twelve pure-Postgres-domain packages by scan-exclusion, not
per-class annotation.** `PostgresPersistenceConfig`'s
`@ComponentScan("com.workin.backend")` stays whole-tree (default profile is
unchanged); nothing needs excluding there. The problem is the opposite
direction — under `legacy`, `BackendApplication`'s own implicit
`@SpringBootApplication` scan must stop reaching `com.workin.backend` at
all, so those 20 modules' controllers/services are never even attempted.
Concretely: `BackendApplication` moves from an implicit, unqualified
`@ComponentScan` to `@SpringBootApplication(scanBasePackages = {})` (or
equivalent — scan nothing by default) plus the two profile-gated
`@Configuration` classes above supplying their own `@ComponentScan`, so under
`legacy`, only `com.workin.legacy` and the cross-cutting classes listed next
are ever scanned.

**5. Guard the thirty-three mixed-package Postgres-specific classes
individually with `@Profile("!legacy")`** — the full list is the "Postgres-
only" column of the table in Context. This is a one-time, bounded,
enumerable change (33 annotations), not an open-ended convention. The five
cross-cutting classes/exceptions (`JwtService`, `TenantScope`,
`TenantScopeFilter`, `NoTenantScopeException`, `TenantContextException`) and
the whole `i18n` package need no guard — they carry no JPA dependency and are
safe to construct under either profile.

**6. `SecurityConfig` gets a third chain**, `@Order(2)`,
`securityMatcher("/api/legacy/**")`, wired to `JwtAuthenticationFilter`
(reused unchanged, per the existing login-endpoint design doc's decision)
and the not-yet-built `LegacyTenantScopeFilter`-equivalent request binding.
The existing no-matcher tenant chain (today `@Order(2)`) shifts to
`@Order(3)`. The platform-admin chain's `@Bean` method itself gets
`@Profile("!legacy")`, since `PlatformAdminJwtService` lives in the now
profile-excluded `platformadmin` package — platform-admin login is not
reachable under the `legacy` profile (see Open Questions: is that
acceptable, or does platform-admin need its own cross-cutting treatment
later).

**7. `AuthorizationPolicyWebConfig`'s blanket interceptor registration does
not run under `legacy`** (it is itself Postgres-guarded, §5). This is
consistent with, not a new gap alongside, the already-known fact that
`hr_permissions` authorization mapping (punch-list item #11) is not built
for legacy yet — `@RequiresPermission` simply has no active enforcement
under this profile until that item lands, which needs its own decision
about how (or whether) `AuthorizationPolicyInterceptor` gets a legacy-side
equivalent.

## Alternatives Considered

- **Two simultaneous datasources, live at once.** Already rejected by the
  repository owner (recorded in
  `docs/migration/2026-08-17-phase1-legacy-login-endpoint-design.md`) in
  favor of a full swap. Would have avoided this ADR's scanning problem
  entirely (both contexts always present, nothing to exclude) at the cost of
  running two connection pools and two transaction managers permanently
  during the transition, and blurring the "one variable changes at a time"
  property ADR-0011's sequencing exists to protect.
- **A second, separate Spring Boot application/module for Phase 1**,
  entirely independent of `BackendApplication`. Rejected: it would duplicate
  every cross-cutting class (`JwtService`, `i18n`, the security-error
  contract) rather than share them, and two deployable artifacts contradicts
  "full profile swap, once" — the whole point is one application that points
  at a different database, not two applications.
- **Per-class `@Profile` annotation for every class in every package,
  including the twelve pure-domain ones**, instead of scan-exclusion for
  those twelve. Rejected as needless: none of those ~150+ classes has any
  ambiguity about which profile they belong to, so annotating each
  individually is pure repetition with no safety benefit over excluding the
  package wholesale — and it invites the exact "someone forgets" failure
  mode ADR-0012 already refused to accept for tenant scoping.
- **Move the 33 mixed-package classes into new, cleanly-separated packages**
  (e.g. `com.workin.backend.shared.jwt`) instead of annotating them in
  place. Would make the split self-evident from package structure alone and
  remove the need for `@Profile` on cross-cutting classes entirely, but is a
  larger one-time refactor across `identity`, `security`, `tenancy`,
  `config`, and `authorization`, and touches significantly more files for
  marginal benefit over 33 explicit annotations. Not proposed now; worth
  reconsidering if the mixed-package list grows.

## Consequences

- **The default profile's behavior is provably unchanged** by construction
  — `PostgresPersistenceConfig` reproduces today's implicit wiring
  explicitly rather than altering it, and the existing ~50 integration
  tests are the check.
- **A new ongoing discipline**: any future Postgres-specific class added to
  `identity`, `security`, `tenancy`, `config`, or `authorization` needs
  `@Profile("!legacy")`, or it silently attempts construction under
  `legacy` and fails context startup with a clear
  `NoSuchBeanDefinitionException` — loud, not silent, but still a rule a
  human has to remember. See Risks for the proposed mitigation.
- **Platform-admin login is not reachable under the `legacy` profile** as
  proposed. If that turns out to be needed sooner than expected, it needs
  its own treatment (see Open Questions).
- **`@RequiresPermission` enforcement does not run under `legacy`** until
  punch-list item #11 (`hr_permissions` mapping) gives it a legacy-side
  equivalent. No regression versus today (nothing legacy-side exists yet
  regardless), but worth stating rather than leaving implicit, per ADR-0012
  item 4's own standard.
- **This determines how every future Phase 1 HTTP endpoint boots**, not
  just login — accepting this ADR unblocks the login controller, the
  `@Order(2)` security chain, and the end-to-end test the design doc has
  been waiting on, and sets the pattern the 19-missing-module and
  20-remap punch-list items (11-13) build on too.

## Risks

- **The 33-class guard list rots.** Mitigation: an ArchUnit test, matching
  this repository's own established pattern (`AuthorizationPolicyArchTest`,
  `LegacyAdapterIsolationTest`) — assert every class in the five mixed
  packages that depends on a `com.workin.backend`-scoped `@Repository`
  carries `@Profile("!legacy")`. Not built by this ADR; proposed as the
  first thing implementation does once accepted, before the profile is
  wired, so the guard exists from the start rather than being bolted on
  after a gap is found the hard way (the same lesson `coverage_audit.py`'s
  Finding I and `parse_target_schema`'s ALTER-TABLE gap already taught this
  repository twice).
- **`scanBasePackages = {}` on `@SpringBootApplication` is an unusual
  pattern** and easy to get subtly wrong (e.g. accidentally also
  suppressing Spring Boot's own autoconfiguration classes, which live
  outside `com.workin.backend` and are found by a different mechanism —
  `spring.factories`/`AutoConfiguration.imports` — not component scanning,
  so this should be safe, but needs verifying against a real boot, not
  assumed from reading the source). Mitigation: the end-to-end test the
  login-endpoint design doc already specifies is exactly what proves this
  in practice.
- **Splitting Flyway locations per profile means the two profiles' schemas
  can drift out of sync with what each one's tests actually exercise** if
  `db/migration/legacy` isn't kept under the same self-testing discipline
  `check_legacy_schema_drift.py` already applies to the vendored schema.
  Mitigation: `legacy_refresh_tokens` already has its own test coverage;
  extend the same pattern to any future `db/migration/legacy` addition.

## Validation Evidence

- Direct source inspection, this ADR's own research pass, 2026-08-17:
  `grep -rn "@Profile" backend/src/main/java` — zero hits. `grep -rl
  "@Entity"`/`"@Repository"` across `com.workin.backend` — 31/31,
  confirming ADR-0011's own discovery numbers still hold.
  `RlsDataSourceConfig.java` read directly — confirms it supplies only
  `DataSource` beans, not a hand-built `EntityManagerFactory`, so Boot's
  automatic JPA wiring is still doing real work today that this ADR's
  `PostgresPersistenceConfig` needs to make explicit, not simply copy.
  `JwtService.java`, `ApiSecurityErrorHandler.java`,
  `JwtAuthenticationFilter.java`, `JwtSecretStartupCheck.java`, all of
  `i18n/*.java`, `AuthorizationPolicyWebConfig.java`,
  `AuthorizationPolicyInterceptor.java` read directly to confirm the
  cross-cutting/Postgres-specific classification in the Context table.
  `LegacyAdapterIsolationTest.java` and its javadoc read directly.
  `TenantAwareJpaTransactionManager` confirmed already located under
  `com.workin.legacy`, not `com.workin.backend.tenancy` — already correctly
  scoped for this ADR's mechanism, no move needed.
- `backend/build.gradle` confirms Spring Boot 4.1.0 — the autoconfiguration
  classes named in Decision §2
  (`DataSourceAutoConfiguration`/`JpaRepositoriesAutoConfiguration`/
  `HibernateJpaAutoConfiguration`/`FlywayAutoConfiguration`) are current
  Spring Boot 4.x/Spring Data JPA package names as of this version; verify
  exact import paths against the actual dependency versions during
  implementation rather than assuming from memory.
- Still needed before this can move from `Proposed` to `Accepted`: a real
  boot of the application under the `legacy` profile (the end-to-end test
  the login-endpoint design doc already specifies), proving both that the
  default profile is unaffected and that the new profile actually starts —
  static source analysis alone, which is all this ADR's research consisted
  of, cannot substitute for that.

## Open Questions

- Is platform-admin login needed under the `legacy` profile at all during
  Phase 1, or is it acceptable that it stays default-profile-only until a
  later phase? This ADR assumes the latter but does not decide it.
- What triggers flipping `legacy` from an explicitly-activated profile to
  the actual default — punch-list item #13 (all 19 missing modules)
  completing, item #12 (remapping the 20 built modules) completing, some
  earlier internal milestone, or a separate owner call unrelated to module
  count? ADR-0011 itself left this open ("whether Phase 1 delivers all 38
  modules in one release or several internal milestones... the engineering
  sequence within it is not yet fixed") — this ADR does not resolve it,
  only makes today's mechanism not depend on the answer.
- Should the 33-class-list ArchUnit guard (Risks) be written before or
  alongside the profile mechanism itself, given it is the actual safety net
  the mechanism depends on? Proposed as "before," but this is a sequencing
  preference, not forced by anything structural.
- Does `db/migration/legacy` need the same `check_legacy_schema_drift.py`-style
  self-testing discipline from day one, or is `LegacyRefreshTokenServiceTest`'s
  existing coverage sufficient until the migration set grows? Not resolved
  here.

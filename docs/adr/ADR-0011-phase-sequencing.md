# ADR-0011: Phase Sequencing — Implementation, Then Storage, Then Modernization

## Metadata

| Field | Value |
|---|---|
| ADR ID | ADR-0011 |
| Title | Phase Sequencing — Implementation, Then Storage, Then Modernization |
| Status | Accepted |
| Date | 2026-08-16 (accepted 2026-08-16 — see `docs/bootstrap/decision-log.md` D-040) |
| Owners | Solution Architect, Migration Engineer |
| Deciders | Repository owner — recorded in `docs/bootstrap/decision-log.md` D-040 |
| Related Issues | `hr-platform#72` (API contract divergence, now Phase 1 blocking) |
| Supersedes | None — amends the implicit ordering assumed by ADR-0002, ADR-0004 and ADR-0005 |
| Superseded By | None |

## Context

The rewrite had been running six transformations concurrently: PHP → Java,
MySQL → PostgreSQL, schema redesign, data cleanup, business-rule correction and
platform modernization. Each is individually reasonable. Together they made
every failure ambiguous — a wrong number could be a port defect, a migration
defect, a schema decision or a deliberate rule change, and telling them apart
required holding all six in mind at once.

Repository-wide discovery on 2026-08-16 (`workin-phase1-strategy-reset.html`)
established what that concurrency had actually produced:

- The Java application is **not** meaningfully coupled to PostgreSQL as a
  *dialect*. All 31 entities use portable `GenerationType.IDENTITY`, there is no
  `columnDefinition` anywhere, no `nativeQuery` in any repository, and exactly
  one PostgreSQL-only statement exists in 242 files
  (`TenantSessionVariable.java:31`, `set_config`).
- It **is** deeply coupled to a redesigned schema. Eleven tables it depends on
  do not exist in legacy MySQL; `company_settings` exists in both with
  incompatible shapes; `leave_balance` was renamed; `is_active` became `active`.
- Nineteen of 38 legacy API modules have no Java counterpart at all.

So the expensive coupling was never the database engine. It was that the new
system had been designed against a schema and an identity model the running
system does not have, which meant no comparison against production behaviour was
possible at any point.

## Decision

**Accepted 2026-08-16** (`docs/bootstrap/decision-log.md` D-040).

**One major transformation at a time, in this order:**

1. **Phase 1 — Replace PHP with Java against the existing MySQL storage
   contract.** Same database, tables, columns, meanings, relationships, ids and
   data representations. No PostgreSQL dependency, no ETL required to launch, no
   target-schema redesign, no opportunistic normalization, no cleanup of
   historical data merely because Java dislikes it. **Java adapts to storage;
   storage does not adapt to Java.**
2. **Phase 2 — Migrate storage.** Java + MySQL → Java + PostgreSQL. This phase
   owns the target schema, Flyway, ETL, type conversion, remediation,
   reconciliation, cutover and write freeze.
3. **Phase 3 — Modernize.** Schema redesign, removal of legacy compatibility,
   stronger constraints, better domain modelling, business-rule cleanup.

Three scope decisions, taken with the discovery numbers visible:

- **Strict legacy API contract parity** in Phase 1 — the URL surface, envelope,
  casing and error body legacy serves. This reverses the direction the shipped
  backend took and makes `#72` blocking rather than documentation debt.
- **Full 38-module replacement**, not a strangler. Java replaces the PHP backend
  at one milestone; PHP and Java never share ownership of a module. The Java
  codebase may remain a modular monolith internally — that is unrelated to
  deployment sequencing.
- **Freeze, do not delete, the PostgreSQL/ETL work.** It is valid Phase 2
  engineering whose sequencing assumption changed, not waste.

**Read compatibility is separated from write correctness.** Java must read the
malformed legacy values that already exist. It must not generate new ones where
avoiding them does not break legitimate business behaviour. Every intentional
difference must be explicit, justified and tested — bug-for-bug parity is not
the goal, and neither is silent correction.

### Recorded exception: the session-token mechanism

**Accepted 2026-08-16, amending this ADR's own Open Questions.**

Strict contract parity governs **storage and business behaviour**. It does not
extend to reproducing a known weak security posture.

- **Kept, because it is business behaviour:** legacy's login semantics and the
  API-visible outcomes they produce — including
  **409 `MULTIPLE_ACCOUNTS_SAME_PHONE`**, the single-`pending`-account login
  path, and the three distinct 403 outcomes below it
  (`login_employee.php:70-107`). Tenant switching is **not** introduced in
  Phase 1; the multi-tenant identity model that removes the 409 remains Phase 3.
- **Not reverted, as an explicit exception:** legacy's **10-year JWT**. Phase 1
  keeps the short-lived access token plus refresh-token rotation model already
  built (ADR-0005). Legacy's token lifetime is a recorded defect
  (`hr-legacy#7`: no company-admin revocation mechanism), and reintroducing it
  would mean deliberately shipping a vulnerability to satisfy a parity rule
  aimed at a different concern.

This is the same read/write split applied to security rather than data: tolerate
what legacy produced, decline to keep producing it. Consistent with how
`hr-legacy#15`, `#16` and `#20` are already handled — behaviour preserved where
legitimate, defects not reproduced.

**The cost is real and is accepted:** this is a client-visible break. The Flutter
clients must adopt token refresh, which ADR-0005 already recorded as a scoped
exception to the don't-change-the-clients direction. It is the one deliberate
divergence from strict contract parity in Phase 1, and any further one needs its
own decision rather than this precedent.

### Evidence precedence for Phase 1 compatibility work

**Accepted 2026-08-18** (`docs/bootstrap/decision-log.md` D-058).

Phase 1 compatibility work — every Wave 12.x module — routinely has to decide
what "the same behaviour" means when the sources that could answer that
disagree: a schema file, the PHP source, a Java model built for a different
target, documentation, or the live system. **When evidence conflicts, Phase 1
precedence is:**

1. **Live production behaviour/data**
2. **The PHP implementation** (the actual source, its full execution path —
   not a summary or a memory of what it "should" do)
3. **The legacy schema** (the vendored dump, including every `ALTER TABLE`
   constraint — not just `CREATE TABLE` column lists; D-050 found a real,
   load-bearing unique constraint that a column-list-only reading missed)
4. **Documentation** (ADRs, specifications, prior decisions)
5. **The existing Java/PostgreSQL implementation** — lowest precedence. It was
   built against the redesigned target model, not transcribed from PHP, and
   this ADR's own "Java adapts to storage" line already governs it: it is
   never assumed compatible merely because it exists.

This orders sources; it does not license modernizing what's found. **Java
adapts to storage, storage does not adapt to Java, and Phase 1 does not
redesign, clean, normalize, or migrate the database** — the existing MariaDB
schema and its data, irregularities included, are the contract. A defect
found while establishing what "the same behaviour" means (D-055's
`manager_id`-clearing bug is the first recorded instance) is reproduced and
recorded, not silently fixed, unless the repository owner explicitly approves
a divergence for that specific case.

## Alternatives Considered

- **Continue the concurrent approach.** Rejected: it had already produced four
  decisions recorded but never built (D-035 A2/A3/A5/A6, found only by running
  the ETL, D-039), which is the signature of a plan too wide to verify.
- **Strangler migration, module by module.** Rejected by the repository owner.
  It requires PHP and Java to share ownership of live modules, which means two
  systems writing the same tables under two sets of business rules — the
  ambiguity this ADR exists to remove, relocated into production.
- **Storage first, then implementation** (migrate to PostgreSQL under PHP).
  Rejected: it puts the riskier transformation first and leaves the PHP codebase
  — which is being discarded — carrying the migration.
- **Phase 1 on PostgreSQL with a compatibility schema.** Rejected: it is a
  schema redesign wearing a compatibility label, and it forfeits the property
  that makes this sequencing safe (below).

## Consequences

- **Rollback stays available for the whole of Phase 1.** Because the storage
  contract does not change, PHP and Java run against the same database, and
  reverting is a deployment change rather than a data migration. This is the
  single largest safety dividend of the sequencing and it should not be traded
  away casually.
- **Parity becomes measurable.** `PHP + MySQL` vs `Java + MySQL` differ in one
  variable, so a differential harness is meaningful.
- **Phase 1 is larger than the previous trajectory**, and this was chosen with
  the number visible: 19 unbuilt modules and 23 unmodelled tables move onto the
  critical path.
- **Work already done splits three ways.** Business logic largely survives —
  `PayrollCalculationService` is a verified line-referenced port of
  `payroll_calculation.php:1101-1276`, and `docs/legacy/` holds 1,049 lines of
  evidence-backed rules. The identity/tenancy/authorization stack requires
  rework. The PostgreSQL/ETL assets freeze. **No artifact was classified
  obsolete.**
- **Tenant isolation loses its database backstop for Phase 1**, because MySQL
  has no row-level security. That consequence is large enough to be its own
  decision: `ADR-0012-phase-1-tenant-isolation.md`.
- ADR-0003, ADR-0004, ADR-0005, ADR-0010 and ADR-0002 all need amendment to
  state which phase they belong to.

## Risks

- **Phase 1 scope is the dominant schedule risk** — roughly half the legacy
  surface is unbuilt. Mitigation: the module inventory and business-rule
  extraction already exist, so the work is enumerable rather than discovered.
- **The frozen Phase 2 work rots.** Mitigation: `phase2Test` is compiled by CI
  on every run and runnable on demand (`./gradlew phase2Test`); the ETL
  self-tests still run.
- **"Preserve the contract" is misread as "write bad Java."** Mitigation: the
  legacy contract is confined to a persistence adapter
  (`com.workin.legacy.**`), deliberately outside the application's
  component-scan root, so legacy representations cannot leak into domain code by
  default.
- **Legacy keeps producing defective rows until cutover** (`hr-legacy#28`,
  `#29`). Accepted consequence of not patching legacy (D-037); Phase 1 refuses
  to generate new ones.

## Validation Evidence

- Discovery traced against `hr-platform` @ `da853f1` and `hr-legacy` @
  `d113204`: 242 main Java files, 45 Flyway migrations (33 common, 12 RLS), 31
  entities, 24 controllers, 68 endpoints against 200 legacy endpoint files
  across 38 modules.
- Dialect coupling measured, not assumed: `@GeneratedValue` is
  `GenerationType.IDENTITY` in all 31 entities; zero `columnDefinition`; zero
  `nativeQuery = true`; 11 `createNativeQuery` sites of which 10 are ANSI.
- Schema divergence confirmed against `mysql_workin.schema.sql`: 11 Java tables
  absent from legacy, 20 shared names, 23 legacy tables unmodelled.
- `company_settings` verified incompatible in both directions —
  `V27__create_company_settings.sql` (one row per company, five typed columns)
  against `mysql_workin.schema.sql:262-267` (EAV join to
  `company_setting_values`).
- Legacy multi-account login behaviour read directly at
  `hr-legacy/apis/api/auth/login_employee.php:18-48` and `:90-107`, corroborated
  by `docs/legacy/business-rule-extraction.md:55`.
- The first Phase 1 slice is merged and green (`#100`, `cfef222`): the legacy
  persistence pattern passes against MariaDB 11.8 — 298 tests, 0 failed, 0
  skipped, including 5 MariaDB-backed adapter tests.

## Open Questions

- Whether Phase 1 delivers all 38 modules in one release or several internal
  milestones. The **cutover** is one event by this decision; the engineering
  sequence within it is not yet fixed.
- ~~Whether the Phase 1 authentication contract keeps legacy's 10-year JWT or
  adopts short-lived tokens.~~ **Resolved 2026-08-16 — see the Decision's
  "Recorded exception" below.**
- What acceptance threshold ends Phase 1 — how much of the differential harness
  must be green, and who signs it off.
- Whether `configs` (which serves both the runtime timezone flag and the desktop
  forced-update channel) is built early enough to communicate cutover, given the
  circularity noted in `#72`.

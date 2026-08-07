# Payroll Group — Module-Level Migration Execution Plan

## Purpose And Scope

This is the PMR-09 (`docs/migration/pre-migration-readiness-gap-analysis.md`,
`hr-platform#15`) deliverable for the payroll group specifically —
`salary_contracts`, `payroll_batches`, `payslips`, `advances`, `penalties`
— the second wave in `docs/migration/migration-strategy-and-sequencing.md`'s
sequencing proposal, following the tenant/identity wave already built
under `backend/`.

This is implementation-level detail, not a repeat of the strategy
document: real Java package/class structure, the exact business rules
each class must encode (cited to their evidence), which legacy defects
must be fixed-by-construction versus explicitly preserved pending a
human decision, the authorization approach, and per-module test
requirements. It does not cover attendance, employees/profile, or any
other module — those remain separately sequenced.

**Not covered here, deliberately**: sprint/calendar scheduling (no
target dates exist anywhere in this repository's planning docs, and
none are invented here) and fine-grained permission-catalog enforcement
(`docs/migration/consolidated-task-matrix.md` F-15/F-17/F-23) — that is
tracked cross-cutting work, not something this module should build a
one-off version of. See "Authorization Approach" below for the exact
boundary.

## What Already Exists (Verified Directly, 2026-08-07)

- Schema: `backend/src/main/resources/db/migration/common/V8__create_employees.sql`
  through `V13__create_penalties.sql`, plus
  `rls/V14__enable_payroll_group_row_level_security.sql`. All 6 tables
  exist in Postgres with RLS enabled and forced, company-scoped via
  `app.current_company_id`.
- **No JPA entities, repositories, services, or controllers exist yet**
  for any of these 6 tables — confirmed via `find` across
  `backend/src/main/java` and directly stated in
  `backend/src/test/java/com/workin/backend/tenancy/PayrollGroupRlsTest.java`'s
  own class comment ("No JPA entities exist yet for this schema-only
  slice"). That test exercises the tables via raw JDBC as a placeholder.
- Identity/tenancy code (`com.workin.backend.identity`,
  `com.workin.backend.tenancy`) is the only real precedent for
  module conventions in this codebase — see "Conventions To Follow"
  below.

## Conventions To Follow (From The Identity/Tenancy Slice)

- Flat package per module (`com.workin.backend.identity`,
  `com.workin.backend.tenancy`), not one package per class type. This
  plan proposes `com.workin.backend.payroll` as one package covering
  all 5 sub-modules together — not 5 separate packages — because they
  are financially and structurally coupled (see "Why One Package, Not
  Five" below).
- Plain constructor injection, no Lombok (`AuthController.java`).
- Request/response DTOs as Java records (`LoginRequest`,
  `RegisterCompanyRequest`, `AuthResponse`).
- `jakarta.validation` (`@Valid`) at the controller boundary.
- Tenant scoping via `AuthorizationContext` (identity, membership,
  companyId, roles) resolved per-request by `TenantContextService`,
  already wired for authenticated endpoints.
- Integration tests extend `AbstractIntegrationTest` (Testcontainers
  Postgres); RLS-specific behavior gets its own test class per the
  `PayrollGroupRlsTest`/`RlsFailClosedTest`/`TenantContextIsolationTest`
  pattern already established.

### Why One Package, Not Five

`docs/migration/migration-strategy-and-sequencing.md` explicitly groups
these 5 tables as "one coordinated group, not module-by-module" because
they are tightly coupled: payroll batch calculation reads from
`salary_contracts`, `advances`, and `penalties` to produce `payslips`
rows, and the legacy system's worst correctness bugs
(`hr-legacy#12`, `#13`, `#14`) come specifically from that calculation
logic being duplicated across endpoints instead of shared. Splitting
this into 5 independent packages would make it easy to repeat that
mistake by construction. One `payroll` package with one shared
calculation service, called by every entry point that needs payroll
math, is the structural fix.

## Employee Entity — Required Prerequisite, Minimal Scope

`V8__create_employees.sql`'s own comment is explicit: this table is
"deliberately minimal... just enough for real FK integrity and RLS
company-scoping to unblock the payroll group," not the employee-profile
module. This plan needs a matching minimal `Employee` JPA entity
(`id`, `companyId`, `firstName`, `lastName`, `phone`, `passwordHash`,
`role`, `active`) — read/write only as needed to support the payroll
group's FKs and role checks. Full employee self-service, join-request
workflow, and every other legacy `employees` field are **out of scope**
here and tracked as a separate, later migration wave per the
sequencing doc. Do not expand this entity's fields beyond what
V8 actually has — that would be scope creep into a different module's
work.

## Authorization Approach

Two layers, and this plan implements only the first:

1. **Role gating** (`AuthorizationContext.roles()`, already resolved
   per-request): each endpoint checks the caller holds one of the
   specific `TenantRole`s the legacy endpoint required — reproduced
   exactly from `docs/api/existing-endpoint-inventory.md`'s per-endpoint
   tables (see each sub-module section below). This is real,
   implementable now with what already exists in `com.workin.backend.tenancy`.
2. **Fine-grained permission catalog** (legacy's `hr_permissions`
   17-flag matrix, `hr-legacy#8`) — `docs/migration/consolidated-task-matrix.md`
   F-15 (migrate flags into the new permission tables), F-17
   (permission-matrix test suite), and F-23 (architecture test
   detecting undeclared authorization policies) are tracked, blocking
   *cutover*, not blocking implementation start. **This plan does not
   invent a payroll-specific version of that enforcement.** Building an
   ad-hoc permission check here would contradict ADR-0010's own point
   (application-service method authorization as the single enforcement
   boundary) and would need to be redone once F-15/F-17/F-23 land.
   Payroll module endpoints get role gating now; fine-grained
   permission enforcement wires in later, uniformly, when that
   cross-cutting work is done — **this module cannot be marked
   cutover-ready under the Migration-Readiness Gate until that
   happens**, per `pre-migration-readiness-gap-analysis.md`'s "Must be
   true before any module's API surface is cut over" section.

### The Structural Fix For `hr-legacy#5`/`#6`/`#8`'s IDOR Pattern

`hr-legacy#5` (advances API: `approve`/`reject`/`pay`/`create`/`delete`
missing `company_id` checks) and `hr-legacy#6` (the same pattern across
10 dashboard modules including payroll) both stem from one root cause:
per-endpoint code that fetches or mutates a row by raw ID without
verifying it belongs to the caller's own company. RLS closes the
**read/update/delete-existing-row** half of this automatically — a
company-scoped session literally cannot see a row belonging to another
company, no per-endpoint code required. It does **not** automatically
close the **create-referencing-a-foreign-ID** half: nothing stops a
service method from inserting an `advances` row with the caller's own
`company_id` but an `employee_id` belonging to a different company,
unless that `employee_id` is actually re-resolved through a
company-scoped lookup first.

**Hard rule for every service class in this module**: any method that
accepts a foreign-key ID belonging to a different entity
(`employee_id`, `batch_id`) must resolve it via its own RLS-scoped
repository (`employeeRepository.findById(id)`, not a raw ID pass-through)
before using it, and treat "not found" as the correct failure mode for
a cross-tenant reference — not a special case, just what RLS naturally
returns. This is what makes `advances/create.php`'s exact bug
structurally impossible here rather than merely fixed-today, and it's
the same discipline that already made `penalties`' 7 endpoints correct
in the legacy code while `advances`' were not (confirmed in
`docs/api/existing-endpoint-inventory.md`'s Penalties section — the
correct pattern already existed elsewhere in that codebase, just
unevenly applied). Each sub-module's test list below includes an
explicit negative test for this (contributing to F-18's per-module
coverage).

## Recommended Build Order Within This Group

1. **`Employee` entity** (prerequisite for everything below).
2. **`SalaryContract`** — no dependencies on the other 4; needed by
   payroll calculation.
3. **`Advance`** and **`Penalty`** — independent of each other, both
   needed by payroll calculation. Build in parallel.
4. **`PayrollCalculationService`** (shared math) — depends on 2 and 3.
5. **`PayrollBatch`** and **`Payslip`** together — `Payslip` rows only
   exist inside a `PayrollBatch`, and `calculate.php`'s behavior (full
   delete-and-reinsert into `payslips`) is really one operation on two
   tables, not two independent features.

This order exists because `PayrollCalculationService` cannot be built
or tested correctly without real `SalaryContract`/`Advance`/`Penalty`
data to compute from, and `payroll_batches`/`payslips` cannot be built
correctly without the calculation service they depend on — reversing
this order would mean building the batch/payslip layer against a stub
and rewriting it once the real calculation service exists.

---

## Module: `SalaryContract`

**Endpoints** (`docs/api/existing-endpoint-inventory.md` "Salary
Contracts", all 5 correctly tenant-scoped in legacy — no IDOR history
here to fix):

| Endpoint | Method | Role |
|---|---|---|
| `POST /api/payroll/salary-contracts` | create | `COMPANY_ADMIN`, `HR` |
| `PUT /api/payroll/salary-contracts/{id}` | update | `COMPANY_ADMIN`, `HR` |
| `DELETE /api/payroll/salary-contracts/{id}` | delete | `COMPANY_ADMIN`, `HR` |
| `GET /api/payroll/salary-contracts?employeeId=` | list (per-employee history) | `COMPANY_ADMIN`, `HR`, `MANAGER` |
| `GET /api/payroll/salary-contracts/{id}` | one | `COMPANY_ADMIN`, `HR`, `MANAGER` |

**Business rules to encode** (`docs/legacy/business-rule-extraction.md`):

- Append-only version history. No update-in-place; "the effective
  contract for a period" is the most recent row with
  `effective_from <=` the period end. `update.php`'s existence in
  legacy is really "correct a mistake," not "change compensation
  going forward" — carry that intent into the new `update` endpoint's
  documentation/behavior, don't let callers assume it retroactively
  changes past payslips (it doesn't; payslips store their own computed
  values at calculation time).
- `salary_mode` is `MONTHLY` or `DAILY`. In `DAILY` mode, legacy zeroes
  `basic_salary` and the 4 allowance columns and uses `daily_wage`
  instead — **preserve this zeroing rule** (it's a real invariant the
  calculation service depends on being consistent), but see the
  daily-wage defect below for what must *not* be preserved.

**Legacy defects requiring an explicit decision before/at
implementation** (do not silently resolve either direction):

1. **`housing_allowance` cannot be set to nonzero anywhere in legacy**
   (`hr-legacy#14`, already flagged as an open product decision in
   `pre-migration-readiness-gap-analysis.md` PMR-06). This module's
   `create`/`update` endpoints must not hardcode `0` the way legacy
   does — but making it freely settable also isn't automatically
   correct without knowing why legacy locked it. **Blocking question
   for whoever owns this module's implementation**: is `housing_allowance`
   a normal contract field going forward (make it settable), or does
   the product intentionally want it entered per-payslip only (in
   which case, don't put a misleading settable field on the contract
   at all)? Do not implement either behavior without this answer
   recorded in `docs/bootstrap/decision-log.md` first.
2. The schema's own migration comment (`V9__create_salary_contracts.sql`)
   already deliberately dropped the legacy `total` generated column —
   no action needed here, just don't reintroduce a stored total on this
   entity; totals belong to `PayrollCalculationService`.

**Tests**:

- Standard CRUD + tenant-isolation (existing row can't be read/updated/
  deleted cross-company — already structurally covered by RLS, write a
  test proving it for this table specifically, per F-18).
- `DAILY` mode correctly zeroes `basic_salary`/allowances on create and
  update.
- Version-history query returns the correct "effective" contract for a
  given date, including a boundary case (`effective_from` exactly equal
  to the period end).

---

## Module: `Advance`

**Endpoints** (`docs/api/existing-endpoint-inventory.md` "Advances" —
this is the module with the confirmed, unmitigated IDOR history,
`hr-legacy#5`):

| Endpoint | Method | Role |
|---|---|---|
| `POST /api/payroll/advances` | create | `EMPLOYEE` (self only), `COMPANY_ADMIN`, `HR` |
| `PUT /api/payroll/advances/{id}` | update | `EMPLOYEE` (own, `PENDING` only, `amount`/`reason` only), `COMPANY_ADMIN`, `HR` (any field) |
| `DELETE /api/payroll/advances/{id}` | delete | `EMPLOYEE` (own, `PENDING` only), `COMPANY_ADMIN`, `HR` |
| `PUT /api/payroll/advances/{id}/approve` | approve | `COMPANY_ADMIN`, `HR` |
| `PUT /api/payroll/advances/{id}/reject` | reject (requires `rejectionReason`) | `COMPANY_ADMIN`, `HR` |
| `PUT /api/payroll/advances/{id}/pay` | pay (reduces `remaining`, rejects overpayment) | `COMPANY_ADMIN`, `HR` |
| `GET /api/payroll/advances?...` | list | `EMPLOYEE` (self only, see rule below), `COMPANY_ADMIN`, `HR` |
| `GET /api/payroll/advances/{id}` | one | `EMPLOYEE` (own only, 403 otherwise), `COMPANY_ADMIN`, `HR` |

**Mandatory fix, not a preservable behavior** (this is the whole reason
this module is in the payroll wave's security-critical set): every one
of these 8 methods must resolve `employeeId` through the RLS-scoped
`EmployeeRepository` per the "Structural Fix" rule above before using
it — `approve`/`reject`/`pay`/`create`/`delete` are exactly the 5
legacy endpoints confirmed missing this check
(`docs/security/threat-model.md`). This closes `hr-legacy#5`'s
acceptance criteria for this module (required before this module's API
surface can cut over, per the Migration-Readiness Gate).

**Business rules to encode**:

- Employee self-service create always forces `status = PENDING`,
  regardless of request body.
- `COMPANY_ADMIN`/`HR` create may set `status` directly. **Do not carry
  forward legacy's unvalidated-status gap** (`create.php` accepts any
  string without checking it's a real status value, contrast with
  `list.php`'s validated filter) — validate against the real status
  enum on every write path, not just reads. This is a fix-by-construction,
  not a preserve-or-decide item; there's no legitimate reason to accept
  an invalid enum value.
- `pay` must reject overpayment (`new remaining < 0`).
- Two deduction modes: `SINGLE_PAYROLL_MONTH`, `INSTALLMENTS`. The
  schema (`V12__create_advances.sql`) already deliberately dropped
  legacy's redundant second `deduction_type` enum — no action needed,
  just don't reintroduce it.

**Open product decision** (flagged in the schema migration comment,
not yet resolved): none blocking for this module specifically beyond
the deduction-mode simplification already made at the schema level.

**Tests**:

- Negative cross-tenant test for **all 5** previously-vulnerable
  methods specifically (`approve`, `reject`, `pay`, `create` with a
  foreign `employeeId`, `delete` by a non-owning `COMPANY_ADMIN`/`HR`)
  — this is the direct regression test for `hr-legacy#5`, and the most
  important test in this entire execution plan.
- Employee can only ever see/edit/delete their own advances;
  `COMPANY_ADMIN`/`HR` scoped to their own company only.
- Status-enum validation rejects garbage input on create and update
  for the `COMPANY_ADMIN`/`HR` path.
- Overpayment rejected on `pay`.

---

## Module: `Penalty`

**Endpoints** (`docs/api/existing-endpoint-inventory.md` "Penalties" —
confirmed correctly scoped in legacy on all 7 endpoints; the model to
replicate, not a defect list):

| Endpoint | Method | Role |
|---|---|---|
| `POST /api/payroll/penalties` | create | `COMPANY_ADMIN`, `HR` |
| `PUT /api/payroll/penalties/{id}` | update (blocked once `appliedToPayroll=true`) | `COMPANY_ADMIN`, `HR` |
| `DELETE /api/payroll/penalties/{id}` | delete (same lock) | `COMPANY_ADMIN`, `HR` |
| `GET /api/payroll/penalties/{id}` | one (role-tiered: self / branch / company) | any authenticated role |
| `GET /api/payroll/penalties?...` | list (same tiering) | any authenticated role |
| `GET /api/payroll/penalties/report` | report | `COMPANY_ADMIN`, `HR`, `MANAGER` |
| `GET /api/payroll/penalties/stats` | stats | `COMPANY_ADMIN`, `HR`, `MANAGER` |

**Role-tiering note**: legacy's `MANAGER` branch-scoping here
(`sql_manager_same_branch_scope()`) depends on a `branches` table that
does not exist yet in the new schema (lowest-priority group per the
sequencing doc). **This module cannot implement true manager
branch-scoping until `branches` exists.** Until then: either (a) scope
`MANAGER` to company-wide read access for penalties as an explicit,
temporary widening (documented as such, not silent), or (b) exclude
`MANAGER` from this module's endpoints entirely until branches lands.
This is a real product-visible behavior choice, not an implementation
detail — record whichever is chosen in `docs/bootstrap/decision-log.md`
before shipping this module, and note that F-25 (manager-scope tests)
already tracks branch-scoping as separate, later work.

**Legacy defect, fix-by-construction**: `report.php`'s CSV option
actually streams XLSX (`hr-legacy#23`). Do not reproduce this — if the
new endpoint supports a CSV export, it must be real CSV; if XLSX is
what's actually wanted, name the parameter/content-type accordingly.
This is purely a naming/content-type correctness fix with no product
ambiguity, unlike the housing-allowance question above.

**Business rules to encode**:

- `penalty_days` (schema: `NUMERIC(5,1)`) is stored in days, not
  currency — money conversion happens in
  `PayrollCalculationService` at calculation time, using the same
  fixed-30-day divisor question flagged below (shared with payroll
  batches).
- `applied_to_payroll` locks a penalty from update/delete once a batch
  finalize has applied it — this lock is enforced at the service layer
  (schema comment: "application-level rule, not a schema constraint").

**Tests**:

- Tenant isolation (create/update/delete/list/one all company-scoped).
- Role-tiered visibility: employee sees only their own; whatever
  `MANAGER` behavior is chosen above is explicitly tested, not left
  implicit.
- `applied_to_payroll=true` blocks update/delete.
- CSV export is actually CSV.

---

## Module: `PayrollCalculationService` (Shared, Not An Endpoint)

This is the direct fix for `hr-legacy#12`/`#13` — three independent,
divergent payroll-math implementations in legacy
(`payroll_calculation.php`'s shared SQL fragment,
`payslips/update.php`'s independent inline reimplementation, and
`payslips/create.php`'s materially simpler, buggy formula that drops
base pay entirely for daily-wage employees). **This service is the
single place payroll math is computed, called by every entry point
below that needs it — no controller or other service may reimplement
any part of this math independently.** That rule itself is the
structural fix; nothing about the math changes what's already decided.

**Inputs**: an `Employee`, their currently-effective `SalaryContract`,
attendance-derived days-present/absent/leave and overtime hours (from
the not-yet-built attendance module — until that exists, these are
plain method parameters, not a live query; do not block this service
on attendance being built, per the sequencing doc's stated dependency
direction, but do not fabricate a data source for it either), the
employee's `PENDING`-deductible `Advance`s for the period, and their
un-applied `Penalty` days for the period.

**Output**: a computed `Payslip` (all columns in
`V11__create_payslips.sql`) — day-rate, absence cost, overtime pay,
gross salary, total entitlements, total deductions, net salary.

**Open product decisions, blocking correct implementation of this
service specifically — do not guess either direction**:

1. **Fixed 30-day divisor vs. real calendar days**
   (`docs/legacy/business-rule-extraction.md`, "Payroll day-rate uses a
   fixed 30-day month"). Legacy always divides by 30 regardless of the
   real month length. This is explicitly flagged as needing "an
   explicit human decision (preserve the fixed-30 convention, or
   deliberately change it and communicate that as a real payroll
   policy change)" — silently picking either option changes every
   employee's effective daily rate.
2. **Per-company fiscal period** (`month_start_day`/`month_end_day`,
   currently stored in legacy's generic EAV settings system, which
   does not exist in the new schema yet — that's part of the
   not-yet-built `company_settings` module). Until that exists, this
   service needs an explicit decision on where fiscal-period
   configuration lives for this slice: a temporary column on
   `companies`, a hardcoded calendar-month assumption documented as a
   known simplification, or blocking payroll-batch calculation on
   `company_settings` being built first. Silently assuming
   calendar-month alignment would misattribute absence days at period
   boundaries for any company that needs a non-calendar cycle, exactly
   as the business-rule extraction warns.
3. **The `payslips.allowances` naming ambiguity carries forward into
   this schema.** `V11__create_payslips.sql` has both a generic
   `allowances` column and separate `food_allowance`/`risk_allowance`/
   `transport_allowance`/`incentives` columns — mirroring legacy's
   actual (undocumented) meaning where `allowances` specifically means
   *housing*. Nothing in the new schema's column name signals this.
   **Recommendation, not a decision made here**: map this column's
   Java field to `housingAllowance` in the entity/DTO layer even
   though the column stays named `allowances`, and document the
   mismatch inline — the same kind of naming trap that caused
   confusion in the legacy code should not be reproduced silently in
   the new one.
4. **Daily-wage employees must never lose base pay in any code path.**
   This is not an open question — it's the one item in this whole plan
   that is a confirmed, reproducible defect (`payslips/create.php`
   silently drops base pay for `DAILY`-mode employees, `hr-legacy#12`)
   with no legitimate reason to preserve it. `PayrollCalculationService`
   must correctly convert `daily_wage × 30`-equivalent (subject to
   decision 1 above) to a monthly-equivalent basic salary for every
   entry point, with no bypass path. Because this plan centralizes all
   payroll math into one service, this defect class is closed by
   construction as long as nothing calls anything except this service.

**Tests**:

- Monthly-mode and daily-mode employees both produce correct
  `basic_salary` on the computed payslip — this is the direct
  regression test for `hr-legacy#12`.
- Absence/overtime/deduction math matches whatever the fixed-divisor
  decision (open item 1) resolves to, with an explicit test case at a
  month-length boundary (a 28-day vs. 31-day month) proving the chosen
  behavior, not just the happy path.
- A single golden-value test computing a full payslip from known
  inputs (contract + advances + penalties + attendance figures) against
  a hand-verified expected output — this becomes the first concrete
  input to PMR-10's differential-testing harness (`hr-platform#16`) once
  that exists, so keep the test inputs/outputs in a form that's easy to
  reuse there rather than one-off inline literals.

---

## Module: `PayrollBatch` And `Payslip`

**Endpoints** (`docs/api/existing-endpoint-inventory.md` "Payroll
Batches" and "Payslips" — all batch endpoints are `COMPANY_ADMIN`/`HR`
only; payslip read endpoints additionally allow `MANAGER`/`EMPLOYEE`
with self-scoping):

| Endpoint | Method | Role | Note |
|---|---|---|---|
| `POST /api/payroll/batches` | create | `COMPANY_ADMIN`, `HR` | DB-enforced `(company_id, month, year)` uniqueness — see fix below |
| `POST /api/payroll/batches/{id}/calculate` | calculate | `COMPANY_ADMIN`, `HR` | Full delete-and-reinsert into `payslips`, wrapped in a transaction — see fix below |
| `PUT /api/payroll/batches/{id}/finalize` | finalize | `COMPANY_ADMIN`, `HR` | Transactional; applies penalty/advance side effects |
| `PUT /api/payroll/batches/{id}/reopen` | reopen | `COMPANY_ADMIN`, `HR` | Transactional; reverses finalize side effects |
| `DELETE /api/payroll/batches/{id}` | delete | `COMPANY_ADMIN`, `HR` | Draft-only |
| `PUT /api/payroll/batches/{id}` | update | `COMPANY_ADMIN`, `HR` | Draft-only |
| `GET /api/payroll/batches` | list | `COMPANY_ADMIN`, `HR` | |
| `GET /api/payroll/batches/{id}` | one | `COMPANY_ADMIN`, `HR` | |
| `GET /api/payroll/batches/{id}/stats` | stats | `COMPANY_ADMIN`, `HR` | |
| `GET /api/payroll/batches/fiscal-period` | fiscal-period preview | `COMPANY_ADMIN`, `HR` | Depends on open decision 2 above |
| `POST /api/payroll/payslips` | create (manual, into an existing draft batch) | `COMPANY_ADMIN`, `HR` | Must go through `PayrollCalculationService` — see fix below |
| `PUT /api/payroll/payslips/{id}` | update (draft-batch-only manual override) | `COMPANY_ADMIN`, `HR` | Must go through `PayrollCalculationService` |
| `DELETE /api/payroll/payslips/{id}` | delete | `COMPANY_ADMIN`, `HR` | Draft-batch-only |
| `GET /api/payroll/payslips` | list | `COMPANY_ADMIN`, `HR`, `MANAGER`, `EMPLOYEE` (self-scoped) | |
| `GET /api/payroll/payslips/{id}` | one | same, 403 (not silent scoping) if not the caller's own | |
| `GET /api/payroll/payslips/export` | export | same, self-scoped | Real format, not the penalties-style CSV/XLSX mislabel |

**Fixes, already partially done at the schema layer**:

1. **Batch uniqueness race** (`hr-legacy#21`) — already closed at the
   schema level: `V10__create_payroll_batches.sql` has a real
   `UNIQUE (company_id, month, year)` constraint, unlike legacy's
   app-level-only `SELECT COUNT(*)` check. The service layer just
   needs to translate the resulting constraint-violation exception into
   a real `409`-style API error — no app-level pre-check needed or
   wanted (a pre-check would just reintroduce the race window the
   constraint exists to close).
2. **Calculate is destructive and must be transactional**
   (`hr-legacy#22` — legacy's `calculate.php` is the one batch-mutating
   endpoint with no transaction, unlike `finalize`/`reopen`). The new
   `calculate` endpoint must wrap the full delete-and-reinsert in a
   single `@Transactional` boundary — this is a mandatory fix, not a
   preserve/decide item; there's no legitimate reason for a partial,
   half-recalculated batch to ever be visible.
3. **Employee deletion must not silently erase payroll history.** This
   module doesn't own the `employees/delete` endpoint (out of scope,
   part of the employee-profile module), but `Payslip`/`Advance`/
   `Penalty`/`SalaryContract`'s FK to `employees` should stay
   `RESTRICT` (Postgres default, already the case per the schema
   comments) and this plan explicitly does **not** authorize any
   cascade-delete helper resembling legacy's `employee_cascade_delete_related()`
   (`hr-legacy#20`) inside this module's scope. If soft-delete-only
   deletion for employees is the eventual decision, that decision
   belongs to whoever implements the employee-profile module, not this
   one — flagging it here only so payroll-module code never grows a
   workaround for a RESTRICT constraint it hits.

**Fix, not yet reflected anywhere at the schema layer — must be
enforced in code**: legacy's `payslips/create.php` bypasses all shared
payroll math (see `PayrollCalculationService` above). The new manual
"add one payslip to a batch" endpoint must call
`PayrollCalculationService` exactly like `calculate` does for every
other employee in the batch — the only difference between "batch
calculate" and "manually add one payslip" should be *how many*
employees get a payslip computed, never *how* the math is done.

**Business rules to encode**:

- `payroll_batches.status`: `DRAFT` / `FINALIZED` only. "Reopened" is
  `FINALIZED` flipped back to `DRAFT`, not a third state.
- `finalize` side effects (transactional, both directions): mark
  matching `Penalty` rows `appliedToPayroll = true`; apply `Advance`
  deductions to `Advance.remaining`. `reopen` reverses both.
- `finalize` blocked if already finalized; `calculate`/`update`/`delete`
  blocked once finalized.
- `payslips` has a real `UNIQUE (batch_id, employee_id)` constraint
  (already in the schema) — legacy's `create.php` app-level
  `COUNT(*)` pre-check becomes unnecessary for the same reason as
  batch uniqueness above; let the constraint do the work, translate
  the violation into a clean API error.

**Tests**:

- `calculate` is atomic: inject a mid-calculation failure (e.g. a
  constraint violation on one employee's payslip) and prove the whole
  batch's payslip set rolls back, not partially commits — direct
  regression test for `hr-legacy#22`.
- Two concurrent `create` requests for the same `(company, month,
  year)` — prove exactly one succeeds and the other gets a clean
  conflict response, not two draft batches — direct regression test
  for `hr-legacy#21`.
- `finalize`/`reopen` correctly apply/reverse penalty and advance side
  effects, including a test that `reopen` after `finalize` restores
  the exact pre-finalize state.
- Manually-created payslip (`POST /api/payroll/payslips`) for a
  daily-wage employee has correct, non-zero base pay — direct
  regression test for `hr-legacy#12`, exercised through this specific
  endpoint since that's exactly where the legacy bug lived.
- Payslip read endpoints: employee sees only their own (403, not
  silent filtering, matching `one.php`'s legacy behavior which was the
  one endpoint in this whole group that already did this correctly).

---

## Summary: Decisions Required Before This Group Is Implementation-Complete

Collected from above, so they're visible in one place rather than
buried per-module. None of these are decided by this document.

1. Is `salary_contracts.housing_allowance` a normal settable contract
   field, or intentionally payslip-only? (`hr-legacy#14`)
2. Preserve the fixed-30-day payroll divisor, or move to real calendar
   days? (business-rule-extraction, "Payroll day-rate")
3. Where does per-company fiscal-period configuration live until
   `company_settings` is built?
4. What does `MANAGER` role access to `penalties` look like before
   `branches` exists — company-wide (documented widening) or excluded?
5. (Recommendation, not a decision needed) Map `payslips.allowances` to
   a clearly-named `housingAllowance` field at the code layer.

## Exit Criteria For This Plan

Per `hr-platform#15`'s own stated exit criteria ("a reviewed, approved
detailed execution plan exists for at least the first migration wave"):
this document, once reviewed, closes PMR-09 for the payroll group
specifically. PMR-09 remains open at the overall-gap level until the
same level of detail exists for attendance and the remaining modules,
per the sequencing doc's later waves — this plan does not claim to
close those.

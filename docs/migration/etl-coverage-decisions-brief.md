# ETL Coverage Decisions Brief (2026-08-12)

## Status: Answered 2026-08-12

The repository owner answered Q1–Q8 in full on 2026-08-12. Every answer
is recorded verbatim below its question, and the set is registered as
**D-032** in [`decision-log.md`](../bootstrap/decision-log.md).

The headline is that **the great majority of these gaps resolved to
"migrate", not "drop"** — only six items are deliberate drops. That
inverts the shape of the remaining work: this was a decision backlog, and
it is now an engineering backlog. See
[What The Answers Imply](#what-the-answers-imply) for the derived work and
the four questions the answers themselves opened.

Deciding to migrate does not close a gap in
`scripts/etl/coverage_audit.py`. A gap closes when the column actually
reaches the target. Most entries therefore stay in the ledger, with their
character changed from *undecided* to *owed an implementation* — see
[Ledger Consequence](#ledger-consequence).

## Purpose

`scripts/etl/coverage_audit.py` reports 47 gaps between the legacy schema
and what the ETL migrates. Every one is registered, so none is a surprise
— but 47 registered gaps are still 47 unanswered questions, and the
answers are not engineering's to invent.

This converts them into questions that can be answered without reading
the ETL, in the shape
[`pending-decisions-brief.md`](./pending-decisions-brief.md) used for the
readiness-gate blockers.

**The questions in this document are planning output** (hr-platform
`CLAUDE.md`). The answers are the repository owner's, recorded as given.

Everything mechanical has already been closed: the `created_at` repair
across 15 entities, `branches.expires_at` and `qr_code`, the five
`advances.deduction_*` scheduling columns, and the four history and
scheduling tables. What remains genuinely needs a human.

## How To Use This

Each question is answerable **yes/no or with one sentence**. Answering
one lets engineering either write the migration or record a deliberate
drop in `coverage_audit.py`'s `ACCEPTED` registry — where it stops being
a gap and starts being a decision.

Priority is about cutover risk, not effort.

---

## P0 — Answer before cutover planning continues

### Q1. Do companies still log in after cutover?

**The gap.** Legacy `companies` carries `password_hash`, `email`,
`otp_verified` and `profile_completed`, and legacy companies log in with
phone + password. The ETL extracts 5 of 21 company columns; none of those
four is among them. The `identities` transform derives **only** from
`stg_employees` (`load_postgres.py`, identity block), so nothing carries
company-level credentials across at all.

**Why it is P0.** If company login is meant to survive, this is a
cutover-blocking data loss with no recovery after the legacy database is
retired. If it is not meant to survive, that is a decision nobody has
recorded, and every downstream reader of the schema is currently guessing.

**The question.** After cutover, does a *company* authenticate as itself,
or only individual users (company admins) through `identities`? ADR-0009's
role-based split suggests the latter, but no accepted decision says so.

**If the answer is "only individual users":** we record the drop and note
how existing company logins become admin identities at cutover — an
onboarding step that does not exist yet.

### A1 (2026-08-12)

> Companies do not authenticate as standalone company principals.
> Existing legacy company login accounts must be migrated into individual
> `COMPANY_ADMIN` identities linked to the corresponding company.
> Preserve compatible password hashes where possible; if the legacy hash
> cannot be supported safely, require a password reset or activation
> flow. Preserve the legacy phone, email, and any onboarding state needed
> to establish the admin identity.

**The hash conditional resolves to "no reset needed."** Legacy hashes are
bcrypt: `password_hash($x, PASSWORD_BCRYPT)` at 9 call sites across
`apis/` and `dashboard/`, verified with `password_verify`, with no MD5,
SHA1, or custom scheme anywhere in either tree. PHP's `PASSWORD_BCRYPT`
emits `$2y$`, and Spring Security's `BCryptPasswordEncoder` verifies
`$2y$` directly. So company password hashes port across unchanged and
the reset/activation branch does not need to be built. This should be
pinned by a test that verifies a known legacy-format hash before the
migration relies on it.

**This answer creates a uniqueness collision that must be resolved before
cutover.** Both `identities.phone` and `companies.phone` are `UNIQUE` in
the target schema, and legacy companies log in by phone. If any company's
login phone equals one of its own employees' phones — an owner who is
also on staff is the obvious case — then minting a `COMPANY_ADMIN`
identity for that company collides with the employee's existing identity
row and the load aborts. The count is not knowable from the schema alone;
it needs the data dump. Recorded as **OQ-1** below.

### Q2. Which of the 16 unmigrated tables must survive?

None has a target table, so each needs a target schema *and* a migration,
or an explicit drop. Grouped by the kind of judgement involved:

| Table | Rows | The question |
|---|---:|---|
| `notifications` | 4,014 | Is in-app notification history worth keeping, or do tenants start clean? |
| `complaints` | ~52 | Support-inbox history — is there a retention or compliance obligation? |
| `assets` | ~25 | Company assets issued to employees. Is this tracked in the new platform at all? |
| `administrative_decisions` | ~22 | Company announcements. Same question. |
| `workforce_planning` | ~15 | Headcount targets. Same question. |
| `employee_docs` | — | Metadata only; the files live under `uploads/` outside the DB. Do documents survive, and if so what happens to the files? |
| `push_tokens` | — | F-08 confirms push never worked end-to-end. Migrating dead tokens seems pointless — confirm. |
| `otp_codes` | ~588 | Short-lived codes. Presumably not worth migrating — confirm. |
| `banners`, `app_content`, `faq_categories`, `faq_items` | — | Marketing/CMS content. Does the new platform own this, or does it move elsewhere? |
| `phone_countries` | — | Reference data. Does the new platform seed its own list, or inherit legacy's? |
| `company_activities`, `company_sizes`, `company_titles` | 2–4 each | Lookup tables referenced by three `companies` FK columns. Decide together with Q3. |

**A "no" is a perfectly good answer** — it just has to be written down.

### A2 (2026-08-12)

**Migrate, with full history:** `notifications`, `complaints`, `assets`,
`administrative_decisions`, `workforce_planning`, `banners`,
`app_content`, `faq_categories`, `faq_items`.

**Migrate including the underlying files:** `employee_docs` — metadata
*and* the actual files from `uploads/`, into storage the new platform
controls.

**Drop:** `push_tokens` (dead per F-08), `otp_codes` (expired/historical).

**Reseed, do not carry legacy ids:** `phone_countries` — seed a canonical
reference dataset.

**Map by value, not by legacy numeric id:** `company_activities`,
`company_sizes`, `company_titles`. Preserve the business values and map
them to seeded/normalized lookups. `company_titles`' exact semantics to
be confirmed during implementation.

> For tables with no target schema, create the required target
> schema/module and migration. Preserve tenant/company ownership,
> relationships, timestamps, and business state. These records must not
> be treated as archive-only data.

That last sentence is the expensive one: it rules out a generic
archive/JSON-blob landing table and requires each of these to become a
real, tenant-scoped module with RLS, exactly like the shipped ones.

---

## P1 — Answer before the companies module is considered complete

### Q3. What happens to the other 12 `companies` columns?

Beyond Q1's four, these have no target column:

- `company_code` — do tenants keep their existing identifier, or get renumbered?
- `first_name`, `last_name` — the company *contact person*, distinct from the company. Does that person become an employee/identity, or is the contact concept dropped?
- `commercial_reg_url` — uploaded registration document. Compliance record?
- `logo_url` — tenant branding. Survives, or re-uploaded?
- `rejection_reason` — company-approval workflow state. Is rejection history retained?
- `main_branch_address` — free text on the tenant row, separate from `branches.address`. Fold into the main branch, or drop?
- `country_code` — stored separately, or folded into the phone value?
- `company_activity_id`, `company_title_id`, `company_size_id` — FKs into the three lookup tables in Q2; decide together.
- `updated_at` — see Q5.

### A3 (2026-08-12)

- `company_code` — **preserve exactly**, do not renumber. It is the
  legacy/external tenant identifier.
- `first_name`, `last_name` — preserve as the legacy company contact and
  use when creating the initial `COMPANY_ADMIN` identity. **Do not**
  auto-create an employee record for that person.
- `commercial_reg_url` — preserve and migrate the document, file included.
- `logo_url` — preserve and migrate the asset. No re-upload.
- `rejection_reason` — preserve with the associated approval/rejection
  state or history.
- `main_branch_address` — fold into the main branch; use as a fallback
  when `branches.address` is missing, then stop maintaining a duplicate
  company-level address.
- `country_code` — normalize phones to E.164; the legacy column may then
  be dropped if the target model does not need it separately.
- `company_activity_id`, `company_title_id`, `company_size_id` — map by
  semantic value/code, not legacy numeric id.
- `updated_at` — per Q5.

Target `companies` currently has five columns (`id`, `name`, `phone`,
`active`, `created_at`). Every preserve above is a schema addition.

E.164 normalization can fail on malformed legacy input, and the answer
does not say what happens then. Recorded as **OQ-2**.

### Q4. Do the two remaining `advances` columns matter?

The five scheduling columns with target columns are now migrated. Two are
left:

- **`deduction_type`** (`single_month` / `multiple_months`) — looks
  redundant with `deduction_mode`, but redundancy has to be confirmed by
  someone who knows the legacy semantics, not assumed by whoever reads
  the enum names.
- **`deduction_installments_json`** — real data, written by
  `advances/create.php` and `update.php`. No target column. If installment
  schedules are ever honoured, this is the only record of what was agreed.

Note `PayrollBatchService`'s Javadoc already records that scheduled
deduction is a v1 heuristic and *"V12's deduction_\* scheduling columns
remain an open product decision."* This is that decision, narrowed to two
columns.

### A4 (2026-08-12)

- **`deduction_type`** — preserve its business semantics. If validation
  against real legacy data proves it is 100% equivalent to
  `deduction_mode`, do **not** add a duplicate target column; add a
  **migration assertion enforcing that equivalence** instead. If any
  differing values exist, migrate/map them explicitly.
- **`deduction_installments_json`** — do not drop. It is the agreed
  installment schedule. Normalize it into target records
  (`advance_installment` or equivalent).

The `deduction_type` answer is conditional on evidence that does not
exist yet: only a schema dump is available, with no rows. The equivalence
cannot be tested until there is a data dump, so the assertion-vs-column
branch stays open until then — this is a dependency, not an ambiguity.

`PayrollBatchService`'s Javadoc should be updated once the schedule is
normalized; it currently records the decision as open.

---

## P2 — Answer opportunistically

### Q5. Does any entity need `updated_at`?

`companies`, `exception_types` and `company_settings` carry an
`updated_at` in legacy; no target table has one. Either the new platform
tracks modification time or it does not — a per-table answer would be
worse than one rule applied consistently.

### A5 (2026-08-12)

> Yes. Add and preserve `updated_at` for **all mutable business
> entities**, not only the three tables that had it in legacy. Use one
> consistent auditing model across the new platform.

This is deliberately wider than the gap that prompted it. Only 3 of 40
shipped Flyway migrations mention `updated_at` today, so this is a
cross-cutting schema and auditing change across roughly 35 tables, plus
a decision on the mechanism (JPA `@LastModifiedDate` versus a database
trigger). The two disagree when a row is changed by the ETL or by SQL
rather than through the application — which is exactly what migration
does. Recorded as **OQ-3**.

### Q6. Can an exception type be deactivated?

`exception_types.is_active` exists in legacy; the target has no such
column. A migrated inactive type would silently become active and
reappear in pickers. Either add the column or confirm deactivation is not
a feature.

### A6 (2026-08-12)

> Migrate it. The target model must support `is_active` or an equivalent
> status. Inactive legacy exception types must not silently become active
> after migration or reappear in selectors.

The "or reappear in selectors" clause makes this more than a column: the
read paths that list exception types have to filter on it, or the column
lands and changes nothing observable.

### Q7. Four employee columns with no target

- `token_version` — legacy JWT-invalidation counter. The rewrite revokes
  through refresh-token families (F-26), so this is probably obsolete;
  confirm rather than assume.
- `address` — employee home address. There may be a data-minimisation
  argument for *not* carrying it. That is still a decision.
- `photo_url` — survives, or re-uploaded?
- `contract_duration_months` — fixed-term contract length. Confirm no
  payroll rule depends on it before dropping.

### A7 (2026-08-12)

- `token_version` — **drop**. Legacy JWT-invalidation detail, replaced by
  the refresh-token/session revocation model (F-26).
- `address` — preserve and migrate as employee profile data. **Treat as
  PII** with appropriate authorization and exposure controls.
- `photo_url` — preserve and migrate both the reference and the file.
- `contract_duration_months` — **do not drop** until fixed-term contract
  semantics are represented safely in the target. Prefer normalizing into
  explicit contract dates or equivalent terms.

`address` being marked PII is an authorization requirement, not just a
column: it needs a rule about which roles may read it, and that rule has
to hold on every endpoint that returns an employee. Recorded as **OQ-4**.

### Q8. `configs.id`

`configs` is read only for the `is_daylight_saving` probe and has no
target table. Confirm no other config key is needed post-cutover.

### A8 (2026-08-12)

> Do not migrate `configs.id` or reproduce the generic legacy `configs`
> table as-is. Migrate `is_daylight_saving` and any other configuration
> keys proven to be required by current business behavior into typed
> platform configuration. Drop unused legacy configuration keys.

"Proven to be required" is the operative phrase: the key set has to be
established by reading legacy call sites, not assumed from the table
contents. Until that read happens the migrated key set is exactly one
(`is_daylight_saving`).

---

## What The Answers Imply

Six deliberate drops: `push_tokens`, `otp_codes`, `token_version`,
`configs.id` plus unused config keys, legacy `phone_countries` ids, and
`country_code` (conditional on E.164 normalization succeeding). Those are
the only entries that can move to `ACCEPTED` on the strength of the
answers alone.

Everything else resolved to **migrate**, and most of it has no target
schema. The derived work, grouped by what it actually requires:

| Work | Scope | Notes |
|---|---|---|
| New tenant-scoped modules | 9 tables from A2 | Each needs Flyway + RLS + load block + fixtures, not an archive table |
| Lookup normalization | 3 tables from A2, mapped by value | Plus a seeded `phone_countries` reference set |
| `companies` schema expansion | 6 columns from A3 | Target has 5 columns today |
| `COMPANY_ADMIN` identity minting | A1 | Blocked on OQ-1 |
| **File migration** | `employee_docs`, `commercial_reg_url`, `logo_url`, `photo_url` | **No mechanism exists** — see below |
| `updated_at` rollout | ~35 tables | Plus the mechanism decision, OQ-3 |
| `advance_installment` normalization | A4 | Parsing legacy JSON into rows |
| `exception_types.is_active` | A6 | Column *and* the read-path filters |
| Contract-term normalization | A7 | Needs product semantics for fixed-term contracts |

**The file migration is a genuinely new work stream, not an ETL change.**
Every artifact in `scripts/etl/` emits SQL. Nothing in it moves bytes.
Four separate answers now require files to travel from legacy `uploads/`
into platform-controlled storage, which needs a storage target, a
transfer mechanism, integrity verification, and a URL-rewriting step so
the migrated rows point at the new locations. None of that exists today,
and it is not a variation on work that does.

## Three Columns This Brief Never Asked About

This document claimed to convert all 47 gaps into questions. It converted
44. Q7 was headed *"Four employee columns with no target"* and there were
seven — `is_mobile_attendance_enabled`, `can_check_in_any_branch` and
`join_request_status` were never put to the owner, so D-032 does not
cover them and they remain `PENDING` in the ledger.

Two of the three are not clerical:

- **`employees.is_mobile_attendance_enabled`** — per-employee opt-in for
  mobile attendance. Dropping it silently changes whether an employee can
  check in from their phone at all.
- **`employees.can_check_in_any_branch`** — per-employee geofence
  exemption. Dropping it silently widens or narrows where someone may
  check in.

Both change what an employee can *do*, which is the same class of risk as
the `created_at` defect: a value that disappears without anything failing.

- **`employees.join_request_status`** — onboarding gate with no target
  column; decide alongside the join-request flow, which the rewrite has
  not built.

## Open Questions Created By The Answers

These are not gaps in the answers — they are consequences that surfaced
while recording them. All four were answered on 2026-08-12 and are
recorded as **D-033**.

**OQ-1 — Phone collision between company admins and employees.**
`identities.phone` and `companies.phone` are both `UNIQUE`. A1 mints a
`COMPANY_ADMIN` identity from the company's login phone; if that phone
already belongs to an employee of that company, the load aborts. Needs a
rule: does one identity carry both roles, does the admin get a different
identifier, or is this rare enough to handle as manual cutover
remediation? The population count needs the data dump.

**OQ-2 — E.164 normalization failures.** A3 drops `country_code` *after*
successful normalization. What happens to a legacy phone that cannot be
normalized: abort the load, migrate it unnormalized and keep
`country_code` for those rows, or quarantine? Since `phone` is the login
identifier, silently mangling one locks a tenant out.

**OQ-3 — `updated_at` mechanism.** JPA auditing versus a database
trigger. They disagree precisely when rows change outside the
application — which is what the ETL does. If migrated rows must carry
legacy modification times rather than the load timestamp, the trigger
must be suppressed during load, the same way `created_at` had to be
written explicitly rather than left to `DEFAULT now()`.

**OQ-4 — `address` as PII.** A7 requires authorization and exposure
controls. Which roles may read it, and does it appear in list endpoints
or only on a single-employee read? This is an authorization-catalog
entry (F-14–F-25), not an ETL concern, but the migration is what makes
the data present.

### Answers (2026-08-12)

**OQ-1 — reuse, never duplicate.** If a legacy company login phone
matches an existing employee identity for the same tenant/person, reuse
that identity and grant it the `COMPANY_ADMIN` membership/role. Never
create a duplicate identity for the same person. If the phone matches an
identity belonging to a different person or tenant context that cannot be
safely reconciled, **flag the record for explicit migration remediation
rather than guessing**.

That last clause requires a remediation channel the ETL does not have
today: a way to abort a single record and report it, instead of aborting
the load. The existing guards are all load-level aborts.

**OQ-2 — preserve, mark invalid, block activation.** If a login phone
cannot be normalized to valid E.164, preserve the original legacy value,
mark the record **migration-invalid**, and block automatic
activation/login until corrected. Do not silently rewrite, discard, or
fabricate a phone number.

This needs a migration-invalid state on the target row, which does not
exist yet — and it means `companies.country_code` cannot be dropped
unconditionally, since unnormalized rows still need it.

**OQ-3 — database-enforced.** `updated_at` must be database-enforced so
writes from the ETL, administrative scripts, and the application behave
identically. JPA auditing may remain as an application convenience but
must not be the sole mechanism or the source of truth.

Note the interaction with migration: a database trigger fires on the
ETL's own INSERTs, so migrated rows would carry the load timestamp unless
the trigger is suppressed during load — the same trap `created_at` fell
into with `DEFAULT now()`, where ~50,000 rows would have claimed the
cutover instant.

**OQ-4 — restricted read, excluded from lists.** Employee home address is
PII. Readable by the employee themself, `COMPANY_ADMIN`, authorized HR
roles, and `SUPER_ADMIN`. Managers and unrelated employees must not
receive it by default. Enforced at the service/API level and excluded
from general employee list responses unless explicitly required.

## Ledger Consequence

`coverage_audit.py` has two registries: `ACCEPTED` (deliberately dropped,
with a reason) and `PENDING` (owed a decision, with a note). "Decided to
migrate, not yet migrated" fits neither. The six drops can move to
`ACCEPTED` immediately; the rest are no longer *undecided*, but they are
still gaps, and marking them `ACCEPTED` would be false — the value does
not reach the target.

The registry needs a third state — something like `SCHEDULED`, carrying
the decision reference and the work it waits on — so `--check` keeps
failing until the migration exists while no longer describing settled
questions as open. Until that lands, the `PENDING` notes are stale in
wording, not in effect: they say "owed a decision" when what is owed is
an implementation.

## What Happens To An Answer

Each answer lands in one of two places:

- **Migrate it** → the column or table gets a target schema and a load
  block, with a fixture assertion, and the ledger entry disappears.
- **Drop it** → the entry moves from `PENDING` to `ACCEPTED` in
  `coverage_audit.py` with the reason recorded inline, and `--check`
  keeps it honest: if the gap later closes, the stale entry fails the
  build.

Either way the gap stops being silent, which is the whole point.

## Evidence

Generated from `scripts/etl/coverage_audit.py --report` against
`hr-legacy/mysql_workin.schema.sql`. Row counts from
[`table-volume-analysis.md`](./table-volume-analysis.md) (measured
2026-08-04, not the AUTO_INCREMENT approximations in
[`database-schema-inventory.md`](./database-schema-inventory.md)).

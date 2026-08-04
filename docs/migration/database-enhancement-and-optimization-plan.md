# Database Enhancement And Optimization Plan

## Status

Builds on `docs/migration/database-schema-inventory.md`'s existing risk
list and this Discovery pass's business-rule/security findings. This is a
set of recommendations for the PostgreSQL target schema, not a decision —
each item below needs the same ADR/decision-log treatment as any other
architecture choice before implementation.

## 1. Schema Fixes Carried From The Migration-Risk List

`database-schema-inventory.md` already identified these; this section
turns them into concrete recommendations rather than open questions:

- **Collation.** Every table uses `utf8mb4_unicode_ci` except `configs`
  (`utf8mb4_general_ci`) — very likely an accident. **Recommendation:**
  normalize to one collation platform-wide in the Postgres schema (a
  single `LC_COLLATE`/`ICU` choice made once), don't replicate the
  inconsistency, and don't spend time confirming intent with the original
  team — the cost of normalizing is near-zero and the cost of preserving
  an accident is a permanent footgun.
- **`ON DELETE` behavior.** Most FKs are `CASCADE`; three
  (`employees.branch_id`, `payslips.employee_id`, `requests.request_type_id`)
  default to `RESTRICT` by omission. Discovery found this is **not**
  intentional protection in at least one case — `payslips.employee_id`'s
  RESTRICT is actively worked around by application code
  (`employee_cascade_delete_related()`, see
  `docs/legacy/business-rule-extraction.md` and GitHub issue #20).
  **Recommendation:** make `ON DELETE` behavior a single, explicit,
  schema-wide decision per relationship type (e.g. "financial-history
  tables never cascade-delete via FK; deletion of a referenced employee
  requires an explicit archival step") rather than inheriting whichever
  behavior 39 of 42 tables happened to get by accident.
- **`notifications.reference_type`/`reference_id`** is a polymorphic
  reference with no FK at all — enforced only in application code today.
  **Recommendation:** either normalize into per-type reference columns (a
  real FK per notification-source table, nullable, with a check that
  exactly one is set) or, if the polymorphic shape is kept, use Postgres's
  `jsonb` with an application-level integrity job rather than pretending
  an unconstrained integer column is safe.
- **`advances.deduction_installments_json`** stores a full installment
  schedule as parsed JSON text. **Recommendation:** a real
  `advance_installments` table (one row per installment,
  FK to `advances.id`) — this is exactly the kind of data Postgres
  relational modeling handles better than a JSON blob, and it directly
  supports queries the current system can only do by loading and parsing
  every row in PHP.
- **Unsigned integers / `year(4)`.** Postgres has no native unsigned int
  type and no `year` type. **Recommendation:** plain `integer`/`bigint`
  with a `CHECK (col >= 0)` constraint where negative values are
  meaningless (essentially every FK and count column in this schema), and
  `smallint` for year columns — decide once, apply schema-wide via
  code-generation/migration tooling, not table-by-table by hand.
- **`ENUM` columns.** Widely used (`employees.role`, `companies.status`,
  `attendance.method`, etc.). **Recommendation:** given how often this
  system's own status/type enums have grown over its lifetime (the
  module inventory already found leftover `sections`/`employee_custody`
  API modules suggesting past renames), prefer `varchar` + `CHECK`
  constraint over Postgres native `CREATE TYPE ... ENUM` — adding a value
  to a `CHECK` constraint is a much lighter migration than
  `ALTER TYPE ... ADD VALUE`, which also can't run inside a transaction
  in older Postgres versions.
- **`CHECK (json_valid(...))` on `phone_countries.phone_prefixes`.**
  **Recommendation:** migrate directly to `jsonb` — this is a place to
  improve rather than replicate the MySQL text+CHECK pattern, per the
  existing schema inventory's own note.

## 2. The EAV Settings System

`company_settings` / `company_setting_values` / `setting_definitions` /
`setting_allowed_values` is a hand-rolled entity-attribute-value pattern.
Discovery found concrete pain from this shape, not just a theoretical
EAV-is-hard-to-query concern:

- The `MONTHLY_LEAVE_ACCRUAL` setting key is read and applied as a
  **yearly total** (`leave_balances/generate.php` uses it directly as
  `TOTAL_DAYS` for the year, no ×12), despite its name implying a monthly
  rate — a real naming/semantics trap living inside the EAV value, not
  something a schema-level fix alone would catch.
- The same codebase already contains a **non-EAV alternative** for a
  conceptually similar problem: `hr_permissions`'s 18 hardcoded boolean
  columns. That table is easy to query, easy to enforce against (when it
  *is* enforced — see GitHub issue #8), and easy to reason about, at the
  cost of a migration every time a new permission is added. The EAV
  settings system is the opposite trade-off: no migration needed to add a
  setting, but every read requires joining across 4 tables and every
  value needs runtime interpretation.

**Recommendation:** for the Postgres target, prefer a `jsonb` column
(`companies.settings jsonb`) over replicating the 4-table EAV shape for
free-form, rarely-queried-by-value settings (most of what's in this
system — display preferences, feature toggles). Reserve a real normalized
table (like `hr_permissions`) only for settings that are: (a) queried
inside `WHERE` clauses regularly, or (b) need per-value referential
integrity (e.g. `setting_allowed_values`-style constrained choices). This
is a genuine architectural decision, not a mechanical translation — flag
for the Solution Architect role, not something to decide by default here.

## 3. Performance

- **`attendance` is the volume outlier.** ~38,960 rows vs. ~7,098
  employees (~5.5x) per the schema inventory, and it's the only table with
  genuinely unbounded linear growth (every check-in/out, forever, for
  every employee, at every company). **Recommendation:** this table needs
  its own indexing and partitioning strategy in the Postgres target — at
  minimum a composite index on `(employee_id, check_in)` (the access
  pattern behind the 2-hour-gap rule, monthly summaries, and payroll's
  attendance lookups) and a `company_id`-reachable index path (via join or
  denormalization) for the dashboard's company-wide list/report queries.
  Consider range partitioning by `check_in` date once volume projections
  are available (needs `table-volume-analysis.md`'s data-dependent
  numbers — see `migration-strategy-and-sequencing.md`).
- **`payroll_calculate_batch()`'s delete-all-then-reinsert pattern**
  (GitHub issue #22) is a correctness issue (non-transactional) but also
  a performance one at scale: recalculating a batch for a company with
  hundreds of employees means hundreds of deletes followed by hundreds of
  inserts, inside no transaction, on every recalculation. **Recommendation:**
  the Java rewrite's equivalent operation should use a real transaction
  (fixing #22) and consider an upsert pattern (`INSERT ... ON CONFLICT ...
  DO UPDATE`, matching the legacy system's own `ON DUPLICATE KEY UPDATE`
  usage elsewhere in `payroll_calculation.php`) rather than delete-then-insert,
  which avoids the transient absence of rows during recalculation as a
  side benefit.
- **Salary-contract lookups.** `payroll_compute_employee_payslip()` finds
  "the effective contract" as "most recent row with `effective_from <=`
  period end" — a pattern that needs an index on
  `(employee_id, effective_from DESC)` to avoid a full scan per employee
  per payroll run; worth confirming this index exists (or gets added) in
  the new schema explicitly, since it's exactly the kind of index that's
  easy to forget because the query "works" without it at low data volume.

## 4. Security Findings With Real Schema/DB-Design Implications

Not every security finding is purely an application-code bug — some
imply the *database design itself* should make the mistake structurally
harder to repeat:

- **GitHub issue #8** (`hr_permissions` enforced on ~21 of 150+
  endpoints): if the Java rewrite keeps a permission-flag table, pair it
  with a query-layer or ORM-level guard (e.g. a repository base class that
  requires a permission check to compile/construct) rather than relying on
  every new endpoint remembering to call an enforcement function — this is
  a database-adjacent recommendation because the fix belongs at the
  data-access layer, not scattered across controllers.
- **GitHub issues #5/#6** (cross-tenant IDOR, 15 modules combined between
  API and dashboard): the schema itself could help here. Postgres Row
  Level Security (RLS), keyed on a `company_id` session variable set once
  per request, would make "forgot to add the tenant filter" structurally
  impossible rather than a per-query discipline problem — worth serious
  consideration given how many independent places this exact mistake was
  made in the legacy system (confirmed in at least 15 separate files
  across two codebases). This is a genuine architecture decision (RLS has
  real operational trade-offs — connection pooling interaction, harder
  to reason about for ORMs that don't set the session variable correctly)
  and should go through the Solution Architect / ADR process, not be
  adopted from this recommendation alone.

## Evidence

Built on `docs/migration/database-schema-inventory.md` (full schema read,
commit `83c326e40f68dd0d560595a6c4e465eb681f2ce8`),
`docs/legacy/business-rule-extraction.md`, and
`docs/security/threat-model.md` from this Discovery pass. GitHub issue
numbers confirmed against the live `workin-hr/hr-legacy` issue tracker.

# Character-Set And Collation Analysis

## Method

Measured directly, 2026-08-04, against the real schema loaded into a
throwaway Docker MySQL container (see
`docs/migration/data-quality-analysis.md` for the full method note):
`SELECT TABLE_NAME, TABLE_COLLATION FROM information_schema.TABLES
WHERE TABLE_SCHEMA='workin'` — checked all 41 tables, not a sample.
**Proven from data.**

## Table Or Column: `configs`

- **Current MySQL Charset/Collation**: `utf8mb4_general_ci` — the
  **only** table in the entire 41-table schema using this collation.
  Every other table (all 40 remaining) uses `utf8mb4_unicode_ci`. This
  confirms, with an exhaustive direct measurement, the finding already
  recorded in `docs/migration/database-enhancement-and-optimization-plan.md`
  (previously a spot-noted observation, now a verified 1-of-41 count).
- **Target PostgreSQL Encoding/Collation**: Not yet decided — Postgres
  has no direct `utf8mb4_unicode_ci` equivalent; the closest match is
  typically an ICU collation (e.g. `und-x-icu` or a specific locale) or
  the database-default `C`/`en_US.utf8` depending on sorting needs. This
  is a real decision the new schema design needs to make once, for the
  whole schema, not table-by-table — see Consequences below.
- **Known Sorting Or Comparison Risk**: `_general_ci` and `_unicode_ci`
  MySQL collations have measurably different sort/comparison behavior
  for certain multi-byte and accented characters. This system's user
  base is confirmed Arabic-speaking (the dashboard's own developer
  documentation, e.g. `installer/AUTO_UPDATE.md`, is written in Arabic,
  and `hr-legacy`'s WhatsApp-based OTP delivery targets Egyptian phone
  numbers by default) — whether `configs` specifically stores any
  Arabic-script content was not directly checked in this pass (this
  file only measured schema-level collation metadata, not column
  content), so treat the collation-mismatch risk as plausible given the
  user base, not confirmed against `configs`'s actual stored values. A
  schema-wide collation mismatch during Postgres migration could
  silently change sort order or equality-comparison behavior
  specifically for `configs` rows relative to every other table, if the
  mismatch isn't deliberately resolved (either by fixing `configs` to
  match before migration, or by choosing a Postgres collation strategy
  that renders the original MySQL distinction moot).
- **Evidence**: Direct `information_schema.TABLES` query against the
  loaded dump, 2026-08-04.

## Consequence For The Target Schema

This is a single, cheap fix in the source: normalizing `configs` to
`utf8mb4_unicode_ci` before migration (a one-line `ALTER TABLE`) removes
the inconsistency at its origin, rather than needing to carry
special-case handling into the Postgres schema design. Recommended
direction, not yet executed (this analysis pass does not modify
`hr-legacy`, per this repository's boundary).

## Findings Requiring A Fresh Production Snapshot

None — collation is a schema-level (DDL) property, not data-dependent.
This result will not change between now and a production snapshot
unless someone alters the schema in the interim, which a fresh
`information_schema.TABLES` check before migration would still catch.

## Operational Assumptions Still Requiring Confirmation

- The specific target Postgres collation/locale strategy for the whole
  schema — an engineering decision, not yet made, and out of scope for
  this data-only analysis pass.
- Whether `configs` holds any data whose sort order is currently
  observably different from the rest of the schema due to this
  mismatch (a behavioral question, not resolvable from the collation
  metadata alone) — low priority given the recommended fix (normalize
  `configs`) sidesteps needing to answer it.

## Evidence

Loaded `mysql_workin.schema.sql` into a throwaway Docker MySQL 8.0
container, ran `information_schema.TABLES` collation query against all
41 tables, container destroyed after analysis, 2026-08-04.

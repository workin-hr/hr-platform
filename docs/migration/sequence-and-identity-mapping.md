# Sequence And Identity Mapping

Tracks how MySQL `AUTO_INCREMENT` columns map to PostgreSQL sequences /
identity columns, including current high-water marks that must be
preserved across cutover.

## Confirmed From Schema (2026-08-04)

Every one of the 41 tables in `mysql_workin.schema.sql` uses a single,
simple `id INT UNSIGNED AUTO_INCREMENT PRIMARY KEY` pattern — confirmed
by direct schema inspection during this pass (no composite primary keys,
no UUID/string primary keys, no natural/business keys used as PKs
anywhere in this schema). This is the **lowest-risk case** for
sequence/identity mapping: every table maps cleanly to Postgres
`GENERATED ALWAYS AS IDENTITY` (or a plain `SERIAL`/sequence, per
whichever the target ORM's convention prefers), 1:1, with no
special-casing needed per table.

## Current AUTO_INCREMENT Value (Per Table)

**Not reliably extracted in this pass.** `mysqldump`'s `AUTO_INCREMENT=N`
values embedded in the schema file's `CREATE TABLE` statements were
attempted via text extraction but the table-name-to-value pairing could
not be confirmed reliable without re-querying a loaded instance
(`SHOW TABLE STATUS` or `information_schema.TABLES.AUTO_INCREMENT`
against a freshly-loaded copy) — marked `Not yet measured` rather than
publishing an unverified number. Low priority to close: this is
mechanical migration detail, not a design decision, and a script
producing it takes minutes to run against any loaded copy of the dump
when needed for the actual cutover runbook.

## Target PostgreSQL Approach

`GENERATED ALWAYS AS IDENTITY` (standard modern Postgres identity
column), seeded per table from `MAX(id)+1` at migration time — safe and
mechanical given the confirmed single-column-integer-PK pattern above.
No table in this schema needs a different approach.

## Cross-System Consistency Risk (If Dual-Write During Cutover)

Only relevant if the migration approach ultimately chosen (ADR-0004,
still `Proposed`) involves a dual-write or phased cutover window where
both MySQL and Postgres accept writes concurrently — in that scenario,
sequence high-water marks must be re-synced after every write burst to
avoid a Postgres-side identity collision on cutover. Not a risk under a
single-cutover (stop-the-world) migration approach. Which approach is
chosen is still an open ADR-0004 decision, not resolved by this
document.

## Evidence

`mysql_workin.schema.sql` (git-ignored, local-only), direct schema
inspection of all 41 `CREATE TABLE` statements, 2026-08-04.

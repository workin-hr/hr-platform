# ETL extraction

The legacy-side extraction for the MySQL to PostgreSQL migration. It is
the step that did not exist when `scripts/migration_diff.py` was written
— the harness compares CSV exports, and nothing produced them.

## Status

Extraction and load are both **executable end to end and proven against
fixtures** in `EtlLoadFixtureTest` (real Postgres, CI). **No production
data has been moved** — that needs a dump.

## Files

| File | What it is |
|---|---|
| `export_legacy.py` | Emits the read-only MySQL extraction and the manifest |
| `load_postgres.py` | Emits the PostgreSQL load: staging, id maps, both transforms |
| `export_target_postgres.py` | Emits the target-side export, keyed back to legacy ids |

```sh
python3 scripts/etl/export_legacy.py --print-sql > export.sql
python3 scripts/etl/export_legacy.py --manifest  > manifest.json
python3 scripts/etl/export_legacy.py --self-test
```

It emits SQL rather than connecting to anything. This repository's
tooling is stdlib-only by rule, so it has to run on an operator's
machine near production with no network installs and no MySQL driver.
The Phase 0 lock also forbids a bare `.sql` file outside `backend/`,
which is the guard working as intended — an extraction script is
operator tooling, not application code.

## The timezone rule is implemented here, not decided here

`docs/migration/2026-08-09-etl-and-timezone-design.md` settles it. The
short version, because getting it backwards is unrecoverable once
loaded:

- `timestamp` columns are true UTC epochs. Exporting under
  `SET time_zone = '+00:00'` yields the real instant — lossless.
- `datetime` columns — `attendance.check_in`, `attendance.check_out`,
  `branches.expires_at` — are literal wall clock with no offset ever
  applied, and the offset in force per row is unrecoverable. They are
  exported **verbatim** and loaded as the same reading in UTC.

`SET time_zone = '+00:00'` does the right thing to both, because MySQL
converts `timestamp` on read and never converts `datetime`.

## Before running a real extraction

1. Fill the `expected_count` nulls in `manifest.json` from the dump.
   Two are already measured: employees 2,871 and attendance 36,316.
2. Capture `configs.is_daylight_saving`. The final query records it. It
   does not change the rule, but it determines how far off historical
   instants are and belongs in the decision log.
3. Export `attendance_days.csv` alongside `attendance.csv`. It carries
   legacy's own `DATE(check_in)` per row and is what conformance test 3
   compares against — the check that the wall-clock rule was actually
   applied.

## Not covered

`hr_permissions` and the EAV settings tables are exported in source
shape because they are transforms, not copies. Identity remapping is
also outstanding: the new tables use `GENERATED ALWAYS AS IDENTITY`, so
legacy primary keys cannot carry over, and the old-to-new map must be
materialised during load and **retained** — reconciliation reports in
legacy ids or nobody can read them.

## Running a load

```sh
python3 scripts/etl/load_postgres.py --print-sql | psql "$TARGET"
```

Sections are independently emittable (`--section ddl|copy|load|finalize`)
so a test can skip `copy` — `\copy` is a psql meta-command — and stage
rows itself. That is what `EtlLoadFixtureTest` does.

The load is **deterministic** (ids allocated in legacy-id order),
**restartable** (every step skips what it already did), **rerun-safe**
(a completed load re-run inserts nothing), and **fail-fast**: an
unmapped `can_*` permission flag or an attendance row that never got an
id aborts with a named error rather than loading something partial.

## Artifacts reconciliation consumes

| Artifact | What it is |
|---|---|
| `migration.id_map` | `(entity, legacy_id) -> new_id`, a real table |
| `migration.load_counts` | rows loaded per entity |

`export_target_postgres.py` reports **legacy** ids by joining the map,
so `migration_diff.py` compares like for like and a finding names a row
you can look up in the old system.

## Known legacy-data ambiguities, surfaced rather than guessed

- **`pay_overtime` has nowhere to go.** Legacy has the setting and the
  payroll port reads it, but the typed `company_settings` table has no
  column for it, so the load cannot carry it. Payroll currently assumes
  overtime is always paid. A company that had it switched off will be
  overpaid after cutover.
- **Legacy has no identity or membership table.** Credentials live on
  the employee row, so identities are derived one-per-distinct-phone and
  memberships one-per-employee. The same phone in two companies is
  something legacy rejects at login rather than models.
- **`departments` is not in the export**, so `employees.department_id`
  and `job_titles.department_id` load as NULL rather than pointing at
  nothing.
- **`branches.expires_at`** is a user-entered wall clock written by the
  dashboard under no timezone at all. Migrated as wall clock like
  everything else; it is QR expiry on a parked feature.

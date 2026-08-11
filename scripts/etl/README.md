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
| `coverage_audit.py` | Proves no legacy column is dropped without a recorded decision |

```sh
python3 scripts/etl/coverage_audit.py --report   # every gap, by class
python3 scripts/etl/coverage_audit.py --check    # fails on an unregistered gap
python3 scripts/etl/coverage_audit.py --self-test
```

`--check` is the one that matters: every gap must be registered as
`ACCEPTED` (decided, with a reason) or `PENDING` (owed a decision, with a
note). A gap in neither fails, and so does a registry entry that no
longer corresponds to a real gap. Columns can still be dropped —
deliberately, in writing — but not quietly. `--report` and `--check` need
the legacy schema (`--schema PATH`, default `../hr-legacy/`);
`--self-test` needs nothing and is what CI runs.

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
   Three are already measured: employees 2,871, attendance 36,316, and
   department-branch assignments 1,245.
2. Capture `configs.is_daylight_saving`. The final query records it. It
   does not change the rule, but it determines how far off historical
   instants are and belongs in the decision log.
3. Export `attendance_days.csv` alongside `attendance.csv`. It carries
   legacy's own `DATE(check_in)` per row and is what conformance test 3
   compares against — the check that the wall-clock rule was actually
   applied.
4. Read the `created_at_quality` probe rows. Every `zero_dates` and
   `null_dates` count must be 0. The load casts `created_at` into a NOT
   NULL `TIMESTAMPTZ`, and MySQL's `0000-00-00 00:00:00` has no
   PostgreSQL equivalent — a nonzero count is a decision to make before
   cutover, not a surprise during it.

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

- **Legacy has no identity or membership table.** Credentials live on
  the employee row, so identities are derived one-per-distinct-phone and
  memberships one-per-employee. The same phone in two companies is
  something legacy rejects at login rather than models.
- **`branches.expires_at`** is a user-entered wall clock written by the
  dashboard under no timezone at all. It is QR expiry on a parked
  feature. Migrated as wall clock like everything else — but only since
  `coverage_audit.py` caught that it was staged and then never written,
  while this file had claimed for three releases that it was migrated.
  The claim is now backed by a fixture assertion rather than by prose.

`pay_overtime` (V40) and `departments` are both migrated now:
`pay_overtime` is string-normalised the way `payroll_company_pays_overtime`
reads it (`'1'`/`'true'`/`'yes'`/`'on'`, case-insensitive, else false; no
row at all stays unset, not false) and payroll now resolves both it and
`overtime_rate` from real company settings instead of a hardcoded
default. `departments` loads before `job_titles`/`employees` (both
reference it); `department_branches` then resolves both sides of its
composite legacy key through the durable id map and derives its tenant
from the mapped department. `departments.manager_id` is the reverse of
the employee reference, a genuine cycle, so it loads NULL and is
backfilled once employees have ids.

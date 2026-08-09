# ETL extraction

The legacy-side extraction for the MySQL to PostgreSQL migration. It is
the step that did not exist when `scripts/migration_diff.py` was written
— the harness compares CSV exports, and nothing produced them.

## Status

**Extraction only. Nothing here loads, and no data has been moved.**
The load and the two non-copy transforms are still to be written.

## Files

| File | What it is |
|---|---|
| `export_legacy.py` | Emits the read-only extraction SQL and the manifest |

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

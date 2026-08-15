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
| `export_legacy.py` | Emits the read-only MySQL extraction/manifest, and (`--extract`) runs it against a live database |
| `requirements.txt` | `PyMySQL`, needed only by `export_legacy.py --extract` |
| `load_postgres.py` | Emits the PostgreSQL load: staging, id maps, both transforms |
| `export_target_postgres.py` | Emits the target-side export, keyed back to legacy ids |
| `coverage_audit.py` | Proves no legacy column is dropped without a recorded decision |

```sh
python3 scripts/etl/coverage_audit.py --report   # every gap, by class
python3 scripts/etl/coverage_audit.py --check    # fails on an unregistered gap
python3 scripts/etl/coverage_audit.py --self-test
```

`--check` is the one that matters: every gap must be registered in
exactly one of three states — `ACCEPTED` (decided not to migrate, with a
reason), `SCHEDULED` (decided to migrate, not yet migrated, naming the
decision that settled it), or `PENDING` (nobody has decided, with a note
saying what must be resolved). A gap in none of them fails. So does a
registry entry that no longer corresponds to a real gap, and one
registered in more than one state at once. Columns can still be dropped —
deliberately, in writing — but not quietly.

`SCHEDULED` exists because deciding to migrate does not close a gap: the
value still is not in the target, so the check must keep failing while
the ledger stops calling a settled question open. As of 2026-08-13 the
ledger reads 60 gaps — 10 accepted, 50 scheduled, 0 pending a decision.
That 0 briefly went to 11 the same day, when the `UNTARGETED_COLUMN`
detection class was added (a column selected and staged but with no
target column at all, invisible to `find_gaps()` before then) — see
`docs/migration/2026-08-13-etl-real-data-findings-decision-brief.md`
for the full history and D-035/D-036 in `decision-log.md` for the
answers that closed it back out. Any count printed here will drift the
moment a new gap appears; treat
`python3 scripts/etl/coverage_audit.py --check`'s own output as the
current truth, not this sentence. `--report` and `--check` need
the legacy schema (`--schema PATH`, default `../hr-legacy/`);
`--self-test` needs nothing and is what CI runs.

```sh
python3 scripts/etl/export_legacy.py --print-sql > export.sql
python3 scripts/etl/export_legacy.py --manifest  > manifest.json
python3 scripts/etl/export_legacy.py --self-test
```

These three need nothing beyond the stdlib and always will. A fourth
mode, `--extract`, connects for real and writes CSVs directly:

```sh
pip install -r scripts/etl/requirements.txt
DB_HOST=... DB_USER=... DB_PASS=... DB_NAME=... \
    python3 scripts/etl/export_legacy.py --extract --out ./legacy_export
```

**This used to be impossible by rule** ("stdlib-only, no MySQL driver, no
network installs, run near production") and the rule is gone on purpose,
not by accident: production's `secure_file_priv` is `/dev/null/`, which
disables MySQL's own `SELECT ... INTO OUTFILE` outright — the fix the
2026-08-13 brief originally proposed — and even where `INTO OUTFILE`
isn't disabled it writes to the *database server's* filesystem, which on
this managed shared host could never be retrieved anyway. There is no
connectionless extraction path against the real host. `--extract` imports
PyMySQL (pure-Python, no compilation) lazily, inside the function that
needs it, so `--print-sql`/`--manifest`/`--self-test` stay dependency-free
— only an operator running a live extraction needs
`scripts/etl/requirements.txt` installed. The Phase 0 lock forbidding a
bare `.sql` file outside `backend/` still holds and is unaffected —
`--extract` never writes SQL to disk, only CSVs.

Credentials (`DB_HOST`/`DB_USER`/`DB_PASS`/`DB_NAME`/`DB_PORT`, the last
optional, default 3306) come from the environment only — never a CLI
argument (shell history, `ps` on a shared host), never a file, never
logged. `--extract` refuses to run anything that is not `SELECT` or
`SET` (`export_legacy.py`'s `_classify_statement`), and additionally puts
the session itself into `SET SESSION TRANSACTION READ ONLY` before
EXPORT_SQL runs — read-only enforced structurally, not only documented.
`--extract` writes one CSV per table (using Python's `csv` module, so
embedded newlines/quotes and the NULL-vs-literal-`\N` collision are
handled correctly by construction — see `write_csv_rows`'s docstring),
plus `manifest.json` with `expected_count` measured from the rows this
run actually wrote (see "Manifest counts are measured, not remembered"
below), not carried from a stale snapshot.

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

## Manifest counts are measured, not remembered

The `expected_count`s in the static `MANIFEST` constant (and the
`manifest.json` `--manifest` prints) are the 2026-08-03 snapshot's
counts, kept only as a historical baseline. D-037
(`docs/bootstrap/decision-log.md`, 2026-08-15) found every single one
stale — all 21 tables grew 4.3%–59.6% in the 12 days since, and
`attendance` grew *during the verification session that found this*.
`--extract` does not carry those constants forward: it counts the rows
it actually wrote and generates a fresh `<out>/manifest.json` from that,
via `build_measured_manifest`. **Hand `migration_diff.py` the
`manifest.json` a real `--extract` run produced, never the static one
`--manifest` prints** — the latter is now guaranteed wrong.

## Before running a real extraction

1. Capture `configs.is_daylight_saving`. `--extract` prints it (and the
   other diagnostic queries below) to stderr; it does not change the
   timezone rule, but it determines how far off historical instants are
   and belongs in the decision log.
2. Read the `created_at_quality` probe rows `--extract` prints. Every
   `zero_dates` and `null_dates` count must be 0. The load casts
   `created_at` into a NOT NULL `TIMESTAMPTZ`, and MySQL's
   `0000-00-00 00:00:00` has no PostgreSQL equivalent — a nonzero count
   is a decision to make before cutover, not a surprise during it.
3. Read the `leave_balance` duplicate-key rows `--extract` prints (legacy
   has no unique key on `(employee_id, year)` but V25 declares one) — a
   nonzero count is, again, a decision to make with real numbers before
   cutover, not an abort discovered during it.

(`attendance_days.csv` no longer needs a separate manual step — it is
just another TABLE statement in `EXPORT_SQL`, written automatically by
`--extract` alongside `attendance.csv`.)

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

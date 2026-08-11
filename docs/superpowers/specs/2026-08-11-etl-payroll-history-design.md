# ETL Payroll History And Scheduling — Design (2026-08-11)

## Purpose And Authority

`scripts/etl/load_postgres.py` migrates 17 entities plus
`department_branches`. Four legacy tables have a shipped target schema
and no migration path at all: `payslips`, `leave_balance`,
`employee_schedules`, and `employee_shift_assignments`. Reconciliation
cannot be green while ~9,700 rows with somewhere to land have no way to
get there, and `payslips` in particular is the retention commitment
D-030 made explicit — all payroll and financial history is always
retained.

This is the largest remaining ETL work that needs neither a production
dump nor a product decision, which is why it goes first. It is provable
against fixtures exactly the way `#82`/`#84`/`#85` were.

**This document is planning output only** (hr-platform `CLAUDE.md`:
Claude's role here is planning/analysis/review; implementation is a
separate, explicitly assigned step).

Evidence: legacy DDL and index definitions read from
`hr-legacy/mysql_workin.schema.sql` (`CREATE TABLE` bodies plus the
`ALTER TABLE` index blocks at lines 1117-1120, 1125-1129, 1173-1175,
1204-1208); row counts from
[`table-volume-analysis.md`](../../migration/table-volume-analysis.md)
(measured 2026-08-04 against the real dump, not the AUTO_INCREMENT
approximations in
[`database-schema-inventory.md`](../../migration/database-schema-inventory.md));
target schemas read in full from `V11__create_payslips.sql`,
`V25__create_requests_and_leave_balances.sql`, and
`V33__create_employee_schedule_foundation.sql`; load-script structure
read from `scripts/etl/load_postgres.py` (`_allocate`, `_load`'s
`penalties` block at lines 581-593, and `_finalize`).

## Volumes

| Legacy table | Rows | Target | Shape |
|---|---:|---|---|
| `employee_shift_assignments` | 3,568 | V33 | 1:1 + `company_id` |
| `leave_balance` | 2,980 | V25 `leave_balances` | 1:1 + `company_id`, `created_at` |
| `payslips` | 2,836 | V11 | 1:1 + `company_id`, `created_at` |
| `employee_schedules` | 352 | V33 | 1:1 + `company_id` |

All four are near-exact column matches. This is a copy slice, not a
transform slice — no EAV collapse, no permission mapping.

## Decisions Taken

Repository owner confirmed 2026-08-11:

- **D-a: payslips are copied verbatim, not recomputed.** Legacy's
  stored numbers are what the employee was actually paid; they are a
  record, not a recomputation. A migrated payslip may not equal what
  `PayrollCalculationService` would compute for the same inputs, and
  that is accepted — reconciliation compares migrated-to-legacy, not
  migrated-to-recomputed.
- **D-b: `created_at` is derived from the parent, not stamped at load
  time.** `payslips.created_at` is its `payroll_batches.created_at` (a
  payslip is created when its batch is calculated).
  `leave_balances.created_at` is `make_date(year, 1, 1)` (the balance
  *is* the year; the row cannot predate it). Neither claims more
  precision than exists, and both order correctly, which `now()` for
  every row would not.
- **D-c: the `leave_balance` duplicate risk is detected at extraction
  and aborted at load.** See "Failure Modes" below.

## Dependency: The `created_at` Repair Must Land First

D-b is currently unimplementable. The load blocks for 14 of the 15
entities that stage a legacy `created_at` omit it from their `INSERT`
column list, so the target's `DEFAULT now()` applies and every migrated
row claims the cutover instant. `departments` is the only entity that
carries it through, added by `#85`.

`payroll_batches` is one of the 14. Deriving `payslips.created_at` from
its parent batch would therefore yield the load clock for all 2,836
payslips — precisely the outcome D-b rejected.

The repair is tracked separately in
[`2026-08-11-etl-created-at-repair.md`](../plans/2026-08-11-etl-created-at-repair.md)
and ships as its own PR, in the shape of `#84`. This slice rebases on it.
Implementing this slice first would produce code that passes its own
fixtures and is wrong on production data.

## Scope

**In:**

- `scripts/etl/export_legacy.py` — four SELECTs added to `EXPORT_SQL`,
  four entries added to `MANIFEST`, the `leave_balance` duplicate probe,
  and `--self-test` checks for all of it.
- `scripts/etl/load_postgres.py` — four `STAGING` entries, four
  `LOAD_ORDER` entries, four `_load` blocks.
- `scripts/etl/export_target_postgres.py` — four target-side SELECTs
  reporting legacy ids through `migration.id_map`, so
  `scripts/migration_diff.py` compares like for like.
- `backend/src/test/java/com/workin/backend/migration/EtlLoadFixtureTest.java`
  — fixture rows and assertions for all four, plus a new abort test.

**Out:**

- No production data moves. `expected_count` stays `null` for all four
  until someone measures them against a real dump (PMR-03,
  [`hr-platform#11`](https://github.com/workin-hr/hr-platform/issues/11)).
- No keep-or-drop decision for the nine remaining unmigrated legacy
  tables (`notifications` at 4,014 rows, `complaints`, `assets`,
  `administrative_decisions`, `workforce_planning`, `push_tokens`,
  `otp_codes`, `configs`, `employee_docs`). Those are product calls, not
  engineering ones, and belong in a decisions brief rather than blocking
  this slice.
- No `#73` attendance-parity fix. The parity baseline stands until
  reconciliation is green, per `#82`.

## Load Order

All four are foreign-key leaves — verified: no migration under
`backend/src/main/resources/db/migration` contains `REFERENCES` to any
of them. They append after `attendance`, with no cycle to resolve:

```text
… "penalties", "attendance",
   "payslips", "leave_balances", "employee_schedules",
   "employee_shift_assignments"
```

### The name-divergence trap

`_finalize` uses `LOAD_ORDER` entries **directly as PostgreSQL table
names** — `pg_get_serial_sequence('{e}', 'id')`, `FROM {e}` in the count
rollup, and `FROM {e} t` in the unmapped-FK guard. Every entity shipped
so far has an identical legacy and target name, so this has never
mattered.

`leave_balance` → `leave_balances` is the first divergence in the
script's history. The `LOAD_ORDER` entry must be the **target** name
`leave_balances`; the `STAGING` key, the CSV file, and the staging table
stay on the **legacy** name `leave_balance`. `_allocate(entity, source)`
already takes the two as separate arguments, so this is expressible
without changing the helper.

Getting it backwards emits `FROM leave_balance` against a table
PostgreSQL does not have, and fails in `finalize` — after every INSERT
has already run.

## Per-Table Derivation

Legacy has no tenant column on any of these four tables; all four
targets carry a denormalized `company_id` for RLS. Three of them derive
it exactly the way the shipped `penalties` block does — join
`migration.id_map` for the employee, then `JOIN employees e ON e.id =
emp.new_id` and read `e.company_id`.

- **`leave_balances`** — `s.year::SMALLINT` (MySQL `year(4)` exports as
  a 4-digit string), `created_at = make_date(s.year::INT, 1, 1)` per
  D-b, and `remaining_days` **omitted from the INSERT column list
  entirely**. It is `GENERATED ALWAYS AS (total_days - used_days)
  STORED` on both sides; naming it in an INSERT makes PostgreSQL reject
  the statement.
- **`employee_schedules`** — legacy has a real `created_at TIMESTAMP`,
  which under `SET time_zone = '+00:00'` is a true UTC instant and
  carries through losslessly (the settled rule,
  [`2026-08-09-etl-and-timezone-design.md`](../../migration/2026-08-09-etl-and-timezone-design.md)).
  `start_time`/`end_time` are `TIME` on both sides, no offset involved.
- **`employee_shift_assignments`** — a second `id_map` join for
  `shift_id`, plus a cross-check that the shift's `company_id` matches
  the employee's.
- **`payslips`** — the one that differs. It can reach `company_id` two
  ways, through `payroll_batches` or through `employees`, and the two
  should agree. It derives from the batch (the batch *is* the company's
  payroll run) and aborts by name when the employee's company disagrees,
  which turns a silent cross-tenant write into a named failure.

## The `remaining_days` Asymmetry

`remaining_days` is exported from legacy **and** exported from the
target, so `migration_diff.py` compares both sides and proves the
generated expression agrees across the two engines. It is excluded only
from the INSERT column list.

Export it; do not insert it. Those are two different decisions, and
conflating them either loses the cross-engine check or breaks the load.

## Failure Modes

The load's existing contract is that it aborts with a named error rather
than loading something partial. Four abort conditions, all following the
shape of the shipped unmapped-`can_*` abort:

| Condition | Why it can happen | Abort names |
|---|---|---|
| `leave_balance` duplicate `(employee_id, year)` | legacy has only `PRIMARY KEY(id)` and a plain `KEY` on `employee_id`; V25 declares `UNIQUE (employee_id, year)` | the employee and the year |
| `payslips` batch/employee company mismatch | no tenant column on payslips; the two derivation paths can disagree | the payslip's legacy id and both company ids |
| `employee_shift_assignments` shift/employee company mismatch | a shift belonging to another tenant | the assignment's legacy id |
| unmapped FK on any of the four | a parent missing from the export | already covered — `_finalize`'s guard iterates `LOAD_ORDER` |

That last row is free precisely because all four go into `LOAD_ORDER`
rather than `AUXILIARY_LOAD_TABLES`.

### Why the duplicate risk is real and unmeasured

[`duplicate-business-key-analysis.md`](../../migration/duplicate-business-key-analysis.md)
covers `job_titles`, `departments`, `shifts`, `branches`,
`request_types`, and `employees.phone`. It does **not** cover
`leave_balance`. Legacy permits two rows for one employee-year; the new
schema does not. Nobody has checked production.

Per D-c this is handled at both ends. `export_legacy.py` gains the
duplicate-count query so the answer lands in the extraction artifact
when the dump is taken — weeks before cutover, while there is still time
to decide what to do about it. `load_postgres.py` keeps a named
fail-fast abort as the backstop. Nothing is collapsed or invented: an
employee whose balance is split across two rows must not silently lose
days.

`payslips` needs no equivalent probe — legacy already enforces
`uk_payslip_batch_employee (batch_id, employee_id)`, the same key V11
carries forward. `employee_schedules` likewise has
`uniq_employee_schedule_date (employee_id, schedule_date)` in legacy,
matching V33's constraint.

## Test Plan

`EtlLoadFixtureTest` runs the emitted SQL against real PostgreSQL with a
miniature dump. The three most recent commits on
`feat/etl-postgres-load` tightened it — token-scoped fixtures, per-test
isolation, assertions scoped to migrated rows. New coverage follows that
pattern rather than inventing one.

**Extend `stageFixture`** with rows for all four tables, chosen so the
cases that only fail on real execution are actually executed:

- a payslip whose batch and employee agree, proving both the
  batch-derived `company_id` and the batch-derived `created_at`
- a leave balance whose `remaining_days` must be computed by PostgreSQL
  rather than copied
- a schedule row carrying a real legacy `created_at`, proving it
  survives as a true UTC instant
- a shift assignment resolving two foreign keys through the map

**Extend `theLoadProgramRunsEndToEndAndIsSafeToRerun`** to assert, for
each of the four: rows landed, foreign keys rewired to new ids,
`company_id` derived correctly, counts recorded in
`migration.load_counts`, sequences advanced past the loaded ids, and a
re-run inserting nothing.

**Add a third `@Test`** for the `leave_balance` duplicate abort,
mirroring `anUnmappedPermissionFlagAbortsTheLoad`: stage two rows for
one employee-year, assert the load aborts and names them. This is the
one that matters most — it is the failure most likely to occur on real
production data, and executing the abort is the only way to know it
works.

**`export_legacy.py --self-test`** gains checks that all four tables
reach `EXPORT_SQL` and `MANIFEST`, and that the duplicate probe is
present — the same guard style that caught `departments` being silently
dropped from the export.

## Acceptance Criteria

- All four tables load, with foreign keys resolved through
  `migration.id_map` and `company_id` derived per the rules above.
- `leave_balances.remaining_days` is computed by PostgreSQL, and its
  value matches legacy's stored value for every fixture row.
- Each of the four abort conditions is proven by a test that executes
  the abort, not by a string check that the SQL contains it.
- A completed load re-run inserts nothing.
- `migration.load_counts` carries a row for each of the four.
- `export_legacy.py --self-test` and `load_postgres.py --self-test` pass.
- The `leave_balance` duplicate count is emitted by the extraction and
  documented as an input the operator must read before cutover.

## Follow-On, Not In This Slice

- A decisions brief converting the nine remaining unmigrated tables into
  answerable keep-or-drop questions, in the shape of
  [`pending-decisions-brief.md`](../../migration/pending-decisions-brief.md).
- `advances` staged columns omit legacy's `deduction_installments_json`
  and its two deduction modes. Whether the new advances module models
  installments at all is an open question, not a defect finding — it
  needs checking before anyone claims `advances` is fully migrated.
- Filling the 14 `null` `expected_count` entries in `MANIFEST`, which
  needs the dump.

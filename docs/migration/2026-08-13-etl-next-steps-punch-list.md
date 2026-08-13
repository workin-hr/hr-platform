# ETL Migration — Next Steps Punch List

## Purpose

D-032–D-034 (`docs/bootstrap/decision-log.md`) closed the entire 47-gap
coverage decision backlog on 2026-08-12: every gap is now `ACCEPTED`,
`SCHEDULED`, or nothing (`PENDING` is empty per
`scripts/etl/coverage_audit.py --check`). That inverted the remaining
work from *decisions* to *engineering*. This document turns the
follow-ups those decisions left behind, plus the prerequisites already
recorded in `scripts/etl/README.md`, into one ordered list. It is a
planning artifact — it does not itself implement anything, per this
repository's [`CLAUDE.md`](../../CLAUDE.md) boundary — and it indexes
`decision-log.md`, `coverage_audit.py`, and `scripts/etl/README.md`
rather than replacing them.

**Priority key** (same convention as `consolidated-task-matrix.md`):
`P0` blocks any real extraction/load or the next slice outright; `P1`
blocks a specific downstream piece of work; `P2` is the bulk of
remaining, unblocked engineering; `P3` is explicitly deferred, listed
here only so it isn't mistaken for an open item.

## P0 — Blocks any real extraction/load — **RESOLVED 2026-08-13**

| # | Item | Why it blocks | Source | Resolution |
|---|---|---|---|---|
| 1 | Obtain a production data dump | Only a 54 KB schema dump was known when this list was first written. Blocks OQ-1's collision population count, Q4's assertion-vs-column branch, and every other data-shaped question. | D-032 impact; D-034 follow-up | **Already present locally**: `hr-legacy/mysql_workin.schema.sql` + `hr-legacy/mysql_workin.data.sql` (dump dated 2026-08-03, gitignored, 8.4 MB data). This is the same dataset `table-volume-analysis.md` and `invalid-date-analysis.md` were built from on 2026-08-04 — row counts cross-checked and match exactly. It is a point-in-time snapshot, not a live connection to production; re-verify against a fresh snapshot before an actual cutover run. |
| 2 | Fill `expected_count` nulls in `manifest.json` from the dump | Three counts were already measured (employees 2,871; attendance 36,316; department-branch assignments 1,245) — the rest were null | `scripts/etl/README.md` §"Before running a real extraction" #1 | **Done.** All 13 remaining nulls filled by direct count against the loaded dump and written into `scripts/etl/export_legacy.py`'s `MANIFEST`: `companies` 269, `exception_types` 111, `shifts` 302, `branches` 375, `job_titles` 1,684, `departments` 950, `requests` 213, `request_types` 192, `company_official_holidays` 69, `salary_contracts` 2,829, `payroll_batches` 61, `advances` 24, `penalties` 15. |
| 3 | Capture `configs.is_daylight_saving` | Doesn't change the timezone rule but determines how far off historical instants are; belongs in the decision log | README #2 | **Done — value is `true`.** Recorded in `docs/migration/2026-08-09-etl-and-timezone-design.md`'s Open Questions. Flip history remains unrecorded — a single current value doesn't establish when the flag last toggled. |
| 4 | Export `attendance_days.csv` alongside `attendance.csv` | Carries legacy's own `DATE(check_in)` per row; what conformance test 3 checks the wall-clock rule against | README #3 | **Confirmed** — one row per attendance record, matches the existing 36,316 baseline. |
| 5 | Read `created_at_quality` probe rows; confirm `zero_dates`/`null_dates` are 0 | The load casts `created_at` into `NOT NULL TIMESTAMPTZ`; MySQL's `0000-00-00 00:00:00` has no PostgreSQL equivalent — a nonzero count needs a decision before cutover, not a surprise during it | README #4 | **Clean — 0 zero-dates, 0 nulls across all 15 probed tables.** No remediation decision needed. Recorded in `docs/migration/invalid-date-analysis.md`. |

Method for items 1–5: loaded `mysql_workin.schema.sql` + `mysql_workin.data.sql` into a throwaway local Docker MySQL 8.0 container, ran `scripts/etl/export_legacy.py`'s probe queries against it, container destroyed immediately after. No production credentials were used or requested.

## P0 — Full end-to-end load + reconciliation run (2026-08-13) — **NOT clean; 10 findings, decisions needed before OQ-1–3**

Before starting items 6–8, ran the complete ETL — extraction, load, and
reconciliation — against the real ~62K-row dataset, in disposable
MySQL 8.0 (source) and Postgres 17 (target, with all 40 Flyway
migrations applied to match `EtlLoadFixtureTest`'s environment)
containers. **This is the first time any part of the load or
reconciliation side has run against real data — `EtlLoadFixtureTest`
only ever exercised a small hand-built fixture.** No implementation was
changed to do this; every fix below was applied to a scratch copy of
the extracted CSVs only, never to the repository, so the load could be
observed past each failure to the next one. Both containers were
destroyed after; `git status` in both repos shows no changes from this
exercise beyond this document.

**Verdict: the load does not complete cleanly.** It took 7 rounds of
scratch-only patches to reach `EXIT: 0`, each uncovering a distinct real
defect — 6 in the data, 2 in the ETL/coverage tooling itself, plus one
extraction-procedure problem found before the load even started. Per
this run's instructions, none of these is being implemented now; they
need the same kind of explicit decision D-032–D-034 gave the original
47 gaps before OQ-1–3 (or any of this) gets built.

### A. The documented extraction procedure does not produce a file `\copy` can load

`scripts/etl/README.md` and `export_legacy.py`'s own header comment
document `mysql --defaults-file=... --batch --raw workin < ... > file`
as the extraction method. Tried literally, on `requests` (which has a
free-text `notes` column): it emits **tab-separated** output (`\copy`
expects comma-delimited `FORMAT csv`), renders `NULL` as the **literal
4-character string `NULL`** (not `\N`, which `\copy ... NULL '\N'`
requires), and — the serious part — **21 of 213 `requests.notes` rows
and 4 of 213 `requests.reply` rows contain embedded real newlines**,
which `--raw` emits unescaped and unquoted, corrupting row boundaries
for any line-based reader. A CSV actually usable by `\copy` was
produced instead via MySQL's own `SELECT ... INTO OUTFILE` with
`OPTIONALLY ENCLOSED BY '"'` (default backslash escaping, verified safe
here since a scan of every extracted free-text column found zero
embedded double-quote characters in this snapshot — that assumption
would need re-checking against a fresher dump). This is what actually
produced the 26 CSVs used for the rest of this run.
**Decision/fix needed**: `export_legacy.py`'s documented procedure
needs to change to something that actually works, or the README needs
to stop presenting `--batch --raw` as viable.

### B. `export_legacy.py`'s `SELECT *` no longer matches `load_postgres.py`'s STAGING shape for 2 tables

`SELECT * FROM setting_definitions` emits **11 columns**; `STAGING`
declares 2 (`id`, `setting_key`). `SELECT * FROM setting_allowed_values`
emits **7**; `STAGING` declares 4. The legacy schema grew
(`label_ar`/`label_en`/`description_ar`/`description_en`/`icon_data`/
`is_multi`/`is_required`/`updated_at` on the first;
`label_ar`/`label_en`/`updated_at` on the second) since `STAGING` was
written. Confirmed empirically: `\copy` rejects both with `ERROR:
extra data after last expected column`. `EtlLoadFixtureTest` never
catches this because it populates staging directly with hand-written
`INSERT`s matching `STAGING`'s declared shape, bypassing `SELECT *`
entirely — this class of drift is invisible to fixtures by
construction. **Decision/fix needed**: either declare the missing
columns in `STAGING` (and decide whether the target needs them — this
is itself new coverage-ledger material, see finding I) or make the
`SELECT` explicit and matching.

### C. `companies.company_name` is NULL for 61 of 269 companies (22.7%) — concentrated in `pending` signup

Target `companies.name` is `NOT NULL`; the load aborts on the first
such row. Breakdown by legacy `status`: `active` 198/198 have a name,
`rejected` 2/2, `suspended` 3/3 — but `pending` is 61 NULL of 66
(92%). This reads as a real product state: a company mid-signup that
hasn't submitted a business name yet, not corrupt data. **Not
documented anywhere before this run.** **Decision needed**: what a
migrated `pending`-with-no-name company gets for `name` (placeholder
string? migration-invalid flag, same shape as OQ-2? exclude
pending-incomplete companies from this migration pass entirely?) — this
is a new open question in the same family as OQ-1/OQ-2, not something
to default silently.

### D. `salary_contracts.effective_from` zero-dates — already known, now empirically confirmed as a hard abort

`invalid-date-analysis.md` already found 23 of 2,829 rows with
`effective_from = '0000-00-00'` and flagged it as needing a business
decision (the column is `NOT NULL`, so `NULL` isn't an option). This
run confirms it's not just a query-level finding: it is a literal load
abort (`ERROR: date/time field value out of range: "0000-00-00"`).
No new decision needed beyond what that document already asks for —
recorded here because seeing it actually break the load is stronger
evidence than the earlier query-only count.

### E. 13 of 36,316 `attendance` rows violate the "punches XOR exception day" invariant

`docs/legacy/business-rule-extraction.md` documents attendance rows as
either real punches or a category-only exception day, never both; the
target schema enforces this with `CHECK (exception_type_id IS NULL OR
check_out IS NULL)`. **13 real rows have both `check_out` and
`exception_type_id` set** (e.g. legacy id 376: `check_out =
2026-07-01 16:27:16`, `exception_type_id = 50`). This contradicts the
documented invariant the target schema was built on — either the
business-rule extraction missed a legacy code path that allows both, or
these 13 rows are themselves a legacy data-quality defect (e.g. an
exception applied after a punch already existed, and legacy's own code
never blocked that). **Not documented anywhere before this run.**
**Decision needed**: which field wins (this run kept `check_out`,
cleared `exception_type_id`, as a test-only choice, not a
recommendation) and whether legacy's actual business logic needs
re-reading for this case.

### F. `leave_balance.year = '0000'` for 6 of 2,980 rows

Breaks the load's synthesized `created_at`
(`make_timestamptz(s.year::INT, 1, 1, 0, 0, 0, 'UTC')` — see
`load_postgres.py`'s D-b comment, "the balance IS the year, so the row
cannot predate it") with `ERROR: date field value out of range:
0-01-01`. All 6 affected rows: employee legacy ids 4731, 4732, 4772,
4779, 4806, 4808. **Not documented in `invalid-date-analysis.md`**,
which doesn't cover this table. **Decision needed**: same family as
`salary_contracts.effective_from` — what a zero-year balance row
becomes (`year` isn't nullable per V25's schema; a sentinel year, or
exclusion, needs a call).

### G. `employee_shift_assignments.effective_from` zero-dates — 23 of 3,568 rows (0.64%)

Same defect class as D, on a column `invalid-date-analysis.md` never
checked. `ERROR: date/time field value out of range: "0000-00-00"`.
**Not documented before this run. Decision needed**, same shape as D.

### H. 3 `exception_types` rows reference a company that does not exist

Legacy `exception_types` ids 1, 3, 4 all have `company_id = 19`, and no
`companies` row with `id = 19` exists in this dump. `load_postgres.py`'s
own fail-fast guard caught this correctly (`ETL: 3 exception_types rows
were allocated an id but never loaded -- a parent was missing from the
export`) — **this is the guard working exactly as designed, not a
script bug.** Confirmed nothing downstream references these 3 ids
(zero `attendance` or `request_types` rows point to them), so dropping
them is low-risk, but that's still a decision, not a default. **Not
documented in `orphan-reference-analysis.md`.**

### I. `coverage_audit.py` has a real detection blind spot — 7 `employees` columns are invisible to the ledger

Traced precisely in `find_gaps()` (`scripts/etl/coverage_audit.py`
~line 429): a column is flagged `UNEXTRACTED_COLUMN` ("no target
column") only when it is **absent from the SELECT**; it's flagged
`UNLOADED_COLUMN` ("staged but absent from INSERT") only when **a
target column exists**. A column that is selected, staged, has **no**
target column, and is never inserted — falls through both branches and
is reported as nothing at all. `employees.employee_code`,
`.country_code`, `.national_id`, `.birth_date`, `.gender`,
`.hire_date`, `.updated_at` are exactly this case: all seven appear in
`export_legacy.py`'s `employees` SELECT and in `load_postgres.py`'s
STAGING list, none has a target column (confirmed against the full
column set from V8 + V29 + V37), and none is in the actual `INSERT INTO
employees (...)`. (`password_hash` looked like an eighth instance at
first glance — it is not: it's already correctly registered `ACCEPTED`
in the ledger, because a target column genuinely exists for it and the
credential is deliberately carried onto `identities.password_hash`
instead per the identity/membership role split, not dropped.)
**This means `coverage_audit.py --check`'s "47 gaps — 0 pending" is
incomplete**: these 7 columns are registered nowhere — not `ACCEPTED`,
not `SCHEDULED`, not `PENDING` — despite being a real, confirmed
drop-in-progress today. `employee_code` at least was a *known,
deliberate* exclusion (documented in `V8__create_employees.sql`'s own
comment as "intentionally omitted... tracked follow-up"); the other six
were never flagged as a decision at all. **Decision/fix needed on two
levels**: (1) each of the 7 columns needs the same kind of accept/drop
call the other 47 gaps got, and (2) `find_gaps()` itself needs a third
detection branch for "selected + staged + no target column" so this
class of gap can't recur invisibly.

### J. `migration_diff.py` cannot currently reconcile 18 of 21 tables — header mismatch, not a data problem

Ran the full reconciliation: extracted target-side CSVs via
`export_target_postgres.py`'s emitted SQL, then
`scripts/migration_diff.py --source --target --manifest`. **18 of 21
tables failed immediately on `header mismatch`** — `export_legacy.py`
and `export_target_postgres.py` select different column subsets for
the same table (e.g. `companies`: source selects
`id,name,phone,status,created_at`; target selects only `id,name,phone`
— the target table genuinely doesn't have `status` yet, by design,
per `V1__create_companies.sql`'s minimal-schema note). `migration_diff.py`
requires an exact header match before comparing a single cell
(`read_export`/`compare_table`), so for these 18 tables **no actual
per-row or per-cell comparison ever runs** — the tool reports a
structural mismatch, not a correctness verdict, and always will until
the two export scripts' column lists are reconciled. **The 3 tables
that do have matching headers passed with a genuine checksum match**
(`attendance_days` 36,316/36,316, `departments` 950/950,
`department_branches` 1,245/1,245) — this is the one clean, positive
result of the whole run: where reconciliation can actually run, the
load is correct. **Decision/fix needed**: `export_target_postgres.py`'s
column lists need to be brought into alignment with `export_legacy.py`'s
(or a documented, deliberate subset with the tool changed to compare
only shared columns) before `migration_diff.py` can be trusted for
anything beyond row counts on these 18 tables.

### Positive finding: no performance problem at this volume

The full load (all 26 tables, ~62K rows) completed in a few seconds
once the data-quality blockers were patched around. Confirms
`table-volume-analysis.md`'s prediction that bulk-copy migration is
well within a single maintenance window at this data volume — no slow
queries or performance concerns surfaced.

### How this changes items 6–8

OQ-1/OQ-2/OQ-3 (below) are about the `COMPANY_ADMIN` identity-minting
layer, which sits *on top of* the base entity load this run just
exercised. Findings C, E, F, G, H land on tables OQ-1–3 don't touch
directly, but the pattern is identical — an abort where a decision
belongs, discovered only by running against real data — and finding I
means the coverage ledger itself cannot currently be trusted as a
complete picture without a fix. Building OQ-1–3 now, on top of a base
load that doesn't complete cleanly and a coverage ledger with a proven
blind spot, would repeat the exact mistake finding I just found.
**Recommended sequencing**: resolve A/B/I/J (tooling; no product
decision needed, these are bugs) and get C/D/E/F/G/H in front of the
repository owner as a decision brief (same shape as
`etl-coverage-decisions-brief.md`) before starting OQ-1–3 implementation.

## P0 — Blocks the `COMPANY_ADMIN` minting slice

**Status 2026-08-13: hold.** Per the run above, implementation on 6–8
should wait for the A/B/I/J tooling fixes and the C/D/E/F/G/H decision
brief — see "How this changes items 6–8" above for why.

| # | Item | Why it blocks | Source |
|---|---|---|---|
| 6 | Build the OQ-1 remediation channel | Every load guard today is a load-level abort; OQ-1 requires per-record handling (reuse identity on same-person match, flag for remediation otherwise) | D-033 follow-up |
| 7 | Build the OQ-2 migration-invalid state | A login phone that fails E.164 normalization must be marked migration-invalid and blocked from auto-activation, not silently rewritten or dropped — also means `companies.country_code` can't be dropped unconditionally | D-033 impact/follow-up |
| 8 | Enforce OQ-3 (`updated_at`) at the database, suppressed correctly during load | ETL, admin-script, and application writes must share identical `updated_at` semantics; a DB trigger must be suppressed during load or migrated rows carry the load timestamp — the same trap `created_at` fell into with `DEFAULT now()` | D-033 impact |

## P1 — Governance/documentation debt

| # | Item | Why | Source |
|---|---|---|---|
| 9 | Record OQ-4 (employee `address` visibility) in the ADR-0010 authorization catalog | Decided (employee self, `COMPANY_ADMIN`, authorized HR roles, `SUPER_ADMIN`; excluded from list endpoints) but not yet written into the catalog | D-033 follow-up |

## P1 — Schema work needed before three D-034 columns can load

| # | Item | Source |
|---|---|---|
| 10 | Add target columns for `is_mobile_attendance_enabled`, `can_check_in_any_branch`, `join_request_status` | D-034 follow-up — all three decided to migrate; no target column exists for any of them yet |

## P1 — File-migration workstream (doesn't exist in any form)

| # | Item | Why | Source |
|---|---|---|---|
| 11 | Design and build the full file-migration workstream, covering all four file-backed columns — `employee_docs` (metadata + files), `companies.commercial_reg_url`, `companies.logo_url`, `employees.photo_url` — including byte transfer out of legacy `uploads/`, integrity verification of each transferred file, storage-location mapping (legacy path → platform-controlled storage reference), and rewriting every stored URL/reference to the new location | Every ETL artifact today emits SQL; nothing moves bytes. `etl-coverage-decisions-brief.md`'s "File migration" row lists exactly these four columns/tables and states "No mechanism exists" | D-032 impact; `docs/migration/etl-coverage-decisions-brief.md` (File migration row, line 341) |

## P2 — Bulk of remaining implementation

| # | Item | Source |
|---|---|---|
| 12 | Implement the 39 `SCHEDULED` ledger entries in `coverage_audit.py` | Each entry cites the decision that settled it; `--self-test` enforces the citation. This is the largest block of remaining work — decided, not built. |

## P2 — Known gaps outside the coverage ledger

| # | Item | Note |
|---|---|---|
| 13 | `hr_permissions` and the EAV settings tables transform (not a straight copy) | Flagged outstanding in `scripts/etl/README.md` §"Not covered" |

**Verified and dropped from this list:** `scripts/etl/README.md` §"Not
covered" also calls durable identity remapping outstanding, but it is
not. `scripts/etl/load_postgres.py` creates `migration.id_map`
(`CREATE TABLE IF NOT EXISTS migration.id_map`, line 214) and populates
it with an `INSERT` per entity during load (companies, departments,
branches, job_titles, shifts, exception_types, employees, identities,
tenant_memberships, and every other mapped entity); every subsequent
staging query resolves foreign keys back through it
(`LEFT JOIN migration.id_map ...`, `map_join()` helper at line 267).
`EtlLoadFixtureTest` exercises it exhaustively — row counts per entity
and dozens of `JOIN migration.id_map` assertions throughout the file,
including a rerun-safety check that the map's row count is unchanged
after a repeated load (`mapBefore`/`mapAfter`, lines 629–640). The
README is stale on this point the same way it was previously caught
being stale on `branches.expires_at` — worth a follow-up fix to the
README itself, but not an open engineering item.

## P3 — Explicitly deferred, not actionable

| Item | Why not actionable |
|---|---|
| GitHub branch protection on `main` | D-013: `workin-hr` is a GitHub Free org, `hr-platform` is private, both branch-protection APIs return `403`. Owner has decided neither an org upgrade nor making the repo public is in scope. `scripts/check-branch-protection.sh` remains built and tested but pending indefinitely under this constraint. |

## Standing risk

As of 2026-08-13, both the extraction-side probes (P0 items 2–5) and a
full extraction+load+reconciliation run (P0 findings A–J above) have
been run against the real legacy snapshot (`mysql_workin.data.sql`,
dated 2026-08-03). `scripts/etl/README.md` §Status's "no production
data has been moved" is narrowly still true — nothing has touched an
actual production database, only this snapshot — but "the load has
never run against real data" is no longer accurate; it has, and it
surfaced 10 real findings, 6 of which need a product decision before
OQ-1–3 or anything else gets built on top. **The load does not complete
cleanly without those decisions.** The snapshot itself is 10 days old
at time of writing and point-in-time — re-verify every finding above
against a fresher dump before an actual cutover run, per the "Findings
Requiring A Fresh Production Snapshot" caveat already carried in
`table-volume-analysis.md` and `invalid-date-analysis.md`; a fresher
snapshot could easily have different rows hitting findings C/E/F/G/H,
not just different counts.

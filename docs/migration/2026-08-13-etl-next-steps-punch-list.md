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

### C. `companies.company_name` is NULL for 61 of 269 companies (22.7%) — concentrated in `pending` signup — **DECIDED 2026-08-13 (D-035, Q1/A1)**

Target `companies.name` is `NOT NULL`; the load aborts on the first
such row. Breakdown by legacy `status`: `active` 198/198 have a name,
`rejected` 2/2, `suspended` 3/3 — but `pending` is 61 NULL of 66
(92%). **Decided**: all companies migrate, including `pending` ones;
`name` stays nullable (no placeholder, not migration-invalid, this is
a valid legacy lifecycle state); a non-null name is required only at
activation (application-layer rule, not yet built). `companies.status`
also gets a real target column preserving all four legacy states
instead of the boolean `load_postgres.py` currently computes — moved
from `PENDING` to `SCHEDULED` in `coverage_audit.py`. Full answer:
`2026-08-13-etl-real-data-findings-decision-brief.md` A1.

### D. `salary_contracts.effective_from` zero-dates — **DECIDED 2026-08-13 (D-035, Q2/A2)**

`invalid-date-analysis.md` already found 23 of 2,829 rows with
`effective_from = '0000-00-00'`; this run confirmed it as a literal
load abort, not just a query-level finding. **Decided**: migrate the
contracts, fall back to the row's own `created_at` date, and record the
repair in migration remediation/audit output (a mechanism that does not
exist yet — see "Engineering now owed" below). Full answer: brief A2.

### E. 13 of 36,316 `attendance` rows violate the "punches XOR exception day" invariant — **DECIDED 2026-08-13 (D-035, Q3/A3)**

`docs/legacy/business-rule-extraction.md` documents attendance rows as
either real punches or a category-only exception day, never both; **13
real rows have both `check_out` and `exception_type_id` set** (e.g.
legacy id 376). **Decided**: these are legacy data-quality defects, not
a real exception to the rule. Preserve `check_in`/`check_out`, clear
`exception_type_id`, record each remediation individually, keep the
target `CHECK` constraint as-is. Full answer: brief A3.

### F. `leave_balance.year = '0000'` for 6 of 2,980 rows — **DECIDED 2026-08-13 (D-035, Q4/A4)**

Breaks the load's synthesized `created_at`. All 6 affected rows:
employee legacy ids 4731, 4732, 4772, 4779, 4806, 4808. **Decided**: do
**not** synthesize a year — the one finding of the six where guessing
was explicitly rejected. Preserve these 6 rows in migration
remediation/quarantine output; exclude them from the operational
`leave_balances` load until a correct year is supplied by a human. Full
answer: brief A4.

### G. `employee_shift_assignments.effective_from` zero-dates — 23 of 3,568 rows (0.64%) — **DECIDED 2026-08-13 (D-035, Q5/A5)**

Same defect class as D, on a column `invalid-date-analysis.md` never
checked. **Decided**: same fallback as D — the row's own `created_at`
date, recorded explicitly as a repair. Full answer: brief A5.

### H. 3 `exception_types` rows reference a company that does not exist — **DECIDED 2026-08-13 (D-035, Q6/A6)**

Legacy `exception_types` ids 1, 3, 4 all have `company_id = 19`, which
doesn't exist in this dump. `load_postgres.py`'s own fail-fast guard
caught this correctly — the guard working as designed, not a script
bug. **Decided**: exclude all three from the operational migration;
record the exclusion explicitly in migration reconciliation/remediation
output. Full answer: brief A6.

### Engineering now owed for C–H (decided, not yet built)

D-035's answers converge on **one shared mechanism none of this repo's
tooling has today**: a migration remediation/audit output that records
every repaired, quarantined, or excluded row, referenced by name in
A2/A3/A5/A6 and required by A4's quarantine specifically. Designing and
building it — and the `companies` status-column schema change from
A1 — are real engineering work now unblocked by decision, tracked
alongside the 40 `SCHEDULED` ledger entries (39 original +
`companies.status`) once the remaining `PENDING` gaps below are also
resolved.

### I. `coverage_audit.py` had a real detection blind spot — **fixed 2026-08-13**

Traced precisely in `find_gaps()` (`scripts/etl/coverage_audit.py`): a
column was flagged `UNEXTRACTED_COLUMN` ("no target column") only when
**absent from the SELECT**; flagged `UNLOADED_COLUMN` ("staged but
absent from INSERT") only when **a target column exists**. A column
that is selected, staged, has **no** target column, and is never
inserted fell through both branches, invisible.

**Fixed and self-tested the same day** — a third detection branch,
`UNTARGETED_COLUMN`, was added (covered by a new self-test fixture and
assertion; `--report`'s kind list updated too, since it was separately
hardcoded to the two old kinds and would have kept omitting the new
class from its own output even after detection was fixed). The fix is
general — it runs against every table — so it surfaced **11 real gaps
total**, not just the 7 `employees` columns found by manual inspection:
11 registered `PENDING` at the time of the fix (7 `employees` columns,
plus `attendance`/`advances`/`requests.updated_at`, plus
`companies.status`), 2 registered `ACCEPTED` as clean renames
(`employees.is_active` → target `active`; `requests.approver_id` →
target `approver_membership_id`). Full enumeration and root cause:
`docs/migration/2026-08-13-etl-real-data-findings-decision-brief.md`
§"Finding I".

**Update, same day**: `companies.status` was answered as part of Q1
(D-035, A1 — see finding C above) and moved from `PENDING` to
`SCHEDULED`. **10 gaps remain `PENDING` and undecided**: the 7
`employees` columns (`employee_code`, `country_code`, `national_id`,
`birth_date`, `gender`, `hire_date`, `updated_at`) plus
`attendance.updated_at`, `advances.updated_at`, `requests.updated_at`.
None of these has been put to the repository owner yet.

**Ledger state: `47 gaps — 8 accepted, 39 scheduled, 0 pending` (before
2026-08-13) → `60 gaps — 10 accepted, 40 scheduled, 10 pending a
decision` (current).** `--self-test` and `--check` both pass. **The
pre-2026-08-13 "0 pending" was never an accurate "nothing undecided" —
treat any reference to "47 gaps" or "0 pending" from before this date
as stale.** Per the repository owner's explicit instruction: A/B/J stay
unimplemented and OQ-1/OQ-2/OQ-3 stay unstarted until all 10 of these
are decided and `--check` reports 0 pending.

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

### How this changes items 6–8 — immediate priority order

OQ-1/OQ-2/OQ-3 (below) are about the `COMPANY_ADMIN` identity-minting
layer, which sits *on top of* the base entity load this run just
exercised. Findings C–H land on tables OQ-1–3 don't touch directly, but
the pattern is identical — an abort where a decision belongs,
discovered only by running against real data.

**Status 2026-08-13, later the same day: C–H answered (D-035); 10 of
11 finding-I gaps remain open.**

**Immediate priority, in order:**

1. ~~Resolve findings C–H~~ **Done — D-035, recorded in
   `decision-log.md` and
   `2026-08-13-etl-real-data-findings-decision-brief.md` (A1–A6).**
   **Resolve the remaining 10 `PENDING` coverage-ledger gaps** finding
   I surfaced (7 `employees` columns + 3 `updated_at` columns) — not
   yet put to the repository owner. Nothing below this can be built
   correctly while these are open — a placeholder or default chosen
   without explicit input is exactly the class of silent, undocumented
   judgment call this repo's process exists to prevent. `--check` must
   reach 0 pending.
2. **Fix A, B, and J** — pure tooling bugs, no product decision needed.
   Minimal patches and tests are prepared (not applied) in the decision
   brief's "Prepared fixes" section. **Also now owed by D-035's
   answers**: the shared migration remediation/audit output mechanism
   (referenced by C–H's decisions) and the `companies` status-column
   schema change (finding C / Q1) — real engineering, not yet built.
3. **Re-run the full ETL end to end** against real data once step 1
   reaches 0 pending and step 2 lands, and confirm it completes cleanly
   with no scratch-only workarounds needed this time.
4. **Only then** start OQ-1 → OQ-2 → OQ-3 implementation (items 6–8
   below), in that order.
5. **Then** P1 (items 9–11), **then** the 39 (now 40) `SCHEDULED`
   ledger entries (item 12).

## P0 — Blocks the `COMPANY_ADMIN` minting slice

**Status 2026-08-13: hold — 4th in the priority order above.** C–H are
decided (D-035); 10 `PENDING` gaps from finding I are not. Implementation
on 6–8 waits for step 1 (those 10, reaching 0 pending) and step 2 (the
A/B/J fixes, plus D-035's remediation-output and `companies` schema
work) to land, then step 3 (a clean full-ETL re-run), before starting
here.

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
| 12 | Implement the 40 `SCHEDULED` ledger entries in `coverage_audit.py` (39 original + `companies.status`, D-035) | Each entry cites the decision that settled it; `--self-test` enforces the citation. This is the largest block of remaining work — decided, not built. |

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

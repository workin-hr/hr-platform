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

## P0 — Blocks the `COMPANY_ADMIN` minting slice

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

As of 2026-08-13, the extraction-side probes (P0 items 2–5) have been
run against a real legacy data snapshot (`mysql_workin.data.sql`, dated
2026-08-03) — see the resolution notes above. **The load side is still
untested against real data**: `scripts/etl/README.md` §Status's "no
production data has been moved" remains true — `load_postgres.py` has
only run against fixtures (`EtlLoadFixtureTest`), never against this
dump's ~62K rows end to end. P0 items 6–8 (`COMPANY_ADMIN` minting) are
still fixture-proven, not production-proven. The snapshot itself is also
10 days old at time of writing and point-in-time — re-verify row counts
and probe results against a fresher dump before an actual cutover run,
per the "Findings Requiring A Fresh Production Snapshot" caveat already
carried in `table-volume-analysis.md` and `invalid-date-analysis.md`.

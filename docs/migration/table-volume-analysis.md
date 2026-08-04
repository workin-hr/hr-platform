# Table-Volume Analysis

## Method

Measured directly, 2026-08-04, against the real schema + data dump
loaded into a throwaway Docker MySQL container (see
`docs/migration/data-quality-analysis.md` for the full method note):
`SELECT TABLE_NAME, TABLE_ROWS, DATA_LENGTH+INDEX_LENGTH FROM
information_schema.TABLES` — exact counts for all 41 tables, not
estimates. **Proven from data.**

## Row Count (as of 2026-08-04, source: loaded dump dated 2026-08-03)

| Table | Rows | Storage (KB) |
|---|---:|---:|
| `attendance` | 36,316 | 64.0 |
| `notifications` | 3,589 | 96.0 |
| `employee_shift_assignments` | 3,568 | 64.0 |
| `leave_balance` | 2,980 | 32.0 |
| `employees` | 2,871 | 144.0 |
| `payslips` | 2,836 | 64.0 |
| `salary_contracts` | 2,829 | 32.0 |
| `job_titles` | 1,684 | 48.0 |
| `department_branches` | 1,245 | 48.0 |
| `departments` | 950 | 32.0 |
| `company_setting_values` | 448 | 48.0 |
| `company_settings` | 397 | 48.0 |
| `branches` | 375 | 48.0 |
| `employee_schedules` | 352 | 48.0 |
| `shifts` | 302 | 32.0 |
| `companies` | 269 | 112.0 |
| `requests` | 213 | 48.0 |
| `request_types` | 192 | 32.0 |
| `exception_types` | 111 | 48.0 |
| `setting_allowed_values` | 79 | 48.0 |
| `company_official_holidays` | 69 | 48.0 |
| `payroll_batches` | 61 | 32.0 |
| `otp_codes` | 54 | 16.0 |
| `complaints` | 36 | 48.0 |
| `advances` | 24 | 32.0 |
| `assets` | 20 | 48.0 |
| `penalties` | 15 | 32.0 |
| `administrative_decisions` | 14 | 32.0 |
| `configs` | 14 | 32.0 |
| `workforce_planning` | 12 | 32.0 |
| `company_activities` | 12 | 16.0 |
| `hr_permissions` | 9 | 48.0 |
| `company_sizes` | 8 | 16.0 |
| `setting_definitions` | 5 | 32.0 |
| `company_titles` | 5 | 16.0 |
| `phone_countries` | 4 | 32.0 |
| `faq_items` | 4 | 48.0 |
| `app_content` | 3 | 32.0 |
| `faq_categories` | 3 | 32.0 |
| `banners` | 2 | 16.0 |
| `push_tokens` | 0 | 32.0 |
| `employee_docs` | 0 | 32.0 |

**Total measured**: ~62,300 rows, ~1.5 MB across all tables at this
snapshot — a small dataset overall; the 8.4 MB raw dump size reflects
SQL statement overhead (INSERT syntax, escaping), not actual stored data
volume.

## Growth Rate

Not yet measured — this is a single point-in-time snapshot with no
prior snapshot to diff against. A second snapshot at a later date would
let `attendance`'s growth rate specifically be measured, since it's the
only table expected to grow roughly linearly with ongoing operation
(check-ins accrue daily; most other tables are closer to a fixed
per-company/per-employee configuration size).

## Migration Method Implication

At this volume (~62K rows total, largest single table 36K rows), **a
straightforward bulk-copy migration is well within reach for a single
maintenance window** — none of these tables are large enough to require
chunked/online replication on volume grounds alone. `attendance` at
36,316 rows and 64 KB is trivial to bulk-copy in seconds, not the
multi-hour concern its row count (roughly 10x the next-largest table,
`notifications` at 3,589 — corrects an earlier ~5.5x estimate in
`docs/migration/database-schema-inventory.md` with this exact
measurement) might otherwise suggest at first glance. **Caveat**: this
conclusion is scoped to the current snapshot's volume — if the real
production database has grown substantially since this dump (see
Findings Requiring A Fresh Snapshot), this conclusion needs
re-verification against current volume, not assumed to still hold.

## Estimated Downtime Contribution

Not yet a grounded estimate — genuine cutover downtime depends on the
migration approach chosen (ADR-0004, still `Proposed`), not just data
volume. At this measured volume, raw data transfer time is not expected
to be the dominant factor in any downtime estimate; schema
transformation, validation queries, and cutover orchestration are more
likely cost drivers, none of which are estimated here.

## Findings Requiring A Fresh Production Snapshot

**This is the most important caveat in this document.** The dump this
analysis is based on is dated 2026-08-03 — a single point-in-time
snapshot, not necessarily representative of current production volume.
If real production has been operating and accumulating `attendance`
rows since this dump, actual current volume could be meaningfully
higher. Re-run the exact `information_schema.TABLES` query against a
fresh snapshot immediately before any actual migration-timing decision.

## Operational Assumptions Still Requiring Confirmation

- Whether this dump represents the full current production dataset or
  a partial/older export — not stated anywhere in the dump itself or
  confirmed with a human; the analysis proceeds on the working
  assumption it's representative, flagged here as unconfirmed.

## Evidence

Loaded `mysql_workin.schema.sql` + `mysql_workin.data.sql` into a
throwaway Docker MySQL 8.0 container, ran `information_schema.TABLES`
row-count/size query against all 41 tables, container destroyed after
analysis, 2026-08-04.

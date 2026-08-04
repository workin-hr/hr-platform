# Invalid-Date Analysis

MySQL permits `0000-00-00` and other zero/partial dates that PostgreSQL
rejects. This template tracks where that matters.

## Method

Measured directly, 2026-08-04, against the real schema + data dump
loaded into a throwaway Docker MySQL container (see
`docs/migration/data-quality-analysis.md` for the full method note).
The initial data load itself surfaced this class of issue directly: MySQL
8's default strict mode **rejected** a zero-date row on first import
attempt (`Incorrect date value: '0000-00-00' for column 'hire_date'`),
which had to be worked around with a permissive session `sql_mode` to
load the full dataset for counting. **Proven from data.**

## Table Or Column: `employees.hire_date`

- **Invalid Value Pattern Observed**: `0000-00-00` (MySQL zero-date).
- **Estimated Scope**: **22 of 2,871 rows** (0.77%). A further 35 rows
  (1.2%) are `NULL` (valid, since the column is nullable — not an
  invalid-date issue, recorded here only to separate it from the 22
  genuinely invalid rows).
- **Proposed Handling**: `NULL` is the lowest-risk target — the column
  is already nullable, and `NULL` is the only value that unambiguously
  means "unknown/not recorded" rather than a fabricated real date.
  Provisional pending business confirmation that no downstream logic
  currently treats `0000-00-00` as a meaningful sentinel (e.g. "no hire
  date yet" business logic keyed on the specific zero value rather than
  null-checking).
- **Evidence**: Direct query + the import-time strict-mode rejection,
  2026-08-04.

## Table Or Column: `employees.birth_date`

- **Invalid Value Pattern Observed**: `0000-00-00`.
- **Estimated Scope**: **2 of 2,871 rows** (0.07%) — a much smaller
  count than `hire_date`. 1,104 rows (38.5%) are legitimately `NULL`
  (the column is optional; not an invalid-date finding, noted for
  completeness).
- **Proposed Handling**: Same as `hire_date` — convert to `NULL`,
  pending confirmation nothing downstream keys on the zero value
  specifically.
- **Evidence**: Direct query, 2026-08-04.

## Table Or Column: `salary_contracts.effective_from`

- **Invalid Value Pattern Observed**: `0000-00-00` — **notably, this
  column is `NOT NULL` with no default**, so these 23 rows currently
  hold an invalid placeholder rather than a legitimately absent value.
- **Estimated Scope**: **23 of 2,829 rows** (0.81%).
- **Proposed Handling**: Cannot simply become `NULL` (column is `NOT
  NULL` by design — an effective-from date is presumably meant to
  always exist for an active contract). Needs a business decision: a
  sentinel date (e.g. the contract's `created_at` date, or the
  company's earliest known operational date), or explicit exclusion of
  these 23 rows pending manual correction before migration. **Do not
  default this silently** — it directly affects payroll-period
  calculations that already have known correctness issues
  (`hr-legacy#12`, `#13`).
- **Evidence**: Direct query, 2026-08-04.

## Findings Requiring A Fresh Production Snapshot

Point-in-time snapshot (dump date 2026-08-03). Re-run before actual
migration/cutover to catch any new invalid dates entered since.

## Operational Assumptions Still Requiring Confirmation

- ~~Whether any application logic treats `0000-00-00` as a meaningful
  sentinel~~ — **Resolved 2026-08-04**: a full-text search of `apis/`
  and `dashboard/` for the literal string `0000-00-00` found zero
  matches. No legacy code path compares against or depends on this
  value specifically — `NULL` remediation for `hire_date`/`birth_date`
  is safe with respect to legacy application logic.
- The correct remediation value for the 23 `salary_contracts.effective_from`
  rows — requires business input, cannot be inferred from the data
  (this one is a `NOT NULL` column, so `NULL` isn't an option the way it
  is for the other two).

## Evidence

Loaded `mysql_workin.schema.sql` + `mysql_workin.data.sql` into a
throwaway Docker MySQL 8.0 container, queried directly (including the
import-time strict-mode rejection as direct evidence of the pattern's
existence), container destroyed after analysis, 2026-08-04.

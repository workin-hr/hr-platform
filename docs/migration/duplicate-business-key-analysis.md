# Duplicate Business-Key Analysis

Tracks columns intended to be unique business keys (not necessarily the
primary key) that may contain duplicates due to missing or inconsistently
enforced uniqueness constraints in the legacy schema.

## Method

Measured directly, 2026-08-04, against the real schema + data dump
loaded into a throwaway Docker MySQL container (see
`docs/migration/data-quality-analysis.md` for the full method note).
First confirmed which candidate business keys have **no** DB-level
uniqueness constraint today (`information_schema.STATISTICS`), then
measured real duplicate counts only for those. **Proven from data**,
not inference.

## Table: `job_titles`

- **Intended Business Key Column(s)**: `(company_id, name)` — no unique
  constraint exists in the schema today (confirmed via
  `information_schema.STATISTICS`; the only index on `job_titles` is
  its primary key and the `company_id`/`department_id` foreign keys).
- **Duplicate Condition Observed**: Case-insensitive, trimmed name
  collision within the same company.
- **Estimated Scope**: **147 duplicate groups** (`SELECT company_id,
  LOWER(TRIM(name)), COUNT(*) FROM job_titles GROUP BY company_id,
  LOWER(TRIM(name)) HAVING COUNT(*)>1`) — out of 1,684 total rows. The
  largest table by row count among the four checked here, and the
  largest duplicate count.
- **Proposed Handling**: Provisional — requires business review before
  a dedupe rule is chosen (merge duplicates and reassign
  `employees.job_title_id`, keep-first, or leave distinct on the theory
  that visually-identical names may represent genuinely different roles
  a company chose to create twice). **Do not silently merge during
  migration** — this changes real employee records.
- **Evidence**: Direct query against the loaded dump, 2026-08-04.

## Table: `departments`

- **Intended Business Key Column(s)**: `(company_id, name)` — no unique
  constraint exists.
- **Duplicate Condition Observed**: Same case-insensitive/trimmed
  collision pattern as `job_titles`.
- **Estimated Scope**: **64 duplicate groups**, out of 950 total rows.
- **Proposed Handling**: Provisional, same caveats as `job_titles` —
  business review required before any merge/dedupe decision.
- **Evidence**: Direct query against the loaded dump, 2026-08-04.

## Table: `shifts`

- **Intended Business Key Column(s)**: `(company_id, name)` — no unique
  constraint exists.
- **Duplicate Condition Observed**: Same pattern.
- **Estimated Scope**: **6 duplicate groups**, out of 302 total rows.
- **Proposed Handling**: Provisional — same caveats.
- **Evidence**: Direct query against the loaded dump, 2026-08-04.

## Table: `branches`

- **Intended Business Key Column(s)**: `(company_id, name)` — no unique
  constraint exists.
- **Duplicate Condition Observed**: Same pattern.
- **Estimated Scope**: **1 duplicate group**, out of 375 total rows —
  the smallest of the four, essentially a rounding case rather than a
  systemic issue.
- **Proposed Handling**: Provisional — likely safe to review and merge
  manually given the small count, but still requires business
  confirmation before touching real branch records (branches carry
  `qr_code`/geofence data other rows may reference).
- **Evidence**: Direct query against the loaded dump, 2026-08-04.

## Table: `request_types` (checked, clean)

- **Intended Business Key Column(s)**: `(company_id, name)` — no unique
  constraint exists in the schema.
- **Duplicate Condition Observed**: None.
- **Estimated Scope**: **0 duplicate groups**, out of 192 total rows —
  included here specifically to record that this was checked and found
  clean, not skipped.
- **Evidence**: Direct query against the loaded dump, 2026-08-04.

## Table: `employees.phone` (checked, confirmed clean — DB-enforced)

- **Intended Business Key Column(s)**: `phone`, globally unique across
  the whole platform (not scoped per company) — enforced today by a
  real `UNIQUE` index (confirmed via `information_schema.STATISTICS`).
- **Duplicate Condition Observed**: None possible — the full data
  import (8.4 MB dump) succeeded under this constraint without a single
  violation, which itself proves no duplicates exist in the source data
  (a duplicate would have raised a unique-constraint error during
  import, the same way the initial import raised a real
  foreign-key-order error and a real zero-date error before those were
  worked around).
- **Migration Note**: If the target Postgres schema keeps this
  constraint (recommended — it's already correctly enforced), no
  remediation is needed here. Flagging only so this isn't re-flagged as
  an open question later.
- **Evidence**: Successful data import under `UNIQUE(phone)`, 2026-08-04.

## Findings Requiring A Fresh Production Snapshot

All duplicate-group counts above are point-in-time (dump date
2026-08-03/loaded 2026-08-04) — re-run before any actual migration
dedupe work, since new duplicates can be created between now and
cutover under the current unconstrained schema.

## Operational Assumptions Still Requiring Confirmation

- Whether visually-duplicate `job_titles`/`departments`/`shifts` names
  within the same company represent genuine duplicates (data-entry
  accidents) or intentionally distinct records a business chose to name
  identically — cannot be determined from the data alone.
- Whether the new Postgres schema should add real `UNIQUE(company_id,
  LOWER(name))`-style constraints for these four tables going forward
  (recommended, but a product/engineering decision, not decided here).

## Evidence

Loaded `mysql_workin.schema.sql` + `mysql_workin.data.sql` into a
throwaway Docker MySQL 8.0 container, queried directly, container
destroyed after analysis, 2026-08-04. No raw customer records reproduced
— only aggregate group counts.

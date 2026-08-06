# PMR-10: Migration-Correctness Test Plan And Differential Harness — Design (2026-08-07)

## Purpose And Authority

Closes the mechanism half of PMR-10 (`hr-platform#16`, P0, "blocks
production cutover of any migrated module"): a migration-correctness
test plan and a runnable differential harness. Anchored to ADR-0004
(MySQL→PostgreSQL approach, Accepted), the measured baseline in
`docs/migration/table-volume-analysis.md` (exact row counts, all 41
tables, 2026-08-04), `docs/migration/migration-validation-queries.md`'s
evidence discipline (nothing marked passed without a recorded run), and
PMR-10's own definition, which names **two distinct components**:

1. **Data reconciliation** — proving migrated rows match the legacy
   source (counts, checksums, per-row diffs).
2. **Behavioral differential testing** — proving the new Java
   implementation produces the same outputs as legacy PHP for the same
   inputs, via golden datasets (the payroll three-formula risk,
   `hr-legacy#12`/`#13`).

Assigned by the repository owner 2026-08-07 ("proceed" on the
recommendation naming PMR-10).

## Honest Scope Split

**Shipped now**:

- The test-plan document (`docs/migration/migration-correctness-test-plan.md`)
  covering both components: reconciliation check layers, the canonical
  CSV export convention, the golden-dataset format and
  legacy-output-capture procedure, the cutover run procedure, and how
  each run populates `migration-validation-queries.md`.
- The reconciliation harness (`scripts/migration_diff.py`,
  stdlib-only, same self-testing pattern as the repo's other two
  tools): compares canonical CSV exports from both databases —
  per-table row counts (optionally against a recorded expected
  baseline), whole-table checksums, and key-based row-level diffs that
  name the exact mismatching keys and columns; non-zero exit on any
  mismatch; `--self-test` proves detection on synthetic
  matched/mismatched/missing-row/extra-row/changed-cell samples.

**Explicitly not closable yet (tracked, sequenced, not dropped)**:

- The production-snapshot run — requires the migration itself and a
  fresh snapshot near cutover (PMR-03's timing); this environment
  never touches production data (CLAUDE.md).
- Per-table canonical export SQL — written alongside the actual
  conversion scripts (F-15's data half and the broader ETL), which do
  not exist yet; the plan defines the projection rules they must
  follow.
- The golden-dataset *runner* for payroll behavior — meaningful only
  when the new payroll-calculation service exists; the format and
  capture procedure ship now so legacy outputs can be captured early.

## Design Decisions

- **CSV-pair comparison, not live dual-DB connections**: total
  confirmed volume is ~62K rows across 41 tables — trivially exportable.
  A dependency-free diff over exports keeps the harness runnable on any
  machine (including the operator's, near production) with only Python,
  and makes every run's inputs archivable evidence by construction.
- **Canonical projection convention** (defined in the plan, enforced by
  the harness's strict mode): explicit column list in fixed order; rows
  ordered by the declared key; `NULL` encoded as the literal `\N`
  (distinct from empty string); timestamps normalized to UTC ISO-8601;
  booleans as `0`/`1`; text exported as UTF-8 after the
  charset-normalization step the collation analysis prescribes.
- **Golden datasets** are versioned CSV/JSON pairs (input rows +
  expected outputs) captured from *live legacy behavior* (not from
  reading the PHP, which is exactly what drifted); the plan defines
  capture via the legacy system's own endpoints/DB effects for payroll
  first (`hr-legacy#12`'s daily-wage case is mandatory in the set).

## Testing

`scripts/migration_diff.py --self-test` covers: identical exports pass;
row-count mismatch detected; changed cell detected and named by
key+column; missing/extra key detected; NULL vs empty-string
distinguished; checksum stability across runs. CI: the harness
self-test joins `backend-validate.yml`'s fast pre-Java step alongside
the Flyway check.

## Consequences

PMR-10's gap moves from "nothing exists" to "mechanism exists, proven
on synthetic data; remaining actions are data-bound, not
design-bound": capture payroll golden datasets (with the payroll
module), write per-table export SQL (with the conversion scripts), and
run against a fresh snapshot at cutover — each recorded in
`migration-validation-queries.md` per its evidence discipline.

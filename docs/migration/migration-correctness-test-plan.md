# Migration-Correctness Test Plan

## Purpose

The systematic answer to PMR-10 (`hr-platform#16`): how migration
correctness is verified, with what tooling, against what baselines,
and what counts as evidence. Two components, matching PMR-10's own
definition:

1. **Data reconciliation** — migrated rows match the legacy source.
2. **Behavioral differential testing** — the new Java implementation
   produces the same outputs as legacy PHP for the same inputs, via
   golden datasets (the payroll three-formula risk,
   `hr-legacy#12`/`#13`).

Evidence discipline follows
[`migration-validation-queries.md`](./migration-validation-queries.md):
nothing is marked passed without a recorded run (date, runner,
outcome, artifacts).

## Component 1 — Data Reconciliation

### Check layers

| Layer | What it proves | Tooling |
|---|---|---|
| Row counts | Every table migrated completely; source snapshot matches the recorded baseline ([`table-volume-analysis.md`](./table-volume-analysis.md)'s exact counts, e.g. `attendance` 36,316, `employees` 2,871) | `scripts/migration_diff.py` (counts + `expected_count`) |
| Whole-table checksums | No undetected drift anywhere in a table | `scripts/migration_diff.py` (SHA-256 over canonicalized, key-ordered rows) |
| Key-based row diffs | Every mismatch is named: table, business key, column, both values | `scripts/migration_diff.py` |
| Business invariants | Semantic correctness beyond field equality: zero FK orphans post-migration (cross-checked against [`orphan-reference-analysis.md`](./orphan-reference-analysis.md)'s known pre-existing orphans and their decided dispositions); invalid-date dispositions applied ([`invalid-date-analysis.md`](./invalid-date-analysis.md)); duplicate-business-key dispositions applied ([`duplicate-business-key-analysis.md`](./duplicate-business-key-analysis.md)); **authorization migration**: every legacy `hr_permissions` row maps to exactly the `membership_permission_overrides` ALLOW rows [`authorization-model.md` §7](../architecture/authorization-model.md)'s rule prescribes | SQL checks recorded per run in `migration-validation-queries.md`; the §7 check is expressible as a canonical export pair (legacy matrix expanded by the mapping rule vs. actual override rows) and diffed by the same harness |

### Canonical export convention

Both sides export each compared table with:

- an explicit column list in a fixed, agreed order (never `SELECT *`);
- rows ordered by the declared business key;
- `NULL` encoded as the literal `\N` — distinct from the empty string
  (the harness's self-test proves it treats them as different);
- timestamps normalized to UTC ISO-8601; booleans as `0`/`1`;
- UTF-8 text, after the charset normalization
  [`character-set-and-collation-analysis.md`](./character-set-and-collation-analysis.md)
  prescribes;
- deliberate transformations (renamed columns, split fields, decided
  data-quality dispositions) applied **in the export queries**, so the
  diff compares intended-equal projections and any residual difference
  is a real defect, not noise.

Per-table export SQL is written alongside the conversion scripts
(F-15's data half and the broader ETL) — it does not exist yet, and
writing it now against unfinished transforms would invent
speculation. The convention above is what those queries must satisfy.

### Harness

`scripts/migration_diff.py --source <dir> --target <dir> --manifest <manifest.json>`,
stdlib-only (runs on an operator's machine with no installs), self-
tested in CI (`backend-validate.yml`) across every detection class:
identical-pass, baseline mismatch, count mismatch, changed cell,
missing/extra/duplicate key, `\N`-vs-empty, checksum stability.
Manifest example:

```json
{
  "tables": [
    {"file": "employees.csv", "key": ["id"], "expected_count": 2871},
    {"file": "hr_permissions_overrides.csv", "key": ["membership_id", "permission_key"]}
  ]
}
```

Exit is non-zero on any finding; the printed report plus the archived
export directories are the run's evidence artifacts.

## Component 2 — Behavioral Differential Testing (Golden Datasets)

**What**: versioned input/expected-output datasets, captured from
**live legacy behavior** — not from reading the PHP, which is exactly
where implementation and intent drifted (`hr-legacy#12`/`#13`/`#17`).
The new implementation must reproduce the expected outputs for every
case, as parameterized tests in `backend/`.

**Format**: one directory per behavior under a `golden/` root (created
with the first dataset): `inputs.csv` (one case per row, explicit
columns), `expected.csv` (same keys, expected output columns), and a
`README.md` recording capture date, legacy version/commit, capture
method, and any anonymization applied. **No production personal data
is ever committed** — captured cases are anonymized to synthetic
identifiers before entering the repository (CLAUDE.md boundary).

**First target — payroll calculation** (mandatory before the payroll
module is implementation-complete):

- Cases spanning both salary modes; the daily-wage base-pay case is
  mandatory (`hr-legacy#12` — the case legacy silently gets wrong;
  the expected value is the **correct** total, recorded as such, with
  legacy's actual buggy output noted alongside so the dataset
  documents the deliberate behavior change rather than hiding it).
- Divergence cases among legacy's three payslip-total implementations
  (`hr-legacy#13`): capture all three outputs; product/engineering
  record which is canonical per case before the new module ships.

**Capture procedure**: run the legacy system (staging copy or
snapshot-restored instance — never live production) with the chosen
inputs via its real endpoints; record produced outputs and DB effects;
anonymize; commit with the README. The runner harness (parameterized
JUnit over the CSVs) ships with the payroll module.

## Cutover Run Procedure

1. Fresh snapshot per PMR-03 (operator action, near cutover).
2. Verify the source-side export counts against
   `table-volume-analysis.md`'s baseline **updated to that snapshot**
   (the recorded 2026-08-04 counts will have drifted with normal use —
   re-measure, record, then use as `expected_count`).
3. Run the migration; export both sides per the convention; run the
   harness; archive report + exports.
4. Run the business-invariant SQL set; record each in
   `migration-validation-queries.md` with date/runner/outcome.
5. Any finding blocks cutover of the affected module until dispositioned
   in writing.

## Explicitly Remaining (Tracked, Data- Or Module-Bound)

- Per-table export SQL — with the conversion scripts (F-15 data half).
- Payroll golden-dataset capture — with the payroll module; needs
  legacy staging access.
- The production-snapshot run — cutover-time, operator-performed.

## Evidence

`scripts/migration_diff.py` (self-tests run in CI);
`hr-platform#16`; `docs/migration/table-volume-analysis.md`;
`docs/migration/migration-validation-queries.md`;
`docs/superpowers/specs/2026-08-07-pmr10-migration-correctness-design.md`.

# Migration Validation Queries

Tracks the reconciliation queries that will confirm a migrated table or
object matches its MySQL source (row counts, checksums, spot-checks on
computed columns). This template stores query intent and evidence, not
speculative results — no query should be marked passed without an actual
run recorded.

## Status (updated 2026-08-07)

Still correctly empty of *results* — reconciliation can only be run
against an actual migrated target, which does not exist yet. What
changed 2026-08-07: the tooling and plan now exist —
[`migration-correctness-test-plan.md`](./migration-correctness-test-plan.md)
defines the check layers, canonical export convention, and cutover run
procedure, and `scripts/migration_diff.py` (self-tested in CI) is the
harness each run's count/checksum/row-diff evidence comes from. The
`table-volume-analysis.md` baseline (all 41 tables, measured
2026-08-04) remains the reference counts — to be re-measured against
the fresh cutover snapshot per the plan's run procedure. The "Last Run
Result" discipline below is unchanged: nothing is marked passed
without an actual recorded run.

## Table Or Object

Record the table, view, routine output, or derived dataset being validated.

## Validation Query Intent (row count parity, checksum, sampled diff)

Describe what the validation is trying to prove and why that proof matters.

## Acceptance Threshold

State the pass rule. If an exact threshold is not yet known, record the kind
of threshold needed rather than inventing one.

## Last Run Result

Do not mark this as passed until an actual run is recorded with date, runner,
and outcome.

## Evidence

Link the query text, run output, reconciliation note, or comparison artifact.

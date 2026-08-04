# Migration Validation Queries

Tracks the reconciliation queries that will confirm a migrated table or
object matches its MySQL source (row counts, checksums, spot-checks on
computed columns). This template stores query intent and evidence, not
speculative results — no query should be marked passed without an actual
run recorded.

## Status (2026-08-04)

Still correctly empty of results — validation queries can only be *run*
against an actual migrated target, which does not exist yet (no backend
implementation has started, per this repository's `CLAUDE.md`
boundary). This is a dependency, not neglect. What **is** now available
from `docs/migration/table-volume-analysis.md`'s exact row counts (all
41 tables, measured 2026-08-04) is the baseline every post-migration row
count should reconcile against — e.g. `attendance` should show exactly
36,316 rows post-migration against this snapshot, `employees` exactly
2,871, etc. The query intent below can be prepared now; only the "Last
Run Result" column requires an actual migrated target to fill in.

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

# Migration Validation Queries

Tracks the reconciliation queries that will confirm a migrated table or
object matches its MySQL source (row counts, checksums, spot-checks on
computed columns). This template stores query intent and evidence, not
speculative results — no query should be marked passed without an actual
run recorded.

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

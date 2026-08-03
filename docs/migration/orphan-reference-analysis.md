# Orphan-Reference Analysis

Tracks foreign-key-shaped relationships that MySQL did not enforce (or that
were enforced inconsistently) and that PostgreSQL's stricter constraint
model may reject on migration.

## Child Table / Column

Record the referencing table and column.

## Referenced Parent Table

Record the intended parent table or key target.

## Orphan Condition Observed

Describe how the orphan condition appears, such as missing parent row,
inconsistent key format, or null-like placeholder.

## Estimated Scope

Only record measured scope. Otherwise mark it `Not yet measured`.

## Proposed Handling (backfill, nullify, reject row, application fix)

Capture the current best remediation approach, but keep it provisional until
business and migration review agree.

## Evidence

Link the orphan-detection query, sample rows, or operational notes proving the
condition exists.

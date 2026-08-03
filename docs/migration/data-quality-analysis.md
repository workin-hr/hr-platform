# Data-Quality Analysis

## Table Or Column

Record the table or column where the quality issue appears.

## Quality Issue Class (nulls, inconsistent formats, referential drift, etc.)

Describe the issue category precisely enough that the team can measure it.

## Detection Method

Record how the issue is detected, such as query, sampled inspection, support
finding, application logic review, or migration dry run.

## Estimated Scope (row count, percentage — only once measured, not assumed)

Leave this blank or mark it `Not yet measured` until an actual measurement
exists.

## Migration Impact

Describe how the issue could affect schema conversion, data loading,
constraints, downstream behavior, or customer-visible correctness.

## Evidence

Link the query, sample rows, incident note, or discovery artifact proving the
issue exists.

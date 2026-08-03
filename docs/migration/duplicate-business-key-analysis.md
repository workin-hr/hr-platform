# Duplicate Business-Key Analysis

Tracks columns intended to be unique business keys (not necessarily the
primary key) that may contain duplicates due to missing or inconsistently
enforced uniqueness constraints in the legacy schema.

## Table

Record the affected table.

## Intended Business Key Column(s)

List the column or column set that appears intended to be unique.

## Duplicate Condition Observed

Describe how duplication is detected and what pattern is present.

## Estimated Scope

Only record a measured count or percentage. Otherwise mark it `Not yet
measured`.

## Proposed Handling (dedupe rule, manual review, keep first/last)

Capture the candidate handling approach, but keep it provisional until the
business owner accepts the rule.

## Evidence

Link the query, sample records, or incident/support evidence that proves the
duplicate condition.

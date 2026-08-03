# Invalid-Date Analysis

MySQL permits `0000-00-00` and other zero/partial dates that PostgreSQL
rejects. This template tracks where that matters.

## Table Or Column

Record the table or column where invalid or MySQL-tolerated date values
appear.

## Invalid Value Pattern Observed

Describe the exact pattern, such as `0000-00-00`, partial dates, impossible
defaults, or mixed text/date storage.

## Estimated Scope

Leave this blank or mark it `Not yet measured` until the scope has been
counted.

## Proposed Handling (NULL, sentinel date, application fix)

Capture the current best remediation hypothesis, but do not treat it as final
without business review.

## Evidence

Link the query, sampled rows, schema default, or application behavior proving
the issue exists.

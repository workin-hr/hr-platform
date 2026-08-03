# Character-Set And Collation Analysis

## Table Or Column

Record the table or column whose character set or collation behavior may
matter during migration.

## Current MySQL Charset/Collation

Capture the exact MySQL setting when known. If the value is inferred from a
database default rather than measured directly, mark that clearly.

## Target PostgreSQL Encoding/Collation

Record the intended PostgreSQL equivalent or note that it is `Not yet
discovered`.

## Known Sorting Or Comparison Risk

Describe the observable risk, such as case-sensitivity drift, accent sorting,
whitespace handling, uniqueness changes, or locale-specific comparison
behavior.

## Evidence

Link schema inspection, query output, sample data behavior, or business-rule
evidence that shows why the entry matters.

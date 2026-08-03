# Sequence And Identity Mapping

Tracks how MySQL `AUTO_INCREMENT` columns map to PostgreSQL sequences /
identity columns, including current high-water marks that must be
preserved across cutover.

## Table / Column

Record the auto-incremented table and column pair.

## Current AUTO_INCREMENT Value

Only record measured values from schema or query output. Do not estimate.

## Target PostgreSQL Approach (`GENERATED ALWAYS AS IDENTITY`, sequence, etc.)

Capture the current best mapping strategy and any reason it may need to differ
by table.

## Cross-System Consistency Risk (if dual-write during cutover)

Describe the risk of drift, collision, or misaligned sequence state if more
than one system writes during cutover.

## Evidence

Link the schema definition, current sequence state query, or cutover planning
evidence.

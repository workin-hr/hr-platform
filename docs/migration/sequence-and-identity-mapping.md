# Sequence And Identity Mapping

Tracks how MySQL `AUTO_INCREMENT` columns map to PostgreSQL sequences /
identity columns, including current high-water marks that must be
preserved across cutover.

## Table / Column

## Current AUTO_INCREMENT Value

## Target PostgreSQL Approach (`GENERATED ALWAYS AS IDENTITY`, sequence, etc.)

## Cross-System Consistency Risk (if dual-write during cutover)

## Evidence

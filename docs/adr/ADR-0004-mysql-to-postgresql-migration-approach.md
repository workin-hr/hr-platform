# ADR-0004 MySQL-To-PostgreSQL Migration Approach

## Status

Proposed

## Context

The current production database is MySQL, while the target direction is PostgreSQL.

## Proposed Direction

Establish a discovery-led migration strategy based on schema, procedure, trigger, performance, and rollback evidence before deciding the migration pattern.

## Consequences

- avoids naive schema translation assumptions
- requires discovery effort before planning cutover
- keeps data-risk visibility early

## Alternatives Considered

- one-step direct migration with limited discovery
- long-term dual-database strategy

## Open Questions

- which MySQL features are hardest to migrate
- what rollback and reconciliation model is acceptable

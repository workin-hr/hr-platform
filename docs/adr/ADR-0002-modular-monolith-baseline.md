# ADR-0002 Modular Monolith Baseline

## Status

Proposed

## Context

The target system needs a credible delivery path for an MVP in roughly two months without unnecessary operational complexity.

## Proposed Direction

Use a modular monolith as the initial architecture assumption until evidence proves a need for a more distributed model.

## Consequences

- simplifies delivery and operations during MVP
- keeps module boundary work inside one deployment unit initially
- requires disciplined internal boundaries to avoid accidental monolith sprawl

## Alternatives Considered

- microservices from the start
- layered monolith without explicit modular boundaries

## Open Questions

- which domains become modules first
- what measurable threshold would justify decomposition

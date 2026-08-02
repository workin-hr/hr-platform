# ADR-0001 Repository Strategy

## Status

Proposed

## Context

The project needs a repository structure that supports planning, discovery, documentation, governance, and later implementation without prematurely collapsing legacy and Flutter code into one repository.

## Proposed Direction

Keep `hr-platform` as the new repository for bootstrap and future implementation, while `hr-legacy` and Flutter remain separate until discovery justifies any change.

## Consequences

- preserves clear boundaries during Phase 0
- reduces premature migration coupling
- requires explicit compatibility and discovery workflows

## Alternatives Considered

- immediate monorepo including legacy and Flutter
- separate repositories for every future component from day one

## Open Questions

- whether Flutter should remain permanently separate
- whether future repository boundaries should change after discovery

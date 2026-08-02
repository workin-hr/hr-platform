# ADR-0005 Authentication And Authorization Direction

## Status

Proposed

## Context

The future system must support admin and employee use cases with tenant isolation and security boundaries.

## Proposed Direction

Document candidate authentication and authorization directions only after legacy, tenant, and integration constraints are understood.

## Consequences

- avoids premature identity design
- keeps tenant isolation visible
- delays irreversible security choices until evidence exists

## Alternatives Considered

- retain unknown legacy behavior without analysis
- finalize security direction during bootstrap

## Open Questions

- current identity flows and token handling
- external identity provider constraints

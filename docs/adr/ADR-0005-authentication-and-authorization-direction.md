# ADR-0005: Authentication And Authorization Direction

## Metadata

| Field | Value |
|---|---|
| ADR ID | ADR-0005 |
| Title | Authentication And Authorization Direction |
| Status | Proposed |
| Date | 2026-08-02 |
| Owners | Solution Architect |
| Deciders | Human engineering leadership — recorded at approval time in `docs/bootstrap/decision-log.md` |
| Related Issues | None yet |
| Supersedes | None |
| Superseded By | None |

## Context

The future system must support admin and employee use cases with tenant isolation and security boundaries.

## Decision

**Approval status: Proposed — this decision has not been approved.**

Document candidate authentication and authorization directions only after legacy, tenant, and integration constraints are understood.

## Alternatives Considered

- retain unknown legacy behavior without analysis
- finalize security direction during bootstrap

## Consequences

- avoids premature identity design
- keeps tenant isolation visible
- delays irreversible security choices until evidence exists

## Risks

- unknown legacy identity/session behavior could be broken by an early authentication redesign
- delaying this decision too long could block dependent architecture decisions (e.g. API versioning, multi-tenant data isolation) if not tracked as an open dependency

## Validation Evidence

None yet — pending Discovery. Requires legacy business-rule extraction (`docs/legacy/business-rule-extraction.md`) and production-behavior evidence (`docs/legacy/production-behavior-evidence.md`) covering current identity flows before this decision can move to Accepted.

## Open Questions

- current identity flows and token handling
- external identity provider constraints

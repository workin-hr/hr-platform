# ADR-0003: API Versioning And Flutter Compatibility

## Metadata

| Field | Value |
|---|---|
| ADR ID | ADR-0003 |
| Title | API Versioning And Flutter Compatibility |
| Status | Proposed |
| Date | 2026-08-02 |
| Owners | Solution Architect, Product Discovery Analyst |
| Deciders | Human engineering leadership — recorded at approval time in `docs/bootstrap/decision-log.md` |
| Related Issues | None yet |
| Supersedes | None |
| Superseded By | None |

## Context

Flutter mobile and desktop clients already exist and depend on current request, response, and error behavior.

## Decision

**Approval status: Proposed — this decision has not been approved.**

Define an evidence-backed compatibility and versioning strategy before target API implementation begins.

## Alternatives Considered

- break compatibility immediately
- rely on undocumented client assumptions

## Consequences

- reduces client breakage risk
- requires endpoint and behavior inventory first
- may constrain early API design choices

## Risks

- undetected strict-parsing assumptions in the Flutter clients could cause silent breakage if compatibility work proceeds without evidence
- versioning strategy chosen too early could be incompatible with real client behavior discovered later

## Validation Evidence

None yet — pending Discovery. Requires the existing endpoint inventory (`docs/api/existing-endpoint-inventory.md`) and Flutter request/response compatibility analysis (`docs/api/flutter-request-response-compatibility.md`) to be populated before this decision can move to Accepted.

## Open Questions

- whether existing clients use strict or tolerant parsing
- what versioning strategy best fits the current client landscape

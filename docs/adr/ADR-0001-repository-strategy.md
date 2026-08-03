# ADR-0001: Repository Strategy

## Metadata

| Field | Value |
|---|---|
| ADR ID | ADR-0001 |
| Title | Repository Strategy |
| Status | Proposed |
| Date | 2026-08-02 |
| Owners | Platform engineering leadership (see CODEOWNERS `@workin-hr/platform-owners`) |
| Deciders | Human engineering leadership — recorded at approval time in `docs/bootstrap/decision-log.md` |
| Related Issues | None yet |
| Supersedes | None |
| Superseded By | None |

## Context

The project needs a repository structure that supports planning, discovery, documentation, governance, and later implementation without prematurely collapsing legacy and Flutter code into one repository.

## Decision

**Approval status: Proposed — this decision has not been approved.**

Keep `hr-platform` as the new repository for bootstrap and future implementation, while `hr-legacy` and Flutter remain separate until discovery justifies any change.

## Alternatives Considered

- immediate monorepo including legacy and Flutter
- separate repositories for every future component from day one

## Consequences

- preserves clear boundaries during Phase 0
- reduces premature migration coupling
- requires explicit compatibility and discovery workflows

## Risks

- repository sprawl if boundaries are not revisited after discovery
- coordination overhead across repositories for changes that span legacy, Flutter, and this repository until a clearer integration contract exists

## Validation Evidence

None yet — pending Discovery. This decision should be revisited with evidence from legacy PHP discovery (`docs/legacy/`) and Flutter compatibility discovery (`docs/api/`) before it can move to Accepted.

## Open Questions

- whether Flutter should remain permanently separate
- whether future repository boundaries should change after discovery

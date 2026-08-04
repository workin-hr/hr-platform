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
| Related Issues | `hr-legacy#7`, `hr-legacy#15`, `hr-platform#18` |
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

**Update 2026-08-04**: the Discovery this ADR was waiting on now exists.
`docs/legacy/business-rule-extraction.md` and
`docs/security/threat-model.md` cover current server-side identity flows
(10-year JWT, no company-admin revocation — `hr-legacy#7`; mobile logout
silently deactivating the account — `hr-legacy#15`).
`docs/api/flutter-request-response-compatibility.md` (Session/Token
Lifecycle section) adds the client-side half: plain `SharedPreferences`
token storage and no refresh capability in either Flutter client
(`hr-platform#18`). A candidate remediation direction — not yet
approved — is recorded in
`docs/security/authentication-remediation-design.md`. This ADR can move
toward `Accepted` once a human reviews that design and the decisions it
leaves open (token lifetimes, backward-compatibility approach for
existing users, whether multi-session support is wanted) are resolved.

## Open Questions

- Which backward-compatible migration approach for existing long-lived
  tokens (dual-validation, forced re-authentication, or staggered
  rollout — see `docs/security/authentication-remediation-design.md`)
  is acceptable given real support-load constraints.
- External identity provider constraints (unchanged from original
  framing — not yet investigated).
- Access/refresh token lifetime values and whether multiple simultaneous
  sessions per identity are desired going forward.

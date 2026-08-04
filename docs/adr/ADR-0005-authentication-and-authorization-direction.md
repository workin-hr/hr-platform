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

### Classification (2026-08-04 revision)

**Can be accepted now.** Both halves of this ADR's original open
questions are resolved with real evidence and an explicit product
decision: the *problem* (10-year JWT, no revocation, plaintext client
storage, no refresh capability) is fully documented with direct code
evidence; the *direction* (short-lived access token + rotating refresh
token, server-side revocation, `flutter_secure_storage` client storage,
forced re-authentication for existing users, no Keycloak/external IdP)
is a confirmed decision, not a hypothesis — see
`docs/security/authentication-remediation-design.md` for the full
design. This does not depend on the technical spike (H3's Keycloak
comparison arm was dropped specifically because this direction is
already decided) or on production/device access. Recommend a human
decider move `Status` to `Accepted` now if they agree with the recorded
direction; remaining open items below are refinements, not blockers to
acceptance.

## Open Questions

- ~~Which backward-compatible migration approach... is acceptable~~ —
  **Resolved 2026-08-04**: forced re-authentication, confirmed by
  product owner. See `docs/security/authentication-remediation-design.md`.
- External identity provider constraints — resolved by direction, not
  by investigation: the decision is to **not** use an external IdP
  (Keycloak or otherwise) for this MVP; self-managed JWT + refresh is
  the chosen approach.
- Access/refresh token lifetime values and whether multiple simultaneous
  sessions per identity are desired going forward — still genuinely
  open, a product/security trade-off not yet decided.

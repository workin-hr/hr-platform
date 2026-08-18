# ADR-0005: Authentication Direction

## Metadata

| Field | Value |
|---|---|
| ADR ID | ADR-0005 |
| Title | Authentication Direction |
| Status | Accepted |
| Date | 2026-08-02 (renamed and rewritten 2026-08-04, accepted 2026-08-04 — see `docs/bootstrap/decision-log.md` D-017; **survives the 2026-08-16 strategy reset as a recorded exception — see "Phase 1 Status" below**) |
| Owners | Solution Architect |
| Deciders | Human engineering leadership — recorded at approval time in `docs/bootstrap/decision-log.md` |
| Related Issues | `hr-legacy#7`, `hr-legacy#15`, `hr-platform#18` |
| Supersedes | None |
| Superseded By | None |

## Phase 1 Status (2026-08-16)

ADR-0011's strategy reset commits Phase 1 to **strict legacy API contract
parity**, which on its face contradicts this ADR: legacy issues one 10-year JWT
with the role in the token, and this ADR replaced that with short-lived access
tokens plus refresh-token rotation.

**This ADR's token model stands.** The repository owner resolved the conflict on
2026-08-16 (`docs/bootstrap/decision-log.md` D-042, ADR-0011's "Recorded
exception"): parity governs **storage and business behaviour**, not the session
mechanism, because *the goal is storage and business-contract parity, not
reproducing a known weak security posture*.

What that means concretely for Phase 1:

- **Preserved** — legacy's login *semantics* and their API-visible outcomes,
  including **409 `MULTIPLE_ACCOUNTS_SAME_PHONE`**, the single-`pending`-account
  login path, and the three distinct 403 outcomes below it
  (`hr-legacy/apis/api/auth/login_employee.php:70-107`). These are business
  rules, and Phase 1 reproduces them exactly.
- **Not preserved** — the 10-year lifetime and the absence of revocation
  (`hr-legacy#7`). Reintroducing those would ship a known vulnerability to
  satisfy a parity rule aimed at a different concern.
- **Not introduced** — tenant switching. Removing the 409 requires the
  multi-tenant identity model, which is Phase 3 (ADR-0011). Phase 1 keeps
  legacy's one-employee-row-per-session shape.

This is the read/write split ADR-0011 applies to data, applied to security:
tolerate what legacy produced, decline to keep producing it. **It is a
client-visible break** — the Flutter clients must adopt refresh — and it is the
one deliberate divergence from strict contract parity in Phase 1. Any further
divergence needs its own decision rather than citing this precedent.

The Open Questions below about refresh lifetime and simultaneous sessions remain
open and are unaffected.

## Scope Correction (2026-08-04)

This ADR previously covered both authentication and authorization under
one title and one Decision. That conflated two different concerns: how
a caller proves who they are (authentication — this ADR) versus what
that caller is allowed to do once identified (authorization — the model
of platform-admin/tenant-admin/employee scopes, tenant-membership
validation, roles/permissions, and enforcement boundaries). Renamed from
"Authentication And Authorization Direction" to **"Authentication
Direction"**; the authorization model is now its own document,
`docs/adr/ADR-0010-authorization-model.md` (Accepted 2026-08-05),
resolved separately from this ADR — this ADR's own scope remains
authentication only.

## Context

The future system must support admin and employee use cases with tenant isolation and security boundaries.

`hr-legacy`'s current authentication has confirmed, direct-evidence
problems: JWTs valid for 10 years with no company-admin revocation
mechanism (`hr-legacy#7`); mobile logout silently deactivating the
employee's account rather than ending the session (`hr-legacy#15`);
both real Flutter clients (`workin_mobile`, `workin_desktop`) store the
session token in plain, unencrypted `SharedPreferences` with no
token-refresh code path at all (`hr-platform#18`,
`docs/api/flutter-request-response-compatibility.md`).

## Decision

**Accepted 2026-08-04** (`docs/bootstrap/decision-log.md` D-017).

**This section previously read "Document candidate authentication and
authorization directions only after legacy, tenant, and integration
constraints are understood" — a Discovery-stage placeholder, not an
actual decision. That Discovery is now complete (see Validation
Evidence); the placeholder is replaced below with the real, confirmed
direction, which the repository owner has accepted.**

The new system's authentication direction is:

- **Self-managed JWT authentication for the MVP** — no external
  identity provider.
- **Short-lived access tokens** — minutes-to-hours, not `hr-legacy`'s
  10-year lifetime. Exact duration is an open refinement (see Open
  Questions), not a blocker to accepting this direction.
- **Rotating refresh tokens** — opaque, server-side-tracked tokens
  (not a second JWT), rotated on every use; reuse of a rotated-out
  refresh token is treated as a compromise signal and revokes the
  entire token family.
- **Server-side refresh-session persistence and revocation** — refresh
  tokens are recorded server-side (not purely client-held), so
  individual sessions can be looked up, listed, and revoked
  individually. Logout and password change/reset revoke the relevant
  session(s) — closing the gap where `hr-legacy` password resets never
  invalidate existing sessions.
- **Secure client storage using `flutter_secure_storage`** — replacing
  both real clients' current plain `SharedPreferences` token storage
  (Android Keystore/iOS Keychain-backed on mobile; platform credential
  stores on desktop). This is a scoped, narrow exception to the
  standing "don't change the Flutter apps" direction, limited to the
  auth-handling layer specifically.
- **Forced re-authentication for all existing users during migration**
  — existing `hr-legacy`-issued JWTs are not migrated or dual-validated
  against the new backend; every user logs in again once, using their
  existing (migrated) credentials. No dual-validation window.
- **No Keycloak or other external identity provider for the MVP** — the
  spike's original Keycloak-comparison experiment is dropped, not
  deferred; this direction is decided.

**Explicitly kept open, not blocking acceptance of the direction above**:
exact access-token lifetime, exact refresh-token lifetime, and whether
multiple simultaneous sessions per identity are supported going
forward. These are refinements within the decided direction — resolving
them later does not require revisiting whether the direction itself is
right.

Full design detail (revocation/cutover mechanics, user communication,
client handling of expired legacy tokens, sequencing of server vs.
client changes, rollback implications): `docs/security/authentication-remediation-design.md`.

## Alternatives Considered

- retain unknown legacy behavior without analysis
- finalize security direction during bootstrap
- external identity provider (Keycloak) — evaluated conceptually via
  the technical-spike plan's original H3 hypothesis; dropped from scope
  once self-managed JWT was confirmed as the direction, not because
  Keycloak was hands-on tested and rejected
- dual-validation of legacy and new tokens during a transition window —
  considered in `docs/security/authentication-remediation-design.md`,
  rejected in favor of forced re-authentication for lower implementation
  risk in the system's most security-sensitive subsystem

## Consequences

- avoids premature identity design
- keeps tenant isolation visible
- **Superseded by the decision above**: this ADR no longer delays
  irreversible security choices — the choices are now made, informed by
  real evidence gathered in Discovery.
- Requires a scoped client-side change to both Flutter clients (secure
  storage + refresh-capable networking) — a narrow, tracked exception to
  "don't change the Flutter apps," not a broader precedent.
- Forces every existing user to re-authenticate once at cutover — a
  real, planned support-load event, not an accidental side effect (see
  `docs/migration/cutover-and-rollback-assumptions.md`).

## Risks

- unknown legacy identity/session behavior could be broken by an early authentication redesign — mitigated: the redesign is now informed by direct code evidence, not guesswork
- delaying this decision too long could block dependent architecture decisions (e.g. API versioning, multi-tenant data isolation) if not tracked as an open dependency
- forcing re-authentication risks a support-load spike at cutover — see `docs/security/authentication-remediation-design.md` and `docs/migration/cutover-and-rollback-assumptions.md` for the communication/rollback plan this risk requires

## Validation Evidence

The Discovery this ADR was originally waiting on is complete.
`docs/legacy/business-rule-extraction.md` and
`docs/security/threat-model.md` cover current server-side identity flows
(10-year JWT, no company-admin revocation — `hr-legacy#7`; mobile logout
silently deactivating the account — `hr-legacy#15`).
`docs/api/flutter-request-response-compatibility.md` (Session/Token
Lifecycle section) adds the client-side half: plain `SharedPreferences`
token storage and no refresh capability in either Flutter client
(`hr-platform#18`). The confirmed direction is recorded in full in
`docs/security/authentication-remediation-design.md`.

### Classification (2026-08-04 revision) — Accepted

**Accepted 2026-08-04**, once the Decision section stated the real
direction, not before. The previous revision of this document
recommended immediate acceptance while the Decision section still held
Discovery-stage placeholder text — that was premature, corrected first,
then accepted by the repository owner
(`docs/bootstrap/decision-log.md` D-017). The three explicitly-scoped
open refinements (exact lifetimes, multi-session policy) remain open
but do not un-do this acceptance — they are refinements within the
accepted direction, not conditions on it.

## Open Questions

- Exact access-token lifetime — a product/security trade-off (UX
  friction vs. exposure-window size), explicitly not a blocker to
  accepting the direction.
- Exact refresh-token lifetime — same category.
- Whether multiple simultaneous sessions per identity are desired going
  forward, or whether the current single-active-session behavior should
  be preserved intentionally — a product decision, not a blocker.
- Exact cutover date/communication lead time for the forced
  re-authentication event — depends on overall migration sequencing
  (`hr-platform#15`, PMR-09), not on this ADR.
- **Moved to `docs/adr/ADR-0010-authorization-model.md`**: everything
  about what an authenticated caller is allowed to do — role/permission
  model, tenant-membership validation, enforcement boundaries, and
  whether authorization data lives in the JWT or is loaded server-side.
  Not resolved here.

## Evidence

`hr-legacy#7`, `hr-legacy#15`, `hr-platform#18`;
`docs/api/flutter-request-response-compatibility.md` ("Session/Token
Lifecycle" section); `docs/security/threat-model.md`;
`docs/security/authentication-remediation-design.md` (the full design
this ADR's Decision section summarizes); direct product-owner decision,
this conversation, 2026-08-04 (forced re-authentication, no external
IdP, `flutter_secure_storage`).

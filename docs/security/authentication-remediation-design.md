# Authentication Remediation Design

## Purpose And Boundary

This is a **design and migration plan only**. It does not implement
anything, and per this repository's [`CLAUDE.md`](../../CLAUDE.md)
boundary, no code from this document should be written until it is
reviewed, an owning ADR (`ADR-0005`) moves from `Proposed` to `Accepted`
referencing it, and a human explicitly assigns implementation work.

## Problem Statement

Direct code reads of `hr-legacy` and both real Flutter clients establish
six compounding problems with today's authentication model, spanning both
server and client:

| # | Problem | Confirmed By |
|---|---|---|
| 1 | JWT tokens are valid for 10 years (`JWT_EXPIRE_HOURS=87600`) | `hr-legacy#7` |
| 2 | Company-admin tokens have no revocation mechanism at all; employee `token_version` is bumped only on fresh login, never on password change/reset | `hr-legacy#7` |
| 3 | Both Flutter clients store the JWT in plain, unencrypted `SharedPreferences` — no `flutter_secure_storage`, no platform Keychain/Keystore | `hr-platform#18`, `docs/api/flutter-request-response-compatibility.md` |
| 4 | Neither Flutter client has any token-refresh code path — only a reactive handler for an outright `401` | `hr-platform#18` |
| 5 | There is no concept of session/device management anywhere in the system — one active token per identity, silently replaced on new login, with no visibility or control for the user | Same as #1/#2, cross-referenced against `docs/api/flutter-request-response-compatibility.md`'s `_forceLogoutOnReplacedSession()` finding |
| 6 | Both Flutter clients are fixed for this migration (per current direction) — any server-side change to token lifetime or shape must work with, or be shipped alongside, a corresponding client change, since the clients cannot silently adapt on their own | `docs/api/flutter-request-response-compatibility.md` |

These six problems must be designed together, not independently, because
fixes to some (shortening token lifetime) actively break others (clients
have no refresh capability) unless sequenced correctly. That sequencing
is the core contribution of this document.

## Design Principles

1. **No design element here assumes the Flutter clients can be changed
   freely or quickly.** Per current direction, `workin_mobile` and
   `workin_desktop` product behavior is not changing as part of this
   migration effort — but their *auth-handling internals* are a
   necessary exception, since problems #3 and #4 above are unfixable
   without touching client code. This is called out explicitly wherever
   it applies below; nothing else in either client should be touched.
2. **Backward compatibility for existing sessions is a hard requirement**,
   not an aspiration — there are real users with real, currently-valid
   10-year tokens. A design that invalidates them all on cutover day
   forces every existing user to re-authenticate simultaneously, which is
   an operational and support-load risk that needs an explicit decision,
   not a default.
3. **Server and client changes must be sequenced, not simultaneous**,
   because a client release (app store review, desktop installer
   distribution via the existing forced-update mechanism, `hr-platform#21`)
   is slower and less controllable than a backend deploy.

## Target Design

### 1. Token lifetime and shape

Replace the single 10-year JWT with a **short-lived access token + longer-lived
refresh token** pair — the standard OAuth2-adjacent pattern:

- Access token: short-lived (candidate range 15 minutes–2 hours; exact
  value is a product/security trade-off between UX friction and
  exposure-window size, not decided here).
- Refresh token: longer-lived (candidate range 30–90 days), **opaque**
  (a random token stored server-side in a `refresh_tokens` table, not a
  second JWT), so it can be looked up, listed, and revoked individually —
  a self-contained JWT refresh token cannot be revoked without a
  separate blocklist, which reintroduces problem #2 in a new form.

### 2. Refresh-token rotation

Each use of a refresh token issues a new refresh token and invalidates
the old one (rotation). This bounds the damage of a leaked refresh token
(single latent value) with a detectable reuse signal: **if a rotated-out
refresh token is presented again, treat it as a compromise signal and
revoke the entire token family** (all descendants of that refresh token),
forcing re-authentication. This is a widely-used pattern precisely because
it turns "was this token stolen" from an unanswerable question into a
detectable event.

### 3. Revocation and logout

- **Logout** deletes the specific refresh token (and its access token, if
  server-side access-token tracking is added — see Session/Device
  Management below) rather than only clearing client-side storage. Today,
  "logout" on mobile has no revocation concept at all — it doesn't
  invalidate anything server-side (worth noting: mobile's actual current
  logout has a **separate, higher-severity bug**, `hr-legacy#15`, where it
  silently deactivates the whole account — that bug must be fixed as part
  of this redesign, not carried forward).
- **Password change/reset** must revoke all existing refresh tokens for
  that identity, forcing re-authentication everywhere — this closes
  problem #2's second half (today, password reset doesn't invalidate
  existing sessions at all).
- **Company-admin revocation**: extend whatever revocation mechanism is
  built to company-admin identities, not just employees — today
  company-admin tokens have *no* revocation path of any kind.

### 4. Secure mobile/desktop storage

Replace plain `SharedPreferences` token storage with platform-backed
secure storage:

- Mobile (`workin_mobile`): `flutter_secure_storage` (Android Keystore /
  iOS Keychain-backed) in place of the current `cache_helper.dart`
  plain-`SharedPreferences` wrapper for the token specifically (other,
  non-sensitive cached data can remain as-is).
- Desktop (`workin_desktop`): platform-appropriate secure storage
  (Windows Credential Manager / macOS Keychain via the same
  `flutter_secure_storage` package, which supports desktop platforms).

This is a client-code change to the auth-handling layer specifically
(`cache_helper.dart`'s token read/write calls), not a product-behavior
change — no UI or user-facing flow changes, consistent with the "don't
change the Flutter apps" direction for everything outside this
auth-remediation scope.

### 5. Client-side refresh capability

Both clients need a new code path: on receiving a 401 (or proactively,
before an access token's known expiry), call a refresh endpoint with the
stored refresh token, obtain a new access/refresh pair, and retry the
original request transparently. Today `http_helper.dart` only has
`_forceLogoutOnReplacedSession()` — a terminal, user-visible failure path.
This needs a **new, non-terminal path attempted first**, falling back to
the existing forced-logout UX only if the refresh call itself fails
(refresh token expired, revoked, or reuse-detected).

### 6. Session/device management

Currently there is no concept of "sessions" as a visible or manageable
entity — a new login silently replaces the previous one
(`token_version` bump), and the replaced session's owner only finds out
when their next request 401s. Recommended target shape (to be confirmed
by product, not decided here):

- A `sessions` (or `refresh_tokens`, if that table doubles as the session
  record) table carrying device/client metadata (platform, app version,
  approximate login time, last-used time).
- A user-facing "active sessions" view (mobile app and/or a future admin
  surface, scope depends on `ADR-0009`) allowing a user to see and
  individually revoke sessions — this is a **new feature**, not a
  migration-parity requirement, and should be scoped as such rather than
  assumed necessary for cutover.
- Whether multiple simultaneous active sessions per identity are allowed
  going forward (today's single-active-session model is enforced, if
  accidentally, by `token_version` bumping) is itself an open product
  question — see Open Questions.

## Backward-Compatible Migration Of Existing Users

This is the part of the design most specific to *migration* rather than
greenfield auth design, and the part most likely to be skipped if not
planned explicitly.

**The constraint**: existing users hold valid 10-year JWTs today, issued
by the legacy system, that the new backend will not recognize (different
signing key, different claim shape, different validation logic) unless
explicitly designed to.

**Candidate approaches** (comparison, not a decision):

1. **Dual-validation window**: the new backend accepts both legacy-shaped
   JWTs (validated against the legacy signing key/logic) and new-shaped
   tokens, for a bounded transition period, silently upgrading a legacy
   token to a new access/refresh pair on the user's next successful
   request. Lowest user-visible friction; highest implementation
   complexity (two validation code paths must coexist correctly and be
   torn down cleanly afterward).
2. **Forced re-authentication on cutover**: legacy tokens are not
   honored post-cutover; every user is logged out and must log in again
   once. Simplest to implement; guaranteed to generate a support-load
   spike and requires coordinated communication (in-app messaging,
   customer notification) timed with the desktop app's existing
   forced-update mechanism (`hr-platform#21`) so users aren't confused by
   an unexplained logout.
3. **Staggered rollout by identity cohort**: migrate authentication for a
   subset of companies/users first (feasible only if a staging/cutover
   testing mechanism exists — currently it does not, per `hr-platform#20`,
   since neither client has an environment switch), observe, then
   proceed. Lowest risk if it can be built; currently blocked on solving
   `hr-platform#20` first.

**This document does not select one of these three.** The choice depends
on acceptable support load, whether a staging mechanism gets built
(`hr-platform#20`), and the cutover timeline — all product/operational
decisions outside this document's scope.

## Sequencing (Server Changes vs. Client Releases)

Because client releases are slow and not fully controllable (app-store
review timelines; desktop installer uptake depends on users actually
running the existing forced-update check), the recommended order is:

1. **Client-side changes ship first, backward-compatible with the
   current backend**: add secure storage (item 4) and refresh-capable
   networking code (item 5) to both clients, but pointed at a refresh
   endpoint that — for now — can be a no-op or return "not yet supported"
   gracefully if the backend doesn't support it yet. This gets the
   client-side capability into the installed base ahead of any backend
   token-lifetime change, so that by the time the backend actually
   shortens token lifetime, most active users already have refresh-aware
   clients.
2. **Backend implements refresh/rotation/revocation** (items 1–3, 6),
   initially still honoring existing long-lived legacy tokens per
   whichever backward-compatibility approach is chosen above.
3. **Backend begins issuing short-lived tokens** to newly-authenticating
   users/sessions, with existing long-lived tokens phased out per the
   chosen backward-compatibility timeline.

This sequencing exists specifically to avoid the failure mode named in
`hr-platform#18`: shortening token lifetime before clients can refresh
would log out the entire active user base on first deploy.

## Consequences And Risks

- This design requires client-side engineering effort against
  `workin_mobile`/`workin_desktop`, which is a narrower exception to
  "don't change the Flutter apps" than a full feature — worth explicit
  product sign-off that this specific, scoped exception is acceptable
  before treating it as approved.
- Building session/device management (item 6) as a user-facing feature is
  scope creep relative to pure security remediation if not explicitly
  requested by product — recommend treating the backend data model
  (sessions/refresh-token table) as in-scope now (it's needed for
  revocation regardless) but the user-facing "manage my sessions" UI as a
  separately-scoped future feature.
- Dual-validation (backward-compatibility option 1) is the riskiest
  component to implement correctly — recommend a technical-spike-style
  proof of concept before committing to it over the simpler forced
  re-authentication option, if it's the direction chosen.

## Open Questions

- Exact access-token and refresh-token lifetimes — a product/security
  trade-off, not decided here.
- Whether multiple simultaneous sessions per identity are desired going
  forward, or whether the current single-active-session behavior should
  be preserved intentionally (as a product decision) rather than as an
  accidental side effect of `token_version` bumping.
- Which backward-compatibility approach (dual-validation, forced
  re-auth, staggered rollout) is acceptable given real support-load and
  timeline constraints — requires product/operations input.
- Whether a user-facing "active sessions" management UI is in scope for
  this migration or a later release.

## Evidence

`hr-legacy#7`, `hr-legacy#15`, `hr-platform#18`, `hr-platform#20`,
`hr-platform#21`; `docs/api/flutter-request-response-compatibility.md`
("Session/Token Lifecycle" section, full evidence citations therein);
`docs/security/threat-model.md`; ADR-0005
(`docs/adr/ADR-0005-authentication-and-authorization-direction.md`, the
architecture ADR this design document is intended to feed).

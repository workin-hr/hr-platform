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

## Backward-Compatible Migration Of Existing Users — Decided: Forced Re-Authentication

**Confirmed product decision, 2026-08-04**: existing `hr-legacy` JWTs
(mobile and desktop) are **not** migrated or dual-validated against the
new backend. This section previously compared three candidate
approaches (dual-validation, forced re-authentication, staggered
rollout) without choosing; that comparison is preserved below for
context, followed by the decided approach's full design.

### Why This Option

Dual-validation was the highest-implementation-complexity option (two
auth code paths coexisting, a real source of subtle bugs in exactly the
subsystem this whole remediation effort exists to make safer). Staggered
rollout was blocked on `hr-platform#20` (no environment-switch mechanism
in either Flutter client) — building that first would delay the whole
auth remediation for a lower-priority capability. Forced
re-authentication is the simplest to implement correctly and was chosen
directly by the product owner with that trade-off explicit: a one-time
support-load spike in exchange for a materially simpler, lower-risk
migration of the most security-sensitive subsystem in the codebase.

### Token Revocation / Cutover Behavior

- At the moment the new backend takes over authentication, **every
  existing `hr-legacy`-issued JWT is treated as invalid** — not
  individually revoked one-by-one (there is no mechanism to revoke
  10-year legacy tokens server-side today, `hr-legacy#7`), but
  categorically: the new backend simply never recognizes the legacy
  signing key/claim shape, so no legacy token can pass validation
  against it.
- User **credentials** (phone + password hash) migrate normally as part
  of the data migration — only session **tokens** do not. No user needs
  to reset their password; they only need to log in again.
- The legacy backend, if kept running in parallel during any transition
  window, continues to honor its own tokens until it is fully
  decommissioned — this design does not require synchronized revocation
  across both systems, since they simply stop being the same
  authentication authority at cutover.

### User Communication Requirements

- **Advance notice before cutover**: users should be told, in advance,
  that they will need to log in again after a specific date/deployment —
  this is a coordination requirement for whoever owns the cutover
  communication plan, not something this design document can schedule.
- **In-the-moment messaging**: when a client's next request fails
  because the legacy token is no longer recognized, the client must show
  a clear "please log in again" state (see Client Handling below), not a
  generic error — this is a client-code requirement, not just a backend
  one.
- **Coordinate with the existing desktop forced-update/maintenance-mode
  mechanism** (`hr-platform#21`, `configs`-served version-gate fields) —
  desktop already has a working mechanism for telling users "something
  changed, take action," which the cutover communication should reuse
  rather than inventing a second, parallel mechanism.

### Client Handling Of Expired/Invalid Legacy Tokens

- Both clients' existing `_forceLogoutOnReplacedSession()`-style handling
  (per `docs/api/flutter-request-response-compatibility.md`, "Session/Token
  Lifecycle") already reacts to an outright `401` with a graceful,
  user-visible "please log in again" flow — the new backend rejecting a
  legacy token on cutover produces exactly this same `401` shape, so
  **no new client-side error-handling code path is required** for the
  cutover event itself, only for the *new* refresh-token flow (item 5 in
  Target Design above), which is a separate, already-scoped client
  change.
- This is a real point in favor of forced re-authentication specifically:
  it degrades through an error path both clients already handle
  correctly, rather than requiring new client logic to be shipped and
  adopted before cutover can safely happen.

### Secure Access/Refresh-Token Lifecycle (Post-Cutover)

Once a user re-authenticates against the new backend, the full target
design applies from Target Design above: short-lived access token,
rotating opaque refresh token, `flutter_secure_storage`-backed storage
on both clients (item 4), server-side revocation on logout/password
change (item 3). Forced re-authentication is specifically the
**migration mechanism** for existing users reaching this target state —
it is not a permanent ongoing behavior; once past cutover, normal
refresh-token rotation governs session lifetime, not repeated forced
logins.

### Rollback Implications

If the new auth backend needs to be rolled back after cutover, users who
already re-authenticated against it hold **new-system credentials/tokens
the old `hr-legacy` system does not recognize** — rollback is **not**
silently transparent to users who already migrated. Concretely: a user
who logged into the new backend, then experiences a rollback to
`hr-legacy`, will need to log in again a *second* time against the old
system (their password still works there, since credentials were
migrated, not replaced — only where their session token is valid
differs). This needs an explicit rollback communication plan if rollback
is a realistic possibility for the cutover window chosen — not designed
here, but named as a required follow-up, not an implicit non-issue.
Recorded also in `docs/migration/cutover-and-rollback-assumptions.md`.

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
2. **Backend implements refresh/rotation/revocation** (items 1–3, 6).
   Because forced re-authentication is the decided approach, there is no
   dual-validation window to build — the backend simply never needs to
   understand the legacy token shape at all.
3. **Backend cuts over**: at go-live, the new backend begins issuing and
   requiring its own short-lived tokens for all authentication; every
   existing legacy session ends at that moment (see the decided-approach
   section above for the full cutover/communication/rollback design).

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
- ~~Dual-validation risk~~ — moot: forced re-authentication was chosen
  specifically because it avoids dual-validation's implementation risk
  (see "Why This Option" above). No spike needed for the
  backward-compatibility mechanism itself.

## Open Questions

- Exact access-token and refresh-token lifetimes — a product/security
  trade-off, not decided here.
- Whether multiple simultaneous sessions per identity are desired going
  forward, or whether the current single-active-session behavior should
  be preserved intentionally (as a product decision) rather than as an
  accidental side effect of `token_version` bumping.
- ~~Which backward-compatibility approach is acceptable~~ — **Resolved
  2026-08-04**: forced re-authentication, confirmed by the product
  owner. See "Backward-Compatible Migration Of Existing Users" above for
  the full design.
- Exact cutover date/communication lead time for the forced
  re-authentication event — not yet scheduled, depends on overall
  migration sequencing (`hr-platform#15`, PMR-09).
- Whether a user-facing "active sessions" management UI is in scope for
  this migration or a later release.

## Evidence

`hr-legacy#7`, `hr-legacy#15`, `hr-platform#18`, `hr-platform#20`,
`hr-platform#21`; `docs/api/flutter-request-response-compatibility.md`
("Session/Token Lifecycle" section, full evidence citations therein);
`docs/security/threat-model.md`; ADR-0005
(`docs/adr/ADR-0005-authentication-direction.md`, the
architecture ADR this design document is intended to feed).

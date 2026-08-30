# ADR-0014: Authentication For The Platform-Admin Web Surface

## Metadata

| Field | Value |
|---|---|
| ADR ID | ADR-0014 |
| Title | Authentication for the platform-admin web surface |
| Status | Proposed |
| Date | 2026-08-30 |
| Owners | Solution Architect (primary), Product (scope input) |
| Deciders | Repository owner; engineering lead for feasibility sign-off |
| Related Issues | None yet |
| Supersedes | None |
| Superseded By | None |

Valid `Status` values: `Proposed`, `Accepted`, `Rejected`, `Superseded`,
`Deferred`. New ADRs must start `Proposed`.

## Context

**ADR-0009 (Accepted, D-025)** settled Option E — the role-based split. Company
and HR administration consolidates onto the native Flutter desktop app;
individual employees stay mobile; only **Workin's own platform-level
administration** stays web (approve/reject/suspend companies, platform
oversight). ADR-0009 §"Technology For The Platform-Admin Web Surface" confirmed
**Next.js** for that surface, with engineering sign-off on 2026-08-05.

ADR-0009 decided *what* the surface is and *what it is built with*. It did not
decide **how it authenticates**. That is the gap this ADR fills, and there are
three constraints on the answer that are already settled elsewhere.

### Constraint 1 — parity does not bind this surface

Phase 1's parity rule (**D-058**, **D-111**) exists for one reason: Flutter
mobile and desktop ship compiled to real devices and cannot be changed, so the
wire contract must not move under them. The platform-admin surface has **no
frozen client — it is built new, and it is its own client**. Nothing on the
other end needs to stay compatible.

Parity therefore has no purchase here. This is not an exception being carved out
of D-058; it is a surface D-058 never covered.

### Constraint 2 — the legacy dashboard's auth is not a candidate

`hr-legacy/dashboard/includes/auth.php` uses PHP sessions:
`$_SESSION['admin_logged_in']` / `company_logged_in` / `hr_logged_in`, with a
`SESSION_TIMEOUT` sliding window renewed on each request. Under ADR-0009 the
company and HR session paths are being retired outright, and the dashboard
carries known security findings (`hr-legacy#2`, `#3`, `#6`).

Porting that design would import those findings into a greenfield application
whose only justification for the design would be that it already existed.

### Constraint 3 — the authentication *model* is already decided

**ADR-0005 (Accepted, D-017)** settled the new system's model: self-managed JWT
for the MVP, **short-lived access tokens**, **opaque rotating refresh tokens**
(not a second JWT) with reuse of a rotated-out token treated as a compromise
signal that revokes the whole family, and **server-side refresh-session
persistence** so sessions can be listed and revoked individually.

That model is already implemented — `JwtService`, `RefreshTokenService`, the
`refresh_tokens` table — with `app.jwt.access-token-ttl-seconds` defaulting to
900 (15 minutes) and `app.jwt.refresh-token-ttl-seconds` to 5184000 (60 days).

**But ADR-0005 is Flutter-shaped.** Its storage decision is
`flutter_secure_storage`, backed by Android Keystore and iOS Keychain. A browser
has no equivalent, and ADR-0005 says nothing about the browser case — a search
of it and `authentication-remediation-design.md` finds no mention of `httpOnly`,
`SameSite`, or CSRF. The model transfers; the **storage and transport do not**,
and that gap is what makes this a decision rather than a lookup.

### Constraint 4 — the platform-admin backend already exists

**This is not greenfield, and an earlier draft of this ADR wrongly assumed it
was.** `com.workin.backend.platformadmin` is already built and already answers
several of the questions this ADR was opened to ask:

| Already decided in code | Where |
|---|---|
| Platform admins are **their own identity type**, not a role on a shared identity | `PlatformAdmin`, `PlatformAdminRepository`, `platform_admins` |
| ADR-0005's rotation model already applies to them | `PlatformAdminSessionService.issue()`/`rotate()`, `platform_admin_refresh_tokens` |
| Session lifetimes are **already scoped tighter than the client defaults** | `app.platform-admin.jwt.access-token-ttl-seconds:900` (15 min) and `refresh-token-ttl-seconds:604800` (**7 days**, not the clients' 60) |
| Platform-admin actions are **already audited** | `PlatformAdminAuditService`, `PlatformAdminAuditEvent` |
| There is **no self-registration**; the first admin comes from bootstrap env vars and is never overwritten | `PlatformAdminBootstrap` |

So the identity model, the token model, the lifetimes and the audit trail are
settled and implemented. Two things are not:

1. **Transport.** `PlatformAdminAuthController` returns both the access token and
   the raw refresh token **in the JSON response body**
   (`PlatformAdminAuthResponse`). That is correct for a machine client. For a
   browser it means the credential lands in JavaScript's hands, which is exactly
   the outcome this ADR exists to prevent.
2. **MFA.** A search of the whole package for TOTP/MFA/two-factor returns
   nothing. The highest-privilege surface in the system is single-factor today.

### Why this matters more here than anywhere else

This is the highest-privilege surface in the system. Its users can suspend and
delete customer companies. A credential compromise here is not one tenant's
problem — it is every tenant's.

## Decision

**Approval status: Proposed — this decision has not been approved.** This
section describes a candidate direction and must not be read as an accepted
architecture decision until `Status` above is changed to `Accepted` by a human,
following independent review.

The platform-admin web surface authenticates as follows.

### 1. Keep the existing backend contract; do not add a cookie transport to it

`PlatformAdminAuthController` stays exactly as it is — tokens in the JSON body,
bearer on the way back in. **No second authentication transport is added to the
Java backend.**

An earlier draft of this ADR proposed that the backend set cookies directly, and
then named "two transports, one backend" as the primary risk it introduced. That
risk was self-inflicted: the transport problem belongs to the web client, not to
the API.

### 2. The browser talks to a server-side session boundary, never to the API

The Next.js app calls the Java API only from its **server side** (route handlers
or a dedicated BFF). That server side holds the platform-admin access and refresh
tokens, and issues the browser its own session cookie:

| Attribute | Value | Why |
|---|---|---|
| `HttpOnly` | yes | script cannot read the session, so an XSS does not become a stolen platform-admin session |
| `Secure` | yes | never transmitted over plaintext |
| `SameSite` | `Lax` | blocks cross-site attachment on state-changing navigations |
| `Path` | scoped to the admin surface | not attached to unrelated routes |

The platform-admin tokens **never reach the browser at all**. This is strictly
better than making them `HttpOnly`: a credential that is never sent to the client
cannot be stolen from it, and the Java backend keeps one authentication model.

**Explicitly rejected: putting the tokens `PlatformAdminAuthResponse` already
returns into `localStorage` or `sessionStorage`.** This is the default many
Next.js examples reach for, and because the backend hands both tokens to whoever
calls `/login`, it is also the path of least resistance here. It would let any
script executing on the page read a credential that suspends customer companies.

Because the session cookie is attached automatically, **CSRF protection is part
of this decision, not a follow-up**: `SameSite=Lax` plus an explicit anti-CSRF
token on every state-changing request.

### 3. Session lifetimes are already correct — confirm, do not change

`app.platform-admin.jwt.access-token-ttl-seconds` is 900 (15 minutes) and
`refresh-token-ttl-seconds` is 604800 (7 days), already scoped separately from
the clients' 60-day refresh. **An earlier draft of this ADR proposed shortening
these without checking; they were already short.** This ADR asserts no change,
only that the BFF's own cookie session must not outlive the refresh token it
wraps.

### 4. MFA is required, with step-up on destructive actions

This is the one genuine gap. Every platform-admin identity enrols in TOTP.
Beyond login, destructive operations — company suspension, company deletion —
require step-up re-authentication, so a hijacked session is not automatically
authority to destroy a customer's tenancy.

The population is small, internal, and bootstrap-provisioned with no
self-registration, which makes this cheap to operate and removes the usual
objection.

### 5. Authorization and audit are unchanged

**ADR-0010**'s model already loads and validates authorization server-side on
every request, and `PlatformAdminAuditService` already records platform-admin
actions. This ADR changes how a browser session is carried, not what an admin
may do or what is recorded.

## Alternatives Considered

### Option A — Port the PHP dashboard's session auth

Reuse `$_SESSION['admin_logged_in']` semantics against the Java backend.

**Rejected.** Its only argument is precedent, and precedent is exactly what
Constraint 1 removes — there is no frozen client forcing it. It also imports a
design carrying open security findings into a new application.

### Option B — Bearer token in browser storage

Store the access/refresh pair `PlatformAdminAuthResponse` already returns in
`localStorage` and send it with an `Authorization` header.

**Rejected**, and worth naming precisely because it is the path of least
resistance: the backend already hands both tokens to any caller of `/login`, so
this requires no backend work at all. It also gives maximum blast radius to any
XSS on the highest-privilege surface in the system. Its advantages — symmetry
with the mobile clients, no CSRF concern, least effort — do not outweigh making a
company-suspending credential script-readable.

### Option B2 — Backend sets the cookies itself

Add a cookie-issuing entry point to `PlatformAdminAuthController` alongside the
existing JSON one.

**Rejected** — and this was the previous draft of this ADR's own proposal. It
gives the Java backend two authentication transports that must never diverge in
how they resolve identity or authorization, which is a permanent correctness
burden accepted to solve a problem that belongs to one client. The BFF achieves
the same browser-side guarantee with no backend change.

### Option C — Access token in memory only, refresh via cookie

Keep the access token in a JS variable (never persisted), refresh through an
`HttpOnly` cookie.

**Not rejected on merit — genuinely defensible**, and materially better than
Option B. Not chosen because it still exposes the access token to script for its
lifetime and needs bespoke silent-refresh handling across tabs and reloads, for
no gain over Option A's successor once the refresh path is already cookie-based.
Worth revisiting if a concrete requirement (e.g. a cross-origin API topology)
makes the cookie path awkward.

### Option D — External identity provider (OIDC/SSO)

Delegate platform-admin identity to an external IdP.

**Deferred, not rejected.** ADR-0005 chose self-managed authentication for the
MVP, and this ADR does not reopen that. For an internal-staff-only surface an
IdP is the conventional 2026 answer and would supply MFA, lifecycle and audit
for free. It is the most likely future supersession of this ADR, and Section 4
is deliberately shaped so that adopting one later replaces a component rather
than the model.

## Consequences

- The Java backend is **unchanged**. No cookie entry point, no second transport,
  no new security model to keep in step with the existing one.
- The Next.js app must never call the Java API from the browser. That is a
  standing architectural constraint on it, not a one-time implementation note,
  and it should be enforced by review or lint rather than convention.
- The BFF becomes a credential holder, so **its own runtime is now in scope for
  platform-admin security**: its session store, its deployment, and its secrets.
  This is the real cost of the approach and it should not be understated — it
  trades a browser-storage risk for a server-side-custody responsibility.
- Cookie domain and subdomain topology becomes a deployment decision.
- MFA needs enrolment, recovery and lockout flows. Small population, but they
  cannot be skipped, and recovery must not become the weakest link.
- **Sequencing**: this is Phase 2 work. Phase 1 has three open G11 blockers
  (**R-023**, **R-024**, **R-025**) and a pending cutover. Recording the decision
  now is cheap; building against it before the port lands is scope expansion
  across an unfinished migration.

## Risks

- **BFF token custody.** The server side now holds long-lived platform-admin
  refresh tokens. A compromise there is equivalent to a compromise of every
  platform-admin session. Mitigation: treat the BFF as a production secret
  holder, not as a static frontend — no tokens in logs, no tokens in the
  filesystem, revocation reachable through the existing
  `PlatformAdminSessionService`.
- **CSRF, newly relevant.** Cookies reintroduce a class the bearer clients never
  had. Mitigation: `SameSite=Lax` plus anti-CSRF tokens, with negative tests
  asserting a cross-site state-changing request is rejected.
- **The easy wrong path stays open.** `PlatformAdminAuthResponse` hands both
  tokens to any caller of `/login`, so a future developer can trivially wire the
  browser straight to the API and reintroduce exactly the risk this ADR closes.
  Mitigation: the constraint in Consequences must be enforced, not assumed.
- **MFA lockout of the only admins**, given bootstrap provisioning and no
  self-registration. A recovery path is required.
- **Session fixation.** The BFF's session identifier must rotate on login and on
  privilege change.
- **Accepted for now**: self-managed authentication carries lifecycle burden an
  IdP would absorb — see Option D.

## Validation Evidence

**Partially evidenced.** What the code already establishes was verified directly
against `com.workin.backend.platformadmin` on 2026-08-30 and is recorded in
Constraint 4: separate identity type, rotation model in use, 15-minute access /
7-day refresh lifetimes, audit trail present, no self-registration, **no MFA**,
tokens returned in the response body.

Still required before this moves from `Proposed` to `Accepted`:

1. Engineering-lead sign-off on the BFF boundary — specifically that the browser
   never receives a platform-admin token, and how that is enforced rather than
   documented.
2. The BFF session store decision (stateless signed cookie versus server-side
   session), and the cookie domain topology it implies.
3. A named TOTP implementation and a recovery design that does not weaken the
   factor it protects.
4. Confirmation of whether the legacy PHP dashboard's `admin`-role surface runs
   in parallel during Phase 2, and if so whether both authenticate independently.

## Open Questions

- ~~Do platform admins exist as their own identity type, or as a role on a
  shared identity?~~ **Answered 2026-08-30 by reading the code**: their own type
  (`PlatformAdmin`, `platform_admins`, `platform_admin_refresh_tokens`), with no
  self-registration. This was the blocking question when the ADR was opened.
- Does the admin surface share an origin with the BFF, and the BFF with the Java
  backend? This decides whether `SameSite=Lax` is sufficient.
- What is the **retention** requirement for `PlatformAdminAuditEvent`? The audit
  trail exists; how long it must survive does not appear to be recorded.
- Is there a regulatory or customer-contractual requirement that would force
  Option D (external IdP) sooner than convenience would?
- Does the existing PHP dashboard's `admin`-role surface keep running during
  Phase 2, and if so, do both surfaces authenticate independently in the
  meantime?

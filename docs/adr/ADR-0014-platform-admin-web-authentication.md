# ADR-0014: Authentication For The Platform-Admin Web Surface

## Metadata

| Field | Value |
|---|---|
| ADR ID | ADR-0014 |
| Title | Authentication for the platform-admin web surface |
| Status | Superseded |
| Date | 2026-08-30 (accepted 2026-08-31 by the repository owner — see `docs/bootstrap/decision-log-wave12r.md` D-146) |
| Owners | Solution Architect (primary), Product (scope input) |
| Deciders | Repository owner; engineering lead for feasibility sign-off |
| Related Issues | `hr-legacy#11` (shared platform-admin password — the finding this surface's identity model exists to close, D-027/F-26); `hr-platform#25` (dashboard retirement, ADR-0009) |
| Supersedes | None |
| Superseded By | ADR-0015 |

Valid `Status` values: `Proposed`, `Accepted`, `Rejected`, `Superseded`,
`Deferred`. New ADRs must start `Proposed`.

## Superseded by ADR-0015

**This ADR designs the wrong architecture.** It assumes a **Next.js
application with a server-side BFF** holding the platform-admin token pair. The
repository owner corrected that premise on 2026-09-01: the admin web is **JTE
pages inside the existing Spring application** — one deployment, server-side
rendered, on the application's existing authentication and session model.

That is an architecture correction rather than a variation, which is why this
ADR is superseded rather than amended: most of its security surface existed
*because of* the browser/backend split and does not survive its removal — the
BFF credential store (**R-033**), rotation-result custody, browser-token
enforcement, cookie topology, and the logout revocation outbox.

**Read [ADR-0015](ADR-0015-platform-admin-jte-authentication.md) instead.** The
requirements that never depended on the BFF — MFA/TOTP and seed custody,
step-up bounds, throttling, per-request authorization, session invalidation,
auditability — are carried forward there, together with CSRF and session-cookie
hardening, which the in-process model makes first-class.

This document is retained rather than deleted because the reasoning it records
remains the evidence for those surviving requirements, and because the review
history on it is where several of them were found.

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
company and HR session paths are being retired outright.

The relevant authentication evidence against it is **`hr-legacy#11`**: the
platform-admin surface authenticated on a **shared password**, with no
individual identity, no per-admin revocation and no audit attribution. D-027
made replacing that a P0 requirement and F-26 records it as substantially
closed by the `platformadmin` package. Porting the session mechanism would walk
back the one finding this surface's identity model was built to close.

*(An earlier draft cited `hr-legacy#2`, `#3` and `#6` here. Those are
tenant-scoping and IDOR defects in dashboard **action handlers** — real, but
properties of those handlers, not of the session mechanism, so porting the
mechanism alone would not import them. Using them as the argument would have
justified a transport decision with unrelated authorization bugs.)*

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
| ADR-0005's **rotation** model already applies to them | `PlatformAdminSessionService.issue()`/`rotate()`, `platform_admin_refresh_tokens` — reuse detection and family revocation are there. **Its session-management half is not**: ADR-0005 requires sessions to be *listed and revoked individually*, and there is no list query on `PlatformAdminRefreshTokenRepository` and no controller exposing either operation. Revocation today needs the refresh token in hand (logout) or is all-or-nothing (`revokeAllForPlatformAdmin`, which has no production caller). Worth noting the same gap exists on the tenant side — `RefreshTokenRepository` has no list query either — so this is an ADR-0005 shortfall, not a platform-admin one. |
| Session lifetimes are **already scoped separately** from the clients' | `app.platform-admin.jwt.access-token-ttl-seconds:900` — identical to the clients' 900, not tighter — and `refresh-token-ttl-seconds:604800` (**7 days** against the clients' 60). Only the refresh bound differs. |
| An audit trail **exists**, for auth-lifecycle events | `PlatformAdminAuditService`, `PlatformAdminAuditEvent` — `LOGIN`/`LOGIN_FAILED`/`LOGOUT`/`SESSION_REUSE_REVOKED`/`ALL_SESSIONS_REVOKED`. F-26 leaves per-endpoint audit of future `platform.*` business actions as a **standing acceptance criterion**, so 'already audited' would overstate it: no business endpoint exists yet to audit. |
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

**Accepted 2026-08-31 by the repository owner** (D-146).

> **One of the two named deciders has signed.** The metadata names the
> repository owner *and* the engineering lead (feasibility). Only the owner has
> approved. The owner can settle direction alone — that is what an owner is for
> — but the feasibility half is unsigned, and validation item 1 is exactly that
> signature. Recorded here rather than left to be inferred from a metadata row:
> **this ADR is owner-accepted, not jointly accepted**, and the BFF boundary has
> had no engineering feasibility review.

**Accepted over its own validation list.** The items below were written as
acceptance blockers; the owner accepted the direction with all of them still
open, which is theirs to do. They do not disappear — they become
**implementation prerequisites**: the design is settled, and none of it may
ship until they are answered. Two in particular gate any code at all —
throttling (Decision 7), because the surface is currently weaker than the system
it replaces, and the **step-up bounds** (Decision 4), because a step-up that is
not bound to its target is defeated in the scenario it exists for.

The active-admin lookup (Decision 5, **R-026**) was in this set and is **no
longer**: it merged in PR #152 and is enforced per request today (**D-145**).
It is listed below as a dependency that has been met, not as outstanding work.

The platform-admin web surface authenticates as follows.

### 1. Keep the existing backend contract; do not add a cookie transport to it

`PlatformAdminAuthController` keeps its **transport**: tokens in the JSON body,
bearer on the way back in. **No second authentication transport is added to the
Java backend.**

> **This is not "no backend changes".** Decision 4 requires TOTP at login and
> step-up on destructive operations, and today `/login` takes phone plus
> password and returns the token pair immediately — there is nowhere for a
> challenge to happen. Delivering MFA needs a changed login exchange (a
> challenge/response step), enrolment endpoints, and a way to mark a session
> step-up-satisfied. Decision 5 requires a backend change too. What this
> decision fixes is that the browser is not given a second way *in*, not that
> the contract is frozen.

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

### 3. Session lifetimes: the baseline is already there; add an idle and an absolute bound

`app.platform-admin.jwt.access-token-ttl-seconds` is 900 (15 minutes) and
`app.platform-admin.jwt.refresh-token-ttl-seconds` is 604800 (**7 days**), scoped
separately from the tenant clients' 60-day value — and that 60-day figure is
recorded as a configurable starting value, not a calibrated product decision, so
an earlier draft was wrong to describe it as calibrated and wrong to propose
shortening admin bounds that were already short.

What actually remains is narrower, and it is not a smaller number:

- The 7-day refresh is **sliding** — each rotation issues a fresh 7-day token, so
  a session used daily never expires. The missing controls are an **idle
  timeout** (expiry measured from last use, not last rotation) and a
  **non-renewable absolute cap on the token family**, after which
  re-authentication is required regardless of activity.
- The BFF's own cookie session must not outlive the refresh token it wraps.

**The bounds themselves are not set here, and that is a gap, not a delegation.**
Declaring an idle timeout and an absolute cap mandatory without numbers lets
this ADR be accepted while the family still slides forever, or lets two
implementations pick incompatible values. Choosing them is validation item 7
below and blocks **implementation**, not acceptance -- this ADR is Accepted, and these are implementation prerequisites.

### 4. MFA is required, with step-up on destructive actions

This is the one genuine gap. Every platform-admin identity enrols in TOTP.
Beyond login, destructive operations — company suspension, company deletion —
require step-up re-authentication, so a hijacked session is not automatically
authority to destroy a customer's tenancy.

**Step-up is bounded, or it is decoration.** A "step-up satisfied" flag set once
and honoured for the rest of the session is indistinguishable from no step-up at
all for any action taken later, which is when a hijacked session would be used.
Three properties are required, not just a representation:

| Property | Why |
|---|---|
| **Maximum age** — minutes, not the session lifetime | the challenge must be recent relative to the action, not to the login |
| **Single use** | one challenge authorises one operation, so it cannot be banked |
| **Bound to the action** | satisfying step-up for a suspension must not also authorise a deletion |
| **Bound to the target** | satisfying step-up to suspend company A must not authorise suspending company B |

The last row is not a refinement of the one above it. Binding to the operation
alone leaves the protection defeated in exactly the scenario it exists for: an
administrator solves a challenge to suspend company A, and an attacker holding
the hijacked session consumes that single-use approval against company B. Same
operation, same session, different tenant — every stated property is satisfied
and the wrong company is suspended.

So the capability is bound to **the canonical operation, the resource
identifier, and a digest of the security-relevant request parameters**, and the
server recomputes that binding from the request it is about to execute rather
than trusting anything echoed back. A mismatch is a failed step-up, not a
retry.

The population is small, internal, and bootstrap-provisioned with no
self-registration, which makes this cheap to operate and removes the usual
objection.

### 5. Deactivation must be enforced per request — it is not today

An earlier draft of this ADR claimed authorization was already loaded and
validated server-side on every request, citing **ADR-0010**. **That is false for
the existing platform-admin path**, and the review that caught it is right:

- `PlatformAdminAuthenticationFilter` builds `AuthenticatedPlatformAdminPrincipal`
  from the JWT subject alone. It **never loads the admin row** and never checks
  `active`.
- `PlatformAdminSessionService` checks deactivation **only on refresh** — which
  F-26 correctly describes as "fail-closed rotation for deactivated admins", a
  narrower claim than per-request enforcement.

So a platform administrator who is deactivated **retains full access until their
access token expires** — up to 15 minutes on the highest-privilege surface in the
system, and deactivation is exactly the control used when an admin should stop
having access immediately.

This ADR required an active-admin lookup in the shared request path, reached by
both the existing bearer entry point and the BFF, with a regression test.

**That is done.** `PlatformAdminAuthenticationFilter` loads the
`platform_admins` row and verifies `active` on every request, failing closed on
a missing row; `PlatformAdminAuthFlowTest#aTokenIssuedBeforeDeactivationStopsWorkingImmediately`
pins it. Closed as **R-026**, decided as **D-145**, merged in PR #152 — so the
BFF inherits the check by construction rather than needing to add it.

The prose above is kept in the past tense rather than deleted: it is the reason
the requirement exists, and a future entry point that bypasses the filter would
reintroduce exactly this.

`PlatformAdminAuditService` records **auth-lifecycle events**
(`LOGIN`/`LOGIN_FAILED`/`LOGOUT`/`SESSION_REUSE_REVOKED`/`ALL_SESSIONS_REVOKED`)
and nothing else — its only callers are `PlatformAdminLoginService` and
`PlatformAdminSessionService`. Per-endpoint audit of business actions is F-26's
standing acceptance criterion, still owed. That part is unchanged by this ADR.

### 6. Logging out of the browser must revoke the backend session, not just the cookie

A browser logout has to do three things, and only the first is obvious:

1. Clear the BFF's session cookie.
2. **Invalidate the BFF's own server-side session record**, so a copied session
   identifier is worthless afterwards.
3. **Call the Java `/api/platform-admin/logout`** with the stored refresh token,
   so the backend family is revoked.

Omit the third and the device looks logged out while the refresh family keeps
sliding for up to seven days — the BFF could resume the session at any point,
and anything holding that refresh token still can. Omit the second and the
cookie value alone is enough to walk back in.

**Order matters, and both obvious orders are wrong.** Clearing the cookie and
deleting the BFF session first destroys the only copy of the refresh token the
BFF holds — so if the Java `/logout` then times out, nothing retains the token
needed to revoke the family, and it keeps sliding. But an earlier draft of this
ADR drew the wrong conclusion from that and said to revoke at the backend first,
keeping the BFF session alive and retrying on failure. **That leaves the
browser-facing session valid for exactly as long as revocation keeps failing —
so a copied session identifier stays usable precisely while its owner is trying
to terminate it.** The failure mode is the attack.

The two requirements are not actually in tension, because they are about
different objects. Logout must, in one atomic step:

1. **Invalidate the BFF session record and clear the cookie immediately** — no
   retry, no waiting on the backend. From this moment the session identifier is
   worthless whoever holds it.
2. **Move the raw refresh token into a durable revocation outbox** that no
   request path can read — only the retry worker can. The token survives to
   revoke the family; the session does not survive to be used.

The outbox retry is idempotent **for the family transition, but not yet for the
audit trail**, and at-least-once delivery makes that difference matter.
`/api/platform-admin/logout` tolerates a token that is unknown or already
revoked — the `REVOKED` update is a no-op the second time — but
`PlatformAdminSessionService.logout()` records a `LOGOUT` event on **every**
call. A committed logout whose response was lost therefore produces one audit
event per retry, all attributed to a single user action, which is exactly the
kind of noise that makes an incident timeline unreliable. So the audit write is
**conditional on the revocation actually transitioning the family**, or carries
an idempotency key the retry reuses. Making the endpoint idempotent in the
status code is not the same as making it idempotent in its side effects, and
the outbox depends on the second. An entry that cannot be drained is an
operational alert, not a user-visible failure — the user is already logged out.

**Logout takes the same per-session lock a refresh does, and the successor
write is a compare-and-swap.** Without both, the two flows race and the session
comes back: a refresh that has already rotated at Java but not yet persisted its
successor will, on resuming, write that successor into a session logout has just
deleted — resurrecting the very session the user asked to end, while outbox
revocation is still pending. The window is exactly the gap between the backend
rotation committing and the BFF storing the result, which is where every other
rotation hazard in this design already lives. So logout participates in the
same serialization as refresh, and **persisting a successor fails if the session
has been tombstoned** rather than recreating it. A tombstone rather than a plain
delete is what makes that check possible.

Worth stating because the residual failure is invisible from the browser: if the
outbox never drains, the user sees a login screen and reasonably concludes the
session is over, while the refresh family keeps sliding for up to seven days.

**This does not fix R-027.** Revoking the family still leaves the *access* token
valid until `exp`; that is the open question ADR-0005 owns. This decision only
requires that logout does everything currently available to it.

### 7. Authentication attempts are throttled, and this is not optional

`PlatformAdminLoginService` has **no attempt limit, no backoff and no lockout**
— only audit attribution of failures. The legacy dashboard it replaces enforces
an **8-attempt, 15-minute lockout** (`dashboard/includes/security.php`), which
the threat model records as one of the few real mitigations standing in front of
the shared admin password.

So the new platform-admin login is currently **weaker than the system it
replaces** on online guessing, and relaying it through a public browser surface
does not change that. Adding TOTP without throttling does not close it either: a
six-digit second factor is 10^6 values, which is a feasible online search
against an unlimited endpoint, and step-up on destructive actions inherits the
same weakness.

Throttling therefore ships **with** MFA, not after it: per-identity attempt
limiting with lockout on both the password and the TOTP step. Matching legacy's
8/15 is the floor, not the target.

**Counted in shared state, not per process.** This ADR assumes a multi-worker
or serverless BFF deployment elsewhere in its own consequences, and a
process-local counter satisfies the sentence above while failing the purpose:
attempts spread across workers each get their own budget, and a worker restart
resets it. Against a six-digit second factor that difference is the whole
control — bounded guessing becomes effectively unbounded. So the limit is
enforced **at the Java backend boundary**, where every attempt converges
regardless of which BFF instance received it, in state that survives a restart.
The acceptance test for this is not "8 attempts are refused" but **8 attempts
submitted through separate workers are refused**, which is what distinguishes
a real limit from a per-process one.

### 8. The existing bearer endpoints are restricted to the BFF, not left open

`/api/platform-admin/login` and `/refresh` return raw access and refresh tokens,
and `/api/platform-admin/**` authenticates through an `Authorization: Bearer`
filter. Leaving that contract publicly reachable while claiming a cookie-only
browser boundary would mean a JS-readable credential path sitting beside the one
this ADR exists to close — any future developer could wire the browser straight
to it.

**Decision: those endpoints remain the only way in, and the BFF becomes their
only permitted caller.** They are not replaced and not duplicated; what changes
is that the browser is not a client of them. How that restriction is enforced —
network reachability, a required BFF credential, or both — is a validation item
below, but the *decision* that they are not browser-facing is made here rather
than left to implementation. The endpoint inventory and client-contract evidence
must record them as BFF-only once that is in place.

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

- The Java backend's **authentication transport** is unchanged. No cookie entry
  point, no second transport, no new security model to keep in step with the
  existing one. This is **not** a claim that the backend needs no changes:
  Decision 5 requires an active-admin lookup in the shared request path
  (**R-026**, delivered in PR #152 as **D-145**), and Decision 8's enforcement
  may require a BFF credential. Both are backend work; only the second is
  outstanding.
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
- **R-026 was an ordering dependency, and it has been met.** This ADR surfaced
  the deactivation defect; Decision 5 depended on it being closed, and both the
  register entry and the fix lived in PR #152, so for a period this ADR named an
  acceptance dependency a reader could not look up on `main`. **#152 has since
  merged**: the active-admin lookup runs per request (**D-145**) and R-026 is
  closed. Splitting them kept the security fix reviewable on its own, which was
  the right trade. Recorded because the dependency was real while it lasted, not
  because it is still outstanding.

## Risks

- **BFF token custody.** The server side now holds long-lived platform-admin
  refresh tokens. A compromise there is equivalent to a compromise of every
  platform-admin session.

  **And it is worse than the backend's own exposure**, which is the part easy to
  miss: Java stores only a SHA-256 *hash* of each refresh token, so a read of
  `platform_admin_refresh_tokens` yields nothing usable. The BFF must retain the
  **raw** token to present it, so its session store — and every backup and
  replica of that store — holds live credentials for every platform admin.

  Mitigation: treat the BFF store as a credential store, not a cache. Encrypt
  the tokens at rest under a key the store itself does not hold, keep backups
  under the same rule, and make revocation through `PlatformAdminSessionService`
  part of the incident response for a store compromise. Excluding tokens from
  logs and the filesystem, as an earlier version of this bullet said, does not
  address a database read at all.
- **Concurrent refresh in the BFF revokes the whole family.**
  `PlatformAdminSessionService.rotate()` treats a second presentation of an
  already-rotated refresh token as reuse and revokes the family — correct
  behaviour against theft, and a trap for a BFF. Several browser requests
  arriving just after the access token expires can reach separate Next.js
  handlers, each submitting the same stored refresh token, and normal
  day-to-day concurrency then looks exactly like an attack. **Scoped
  correctly**: `revokeFamilyForReuse()` calls `setStatusForFamily()`, so what
  dies is the presented token's family — that one session — not every session
  the admin holds, which carry different family UUIDs. The admin is logged out
  mid-task rather than everywhere at once. Mitigation: the BFF must serialise
  refresh per session — a lock or single-flight around rotation — and that
  requirement belongs in its implementation notes, not discovered in
  production.

  **A lock is not sufficient on its own, and the BFF cannot close the gap by
  itself.** A lock handles concurrent callers; it does not handle a *lost
  outcome*. If Java commits the rotation but its response never arrives, or the
  worker dies before persisting the successor, the session store still holds the
  rotated-out token — and its next use is classified as reuse, revoking the
  family, with no attacker anywhere near it.

  An earlier version of this bullet proposed "persist intent and reconcile".
  **That is not implementable against the current API**, and saying so is more
  useful than a mitigation that cannot be built: `PlatformAdminSessionService.rotate()`
  takes only the presented token, stores only the successor's **hash**, returns
  the raw successor exactly once, and offers no idempotency key and no
  reconciliation endpoint. A BFF that lost the response has nothing to ask and
  nothing to retry with — re-presenting the predecessor is precisely what
  triggers revocation.

  Closing it needs a **backend** change, which this ADR does not design: an
  idempotency key on rotation, or a bounded grace window in which the immediate
  predecessor is accepted once more, or an endpoint that returns the current
  successor for a session. Choosing between those is validation item 11.

  **Until then the residual is explicit**: a lost rotation response logs that
  administrator out, and they log in again. Bounded and recoverable, but it is a
  real failure mode of the design rather than an implementation detail — and it
  qualifies Decision 1, which says the backend contract is unchanged. It is
  unchanged for *transport*; this is the one place the design may still require
  something from it.

  **The lock also has to be shared, not process-local.** A Next.js BFF runs on
  several workers or serverless instances, so an in-process mutex or
  single-flight leaves two instances racing the same stored refresh token — the
  exact scenario, with the mitigation appearing to be in place. Coordination
  must go through whatever backing store holds the session (Decision 2's
  server-side store), so all instances serialise against the same record.
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

**These were acceptance blockers; the ADR was accepted over them on 2026-08-31,
so they are now implementation prerequisites.** None of this may ship until they
are answered — the decision being settled is not the same as the design being
buildable:

1. Engineering-lead sign-off on the BFF boundary — specifically that the browser
   never receives a platform-admin token, and how that is enforced rather than
   documented.
2. The BFF session store design, and the cookie domain topology it implies.
   **Not** "stateless signed cookie versus server-side session": a stateless
   cookie carrying the token pair would send those tokens to the browser, which
   Decision 2 exists to prevent — signing gives integrity, not confidentiality.
   A cookie holding only an opaque handle is server-side state by definition. So
   the open question is *what* server-side store, not *whether* one.

   **Choosing the store is choosing its custody, so this prerequisite is not
   closed by naming a technology** (**R-033**). That store holds the raw
   refresh token for every logged-in administrator, and a reader of it bypasses
   MFA entirely — the factor was spent at login and what remains mints access
   tokens directly. Required with the choice, not after it: **encryption at
   rest under a key held outside the store**, access restricted to the BFF
   runtime identity, **replicas and backups held to the same standard** as the
   primary, reads logged as a detection surface, and a **tested mass-revocation
   path** (see R-033's contingency — the method that looks like one is
   per-administrator and unwired). Retrofitting these onto a chosen store is
   the expensive order.
3. A named TOTP implementation and a recovery design that does not weaken the
   factor it protects — **and seed custody, which is the part that is easy to
   leave undefined.** Verification requires the backend to keep each symmetric
   seed in recoverable form, so a plaintext column or an unprotected backup
   would let anyone who can read the database generate every administrator's
   second factor. MFA would then be defeated by exactly the compromise it is
   meant to survive, silently and for every account at once. Required:
   **application-level encryption with the key held outside the database**,
   access restricted to the verification path, backups protected to the same
   standard as the primary store, and a defined re-encryption path for key
   rotation.
4. Confirmation of whether the legacy PHP dashboard's `admin`-role surface runs
   in parallel during Phase 2, and if so whether both authenticate independently.
5. Enforcement mechanism for keeping `/api/platform-admin/**` BFF-only
   (Decision 8) — network reachability, a required BFF credential, or both.
6. ~~The active-admin lookup required by Decision 5, with a regression test per
   transport.~~ **Met.** **R-026** closed, **D-145** accepted, merged in PR #152;
   the lookup runs on every request. Struck rather than deleted so the
   dependency stays visible, but it is not outstanding work and must not be
   re-planned as such.
7. **Concrete session bounds**: the idle timeout and the non-renewable absolute
   family cap Decision 3 requires, as numbers — **and the cap enforced on the
   backend family, not in the BFF**. A cookie or session-store expiry does not
   constrain a raw refresh token stolen from that store and replayed through an
   allowed backend channel, which is exactly the R-033 scenario the cap is
   supposed to bound. `rotate()` today issues every successor at `now + 7 days`
   and never consults the family's original issuance, so the family slides
   forever and an implementation could satisfy the numeric requirement entirely
   in the BFF. Java must **persist or derive the family origin, refuse rotation
   past the cap, and have a test that advances a family beyond it**.
8. The MFA-bearing login exchange Decision 4 implies — challenge/response,
   enrolment, and how a step-up-satisfied session is represented **with its
   maximum age, single-use, action-binding and target-binding rules**. The last
   is not a detail of the third: an approval bound to "suspend" but not to
   *which company* is consumable by a hijacked session against a different
   tenant, which is the scenario step-up exists for.
9. Attempt throttling for the password and TOTP steps (Decision 7), with the
   limit and lockout window chosen — **and counted in shared, restart-surviving
   state at the Java backend boundary**, not per BFF process. A process-local
   counter meets the stated limit while giving each worker its own budget, so
   the validation is **8 attempts through separate workers**, not 8 attempts.
10. Whether ADR-0005's *list and revoke sessions individually* requirement is
    delivered for this surface before it ships, or explicitly deferred. It is
    unimplemented on both surfaces today, so this ADR does not create the gap —
    but an admin surface with MFA and no way to see or kill your own sessions is
    an odd place to leave it.
11. How a lost rotation response is recovered: an idempotency key on
    `rotate()`, a bounded grace window accepting the immediate predecessor once,
    or an endpoint returning a session's current successor. Needs a backend
    change; the BFF cannot close it alone.

    **Each option pays a different price, and none is free — so this stays a
    decision rather than a recommendation.** "Idempotency key" and "return the
    current successor" both require Java to **retain the raw successor** after
    the first call, because the repository keeps only its hash: a second copy of
    exactly what R-033 models for the BFF, in a system R-033 does not cover. If
    either is chosen it inherits the same controls — bounded retention,
    encryption under an external key, matching access and backup handling.

    The **bounded grace window** retains nothing, but it is not therefore the
    safe choice: accepting the immediate predecessor again **deliberately
    weakens reuse detection**. Inside that window a thief holding a
    just-rotated token can present it without tripping ADR-0005's
    family-revocation signal, and if the legitimate BFF has already stored its
    successor, that replay mints another successor and can take over or
    terminate the session. Choosing it means bounding the window against the
    theft-to-use interval it opens, and testing that a replay outside it still
    revokes the family.

    An earlier version of this text preferred the grace window on
    plaintext-custody grounds alone. That traded a storage risk for a detection
    risk without saying so.

**Identity separation is deliberately not on this list.** **D-027** made
individual platform-admin identity a P0 requirement and **F-26** records it as
substantially closed — separate identities, structurally separated JWT sessions,
`platform_admin_refresh_tokens`, and audit attribution with a NOT NULL admin FK.
An earlier draft of this ADR listed it as an unanswered acceptance blocker, which
would have reopened an accepted security decision against evidence that already
exists.

## Open Questions

- ~~Do platform admins exist as their own identity type, or as a role on a
  shared identity?~~ **Answered 2026-08-30 by reading the code**: their own type
  (`PlatformAdmin`, `platform_admins`, `platform_admin_refresh_tokens`), with no
  self-registration. This was the blocking question when the ADR was opened.
- Does the browser-facing admin surface share a **schemeful site** with the BFF
  that sets the cookie? That — not "same origin" — is what decides `SameSite`
  behaviour: different subdomains are different origins but usually the same
  site, so `Lax` still attaches. The **BFF-to-Java** leg is irrelevant to this
  question: it is server-to-server and the browser's session cookie is never
  attached to it.
- What is the **retention** requirement for `PlatformAdminAuditEvent`? The audit
  trail exists; how long it must survive does not appear to be recorded.
- Is there a regulatory or customer-contractual requirement that would force
  Option D (external IdP) sooner than convenience would?
- Does the existing PHP dashboard's `admin`-role surface keep running during
  Phase 2, and if so, do both surfaces authenticate independently in the
  meantime?

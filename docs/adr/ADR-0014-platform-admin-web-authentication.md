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

### 1. The ADR-0005 model applies unchanged

Short-lived access token, opaque rotating refresh token with reuse detection and
family revocation, server-side session persistence. No second model, no
parallel implementation: the same `RefreshTokenService` semantics the mobile and
desktop clients use.

### 2. The browser never holds a JS-readable credential

The session is carried in cookies set by the backend:

| Attribute | Value | Why |
|---|---|---|
| `HttpOnly` | yes | script cannot read the credential, so an XSS does not become a stolen session |
| `Secure` | yes | never transmitted over plaintext |
| `SameSite` | `Lax` | blocks cross-site cookie attachment on state-changing navigations |
| `Path` | scoped to the admin surface | not attached to unrelated backend routes |

**Explicitly rejected: a bearer JWT in `localStorage` or `sessionStorage`.** This
is the default many Next.js examples still reach for, and it means any script
executing on the page — first-party bug, compromised dependency, injected
content — can read and exfiltrate a credential that suspends companies.
`HttpOnly` removes that entire class of outcome for the cost of a cookie
attribute.

Because cookies are attached automatically, **CSRF protection is required** and
is part of this decision, not a follow-up: `SameSite=Lax` plus an explicit
anti-CSRF token on every state-changing request.

### 3. Shorter session lifetimes than the mobile default

The 60-day refresh TTL is calibrated for a phone in a pocket. For a surface that
suspends companies it is too long. This surface gets its own bounds — an idle
timeout in hours and an absolute cap in days, not months — configured
separately from the client defaults rather than inheriting them.

### 4. MFA is required, with step-up on destructive actions

Every platform-admin identity enrols in TOTP. Beyond login, destructive
operations — company suspension, company deletion — require step-up
re-authentication, so a hijacked *session* is not automatically authority to
destroy a customer's tenancy.

The user population is small and internal, which makes this cheap to operate and
removes the usual objection.

### 5. Authorization is unchanged

**ADR-0010**'s model already anticipates this surface: authorization data is
loaded and validated server-side on every request, explicitly so that web,
mobile and desktop cannot diverge in freshness. This ADR changes how a caller
proves *who* they are, not what they may do.

## Alternatives Considered

### Option A — Port the PHP dashboard's session auth

Reuse `$_SESSION['admin_logged_in']` semantics against the Java backend.

**Rejected.** Its only argument is precedent, and precedent is exactly what
Constraint 1 removes — there is no frozen client forcing it. It also imports a
design carrying open security findings into a new application.

### Option B — Bearer token in browser storage

Issue the same access/refresh pair the Flutter clients receive and store it in
`localStorage`, with an `Authorization` header.

**Rejected.** Maximum blast radius from any XSS on the highest-privilege surface
in the system. Its advantages — symmetry with the mobile clients, no CSRF
concern — do not outweigh making the credential script-readable here.

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

- The Java backend gains a **second authentication transport**: bearer tokens
  for the Flutter clients, cookies for the admin web surface. Both must resolve
  to **one** authoritative validation and authorization path — two entry points,
  not two security models. This is the main implementation risk (see Risks).
- The Next.js app needs a server-side session boundary (route handlers or a BFF)
  rather than calling the Java API directly from the browser, so cookies stay
  first-party and the token never reaches client JavaScript.
- Cookie domain and subdomain topology becomes a deployment decision, not just
  an application one.
- MFA needs enrolment, recovery and lockout flows — small population, but they
  cannot be skipped.
- Platform-admin actions need audit logging that records the acting admin
  identity; this surface's whole purpose is actions taken *on* customers.
- **Sequencing**: this is Phase 2 work. Phase 1 has three open G11 blockers
  (**R-023**, **R-024**, **R-025**) and a pending cutover. Recording the decision
  now is cheap; building against it before the port lands is scope expansion
  across an unfinished migration.

## Risks

- **Two transports, one backend.** If cookie and bearer paths diverge in how
  they resolve identity or authorization, one becomes a bypass of the other.
  Mitigation: a single validation component both entry points delegate to, and a
  test that asserts an identical authorization outcome for the same principal
  arriving either way.
- **CSRF, newly relevant.** Cookies reintroduce a class the bearer clients never
  had. Mitigation: `SameSite=Lax` plus anti-CSRF tokens, and negative tests that
  a cross-site state-changing request is rejected.
- **MFA lockout of the only admins.** A recovery path is required, and it must
  not itself become the weakest link.
- **Session-fixation and post-auth rotation.** The session identifier must be
  rotated on login and on privilege change.
- **Accepted for now**: self-managed authentication carries lifecycle burden
  (joiners/leavers, credential rotation) an IdP would absorb — see Option D.

## Validation Evidence

**None yet — this decision has not been validated.** Required before it can move
from `Proposed` to `Accepted`:

1. Engineering-lead sign-off on the two-transport approach, specifically the
   single-validation-path requirement.
2. A decision on the Next.js session boundary — route handlers versus a
   dedicated BFF — with the cookie domain topology it implies.
3. Confirmed idle and absolute session bounds for this surface (Section 3
   states the shape, not the numbers).
4. A named TOTP implementation and a recovery design.
5. Confirmation that platform-admin identities are provisioned distinctly from
   company/HR identities, so this surface's stricter rules cannot be inherited
   by, or bypassed through, a company-scoped account.

## Open Questions

- Do platform admins exist as their own identity type today, or as a role on a
  shared identity? Item 5 above cannot be answered until this is.
- Does the admin surface share an origin with the Java backend, or is it
  cross-origin? This decides whether `SameSite=Lax` is sufficient or the
  topology needs revisiting.
- What is the audit retention requirement for platform-admin actions?
- Is there a regulatory or customer-contractual requirement that would force
  Option D (external IdP) sooner than convenience would?
- Does the existing PHP dashboard's `admin`-role surface keep running during
  Phase 2, and if so, do both surfaces authenticate independently in the
  meantime?

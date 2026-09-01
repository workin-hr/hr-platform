# ADR-0015: Authentication For The Platform-Admin Web Surface (JTE, In-Process)

## Metadata

| Field | Value |
|---|---|
| ADR ID | ADR-0015 |
| Title | Authentication for the platform-admin web surface, server-rendered with JTE |
| Status | Accepted |
| Date | 2026-09-01 |
| Owners | Solution Architect (primary), Product (scope input) |
| Deciders | Repository owner |
| Related Issues | `hr-legacy#11` (shared platform-admin password — the finding this surface's identity model exists to close, D-027/F-26); `hr-platform#25` (dashboard retirement, ADR-0009) |
| Supersedes | ADR-0014 |
| Superseded By | None |

## Context

**ADR-0014 designed this surface as a Next.js application with a server-side
BFF holding the platform-admin token pair.** The repository owner corrected that
premise on 2026-09-01: the admin web is **JTE pages inside the existing Spring
application** — one deployment, server-side rendered, on the application's
existing authentication and session model. Not a separate Java web service, and
not a variation of the Next.js design.

This is an **architecture correction, not a refinement**, which is why ADR-0014
is superseded rather than amended. Most of ADR-0014's security surface existed
*because of* the browser/backend split, and does not survive its removal.

**ADR-0009's recommendation of Next.js is corrected to JTE** by the same
instruction; its Open Questions entry is updated to point here.

## Decision

The platform-admin web surface is **server-rendered JTE templates inside the
existing Spring Boot application**, authenticated by a **server-side HTTP
session** established at login. No platform-admin token is issued to, stored in,
or read by the browser.

### 1. One deployment, one authentication model

The admin pages are controllers in the same application that already serves
`/api/platform-admin/**`. Authentication happens once, server-side; the browser
holds a session cookie and nothing else. There is no second service, no second
credential store, and no token in transit to a client.

`PlatformAdminAuthController`'s existing JSON/bearer contract is **unchanged**
and remains what the API clients use. The JTE surface does not consume it over
HTTP — it calls the same services in-process.

### 2. What ADR-0014 required that no longer applies

Each of these existed only because a separate frontend held credentials. With
the surface in-process there is nothing to hold, so they are **removed, not
deferred**:

| ADR-0014 requirement | Why it is gone |
|---|---|
| BFF session store holding raw refresh tokens (**R-033**) | No BFF; no refresh token leaves the application |
| Rotation-result custody (idempotency key / grace window / successor endpoint) | No cross-process rotation to lose a response to |
| "The browser never receives a token", and enforcing it | The browser was never going to; there is no client-side token path to police |
| Cookie domain / subdomain topology, cross-origin concerns | Same origin, same application |
| Logout revocation outbox, and its audit idempotency | Session invalidation is local and synchronous |
| Restricting `/api/platform-admin/**` to a BFF caller | The admin UI is not a caller of that API |

### 3. What survives, because it never depended on the BFF

- **MFA/TOTP**, with enrolment and recovery, and **seed custody**: the backend
  still stores a symmetric seed in recoverable form, so it still needs
  application-level encryption under a key held outside the database, restricted
  access, backups to the same standard, and a defined re-encryption path. The
  BFF's disappearance changes none of that.
- **Step-up for destructive operations**, with all four bounds: maximum age in
  minutes, single use, bound to the canonical operation, **and bound to the
  resource identifier plus a server-recomputed digest of the security-relevant
  request parameters**. An approval bound to "suspend" but not to *which
  company* is consumable against a different tenant.
- **Authentication throttling** on the password and TOTP steps, counted in
  shared, restart-surviving state. Legacy's 8 attempts / 15 minutes is the
  floor. A six-digit second factor without throttling is a feasible online
  search.
- **Per-request authorization**: the active-admin lookup already enforced
  (**R-026** closed, **D-145**), inherited by the JTE controllers because it
  lives in the request path rather than in a handler.
- **Session invalidation** that is immediate and complete on logout, on
  administrator deactivation, and on password change.
- **Auditability**: `PlatformAdminAuditEvent` attribution for administrative
  actions, unchanged.

### 4. What the in-process model introduces that the BFF design did not

- **CSRF.** A cookie-authenticated, server-rendered surface is CSRF-exposed in a
  way a bearer-token API is not. Every state-changing JTE route requires a
  synchroniser token; the JSON API keeps its bearer contract and its existing
  CSRF-disabled chain. These are two different security models in one
  application and the filter-chain boundary between them must be explicit.
- **Session cookie configuration** is now a security control: `HttpOnly`,
  `Secure`, `SameSite`, a bounded idle timeout, and a **non-renewable absolute
  cap** so a session cannot slide indefinitely. Session fixation must be
  prevented by rotating the session id on login.
- **Template output escaping.** JTE escapes by default; any use of raw output on
  this surface is a review point, since the data rendered includes
  administrator-supplied and tenant-supplied strings.

### 5. Sequencing, unchanged

This remains **Phase 2**. Phase 1 is the PHP→Java port and its parity
verification, which is where effort goes first. Recording the decision now is
cheap; building against it before the port is verified is scope expansion across
an unfinished migration.

## Consequences

- The security surface is **substantially smaller** than ADR-0014's: no second
  credential store, no cross-process token custody, no cookie topology, no
  outbox. R-033 is closed as not-applicable rather than mitigated.
- One deployment and one authentication model to operate, at the cost of the
  admin UI sharing a runtime with the API — a change to either is a redeploy of
  both.
- **CSRF and session-cookie hardening become first-class**, where the BFF design
  had pushed them into a separate runtime.
- The JTE surface must not become a second, divergent authorization
  implementation: it calls the same services and inherits the same per-request
  checks, or it will drift from the API's model.

## Alternatives Considered

- **Next.js with a server-side BFF (ADR-0014).** Rejected by the repository
  owner on 2026-09-01. It is a defensible design in isolation — it keeps tokens
  off the browser and suits a team already invested in React — but it buys that
  with a second deployment, a second credential store holding every live raw
  refresh token (**R-033**), cross-process rotation recovery, cookie topology,
  and an enforcement problem ("the browser must never receive a token") that
  only exists because a browser app is making the calls. In-process rendering
  removes all of it rather than mitigating it.
- **A separate Java service rendering JTE.** Rejected: it reintroduces the
  deployment split and an inter-service authentication boundary while keeping
  none of the Next.js ecosystem benefit that motivated the split in the first
  place. Explicitly ruled out by the owner.
- **Keep the PHP dashboard's `admin`-role pages.** Rejected by ADR-0009: the
  dashboard is being retired, and it authenticates with the shared admin
  password this surface's identity model exists to close (`hr-legacy#11`,
  D-027/F-26).
- **A JSON API plus a static SPA served by the same application.** Rejected: it
  keeps the browser-holds-a-token problem without the benefit of a real BFF, and
  is the worst of both designs.

## Risks

- **Two authentication models in one application.** A cookie-authenticated,
  CSRF-protected UI chain now sits beside a bearer-authenticated, CSRF-disabled
  API chain. A filter-chain matcher that is too broad on either side silently
  gives one model's requests the other's guarantees — the failure mode is quiet,
  and it is why prerequisite 5 requires the boundary to be tested rather than
  reviewed.
- **CSRF is new exposure**, not inherited. The bearer API never needed it; a
  cookie-authenticated state-changing route does.
- **Authorization drift.** If the JTE controllers re-implement checks rather
  than calling the same services, the UI and the API diverge over time and the
  UI becomes the weaker path. Mitigated by rendering through the same services
  and inheriting the per-request active-admin lookup (**D-145**).
- **Template injection.** JTE escapes by default, but the surface renders
  administrator- and tenant-supplied strings, so any raw output is a review
  point.
- **Shared runtime.** The admin UI and the API redeploy together; an admin-UI
  change now carries API deployment risk.
- **Unbuilt-surface risk.** Every requirement here is a prerequisite for work
  that has not started. The main risk to the decision is that it is implemented
  from the prerequisite list without re-reading the reasoning, which is why each
  prerequisite states why it exists rather than only what to do.

## Validation Evidence

**The evidence for this decision is what the in-process model inherits**, all of
it already merged and tested — that is what makes the correction safe to accept
rather than merely cheaper:

- **Per-request authorization already holds.**
  `PlatformAdminAuthenticationFilter` loads the `platform_admins` row and
  verifies `active` on every request, failing closed when the row is absent.
  `PlatformAdminAuthFlowTest#aTokenIssuedBeforeDeactivationStopsWorkingImmediately`
  logs in, confirms the token works, deactivates the row, and asserts the same
  unexpired token is refused. **R-026** closed, **D-145** accepted, PR #152.
  The JTE controllers inherit this because it lives in the request path, not in
  a handler — which is the specific property that makes an in-process UI safe to
  add.
- **Platform-admin identity is separated.** `PlatformAdmin`, `platform_admins`,
  `platform_admin_refresh_tokens`, and audit attribution through a NOT NULL
  admin foreign key. **D-027**, **F-26**.
- **Session revocation semantics are covered.** `PlatformAdminSessionFlowTest`
  exercises logout, rotation, reuse-detection revoking the family, and
  fail-closed rotation for a deactivated administrator.
- **The API contract this decision leaves alone is pinned.**
  `PlatformAdminAuthController`'s JSON/bearer transport is unchanged by this
  ADR, and its existing tests continue to cover it — the JTE surface calls the
  same services in-process rather than consuming that API.

**Evidence this decision does not have, stated so it is not mistaken for
covered:** the JTE surface itself is unbuilt, so CSRF protection, session-cookie
flags, session-id rotation, MFA/TOTP and step-up have no implementation and no
tests. Each is an implementation prerequisite below, and each names what would
close it. Accepting this ADR settles the architecture; it does not assert that
the surface is secure, because the surface does not exist.

## Implementation Prerequisites

Design settled; none of this may ship until these are answered:

1. **TOTP implementation, enrolment, recovery, and seed custody** — encryption
   under a key held outside the database, restricted access, protected backups,
   a defined re-encryption path. **The first-time enrolment ceremony is part of
   this, not a detail of it**: the window between an administrator's account
   existing and their second factor being bound is a window in which a password
   alone is sufficient, and this population is bootstrap-provisioned with no
   self-registration, so someone else creates the account. Required: enrolment
   authenticated by more than the password being enrolled against, a single-use
   time-bounded enrolment token rather than an open "set your TOTP" page, the
   seed shown exactly once and never retrievable afterwards, confirmation by a
   verified code before the factor is considered bound, and the account unable
   to perform destructive operations until it is. Recovery must not become a
   second, weaker enrolment path.
2. **Step-up representation** with maximum age, single use, action binding
   **and** target/request-digest binding, recomputed server-side.
3. **Throttling** for the password and TOTP steps, in shared restart-surviving
   state, validated by attempts through separate workers rather than a count.
4. **Session bounds as numbers**: idle timeout and non-renewable absolute cap,
   plus session-id rotation on login. **The cap must also bound the API tokens**,
   not just the UI session. `PlatformAdminAuthController` still issues access
   and refresh tokens for API clients, and `rotate()` currently issues every
   successor at `now + 7 days` without consulting the family's origin, so a
   family slides indefinitely and an access token minted near the cap outlives
   it. Java must persist or derive the family origin, refuse rotation past the
   cap, and clamp an issued access token's expiry to the family's remaining
   life — proven by a test that advances a family beyond the cap and asserts
   both the refusal and the clamp.
5. **CSRF protection** on every state-changing JTE route, and an explicit,
   tested filter-chain boundary between the cookie-authenticated UI and the
   bearer-authenticated API.
6. **Session-cookie flags** — `HttpOnly`, `Secure`, `SameSite` — chosen and
   pinned by a test.
7. Whether the legacy PHP dashboard's `admin`-role surface runs **in parallel**
   during Phase 2, and if so whether both authenticate independently. **If it
   does, MFA is only as strong as the weakest surface**: the PHP dashboard
   authenticates with the shared admin password (`hr-legacy#11`) and has no
   second factor, so leaving it reachable means every control in this ADR can
   be walked around by logging in there instead. Running them in parallel
   therefore requires the PHP surface to be either MFA-gated too, or restricted
   so it cannot perform anything this surface protects, or taken down. "Both
   authenticate independently" is not an acceptable answer on its own.
8. Whether ADR-0005's *list and revoke sessions individually* is delivered for
   this surface or explicitly deferred. It is unimplemented on both surfaces
   today, so this ADR does not create the gap.
9. `PlatformAdminAuditEvent` retention.

## Open Questions

- Does the JTE surface reuse the Spring Security session infrastructure the
  tenant chain uses, or does it get its own chain? The filter-chain boundary in
  prerequisite 5 depends on the answer.

## Evidence

Repository owner instruction, 2026-09-01: JTE pages inside the existing Spring
application, one deployment, existing authentication/session model, and BFF-
specific requirements removed rather than carried over. ADR-0014 for the design
this supersedes and the reasoning that remains valid. **D-145**/**R-026** for
the per-request active-admin check this inherits. **D-027**/**F-26** for the
platform-admin identity separation this builds on.

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

`PlatformAdminAuthController`'s existing JSON/bearer contract is **not
consumed by this surface**: the JTE controllers call the same services
in-process rather than over HTTP.

**That endpoint is nonetheless in scope for MFA, and cannot be left as-is.**
`POST /api/platform-admin/login` today accepts `phone` and `password` and
returns an access/refresh pair with no second factor. Left that way, it is a
door around every control in this ADR: a stolen password authenticates there
and the resulting bearer token is accepted by the `/api/platform-admin/**`
chain for the same privileged operations the JTE surface gates behind TOTP.
Requiring TOTP on the UI while leaving the API open does not produce MFA; it
produces the appearance of it.

The cost of closing it is low and was checked rather than assumed: **no client
outside this repository calls it.** The only references are
`SecurityConfig` and four backend tests
(`PlatformAdminAuthFlowTest`, `PlatformAdminSessionFlowTest`,
`PlatformAdminAuditTest`, `PlatformAdminDomainSeparationTest`) — the Flutter
desktop and mobile clients authenticate through the tenant and legacy surfaces,
not this one. **D-111** (zero client change) therefore does not protect this
endpoint, and prerequisite 8 requires it be closed before privileged operations
ship.

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
- **Per-request authorization**: the active-admin lookup enforced by **D-145**
  (**R-026** closed) — *as a requirement*. It is **not inherited
  automatically**, and an earlier draft of this ADR wrongly said it was.
  `PlatformAdminAuthenticationFilter` is installed only on the stateless
  `/api/platform-admin/**` chain and performs its lookup only after parsing an
  `Authorization: Bearer` token, so a cookie-authenticated request on a
  separate chain reaches no such filter and revalidates nothing. The JTE chain
  must therefore perform its **own** per-request lookup of the authenticated
  administrator and reject a row that has become inactive or been deleted,
  rather than trusting the session's contents. Without it, deactivating an
  administrator would leave their existing session working until it expired,
  which is exactly what D-145 exists to prevent (prerequisite 9).
- **Session invalidation** that is immediate and complete on logout, on
  administrator deactivation, and on password change.
- **Auditability**: `PlatformAdminAuditEvent` attribution for administrative
  actions — **as a requirement, not as an existing capability.** Calling it
  "unchanged" overstated what is implemented: `PlatformAdminAuditEventType`
  contains only `LOGIN`, `LOGIN_FAILED`, `LOGOUT`, and
  `SESSION_REUSE_REVOKED`, and the row carries only actor, type, a free-text
  detail string and a timestamp. It can record *that someone logged in*; it
  cannot record that a company was suspended, by whom, against which company,
  or with which step-up approval. The surface this ADR describes performs
  exactly those administrative actions, so the audit model has to grow with it
  (prerequisite 10).

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

Design settled. Items 1 and 7 are **answered** by **D-152** and specified
below; the rest still need answers. None of this may ship until all of them
are both answered and implemented:

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

   **Existing rows are the hard case and must be solved before TOTP is
   enforced.** `PlatformAdmin` today has exactly four columns — `id`, `phone`,
   `passwordHash`, `active` — with no TOTP seed, and `PlatformAdminBootstrap`
   creates the row from a phone and a password alone. Both obvious answers are
   wrong: enforcing TOTP immediately locks out every existing administrator
   with no path back in, and letting the first password-authenticated session
   self-enrol means whoever reaches the login first with a stolen password
   binds the second factor to their own device and locks the real
   administrator out. **Settled by D-152 (2026-09-01): an
   operator-assisted bootstrap flow.** A password-only authenticated session
   may **not** claim the first factor. The flow is:

   1. Generate a **cryptographically random, short-lived, single-use**
      enrolment token **server-side**, associated with one specific
      `PlatformAdmin`.
   2. Deliver it to the known administrator over a **separately verified
      out-of-band channel**.
   3. Require **password *and* bootstrap token** before enrolment is allowed.
   4. **Invalidate the token immediately** on successful enrolment.
   5. Grant normal admin access only after a **successful TOTP verification**,
      not merely after enrolment.
   6. Never send or persist the raw TOTP seed anywhere except the
      application's protected TOTP credential store.

   The token is stored **hashed** if persisted at all, carries a short expiry,
   and its **issuance, use and revocation are each audited** — extending
   prerequisite 10's event types, not only the administrative actions.
   Existing rows migrate in an explicitly **unbound** state and cannot perform
   destructive operations until bound. Regression tests cover upgrading an
   existing password-only row through the full flow, and assert that a
   password alone — with no live token — cannot enrol.
2. **Step-up representation** with maximum age, single use, action binding
   **and** target/request-digest binding, recomputed server-side.
3. **Throttling** for the password and TOTP steps, in shared restart-surviving
   state, validated by attempts through separate workers rather than a count.
   **Attempts against unknown identifiers must consume the same budget as
   known ones**, and the unknown-identifier path must do the same work as the
   known one. Today `PlatformAdminLoginService` throws immediately when
   `findByPhone` misses and runs BCrypt only for an existing row, so an
   unauthenticated caller can both enumerate which phone numbers are
   administrators — by response timing — and make unlimited attempts against
   ones that are not. Required: a shared budget keyed so a miss is counted,
   and a dummy verification against a fixed hash on the miss path so the two
   outcomes are not distinguishable by timing.
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
   bearer-authenticated API. **The UI chain must carry its own
   `securityMatcher`, and every JTE route must be proven to land on it.**
   Failing open here is silent and severe: `SecurityConfig.tenantSecurityFilterChain()`
   is the order-3 catch-all, declares **no** `securityMatcher`, disables CSRF,
   and authenticates with `JwtAuthenticationFilter` — so a JTE mapping omitted
   from the UI matcher does not 404 or 403, it is quietly served under a chain
   that accepts a **tenant** bearer token and applies no CSRF protection at
   all. Testing a handful of named routes cannot detect the omission, because
   the failure is precisely the route nobody listed. Required: a test that
   **enumerates the application's JTE controller mappings from the handler
   registry** and asserts each resolves to the UI chain, so a newly added page
   that is not covered fails the build rather than shipping unprotected.
6. **Session-cookie flags** — `HttpOnly`, `Secure`, `SameSite` — chosen and
   pinned by a test.
7. **The legacy PHP dashboard's `admin`-role surface at cutover.** While it
   runs in parallel, **MFA is only as strong as the weakest surface**: the PHP dashboard
   authenticates with the shared admin password (`hr-legacy#11`) and has no
   second factor, so leaving it reachable means every control in this ADR can
   be walked around by logging in there instead. "Both authenticate
   independently" is **not** an acceptable answer: independent authentication
   is the problem, not the mitigation, because the weaker of the two doors is
   the one an attacker uses.

   **Settled by D-152 (2026-09-01): the legacy PHP admin surface is disabled
   at cutover.** It must not remain reachable as an alternative authentication
   path once the JTE admin is live. If it is retained for rollback it stays
   deployed or staged but **network-inaccessible by default**, exposed only as
   part of an explicit, deliberate rollback procedure. This is a shipment
   gate: the JTE surface does not perform a privileged operation while the PHP
   surface is reachable.
8. **MFA on the bearer login, or that surface restricted.**
   `POST /api/platform-admin/login` currently returns a token pair for
   `phone` + `password` alone, which walks around the JTE surface's TOTP for
   the same privileged operations. Enforcing a second factor there changes the
   request/response flow, so the API's MFA challenge contract must be defined
   explicitly rather than left implicit. No client outside this repository
   calls it — only `SecurityConfig` and four backend tests — so **D-111** does
   not constrain the change and the alternative of restricting the surface is
   equally available. One of the two ships before privileged operations do.
9. **Per-request active-admin revalidation on the cookie chain**, not inherited.
   `PlatformAdminAuthenticationFilter` runs only on the bearer chain and only
   after parsing an `Authorization` header, so the JTE chain needs its own
   equivalent, proven by a test that deactivates an administrator mid-session
   and asserts the next page request is refused rather than served until
   expiry (**D-145**).
10. **Audit coverage for administrative actions, then retention.**
    `PlatformAdminAuditEventType` today holds only `LOGIN`, `LOGIN_FAILED`,
    `LOGOUT`, `SESSION_REUSE_REVOKED`, and the row carries only actor, type, a
    free-text detail and a timestamp — it cannot answer *who suspended which
    company, when, under which step-up approval*. Required before the surface
    performs administrative actions: event types for those actions **and for the D-152 bootstrap token's issuance, use and revocation**, a
    structured target (type and identifier) rather than prose in `detail`, a
    link to the step-up approval that authorised it, and the event written in
    the same transaction as the action so a committed change cannot exist
    without its audit row. Retention is decided once there is something worth
    retaining.
11. **Session storage across workers, or enforced affinity.** The application
    has no session infrastructure today — every chain is
    `SessionCreationPolicy.STATELESS` — so introducing a server-side session
    introduces the question with it. Default in-memory sessions break under the
    multi-worker deployment prerequisite 3 already assumes: the same cookie is
    authenticated on one worker and anonymous on the next, and logout cannot
    reliably invalidate a session held in another worker's heap, which
    silently defeats the invalidation requirement above. Spring Session JDBC on
    the existing datasource is the default choice here — it needs no new
    infrastructure, and the alternative of sticky affinity moves the problem
    into the load balancer without solving invalidation. Proven by tests that
    log in, make an authenticated request, and log out **with consecutive
    requests served by different workers**.
12. **TOTP codes are single-use, not just the approvals they mint.** Single use
    currently applies to the resulting step-up approval, so within one accepted
    time window the same six digits can mint several individually-single-use
    approvals bound to different targets, or be replayed between the login step
    and a step-up. Required: the last accepted time-step is recorded per
    administrator and a code at or below it is refused, so an observed code
    cannot be reused at all.
13. Whether ADR-0005's *list and revoke sessions individually* is delivered for
    this surface or explicitly deferred. It is unimplemented on both surfaces
    today, so this ADR does not create the gap.

## Implementation Status

Added 2026-09-03 (**D-160**), when Phase 1 closed and work on this surface
began. This section records what exists; it does not amend the decision or the
prerequisites above.

**Built:**

| Prerequisite | State | Where |
|---|---|---|
| 5 — CSRF on every state-changing route, and a tested chain boundary | **Done** | `PlatformAdminWebSecurityConfig` carries its own `securityMatcher`; `PlatformAdminWebChainCoverageTest` enumerates the handler registry and asserts every admin mapping resolves to that chain, plus that the chain does not swallow unrelated paths |
| 6 — session-cookie flags pinned by a test | **Done** | `application.properties`; asserted in `PlatformAdminWebSessionTest` |
| 9 — per-request active-admin revalidation on the cookie chain | **Done** | `PlatformAdminSessionRevalidationFilter`; a deactivation mid-session is refused on the next request |
| 11 — session storage across workers | **Done** | Spring Session JDBC on the existing datasource, tables in `common/V46`; logout is asserted to delete the shared row, not just the local one |
| 4 — session bounds, UI half | **Done** | 30-minute idle timeout, non-renewable 8-hour absolute cap stamped at login and enforced per request |
| 3 — throttling in shared, restart-surviving state, with unknown identifiers consuming the same budget and doing the same work | **Done** | `PlatformAdminLoginThrottle` + `platform_admin_login_attempts` (`common/V47`); the miss path verifies against a fixed dummy hash so it costs the same, and `PlatformAdminLoginThrottleTest` proves a miss spends budget by failing eight times against a phone and only then creating that administrator |
| 4 — the same cap bounding API token families | **Done** | The family's origin is persisted on every row (`common/V48`) and copied forward on rotation, so rotating cannot reset it and pruning rotated rows cannot lose it. `rotate()` refuses a family past origin + cap and revokes it; both the successor refresh token and the issued access token are clamped to the family's remaining life. `PlatformAdminFamilyCapTest` advances a family past the cap and asserts the refusal, and near the cap asserts both clamps |

**Not built, and blocking any privileged operation:** prerequisites 1 (TOTP,
enrolment, seed custody), 2 (step-up binding), 8 (MFA on the bearer login or
that surface restricted), 10 (audit coverage for administrative actions), 12
(single-use TOTP codes), and 13 (session listing/revocation, or an explicit
deferral).

**The number chosen for the API family cap needs owner confirmation.** ADR-0015
requires the cap to bound API tokens but names no figure. It is set to seven
days -- equal to the configured refresh-token lifetime -- so the change removes
the *sliding* without shortening any single token a client already relies on: an
administrator who keeps refreshing now re-authenticates weekly instead of never.
A shorter cap is defensible for the highest-privilege surface and is a product
call, not an implementation one.

**One decision taken while implementing prerequisite 3, recorded because it is
a trade rather than a detail:** an exhausted budget answers with the same 401
and the same message as a wrong password. A distinct status would tell an
attacker precisely when to back off, and it would change the bearer API's
response contract that prerequisite 8 will revisit on its own terms. The cost
is that a locked-out administrator is told "invalid credentials" rather than
"try again later"; the lockout is visible to operators through the audit
events instead. Revisit this alongside prerequisite 8.

The surface therefore performs **no administrative action**. It authenticates,
renders one page that says so, and logs out. That is deliberate: this ADR states
that none of it may ship until every prerequisite is answered and implemented,
and shipping the company operations first would be the exact failure the
prerequisite list exists to prevent.

## Open Questions

None blocking. The filter-chain question an earlier draft left open is
answered by prerequisites 5 and 11: the JTE surface gets **its own chain with
an explicit `securityMatcher`**, ahead of the order-3 catch-all, with
server-side sessions in shared storage — it does not reuse the tenant chain,
which is stateless, CSRF-disabled, and authenticates tenant bearer tokens.

## Evidence

Repository owner instruction, 2026-09-01: JTE pages inside the existing Spring
application, one deployment, existing authentication/session model, and BFF-
specific requirements removed rather than carried over. ADR-0014 for the design
this supersedes and the reasoning that remains valid. **D-145**/**R-026** for
the per-request active-admin check this inherits. **D-027**/**F-26** for the
platform-admin identity separation this builds on.

# Phase 1 — Legacy Login Endpoint Design (Punch-List Item #9)

## Decided

The repository owner confirmed the recommended option on all four
judgment calls raised by the research proposal
(`/tmp/.../legacy-login-endpoint-proposal.md`, not committed — scratch
work; this doc is the durable record):

1. **URL path**: `/api/legacy/auth/login_employee`, a new path scoped
   under `/api/legacy/**`. D-040 requires behavioural parity (status
   codes, outcomes), not URL parity — `AuthController`'s whole
   `/api/auth/*` family is itself new relative to legacy.
2. **JWT claims**: reuse `JwtService.issueAccessToken` as-is, unmodified.
   `identityId` and `membershipId` both carry the legacy employee id
   (legacy has no separate membership concept); `companyId` carries the
   resolved company id.
3. **Response body**: session material only — `accessToken`,
   `refreshToken`, `employeeId`, `companyId`. Legacy's `public_row(employee)`
   payload is not reproduced; the client fetches the profile separately
   after authenticating, the same pattern `AuthController.register`/`login`
   already use.
4. **Architecture**: parallel, not shared. A new controller, a new
   `@Order(3)` security chain scoped to `/api/legacy/**`, and a new
   tenant-context service — not a branch inside the existing
   `/api/auth/login` path or `TenantContextService`.

## Built so far

**`LegacyTenantContextService`** (`com.workin.legacy.auth`) — the actual
trust-boundary logic decision 2 depends on. `TenantScopeFilter`'s
resolver contract requires deriving the tenant id from the authenticated
principal, not trusting a claim outright; this service re-derives the
claimed employee from `LegacyEmployeeRepository`, cross-checks it
against the authenticated identity and the claimed company, and throws
`NoTenantScopeException` on any mismatch — mirroring
`TenantContextService.establishContext`'s re-derive-then-cross-check
order on the PostgreSQL side.

It must call `TenantFilterActivator.deactivateForPreTenantLookup()`
before querying: it runs before any `TenantScope` is established (that
is its job), and `TenantAwareJpaTransactionManager` binds every fresh
transaction to the `NO_TENANT` sentinel by default, which would
otherwise make the re-derivation query itself return zero rows
unconditionally — the same problem the pre-tenant phone lookup already
solves, applied here for the first time to a *post-authentication*
lookup rather than a pre-authentication one.

Tested against real MariaDB (`LegacyTenantContextServiceTest`,
`com.workin.legacy.auth`), through a real Spring Data
`LegacyEmployeeRepository` proxy built with `JpaRepositoryFactory`
rather than a full Spring context — the same reason
`TenantBindingEndToEndTest` builds its own `EntityManagerFactory`: the
application still boots against PostgreSQL until auth/authz is
reworked, so there is no MySQL context to inject a repository from yet.
This is also the first proof that `LegacyEmployeeRepository`'s derived
query methods work through Hibernate against the real legacy schema —
`LegacyEmployeeAdapterTest`'s javadoc names that as deferred work; this
is that step, scoped to the one method this service calls. 5 tests: a
legitimate claim is accepted and returns the employee's real company; a
claimed company that does not match the employee's real company is
rejected; a claimed employee id that belongs to someone else is
rejected; a claimed employee id that does not exist is rejected; and the
lookup is proven to run correctly inside a fresh, `NO_TENANT`-bound
transaction (i.e. the deactivation call is proven load-bearing, not
decorative).

## Blocked — not built

**Token issuance for the login controller.** `RefreshTokenService`
cannot be reused as-is for legacy sessions, contrary to the research
proposal's tentative assumption. Two independent findings, either one
sufficient on its own:

- **Schema**: `V15__create_refresh_tokens.sql` declares real foreign
  keys — `identity_id REFERENCES identities(id)`,
  `membership_id REFERENCES tenant_memberships(id)`,
  `company_id REFERENCES companies(id)` — all three PostgreSQL tables. A
  legacy employee id has no row in `identities` or
  `tenant_memberships`; inserting a refresh token for one would violate
  the FK constraints outright.
- **Behaviour**: `RefreshTokenService.rotate()` calls
  `identityRepository.findById(...)` and
  `membershipIndexService.findMembershipsForIdentity(...)` to re-check
  liveness on every rotation. Both are PostgreSQL-identity-model
  specific; called with a legacy employee id, `identityActive` resolves
  `false` and the very first rotation attempt revokes the family as a
  reuse-detection false positive.

So this is not a schema-only gap a new FK-free table would necessarily
fix — rotation's liveness re-check is coupled to the PostgreSQL identity
model at the service-method level. Reusing `RefreshTokenService` for
legacy sessions would require either a legacy-specific rotation path
with its own liveness check (a real design decision, not a mechanical
port) or a decision that legacy sessions don't get rotation-with-reuse-detection
at all, which conflicts with D-042's "outcomes are parity, token
lifetime is the recorded exception" stance — token lifetime already
changed once (dropping the 10-year JWT); dropping rotation too would be
a second, uncosted divergence.

**Not built as a result**: the login controller (its success path needs
a refresh token to return), the `@Order(3)` security chain, and the
end-to-end integration test. All three are mechanical once the token
question is answered — the blocking piece is exclusively token
issuance.

## Next

A decision on legacy session/refresh-token storage — likely its own
punch-list sub-item — before the controller and security chain can be
built. Options worth putting to the repository owner: (a) a parallel
`legacy_refresh_tokens` table with employee-id-only liveness checking
(no membership index, since legacy has none), (b) issuing only a
short-lived access token for legacy sessions with no refresh rotation
for now (a recorded, temporary exception, matching the shape of D-042's
token-lifetime exception), or (c) something else. Not decided here —
this doc stops at the finding, per the same standard the punch list
itself was corrected under: a real architectural gap gets surfaced, not
silently routed around.

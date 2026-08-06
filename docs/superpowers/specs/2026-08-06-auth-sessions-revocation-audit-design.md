# Sessions, Revocation, And Audit Attribution — Backend Slice Design (2026-08-06)

## Purpose And Authority

This is the slice-scoped design for the next `backend/` increment:
refresh-token sessions, rotation, revocation, and platform-admin audit
attribution. It makes no new architecture decisions — every design
element below is an application of already-accepted decisions:

- `docs/adr/ADR-0005-authentication-direction.md` (Accepted) and its
  full design in `docs/security/authentication-remediation-design.md`
  (Target Design items 1–3 and the backend half of item 6).
- `docs/adr/ADR-0010-authorization-model.md` Dimension 6 (minimal token
  claims) and §8 (platform/tenant domain separation).
- F-26's remaining closure criteria
  (`docs/migration/consolidated-task-matrix.md`): individual
  platform-admin session revocation and audit attribution.
- D-028 (`docs/bootstrap/decision-log.md`): implementation is authorized
  for `backend/` only.

Implementation of this slice was explicitly assigned by the repository
owner on 2026-08-06 ("ok proceed" on the recommendation naming exactly
this slice).

## In Scope

1. Opaque, rotating, server-side refresh tokens for **both** token
   domains (tenant identity and platform admin), hashed at rest.
2. Refresh endpoints with rotation and reuse-detection
   (family revocation on reuse, per the accepted design).
3. Logout endpoints that revoke server-side session state — including a
   regression guarantee that logout never deactivates the account
   (`hr-legacy#15` must not be carried forward).
4. Revoke-all-sessions service primitives for both domains (the building
   block the future password-change/reset endpoints, F-27, will call).
5. A platform-admin audit-event table and service, with session
   lifecycle events recorded now and the substrate defined for every
   future `platform.*` business endpoint.
6. Access-token `sid` claim becomes the real session (family) id instead
   of a throwaway random UUID, so access tokens correlate to sessions.

## Out Of Scope (Tracked, Not Dropped)

- Client-side work: secure storage and refresh-capable networking in the
  Flutter apps (`hr-platform#18`, F-02's client half) — separate repos,
  not unlocked by D-028.
- Password change/reset endpoints themselves (F-27) — only the
  revoke-all primitive they need is built here.
- User-facing "active sessions" management UI/endpoints — explicitly
  future scope per the accepted design ("scoped as such rather than
  assumed necessary for cutover").
- Device/client metadata on session rows — the accepted design marks
  this "to be confirmed by product"; adding columns later is a
  non-breaking migration.
- `platform.*` business functionality (approve/suspend/delete company) —
  still gated on this slice landing, per F-26.
- Tenant-domain audit events — audit scope here is the platform domain
  (F-26); tenant-side audit arrives with each business module.

## Design

### 1. Refresh-token data model

Two tables, one per token domain, mirroring the deliberate structural
separation of the two JWT issuers (a platform session row can never be
replayed against the tenant domain because the lookup tables are
disjoint, same reasoning as `SecurityConfig`'s two filter chains):

`refresh_tokens` (tenant-identity domain; global like `identities`, so
not RLS-protected):

- `id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY`
- `identity_id BIGINT NOT NULL REFERENCES identities(id)`
- `membership_id BIGINT NOT NULL` and `company_id BIGINT NOT NULL` — the
  tenant context selected at login; refresh re-validates it (below)
- `family_id UUID NOT NULL` — the session identity; constant across
  rotations within one login session
- `token_hash VARCHAR(64) NOT NULL UNIQUE` — SHA-256 hex of the opaque
  secret; the raw value is returned to the client once and never stored
- `status VARCHAR(16) NOT NULL` — `ACTIVE` / `ROTATED` / `REVOKED`
- `created_at TIMESTAMPTZ NOT NULL DEFAULT now()`
- `expires_at TIMESTAMPTZ NOT NULL`
- Index on `(family_id)` (family revocation) and `(identity_id)`
  (revoke-all).

`platform_admin_refresh_tokens` (platform domain): identical shape minus
`membership_id`/`company_id`, FK to `platform_admins(id)`.

The token value itself is 256 bits from `SecureRandom`, base64url,
opaque — not a JWT — exactly per the accepted design ("so it can be
looked up, listed, and revoked individually").

### 2. Rotation and reuse detection

On `POST` to a refresh endpoint with a presented token:

1. Hash it; look up the row.
2. Row missing, or `ACTIVE` but expired → `401`. Nothing to rotate.
3. Row `ROTATED` or `REVOKED` → **compromise signal**: revoke the entire
   family (`status = REVOKED` for every row with that `family_id`) and
   return `401`. In the platform domain this also writes a
   `SESSION_REUSE_REVOKED` audit event; the tenant domain has no audit
   table in this slice and relies on structured logging. This is the
   accepted design's detectable-reuse rule, applied strictly — a
   concurrent double-refresh from a flaky client loses its session and
   must re-authenticate; the accepted design chose detectability over
   leniency and this slice does not soften it.
4. Row `ACTIVE` and unexpired → rotate inside one transaction: flip the
   row to `ROTATED` with a guarded update (`WHERE status = 'ACTIVE'` —
   the concurrency loser falls into rule 3), insert a new `ACTIVE` row
   in the same family with a fresh expiry, and issue a new access token
   carrying `sid = family_id`.
5. Tenant domain only: before issuing, re-resolve the identity's active
   memberships and require the stored `membership_id` to still be among
   them (fail-closed, per ADR-0010 Dimension 2 — the token row is a
   context selector, not proof of membership). A vanished/inactive
   membership revokes the family and returns `401`.

Refresh-token TTLs are configurable, decided-by-default (the accepted
design leaves exact lifetimes as an open product trade-off; these are
starting values inside its stated candidate ranges, not new decisions):

- `app.jwt.refresh-token-ttl-seconds`, default 60 days (range 30–90).
- `app.platform-admin.jwt.refresh-token-ttl-seconds`, default 7 days —
  deliberately shorter than the tenant default because platform-admin
  sessions are the highest-privilege surface; also configurable.

### 3. Endpoints

Tenant domain (all under the existing `permitAll` `/api/auth/**`
matcher — refresh and logout authenticate by refresh-token possession,
since the access token may already be expired):

- `POST /api/auth/refresh` `{refreshToken}` → `200`
  `{accessToken, refreshToken, membershipId, companyId}` or `401`.
- `POST /api/auth/logout` `{refreshToken}` → `204` always (idempotent;
  an unknown token still gets `204` so the endpoint is not a validity
  oracle). Revokes the token's whole family. **Never touches
  `identities.active`** — the `hr-legacy#15` regression test asserts the
  identity can still log in afterwards.

Platform domain (new `permitAll` entries for
`/api/platform-admin/refresh` and `/api/platform-admin/logout` in the
platform chain):

- `POST /api/platform-admin/refresh` `{refreshToken}` → `200`
  `{accessToken, refreshToken, platformAdminId}` or `401`.
- `POST /api/platform-admin/logout` `{refreshToken}` → `204`, same
  idempotency; writes an attributed audit event when the token resolved
  to a real session.

`AuthResponse` and `PlatformAdminAuthResponse` gain a `refreshToken`
field; register and login now return an access/refresh pair.

### 4. Revoke-all primitives

- `RefreshTokenService.revokeAllForIdentity(identityId)`
- `PlatformAdminSessionService.revokeAllForPlatformAdmin(adminId)`

Service-level only in this slice (no HTTP surface yet); these are the
mechanism the accepted design requires for "password change/reset must
revoke all existing refresh tokens" (F-27's endpoints) and for future
admin-management ("revoke another admin's sessions"). Each writes an
audit event in the platform domain.

### 5. Platform-admin audit attribution

`platform_admin_audit_events` (platform-global, not RLS):

- `id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY`
- `platform_admin_id BIGINT NOT NULL REFERENCES platform_admins(id)` —
  every event is attributed to an individual; unattributable events
  (e.g. a failed login for a phone matching no admin) are deliberately
  not rows in this table
- `event_type VARCHAR(64) NOT NULL`
- `detail TEXT` (nullable, human-readable context)
- `occurred_at TIMESTAMPTZ NOT NULL DEFAULT now()`

`PlatformAdminAuditService.record(adminId, eventType, detail)` is the
single write path. Events recorded by this slice:

- `LOGIN` — successful platform-admin login
- `LOGIN_FAILED` — failed attempt against an existing admin's phone
  (wrong password or inactive account); attempts against unknown phones
  are not attributable and are left to ordinary structured logging
- `LOGOUT` — session ended by logout
- `SESSION_REUSE_REVOKED` — reuse detection revoked a family
- `ALL_SESSIONS_REVOKED` — the revoke-all primitive ran

Per-refresh events are deliberately not recorded (a 15-minute access
TTL would generate ~100 low-value rows per admin per day; the family id
in every access token already correlates activity to its session).

F-26 closure framing: this slice delivers the *session revocation* half
in full and the *audit attribution* substrate plus session-lifecycle
coverage. "Comprehensive" attribution of business actions becomes a
standing acceptance criterion — every future `platform.*` endpoint must
write to this table via `PlatformAdminAuditService` — enforceable later
by the same ArchUnit mechanism as F-23. The matrix row is updated by
this slice's PR to say exactly that, not to claim full closure.

### 6. Access-token `sid` coherence

`JwtService.issueAccessToken(...)` and
`PlatformAdminJwtService.issueAccessToken(...)` gain a session-id
parameter; `sid` is now the refresh-token `family_id`. Login/register
create the session first, then issue the access token. Claim shape is
unchanged (`sid` was already present), so this is not a token-contract
change for clients.

## Error Handling

All rejection paths return `401` with no distinguishing detail (no
valid/expired/revoked oracle). Logout returns `204` unconditionally.
Validation failures on request bodies follow the existing
`@Valid`/`ResponseStatusException` conventions.

## Testing (Testcontainers integration, same base as existing suite)

1. `AuthSessionFlowTest` (tenant domain): login returns a pair; refresh
   rotates (new pair works, old refresh token now `401`); reuse of a
   rotated token revokes the whole family (the newest token stops
   working too); logout revokes and is idempotent (`204` on unknown
   token); expired refresh token is rejected (row aged in DB);
   membership no longer active → refresh `401`; logout leaves
   `identities.active` true and login still works (`hr-legacy#15`
   regression); revoke-all kills every session for the identity.
2. `PlatformAdminSessionFlowTest`: login/refresh/logout/reuse parity
   for the platform domain; a tenant refresh token presented at
   `/api/platform-admin/refresh` is `401` and vice versa (domain
   separation proven, not assumed); revoke-all parity.
3. `PlatformAdminAuditTest`: `LOGIN`, `LOGIN_FAILED` (existing phone,
   wrong password), `LOGOUT`, `SESSION_REUSE_REVOKED`, and
   `ALL_SESSIONS_REVOKED` rows exist with the correct
   `platform_admin_id`; no row for a login attempt with an unknown
   phone.
4. Existing tests updated for the widened `AuthResponse` /
   `PlatformAdminAuthResponse` shapes; everything else must keep
   passing unmodified.

## Migrations

- `V8__create_refresh_tokens.sql`
- `V9__create_platform_admin_refresh_tokens.sql`
- `V10__create_platform_admin_audit_events.sql`

All in `db/migration/common` (none of these tables is tenant-owned; RLS
does not apply, same precedent as `identities` and `platform_admins`).

## Open Questions Deliberately Left Open

Unchanged from the accepted design, not resolved here: exact production
token lifetimes (defaults above are configurable starting values);
whether multiple simultaneous sessions per identity should be limited
(this slice allows multiple families per identity — each login is a new
session — matching the accepted design's session-management model and
leaving the single-vs-multi session product question open); session
management UI scope.

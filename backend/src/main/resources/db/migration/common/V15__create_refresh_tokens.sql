-- Opaque rotating refresh tokens for the tenant-identity token domain
-- (docs/adr/ADR-0005-authentication-direction.md, Target Design items
-- 1-3; docs/superpowers/specs/2026-08-06-auth-sessions-revocation-audit-design.md).
-- Global like identities (no RLS): a session belongs to an identity,
-- not to a tenant. family_id is the session identity, constant across
-- rotations; token_hash is SHA-256 hex -- the raw value is returned to
-- the client once and never stored. status is a real CHECK constraint,
-- not an app-level convention (the hr-legacy#21 lesson).
CREATE TABLE refresh_tokens (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    identity_id BIGINT NOT NULL REFERENCES identities(id),
    membership_id BIGINT NOT NULL REFERENCES tenant_memberships(id),
    company_id BIGINT NOT NULL REFERENCES companies(id),
    family_id UUID NOT NULL,
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    status VARCHAR(16) NOT NULL CHECK (status IN ('ACTIVE', 'ROTATED', 'REVOKED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX refresh_tokens_family_id_idx ON refresh_tokens (family_id);
CREATE INDEX refresh_tokens_identity_id_idx ON refresh_tokens (identity_id);

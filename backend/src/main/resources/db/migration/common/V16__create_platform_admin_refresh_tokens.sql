-- Platform-domain twin of refresh_tokens (V8), deliberately a separate
-- table: the two session stores are disjoint by construction, mirroring
-- the two JWT issuers and SecurityConfig's two filter chains
-- (docs/architecture/authorization-model.md §8 -- platform and tenant
-- namespaces never overlap accidentally). Closes the session-revocation
-- half of F-26's remaining gap.
CREATE TABLE platform_admin_refresh_tokens (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    platform_admin_id BIGINT NOT NULL REFERENCES platform_admins(id),
    family_id UUID NOT NULL,
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    status VARCHAR(16) NOT NULL CHECK (status IN ('ACTIVE', 'ROTATED', 'REVOKED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX platform_admin_refresh_tokens_family_id_idx
    ON platform_admin_refresh_tokens (family_id);
CREATE INDEX platform_admin_refresh_tokens_platform_admin_id_idx
    ON platform_admin_refresh_tokens (platform_admin_id);

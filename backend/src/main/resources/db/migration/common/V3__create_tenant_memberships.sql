-- The anchor of docs/architecture/authorization-model.md's authorization
-- model: one row per (identity, tenant) pair. RLS-protected (see
-- rls/V6__enable_row_level_security.sql) -- this is the exact table
-- shape the H2 spike proved the RLS pattern against
-- (docs/migration/technical-spike-plan.md's "Full Spike Findings"),
-- applied here for real.
CREATE TABLE tenant_memberships (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    identity_id BIGINT NOT NULL REFERENCES identities (id),
    company_id BIGINT NOT NULL REFERENCES companies (id),
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT tenant_memberships_status_check CHECK (status IN ('ACTIVE', 'DISABLED')),
    CONSTRAINT tenant_memberships_identity_company_unique UNIQUE (identity_id, company_id)
);

CREATE INDEX tenant_memberships_company_id_idx ON tenant_memberships (company_id);
CREATE INDEX tenant_memberships_identity_id_idx ON tenant_memberships (identity_id);

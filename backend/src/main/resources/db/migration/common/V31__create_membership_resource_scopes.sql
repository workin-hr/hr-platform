-- ADR-0010 enforcement boundary 3 (docs/architecture/authorization-model.md
-- section 3): a membership's resource scopes constrain WHICH employees
-- its permissions reach. scope_id references a branch or department by
-- scope_type (no FK -- it points at two possible tables; the service
-- validates the reference in-tenant). BRANCH/DEPARTMENT only in this
-- slice; DIRECT_REPORT and EMPLOYEE (F-25) deferred, excluded by the
-- CHECK. company_id denormalized for RLS.
CREATE TABLE membership_resource_scopes (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    membership_id BIGINT NOT NULL REFERENCES tenant_memberships (id),
    company_id BIGINT NOT NULL REFERENCES companies (id),
    scope_type VARCHAR(16) NOT NULL CHECK (scope_type IN ('BRANCH', 'DEPARTMENT')),
    scope_id BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT membership_resource_scopes_unique UNIQUE (membership_id, scope_type, scope_id)
);
CREATE INDEX membership_resource_scopes_membership_id_idx ON membership_resource_scopes (membership_id);
CREATE INDEX membership_resource_scopes_company_id_idx ON membership_resource_scopes (company_id);

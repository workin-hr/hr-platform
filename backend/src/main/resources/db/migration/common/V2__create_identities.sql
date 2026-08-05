-- An identity is a global principal, independent of any tenant --
-- docs/adr/ADR-0010-authorization-model.md Dimension 1: "An identity may
-- have memberships in multiple tenants. Roles, permissions, and resource
-- scopes belong to the membership, not globally to the identity." This
-- table intentionally carries no company_id, no role, no permission --
-- those all live on tenant_memberships (V3) and its child tables.
CREATE TABLE identities (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    phone VARCHAR(32) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

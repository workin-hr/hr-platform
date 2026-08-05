-- Companies are the tenant root -- ADR-0002 Part B's tenant-isolation
-- mechanism (RLS, keyed on app.current_company_id) scopes every
-- tenant-owned row against companies.id. Not RLS-protected itself: a
-- company's own row is looked up before a tenant context exists yet
-- (registration, login), and platform-admin visibility across all
-- companies is a real, intended capability (docs/architecture/authorization-model.md
-- "Platform-domain starter catalog").
CREATE TABLE companies (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    phone VARCHAR(32) NOT NULL UNIQUE,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

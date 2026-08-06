-- Platform-domain principal, deliberately separate from identities/
-- tenant_memberships (docs/architecture/authorization-model.md's two
-- authorization domains -- "Platform domain" vs "Tenant domain").
-- Replaces hr-legacy's single shared admin password
-- (dashboard/includes/auth.php's doAdminLogin(), hr-legacy#11) with
-- real individual identities. Not RLS-protected -- like companies and
-- permissions, this is global/platform-level data, not tenant-owned.
-- No self-registration path exists for this table: the first row is
-- created only by PlatformAdminBootstrap at startup from operator-
-- provided env vars (docs/migration/consolidated-task-matrix.md F-26).
CREATE TABLE platform_admins (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    phone VARCHAR(32) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

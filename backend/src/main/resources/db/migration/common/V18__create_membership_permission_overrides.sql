-- Per-membership explicit ALLOW/DENY on a specific catalog permission
-- (docs/adr/ADR-0010-authorization-model.md Dimension 3;
-- docs/architecture/authorization-model.md §3). This is the table the
-- legacy hr_permissions matrix migrates into (§7's migration rule:
-- can_X = 1 becomes ALLOW rows for that row's canonical keys) and the
-- home of legitimate individual exceptions going forward. Tenant-owned:
-- RLS enabled in rls/V19.
CREATE TABLE membership_permission_overrides (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    membership_id BIGINT NOT NULL REFERENCES tenant_memberships (id),
    company_id BIGINT NOT NULL REFERENCES companies (id),
    permission_id BIGINT NOT NULL REFERENCES permissions (id),
    effect VARCHAR(8) NOT NULL CHECK (effect IN ('ALLOW', 'DENY')),
    CONSTRAINT membership_permission_overrides_membership_permission_unique
        UNIQUE (membership_id, permission_id)
);

CREATE INDEX membership_permission_overrides_company_id_idx
    ON membership_permission_overrides (company_id);
CREATE INDEX membership_permission_overrides_membership_id_idx
    ON membership_permission_overrides (membership_id);

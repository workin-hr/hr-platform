-- members.read / members.manage are new-platform keys: legacy's
-- hr_permissions has no member-administration flag (role administration
-- was implicit company-admin dashboard behavior). COMPANY_ADMIN gets
-- both, matching V20's full-tenant-catalog rule for that role.
INSERT INTO permissions (permission_key, description) VALUES
    ('members.read', 'View tenant members, their roles, and permission overrides (new platform; no legacy flag)'),
    ('members.manage', 'Assign/remove roles, grant/revoke permission overrides, activate/disable memberships (new platform; no legacy flag)');

INSERT INTO role_permissions (role, permission_id)
SELECT 'COMPANY_ADMIN', p.id FROM permissions p
WHERE p.permission_key IN ('members.read', 'members.manage');

-- docs/architecture/authorization-model.md section 9: tenant-domain
-- audit with individual actor attribution -- the tenant counterpart of
-- V17's platform_admin_audit_events. detail carries before/after
-- context where applicable. RLS in rls/V24.
CREATE TABLE tenant_audit_events (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    company_id BIGINT NOT NULL REFERENCES companies (id),
    actor_membership_id BIGINT NOT NULL REFERENCES tenant_memberships (id),
    target_membership_id BIGINT REFERENCES tenant_memberships (id),
    action VARCHAR(64) NOT NULL,
    permission_key VARCHAR(128),
    detail TEXT,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX tenant_audit_events_company_id_idx ON tenant_audit_events (company_id);
CREATE INDEX tenant_audit_events_actor_idx ON tenant_audit_events (actor_membership_id);

-- Proven pattern from the H2 spike (docs/migration/technical-spike-plan.md
-- "Full Spike Findings"), applied for real: FORCE ROW LEVEL SECURITY,
-- fail-closed on an unset session variable. NULLIF(...) means an unset
-- app.current_company_id resolves to NULL, which never matches any
-- company_id -- zero rows visible by default, not fail-open.
ALTER TABLE tenant_memberships ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenant_memberships FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_memberships_isolation ON tenant_memberships
    USING (company_id = NULLIF(current_setting('app.current_company_id', true), '')::BIGINT);

ALTER TABLE membership_roles ENABLE ROW LEVEL SECURITY;
ALTER TABLE membership_roles FORCE ROW LEVEL SECURITY;

CREATE POLICY membership_roles_isolation ON membership_roles
    USING (company_id = NULLIF(current_setting('app.current_company_id', true), '')::BIGINT);

-- Same fail-closed pattern as rls/V5 through rls/V30: FORCE ROW LEVEL
-- SECURITY, NULLIF(...) so an unset app.current_company_id resolves to
-- NULL -- zero rows visible by default, not fail-open.
ALTER TABLE membership_resource_scopes ENABLE ROW LEVEL SECURITY;
ALTER TABLE membership_resource_scopes FORCE ROW LEVEL SECURITY;

CREATE POLICY membership_resource_scopes_isolation ON membership_resource_scopes
    USING (company_id = NULLIF(current_setting('app.current_company_id', true), '')::BIGINT);

-- Same fail-closed pattern as rls/V5 through rls/V26: FORCE ROW LEVEL
-- SECURITY, NULLIF(...) so an unset app.current_company_id resolves to
-- NULL -- zero rows visible by default, not fail-open.
ALTER TABLE company_settings ENABLE ROW LEVEL SECURITY;
ALTER TABLE company_settings FORCE ROW LEVEL SECURITY;

CREATE POLICY company_settings_isolation ON company_settings
    USING (company_id = NULLIF(current_setting('app.current_company_id', true), '')::BIGINT);

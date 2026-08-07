-- Same fail-closed pattern as rls/V5, rls/V14, rls/V22: FORCE ROW
-- LEVEL SECURITY, NULLIF(...) so an unset app.current_company_id
-- resolves to NULL -- zero rows visible by default, not fail-open.
ALTER TABLE tenant_audit_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenant_audit_events FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_audit_events_isolation ON tenant_audit_events
    USING (company_id = NULLIF(current_setting('app.current_company_id', true), '')::BIGINT);

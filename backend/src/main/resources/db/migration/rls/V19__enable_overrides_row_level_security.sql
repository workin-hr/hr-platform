-- Same fail-closed pattern as V5: FORCE ROW LEVEL SECURITY, and an
-- unset app.current_company_id resolves to NULL, matching zero rows.
ALTER TABLE membership_permission_overrides ENABLE ROW LEVEL SECURITY;
ALTER TABLE membership_permission_overrides FORCE ROW LEVEL SECURITY;

CREATE POLICY membership_permission_overrides_isolation ON membership_permission_overrides
    USING (company_id = NULLIF(current_setting('app.current_company_id', true), '')::BIGINT);

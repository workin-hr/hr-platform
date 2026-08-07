-- Same fail-closed pattern as rls/V5, rls/V14, rls/V22, rls/V24:
-- FORCE ROW LEVEL SECURITY, NULLIF(...) so an unset
-- app.current_company_id resolves to NULL -- zero rows visible by
-- default, not fail-open. Covers the three tables added in common/V25.
ALTER TABLE request_types ENABLE ROW LEVEL SECURITY;
ALTER TABLE request_types FORCE ROW LEVEL SECURITY;

CREATE POLICY request_types_isolation ON request_types
    USING (company_id = NULLIF(current_setting('app.current_company_id', true), '')::BIGINT);

ALTER TABLE requests ENABLE ROW LEVEL SECURITY;
ALTER TABLE requests FORCE ROW LEVEL SECURITY;

CREATE POLICY requests_isolation ON requests
    USING (company_id = NULLIF(current_setting('app.current_company_id', true), '')::BIGINT);

ALTER TABLE leave_balances ENABLE ROW LEVEL SECURITY;
ALTER TABLE leave_balances FORCE ROW LEVEL SECURITY;

CREATE POLICY leave_balances_isolation ON leave_balances
    USING (company_id = NULLIF(current_setting('app.current_company_id', true), '')::BIGINT);

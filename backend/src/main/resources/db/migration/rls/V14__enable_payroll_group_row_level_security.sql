-- Same fail-closed pattern as rls/V5__enable_row_level_security.sql:
-- FORCE ROW LEVEL SECURITY, NULLIF(...) so an unset app.current_company_id
-- resolves to NULL -- zero rows visible by default, not fail-open.
-- Covers all 6 tables added in common/V8-V13, each already carrying a
-- real company_id column (direct on employees/payroll_batches,
-- denormalized on salary_contracts/payslips/advances/penalties).
ALTER TABLE employees ENABLE ROW LEVEL SECURITY;
ALTER TABLE employees FORCE ROW LEVEL SECURITY;

CREATE POLICY employees_isolation ON employees
    USING (company_id = NULLIF(current_setting('app.current_company_id', true), '')::BIGINT);

ALTER TABLE salary_contracts ENABLE ROW LEVEL SECURITY;
ALTER TABLE salary_contracts FORCE ROW LEVEL SECURITY;

CREATE POLICY salary_contracts_isolation ON salary_contracts
    USING (company_id = NULLIF(current_setting('app.current_company_id', true), '')::BIGINT);

ALTER TABLE payroll_batches ENABLE ROW LEVEL SECURITY;
ALTER TABLE payroll_batches FORCE ROW LEVEL SECURITY;

CREATE POLICY payroll_batches_isolation ON payroll_batches
    USING (company_id = NULLIF(current_setting('app.current_company_id', true), '')::BIGINT);

ALTER TABLE payslips ENABLE ROW LEVEL SECURITY;
ALTER TABLE payslips FORCE ROW LEVEL SECURITY;

CREATE POLICY payslips_isolation ON payslips
    USING (company_id = NULLIF(current_setting('app.current_company_id', true), '')::BIGINT);

ALTER TABLE advances ENABLE ROW LEVEL SECURITY;
ALTER TABLE advances FORCE ROW LEVEL SECURITY;

CREATE POLICY advances_isolation ON advances
    USING (company_id = NULLIF(current_setting('app.current_company_id', true), '')::BIGINT);

ALTER TABLE penalties ENABLE ROW LEVEL SECURITY;
ALTER TABLE penalties FORCE ROW LEVEL SECURITY;

CREATE POLICY penalties_isolation ON penalties
    USING (company_id = NULLIF(current_setting('app.current_company_id', true), '')::BIGINT);

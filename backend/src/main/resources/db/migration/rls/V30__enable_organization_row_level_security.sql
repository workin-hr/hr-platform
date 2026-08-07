-- Same fail-closed pattern as rls/V5 through rls/V28: FORCE ROW LEVEL
-- SECURITY, NULLIF(...) so an unset app.current_company_id resolves to
-- NULL -- zero rows visible by default, not fail-open. Covers the five
-- tables added in common/V29 (employees already has RLS from V5).
ALTER TABLE branches ENABLE ROW LEVEL SECURITY;
ALTER TABLE branches FORCE ROW LEVEL SECURITY;

CREATE POLICY branches_isolation ON branches
    USING (company_id = NULLIF(current_setting('app.current_company_id', true), '')::BIGINT);

ALTER TABLE departments ENABLE ROW LEVEL SECURITY;
ALTER TABLE departments FORCE ROW LEVEL SECURITY;

CREATE POLICY departments_isolation ON departments
    USING (company_id = NULLIF(current_setting('app.current_company_id', true), '')::BIGINT);

ALTER TABLE department_branches ENABLE ROW LEVEL SECURITY;
ALTER TABLE department_branches FORCE ROW LEVEL SECURITY;

CREATE POLICY department_branches_isolation ON department_branches
    USING (company_id = NULLIF(current_setting('app.current_company_id', true), '')::BIGINT);

ALTER TABLE job_titles ENABLE ROW LEVEL SECURITY;
ALTER TABLE job_titles FORCE ROW LEVEL SECURITY;

CREATE POLICY job_titles_isolation ON job_titles
    USING (company_id = NULLIF(current_setting('app.current_company_id', true), '')::BIGINT);

ALTER TABLE shifts ENABLE ROW LEVEL SECURITY;
ALTER TABLE shifts FORCE ROW LEVEL SECURITY;

CREATE POLICY shifts_isolation ON shifts
    USING (company_id = NULLIF(current_setting('app.current_company_id', true), '')::BIGINT);

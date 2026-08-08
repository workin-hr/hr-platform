-- Same fail-closed pattern as rls/V5 through rls/V32: FORCE ROW LEVEL
-- SECURITY, NULLIF(...) so an unset app.current_company_id resolves to
-- NULL -- zero rows visible by default, not fail-open.
ALTER TABLE employee_shift_assignments ENABLE ROW LEVEL SECURITY;
ALTER TABLE employee_shift_assignments FORCE ROW LEVEL SECURITY;

CREATE POLICY employee_shift_assignments_isolation ON employee_shift_assignments
	USING (company_id = NULLIF(current_setting('app.current_company_id', true), '')::BIGINT);

ALTER TABLE employee_schedules ENABLE ROW LEVEL SECURITY;
ALTER TABLE employee_schedules FORCE ROW LEVEL SECURITY;

CREATE POLICY employee_schedules_isolation ON employee_schedules
	USING (company_id = NULLIF(current_setting('app.current_company_id', true), '')::BIGINT);

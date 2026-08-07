-- Same fail-closed pattern as rls/V5 and rls/V14: FORCE ROW LEVEL
-- SECURITY, NULLIF(...) so an unset app.current_company_id resolves to
-- NULL -- zero rows visible by default, not fail-open. Covers both
-- tables added in common/V21.
ALTER TABLE exception_types ENABLE ROW LEVEL SECURITY;
ALTER TABLE exception_types FORCE ROW LEVEL SECURITY;

CREATE POLICY exception_types_isolation ON exception_types
    USING (company_id = NULLIF(current_setting('app.current_company_id', true), '')::BIGINT);

ALTER TABLE attendance ENABLE ROW LEVEL SECURITY;
ALTER TABLE attendance FORCE ROW LEVEL SECURITY;

CREATE POLICY attendance_isolation ON attendance
    USING (company_id = NULLIF(current_setting('app.current_company_id', true), '')::BIGINT);

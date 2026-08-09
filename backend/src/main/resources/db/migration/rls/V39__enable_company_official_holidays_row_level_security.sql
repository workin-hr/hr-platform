-- Same fail-closed pattern as rls/V5 through rls/V34: FORCE ROW LEVEL
-- SECURITY, NULLIF(...) so an unset app.current_company_id resolves to
-- NULL -- zero rows visible by default, not fail-open.
--
-- Worth noting because legacy has the opposite property: its
-- company_official_holidays reads are scoped only by an explicit
-- company_id predicate in each query, with no database backstop.
ALTER TABLE company_official_holidays ENABLE ROW LEVEL SECURITY;
ALTER TABLE company_official_holidays FORCE ROW LEVEL SECURITY;

CREATE POLICY company_official_holidays_isolation ON company_official_holidays
	USING (company_id = NULLIF(current_setting('app.current_company_id', true), '')::BIGINT);

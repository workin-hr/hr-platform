-- H2 spike, "isolation-rls" profile only (see application-isolation-rls.properties'
-- spring.flyway.locations, which includes this db/migration/rls folder in
-- addition to db/migration/common -- the "isolation-guard" profile never
-- runs this file, so its branches table has no RLS at all and isolation
-- is enforced purely at the repository layer instead).
--
-- FORCE ROW LEVEL SECURITY (not just ENABLE) is deliberate: by default
-- Postgres RLS does not apply to the table owner, and Flyway's connecting
-- role becomes the table owner here. FORCE makes the policy apply even to
-- the owning role, which is the realistic production shape (the
-- application's own DB role would very likely be the table owner too).
ALTER TABLE branches ENABLE ROW LEVEL SECURITY;
ALTER TABLE branches FORCE ROW LEVEL SECURITY;

-- Fail-closed by design: current_setting(..., true) returns NULL if the
-- session variable was never set (e.g. a request path that forgot to set
-- tenant context), and `company_id = NULL` is never true in SQL -- so an
-- unset tenant context sees zero rows, not every company's rows.
CREATE POLICY tenant_isolation_branches ON branches
    USING (company_id = NULLIF(current_setting('app.current_company_id', true), '')::BIGINT);

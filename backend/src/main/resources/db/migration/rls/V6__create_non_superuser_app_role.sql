-- The H2 spike's central, dangerous finding: Postgres RLS is always
-- bypassed for a superuser connection, regardless of FORCE ROW LEVEL
-- SECURITY. This role is the non-optional condition ADR-0002's Decision
-- (condition 1) attaches to accepting RLS -- the application's runtime
-- DataSource must never connect as a superuser. Enforced in code too:
-- see RlsDataSourceConfig and SuperuserStartupCheck.
CREATE ROLE "${app_runtime_db_username}" LOGIN PASSWORD '${app_runtime_db_password}';
GRANT USAGE ON SCHEMA public TO "${app_runtime_db_username}";
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO "${app_runtime_db_username}";
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO "${app_runtime_db_username}";
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO "${app_runtime_db_username}";
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT USAGE, SELECT ON SEQUENCES TO "${app_runtime_db_username}";

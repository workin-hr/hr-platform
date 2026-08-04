-- H2 spike, "isolation-rls" profile only. Real finding, not a guess:
-- Postgres RLS is always bypassed for superusers, regardless of FORCE
-- ROW LEVEL SECURITY (FORCE only overrides the *table-owner* exemption,
-- never the superuser exemption -- see Postgres's own CREATE POLICY
-- documentation). Testcontainers' PostgreSQLContainer default user
-- becomes the initdb superuser for a fresh container, which would make
-- RLS silently do nothing if the application connected as that user.
-- This migration creates a real, unprivileged application role that
-- Flyway (running as the superuser) grants exactly the privileges the
-- app needs -- mirroring the realistic production shape where
-- migrations run as an owner/admin role and the application runtime
-- connects as a more restricted one.
CREATE ROLE app_runtime LOGIN PASSWORD 'app_runtime_password';
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO app_runtime;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO app_runtime;

-- Closes a gap found while implementing the payroll group
-- (docs/migration/payroll-module-execution-plan.md): V8's employees
-- table has its own standalone phone/password_hash and carries no link
-- to the identities/tenant_memberships auth system that
-- AuthController/JwtService/TenantContextService already use. Without
-- this, an authenticated EMPLOYEE-role membership has no way to
-- resolve which employees row is "their own" -- required for every
-- self-scoping rule in the payroll group (advances, payslips).
--
-- This is a structural completion of already-Accepted architecture
-- (ADR-0010 established identities/tenant_memberships as the single
-- auth anchor), not a new product decision -- nullable and unique so
-- an employee row can exist before the corresponding person has ever
-- logged into the new system (matches the bulk-migration reality: an
-- imported employee row and its identity may not arrive in the same
-- step), and so one identity can never silently double as two
-- employees in the same company.
ALTER TABLE employees ADD COLUMN identity_id BIGINT REFERENCES identities (id);
ALTER TABLE employees ADD CONSTRAINT employees_identity_id_unique UNIQUE (identity_id);

CREATE INDEX employees_identity_id_idx ON employees (identity_id);

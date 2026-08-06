-- Deliberately minimal: just enough for real FK integrity and RLS
-- company-scoping to unblock the payroll group (salary_contracts,
-- payroll_batches, payslips, advances, penalties -- all FK to
-- employees.id in the legacy schema, hr-legacy/mysql_workin.schema.sql).
-- branch_id/department_id/job_title_id/employee_code are intentionally
-- omitted -- those reference tables (branches/departments/job_titles)
-- don't exist yet and are the lowest-priority group in
-- docs/migration/migration-strategy-and-sequencing.md. The full
-- employee-profile module (self-service endpoints, join-request
-- workflow, all legacy fields) is separate, later, tracked follow-up --
-- this table only exists to make the payroll group's foreign keys real.
--
-- Tenant-owned data, unlike companies -- RLS-protected in
-- rls/V14__enable_payroll_group_row_level_security.sql, same pattern as
-- tenant_memberships.
CREATE TABLE employees (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    company_id BIGINT NOT NULL REFERENCES companies (id),
    first_name VARCHAR(255) NOT NULL,
    last_name VARCHAR(255) NOT NULL DEFAULT '',
    phone VARCHAR(32) UNIQUE,
    password_hash VARCHAR(255),
    role VARCHAR(32) NOT NULL DEFAULT 'EMPLOYEE',
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT employees_role_check CHECK (role IN ('COMPANY_ADMIN', 'HR', 'MANAGER', 'EMPLOYEE'))
);

CREATE INDEX employees_company_id_idx ON employees (company_id);

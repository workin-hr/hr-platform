-- Translated from hr-legacy/mysql_workin.schema.sql's penalties table.
-- penalty_days is stored in days, not currency -- converted to money at
-- payroll time by future business logic, same as legacy.
-- applied_to_payroll locks a row from further update/delete once its
-- batch is finalized (application-level rule, not a schema constraint
-- here, since business logic is out of scope for this slice).
--
-- company_id denormalized for RLS, same reasoning as
-- V9__create_salary_contracts.sql.
CREATE TABLE penalties (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    employee_id BIGINT NOT NULL REFERENCES employees (id),
    company_id BIGINT NOT NULL REFERENCES companies (id),
    penalty_type VARCHAR(100) NOT NULL,
    penalty_days NUMERIC(5, 1) NOT NULL DEFAULT 0.0,
    reason TEXT,
    penalty_date DATE NOT NULL,
    applied_to_payroll BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX penalties_employee_id_idx ON penalties (employee_id);
CREATE INDEX penalties_company_id_idx ON penalties (company_id);

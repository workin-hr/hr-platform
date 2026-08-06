-- Translated from hr-legacy/mysql_workin.schema.sql's salary_contracts
-- table. Append-only, versioned history -- no uniqueness on
-- (employee_id, effective_from); "the effective contract for a period"
-- is application-computed as the most recent row with
-- effective_from <= period end (same as legacy).
--
-- company_id is denormalized here even though legacy only reaches it
-- via employee_id -> employees.company_id -- same reasoning already
-- used for membership_roles (V4): keeps RLS a simple column check
-- instead of a subquery. docs/migration/tenant-boundary-verification.md
-- already confirmed 0 real-data mismatches on this exact path.
--
-- Deliberately dropped vs. legacy: the generated `total` column.
-- Legacy's own formula excludes daily_wage entirely (a documented
-- defect) -- real payslip totals belong in future payroll-calculation
-- business logic, not a schema-level column carrying that formula
-- forward.
CREATE TABLE salary_contracts (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    employee_id BIGINT NOT NULL REFERENCES employees (id),
    company_id BIGINT NOT NULL REFERENCES companies (id),
    salary_mode VARCHAR(16) NOT NULL DEFAULT 'MONTHLY',
    basic_salary NUMERIC(10, 2) NOT NULL DEFAULT 0.00,
    daily_wage NUMERIC(10, 2),
    housing_allowance NUMERIC(10, 2) NOT NULL DEFAULT 0.00,
    transport_allowance NUMERIC(10, 2) NOT NULL DEFAULT 0.00,
    food_allowance NUMERIC(10, 2) NOT NULL DEFAULT 0.00,
    risk_allowance NUMERIC(10, 2) NOT NULL DEFAULT 0.00,
    incentives NUMERIC(10, 2) NOT NULL DEFAULT 0.00,
    insurance_deduction NUMERIC(10, 2) NOT NULL DEFAULT 0.00,
    tax_deduction NUMERIC(10, 2) NOT NULL DEFAULT 0.00,
    advances_deduction NUMERIC(10, 2) NOT NULL DEFAULT 0.00,
    fund_deduction NUMERIC(10, 2) NOT NULL DEFAULT 0.00,
    penalty_deduction NUMERIC(10, 2) NOT NULL DEFAULT 0.00,
    effective_from DATE NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT salary_contracts_mode_check CHECK (salary_mode IN ('MONTHLY', 'DAILY'))
);

CREATE INDEX salary_contracts_employee_id_idx ON salary_contracts (employee_id);
CREATE INDEX salary_contracts_company_id_idx ON salary_contracts (company_id);

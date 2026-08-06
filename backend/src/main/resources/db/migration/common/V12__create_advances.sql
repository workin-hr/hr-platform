-- Translated from hr-legacy/mysql_workin.schema.sql's advances table.
--
-- Deliberately dropped vs. legacy: deduction_type and
-- deduction_installments_json. Legacy carries both deduction_mode
-- ('single_payroll_month'/'installments') and a second, seemingly
-- redundant deduction_type ('single_month'/'multiple_months') enum.
-- Only deduction_mode is modeled here -- the redundancy needs a product
-- decision before the business-logic slice, not a schema-level guess.
--
-- company_id denormalized for RLS, same reasoning as
-- V9__create_salary_contracts.sql.
CREATE TABLE advances (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    employee_id BIGINT NOT NULL REFERENCES employees (id),
    company_id BIGINT NOT NULL REFERENCES companies (id),
    amount NUMERIC(10, 2) NOT NULL,
    remaining NUMERIC(10, 2) NOT NULL,
    reason TEXT,
    deduction_mode VARCHAR(32) NOT NULL DEFAULT 'SINGLE_PAYROLL_MONTH',
    deduction_month_count INT NOT NULL DEFAULT 1,
    deduction_amount_per_month NUMERIC(10, 2),
    deduction_payroll_year SMALLINT,
    deduction_payroll_month SMALLINT,
    rejection_reason TEXT,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    request_date DATE NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT advances_status_check CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    CONSTRAINT advances_deduction_mode_check CHECK (deduction_mode IN ('SINGLE_PAYROLL_MONTH', 'INSTALLMENTS'))
);

CREATE INDEX advances_employee_id_idx ON advances (employee_id);
CREATE INDEX advances_company_id_idx ON advances (company_id);

-- Translated from hr-legacy/mysql_workin.schema.sql's payslips table.
-- The (batch_id, employee_id) UNIQUE constraint is real in legacy too --
-- carried forward as-is.
--
-- employee_id intentionally has no ON DELETE clause -- Postgres's
-- default (effectively RESTRICT) matches legacy's own implicit
-- behavior. Deliberately not providing any cascade-delete path from
-- employees to payroll history: legacy's employees/delete.php?cascade=1
-- explicitly works around its own RESTRICT to hard-delete payslips (an
-- already-documented defect, docs/legacy/business-rule-extraction.md).
-- Employee removal in this schema is soft-delete (employees.active)
-- only, same as every other entity in this backend.
--
-- company_id denormalized for RLS, same reasoning as
-- V9__create_salary_contracts.sql. created_at is a deliberate addition
-- -- legacy's payslips is the only table in this group with neither
-- created_at nor updated_at; every other table here has one, so this
-- keeps that consistent rather than copying the gap forward.
CREATE TABLE payslips (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    batch_id BIGINT NOT NULL REFERENCES payroll_batches (id) ON DELETE CASCADE,
    employee_id BIGINT NOT NULL REFERENCES employees (id),
    company_id BIGINT NOT NULL REFERENCES companies (id),
    days_present SMALLINT NOT NULL DEFAULT 0,
    days_absent SMALLINT NOT NULL DEFAULT 0,
    days_leave SMALLINT NOT NULL DEFAULT 0,
    overtime_hours NUMERIC(5, 1) NOT NULL DEFAULT 0.0,
    basic_salary NUMERIC(10, 2) NOT NULL DEFAULT 0.00,
    allowances NUMERIC(10, 2) NOT NULL DEFAULT 0.00,
    overtime_pay NUMERIC(10, 2) NOT NULL DEFAULT 0.00,
    penalties_total NUMERIC(10, 2) NOT NULL DEFAULT 0.00,
    advance_deduction NUMERIC(10, 2) NOT NULL DEFAULT 0.00,
    other_deductions NUMERIC(10, 2) NOT NULL DEFAULT 0.00,
    net_salary NUMERIC(10, 2) NOT NULL DEFAULT 0.00,
    food_allowance NUMERIC(10, 2) NOT NULL DEFAULT 0.00,
    risk_allowance NUMERIC(10, 2) NOT NULL DEFAULT 0.00,
    transport_allowance NUMERIC(10, 2) NOT NULL DEFAULT 0.00,
    incentives NUMERIC(10, 2) NOT NULL DEFAULT 0.00,
    insurance_deduction NUMERIC(10, 2) NOT NULL DEFAULT 0.00,
    tax_deduction NUMERIC(10, 2) NOT NULL DEFAULT 0.00,
    advances_deduction NUMERIC(10, 2) NOT NULL DEFAULT 0.00,
    fund_deduction NUMERIC(10, 2) NOT NULL DEFAULT 0.00,
    gross_salary NUMERIC(10, 2) NOT NULL DEFAULT 0.00,
    total_entitlements NUMERIC(10, 2) NOT NULL DEFAULT 0.00,
    total_deductions NUMERIC(10, 2) NOT NULL DEFAULT 0.00,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT payslips_batch_employee_unique UNIQUE (batch_id, employee_id)
);

CREATE INDEX payslips_batch_id_idx ON payslips (batch_id);
CREATE INDEX payslips_employee_id_idx ON payslips (employee_id);
CREATE INDEX payslips_company_id_idx ON payslips (company_id);

-- Translated from hr-legacy's exception_types and attendance tables
-- (docs/migration/database-schema-inventory.md; attendance is the
-- system's largest table, ~5.5x employees). company_id denormalized
-- for RLS, same reasoning as V9__create_salary_contracts.sql.
--
-- Punches XOR exception day (docs/legacy/business-rule-extraction.md):
-- a row is either real punches (check_in/check_out timestamps) or a
-- category-only exception day (exception_type_id set, check_in at
-- that day's midnight, check_out always null) -- never both. The
-- DB-enforceable half is "an exception row never has a checkout";
-- midnight-forcing and required-fields-per-shape stay app-level in
-- AttendanceService.
CREATE TABLE exception_types (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    company_id BIGINT NOT NULL REFERENCES companies (id),
    name VARCHAR(150) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX exception_types_company_id_idx ON exception_types (company_id);

CREATE TABLE attendance (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    employee_id BIGINT NOT NULL REFERENCES employees (id),
    company_id BIGINT NOT NULL REFERENCES companies (id),
    check_in TIMESTAMPTZ NOT NULL,
    check_out TIMESTAMPTZ,
    method VARCHAR(10) CHECK (method IN ('app', 'excel', 'qr')),
    latitude NUMERIC(9, 6),
    longitude NUMERIC(9, 6),
    exception_type_id BIGINT REFERENCES exception_types (id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (exception_type_id IS NULL OR check_out IS NULL)
);
CREATE INDEX attendance_employee_id_idx ON attendance (employee_id);
CREATE INDEX attendance_company_id_idx ON attendance (company_id);
CREATE INDEX attendance_employee_check_in_idx ON attendance (employee_id, check_in);

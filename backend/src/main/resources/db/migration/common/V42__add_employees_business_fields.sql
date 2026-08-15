-- D-036: six employees business fields, mapped 1:1 from
-- hr-legacy/mysql_workin.schema.sql's employees table (lines 407-421),
-- all nullable there and kept nullable here -- this migration only
-- adds columns, a future load populates them.
--
-- employee_code VARCHAR(64): preserve exactly, do not renumber
-- (D-036). Legacy enforces per-company uniqueness with two separate
-- constraints for the same pair (unique_employee_code_per_company,
-- uq_employees_company_code -- legacy's own duplication) -- carried
-- here as one real UNIQUE.
--
-- country_code VARCHAR(10): retained, not dropped, until E.164 phone
-- normalization succeeds (D-036) -- reusable by OQ-2's (D-033)
-- migration-invalid-phone remediation once built.
--
-- national_id VARCHAR(50): migrated as-is.
--
-- birth_date / hire_date DATE: legacy zero-dates ('0000-00-00') map
-- to NULL, never an invented date (D-036, consistent with
-- docs/migration/invalid-date-analysis.md's original proposal -- 22
-- hire_date and 2 birth_date zero-date rows).
--
-- gender: legacy enum('male','female','other') maps 1:1 by value, not
-- a collapse (D-036) -- CHECK mirrors the legacy enum exactly, same
-- pattern as employees_role_check (V8) and attendance.method (V21).
ALTER TABLE employees
    ADD COLUMN employee_code VARCHAR(64),
    ADD COLUMN country_code VARCHAR(10),
    ADD COLUMN national_id VARCHAR(50),
    ADD COLUMN birth_date DATE,
    ADD COLUMN hire_date DATE,
    ADD COLUMN gender VARCHAR(10)
        CONSTRAINT employees_gender_check CHECK (gender IN ('male', 'female', 'other')),
    ADD CONSTRAINT employees_company_employee_code_unique UNIQUE (company_id, employee_code);

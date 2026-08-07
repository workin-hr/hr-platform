-- Translated from hr-legacy/mysql_workin.schema.sql at the pinned
-- Discovery commit. Recorded normalizations (slice spec): shifts
-- gains NOT NULL company_id (legacy allows null, but unowned rows are
-- invisible under RLS); the junction gains a denormalized company_id
-- for RLS; employees' new org columns are nullable (legacy's NOT NULL
-- branch_id cannot hold for already-created employees -- onboarding
-- enforces it when that flow exists).
CREATE TABLE branches (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    company_id BIGINT NOT NULL REFERENCES companies (id),
    name VARCHAR(255) NOT NULL,
    address VARCHAR(500),
    latitude NUMERIC(10, 7),
    longitude NUMERIC(10, 7),
    radius_meters INT NOT NULL DEFAULT 200,
    qr_code VARCHAR(100),
    expires_at TIMESTAMPTZ,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX branches_company_id_idx ON branches (company_id);

CREATE TABLE departments (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    company_id BIGINT NOT NULL REFERENCES companies (id),
    name VARCHAR(255) NOT NULL,
    manager_id BIGINT REFERENCES employees (id),
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX departments_company_id_idx ON departments (company_id);

CREATE TABLE department_branches (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    department_id BIGINT NOT NULL REFERENCES departments (id),
    branch_id BIGINT NOT NULL REFERENCES branches (id),
    company_id BIGINT NOT NULL REFERENCES companies (id),
    CONSTRAINT department_branches_pair_unique UNIQUE (department_id, branch_id)
);
CREATE INDEX department_branches_company_id_idx ON department_branches (company_id);

CREATE TABLE job_titles (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    company_id BIGINT NOT NULL REFERENCES companies (id),
    department_id BIGINT REFERENCES departments (id),
    name VARCHAR(255) NOT NULL,
    work_hours NUMERIC(5, 2) NOT NULL DEFAULT 8.00,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX job_titles_company_id_idx ON job_titles (company_id);

CREATE TABLE shifts (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    company_id BIGINT NOT NULL REFERENCES companies (id),
    name VARCHAR(100) NOT NULL,
    start_time TIME NOT NULL,
    end_time TIME NOT NULL,
    days_off VARCHAR(20),
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX shifts_company_id_idx ON shifts (company_id);

ALTER TABLE employees ADD COLUMN branch_id BIGINT REFERENCES branches (id);
ALTER TABLE employees ADD COLUMN department_id BIGINT REFERENCES departments (id);
ALTER TABLE employees ADD COLUMN job_title_id BIGINT REFERENCES job_titles (id);

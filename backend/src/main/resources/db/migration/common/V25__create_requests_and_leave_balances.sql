-- requests.manage is a new-platform key (owner-confirmed 2026-08-07):
-- legacy request creation is EMPLOYEE-only self-service, which the
-- rewrite defers codebase-wide; HR create-on-behalf needs its own
-- gate, distinct from requests.approve (legacy can_requests).
INSERT INTO permissions (permission_key, description) VALUES
    ('requests.manage', 'Create/edit/delete requests on behalf of employees and manage request types (new platform; legacy create was employee self-service only)');

INSERT INTO role_permissions (role, permission_id)
SELECT 'COMPANY_ADMIN', p.id FROM permissions p WHERE p.permission_key = 'requests.manage';

-- Translated from hr-legacy/mysql_workin.schema.sql (fetched at the
-- pinned Discovery commit 83c326e). company_id denormalized for RLS
-- (legacy scopes requests via an employees join). Normalizations,
-- recorded in the slice spec: uppercase status values, approver as a
-- membership FK (legacy stored an employee id), no updated_at,
-- plural leave_balances, and a real UNIQUE(employee_id, year) that
-- legacy only assumed app-level (same move as hr-legacy#21).
CREATE TABLE request_types (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    company_id BIGINT NOT NULL REFERENCES companies (id),
    name VARCHAR(100) NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    deduct_balance BOOLEAN NOT NULL DEFAULT FALSE,
    counts_as_paid_leave BOOLEAN NOT NULL DEFAULT TRUE,
    add_attendance_exception BOOLEAN NOT NULL DEFAULT FALSE,
    exception_type_id BIGINT REFERENCES exception_types (id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX request_types_company_id_idx ON request_types (company_id);

CREATE TABLE requests (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    employee_id BIGINT NOT NULL REFERENCES employees (id),
    company_id BIGINT NOT NULL REFERENCES companies (id),
    request_type_id BIGINT NOT NULL REFERENCES request_types (id),
    from_date DATE NOT NULL,
    to_date DATE NOT NULL,
    from_time TIME,
    to_time TIME,
    notes TEXT,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    reply TEXT,
    approver_membership_id BIGINT REFERENCES tenant_memberships (id),
    decided_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX requests_employee_id_idx ON requests (employee_id);
CREATE INDEX requests_company_id_idx ON requests (company_id);

CREATE TABLE leave_balances (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    employee_id BIGINT NOT NULL REFERENCES employees (id),
    company_id BIGINT NOT NULL REFERENCES companies (id),
    year SMALLINT NOT NULL,
    period_from_month SMALLINT NOT NULL DEFAULT 1,
    period_to_month SMALLINT NOT NULL DEFAULT 12,
    monthly_cap_days NUMERIC(5, 2),
    total_days NUMERIC(5, 1) NOT NULL DEFAULT 0.0,
    used_days NUMERIC(5, 1) NOT NULL DEFAULT 0.0,
    remaining_days NUMERIC(5, 1) GENERATED ALWAYS AS (total_days - used_days) STORED,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT leave_balances_employee_year_unique UNIQUE (employee_id, year)
);
CREATE INDEX leave_balances_company_id_idx ON leave_balances (company_id);

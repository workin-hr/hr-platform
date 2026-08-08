-- Employee-schedule foundation
-- (docs/superpowers/specs/2026-08-08-employee-schedule-foundation-design.md).
-- Translated from hr-legacy/mysql_workin.schema.sql:455-482 @ d113204.
-- Recorded normalizations: both tables gain a denormalized company_id
-- for RLS (legacy has no tenant column here -- the same treatment V29
-- gave department_branches); employee_schedules gets an explicit
-- UNIQUE (employee_id, schedule_date) -- legacy relies on an implicit
-- unique key for ON DUPLICATE KEY UPDATE, Postgres needs it declared.
CREATE TABLE employee_shift_assignments (
	id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
	company_id BIGINT NOT NULL REFERENCES companies (id),
	employee_id BIGINT NOT NULL REFERENCES employees (id),
	shift_id BIGINT NOT NULL REFERENCES shifts (id),
	effective_from DATE NOT NULL,
	created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX employee_shift_assignments_lookup_idx
	ON employee_shift_assignments (employee_id, effective_from DESC);
CREATE INDEX employee_shift_assignments_company_id_idx
	ON employee_shift_assignments (company_id);

CREATE TABLE employee_schedules (
	id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
	company_id BIGINT NOT NULL REFERENCES companies (id),
	employee_id BIGINT NOT NULL REFERENCES employees (id),
	schedule_date DATE NOT NULL,
	name VARCHAR(255),
	start_time TIME,
	end_time TIME,
	exception_note VARCHAR(255),
	created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
	CONSTRAINT employee_schedules_employee_date_unique UNIQUE (employee_id, schedule_date)
);
CREATE INDEX employee_schedules_company_id_idx ON employee_schedules (company_id);

-- Owner decision 2026-08-08: dedicated schedules.* keys (the
-- key-per-module pattern), a recorded departure from legacy's
-- role-only gate on apis/api/schedules/*.
INSERT INTO permissions (permission_key, description) VALUES
	('schedules.read', 'View employee schedules and monthly overview (legacy: role-only gate)'),
	('schedules.manage', 'Assign and generate employee schedule days (legacy: role-only gate)');

INSERT INTO role_permissions (role, permission_id)
SELECT 'COMPANY_ADMIN', p.id FROM permissions p
WHERE p.permission_key IN ('schedules.read', 'schedules.manage');

-- Normalized permission catalog -- docs/adr/ADR-0010-authorization-model.md
-- Dimension 3, docs/architecture/authorization-model.md §3/§7. Global,
-- not tenant-scoped: a permission's existence and its key are the same
-- across every tenant, so no RLS applies to these two tables.
CREATE TABLE permissions (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    permission_key VARCHAR(128) NOT NULL UNIQUE,
    description VARCHAR(255) NOT NULL
);

CREATE TABLE role_permissions (
    role VARCHAR(32) NOT NULL,
    permission_id BIGINT NOT NULL REFERENCES permissions (id),
    CONSTRAINT role_permissions_role_check CHECK (role IN ('COMPANY_ADMIN', 'HR', 'MANAGER', 'EMPLOYEE')),
    PRIMARY KEY (role, permission_id)
);

-- membership_roles is tenant-scoped (through its owning membership) and
-- RLS-protected (rls/V6__enable_row_level_security.sql).
CREATE TABLE membership_roles (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    membership_id BIGINT NOT NULL REFERENCES tenant_memberships (id),
    company_id BIGINT NOT NULL REFERENCES companies (id),
    role VARCHAR(32) NOT NULL,
    CONSTRAINT membership_roles_role_check CHECK (role IN ('COMPANY_ADMIN', 'HR', 'MANAGER', 'EMPLOYEE')),
    CONSTRAINT membership_roles_membership_role_unique UNIQUE (membership_id, role)
);

CREATE INDEX membership_roles_company_id_idx ON membership_roles (company_id);

-- Canonical permission catalog, mapped directly from hr-legacy's real
-- hr_permissions columns -- docs/architecture/authorization-model.md §7.
-- Seeded here as real, correct reference data (Required Implementation
-- Task 1's mapping, carried from documentation into schema); population
-- of role_permissions default bundles and membership_permission_overrides
-- (Required Implementation Task 2, the legacy-data migration itself) is
-- separate, later work -- not invented here.
INSERT INTO permissions (permission_key, description) VALUES
    ('reports.read', 'View platform-admin/company report dashboards (legacy can_dashboard)'),
    ('activities.read', 'View recent activity feed (legacy can_recent_activities)'),
    ('branches.read', 'View branches (legacy can_branches)'),
    ('branches.manage', 'Create/edit/delete branches (legacy can_branches)'),
    ('departments.read', 'View departments (legacy can_departments)'),
    ('departments.manage', 'Create/edit/delete departments (legacy can_departments)'),
    ('job_titles.read', 'View job titles (legacy can_job_titles)'),
    ('job_titles.manage', 'Create/edit/delete job titles (legacy can_job_titles)'),
    ('shifts.read', 'View shifts (legacy can_shifts)'),
    ('shifts.manage', 'Create/edit/delete shifts (legacy can_shifts)'),
    ('leave_balances.read', 'View leave balances (legacy can_leave_balances)'),
    ('leave_balances.manage', 'Adjust leave balances (legacy can_leave_balances)'),
    ('assets.read', 'View assets (legacy can_assets)'),
    ('assets.manage', 'Create/edit/delete assets (legacy can_assets)'),
    ('advances.read', 'View advances (legacy can_advances)'),
    ('advances.manage', 'Create/edit advances (legacy can_advances)'),
    ('advances.approve', 'Approve/reject/pay advances (legacy can_advances)'),
    ('workforce_planning.read', 'View workforce planning (legacy can_workforce_planning)'),
    ('workforce_planning.manage', 'Edit workforce planning (legacy can_workforce_planning)'),
    ('salary_calculator.read', 'Use the gross-to-net salary calculator (legacy can_salary_calculator)'),
    ('company.settings.read', 'View company settings (legacy can_company_settings)'),
    ('company.settings.manage', 'Edit company settings (legacy can_company_settings)'),
    ('employees.read', 'View employees (legacy can_employees)'),
    ('employees.manage', 'Create/edit/delete employees (legacy can_employees)'),
    ('employees.decisions.manage', 'Administrative decisions on employees (legacy can_employees)'),
    ('notifications.manage', 'Manage notifications (legacy can_employees)'),
    ('complaints.manage', 'Manage complaints (legacy can_employees)'),
    ('attendance.read', 'View attendance (legacy can_attendance)'),
    ('attendance.correct', 'Correct/edit attendance records (legacy can_attendance)'),
    ('requests.read', 'View requests (legacy can_requests)'),
    ('requests.approve', 'Approve/reject requests (legacy can_requests)'),
    ('payroll.read', 'View payroll (legacy can_payroll)'),
    ('payroll.run', 'Run/finalize payroll batches (legacy can_payroll)'),
    ('penalties.read', 'View penalties (legacy can_penalties)'),
    ('penalties.manage', 'Create/edit/delete penalties (legacy can_penalties)'),
    ('platform.companies.read', 'View any company on the platform (platform-admin only)'),
    ('platform.companies.approve', 'Approve/reject company registrations (platform-admin only)'),
    ('platform.companies.suspend', 'Suspend a company (platform-admin only)'),
    ('platform.companies.delete', 'Delete a company (platform-admin only)');

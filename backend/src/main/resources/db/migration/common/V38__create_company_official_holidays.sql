-- Ports hr-legacy's company_official_holidays (mysql_workin.schema.sql:244-250,
-- 1030-1033 @ d113204). Single-day, per-company, named holidays -- the
-- table the schedule and attendance-calendar modules have both been
-- carrying a declared "empty holidays" stub against since PR #67.
--
-- Faithful to legacy: one DATE per row (no ranges), a NOT NULL name the
-- API rejects blank but the column permits, and a UNIQUE
-- (company_id, holiday_date) that makes "same day twice" impossible.
--
-- Deliberate departures, all additive and none behavioural:
--   * company_id carries a real FOREIGN KEY. Legacy declares none
--     anywhere in this table, so orphan rows are possible there and
--     deleting a company leaves its holidays behind.
--   * legacy's idx_company_holiday_range is dropped: it duplicates
--     uq_company_holiday_date exactly (same columns, same order), so it
--     is a second copy of the same B-tree for no benefit.
--   * updated_at exists here. Legacy has none despite its update
--     endpoint mutating rows, so a changed holiday is untraceable.
CREATE TABLE company_official_holidays (
	id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
	company_id BIGINT NOT NULL REFERENCES companies(id),
	name VARCHAR(150) NOT NULL,
	holiday_date DATE NOT NULL,
	created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
	updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
	CONSTRAINT uq_company_official_holidays_date UNIQUE (company_id, holiday_date)
);

-- The unique constraint already indexes (company_id, holiday_date), which
-- is exactly the range-scan shape every read uses, so no further index.

-- First dedicated holidays.* keys. Legacy gates these endpoints on role
-- plus the can_company_settings flag rather than a permission key; the
-- key-per-module pattern is this codebase's established replacement
-- (the V33 precedent), and COMPANY_ADMIN gets both by default per V20.
INSERT INTO permissions (permission_key, description) VALUES
	('holidays.read', 'View company official holidays (legacy: role + can_company_settings gate)'),
	('holidays.manage', 'Create, update and delete company official holidays (legacy: role + can_company_settings gate)');

INSERT INTO role_permissions (role, permission_id)
SELECT 'COMPANY_ADMIN', p.id FROM permissions p
WHERE p.permission_key IN ('holidays.read', 'holidays.manage');

-- Ports legacy's per-employee expected-hours override
-- (hr-legacy mysql_workin.schema.sql:408, `expected_daily_hours
-- decimal(5,2) DEFAULT NULL`) so the attendance-calendar engine can
-- reproduce its expected-minutes fallback chain exactly:
--   COALESCE(NULLIF(employees.expected_daily_hours, 0),
--            NULLIF(job_titles.work_hours, 0),
--            8) * 60
-- (attendance_excel_analyzer.php:517-534). The owner's standing
-- instruction is that the new system's business rules match hr-legacy;
-- collapsing this to a job-title-then-8 fallback would silently drop a
-- per-employee override that legacy honours, so the column is ported
-- rather than designed away (the open question the engine's design spec
-- left for this decision).
--
-- Nullable with no default, exactly as legacy: NULL and 0 both mean
-- "unset" to the NULLIF chain, so a 0 written by an importer behaves
-- the same as an absent value. NUMERIC(5,2) is the Postgres spelling of
-- decimal(5,2) -- same precision, same 0.00-999.99 range.
ALTER TABLE employees
	ADD COLUMN expected_daily_hours NUMERIC(5, 2);

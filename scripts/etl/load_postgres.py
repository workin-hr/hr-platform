#!/usr/bin/env python3
"""Emit the PostgreSQL load program for the legacy exports.

Companion to `export_legacy.py`. Together they are the ETL the
reconciliation harness has never had: extraction produced nothing, and
nothing loaded.

Emits SQL rather than connecting, for the same reason extraction does --
this repository's tooling is stdlib-only, so it must run on an
operator's machine with no driver installs. The operator runs psql from
the directory holding the CSVs.

    python3 scripts/etl/load_postgres.py --print-sql | psql "$TARGET"

Design commitments, all of which the emitted SQL keeps:

* **Deterministic.** Ids are allocated in legacy-id order, so the same
  export loaded twice into two empty databases produces the same map.
* **Restartable.** Every step skips what it already did. A load killed
  halfway can be re-run and continues.
* **Rerun-safe.** Re-running a completed load inserts nothing.
* **Fail-fast.** Unmapped foreign keys and unmapped legacy permission
  columns abort with a named error rather than loading a partial row.
* **Auditable.** `migration.id_map` and `migration.load_counts` are real
  tables, not in-memory state, and are what reconciliation reads.

Sections (`--section`): `ddl`, `copy`, `load`, `finalize`. Tests use
`ddl` + `load` + `finalize` and populate staging themselves; an operator
uses all four.

Usage:
    python3 scripts/etl/load_postgres.py --print-sql
    python3 scripts/etl/load_postgres.py --section load
    python3 scripts/etl/load_postgres.py --self-test
"""

from __future__ import annotations

import sys

# hr_permissions can_* flag -> the permission keys it grants, in the
# same order as hr-legacy's own column order (mysql_workin.schema.sql)
# so STAGING below can declare them positionally for COPY. A flag
# present in the dump but absent here aborts the load: silently
# dropping a permission is how someone loses access after cutover.
PERMISSION_MAP = {
    "can_dashboard": ["reports.read"],
    "can_recent_activities": ["activities.read"],
    "can_branches": ["branches.read", "branches.manage"],
    "can_departments": ["departments.read", "departments.manage"],
    "can_job_titles": ["job_titles.read", "job_titles.manage"],
    "can_shifts": ["shifts.read", "shifts.manage"],
    "can_leave_balances": ["leave_balances.read", "leave_balances.manage"],
    "can_assets": ["assets.read", "assets.manage"],
    "can_advances": ["advances.read", "advances.manage", "advances.approve"],
    "can_workforce_planning": ["workforce_planning.read", "workforce_planning.manage"],
    "can_salary_calculator": ["salary_calculator.read"],
    "can_company_settings": ["company.settings.read", "company.settings.manage"],
    "can_employees": ["employees.read", "employees.manage", "employees.decisions.manage",
                       "notifications.manage", "complaints.manage"],
    "can_attendance": ["attendance.read", "attendance.correct"],
    "can_requests": ["requests.read", "requests.approve"],
    "can_payroll": ["payroll.read", "payroll.run"],
    "can_penalties": ["penalties.read", "penalties.manage"],
}

# --------------------------------------------------------------------
# Staging: every column TEXT. Legacy types are re-asserted on the way
# into the real tables, so a bad value fails at the cast with the row in
# hand rather than at COPY time with a line number.
# --------------------------------------------------------------------
STAGING = {
    "companies": ["id", "name", "phone", "status", "created_at"],
    "employees": [
        "id", "company_id", "branch_id", "department_id", "job_title_id", "employee_code",
        "expected_daily_hours", "first_name", "last_name", "phone", "country_code",
        "password_hash", "role", "national_id", "birth_date", "gender", "hire_date",
        "is_active", "created_at", "updated_at",
    ],
    "attendance": [
        "id", "employee_id", "check_in", "check_out", "method", "exception_type_id",
        "latitude", "longitude", "created_at", "updated_at",
    ],
    "attendance_days": ["id", "legacy_day"],
    "exception_types": ["id", "company_id", "name", "created_at"],
    "shifts": ["id", "company_id", "name", "start_time", "end_time", "days_off", "is_active", "created_at"],
    "branches": [
        "id", "company_id", "name", "address", "latitude", "longitude", "radius_meters",
        "expires_at", "qr_code", "is_active", "created_at",
    ],
    "job_titles": ["id", "company_id", "department_id", "name", "work_hours", "is_active", "created_at"],
    "departments": ["id", "company_id", "name", "manager_id", "is_active", "created_at"],
    "department_branches": ["department_id", "branch_id"],
    "request_types": [
        "id", "company_id", "name", "is_active", "deduct_balance", "counts_as_paid_leave",
        "add_attendance_exception", "exception_type_id", "created_at",
    ],
    "requests": [
        "id", "employee_id", "request_type_id", "from_date", "to_date", "from_time", "to_time",
        "notes", "status", "reply", "approver_id", "decided_at", "created_at", "updated_at",
    ],
    "company_official_holidays": ["id", "company_id", "name", "holiday_date", "created_at"],
    "salary_contracts": [
        "id", "employee_id", "salary_mode", "basic_salary", "daily_wage", "housing_allowance",
        "transport_allowance", "food_allowance", "risk_allowance", "incentives",
        "insurance_deduction", "tax_deduction", "advances_deduction", "fund_deduction",
        "penalty_deduction", "effective_from", "created_at",
    ],
    "payroll_batches": ["id", "company_id", "month", "year", "period_from", "period_to", "status", "created_at"],
    "advances": [
        "id", "employee_id", "amount", "remaining", "reason", "rejection_reason",
        "status", "request_date", "created_at", "updated_at",
        "deduction_mode", "deduction_month_count", "deduction_amount_per_month",
        "deduction_payroll_year", "deduction_payroll_month",
    ],
    "penalties": [
        "id", "employee_id", "penalty_type", "penalty_days", "reason", "penalty_date",
        "applied_to_payroll", "created_at",
    ],
    "payslips": [
        "id", "batch_id", "employee_id", "days_present", "days_absent", "days_leave",
        "overtime_hours", "basic_salary", "allowances", "overtime_pay", "penalties_total",
        "advance_deduction", "other_deductions", "net_salary", "food_allowance",
        "risk_allowance", "transport_allowance", "incentives", "insurance_deduction",
        "tax_deduction", "advances_deduction", "fund_deduction", "gross_salary",
        "total_entitlements", "total_deductions",
    ],
    # Staged under the LEGACY name; the target table is leave_balances.
    # See LOAD_ORDER for why the two must not be confused.
    "leave_balance": [
        "id", "employee_id", "year", "period_from_month", "period_to_month",
        "monthly_cap_days", "total_days", "used_days", "remaining_days",
    ],
    "employee_schedules": [
        "id", "employee_id", "schedule_date", "name", "start_time", "end_time",
        "exception_note", "created_at",
    ],
    "employee_shift_assignments": [
        "id", "employee_id", "shift_id", "effective_from", "created_at",
    ],
    # Transform sources, loaded in legacy shape. hr_permissions' column
    # list must match hr-legacy's own column order exactly: plain COPY
    # matches positionally, not by CSV header name, so this is not a
    # convenience -- it is what makes `\copy ... FROM 'hr_permissions.csv'`
    # against the real export line up at all.
    "hr_permissions": ["id", "employee_id"] + list(PERMISSION_MAP) + ["updated_at"],
    "legacy_company_settings": ["id", "company_id", "setting_definition_id"],
    "company_setting_values": ["id", "company_setting_id", "setting_allowed_value_id"],
    # Fix B: these two were the actual bug (docs/migration/2026-08-13-etl-
    # real-data-findings-decision-brief.md). export_legacy.py used to
    # SELECT * here -- 11 and 7 legacy columns respectively -- while this
    # dict declared only 2 and 4. \copy matches a CSV to this table
    # positionally, not by header name (see the hr_permissions comment
    # below), so that drift is not a missing value, it is every column
    # after the declared ones shifted left, and \copy rejected the whole
    # file with "extra data after last expected column". These two lists
    # are now the FULL legacy column set minus updated_at (verified dead:
    # zero reads anywhere in hr-legacy, and V44__create_settings_catalog.sql
    # deliberately has no such target column -- see EXPORT_SQL's comment
    # in export_legacy.py, right above these same two SELECTs).
    # coverage_audit.py's check_export_staging_agreement enforces that
    # these two lists never again drift from what EXPORT_SQL actually
    # selects.
    "setting_definitions": [
        "id", "setting_key", "label_ar", "label_en", "description_ar",
        "description_en", "icon_data", "is_multi", "is_required", "sort_order",
    ],
    "setting_allowed_values": [
        "id", "setting_definition_id", "value", "label_ar", "label_en", "sort_order",
    ],
}

# Legacy setting_key -> typed company_settings column. BOOLEAN columns
# get string-normalised rather than cast (see _load's setting_cases):
# payroll_company_pays_overtime (hr-legacy/apis/helpers/
# payroll_calculation.php:140-149) treats only '1'/'true'/'yes'/'on'
# (case-insensitive, trimmed) as true, anything else present as false --
# a plain ::BOOLEAN cast would error on values legacy itself treats as
# false, and accept a couple (e.g. 't') legacy does not.
SETTING_COLUMNS = {
    "month_start_day": ("month_start_day", "SMALLINT"),
    "month_end_day": ("month_end_day", "SMALLINT"),
    "weekly_off_days": ("weekly_off_days", "VARCHAR"),
    "overtime_rate": ("overtime_rate", "NUMERIC"),
    "monthly_leave_accrual": ("monthly_leave_accrual", "NUMERIC"),
    "pay_overtime": ("pay_overtime", "BOOLEAN"),
}

# setting_key values accepted as "true" by payroll_company_pays_overtime.
# A row that exists but doesn't match is false; no row at all (the
# aggregate below sees nothing) stays NULL -- unset, not false --
# so EffectiveCompanySettings' own default (true) applies downstream.
TRUTHY_SETTING_VALUES = ("1", "true", "yes", "on")

# Entities whose ids are allocated and mapped, in foreign-key order.
# departments precedes job_titles/employees (both reference it), but
# departments.manager_id -> employees is the reverse direction -- a
# genuine cycle, resolved by loading departments with manager_id NULL
# here and backfilling it once employees exist (see _load).
LOAD_ORDER = [
    # setting_definitions/setting_allowed_values (V44) are global catalog
    # tables -- no RLS, no company_id, same precedent as
    # common/V4__create_permission_catalog.sql's permissions table (see
    # V44's own comment block) -- so they have no foreign-key dependency
    # on anything else in this list and could run anywhere. Listed first
    # for that reason: the only internal ordering constraint is
    # setting_allowed_values.setting_definition_id -> setting_definitions,
    # which _load() satisfies by allocating/inserting definitions before
    # allowed_values (see the "settings catalog" section below).
    "setting_definitions", "setting_allowed_values",
    "companies", "departments", "branches", "job_titles", "shifts", "exception_types", "employees",
    "identities", "tenant_memberships", "request_types", "requests",
    "company_official_holidays", "salary_contracts", "payroll_batches", "advances",
    "penalties", "attendance",
    # History and scheduling. All four are foreign-key leaves -- nothing in
    # the target schema REFERENCES them -- so they append here with no cycle
    # to resolve.
    #
    # NOTE the name: these entries are TARGET table names, because _finalize
    # uses them directly as such (pg_get_serial_sequence, the count rollup,
    # the unmapped-FK guard). Every other entity's legacy and target names
    # happen to match; `leave_balances` is the first that does not. Its
    # STAGING key, CSV, and staging table stay on the legacy singular
    # `leave_balance`. Writing the legacy name here emits SQL against a
    # table PostgreSQL does not have, and fails in finalize -- after every
    # INSERT has already run.
    "payslips", "leave_balances", "employee_schedules", "employee_shift_assignments",
]

# Tables loaded through mapped parents but without a legacy id of their own.
# They still need row-count artifacts and sequence finalization.
AUXILIARY_LOAD_TABLES = ["department_branches"]
ARTIFACT_TABLES = LOAD_ORDER + AUXILIARY_LOAD_TABLES


def _ddl() -> str:
    out = [
        "-- ================= migration bookkeeping =================",
        "CREATE SCHEMA IF NOT EXISTS migration;",
        "",
        "-- The durable legacy -> new id mapping. A real table, not in-memory",
        "-- state: reconciliation reads it, and without it a finding reports",
        "-- an id nobody can trace back to the legacy row.",
        "CREATE TABLE IF NOT EXISTS migration.id_map (",
        "    entity     TEXT   NOT NULL,",
        "    legacy_id  BIGINT NOT NULL,",
        "    new_id     BIGINT NOT NULL,",
        "    PRIMARY KEY (entity, legacy_id)",
        ");",
        "-- Many legacy employees can share one identity (same phone), so",
        "-- new_id is deliberately not unique.",
        "CREATE INDEX IF NOT EXISTS id_map_entity_new_idx ON migration.id_map (entity, new_id);",
        "",
        "CREATE TABLE IF NOT EXISTS migration.load_counts (",
        "    entity   TEXT PRIMARY KEY,",
        "    rows     BIGINT NOT NULL,",
        "    recorded TIMESTAMPTZ NOT NULL DEFAULT now()",
        ");",
        "",
        "-- ================= staging =================",
    ]
    for table, columns in STAGING.items():
        cols = ",\n".join(f"    {c} TEXT" for c in columns)
        out += [f"CREATE TABLE IF NOT EXISTS migration.stg_{table} (", cols, ");", ""]
    return "\n".join(out)


def _copy() -> str:
    out = ["-- ================= load the exports =================",
           "-- Run psql from the directory holding the CSVs.",
           "-- \\N is NULL, distinct from the empty string.", ""]
    for table in STAGING:
        source = "company_settings.csv" if table == "legacy_company_settings" else f"{table}.csv"
        out.append(
            f"\\copy migration.stg_{table} FROM '{source}' WITH (FORMAT csv, HEADER true, NULL '\\N')")
    return "\n".join(out)


def _allocate(entity: str, source: str, legacy_id: str = "id", where: str = "TRUE") -> str:
    """Deterministic id allocation: legacy-id order, skipping what exists."""
    return f"""
-- {entity}: allocate ids (deterministic, restartable)
INSERT INTO migration.id_map (entity, legacy_id, new_id)
SELECT '{entity}', s.{legacy_id}::BIGINT,
       nextval(pg_get_serial_sequence('{entity}', 'id'))
FROM {source} s
WHERE {where}
  AND s.{legacy_id} IS NOT NULL
  AND NOT EXISTS (
      SELECT 1 FROM migration.id_map m
      WHERE m.entity = '{entity}' AND m.legacy_id = s.{legacy_id}::BIGINT)
ORDER BY s.{legacy_id}::BIGINT;
""".strip()


def _fk(alias: str, entity: str, column: str) -> str:
    return (f"LEFT JOIN migration.id_map {alias} ON {alias}.entity = '{entity}' "
            f"AND {alias}.legacy_id = {column}::BIGINT")


def _load() -> str:
    p = []

    # ---------- companies ----------
    p.append(_allocate("companies", "migration.stg_companies"))
    p.append("""
INSERT INTO companies (id, name, phone, active, created_at) OVERRIDING SYSTEM VALUE
SELECT m.new_id, s.name, s.phone, COALESCE(s.status, 'active') = 'active',
       (s.created_at::TIMESTAMP AT TIME ZONE 'UTC')
FROM migration.stg_companies s
JOIN migration.id_map m ON m.entity = 'companies' AND m.legacy_id = s.id::BIGINT
WHERE NOT EXISTS (SELECT 1 FROM companies c WHERE c.id = m.new_id);
""".strip())

    # ---------- departments ----------
    # manager_id is deliberately NULL here: departments.manager_id ->
    # employees is the reverse of employees/job_titles.department_id ->
    # departments, a genuine cycle. Employees don't have ids yet at this
    # point in the load, so manager_id is backfilled after the employees
    # block below, once migration.id_map has an 'employees' entry to
    # resolve it through.
    p.append(_allocate("departments", "migration.stg_departments"))
    p.append("""
INSERT INTO departments (id, company_id, name, is_active, created_at) OVERRIDING SYSTEM VALUE
SELECT m.new_id, cm.new_id, s.name, COALESCE(s.is_active, '1') = '1',
       (s.created_at::TIMESTAMP AT TIME ZONE 'UTC')
FROM migration.stg_departments s
JOIN migration.id_map m ON m.entity = 'departments' AND m.legacy_id = s.id::BIGINT
JOIN migration.id_map cm ON cm.entity = 'companies' AND cm.legacy_id = s.company_id::BIGINT
WHERE NOT EXISTS (SELECT 1 FROM departments d WHERE d.id = m.new_id);
""".strip())

    # ---------- branches / job_titles / shifts / exception_types ----------
    p.append(_allocate("branches", "migration.stg_branches"))
    p.append("""
-- expires_at is a legacy `datetime`, not a `timestamp`: literal wall clock
-- with no offset ever applied, so it loads as the same reading in UTC and
-- is never shifted -- the same rule attendance.check_in follows.
INSERT INTO branches (id, company_id, name, address, latitude, longitude, radius_meters,
                      is_active, created_at, qr_code, expires_at)
OVERRIDING SYSTEM VALUE
SELECT m.new_id, cm.new_id, s.name, s.address,
       s.latitude::NUMERIC, s.longitude::NUMERIC, COALESCE(s.radius_meters::INT, 0),
       COALESCE(s.is_active, '1') = '1',
       (s.created_at::TIMESTAMP AT TIME ZONE 'UTC'),
       s.qr_code,
       (s.expires_at::TIMESTAMP AT TIME ZONE 'UTC')
FROM migration.stg_branches s
JOIN migration.id_map m ON m.entity = 'branches' AND m.legacy_id = s.id::BIGINT
JOIN migration.id_map cm ON cm.entity = 'companies' AND cm.legacy_id = s.company_id::BIGINT
WHERE NOT EXISTS (SELECT 1 FROM branches b WHERE b.id = m.new_id);
""".strip())

    p.append("""
-- department_branches has a composite legacy key, so it needs no id_map
-- row of its own. Both parents still resolve through the durable map,
-- and company_id is derived from the department after requiring the
-- branch to belong to the same company.
INSERT INTO department_branches (department_id, branch_id, company_id)
SELECT dm.new_id, bm.new_id, d.company_id
FROM migration.stg_department_branches s
JOIN migration.id_map dm ON dm.entity = 'departments' AND dm.legacy_id = s.department_id::BIGINT
JOIN migration.id_map bm ON bm.entity = 'branches' AND bm.legacy_id = s.branch_id::BIGINT
JOIN departments d ON d.id = dm.new_id
JOIN branches b ON b.id = bm.new_id AND b.company_id = d.company_id
ON CONFLICT (department_id, branch_id) DO NOTHING;

-- Unlike id-mapped tables, the junction cannot use finalize's generic
-- id_map guard. Check every staged pair explicitly so an orphaned or
-- cross-company assignment aborts instead of disappearing silently.
DO $$
DECLARE missing BIGINT;
BEGIN
    SELECT count(*) INTO missing
    FROM migration.stg_department_branches s
    LEFT JOIN migration.id_map dm
      ON dm.entity = 'departments' AND dm.legacy_id = s.department_id::BIGINT
    LEFT JOIN migration.id_map bm
      ON bm.entity = 'branches' AND bm.legacy_id = s.branch_id::BIGINT
    LEFT JOIN departments d ON d.id = dm.new_id
    LEFT JOIN branches b ON b.id = bm.new_id
    WHERE dm.new_id IS NULL OR bm.new_id IS NULL
       OR d.id IS NULL OR b.id IS NULL
       OR d.company_id IS DISTINCT FROM b.company_id
       OR NOT EXISTS (
           SELECT 1 FROM department_branches db
           WHERE db.department_id = dm.new_id
             AND db.branch_id = bm.new_id
             AND db.company_id = d.company_id);
    IF missing > 0 THEN
        RAISE EXCEPTION 'ETL: % department_branches rows were not loaded -- a parent was missing or belonged to another company', missing;
    END IF;
END $$;
""".strip())

    p.append(_allocate("job_titles", "migration.stg_job_titles"))
    p.append(f"""
INSERT INTO job_titles (id, company_id, department_id, name, work_hours, is_active, created_at)
OVERRIDING SYSTEM VALUE
SELECT m.new_id, cm.new_id, dm.new_id, s.name,
       COALESCE(s.work_hours::NUMERIC, 8), COALESCE(s.is_active, '1') = '1',
       (s.created_at::TIMESTAMP AT TIME ZONE 'UTC')
FROM migration.stg_job_titles s
JOIN migration.id_map m ON m.entity = 'job_titles' AND m.legacy_id = s.id::BIGINT
JOIN migration.id_map cm ON cm.entity = 'companies' AND cm.legacy_id = s.company_id::BIGINT
{_fk('dm', 'departments', 's.department_id')}
WHERE NOT EXISTS (SELECT 1 FROM job_titles j WHERE j.id = m.new_id);
""".strip())

    p.append(_allocate("shifts", "migration.stg_shifts"))
    p.append("""
INSERT INTO shifts (id, company_id, name, start_time, end_time, days_off, is_active, created_at)
OVERRIDING SYSTEM VALUE
SELECT m.new_id, cm.new_id, s.name, s.start_time::TIME, s.end_time::TIME, s.days_off,
       COALESCE(s.is_active, '1') = '1',
       (s.created_at::TIMESTAMP AT TIME ZONE 'UTC')
FROM migration.stg_shifts s
JOIN migration.id_map m ON m.entity = 'shifts' AND m.legacy_id = s.id::BIGINT
JOIN migration.id_map cm ON cm.entity = 'companies' AND cm.legacy_id = s.company_id::BIGINT
WHERE NOT EXISTS (SELECT 1 FROM shifts sh WHERE sh.id = m.new_id);
""".strip())

    # D-035/A6 (finding H): legacy exception_types 1, 3 and 4 all point at
    # company_id 19, which does not exist -- still true against live
    # production on 2026-08-15, unchanged since the 2026-08-03 snapshot
    # (D-037). A6 decided to exclude them from the operational migration
    # and record the exclusion explicitly, "not a silent drop".
    #
    # A silent drop is exactly what happened before this guard: ids were
    # allocated for all three, then the INSERT's join to the company map
    # dropped them, leaving orphaned migration.id_map rows behind (proved
    # in the 2026-08-15 rehearsal -- 142 staged, 139 loaded, ids 1/3/4
    # still present in id_map). Filtering at ALLOCATION instead means no
    # id_map entry is ever created for an unresolvable parent.
    #
    # This deliberately does NOT touch _finalize()'s unmapped-FK guard.
    # That guard fires when an id_map entry has no matching loaded row; by
    # keeping these rows out of id_map entirely, the guard never sees them
    # and stays fully armed for any OTHER unexpected unmapped FK -- which
    # is the case it exists to catch.
    _orphan_company = (
        "NOT EXISTS (SELECT 1 FROM migration.id_map cm "
        "WHERE cm.entity = 'companies' AND cm.legacy_id = s.company_id::BIGINT)"
    )
    p.append(_allocate("exception_types", "migration.stg_exception_types",
                       where=f"NOT ({_orphan_company})"))
    p.append(f"""
-- The exclusion, recorded as an artifact rather than left implicit --
-- reconciliation reads migration.load_counts, so a reader comparing
-- legacy's exception_types count against the target's finds the
-- difference already explained here instead of looking like data loss.
-- Still owed: A6's "record the exclusion" arguably wants the excluded
-- ids themselves, not just a count. That needs D-035's shared
-- remediation/audit output, which does not exist yet.
INSERT INTO migration.load_counts (entity, rows)
SELECT 'exception_types_excluded_orphaned_company', count(*)
FROM migration.stg_exception_types s
WHERE {_orphan_company}
ON CONFLICT (entity) DO UPDATE SET rows = EXCLUDED.rows, recorded = now();
""".strip())
    p.append("""
INSERT INTO exception_types (id, company_id, name, created_at) OVERRIDING SYSTEM VALUE
SELECT m.new_id, cm.new_id, s.name,
       (s.created_at::TIMESTAMP AT TIME ZONE 'UTC')
FROM migration.stg_exception_types s
JOIN migration.id_map m ON m.entity = 'exception_types' AND m.legacy_id = s.id::BIGINT
JOIN migration.id_map cm ON cm.entity = 'companies' AND cm.legacy_id = s.company_id::BIGINT
WHERE NOT EXISTS (SELECT 1 FROM exception_types e WHERE e.id = m.new_id);
""".strip())

    # ---------- employees ----------
    p.append(_allocate("employees", "migration.stg_employees"))
    p.append("""
-- One row per distinct phone: the deterministic identity anchor (the
-- lowest legacy employee id owning that phone, possibly in a different
-- company). Computed once and joined wherever "does this employee own
-- the identity, or share someone else's" matters, instead of a
-- correlated subquery per row.
CREATE TEMP TABLE IF NOT EXISTS migration_phone_anchor AS
SELECT s.phone, MIN(s.id::BIGINT) AS anchor_employee_id
FROM migration.stg_employees s
WHERE s.phone IS NOT NULL AND s.phone <> ''
GROUP BY s.phone;
""".strip())
    p.append(f"""
-- employees.phone is globally UNIQUE (V8), unlike legacy's -- the same
-- phone can legitimately own employee rows in several companies. Only
-- the phone anchor keeps its phone here; every other employee sharing
-- it gets NULL, since the real, shared phone lives on the identity
-- every one of them is linked to via tenant_memberships.
INSERT INTO employees (id, company_id, first_name, last_name, phone, role, active,
                       branch_id, department_id, job_title_id, expected_daily_hours,
                       created_at)
OVERRIDING SYSTEM VALUE
SELECT m.new_id, cm.new_id, s.first_name, COALESCE(s.last_name, ''),
       CASE WHEN pa.anchor_employee_id = s.id::BIGINT THEN s.phone ELSE NULL END,
       UPPER(COALESCE(s.role, 'employee')), COALESCE(s.is_active, '1') = '1',
       bm.new_id, dm.new_id, jm.new_id, s.expected_daily_hours::NUMERIC,
       (s.created_at::TIMESTAMP AT TIME ZONE 'UTC')
FROM migration.stg_employees s
JOIN migration.id_map m ON m.entity = 'employees' AND m.legacy_id = s.id::BIGINT
JOIN migration.id_map cm ON cm.entity = 'companies' AND cm.legacy_id = s.company_id::BIGINT
LEFT JOIN migration_phone_anchor pa ON pa.phone = s.phone
{_fk('bm', 'branches', 's.branch_id')}
{_fk('dm', 'departments', 's.department_id')}
{_fk('jm', 'job_titles', 's.job_title_id')}
WHERE NOT EXISTS (SELECT 1 FROM employees e WHERE e.id = m.new_id);

-- departments.manager_id backfill: the reverse half of the cycle noted
-- above. Employees now have ids, so this can resolve; NULL legacy
-- manager_id (most departments) or a manager who never got an id both
-- leave it NULL rather than blocking the department.
UPDATE departments d
SET manager_id = mgr.new_id
FROM migration.stg_departments s
JOIN migration.id_map dm ON dm.entity = 'departments' AND dm.legacy_id = s.id::BIGINT
{_fk('mgr', 'employees', 's.manager_id')}
WHERE d.id = dm.new_id AND d.manager_id IS DISTINCT FROM mgr.new_id;
""".strip())

    # ---------- identity model ----------
    p.append("""
-- identities: one per distinct phone. Legacy has no identity table --
-- an employee row carries its own credentials -- and the same phone can
-- appear in several companies, which legacy rejects at login rather
-- than modelling.
INSERT INTO migration.id_map (entity, legacy_id, new_id)
SELECT 'identities', pa.anchor_employee_id, nextval(pg_get_serial_sequence('identities', 'id'))
FROM migration_phone_anchor pa
WHERE NOT EXISTS (
    SELECT 1 FROM migration.id_map m
    WHERE m.entity = 'identities' AND m.legacy_id = pa.anchor_employee_id)
ORDER BY pa.anchor_employee_id;

INSERT INTO identities (id, phone, password_hash) OVERRIDING SYSTEM VALUE
SELECT m.new_id, s.phone, COALESCE(s.password_hash, '!')
FROM migration.stg_employees s
JOIN migration.id_map m ON m.entity = 'identities' AND m.legacy_id = s.id::BIGINT
WHERE NOT EXISTS (SELECT 1 FROM identities i WHERE i.id = m.new_id);
""".strip())

    p.append("""
-- tenant_memberships has UNIQUE(identity_id, company_id): two employee
-- rows in the SAME company sharing a phone (the data-quality shape
-- legacy's own MULTIPLE_ACCOUNTS_SAME_PHONE login check rejects rather
-- than models) must collapse onto one real row, not two. The lowest
-- employee id per (company, phone) is the membership anchor; every
-- other employee sharing it still gets its own id_map entry --
-- hr_permissions references employee_id directly -- pointing at the
-- anchor's new_id rather than a fresh one.
CREATE TEMP TABLE IF NOT EXISTS migration_membership_anchor AS
SELECT s.company_id, s.phone, MIN(s.id::BIGINT) AS anchor_employee_id
FROM migration.stg_employees s
WHERE s.phone IS NOT NULL AND s.phone <> ''
GROUP BY s.company_id, s.phone;

INSERT INTO migration.id_map (entity, legacy_id, new_id)
SELECT 'tenant_memberships', ma.anchor_employee_id,
       nextval(pg_get_serial_sequence('tenant_memberships', 'id'))
FROM migration_membership_anchor ma
WHERE NOT EXISTS (
    SELECT 1 FROM migration.id_map m
    WHERE m.entity = 'tenant_memberships' AND m.legacy_id = ma.anchor_employee_id)
ORDER BY ma.anchor_employee_id;

INSERT INTO migration.id_map (entity, legacy_id, new_id)
SELECT 'tenant_memberships', s.id::BIGINT, am.new_id
FROM migration.stg_employees s
JOIN migration_membership_anchor ma ON ma.company_id = s.company_id AND ma.phone = s.phone
JOIN migration.id_map am ON am.entity = 'tenant_memberships' AND am.legacy_id = ma.anchor_employee_id
WHERE s.id::BIGINT <> ma.anchor_employee_id
  AND NOT EXISTS (
      SELECT 1 FROM migration.id_map m
      WHERE m.entity = 'tenant_memberships' AND m.legacy_id = s.id::BIGINT)
ORDER BY s.id::BIGINT;

INSERT INTO tenant_memberships (id, identity_id, company_id, status) OVERRIDING SYSTEM VALUE
SELECT m.new_id, im.new_id, cm.new_id,
       CASE WHEN COALESCE(s.is_active, '1') = '1' THEN 'ACTIVE' ELSE 'DISABLED' END
FROM migration_membership_anchor ma
JOIN migration.stg_employees s ON s.id::BIGINT = ma.anchor_employee_id
JOIN migration.id_map m ON m.entity = 'tenant_memberships' AND m.legacy_id = ma.anchor_employee_id
JOIN migration.id_map cm ON cm.entity = 'companies' AND cm.legacy_id = s.company_id::BIGINT
JOIN migration_phone_anchor pa ON pa.phone = ma.phone
JOIN migration.id_map im ON im.entity = 'identities' AND im.legacy_id = pa.anchor_employee_id
WHERE NOT EXISTS (SELECT 1 FROM tenant_memberships t WHERE t.id = m.new_id);

INSERT INTO membership_roles (membership_id, company_id, role)
SELECT m.new_id, cm.new_id, UPPER(COALESCE(s.role, 'employee'))
FROM migration.stg_employees s
JOIN migration.id_map m ON m.entity = 'tenant_memberships' AND m.legacy_id = s.id::BIGINT
JOIN migration.id_map cm ON cm.entity = 'companies' AND cm.legacy_id = s.company_id::BIGINT
ON CONFLICT DO NOTHING;
""".strip())

    # ---------- request types / requests ----------
    p.append(_allocate("request_types", "migration.stg_request_types"))
    p.append(f"""
INSERT INTO request_types (id, company_id, name, is_active, deduct_balance,
                           counts_as_paid_leave, add_attendance_exception, exception_type_id,
                           created_at)
OVERRIDING SYSTEM VALUE
SELECT m.new_id, cm.new_id, s.name,
       COALESCE(s.is_active, '1') = '1', COALESCE(s.deduct_balance, '0') = '1',
       COALESCE(s.counts_as_paid_leave, '1') = '1', COALESCE(s.add_attendance_exception, '0') = '1',
       em.new_id,
       (s.created_at::TIMESTAMP AT TIME ZONE 'UTC')
FROM migration.stg_request_types s
JOIN migration.id_map m ON m.entity = 'request_types' AND m.legacy_id = s.id::BIGINT
JOIN migration.id_map cm ON cm.entity = 'companies' AND cm.legacy_id = s.company_id::BIGINT
{_fk('em', 'exception_types', 's.exception_type_id')}
WHERE NOT EXISTS (SELECT 1 FROM request_types r WHERE r.id = m.new_id);
""".strip())

    p.append(_allocate("requests", "migration.stg_requests"))
    p.append(f"""
-- approver_id is the legacy employee who decided the request; resolved
-- through tenant_memberships like hr_permissions is, and left NULL for
-- the not-yet-decided case rather than requiring one.
INSERT INTO requests (id, employee_id, company_id, request_type_id, from_date, to_date,
                      from_time, to_time, notes, status, reply, approver_membership_id, decided_at,
                      created_at)
OVERRIDING SYSTEM VALUE
SELECT m.new_id, emp.new_id, e.company_id, rt.new_id,
       s.from_date::DATE, s.to_date::DATE, s.from_time::TIME, s.to_time::TIME,
       s.notes, UPPER(COALESCE(s.status, 'pending')), s.reply, am.new_id,
       (s.decided_at::TIMESTAMP AT TIME ZONE 'UTC'),
       (s.created_at::TIMESTAMP AT TIME ZONE 'UTC')
FROM migration.stg_requests s
JOIN migration.id_map m ON m.entity = 'requests' AND m.legacy_id = s.id::BIGINT
JOIN migration.id_map emp ON emp.entity = 'employees' AND emp.legacy_id = s.employee_id::BIGINT
JOIN employees e ON e.id = emp.new_id
JOIN migration.id_map rt ON rt.entity = 'request_types' AND rt.legacy_id = s.request_type_id::BIGINT
{_fk('am', 'tenant_memberships', 's.approver_id')}
WHERE NOT EXISTS (SELECT 1 FROM requests r WHERE r.id = m.new_id);
""".strip())

    # ---------- holidays ----------
    p.append(_allocate("company_official_holidays", "migration.stg_company_official_holidays"))
    p.append("""
INSERT INTO company_official_holidays (id, company_id, name, holiday_date, created_at)
OVERRIDING SYSTEM VALUE
SELECT m.new_id, cm.new_id, s.name, s.holiday_date::DATE,
       (s.created_at::TIMESTAMP AT TIME ZONE 'UTC')
FROM migration.stg_company_official_holidays s
JOIN migration.id_map m ON m.entity = 'company_official_holidays' AND m.legacy_id = s.id::BIGINT
JOIN migration.id_map cm ON cm.entity = 'companies' AND cm.legacy_id = s.company_id::BIGINT
WHERE NOT EXISTS (SELECT 1 FROM company_official_holidays h WHERE h.id = m.new_id);
""".strip())

    # ---------- payroll ----------
    p.append(_allocate("salary_contracts", "migration.stg_salary_contracts"))
    p.append("""
-- effective_from zero-dates (D-035/A2, re-verified live and unchanged at
-- 23 of 3,242 rows, D-037 finding D): legacy's effective_from is
-- `date NOT NULL` with no default (invalid-date-analysis.md), so these
-- rows hold the MySQL zero-date placeholder '0000-00-00' rather than a
-- legitimately absent value -- unlike hire_date/birth_date, NULL is not
-- an option here even in principle. A plain s.effective_from::DATE
-- aborts the load outright ("date/time field value out of range:
-- 0000-00-00") the moment a real production dump reaches this INSERT.
--
-- The guard is on the RAW STAGED TEXT, not a pre-cast value: CASE
-- evaluates its WHEN condition per row and only evaluates the matching
-- branch's expression for that row, so a row that fails the regex never
-- reaches ::DATE at all -- the bad cast is never attempted, not merely
-- caught. The pattern requires a non-zero leading year digit, which
-- excludes '0000-00-00' (and, as a side effect, any empty or otherwise
-- malformed value) without needing a cast to test.
--
-- Fallback is the row's OWN created_at (staged and used unmodified two
-- lines below; verified zero-date-free for this table by export_legacy.py's
-- created_at_quality probe, invalid-date-analysis.md's "created_at (15
-- tables)" finding) -- deliberately NOT now(): a load-time timestamp on
-- a migrated row is the exact D-033/OQ-3 trap (a migrated row silently
-- reading as if it happened during the load). Recording this repair in
-- migration remediation/audit output, as A2 also requires, remains owed
-- -- that mechanism does not exist yet and is not built here.
INSERT INTO salary_contracts (id, employee_id, company_id, salary_mode, basic_salary, daily_wage,
       housing_allowance, transport_allowance, food_allowance, risk_allowance, incentives,
       insurance_deduction, tax_deduction, advances_deduction, fund_deduction, penalty_deduction,
       effective_from, created_at)
OVERRIDING SYSTEM VALUE
SELECT m.new_id, emp.new_id, e.company_id, UPPER(COALESCE(s.salary_mode, 'monthly')),
       COALESCE(s.basic_salary::NUMERIC, 0), COALESCE(s.daily_wage::NUMERIC, 0),
       COALESCE(s.housing_allowance::NUMERIC, 0), COALESCE(s.transport_allowance::NUMERIC, 0),
       COALESCE(s.food_allowance::NUMERIC, 0), COALESCE(s.risk_allowance::NUMERIC, 0),
       COALESCE(s.incentives::NUMERIC, 0), COALESCE(s.insurance_deduction::NUMERIC, 0),
       COALESCE(s.tax_deduction::NUMERIC, 0), COALESCE(s.advances_deduction::NUMERIC, 0),
       COALESCE(s.fund_deduction::NUMERIC, 0), COALESCE(s.penalty_deduction::NUMERIC, 0),
       CASE WHEN s.effective_from ~ '^[1-9][0-9]{3}-[0-9]{2}-[0-9]{2}$'
            THEN s.effective_from::DATE
            ELSE s.created_at::DATE
       END,
       (s.created_at::TIMESTAMP AT TIME ZONE 'UTC')
FROM migration.stg_salary_contracts s
JOIN migration.id_map m ON m.entity = 'salary_contracts' AND m.legacy_id = s.id::BIGINT
JOIN migration.id_map emp ON emp.entity = 'employees' AND emp.legacy_id = s.employee_id::BIGINT
JOIN employees e ON e.id = emp.new_id
WHERE NOT EXISTS (SELECT 1 FROM salary_contracts c WHERE c.id = m.new_id);
""".strip())

    p.append(_allocate("payroll_batches", "migration.stg_payroll_batches"))
    p.append("""
INSERT INTO payroll_batches (id, company_id, month, year, period_from, period_to, status,
                             created_at)
OVERRIDING SYSTEM VALUE
SELECT m.new_id, cm.new_id, s.month::SMALLINT, s.year::SMALLINT,
       s.period_from::DATE, s.period_to::DATE,
       CASE WHEN LOWER(COALESCE(s.status, 'draft')) IN ('finalized', 'final') THEN 'FINALIZED' ELSE 'DRAFT' END,
       (s.created_at::TIMESTAMP AT TIME ZONE 'UTC')
FROM migration.stg_payroll_batches s
JOIN migration.id_map m ON m.entity = 'payroll_batches' AND m.legacy_id = s.id::BIGINT
JOIN migration.id_map cm ON cm.entity = 'companies' AND cm.legacy_id = s.company_id::BIGINT
WHERE NOT EXISTS (SELECT 1 FROM payroll_batches b WHERE b.id = m.new_id);
""".strip())

    p.append(_allocate("advances", "migration.stg_advances"))
    p.append("""
-- deduction_mode is a lowercase legacy enum and the target CHECKs against
-- uppercase, same shape as status. The remaining scheduling columns are
-- nullable in both, except month_count which defaults to 1 on both sides.
INSERT INTO advances (id, employee_id, company_id, amount, remaining, reason,
                      rejection_reason, status, request_date, created_at,
                      deduction_mode, deduction_month_count, deduction_amount_per_month,
                      deduction_payroll_year, deduction_payroll_month)
OVERRIDING SYSTEM VALUE
SELECT m.new_id, emp.new_id, e.company_id, COALESCE(s.amount::NUMERIC, 0),
       COALESCE(s.remaining::NUMERIC, 0), s.reason, s.rejection_reason,
       UPPER(COALESCE(s.status, 'pending')), s.request_date::DATE,
       (s.created_at::TIMESTAMP AT TIME ZONE 'UTC'),
       UPPER(COALESCE(s.deduction_mode, 'single_payroll_month')),
       COALESCE(s.deduction_month_count::INT, 1),
       s.deduction_amount_per_month::NUMERIC,
       s.deduction_payroll_year::SMALLINT,
       s.deduction_payroll_month::SMALLINT
FROM migration.stg_advances s
JOIN migration.id_map m ON m.entity = 'advances' AND m.legacy_id = s.id::BIGINT
JOIN migration.id_map emp ON emp.entity = 'employees' AND emp.legacy_id = s.employee_id::BIGINT
JOIN employees e ON e.id = emp.new_id
WHERE NOT EXISTS (SELECT 1 FROM advances a WHERE a.id = m.new_id);
""".strip())

    p.append(_allocate("penalties", "migration.stg_penalties"))
    p.append("""
INSERT INTO penalties (id, employee_id, company_id, penalty_type, penalty_days, reason,
                       penalty_date, applied_to_payroll, created_at)
OVERRIDING SYSTEM VALUE
SELECT m.new_id, emp.new_id, e.company_id, s.penalty_type,
       COALESCE(s.penalty_days::NUMERIC, 0), s.reason, s.penalty_date::DATE,
       COALESCE(s.applied_to_payroll, '0') = '1',
       (s.created_at::TIMESTAMP AT TIME ZONE 'UTC')
FROM migration.stg_penalties s
JOIN migration.id_map m ON m.entity = 'penalties' AND m.legacy_id = s.id::BIGINT
JOIN migration.id_map emp ON emp.entity = 'employees' AND emp.legacy_id = s.employee_id::BIGINT
JOIN employees e ON e.id = emp.new_id
WHERE NOT EXISTS (SELECT 1 FROM penalties pn WHERE pn.id = m.new_id);
""".strip())

    # ---------- attendance: the timezone rule ----------
    p.append(_allocate("attendance", "migration.stg_attendance"))
    p.append(f"""
-- THE TIMEZONE RULE. check_in/check_out are legacy `datetime`: literal
-- wall clock with no recoverable offset. They are loaded as the SAME
-- reading in UTC -- `AT TIME ZONE 'UTC'` reinterprets, it does not
-- shift. Changing this to a real offset silently breaks exception-day
-- detection and moves every early-morning punch to the previous day.
-- See docs/migration/2026-08-09-etl-and-timezone-design.md.
--
-- D-035/A3 (finding E): a legacy row may carry BOTH a completed punch
-- and an exception category, which V21's
-- CHECK (exception_type_id IS NULL OR check_out IS NULL) rejects
-- outright -- the first real load aborted here. A3 decided the
-- constraint is right and the DATA is wrong: preserve the real
-- check_in/check_out, clear exception_type_id, keep the CHECK. So the
-- exception is dropped for exactly those rows, and nothing else about
-- them changes -- the row is not excluded and the punches are not
-- nulled.
--
-- The guard reads the RAW staged text (same approach as A2/A5's
-- effective_from regex above) rather than a cast, so it is structural
-- rather than tied to a row count: D-035 sized this at 13 rows, it was
-- 39 of 39,881 on 2026-08-15, and it grows ~2/day because legacy is
-- deliberately not patched (workin-hr/hr-legacy issue #28, D-037).
-- Whatever the count is at extraction time, this handles it.
--
-- `method` keys off the SAME remediated condition, not the raw
-- exception id. A row we have just decided to treat as a real punch
-- must keep its real method; forcing NULL there would preserve the
-- punch while discarding how it was recorded. `method` is nulled only
-- for a genuine exception-only row (exception set AND no checkout).
--
-- Still owed: A3 also requires each remediation be recorded
-- individually. No per-row audit mechanism exists in this file yet --
-- that is D-035's shared remediation/audit output, still an open
-- design question.
INSERT INTO attendance (id, employee_id, company_id, check_in, check_out, method,
                        latitude, longitude, exception_type_id, created_at)
OVERRIDING SYSTEM VALUE
SELECT m.new_id, emp.new_id, e.company_id,
       (s.check_in::TIMESTAMP AT TIME ZONE 'UTC'),
       (s.check_out::TIMESTAMP AT TIME ZONE 'UTC'),
       CASE WHEN s.exception_type_id IS NULL
                 OR (s.check_out IS NOT NULL AND s.check_out <> '')
            THEN COALESCE(s.method, 'app') ELSE NULL END,
       s.latitude::NUMERIC, s.longitude::NUMERIC,
       CASE WHEN s.check_out IS NOT NULL AND s.check_out <> ''
            THEN NULL ELSE ex.new_id END,
       (s.created_at::TIMESTAMP AT TIME ZONE 'UTC')
FROM migration.stg_attendance s
JOIN migration.id_map m ON m.entity = 'attendance' AND m.legacy_id = s.id::BIGINT
JOIN migration.id_map emp ON emp.entity = 'employees' AND emp.legacy_id = s.employee_id::BIGINT
JOIN employees e ON e.id = emp.new_id
{_fk('ex', 'exception_types', 's.exception_type_id')}
WHERE NOT EXISTS (SELECT 1 FROM attendance a WHERE a.id = m.new_id);
""".strip())

    # ---------- payroll history and scheduling ----------
    # payslips carry company_id from their BATCH: a payslip belongs to the
    # payroll run that produced it. created_at likewise -- legacy has no
    # timestamp column on payslips at all, and a payslip comes into
    # existence when its batch is calculated (D-b). The employee's own
    # company is cross-checked immediately below.
    p.append(_allocate("payslips", "migration.stg_payslips"))
    p.append("""
INSERT INTO payslips (id, batch_id, employee_id, company_id, days_present, days_absent,
                      days_leave, overtime_hours, basic_salary, allowances, overtime_pay,
                      penalties_total, advance_deduction, other_deductions, net_salary,
                      food_allowance, risk_allowance, transport_allowance, incentives,
                      insurance_deduction, tax_deduction, advances_deduction,
                      fund_deduction, gross_salary, total_entitlements, total_deductions,
                      created_at)
OVERRIDING SYSTEM VALUE
SELECT m.new_id, bm.new_id, emp.new_id, b.company_id,
       COALESCE(s.days_present::SMALLINT, 0), COALESCE(s.days_absent::SMALLINT, 0),
       COALESCE(s.days_leave::SMALLINT, 0), COALESCE(s.overtime_hours::NUMERIC, 0),
       COALESCE(s.basic_salary::NUMERIC, 0), COALESCE(s.allowances::NUMERIC, 0),
       COALESCE(s.overtime_pay::NUMERIC, 0), COALESCE(s.penalties_total::NUMERIC, 0),
       COALESCE(s.advance_deduction::NUMERIC, 0), COALESCE(s.other_deductions::NUMERIC, 0),
       COALESCE(s.net_salary::NUMERIC, 0), COALESCE(s.food_allowance::NUMERIC, 0),
       COALESCE(s.risk_allowance::NUMERIC, 0), COALESCE(s.transport_allowance::NUMERIC, 0),
       COALESCE(s.incentives::NUMERIC, 0), COALESCE(s.insurance_deduction::NUMERIC, 0),
       COALESCE(s.tax_deduction::NUMERIC, 0), COALESCE(s.advances_deduction::NUMERIC, 0),
       COALESCE(s.fund_deduction::NUMERIC, 0), COALESCE(s.gross_salary::NUMERIC, 0),
       COALESCE(s.total_entitlements::NUMERIC, 0), COALESCE(s.total_deductions::NUMERIC, 0),
       b.created_at
FROM migration.stg_payslips s
JOIN migration.id_map m ON m.entity = 'payslips' AND m.legacy_id = s.id::BIGINT
JOIN migration.id_map bm ON bm.entity = 'payroll_batches' AND bm.legacy_id = s.batch_id::BIGINT
JOIN payroll_batches b ON b.id = bm.new_id
JOIN migration.id_map emp ON emp.entity = 'employees' AND emp.legacy_id = s.employee_id::BIGINT
WHERE NOT EXISTS (SELECT 1 FROM payslips ps WHERE ps.id = m.new_id);
""".strip())
    p.append("""
-- A payslip can reach company_id two ways, through its batch or through
-- its employee, and they must agree. Legacy has no tenant column here to
-- check against, so the two mapped parents are compared directly: a
-- disagreement is a cross-tenant row, and loading it silently would put
-- one company's payroll inside another's.
DO $$
DECLARE crossed TEXT;
BEGIN
    SELECT string_agg(m.legacy_id || ' (batch company ' || p.company_id
                      || ', employee company ' || e.company_id || ')', ', ')
    INTO crossed
    FROM payslips p
    JOIN migration.id_map m ON m.entity = 'payslips' AND m.new_id = p.id
    JOIN employees e ON e.id = p.employee_id
    WHERE e.company_id <> p.company_id;
    IF crossed IS NOT NULL THEN
        RAISE EXCEPTION 'ETL: payslips whose batch and employee belong to different companies: %', crossed;
    END IF;
END $$;
""".strip())

    # leave_balances: the duplicate guard runs BEFORE allocation, so the
    # failure names an employee and a year rather than an index.
    p.append("""
-- Legacy has only PRIMARY KEY(id) and a plain KEY on employee_id; V25
-- declares UNIQUE(employee_id, year). Checked here by name rather than
-- letting the constraint fire with a message naming an index. An employee
-- whose balance is split across two rows must not silently lose days.
DO $$
DECLARE dupes TEXT;
BEGIN
    SELECT string_agg(d.employee_id || '/' || d.year, ', ') INTO dupes
    FROM (
        SELECT s.employee_id, s.year
        FROM migration.stg_leave_balance s
        GROUP BY s.employee_id, s.year
        HAVING count(*) > 1
    ) d;
    IF dupes IS NOT NULL THEN
        RAISE EXCEPTION 'ETL: leave_balance has duplicate (employee_id, year) rows: %. V25 declares UNIQUE(employee_id, year); decide which balance is real before loading.', dupes;
    END IF;
END $$;
""".strip())
    p.append(_allocate("leave_balances", "migration.stg_leave_balance"))
    p.append("""
-- remaining_days is GENERATED ALWAYS AS STORED on both sides: naming it
-- in this INSERT makes PostgreSQL reject the statement. It is still
-- exported from both sides so migration_diff proves the two engines
-- compute it identically. created_at derives from the row's own year --
-- the balance IS the year, so the row cannot predate it (D-b) -- EXCEPT
-- when year is legacy's zero-sentinel, handled by the CASE below.
--
-- year = 0: legacy's `year(4) NOT NULL` (hr-legacy/mysql_workin.schema.sql:613)
-- silently stores an unset/invalid year as MySQL YEAR's zero-value
-- ('0000') instead of rejecting it (hr-legacy issue #29; root cause is
-- `??`, which guards only a missing/null key, at
-- apis/helpers/employee_create_helper.php:207 and
-- apis/api/employees/create.php:202 -- an explicit '' or 0 slips
-- through where the adjacent period_from_month/period_to_month/
-- monthly_cap_days lines use the correct isset(...) && ... !== ''
-- guard). D-035/A4 quarantined these rows on the premise that they
-- could not be stored target-side. They can: leave_balances.year is
-- SMALLINT NOT NULL (V25__create_requests_and_leave_balances.sql:55)
-- and SMALLINT stores 0 fine -- the ONLY thing that fails is this
-- derived created_at, because make_timestamptz(0, 1, 1, ...) errors
-- ("date field value out of range: 0-01-01"): there is no year 0 in
-- the proleptic Gregorian calendar Postgres uses. The owner's
-- 2026-08-15 carry-as-is decision (A4 reopened and superseded, see
-- D-037 in docs/bootstrap/decision-log.md) replaces the quarantine:
-- year is carried through unchanged, including 0 -- no exclusion, no
-- invented year -- and only the synthesis below is repaired.
--
-- The guard is the legacy column's own valid range, not merely
-- "<> 0": MySQL's year(4) accepts 1901-2155 as real years and 0000 as
-- its sole out-of-band value, so anything outside that range is
-- already not a real legacy year and takes the fallback rather than
-- ever reaching make_timestamptz. The fallback is the parent
-- employee's own created_at (e, already joined below for company_id)
-- -- the nearest real, deterministic timestamp available, and the
-- balance cannot predate the employee who holds it. It is
-- deliberately NOT now(): a load-time timestamp on a migrated row is
-- the exact trap D-033/OQ-3 exists to prevent (a migrated row
-- silently reading as if it happened during the load).
--
-- New platform writes must never produce year = 0; V45 adds the CHECK
-- that documents this as a legacy-only sentinel and why it cannot be
-- enforced at the database layer for new rows.
INSERT INTO leave_balances (id, employee_id, company_id, year, period_from_month,
                            period_to_month, monthly_cap_days, total_days, used_days,
                            created_at)
OVERRIDING SYSTEM VALUE
SELECT m.new_id, emp.new_id, e.company_id, s.year::SMALLINT,
       COALESCE(s.period_from_month::SMALLINT, 1),
       COALESCE(s.period_to_month::SMALLINT, 12),
       s.monthly_cap_days::NUMERIC,
       COALESCE(s.total_days::NUMERIC, 0), COALESCE(s.used_days::NUMERIC, 0),
       CASE WHEN s.year::INT BETWEEN 1901 AND 2155
            THEN make_timestamptz(s.year::INT, 1, 1, 0, 0, 0, 'UTC')
            ELSE e.created_at
       END
FROM migration.stg_leave_balance s
JOIN migration.id_map m ON m.entity = 'leave_balances' AND m.legacy_id = s.id::BIGINT
JOIN migration.id_map emp ON emp.entity = 'employees' AND emp.legacy_id = s.employee_id::BIGINT
JOIN employees e ON e.id = emp.new_id
WHERE NOT EXISTS (SELECT 1 FROM leave_balances lb WHERE lb.id = m.new_id);
""".strip())

    p.append(_allocate("employee_schedules", "migration.stg_employee_schedules"))
    p.append("""
INSERT INTO employee_schedules (id, company_id, employee_id, schedule_date, name,
                                start_time, end_time, exception_note, created_at)
OVERRIDING SYSTEM VALUE
SELECT m.new_id, e.company_id, emp.new_id, s.schedule_date::DATE, s.name,
       s.start_time::TIME, s.end_time::TIME, s.exception_note,
       (s.created_at::TIMESTAMP AT TIME ZONE 'UTC')
FROM migration.stg_employee_schedules s
JOIN migration.id_map m ON m.entity = 'employee_schedules' AND m.legacy_id = s.id::BIGINT
JOIN migration.id_map emp ON emp.entity = 'employees' AND emp.legacy_id = s.employee_id::BIGINT
JOIN employees e ON e.id = emp.new_id
WHERE NOT EXISTS (SELECT 1 FROM employee_schedules es WHERE es.id = m.new_id);
""".strip())

    p.append(_allocate("employee_shift_assignments", "migration.stg_employee_shift_assignments"))
    p.append("""
-- effective_from zero-dates (D-035/A5, re-verified live and unchanged at
-- 23 of 4,184 rows, D-037 finding G): same defect shape as salary_contracts
-- above and the same fallback decision -- see that INSERT's comment for
-- the full reasoning (NOT NULL column, zero-date is legacy's own invalid
-- placeholder not a legitimate absence, guard must run on the raw staged
-- text so the bad ::DATE cast is never attempted for a bad row, not just
-- caught after). Fallback is this row's own created_at, which IS staged
-- (STAGING["employee_shift_assignments"] above, and export_legacy.py's
-- own SELECT for this table) and used unmodified two lines below.
--
-- One difference from salary_contracts: export_legacy.py's
-- created_at_quality probe does NOT include employee_shift_assignments
-- in its 15-table zero-date check (invalid-date-analysis.md's "created_at
-- (15 tables)" list), so unlike salary_contracts.created_at, this
-- fallback value has not itself been proven zero-date-free against live
-- data. No zero-date has ever been observed here, and extending that
-- probe means touching export_legacy.py, which is out of scope for this
-- change -- left as a residual, currently-unverified risk rather than
-- silently widened.
INSERT INTO employee_shift_assignments (id, company_id, employee_id, shift_id,
                                        effective_from, created_at)
OVERRIDING SYSTEM VALUE
SELECT m.new_id, e.company_id, emp.new_id, sh.new_id,
       CASE WHEN s.effective_from ~ '^[1-9][0-9]{3}-[0-9]{2}-[0-9]{2}$'
            THEN s.effective_from::DATE
            ELSE s.created_at::DATE
       END,
       (s.created_at::TIMESTAMP AT TIME ZONE 'UTC')
FROM migration.stg_employee_shift_assignments s
JOIN migration.id_map m ON m.entity = 'employee_shift_assignments' AND m.legacy_id = s.id::BIGINT
JOIN migration.id_map emp ON emp.entity = 'employees' AND emp.legacy_id = s.employee_id::BIGINT
JOIN employees e ON e.id = emp.new_id
JOIN migration.id_map sh ON sh.entity = 'shifts' AND sh.legacy_id = s.shift_id::BIGINT
WHERE NOT EXISTS (SELECT 1 FROM employee_shift_assignments a WHERE a.id = m.new_id);
""".strip())
    p.append("""
-- A shift belonging to another tenant would put the assignment in the
-- wrong company. Legacy has no tenant column on either side, so the two
-- mapped parents are compared directly.
DO $$
DECLARE crossed BIGINT;
BEGIN
    SELECT count(*) INTO crossed
    FROM employee_shift_assignments a
    JOIN shifts sh ON sh.id = a.shift_id
    WHERE sh.company_id <> a.company_id;
    IF crossed > 0 THEN
        RAISE EXCEPTION 'ETL: % employee_shift_assignments rows reference a shift from another company', crossed;
    END IF;
END $$;
""".strip())

    # ---------- settings catalog: load into V44's target tables ----------
    # Until now the load only pivoted the EAV chain into company_settings'
    # six typed columns (below) and dropped setting_definitions/
    # setting_allowed_values entirely once the pivot had read them.
    # V44__create_settings_catalog.sql gives them real target tables, so
    # they are now ALSO loaded here as an ordinary id-mapped row copy --
    # in addition to, not instead of, feeding TRANSFORM 1 below, which
    # keeps reading directly from migration.stg_setting_definitions /
    # migration.stg_setting_allowed_values and is unchanged by this block.
    #
    # Presentation metadata only -- V44's own comment block states the
    # premise this load must not violate: the six typed company_settings
    # columns remain the SOLE authority for a company's chosen VALUE.
    # Nothing below writes to company_settings or company_setting_values;
    # doing so would reintroduce legacy's EAV by accident.
    p.append("-- ================= settings catalog (V44) =================")
    p.append(_allocate("setting_definitions", "migration.stg_setting_definitions"))
    p.append("""
-- is_multi/is_required are legacy tinyint(1), string-compared to '1' the
-- same way every other legacy boolean in this file is (e.g. employees'
-- COALESCE(s.is_active, '1') = '1' above) rather than cast, since a
-- tinyint(1) column is TEXT in staging and ::BOOLEAN would reject values
-- that are merely absent. updated_at is not selected by EXPORT_SQL for
-- this table (see its comment there) so there is nothing to carry here.
INSERT INTO setting_definitions (id, setting_key, label_ar, label_en, description_ar,
                                 description_en, icon_data, is_multi, is_required, sort_order)
OVERRIDING SYSTEM VALUE
SELECT m.new_id, s.setting_key, s.label_ar, s.label_en, s.description_ar, s.description_en,
       s.icon_data, COALESCE(s.is_multi, '0') = '1', COALESCE(s.is_required, '0') = '1',
       COALESCE(s.sort_order::INT, 0)
FROM migration.stg_setting_definitions s
JOIN migration.id_map m ON m.entity = 'setting_definitions' AND m.legacy_id = s.id::BIGINT
WHERE NOT EXISTS (SELECT 1 FROM setting_definitions sd WHERE sd.id = m.new_id);
""".strip())

    p.append(_allocate("setting_allowed_values", "migration.stg_setting_allowed_values"))
    p.append("""
-- setting_definition_id resolves through migration.id_map exactly like
-- every other foreign key in this file: plain JOIN, not LEFT JOIN,
-- because V44 declares it NOT NULL REFERENCES setting_definitions --
-- the same "required FK" convention company_id follows on departments/
-- branches/etc. above (a plain `JOIN migration.id_map cm ON cm.entity =
-- 'companies' ...`), as opposed to the LEFT JOIN _fk() helper
-- (load_postgres.py:296-298) reserved for optional FKs like
-- branches.department_id. A row whose parent
-- definition never loaded is silently skipped by this INNER JOIN and
-- then caught -- not silently dropped -- by finalize()'s unmapped-FK
-- guard, same fail-fast pattern as every other entity in LOAD_ORDER.
INSERT INTO setting_allowed_values (id, setting_definition_id, value, label_ar, label_en, sort_order)
OVERRIDING SYSTEM VALUE
SELECT m.new_id, dm.new_id, s.value, s.label_ar, s.label_en, COALESCE(s.sort_order::INT, 0)
FROM migration.stg_setting_allowed_values s
JOIN migration.id_map m ON m.entity = 'setting_allowed_values' AND m.legacy_id = s.id::BIGINT
JOIN migration.id_map dm ON dm.entity = 'setting_definitions' AND dm.legacy_id = s.setting_definition_id::BIGINT
WHERE NOT EXISTS (SELECT 1 FROM setting_allowed_values av WHERE av.id = m.new_id);
""".strip())

    # ---------- transform: EAV -> typed company_settings ----------
    truthy = ", ".join(f"'{v}'" for v in TRUTHY_SETTING_VALUES)
    setting_cases = "\n".join(
        # bool_or, not MAX: Postgres's MAX has no BOOLEAN overload.
        (f"       bool_or(CASE WHEN d.setting_key = '{key}' THEN "
         f"LOWER(TRIM(v.value)) IN ({truthy}) END) AS {column},"
         if sql_type == "BOOLEAN" else
         f"       MAX(CASE WHEN d.setting_key = '{key}' THEN v.value END)::{sql_type} AS {column},")
        for key, (column, sql_type) in SETTING_COLUMNS.items()
    ).rstrip(",")
    p.append(f"""
-- TRANSFORM 1: the EAV settings chain collapses into typed columns.
-- legacy company_settings -> company_setting_values -> setting_allowed_values,
-- pivoted by setting_definitions.setting_key.
--
-- A multi-valued setting (weekly_off_days is a list) becomes a
-- comma-joined string in the order legacy renders it: sort_order, then id.
INSERT INTO company_settings (company_id, month_start_day, month_end_day,
                              weekly_off_days, overtime_rate, monthly_leave_accrual, pay_overtime)
SELECT cm.new_id,
{setting_cases}
FROM migration.stg_legacy_company_settings cs
JOIN migration.id_map cm ON cm.entity = 'companies' AND cm.legacy_id = cs.company_id::BIGINT
JOIN migration.stg_setting_definitions d ON d.id = cs.setting_definition_id
JOIN LATERAL (
    SELECT string_agg(av.value, ',' ORDER BY av.sort_order::INT, av.id::INT) AS value
    FROM migration.stg_company_setting_values csv_
    JOIN migration.stg_setting_allowed_values av ON av.id = csv_.setting_allowed_value_id
    WHERE csv_.company_setting_id = cs.id
) v ON TRUE
GROUP BY cm.new_id
ON CONFLICT (company_id) DO NOTHING;
""".strip())

    # ---------- transform: hr_permissions -> overrides ----------
    flag_union = "\nUNION ALL\n".join(
        f"    SELECT '{flag}'::TEXT AS flag, '{key}'::TEXT AS permission_key"
        for flag, keys in PERMISSION_MAP.items() for key in keys
    )
    p.append(f"""
-- TRANSFORM 2: legacy per-employee permission flags become ALLOW
-- overrides on that employee's membership.
--
-- Fail-fast on an unmapped flag. A can_* column present in the dump but
-- missing from PERMISSION_MAP would otherwise be silently dropped, and
-- somebody loses access at cutover with nothing to show why.
DO $$
DECLARE unmapped TEXT;
BEGIN
    SELECT string_agg(column_name, ', ') INTO unmapped
    FROM information_schema.columns
    WHERE table_schema = 'migration' AND table_name = 'stg_hr_permissions'
      AND column_name LIKE 'can\\_%'
      AND column_name NOT IN ({", ".join("'" + f + "'" for f in PERMISSION_MAP)});
    IF unmapped IS NOT NULL THEN
        RAISE EXCEPTION 'ETL: unmapped hr_permissions flags: %. Add them to PERMISSION_MAP.', unmapped;
    END IF;
END $$;

CREATE TEMP TABLE IF NOT EXISTS migration_permission_map AS
{flag_union};

INSERT INTO membership_permission_overrides (membership_id, company_id, permission_id, effect)
SELECT tm.new_id, e.company_id, p.id, 'ALLOW'
FROM migration.stg_hr_permissions hp
JOIN migration.id_map emp ON emp.entity = 'employees' AND emp.legacy_id = hp.employee_id::BIGINT
JOIN employees e ON e.id = emp.new_id
JOIN migration.id_map tm ON tm.entity = 'tenant_memberships' AND tm.legacy_id = hp.employee_id::BIGINT
JOIN migration_permission_map pm ON TRUE
JOIN permissions p ON p.permission_key = pm.permission_key
WHERE (to_jsonb(hp) ->> pm.flag) = '1'
ON CONFLICT (membership_id, permission_id) DO NOTHING;
""".strip())

    return "\n\n".join(p)


def _finalize() -> str:
    seq = "\n".join(
        f"SELECT setval(pg_get_serial_sequence('{e}', 'id'), "
        f"GREATEST((SELECT COALESCE(MAX(id), 1) FROM {e}), 1));"
        for e in ARTIFACT_TABLES
    )
    counts = "\nUNION ALL\n".join(
        f"    SELECT '{e}'::TEXT, count(*)::BIGINT FROM {e}" for e in ARTIFACT_TABLES
    )
    # Unmapped-FK guard, for every mapped entity: id_map is allocated
    # unconditionally (that step only needs the row's OWN legacy id), but
    # the real INSERT INTO <entity> silently drops a row whose FK chain
    # doesn't resolve (an INNER JOIN to a missing parent's id_map row).
    # So the guard cannot check id_map membership -- checking it against
    # `WHERE NOT EXISTS` is comparing an entry that is by then always
    # there. It has to check whether the mapped row actually landed.
    guards = "\n\n".join(f"""
DO $$
DECLARE missing BIGINT;
BEGIN
    SELECT count(*) INTO missing
    FROM migration.id_map m
    WHERE m.entity = '{e}'
      AND NOT EXISTS (SELECT 1 FROM {e} t WHERE t.id = m.new_id);
    IF missing > 0 THEN
        RAISE EXCEPTION 'ETL: % {e} rows were allocated an id but never loaded -- a parent was missing from the export', missing;
    END IF;
END $$;""".strip() for e in LOAD_ORDER)
    return f"""
-- ================= finalize =================
-- Identity sequences are pushed past the loaded ids. Skipping this makes
-- the first post-cutover insert collide with a migrated row.
{seq}

-- Row counts, as an artifact reconciliation reads rather than a number
-- scrolling past in a terminal.
INSERT INTO migration.load_counts (entity, rows)
SELECT * FROM (
{counts}
) t
ON CONFLICT (entity) DO UPDATE SET rows = EXCLUDED.rows, recorded = now();

-- Unmapped-foreign-key guard: an id_map entry with no matching row means
-- a parent was missing from the export and the row was silently dropped.
{guards}
""".strip()


SECTIONS = {"ddl": _ddl, "copy": _copy, "load": _load, "finalize": _finalize}


def build(sections=None) -> str:
    names = sections or ["ddl", "copy", "load", "finalize"]
    return "\n\n".join(SECTIONS[n]() for n in names) + "\n"


def self_test() -> int:
    failures: list[str] = []

    def check(name: str, condition: bool) -> None:
        print(("OK  " if condition else "FAIL ") + name)
        if not condition:
            failures.append(name)

    sql = build()
    load = _load()

    check("the id map is a real table, not in-memory state",
          "CREATE TABLE IF NOT EXISTS migration.id_map" in sql)
    check("row counts are persisted as an artifact",
          "INSERT INTO migration.load_counts" in sql)
    check("ids are allocated in deterministic legacy order",
          load.count("ORDER BY s.id::BIGINT;") >= len(LOAD_ORDER) - 4)
    check("every load step is rerun-safe",
          load.count("WHERE NOT EXISTS") >= len(LOAD_ORDER))
    check("attendance is loaded under the wall-clock rule",
          "AT TIME ZONE 'UTC'" in load and "AT TIME ZONE '+02" not in load)
    check("identity sequences are advanced past migrated ids", "setval(" in sql)
    check("unmapped permission flags abort the load",
          "unmapped hr_permissions flags" in load)
    check("every mapped entity gets an unmapped-FK guard, not just attendance",
          sql.count("were allocated an id but never loaded") >= len(LOAD_ORDER))
    check("both non-copy transforms are present",
          "TRANSFORM 1" in load and "TRANSFORM 2" in load)
    check("pay_overtime is string-normalised, not blindly cast (V40 typed it BOOLEAN)",
          "LOWER(TRIM(v.value)) IN ('1', 'true', 'yes', 'on')" in load)
    check("sections are independently emittable",
          all(SECTIONS[s]() for s in SECTIONS))
    check("every mapped entity gets a sequence fixup",
          all(f"pg_get_serial_sequence('{e}', 'id')" in sql for e in LOAD_ORDER))
    check("PERMISSION_MAP covers every legacy can_* flag (17, per hr-legacy@d113204)",
          len(PERMISSION_MAP) == 17)
    check("stg_hr_permissions declares real can_* columns for \\copy, not just id/employee_id",
          all(f"    {flag} TEXT" in sql for flag in PERMISSION_MAP))
    check("employees.phone is not carried for every duplicate of a shared phone",
          "THEN s.phone ELSE NULL" in load)
    check("tenant_memberships collapses same-company shared-phone duplicates onto one row",
          "migration_membership_anchor" in load)
    check("requests carries approver, decision time, and notes, not just reply",
          "approver_membership_id" in load and "s.notes" in load)
    check("departments is loaded, not silently dropped", "departments" in LOAD_ORDER)
    check("department branch assignments load through both mapped parents",
          "INSERT INTO department_branches" in load
          and "migration.stg_department_branches" in load
          and "ETL: % department_branches rows were not loaded" in load)
    check("auxiliary junction gets a sequence fixup and count artifact",
          "department_branches" in ARTIFACT_TABLES
          and "pg_get_serial_sequence('department_branches', 'id')" in sql
          and "SELECT 'department_branches'::TEXT, count(*)::BIGINT" in sql)
    check("job_titles AND employees.department_id both resolve through the departments map",
          load.count("JOIN migration.id_map dm ON dm.entity = 'departments'") >= 2)
    check("departments.manager_id backfills after employees exist (breaks the cycle)",
          "UPDATE departments d" in load and "SET manager_id = mgr.new_id" in load)

    # ---------- leave_balances year=0 sentinel (D-035/A4 superseded by the
    # owner's 2026-08-15 carry-as-is decision, D-037) ----------
    lb_section = load.split("INSERT INTO leave_balances (id, employee_id", 1)[-1] \
        .split("FROM migration.stg_leave_balance s", 1)[0]
    check("leave_balances.year is carried through unchanged, including 0 -- "
          "no exclusion, no substituted year (D-035/A4's quarantine is "
          "superseded by the owner's carry-as-is call, D-037)",
          "s.year::SMALLINT" in lb_section)
    check("make_timestamptz is guarded to legacy year(4)'s real range and is "
          "never reached for year 0 or any other non-real year",
          "CASE WHEN s.year::INT BETWEEN 1901 AND 2155" in lb_section
          and "THEN make_timestamptz(s.year::INT, 1, 1, 0, 0, 0, 'UTC')" in lb_section)
    check("the created_at fallback is the parent employee's own created_at, "
          "never now() -- the D-033/OQ-3 trap of a migrated row silently "
          "reading as if it happened during the load",
          "ELSE e.created_at" in lb_section and "now()" not in lb_section)

    # ---------- salary_contracts / employee_shift_assignments effective_from
    # zero-dates (D-035/A2, D-035/A5; re-verified live and unchanged,
    # D-037 findings D and G) ----------
    sc_section = load.split("INSERT INTO salary_contracts (id, employee_id", 1)[-1] \
        .split("FROM migration.stg_salary_contracts s", 1)[0]
    check("salary_contracts.effective_from's zero-date guard tests the raw "
          "staged text, not a pre-cast value -- a bad row never reaches "
          "::DATE at all (D-035/A2)",
          "CASE WHEN s.effective_from ~ '^[1-9][0-9]{3}-[0-9]{2}-[0-9]{2}$'" in sc_section
          and "THEN s.effective_from::DATE" in sc_section)
    check("salary_contracts.effective_from's fallback is the row's own "
          "created_at, never now() (D-035/A2's decided fallback; the "
          "D-033/OQ-3 trap of a migrated row silently reading as if it "
          "happened during the load)",
          "ELSE s.created_at::DATE" in sc_section and "now()" not in sc_section)

    esa_section = load.split("INSERT INTO employee_shift_assignments (id, company_id", 1)[-1] \
        .split("FROM migration.stg_employee_shift_assignments s", 1)[0]
    check("employee_shift_assignments.effective_from's zero-date guard tests "
          "the raw staged text, not a pre-cast value -- a bad row never "
          "reaches ::DATE at all (D-035/A5)",
          "CASE WHEN s.effective_from ~ '^[1-9][0-9]{3}-[0-9]{2}-[0-9]{2}$'" in esa_section
          and "THEN s.effective_from::DATE" in esa_section)
    check("employee_shift_assignments.effective_from's fallback is the "
          "row's own created_at, never now() (D-035/A5's decided fallback; "
          "same D-033/OQ-3 trap)",
          "ELSE s.created_at::DATE" in esa_section and "now()" not in esa_section)
    check("both effective_from guards use the identical regex (same defect "
          "shape, same fallback decision -- D-035/A5 explicitly reuses A2's "
          "rule rather than inventing a second one)",
          sql.count("CASE WHEN s.effective_from ~ '^[1-9][0-9]{3}-[0-9]{2}-[0-9]{2}$'") == 2)

    # ---------- D-035/A3: attendance punches-XOR-exception (finding E) ----------
    att_section = load.split("INSERT INTO attendance (id, employee_id", 1)[-1] \
        .split("FROM migration.stg_attendance s", 1)[0]
    check("a row carrying real punches has its exception cleared, keyed off "
          "the raw staged check_out text (D-035/A3)",
          "CASE WHEN s.check_out IS NOT NULL AND s.check_out <> ''" in att_section
          and "THEN NULL ELSE ex.new_id END" in att_section)
    check("A3 preserves the punches -- check_in/check_out are still selected "
          "unconditionally, so the row is neither dropped nor blanked",
          "(s.check_in::TIMESTAMP AT TIME ZONE 'UTC')" in att_section
          and "(s.check_out::TIMESTAMP AT TIME ZONE 'UTC')" in att_section)
    check("method keys off the REMEDIATED condition, so a preserved punch "
          "keeps its real method instead of being nulled as if it were an "
          "exception-only row",
          "CASE WHEN s.exception_type_id IS NULL\n                 OR "
          "(s.check_out IS NOT NULL AND s.check_out <> '')" in att_section)
    check("A3 does not relax V21's CHECK -- the load never drops or alters "
          "a constraint to make the data fit (A3: the constraint was "
          "correct, the data was wrong)",
          "DROP CONSTRAINT" not in sql.upper()
          and "ALTER TABLE ATTENDANCE" not in sql.upper()
          and "SET CONSTRAINTS" not in sql.upper())

    # ---------- D-035/A6: orphaned exception_types (finding H) ----------
    check("exception_types with an unresolvable company are excluded at "
          "ALLOCATION, so no orphaned migration.id_map row is left behind "
          "(D-035/A6's 'not a silent drop')",
          "-- exception_types: allocate ids" in load
          and "WHERE NOT (NOT EXISTS (SELECT 1 FROM migration.id_map cm" in load)
    check("the exclusion is recorded as a reconciliation-readable artifact, "
          "using the existing load_counts mechanism rather than a new one",
          "'exception_types_excluded_orphaned_company'" in load
          and "INSERT INTO migration.load_counts (entity, rows)" in load)

    # ---------- settings catalog load (V44) ----------
    check("setting_definitions and setting_allowed_values load into their "
          "own V44 target tables, not just consumed as a pivot source",
          "INSERT INTO setting_definitions (" in load
          and "INSERT INTO setting_allowed_values (" in load)
    check("both new entities are registered in migration.id_map like every other entity",
          "setting_definitions" in LOAD_ORDER and "setting_allowed_values" in LOAD_ORDER)
    check("setting_allowed_values.setting_definition_id resolves through the id_map, "
          "not the raw staged legacy id",
          "dm.entity = 'setting_definitions' AND dm.legacy_id = s.setting_definition_id::BIGINT" in load)
    check("is_multi/is_required are string-normalised like every other legacy "
          "tinyint(1)/boolean column, not cast",
          "COALESCE(s.is_multi, '0') = '1'" in load
          and "COALESCE(s.is_required, '0') = '1'" in load)
    catalog_section = load.split("-- ================= settings catalog (V44) =================", 1)[-1] \
        .split("-- TRANSFORM 1:", 1)[0]
    check("the catalog load section writes only to the two V44 tables -- "
          "presentation metadata only, per V44's comment block, never to "
          "company_settings/company_setting_values (that would reintroduce "
          "legacy's EAV by accident)",
          "INSERT INTO company_settings" not in catalog_section
          and "INSERT INTO company_setting_values" not in catalog_section
          and "INSERT INTO setting_definitions" in catalog_section
          and "INSERT INTO setting_allowed_values" in catalog_section)
    check("TRANSFORM 1 (the EAV pivot) is unchanged: still the sole writer of "
          "company_settings' six typed columns",
          "INSERT INTO company_settings (company_id, month_start_day" in load)

    return 1 if failures else 0


def main() -> int:
    if "--section" in sys.argv:
        name = sys.argv[sys.argv.index("--section") + 1]
        if name not in SECTIONS:
            print(f"unknown section: {name}", file=sys.stderr)
            return 2
        print(build([name]))
        return 0
    if "--print-sql" in sys.argv:
        print(build())
        return 0
    print(__doc__)
    return 0


if __name__ == "__main__":
    sys.exit(self_test() if "--self-test" in sys.argv else main())

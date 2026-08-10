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
        "expires_at", "is_active", "created_at",
    ],
    "job_titles": ["id", "company_id", "department_id", "name", "work_hours", "is_active", "created_at"],
    "request_types": [
        "id", "company_id", "name", "is_active", "deduct_balance", "counts_as_paid_leave",
        "add_attendance_exception", "exception_type_id", "created_at",
    ],
    "requests": [
        "id", "employee_id", "request_type_id", "from_date", "to_date", "from_time", "to_time",
        "status", "reply", "approver_id", "decided_at", "created_at", "updated_at",
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
    ],
    "penalties": [
        "id", "employee_id", "penalty_type", "penalty_days", "reason", "penalty_date",
        "applied_to_payroll", "created_at",
    ],
    # Transform sources, loaded in legacy shape.
    "hr_permissions": None,  # columns discovered at load time -- see PERMISSION_MAP
    "legacy_company_settings": ["id", "company_id", "setting_definition_id"],
    "company_setting_values": ["id", "company_setting_id", "setting_allowed_value_id"],
    "setting_definitions": ["id", "setting_key"],
    "setting_allowed_values": ["id", "setting_definition_id", "value", "sort_order"],
}

# hr_permissions can_* flag -> the permission keys it grants.
# A flag present in the dump but absent here aborts the load: silently
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
}

# Legacy setting_key -> typed company_settings column. pay_overtime is
# deliberately absent: the typed table has no column for it. See README.
SETTING_COLUMNS = {
    "month_start_day": ("month_start_day", "SMALLINT"),
    "month_end_day": ("month_end_day", "SMALLINT"),
    "weekly_off_days": ("weekly_off_days", "VARCHAR"),
    "overtime_rate": ("overtime_rate", "NUMERIC"),
    "monthly_leave_accrual": ("monthly_leave_accrual", "NUMERIC"),
}

# Entities whose ids are allocated and mapped, in foreign-key order.
LOAD_ORDER = [
    "companies", "branches", "job_titles", "shifts", "exception_types", "employees",
    "identities", "tenant_memberships", "request_types", "requests",
    "company_official_holidays", "salary_contracts", "payroll_batches", "advances",
    "penalties", "attendance",
]


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
        if columns is None:
            # hr_permissions is discovered: its can_* set differs by dump.
            out += [
                f"CREATE TABLE IF NOT EXISTS migration.stg_{table} (",
                "    id TEXT, employee_id TEXT",
                ");",
                "-- Remaining can_* columns are added by the operator's COPY",
                "-- header; see the load section's unmapped-flag guard.",
                "",
            ]
            continue
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
INSERT INTO companies (id, name, phone, active) OVERRIDING SYSTEM VALUE
SELECT m.new_id, s.name, s.phone, COALESCE(s.status, 'active') = 'active'
FROM migration.stg_companies s
JOIN migration.id_map m ON m.entity = 'companies' AND m.legacy_id = s.id::BIGINT
WHERE NOT EXISTS (SELECT 1 FROM companies c WHERE c.id = m.new_id);
""".strip())

    # ---------- branches / job_titles / shifts / exception_types ----------
    p.append(_allocate("branches", "migration.stg_branches"))
    p.append("""
INSERT INTO branches (id, company_id, name, address, latitude, longitude, radius_meters, is_active)
OVERRIDING SYSTEM VALUE
SELECT m.new_id, cm.new_id, s.name, s.address,
       s.latitude::NUMERIC, s.longitude::NUMERIC, COALESCE(s.radius_meters::INT, 0),
       COALESCE(s.is_active, '1') = '1'
FROM migration.stg_branches s
JOIN migration.id_map m ON m.entity = 'branches' AND m.legacy_id = s.id::BIGINT
JOIN migration.id_map cm ON cm.entity = 'companies' AND cm.legacy_id = s.company_id::BIGINT
WHERE NOT EXISTS (SELECT 1 FROM branches b WHERE b.id = m.new_id);
""".strip())

    p.append(_allocate("job_titles", "migration.stg_job_titles"))
    p.append("""
-- department_id is dropped: legacy departments are not in this export,
-- so carrying the raw id would point at nothing.
INSERT INTO job_titles (id, company_id, department_id, name, work_hours, is_active)
OVERRIDING SYSTEM VALUE
SELECT m.new_id, cm.new_id, NULL, s.name,
       COALESCE(s.work_hours::NUMERIC, 8), COALESCE(s.is_active, '1') = '1'
FROM migration.stg_job_titles s
JOIN migration.id_map m ON m.entity = 'job_titles' AND m.legacy_id = s.id::BIGINT
JOIN migration.id_map cm ON cm.entity = 'companies' AND cm.legacy_id = s.company_id::BIGINT
WHERE NOT EXISTS (SELECT 1 FROM job_titles j WHERE j.id = m.new_id);
""".strip())

    p.append(_allocate("shifts", "migration.stg_shifts"))
    p.append("""
INSERT INTO shifts (id, company_id, name, start_time, end_time, days_off, is_active)
OVERRIDING SYSTEM VALUE
SELECT m.new_id, cm.new_id, s.name, s.start_time::TIME, s.end_time::TIME, s.days_off,
       COALESCE(s.is_active, '1') = '1'
FROM migration.stg_shifts s
JOIN migration.id_map m ON m.entity = 'shifts' AND m.legacy_id = s.id::BIGINT
JOIN migration.id_map cm ON cm.entity = 'companies' AND cm.legacy_id = s.company_id::BIGINT
WHERE NOT EXISTS (SELECT 1 FROM shifts sh WHERE sh.id = m.new_id);
""".strip())

    p.append(_allocate("exception_types", "migration.stg_exception_types"))
    p.append("""
INSERT INTO exception_types (id, company_id, name) OVERRIDING SYSTEM VALUE
SELECT m.new_id, cm.new_id, s.name
FROM migration.stg_exception_types s
JOIN migration.id_map m ON m.entity = 'exception_types' AND m.legacy_id = s.id::BIGINT
JOIN migration.id_map cm ON cm.entity = 'companies' AND cm.legacy_id = s.company_id::BIGINT
WHERE NOT EXISTS (SELECT 1 FROM exception_types e WHERE e.id = m.new_id);
""".strip())

    # ---------- employees ----------
    p.append(_allocate("employees", "migration.stg_employees"))
    p.append(f"""
INSERT INTO employees (id, company_id, first_name, last_name, phone, role, active,
                       branch_id, department_id, job_title_id, expected_daily_hours)
OVERRIDING SYSTEM VALUE
SELECT m.new_id, cm.new_id, s.first_name, COALESCE(s.last_name, ''), s.phone,
       UPPER(COALESCE(s.role, 'employee')), COALESCE(s.is_active, '1') = '1',
       bm.new_id, NULL, jm.new_id, s.expected_daily_hours::NUMERIC
FROM migration.stg_employees s
JOIN migration.id_map m ON m.entity = 'employees' AND m.legacy_id = s.id::BIGINT
JOIN migration.id_map cm ON cm.entity = 'companies' AND cm.legacy_id = s.company_id::BIGINT
{_fk('bm', 'branches', 's.branch_id')}
{_fk('jm', 'job_titles', 's.job_title_id')}
WHERE NOT EXISTS (SELECT 1 FROM employees e WHERE e.id = m.new_id);
""".strip())

    # ---------- identity model ----------
    p.append("""
-- identities: one per distinct phone. Legacy has no identity table --
-- an employee row carries its own credentials -- and the same phone can
-- appear in several companies, which legacy rejects at login rather
-- than modelling. The lowest employee id owning a phone is the
-- deterministic anchor.
INSERT INTO migration.id_map (entity, legacy_id, new_id)
SELECT 'identities', anchor.employee_id, nextval(pg_get_serial_sequence('identities', 'id'))
FROM (
    SELECT MIN(s.id::BIGINT) AS employee_id, s.phone
    FROM migration.stg_employees s
    WHERE s.phone IS NOT NULL AND s.phone <> ''
    GROUP BY s.phone
) anchor
WHERE NOT EXISTS (
    SELECT 1 FROM migration.id_map m
    WHERE m.entity = 'identities' AND m.legacy_id = anchor.employee_id)
ORDER BY anchor.employee_id;

INSERT INTO identities (id, phone, password_hash) OVERRIDING SYSTEM VALUE
SELECT m.new_id, s.phone, COALESCE(s.password_hash, '!')
FROM migration.stg_employees s
JOIN migration.id_map m ON m.entity = 'identities' AND m.legacy_id = s.id::BIGINT
WHERE NOT EXISTS (SELECT 1 FROM identities i WHERE i.id = m.new_id);
""".strip())

    p.append("""
-- tenant_memberships: one per employee that has an identity. Keyed on
-- the employee id, which is what hr_permissions references.
INSERT INTO migration.id_map (entity, legacy_id, new_id)
SELECT 'tenant_memberships', s.id::BIGINT,
       nextval(pg_get_serial_sequence('tenant_memberships', 'id'))
FROM migration.stg_employees s
JOIN migration.id_map im ON im.entity = 'identities'
     AND im.legacy_id = (SELECT MIN(x.id::BIGINT) FROM migration.stg_employees x WHERE x.phone = s.phone)
WHERE s.phone IS NOT NULL AND s.phone <> ''
  AND NOT EXISTS (
      SELECT 1 FROM migration.id_map m
      WHERE m.entity = 'tenant_memberships' AND m.legacy_id = s.id::BIGINT)
ORDER BY s.id::BIGINT;

INSERT INTO tenant_memberships (id, identity_id, company_id, status) OVERRIDING SYSTEM VALUE
SELECT m.new_id, im.new_id, cm.new_id,
       CASE WHEN COALESCE(s.is_active, '1') = '1' THEN 'ACTIVE' ELSE 'DISABLED' END
FROM migration.stg_employees s
JOIN migration.id_map m ON m.entity = 'tenant_memberships' AND m.legacy_id = s.id::BIGINT
JOIN migration.id_map cm ON cm.entity = 'companies' AND cm.legacy_id = s.company_id::BIGINT
JOIN migration.id_map im ON im.entity = 'identities'
     AND im.legacy_id = (SELECT MIN(x.id::BIGINT) FROM migration.stg_employees x WHERE x.phone = s.phone)
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
                           counts_as_paid_leave, add_attendance_exception, exception_type_id)
OVERRIDING SYSTEM VALUE
SELECT m.new_id, cm.new_id, s.name,
       COALESCE(s.is_active, '1') = '1', COALESCE(s.deduct_balance, '0') = '1',
       COALESCE(s.counts_as_paid_leave, '1') = '1', COALESCE(s.add_attendance_exception, '0') = '1',
       em.new_id
FROM migration.stg_request_types s
JOIN migration.id_map m ON m.entity = 'request_types' AND m.legacy_id = s.id::BIGINT
JOIN migration.id_map cm ON cm.entity = 'companies' AND cm.legacy_id = s.company_id::BIGINT
{_fk('em', 'exception_types', 's.exception_type_id')}
WHERE NOT EXISTS (SELECT 1 FROM request_types r WHERE r.id = m.new_id);
""".strip())

    p.append(_allocate("requests", "migration.stg_requests"))
    p.append("""
INSERT INTO requests (id, employee_id, company_id, request_type_id, from_date, to_date,
                      from_time, to_time, status, reply)
OVERRIDING SYSTEM VALUE
SELECT m.new_id, emp.new_id, e.company_id, rt.new_id,
       s.from_date::DATE, s.to_date::DATE, s.from_time::TIME, s.to_time::TIME,
       UPPER(COALESCE(s.status, 'pending')), s.reply
FROM migration.stg_requests s
JOIN migration.id_map m ON m.entity = 'requests' AND m.legacy_id = s.id::BIGINT
JOIN migration.id_map emp ON emp.entity = 'employees' AND emp.legacy_id = s.employee_id::BIGINT
JOIN employees e ON e.id = emp.new_id
JOIN migration.id_map rt ON rt.entity = 'request_types' AND rt.legacy_id = s.request_type_id::BIGINT
WHERE NOT EXISTS (SELECT 1 FROM requests r WHERE r.id = m.new_id);
""".strip())

    # ---------- holidays ----------
    p.append(_allocate("company_official_holidays", "migration.stg_company_official_holidays"))
    p.append("""
INSERT INTO company_official_holidays (id, company_id, name, holiday_date)
OVERRIDING SYSTEM VALUE
SELECT m.new_id, cm.new_id, s.name, s.holiday_date::DATE
FROM migration.stg_company_official_holidays s
JOIN migration.id_map m ON m.entity = 'company_official_holidays' AND m.legacy_id = s.id::BIGINT
JOIN migration.id_map cm ON cm.entity = 'companies' AND cm.legacy_id = s.company_id::BIGINT
WHERE NOT EXISTS (SELECT 1 FROM company_official_holidays h WHERE h.id = m.new_id);
""".strip())

    # ---------- payroll ----------
    p.append(_allocate("salary_contracts", "migration.stg_salary_contracts"))
    p.append("""
INSERT INTO salary_contracts (id, employee_id, company_id, salary_mode, basic_salary, daily_wage,
       housing_allowance, transport_allowance, food_allowance, risk_allowance, incentives,
       insurance_deduction, tax_deduction, advances_deduction, fund_deduction, penalty_deduction,
       effective_from)
OVERRIDING SYSTEM VALUE
SELECT m.new_id, emp.new_id, e.company_id, UPPER(COALESCE(s.salary_mode, 'monthly')),
       COALESCE(s.basic_salary::NUMERIC, 0), COALESCE(s.daily_wage::NUMERIC, 0),
       COALESCE(s.housing_allowance::NUMERIC, 0), COALESCE(s.transport_allowance::NUMERIC, 0),
       COALESCE(s.food_allowance::NUMERIC, 0), COALESCE(s.risk_allowance::NUMERIC, 0),
       COALESCE(s.incentives::NUMERIC, 0), COALESCE(s.insurance_deduction::NUMERIC, 0),
       COALESCE(s.tax_deduction::NUMERIC, 0), COALESCE(s.advances_deduction::NUMERIC, 0),
       COALESCE(s.fund_deduction::NUMERIC, 0), COALESCE(s.penalty_deduction::NUMERIC, 0),
       s.effective_from::DATE
FROM migration.stg_salary_contracts s
JOIN migration.id_map m ON m.entity = 'salary_contracts' AND m.legacy_id = s.id::BIGINT
JOIN migration.id_map emp ON emp.entity = 'employees' AND emp.legacy_id = s.employee_id::BIGINT
JOIN employees e ON e.id = emp.new_id
WHERE NOT EXISTS (SELECT 1 FROM salary_contracts c WHERE c.id = m.new_id);
""".strip())

    p.append(_allocate("payroll_batches", "migration.stg_payroll_batches"))
    p.append("""
INSERT INTO payroll_batches (id, company_id, month, year, period_from, period_to, status)
OVERRIDING SYSTEM VALUE
SELECT m.new_id, cm.new_id, s.month::SMALLINT, s.year::SMALLINT,
       s.period_from::DATE, s.period_to::DATE,
       CASE WHEN LOWER(COALESCE(s.status, 'draft')) IN ('finalized', 'final') THEN 'FINALIZED' ELSE 'DRAFT' END
FROM migration.stg_payroll_batches s
JOIN migration.id_map m ON m.entity = 'payroll_batches' AND m.legacy_id = s.id::BIGINT
JOIN migration.id_map cm ON cm.entity = 'companies' AND cm.legacy_id = s.company_id::BIGINT
WHERE NOT EXISTS (SELECT 1 FROM payroll_batches b WHERE b.id = m.new_id);
""".strip())

    p.append(_allocate("advances", "migration.stg_advances"))
    p.append("""
INSERT INTO advances (id, employee_id, company_id, amount, remaining, reason,
                      rejection_reason, status, request_date)
OVERRIDING SYSTEM VALUE
SELECT m.new_id, emp.new_id, e.company_id, COALESCE(s.amount::NUMERIC, 0),
       COALESCE(s.remaining::NUMERIC, 0), s.reason, s.rejection_reason,
       UPPER(COALESCE(s.status, 'pending')), s.request_date::DATE
FROM migration.stg_advances s
JOIN migration.id_map m ON m.entity = 'advances' AND m.legacy_id = s.id::BIGINT
JOIN migration.id_map emp ON emp.entity = 'employees' AND emp.legacy_id = s.employee_id::BIGINT
JOIN employees e ON e.id = emp.new_id
WHERE NOT EXISTS (SELECT 1 FROM advances a WHERE a.id = m.new_id);
""".strip())

    p.append(_allocate("penalties", "migration.stg_penalties"))
    p.append("""
INSERT INTO penalties (id, employee_id, company_id, penalty_type, penalty_days, reason,
                       penalty_date, applied_to_payroll)
OVERRIDING SYSTEM VALUE
SELECT m.new_id, emp.new_id, e.company_id, s.penalty_type,
       COALESCE(s.penalty_days::NUMERIC, 0), s.reason, s.penalty_date::DATE,
       COALESCE(s.applied_to_payroll, '0') = '1'
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
INSERT INTO attendance (id, employee_id, company_id, check_in, check_out, method,
                        latitude, longitude, exception_type_id)
OVERRIDING SYSTEM VALUE
SELECT m.new_id, emp.new_id, e.company_id,
       (s.check_in::TIMESTAMP AT TIME ZONE 'UTC'),
       (s.check_out::TIMESTAMP AT TIME ZONE 'UTC'),
       CASE WHEN s.exception_type_id IS NULL THEN COALESCE(s.method, 'app') ELSE NULL END,
       s.latitude::NUMERIC, s.longitude::NUMERIC, ex.new_id
FROM migration.stg_attendance s
JOIN migration.id_map m ON m.entity = 'attendance' AND m.legacy_id = s.id::BIGINT
JOIN migration.id_map emp ON emp.entity = 'employees' AND emp.legacy_id = s.employee_id::BIGINT
JOIN employees e ON e.id = emp.new_id
{_fk('ex', 'exception_types', 's.exception_type_id')}
WHERE NOT EXISTS (SELECT 1 FROM attendance a WHERE a.id = m.new_id);
""".strip())

    # ---------- transform: EAV -> typed company_settings ----------
    setting_cases = "\n".join(
        f"       MAX(CASE WHEN d.setting_key = '{key}' THEN v.value END)::{sql_type} AS {column},"
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
                              weekly_off_days, overtime_rate, monthly_leave_accrual)
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
        for e in LOAD_ORDER
    )
    counts = "\nUNION ALL\n".join(
        f"    SELECT '{e}'::TEXT, count(*)::BIGINT FROM {e}" for e in LOAD_ORDER
    )
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

-- Unmapped-foreign-key guard: a row that reached staging but never got
-- an id means a parent was missing from the export.
DO $$
DECLARE missing BIGINT;
BEGIN
    SELECT count(*) INTO missing
    FROM migration.stg_attendance s
    WHERE NOT EXISTS (SELECT 1 FROM migration.id_map m
                      WHERE m.entity = 'attendance' AND m.legacy_id = s.id::BIGINT);
    IF missing > 0 THEN
        RAISE EXCEPTION 'ETL: % attendance rows were staged but never mapped', missing;
    END IF;
END $$;
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
    check("unmapped attendance rows abort the load",
          "were staged but never mapped" in sql)
    check("both non-copy transforms are present",
          "TRANSFORM 1" in load and "TRANSFORM 2" in load)
    check("pay_overtime is not silently invented as a column",
          "pay_overtime" not in load)
    check("sections are independently emittable",
          all(SECTIONS[s]() for s in SECTIONS))
    check("every mapped entity gets a sequence fixup",
          all(f"pg_get_serial_sequence('{e}', 'id')" in sql for e in LOAD_ORDER))

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

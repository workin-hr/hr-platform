#!/usr/bin/env python3
"""Emit the legacy (MySQL) extraction for the hr-platform migration.

`scripts/migration_diff.py` reconciles CSV exports and nothing produced
them, so the harness has never had an input. This is that step.

It emits SQL rather than connecting: this repository's tooling is
stdlib-only by rule, so it must run on an operator's machine near
production with no network installs and no MySQL driver. The operator
pipes the output into `mysql` and redirects each result to the file
named in the manifest.

**Extraction only. Nothing here loads, and nothing writes to legacy.**

Usage:
    python3 scripts/etl/export_legacy.py --print-sql   > export.sql
    python3 scripts/etl/export_legacy.py --manifest    > manifest.json
    python3 scripts/etl/export_legacy.py --self-test
"""

from __future__ import annotations

import json
import sys

EXPORT_SQL = r"""
-- Legacy (MySQL) extraction for the hr-platform migration.
-- Produces the CSV exports scripts/migration_diff.py reconciles against
-- the Postgres side. READ ONLY: nothing here writes to legacy.
--
-- Run with:
--   mysql --defaults-file=... --batch --raw workin < export_legacy_mysql.sql
-- and redirect each SELECT to the matching file in the manifest.
--
-- ============================================================
-- THE TIMEZONE RULE  (docs/migration/2026-08-09-etl-and-timezone-design.md)
-- ============================================================
-- Legacy stores two different kinds of time and they must be exported
-- differently. Getting this backwards is unrecoverable once loaded.
--
--   * `timestamp` columns (created_at, updated_at, decided_at, ...) are
--     true UTC epochs internally. Exporting under UTC yields the real
--     instant -- lossless.
--   * `datetime` columns (attendance.check_in, attendance.check_out,
--     branches.expires_at) are literal wall-clock text with no offset
--     ever applied, and the offset in force when each row was written is
--     unrecoverable. They are exported VERBATIM and loaded as the same
--     reading in UTC.
--
-- SET time_zone = '+00:00' therefore does exactly the right thing to
-- both: it makes timestamp columns render as true UTC, and it does not
-- touch datetime columns at all -- MySQL never converts those.
SET time_zone = '+00:00';
SET SESSION group_concat_max_len = 1000000;

-- NULL is exported as the literal \N, distinct from an empty string,
-- which is the encoding migration_diff.py expects.

-- ---------- companies ----------
SELECT id, company_name AS name, phone, status, created_at
FROM companies
ORDER BY id;

-- ---------- employees ----------
-- expected_daily_hours is carried across: V37 ported the column, and
-- payroll's overtime divisor reads it.
SELECT id, company_id, branch_id, department_id, job_title_id,
       employee_code, expected_daily_hours, first_name, last_name,
       phone, country_code, password_hash, role, national_id, birth_date,
       gender, hire_date, is_active, created_at, updated_at
FROM employees
ORDER BY id;

-- ---------- attendance ----------
-- The load-bearing one. check_in/check_out are datetime: exported as the
-- literal wall clock, NOT shifted. `method` is carried through because
-- method='excel' rows come from fingerprint devices in device-local time
-- and are ambiguous for a reason unrelated to the config flag -- the
-- reconciliation report segments on it.
SELECT id, employee_id,
       DATE_FORMAT(check_in,  '%Y-%m-%d %H:%i:%s') AS check_in,
       DATE_FORMAT(check_out, '%Y-%m-%d %H:%i:%s') AS check_out,
       method, exception_type_id, latitude, longitude,
       created_at, updated_at
FROM attendance
ORDER BY id;

-- ---------- attendance day bucketing, for conformance test 3 ----------
-- Legacy's own DATE(check_in) per row. The Postgres side must agree with
-- this for every row after load; any disagreement means the wall-clock
-- rule was not applied.
SELECT id, DATE(check_in) AS legacy_day
FROM attendance
ORDER BY id;

-- ---------- exception types ----------
SELECT id, company_id, name, created_at
FROM exception_types
ORDER BY id;

-- ---------- shifts / org ----------
SELECT id, company_id, name, start_time, end_time, days_off, is_active, created_at
FROM shifts ORDER BY id;

SELECT id, company_id, name, address, latitude, longitude, radius_meters,
       DATE_FORMAT(expires_at, '%Y-%m-%d %H:%i:%s') AS expires_at,
       qr_code, is_active, created_at
FROM branches ORDER BY id;

SELECT id, company_id, department_id, name, work_hours, is_active, created_at
FROM job_titles ORDER BY id;

SELECT id, company_id, name, manager_id, is_active, created_at
FROM departments ORDER BY id;

SELECT department_id, branch_id
FROM department_branches ORDER BY department_id, branch_id;

-- ---------- requests ----------
SELECT id, employee_id, request_type_id, from_date, to_date,
       from_time, to_time, notes, status, reply, approver_id, decided_at,
       created_at, updated_at
FROM requests ORDER BY id;

SELECT id, company_id, name, is_active, deduct_balance, counts_as_paid_leave,
       add_attendance_exception, exception_type_id, created_at
FROM request_types ORDER BY id;

-- ---------- holidays ----------
SELECT id, company_id, name, holiday_date, created_at
FROM company_official_holidays ORDER BY id;

-- ---------- payroll ----------
SELECT id, employee_id, salary_mode, basic_salary, daily_wage,
       housing_allowance, transport_allowance, food_allowance,
       risk_allowance, incentives, insurance_deduction, tax_deduction,
       advances_deduction, fund_deduction, penalty_deduction,
       effective_from, created_at
FROM salary_contracts ORDER BY id;

SELECT id, company_id, month, year, period_from, period_to, status, created_at
FROM payroll_batches ORDER BY id;

-- The deduction_* scheduling columns are carried: V12 has a target column
-- for each, and without them an installment repayment loads as the
-- single-month default, misstating the arrangement.
SELECT id, employee_id, amount, remaining, reason, rejection_reason,
       status, request_date, created_at, updated_at,
       deduction_mode, deduction_month_count, deduction_amount_per_month,
       deduction_payroll_year, deduction_payroll_month
FROM advances ORDER BY id;

SELECT id, employee_id, penalty_type, penalty_days, reason, penalty_date,
       applied_to_payroll, created_at
FROM penalties ORDER BY id;

-- ---------- payroll history and scheduling ----------
-- Four tables with a shipped target schema and, until now, no migration
-- path at all. payslips are copied verbatim rather than recomputed: they
-- are the record of what an employee was actually paid, not an input to
-- be re-derived (D-a).
SELECT id, batch_id, employee_id, days_present, days_absent, days_leave,
       overtime_hours, basic_salary, allowances, overtime_pay, penalties_total,
       advance_deduction, other_deductions, net_salary, food_allowance,
       risk_allowance, transport_allowance, incentives, insurance_deduction,
       tax_deduction, advances_deduction, fund_deduction, gross_salary,
       total_entitlements, total_deductions
FROM payslips ORDER BY id;

-- remaining_days is GENERATED on both sides. Exported so migration_diff
-- can prove the two engines agree on it; never inserted.
SELECT id, employee_id, year, period_from_month, period_to_month,
       monthly_cap_days, total_days, used_days, remaining_days
FROM leave_balance ORDER BY id;

-- Legacy has no unique key on (employee_id, year) but V25 declares one.
-- Counted here, at dump time, so a duplicate is a decision made with real
-- numbers weeks before cutover rather than an abort discovered during it.
SELECT employee_id, year, COUNT(*) AS duplicate_rows
FROM leave_balance
GROUP BY employee_id, year
HAVING COUNT(*) > 1
ORDER BY employee_id, year;

SELECT id, employee_id, schedule_date, name, start_time, end_time,
       exception_note, created_at
FROM employee_schedules ORDER BY id;

SELECT id, employee_id, shift_id, effective_from, created_at
FROM employee_shift_assignments ORDER BY id;

-- ---------- conversions, exported raw for transform ----------
-- These two are NOT row copies. hr_permissions expands into
-- membership_permission_overrides, and the EAV settings pivot into the
-- typed company_settings columns. Exported in source shape; the
-- transform is designed separately.
SELECT * FROM hr_permissions ORDER BY id;
-- The EAV chain. `company_settings` is legacy's join table between a
-- company and a setting definition; it is exported as
-- company_settings.csv and staged as stg_legacy_company_settings, so it
-- is not confused with the new typed table of the same name.
SELECT id, company_id, setting_definition_id FROM company_settings ORDER BY id;
SELECT * FROM company_setting_values ORDER BY id;
SELECT * FROM setting_definitions ORDER BY id;
SELECT * FROM setting_allowed_values ORDER BY id;

-- ---------- created_at quality probe ----------
-- The load carries legacy created_at into NOT NULL TIMESTAMPTZ columns.
-- MySQL permits '0000-00-00 00:00:00' where PostgreSQL has no such value,
-- and invalid-date-analysis.md proves zero-dates are real in this dump --
-- it just never examined these columns. A single bad value aborts the
-- cutover run at the cast, so it is counted here, at dump time, while
-- there is still time to decide what to do about it.
SELECT 'created_at_quality' AS probe, t.name AS table_name,
       t.zero_dates, t.null_dates
FROM (
    SELECT 'companies' AS name,
           SUM(created_at = '0000-00-00 00:00:00') AS zero_dates,
           SUM(created_at IS NULL) AS null_dates FROM companies
    UNION ALL SELECT 'employees',
           SUM(created_at = '0000-00-00 00:00:00'), SUM(created_at IS NULL) FROM employees
    UNION ALL SELECT 'attendance',
           SUM(created_at = '0000-00-00 00:00:00'), SUM(created_at IS NULL) FROM attendance
    UNION ALL SELECT 'branches',
           SUM(created_at = '0000-00-00 00:00:00'), SUM(created_at IS NULL) FROM branches
    UNION ALL SELECT 'shifts',
           SUM(created_at = '0000-00-00 00:00:00'), SUM(created_at IS NULL) FROM shifts
    UNION ALL SELECT 'job_titles',
           SUM(created_at = '0000-00-00 00:00:00'), SUM(created_at IS NULL) FROM job_titles
    UNION ALL SELECT 'departments',
           SUM(created_at = '0000-00-00 00:00:00'), SUM(created_at IS NULL) FROM departments
    UNION ALL SELECT 'exception_types',
           SUM(created_at = '0000-00-00 00:00:00'), SUM(created_at IS NULL) FROM exception_types
    UNION ALL SELECT 'request_types',
           SUM(created_at = '0000-00-00 00:00:00'), SUM(created_at IS NULL) FROM request_types
    UNION ALL SELECT 'requests',
           SUM(created_at = '0000-00-00 00:00:00'), SUM(created_at IS NULL) FROM requests
    UNION ALL SELECT 'company_official_holidays',
           SUM(created_at = '0000-00-00 00:00:00'), SUM(created_at IS NULL) FROM company_official_holidays
    UNION ALL SELECT 'salary_contracts',
           SUM(created_at = '0000-00-00 00:00:00'), SUM(created_at IS NULL) FROM salary_contracts
    UNION ALL SELECT 'payroll_batches',
           SUM(created_at = '0000-00-00 00:00:00'), SUM(created_at IS NULL) FROM payroll_batches
    UNION ALL SELECT 'advances',
           SUM(created_at = '0000-00-00 00:00:00'), SUM(created_at IS NULL) FROM advances
    UNION ALL SELECT 'penalties',
           SUM(created_at = '0000-00-00 00:00:00'), SUM(created_at IS NULL) FROM penalties
) t
ORDER BY t.name;

-- ---------- the offset the export ran under, for the record ----------
-- Recorded so a later reader can tell what the exporter assumed, and so
-- the is_daylight_saving question is answered by the dump itself rather
-- than from memory.
SELECT config_key, config_value FROM configs WHERE config_key = 'is_daylight_saving';

"""

MANIFEST = r"""
{
  "_comment": "Input to scripts/migration_diff.py. expected_count is the measured legacy baseline from docs/migration/table-volume-analysis.md; fill the remaining nulls from the real dump before a cutover run.",
  "tables": [
    {"file": "companies.csv", "key": ["id"], "expected_count": null},
    {"file": "employees.csv", "key": ["id"], "expected_count": 2871},
    {"file": "attendance.csv", "key": ["id"], "expected_count": 36316},
    {"file": "attendance_days.csv", "key": ["id"], "expected_count": 36316},
    {"file": "exception_types.csv", "key": ["id"], "expected_count": null},
    {"file": "shifts.csv", "key": ["id"], "expected_count": null},
    {"file": "branches.csv", "key": ["id"], "expected_count": null},
    {"file": "job_titles.csv", "key": ["id"], "expected_count": null},
    {"file": "departments.csv", "key": ["id"], "expected_count": null},
    {"file": "department_branches.csv", "key": ["department_id", "branch_id"], "expected_count": 1245},
    {"file": "requests.csv", "key": ["id"], "expected_count": null},
    {"file": "request_types.csv", "key": ["id"], "expected_count": null},
    {"file": "company_official_holidays.csv", "key": ["id"], "expected_count": null},
    {"file": "salary_contracts.csv", "key": ["id"], "expected_count": null},
    {"file": "payroll_batches.csv", "key": ["id"], "expected_count": null},
    {"file": "advances.csv", "key": ["id"], "expected_count": null},
    {"file": "penalties.csv", "key": ["id"], "expected_count": null},
    {"file": "payslips.csv", "key": ["id"], "expected_count": 2836},
    {"file": "leave_balance.csv", "key": ["id"], "expected_count": 2980},
    {"file": "employee_schedules.csv", "key": ["id"], "expected_count": 352},
    {"file": "employee_shift_assignments.csv", "key": ["id"], "expected_count": 3568}
  ]
}
"""


def self_test() -> int:
    failures: list[str] = []

    def check(name: str, condition: bool) -> None:
        print(("OK  " if condition else "FAIL ") + name)
        if not condition:
            failures.append(name)

    # The rule this whole file exists to implement.
    check("session is pinned to UTC", "SET time_zone = '+00:00'" in EXPORT_SQL)
    check(
        "datetime columns are exported verbatim, not shifted",
        "DATE_FORMAT(check_in" in EXPORT_SQL and "CONVERT_TZ" not in EXPORT_SQL,
    )
    check(
        "legacy's own day bucketing is exported for the conformance check",
        "DATE(check_in) AS legacy_day" in EXPORT_SQL,
    )
    check(
        "method is carried so excel-device rows can be segmented",
        "method, exception_type_id" in EXPORT_SQL,
    )
    check(
        "the daylight-saving flag is captured from the dump itself",
        "is_daylight_saving" in EXPORT_SQL,
    )
    check("extraction is read-only", not any(
        word in EXPORT_SQL.upper() for word in ("INSERT INTO", "UPDATE ", "DELETE FROM", "DROP ", "ALTER ")))
    check(
        "companies is selected by its real column name, not the target's",
        "company_name AS name" in EXPORT_SQL and "SELECT id, name," not in EXPORT_SQL,
    )
    check("requests.notes is carried, not just reply", "from_time, to_time, notes," in EXPORT_SQL)
    check("departments and their branch assignments are exported, not silently dropped",
          "FROM departments ORDER BY id" in EXPORT_SQL
          and "FROM department_branches ORDER BY department_id, branch_id" in EXPORT_SQL
          and "departments.csv" in MANIFEST
          and "department_branches.csv" in MANIFEST)
    check(
        "created_at is probed for zero-dates before the load depends on it",
        "created_at_quality" in EXPORT_SQL
        and "0000-00-00 00:00:00" in EXPORT_SQL
        and EXPORT_SQL.count("AS zero_dates") == 1,
    )

    manifest = json.loads(MANIFEST)
    files = [t["file"] for t in manifest["tables"]]
    check("manifest lists attendance and its day bucketing",
          "attendance.csv" in files and "attendance_days.csv" in files)
    check("every manifest entry declares a key", all(t.get("key") for t in manifest["tables"]))
    check("measured baselines are carried",
          any(t["file"] == "attendance.csv" and t["expected_count"] == 36316 for t in manifest["tables"]))

    return 1 if failures else 0


def main() -> int:
    if "--manifest" in sys.argv:
        print(MANIFEST.strip())
        return 0
    if "--print-sql" in sys.argv:
        print(EXPORT_SQL.strip())
        return 0
    print(__doc__)
    return 0


if __name__ == "__main__":
    sys.exit(self_test() if "--self-test" in sys.argv else main())

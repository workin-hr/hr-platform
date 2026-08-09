#!/usr/bin/env python3
"""Emit the PostgreSQL-side export, keyed back to legacy ids.

`scripts/migration_diff.py` compares two CSVs row by row on a declared
key. That only works if both sides agree on what the key means -- and
they do not: the new tables use GENERATED ALWAYS AS IDENTITY, so a row's
id there is unrelated to its legacy id.

Every SELECT here therefore joins `migration.id_map` and reports the
**legacy** id. The differ then compares like for like, and a finding
names a row somebody can actually go and look at in the old system.

Emits SQL for the same reason the other two scripts do: stdlib only, no
driver, no network installs.

Usage:
    python3 scripts/etl/export_target_postgres.py --print-sql
    python3 scripts/etl/export_target_postgres.py --self-test
"""

from __future__ import annotations

import sys

# entity -> (target table, extra select columns) with the id mapped back.
EXPORTS = [
    ("companies", "SELECT m.legacy_id AS id, t.name, t.phone FROM companies t"),
    ("employees",
     "SELECT m.legacy_id AS id, cm.legacy_id AS company_id, t.first_name, t.last_name, "
     "t.phone, t.role, t.active, t.expected_daily_hours FROM employees t "
     "JOIN migration.id_map cm ON cm.entity = 'companies' AND cm.new_id = t.company_id"),
    ("attendance",
     "SELECT m.legacy_id AS id, em.legacy_id AS employee_id, "
     "to_char(t.check_in AT TIME ZONE 'UTC', 'YYYY-MM-DD HH24:MI:SS') AS check_in, "
     "to_char(t.check_out AT TIME ZONE 'UTC', 'YYYY-MM-DD HH24:MI:SS') AS check_out, "
     "t.method FROM attendance t "
     "JOIN migration.id_map em ON em.entity = 'employees' AND em.new_id = t.employee_id"),
    ("attendance_days",
     "SELECT m.legacy_id AS id, "
     "to_char(t.check_in AT TIME ZONE 'UTC', 'YYYY-MM-DD') AS legacy_day FROM attendance t"),
    ("exception_types", "SELECT m.legacy_id AS id, t.name FROM exception_types t"),
    ("shifts", "SELECT m.legacy_id AS id, t.name, t.start_time, t.end_time, t.days_off FROM shifts t"),
    ("branches", "SELECT m.legacy_id AS id, t.name, t.address FROM branches t"),
    ("job_titles", "SELECT m.legacy_id AS id, t.name, t.work_hours FROM job_titles t"),
    ("request_types", "SELECT m.legacy_id AS id, t.name, t.counts_as_paid_leave FROM request_types t"),
    ("requests",
     "SELECT m.legacy_id AS id, em.legacy_id AS employee_id, t.from_date, t.to_date, t.status "
     "FROM requests t JOIN migration.id_map em ON em.entity = 'employees' AND em.new_id = t.employee_id"),
    ("company_official_holidays",
     "SELECT m.legacy_id AS id, t.name, t.holiday_date FROM company_official_holidays t"),
    ("salary_contracts",
     "SELECT m.legacy_id AS id, em.legacy_id AS employee_id, t.salary_mode, t.basic_salary, "
     "t.daily_wage, t.effective_from FROM salary_contracts t "
     "JOIN migration.id_map em ON em.entity = 'employees' AND em.new_id = t.employee_id"),
    ("payroll_batches", "SELECT m.legacy_id AS id, t.month, t.year, t.period_from, t.period_to FROM payroll_batches t"),
    ("advances",
     "SELECT m.legacy_id AS id, em.legacy_id AS employee_id, t.amount, t.status FROM advances t "
     "JOIN migration.id_map em ON em.entity = 'employees' AND em.new_id = t.employee_id"),
    ("penalties",
     "SELECT m.legacy_id AS id, em.legacy_id AS employee_id, t.penalty_days, t.penalty_date "
     "FROM penalties t JOIN migration.id_map em ON em.entity = 'employees' AND em.new_id = t.employee_id"),
]


def build() -> str:
    out = [
        "-- Target-side export for scripts/migration_diff.py.",
        "-- Ids are reported as LEGACY ids via migration.id_map, so the two",
        "-- sides are comparable and a finding names a row you can look up.",
        "",
    ]
    for entity, select in EXPORTS:
        table = "attendance" if entity == "attendance_days" else entity
        out.append(f"-- {entity}.csv")
        out.append(
            f"{select}\n"
            f"JOIN migration.id_map m ON m.entity = '{table}' AND m.new_id = t.id\n"
            f"ORDER BY m.legacy_id;\n")
    return "\n".join(out)


def self_test() -> int:
    failures: list[str] = []

    def check(name: str, condition: bool) -> None:
        print(("OK  " if condition else "FAIL ") + name)
        if not condition:
            failures.append(name)

    sql = build()
    check("no export reports a raw target id",
          sql.count("legacy_id AS id") == len(EXPORTS))
    check("every export joins the id map", sql.count("JOIN migration.id_map m") == len(EXPORTS))
    check("attendance renders wall clock, not an offset instant",
          "AT TIME ZONE 'UTC', 'YYYY-MM-DD HH24:MI:SS'" in sql)
    check("the day-bucketing conformance export is present",
          "attendance_days.csv" in sql and "'YYYY-MM-DD') AS legacy_day" in sql)
    check("foreign keys are reported as legacy ids too",
          "em.legacy_id AS employee_id" in sql and "cm.legacy_id AS company_id" in sql)
    check("every export is deterministically ordered", sql.count("ORDER BY m.legacy_id;") == len(EXPORTS))
    return 1 if failures else 0


def main() -> int:
    if "--print-sql" in sys.argv:
        print(build())
        return 0
    print(__doc__)
    return 0


if __name__ == "__main__":
    sys.exit(self_test() if "--self-test" in sys.argv else main())

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

import json
import sys

from export_legacy import EXPORT_SQL, MANIFEST

# entity -> (target table, extra select columns) with the id mapped back.
EXPORTS = [
    ("companies", "SELECT m.legacy_id AS id, t.name, t.phone FROM companies t"),
    # The six D-036 business fields are named exactly as export_legacy.py
    # names them, so migration_diff.py's shared-column intersection picks
    # them up and compares values. Until the 2026-08-16 load populated
    # them they could only ever be reported as `source-only` -- a column
    # set difference, never a verdict on a single cell.
    #
    # Two of them are expected to differ on the repaired rows, by design:
    # birth_date/hire_date where legacy holds '0000-00-00', and gender
    # where legacy holds ''. Those are the D-036 repairs, and they are
    # exactly the rows the still-unbuilt remediation output owes a record
    # of -- so until finding 3b's declared-transformation support lands,
    # they surface here as findings rather than nowhere.
    ("employees",
     "SELECT m.legacy_id AS id, cm.legacy_id AS company_id, t.first_name, t.last_name, "
     "t.phone, t.role, t.active, t.expected_daily_hours, "
     "t.employee_code, t.country_code, t.national_id, t.birth_date, t.gender, t.hire_date "
     "FROM employees t "
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
    ("departments",
     "SELECT m.legacy_id AS id, cm.legacy_id AS company_id, t.name, "
     "em.legacy_id AS manager_id, CASE WHEN t.is_active THEN 1 ELSE 0 END AS is_active, "
     "to_char(t.created_at AT TIME ZONE 'UTC', 'YYYY-MM-DD HH24:MI:SS') AS created_at "
     "FROM departments t "
     "JOIN migration.id_map cm ON cm.entity = 'companies' AND cm.new_id = t.company_id "
     "LEFT JOIN migration.id_map em ON em.entity = 'employees' AND em.new_id = t.manager_id"),
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
    ("payslips",
     "SELECT m.legacy_id AS id, bm.legacy_id AS batch_id, em.legacy_id AS employee_id, "
     "t.basic_salary, t.net_salary, t.gross_salary, t.total_deductions FROM payslips t "
     "JOIN migration.id_map bm ON bm.entity = 'payroll_batches' AND bm.new_id = t.batch_id "
     "JOIN migration.id_map em ON em.entity = 'employees' AND em.new_id = t.employee_id"),
    # remaining_days is reported on both sides deliberately: it is GENERATED
    # by each engine independently, so comparing it proves they agree.
    ("leave_balance",
     "SELECT m.legacy_id AS id, em.legacy_id AS employee_id, t.year, t.total_days, "
     "t.used_days, t.remaining_days FROM leave_balances t "
     "JOIN migration.id_map em ON em.entity = 'employees' AND em.new_id = t.employee_id"),
    ("employee_schedules",
     "SELECT m.legacy_id AS id, em.legacy_id AS employee_id, t.schedule_date, t.name, "
     "t.start_time, t.end_time, t.exception_note FROM employee_schedules t "
     "JOIN migration.id_map em ON em.entity = 'employees' AND em.new_id = t.employee_id"),
    ("employee_shift_assignments",
     "SELECT m.legacy_id AS id, em.legacy_id AS employee_id, sm.legacy_id AS shift_id, "
     "t.effective_from FROM employee_shift_assignments t "
     "JOIN migration.id_map em ON em.entity = 'employees' AND em.new_id = t.employee_id "
     "JOIN migration.id_map sm ON sm.entity = 'shifts' AND sm.new_id = t.shift_id"),
]

# Manifest file name -> the id_map entity and target table, where the
# legacy and target names diverge. leave_balance is the only one.
EXPORT_TABLE_OVERRIDE = {"leave_balance": "leave_balances"}

# Composite-key tables do not have a legacy id to materialize in id_map.
# Their parent ids are still translated back before reconciliation.
JUNCTION_EXPORTS = [
    ("department_branches",
     "SELECT dm.legacy_id AS department_id, bm.legacy_id AS branch_id "
     "FROM department_branches t "
     "JOIN migration.id_map dm ON dm.entity = 'departments' AND dm.new_id = t.department_id "
     "JOIN migration.id_map bm ON bm.entity = 'branches' AND bm.new_id = t.branch_id "
     "ORDER BY dm.legacy_id, bm.legacy_id;"),
]


def build() -> str:
    out = [
        "-- Target-side export for scripts/migration_diff.py.",
        "-- Ids are reported as LEGACY ids via migration.id_map, so the two",
        "-- sides are comparable and a finding names a row you can look up.",
        "",
    ]
    for entity, select in EXPORTS:
        table = "attendance" if entity == "attendance_days" else EXPORT_TABLE_OVERRIDE.get(entity, entity)
        out.append(f"-- {entity}.csv")
        out.append(
            f"{select}\n"
            f"JOIN migration.id_map m ON m.entity = '{table}' AND m.new_id = t.id\n"
            f"ORDER BY m.legacy_id;\n")
    for entity, select in JUNCTION_EXPORTS:
        out.append(f"-- {entity}.csv")
        out.append(f"{select}\n")
    return "\n".join(out)


def self_test() -> int:
    failures: list[str] = []

    def check(name: str, condition: bool) -> None:
        print(("OK  " if condition else "FAIL ") + name)
        if not condition:
            failures.append(name)

    sql = build()
    manifest_files = {spec["file"] for spec in json.loads(MANIFEST)["tables"]}
    export_files = ({f"{entity}.csv" for entity, _ in EXPORTS}
                    | {f"{entity}.csv" for entity, _ in JUNCTION_EXPORTS})
    check("no export reports a raw target id",
          sql.count("legacy_id AS id") == len(EXPORTS))
    check("every export joins the id map", sql.count("JOIN migration.id_map m") == len(EXPORTS))
    check("attendance renders wall clock, not an offset instant",
          "AT TIME ZONE 'UTC', 'YYYY-MM-DD HH24:MI:SS'" in sql)
    check("the day-bucketing conformance export is present",
          "attendance_days.csv" in sql and "'YYYY-MM-DD') AS legacy_day" in sql)
    check("foreign keys are reported as legacy ids too",
          "em.legacy_id AS employee_id" in sql and "cm.legacy_id AS company_id" in sql)
    check("every id-mapped export is deterministically ordered",
          sql.count("ORDER BY m.legacy_id;") == len(EXPORTS))
    check("department export maps company and manager ids back to legacy ids",
          "departments.csv" in sql and "em.legacy_id AS manager_id" in sql)
    check("department branch export maps both halves of its composite key",
          "department_branches.csv" in sql
          and "dm.legacy_id AS department_id" in sql
          and "ORDER BY dm.legacy_id, bm.legacy_id;" in sql)
    check("every manifest file has a target-side export", export_files == manifest_files)
    # A column only gets compared if BOTH sides emit it under the same
    # name -- migration_diff.py intersects the two headers and reports
    # anything else as a column-set difference, which is a structural
    # note, not a verdict on any value. Asserting both sides at once is
    # the point: naming these here while export_legacy.py called them
    # something else would read as covered and compare nothing.
    employees_select = next(select for entity, select in EXPORTS if entity == "employees")
    # Scoped to the legacy employees SELECT, not the whole EXPORT_SQL:
    # `country_code` is also a companies column, so an unscoped substring
    # test would pass even if employees never selected it.
    legacy_employees = EXPORT_SQL.split("-- ---------- employees ----------", 1)[-1] \
        .split("FROM employees", 1)[0]
    check("the six D-036 business fields are reconciled, not just loaded -- "
          "both exports name them identically so the differ compares values",
          all(f"t.{column}" in employees_select and column in legacy_employees
              for column in ("employee_code", "country_code", "national_id",
                             "birth_date", "gender", "hire_date")))
    return 1 if failures else 0


def main() -> int:
    if "--print-sql" in sys.argv:
        print(build())
        return 0
    print(__doc__)
    return 0


if __name__ == "__main__":
    sys.exit(self_test() if "--self-test" in sys.argv else main())

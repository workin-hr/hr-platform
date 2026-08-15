#!/usr/bin/env python3
r"""Emit the legacy (MySQL) extraction for the hr-platform migration, and
(`--extract`) actually run it against a live database.

`scripts/migration_diff.py` reconciles CSV exports and nothing produced
them, so the harness has never had an input. This is that step.

Three modes need nothing beyond the stdlib -- `--print-sql`, `--manifest`,
`--self-test` -- and stay that way. A fourth, `--extract`, connects with a
real driver and writes CSVs directly. That used to be impossible by rule
("stdlib-only, no MySQL driver, run near production with no network
installs"), and the rule is now gone on purpose, not by accident:
production's `secure_file_priv` is `/dev/null/`, which disables MySQL's
own `SELECT ... INTO OUTFILE` outright, and even where it isn't disabled
`INTO OUTFILE` writes to the *database server's* filesystem -- production
here is managed shared hosting, so the file could never be retrieved
either way. There is no connectionless path left. `--extract` imports
PyMySQL (scripts/etl/requirements.txt; pure-Python, no compilation, so it
installs anywhere) lazily, inside the function that needs it, so the
other three modes still run on a bare Python install with zero
dependencies -- only an operator running a live extraction needs the
driver.

The documented procedure this replaces (`mysql --batch --raw ... |`
redirected per table) was also broken independently of the above: `--raw`
disables escaping, so the 29 `requests.notes` / 6 `requests.reply` rows
(live production, 2026-08-15) containing embedded newlines corrupted row
boundaries for any line-based reader, and NULL rendered as the literal
4-character string `NULL` rather than `\N`. `--extract` uses Python's
`csv` module (QUOTE_MINIMAL, plus the NULL-collision handling on
`write_csv_rows` below) instead of hand-rolled delimiter/escaping logic,
which makes both classes of corruption structurally impossible rather
than merely re-verified against whatever snapshot happened to be tested
last -- see the brief's own caveat that its `INTO OUTFILE` proposal
depended on "this snapshot has no embedded double-quote characters," an
assumption a real CSV writer never has to make.

**Extraction only. Nothing here loads, and nothing writes to legacy.**
`--extract` enforces that structurally, not just by docstring promise:
every statement is required to start with SELECT or SET
(`_classify_statement`), and the session itself is put into
`SET SESSION TRANSACTION READ ONLY` before any of EXPORT_SQL runs.

Usage:
    python3 scripts/etl/export_legacy.py --print-sql   > export.sql
    python3 scripts/etl/export_legacy.py --manifest    > manifest.json
    python3 scripts/etl/export_legacy.py --self-test
    DB_HOST=... DB_USER=... DB_PASS=... DB_NAME=... \
        python3 scripts/etl/export_legacy.py --extract --out DIR

`--extract` reads credentials from the environment only (DB_HOST,
DB_USER, DB_PASS, DB_NAME, DB_PORT, the last optional/default 3306) --
never a CLI argument (shell history, `ps` on a shared host), never a
file, never logged. It writes one CSV per table EXPORT_SQL selects, plus
a `manifest.json` whose `expected_count`s are measured from the rows this
run actually wrote, not remembered from a snapshot -- see
`build_measured_manifest`'s docstring and D-037
(docs/bootstrap/decision-log.md) for why the static MANIFEST below can no
longer be handed to `migration_diff.py` directly.
"""

from __future__ import annotations

import csv
import datetime
import io
import json
import os
import re
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
-- hr_permissions and the EAV chain are NOT row copies in the ordinary
-- sense. hr_permissions expands into membership_permission_overrides;
-- company_settings/company_setting_values feed the EAV pivot into the
-- typed company_settings columns; setting_definitions/
-- setting_allowed_values feed that SAME pivot (via setting_key) AND, as
-- of V44__create_settings_catalog.sql, are ALSO loaded as an ordinary
-- id-mapped row copy into their own new target tables -- see
-- load_postgres.py's "settings catalog" load block. Exported in source
-- shape; the transforms are designed separately.
--
-- All four SELECTs below are explicit column lists, not SELECT *, and
-- must exactly match their STAGING entry in load_postgres.py, in the
-- same order: \copy matches a CSV to migration.stg_<table> positionally,
-- not by header name (load_postgres.py:141-146's comment on
-- hr_permissions says this explicitly), so a column SELECT * silently
-- adds and STAGING does not declare is not a missing value -- it is
-- every subsequent column shifted one to the left, and \copy rejects the
-- whole file with "extra data after last expected column". That is
-- exactly what SELECT * did here before this was fixed: setting_definitions
-- selected all 11 legacy columns while STAGING declared 2
-- (id, setting_key), and setting_allowed_values selected all 7 while
-- STAGING declared 4. coverage_audit.py's check_export_staging_agreement
-- (added alongside this fix) now fails loudly if the two ever drift
-- again, including a regression back to SELECT *.
SELECT id, employee_id, can_dashboard, can_recent_activities, can_branches,
       can_departments, can_job_titles, can_shifts, can_leave_balances,
       can_assets, can_advances, can_workforce_planning, can_salary_calculator,
       can_company_settings, can_employees, can_attendance, can_requests,
       can_payroll, can_penalties, updated_at
FROM hr_permissions ORDER BY id;
-- The EAV chain. `company_settings` is legacy's join table between a
-- company and a setting definition; it is exported as
-- company_settings.csv and staged as stg_legacy_company_settings, so it
-- is not confused with the new typed table of the same name.
SELECT id, company_id, setting_definition_id FROM company_settings ORDER BY id;
SELECT id, company_setting_id, setting_allowed_value_id
FROM company_setting_values ORDER BY id;
-- updated_at is deliberately NOT selected on either of the next two
-- tables: verified zero reads anywhere in the hr-legacy codebase, and
-- V44__create_settings_catalog.sql -- which creates the target tables
-- these rows are loaded into -- deliberately omits it too (see that
-- migration's own comment block for why). Carrying a legacy column that
-- has no target column and no reader would just relocate a dead column
-- instead of retiring it.
SELECT id, setting_key, label_ar, label_en, description_ar, description_en,
       icon_data, is_multi, is_required, sort_order
FROM setting_definitions ORDER BY id;
SELECT id, setting_definition_id, value, label_ar, label_en, sort_order
FROM setting_allowed_values ORDER BY id;

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
  "_comment": "Input to scripts/migration_diff.py. expected_count is the measured legacy baseline from docs/migration/table-volume-analysis.md. All entries confirmed 2026-08-13 against mysql_workin.schema.sql + mysql_workin.data.sql (dump dated 2026-08-03) -- re-verify against a fresh snapshot before an actual cutover run.",
  "tables": [
    {"file": "companies.csv", "key": ["id"], "expected_count": 269},
    {"file": "employees.csv", "key": ["id"], "expected_count": 2871},
    {"file": "attendance.csv", "key": ["id"], "expected_count": 36316},
    {"file": "attendance_days.csv", "key": ["id"], "expected_count": 36316},
    {"file": "exception_types.csv", "key": ["id"], "expected_count": 111},
    {"file": "shifts.csv", "key": ["id"], "expected_count": 302},
    {"file": "branches.csv", "key": ["id"], "expected_count": 375},
    {"file": "job_titles.csv", "key": ["id"], "expected_count": 1684},
    {"file": "departments.csv", "key": ["id"], "expected_count": 950},
    {"file": "department_branches.csv", "key": ["department_id", "branch_id"], "expected_count": 1245},
    {"file": "requests.csv", "key": ["id"], "expected_count": 213},
    {"file": "request_types.csv", "key": ["id"], "expected_count": 192},
    {"file": "company_official_holidays.csv", "key": ["id"], "expected_count": 69},
    {"file": "salary_contracts.csv", "key": ["id"], "expected_count": 2829},
    {"file": "payroll_batches.csv", "key": ["id"], "expected_count": 61},
    {"file": "advances.csv", "key": ["id"], "expected_count": 24},
    {"file": "penalties.csv", "key": ["id"], "expected_count": 15},
    {"file": "payslips.csv", "key": ["id"], "expected_count": 2836},
    {"file": "leave_balance.csv", "key": ["id"], "expected_count": 2980},
    {"file": "employee_schedules.csv", "key": ["id"], "expected_count": 352},
    {"file": "employee_shift_assignments.csv", "key": ["id"], "expected_count": 3568}
  ]
}
"""


# --------------------------------------------------------------------
# Statement splitting / classification, shared by --extract and its
# self-tests. Pure functions, no DB -- everything above this line still
# needs nothing but the stdlib to import cleanly.
# --------------------------------------------------------------------

# Same encoding migration_diff.py's `NULL` constant uses (scripts/
# migration_diff.py:46) -- kept as this module's own literal rather than
# imported, matching every other script under scripts/etl/, which stays
# self-contained (operator tooling: it must run standalone off a checkout
# near production, not depend on a sibling module living at a fixed
# relative path outside this directory).
NULL_MARKER = "\\N"


def _split_statements(export_sql: str) -> list[str]:
    """EXPORT_SQL -> top-level statements, comments and blank lines
    dropped.

    Comment lines are stripped BEFORE splitting on ';', not after: several
    of EXPORT_SQL's own comments contain a literal ';' mid-sentence (e.g.
    "any disagreement means the wall-clock" 's line, "...after load; any
    disagreement...", export_legacy.py:136) which would otherwise split a
    statement in half. coverage_audit.py's parse_exported()
    (scripts/etl/coverage_audit.py:495) splits on ';' first and tolerates
    that fine because it only substring-searches each chunk; it never
    requires a chunk to start cleanly with SELECT the way
    _classify_statement below does, so it can't reuse that ordering as-is.
    """
    sql_only = "\n".join(
        line for line in export_sql.splitlines() if not line.strip().startswith("--")
    )
    return [stmt.strip() for stmt in sql_only.split(";") if stmt.strip()]


def _classify_statement(stmt: str) -> tuple[str, str | None]:
    """One EXPORT_SQL statement -> ("SET", None) | ("TABLE", filename) |
    ("REPORT", label).

    TABLE statements get written to <filename> by a live extraction.
    REPORT statements -- the leave_balance duplicate-key check, the
    created_at zero-date probe, the is_daylight_saving flag -- are
    diagnostic: none of the three ever had a MANIFEST entry or a
    migration_diff.py reconciliation, so --extract prints them for the
    operator instead of writing a CSV nobody reads (see _print_report).

    Refuses anything that is not SELECT or SET -- the read-only guarantee
    this file's docstring promises, enforced here structurally rather
    than left as a comment. (Belt-and-suspenders: run_extraction also
    puts the session itself into SET SESSION TRANSACTION READ ONLY
    before any of this executes.)
    """
    upper = stmt.upper()
    if upper.startswith("SET "):
        return "SET", None
    if not upper.startswith("SELECT"):
        raise ValueError(f"refusing non-SELECT/SET statement: {stmt[:60]!r}")

    if "HAVING COUNT(*) > 1" in upper:
        return "REPORT", "leave_balance duplicate keys"
    if "CREATED_AT_QUALITY" in upper:
        return "REPORT", "created_at zero-date probe"
    if "IS_DAYLIGHT_SAVING" in upper:
        return "REPORT", "is_daylight_saving flag"

    match = re.search(r"\bFROM\s+(\w+)", stmt, re.I)
    if not match:
        raise ValueError(f"cannot find FROM table in statement: {stmt[:60]!r}")
    table = match.group(1)

    # attendance is exported twice: the row copy, and its own
    # DATE(check_in) bucketing for conformance test 3 ("attendance day
    # bucketing" section of EXPORT_SQL). Both are FROM attendance; only
    # the bucketing query selects legacy_day.
    if table == "attendance" and "LEGACY_DAY" in upper:
        return "TABLE", "attendance_days.csv"
    return "TABLE", f"{table}.csv"


def _csv_cell(value: object) -> tuple[str, bool]:
    """A raw driver value -> (csv cell text, force_quote).

    NULL -> NULL_MARKER, unquoted -- migration_diff.py's own contract
    (scripts/migration_diff.py:46). A REAL value that happens to equal
    NULL_MARKER is flagged for forced quoting: PostgreSQL's
    `COPY ... (FORMAT csv, NULL '\\N')` -- the load side's own documented
    target format (docs/migration/2026-08-13-etl-real-data-findings-
    decision-brief.md's Fix A section) -- treats an UNQUOTED match to the
    NULL string as NULL but a QUOTED one as the literal value; this is
    what makes that distinction land in the file. Everything else is left
    to csv.QUOTE_MINIMAL (write_csv_rows below), which already handles
    embedded commas/quotes/newlines correctly -- the entire reason this
    mode exists instead of hand-rolled escaping.
    """
    if value is None:
        return NULL_MARKER, False
    text = str(value)
    return text, text == NULL_MARKER


def write_csv_rows(handle, header: list[str], rows) -> int:
    """Write `header` then `rows` (iterables of raw driver values) to
    `handle` as CSV. Returns the row count written.

    Two csv.writer objects share one file handle rather than one writer
    with per-call quoting, because the stdlib csv module sets quoting
    per-writer, not per-field, and only the rare row containing a real
    NULL_MARKER-valued string needs forcing (see _csv_cell). Forcing that
    whole row is exactly as correct as forcing just the one field would
    be -- a quoted field elsewhere in the row is always safe to read
    back -- and far simpler than reaching into the csv module to quote
    one cell of a row.
    """
    minimal = csv.writer(handle)
    forced = csv.writer(handle, quoting=csv.QUOTE_ALL)
    minimal.writerow(header)
    count = 0
    for row in rows:
        cells: list[str] = []
        force = False
        for value in row:
            text, needs_quote = _csv_cell(value)
            cells.append(text)
            force = force or needs_quote
        (forced if force else minimal).writerow(cells)
        count += 1
    return count


def build_measured_manifest(counts: dict[str, int], generated_at: str) -> str:
    """MANIFEST's shape, with every `expected_count` replaced by the count
    a live run actually wrote.

    D-037 (docs/bootstrap/decision-log.md, 2026-08-15): every hardcoded
    expected_count above is stale -- all 21 tables grew 4.3%-59.6% in the
    12 days since the 2026-08-03 snapshot they were measured from, and
    `attendance` grew *during the verification session itself*. A number
    that volatile cannot be remembered between runs; it has to be
    measured every time. The static MANIFEST constant above is kept, not deleted
    -- useful as a historical baseline for "how much did this table grow"
    -- but the manifest a real run hands to migration_diff.py must be
    this function's output, not that constant.

    Raises if `counts` is missing a file MANIFEST expects: a partial
    manifest handed to migration_diff.py would silently compare some
    tables against a stale snapshot count and others against nothing,
    which is worse than failing loudly here.
    """
    static = json.loads(MANIFEST)
    tables = []
    for entry in static["tables"]:
        filename = entry["file"]
        if filename not in counts:
            raise ValueError(f"live extraction never wrote {filename}, cannot measure it")
        tables.append({"file": filename, "key": entry["key"], "expected_count": counts[filename]})
    manifest = {
        "_comment": (
            f"Input to scripts/migration_diff.py. expected_count measured "
            f"live at extraction time ({generated_at}), not carried from a "
            f"prior snapshot -- see D-037, docs/bootstrap/decision-log.md, "
            f"and export_legacy.py's build_measured_manifest()."
        ),
        "tables": tables,
    }
    return json.dumps(manifest, indent=2)


def _print_report(label: str, header: list[str], rows: list[tuple]) -> None:
    """Print a REPORT statement's results for the operator.

    These three queries (leave_balance duplicate keys, the created_at
    zero-date probe, is_daylight_saving) never had a MANIFEST entry and
    are not something migration_diff.py reconciles -- under the old
    `mysql --batch --raw |` procedure the operator would simply have seen
    them on the terminal; this is that, for --extract.
    """
    print(f"\n=== {label} ({len(rows)} row(s)) ===", file=sys.stderr)
    if not rows:
        print("  (none)", file=sys.stderr)
    for row in rows:
        pairs = ", ".join(f"{column}={value}" for column, value in zip(header, row))
        print(f"  {pairs}", file=sys.stderr)


def run_extraction(out_dir: str) -> dict[str, int]:
    """Connect to legacy MySQL/MariaDB and run EXPORT_SQL for real: one
    CSV per TABLE statement, REPORT statements printed for the operator,
    then <out_dir>/manifest.json built by build_measured_manifest.

    Imports PyMySQL lazily -- see the module docstring for why this
    dependency exists at all (secure_file_priv=/dev/null/ rules out
    INTO OUTFILE) and why the import stays inside this function (so
    --print-sql/--manifest/--self-test need nothing beyond the stdlib).
    """
    try:
        import pymysql  # noqa: local import is deliberate, see docstring above
        import pymysql.converters
        from pymysql.constants import FIELD_TYPE
    except ImportError as exc:
        raise SystemExit(
            f"{exc}. --extract needs PyMySQL, which --print-sql/--manifest/"
            f"--self-test deliberately do not: pip install -r scripts/etl/requirements.txt"
        ) from exc

    required = ("DB_HOST", "DB_USER", "DB_PASS", "DB_NAME")
    missing = [name for name in required if not os.environ.get(name)]
    if missing:
        raise SystemExit(
            "missing required environment variable(s): "
            + ", ".join(missing)
            + " (credentials come from the environment only -- see the module docstring)"
        )

    # Disable the driver's own DATE/DATETIME/TIMESTAMP/TIME parsing
    # entirely: every temporal column comes back as the exact string
    # MySQL sent, already correct because of SET time_zone (THE TIMEZONE
    # RULE, EXPORT_SQL's header comment above). This makes "the driver
    # does not convert anything" true by construction for every temporal
    # column -- including ones this comment does not enumerate -- rather
    # than relying on reasoning about whether PyMySQL's parsed datetime
    # objects would happen to already be naive-and-UTC.
    conv = pymysql.converters.conversions.copy()
    for code in (FIELD_TYPE.TIMESTAMP, FIELD_TYPE.DATETIME, FIELD_TYPE.DATE,
                 FIELD_TYPE.TIME, FIELD_TYPE.NEWDATE):
        conv[code] = lambda raw: raw

    connection = pymysql.connect(
        host=os.environ["DB_HOST"],
        user=os.environ["DB_USER"],
        password=os.environ["DB_PASS"],
        database=os.environ["DB_NAME"],
        port=int(os.environ.get("DB_PORT", "3306")),
        conv=conv,
        autocommit=True,
        charset="utf8mb4",
    )
    try:
        with connection.cursor() as cursor:
            # Belt-and-suspenders read-only, on top of _classify_statement's
            # SELECT/SET allowlist: reject writes at the server, not only
            # in this script's own parsing.
            cursor.execute("SET SESSION TRANSACTION READ ONLY")

            os.makedirs(out_dir, exist_ok=True)
            counts: dict[str, int] = {}
            for stmt in _split_statements(EXPORT_SQL):
                kind, name = _classify_statement(stmt)
                cursor.execute(stmt)
                if kind == "SET":
                    continue
                header = [d[0] for d in cursor.description]
                rows = cursor.fetchall()
                if kind == "REPORT":
                    _print_report(name, header, rows)
                    continue
                path = os.path.join(out_dir, name)
                with open(path, "w", newline="", encoding="utf-8") as handle:
                    counts[name] = write_csv_rows(handle, header, rows)
                print(f"{name}: {counts[name]} rows", file=sys.stderr)
    finally:
        connection.close()

    generated_at = datetime.datetime.now(datetime.timezone.utc).isoformat()
    manifest_text = build_measured_manifest(counts, generated_at)
    with open(os.path.join(out_dir, "manifest.json"), "w", encoding="utf-8") as handle:
        handle.write(manifest_text)
    return counts


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

    # --------------------------------------------------------------
    # --extract's building blocks. All pure functions -- no DB needed --
    # so these run in every CI invocation, not just an operator's.
    # --------------------------------------------------------------

    # The exact scenario Fix A exists for: a real requests.notes/.reply
    # value is never distinguishable from actual data quoting is needed
    # for -- embedded newlines, embedded double quotes -- because the
    # stdlib csv module (QUOTE_MINIMAL) handles both by construction. This
    # replaces the brief's fragile "we scanned this snapshot and found no
    # embedded double-quote characters" assumption with a structural
    # guarantee that holds for every future snapshot too, not just the
    # one that happened to be checked.
    buf = io.StringIO()
    write_csv_rows(buf, ["id", "notes"], [(1, "line one\nline two"), (2, 'has "quotes" inside')])
    buf.seek(0)
    rows = list(csv.reader(buf))
    check(
        "embedded newlines and quotes round-trip correctly, no assumption needed",
        rows == [["id", "notes"], ["1", "line one\nline two"], ["2", 'has "quotes" inside']],
    )

    # The \N collision (spec item 3): a real string value equal to the
    # NULL marker must be distinguishable from an actual NULL. csv.reader
    # can't tell quoted from unquoted after parsing -- both come back as
    # the same Python string -- so the proof has to be at the raw-byte
    # level, which is also exactly what PostgreSQL's own
    # `COPY ... (FORMAT csv, NULL '\N')` inspects: it treats an unquoted
    # match to the NULL string as NULL and a quoted one as literal data.
    buf = io.StringIO()
    write_csv_rows(buf, ["id", "value"], [(1, None), (2, NULL_MARKER)])
    lines = buf.getvalue().splitlines()
    check(
        "an actual NULL is written as unquoted \\N",
        lines[1] == "1,\\N",
    )
    check(
        # QUOTE_ALL quotes every field in the row, not just the colliding
        # one (write_csv_rows forces the whole row -- see its docstring)
        # -- so the id column is quoted too; that's harmless, just noisier.
        "a real string value equal to \\N round-trips as data, not NULL "
        "(force-quoted so PostgreSQL's COPY NULL detection does not eat it)",
        lines[2] == '"2","\\N"',
    )
    check(
        "both forms parse back to the identical two-character string via csv.reader "
        "(proving quoting, not content, is what a downstream reader must key off)",
        list(csv.reader(io.StringIO(lines[1])))[0][1] == list(csv.reader(io.StringIO(lines[2])))[0][1] == NULL_MARKER,
    )

    # EXPORT_SQL is the single source of truth (spec item 1): --extract
    # must cope with every statement currently in it, not a hand-copied
    # subset. This fails loudly the moment a statement's shape changes in
    # a way the classifier cannot recognize, instead of --extract quietly
    # mis-filing or dropping a table at 2am on an operator's machine.
    statements = _split_statements(EXPORT_SQL)
    kinds = [_classify_statement(s) for s in statements]
    set_count = sum(1 for kind, _ in kinds if kind == "SET")
    table_files = sorted(name for kind, name in kinds if kind == "TABLE")
    report_labels = sorted(name for kind, name in kinds if kind == "REPORT")
    check(
        "the two session SET statements (time zone, group_concat_max_len) are found",
        set_count == 2,
    )
    check(
        "every REPORT statement is recognized: leave_balance duplicates, "
        "created_at probe, is_daylight_saving",
        report_labels == sorted([
            "leave_balance duplicate keys",
            "created_at zero-date probe",
            "is_daylight_saving flag",
        ]),
    )
    expected_table_files = sorted(
        [t["file"] for t in manifest["tables"]]
        # Transform sources (README's "Not covered" section / coverage_audit.py's
        # TRANSFORM_SOURCES): exported as CSV like everything else, but never a
        # MANIFEST entry because migration_diff.py never reconciles them directly.
        + ["hr_permissions.csv", "company_settings.csv", "company_setting_values.csv",
           "setting_definitions.csv", "setting_allowed_values.csv"]
    )
    check(
        "every TABLE statement maps to exactly the 21 manifest files plus the "
        "5 known transform-source extras -- no more, no fewer",
        table_files == expected_table_files,
    )
    check(
        "attendance's two SELECTs classify to two different files, not a collision",
        ("TABLE", "attendance.csv") in kinds and ("TABLE", "attendance_days.csv") in kinds,
    )
    check(
        "a non-SELECT/SET statement is refused, not silently skipped",
        _raises(ValueError, _classify_statement, "DELETE FROM employees"),
    )

    # build_measured_manifest (spec item 5): must replace every
    # expected_count with what was actually measured, and must refuse to
    # produce a partial manifest rather than silently omitting a table.
    fake_counts = {t["file"]: 999999 for t in manifest["tables"]}
    measured = json.loads(build_measured_manifest(fake_counts, "2026-08-15T00:00:00+00:00"))
    check(
        "build_measured_manifest replaces every expected_count with the measured value",
        all(t["expected_count"] == 999999 for t in measured["tables"]),
    )
    check(
        "build_measured_manifest preserves file names and keys unchanged",
        [(t["file"], t["key"]) for t in measured["tables"]]
        == [(t["file"], t["key"]) for t in manifest["tables"]],
    )
    check(
        "the measured manifest's comment says measured, not a remembered snapshot",
        "measured" in measured["_comment"] and "D-037" in measured["_comment"],
    )
    incomplete_counts = dict(fake_counts)
    del incomplete_counts["attendance.csv"]
    check(
        "a manifest missing a table's count fails loudly rather than shipping partial",
        _raises(ValueError, build_measured_manifest, incomplete_counts, "2026-08-15T00:00:00+00:00"),
    )

    # The lazy-import contract (spec item: PyMySQL only loads inside
    # run_extraction): loading this module and running its self-test must
    # never import the driver, or --print-sql/--manifest/--self-test would
    # silently gain the dependency they are documented not to need.
    check("pymysql is not imported merely by running this module's self-test",
          "pymysql" not in sys.modules)

    return 1 if failures else 0


def _raises(exc_type: type[BaseException], fn, *args) -> bool:
    """True iff calling fn(*args) raises exc_type. Local helper so the
    checks above read as one line each instead of a try/except block."""
    try:
        fn(*args)
    except exc_type:
        return True
    return False


def main() -> int:
    if "--manifest" in sys.argv:
        print(MANIFEST.strip())
        return 0
    if "--print-sql" in sys.argv:
        print(EXPORT_SQL.strip())
        return 0
    if "--extract" in sys.argv:
        if "--out" not in sys.argv:
            print("--extract requires --out DIR", file=sys.stderr)
            return 2
        out_dir = sys.argv[sys.argv.index("--out") + 1]
        counts = run_extraction(out_dir)
        total = sum(counts.values())
        print(f"wrote {len(counts)} CSV files, {total} rows total, to {out_dir}/")
        print(f"wrote {out_dir}/manifest.json with counts measured from this run")
        return 0
    print(__doc__)
    return 0


if __name__ == "__main__":
    sys.exit(self_test() if "--self-test" in sys.argv else main())

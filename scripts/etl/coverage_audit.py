#!/usr/bin/env python3
"""Prove that no legacy column is silently dropped on the way to PostgreSQL.

Every gap found so far was found by accident, while looking at something
adjacent: `created_at` dropped by 14 load blocks, the `advances`
scheduling columns never extracted, `branches.qr_code` with a target
column and no export. That is not a method, and at cutover the cost of
missing one is a value nobody can get back.

This is the method. It reads the three artifacts that actually decide
what survives -- the legacy schema, `export_legacy.py`'s extraction, and
`load_postgres.py`'s staging and INSERT column lists -- and reports every
legacy column that does not reach the target, in three detection classes:

    UNEXTRACTED_TABLE   a legacy table with no SELECT at all
    UNEXTRACTED_COLUMN  a column absent from its table's SELECT
    UNLOADED_COLUMN     staged, then left out of the INSERT column list
                        -- the exact shape of the created_at defect

The point is not the report. The point is `--check`: every gap must be
registered in ACCEPTED (a decision was made and recorded) or PENDING (a
decision is owed, with a tracking note). A gap in neither fails the
build. So a column can still be dropped -- deliberately, in writing --
but it can no longer be dropped quietly.

A stale registry entry fails too. An accepted drop that has since been
implemented, or a pending gap that has since been closed, is a lie in
the ledger, and a ledger nobody trusts is worse than none.

Stdlib only, no database connection, same rule as the rest of
`scripts/etl/` -- it parses source, it does not query anything.

Usage:
    python3 scripts/etl/coverage_audit.py --report
    python3 scripts/etl/coverage_audit.py --check
    python3 scripts/etl/coverage_audit.py --self-test

`--report` and `--check` need the legacy schema, which lives outside this
repository; pass `--schema PATH` or keep the default sibling checkout.
`--self-test` needs nothing -- it runs every detection class against
synthetic fixtures, so CI verifies the detector itself without requiring
the legacy dump to be present.
"""

from __future__ import annotations

import os
import re
import sys

DEFAULT_SCHEMA = os.path.join("..", "hr-legacy", "mysql_workin.schema.sql")
MIGRATION_DIR = os.path.join("backend", "src", "main", "resources", "db", "migration")
EXPORT_SCRIPT = os.path.join("scripts", "etl", "export_legacy.py")
LOAD_SCRIPT = os.path.join("scripts", "etl", "load_postgres.py")

# Legacy table -> target table, for the only names that diverge. Every
# other table keeps its name; a new divergence belongs here, not in a
# special case somewhere in the load.
TABLE_MAP = {"leave_balance": "leave_balances"}

# Legacy tables that are inputs to a transform rather than row copies.
# Their columns are consumed by the transform, not mapped one-to-one, so
# per-column coverage is meaningless for them.
TRANSFORM_SOURCES = {
    "hr_permissions",
    "company_settings",
    "company_setting_values",
    "setting_definitions",
    "setting_allowed_values",
}

# --------------------------------------------------------------------
# The ledger. Every known gap lives in exactly one of these.
# --------------------------------------------------------------------

# Decided, in writing, not to migrate. The reason is the point -- an
# entry without one is indistinguishable from an oversight.
ACCEPTED: dict[str, str] = {
    "salary_contracts.total": (
        "GENERATED ALWAYS AS STORED on both sides; PostgreSQL rejects an "
        "INSERT naming it. Exported for cross-engine comparison, never loaded."
    ),
    "employees.password_hash": (
        "Deliberately not written to employees: credentials live on identities "
        "(load_postgres.py's identity transform consumes this column). The target "
        "employees.password_hash column is vestigial and is its own question."
    ),
}

# Legacy tables with no extraction at all. Registered as whole tables
# because per-column entries would say the same thing 8-25 times.
ACCEPTED_TABLES: dict[str, str] = {}

PENDING_TABLES: dict[str, str] = {
    "payslips": "Target V11 exists. Slice specced in 2026-08-11-etl-payroll-history-design.md.",
    "leave_balance": "Target V25 leave_balances exists. Same slice.",
    "employee_schedules": "Target V33 exists. Same slice.",
    "employee_shift_assignments": "Target V33 exists. Same slice.",
    "notifications": "4,014 rows. No target table. Keep-or-drop is a product call.",
    "complaints": "Support inbox. No target table. Product call.",
    "assets": "Company assets issued to employees. No target table. Product call.",
    "administrative_decisions": "Company announcements. No target table. Product call.",
    "workforce_planning": "Headcount targets. No target table. Product call.",
    "employee_docs": "Document metadata; files live outside the DB. Product call.",
    "push_tokens": "FCM tokens; F-08 says push never worked end-to-end. Product call.",
    "otp_codes": "Short-lived codes. Almost certainly not worth migrating -- confirm.",
    "banners": "Marketing surface. No target table. Product call.",
    "app_content": "CMS-ish content. No target table. Product call.",
    "faq_categories": "Reference/content data. No target table. Product call.",
    "faq_items": "Reference/content data. No target table. Product call.",
    "phone_countries": "Reference data; the new platform may seed its own. Confirm.",
    "company_activities": "Lookup referenced by companies.company_activity_id. Confirm.",
    "company_sizes": "Lookup referenced by companies.company_size_id. Confirm.",
    "company_titles": "Lookup referenced by companies.company_title_id. Confirm.",
}

# Known, undecided, and owed an answer. The note names what has to be
# resolved -- 'TODO' is not a note.
PENDING: dict[str, str] = {
    "companies.password_hash": (
        "Company-level login credentials. Legacy companies log in with "
        "phone+password; the identities transform derives only from "
        "stg_employees, so nothing carries company credentials across. "
        "Decide whether company login survives cutover at all."
    ),
    "companies.email": "Part of the company-onboarding surface; no target column.",
    "companies.otp_verified": "Registration/verification state; no target column.",
    "companies.profile_completed": "Onboarding gate; no target column.",
    "employees.is_mobile_attendance_enabled": (
        "Per-employee mobile-attendance opt-in. Affects attendance behaviour, "
        "so dropping it silently changes what employees can do."
    ),
    "employees.can_check_in_any_branch": (
        "Per-employee geofence exemption; same class as the flag above."
    ),
    "employees.join_request_status": "Onboarding gate; no target column.",
    "advances.deduction_type": (
        "Legacy enum single_month/multiple_months. Looks redundant with "
        "deduction_mode, but redundancy has to be confirmed, not assumed."
    ),
    "advances.deduction_installments_json": (
        "Real data written by advances/create.php and update.php. No target "
        "column; needs one or an explicit drop."
    ),
    "companies.company_code": (
        "Human-facing company identifier. Decide whether tenants keep their "
        "existing code after cutover or are renumbered."
    ),
    "companies.first_name": (
        "Company contact person's name, distinct from the company name. Decide "
        "whether the contact becomes an employee/identity row or is dropped."
    ),
    "companies.last_name": "Company contact person's surname; same decision as first_name.",
    "companies.country_code": (
        "Dial-code prefix for the company phone. Decide whether the target "
        "stores it separately or folds it into the phone value."
    ),
    "companies.commercial_reg_url": (
        "Uploaded commercial-registration document. Likely a compliance record; "
        "decide whether it must be retained."
    ),
    "companies.logo_url": (
        "Tenant branding shown in the clients. Decide whether branding survives "
        "cutover or tenants re-upload."
    ),
    "companies.rejection_reason": (
        "Company-approval workflow state, paired with the status enum. Decide "
        "whether rejection history is retained."
    ),
    "companies.main_branch_address": (
        "Free-text address on the tenant row, separate from branches.address. "
        "Decide whether it maps onto the main branch or is dropped."
    ),
    "companies.company_activity_id": (
        "FK into company_activities, itself unextracted. Decide the lookup "
        "tables and this column together, not separately."
    ),
    "companies.company_title_id": "FK into company_titles; decide with the lookup tables.",
    "companies.company_size_id": "FK into company_sizes; decide with the lookup tables.",
    "companies.updated_at": (
        "The target companies table has created_at only. Decide whether any "
        "entity needs updated_at, since several legacy tables carry one."
    ),
    "configs.id": (
        "configs is read only for the is_daylight_saving probe and has no target "
        "table; confirm no other config key is needed post-cutover."
    ),
    "employees.address": (
        "Employee home address. Decide whether the rewrite stores personal "
        "addresses at all -- there may be a data-minimisation argument not to."
    ),
    "employees.photo_url": (
        "Employee photo shown in the clients. Decide whether photos survive "
        "cutover or are re-uploaded."
    ),
    "employees.contract_duration_months": (
        "Fixed-term contract length. Confirm with payroll whether any rule "
        "depends on it before dropping."
    ),
    "employees.token_version": (
        "Legacy JWT-invalidation counter. The rewrite revokes via refresh-token "
        "families (F-26), so this is probably obsolete -- confirm, do not assume."
    ),
    "exception_types.is_active": (
        "Legacy can deactivate an exception type; the target cannot. A migrated "
        "inactive type would silently become active and reappear in pickers."
    ),
    "exception_types.updated_at": (
        "No target column. Decide alongside companies.updated_at rather than "
        "table by table."
    ),
    "salary_contracts.total": (
        "GENERATED ALWAYS AS STORED in legacy; the target has no such column. "
        "Should still be exported for cross-engine comparison."
    ),
}


# --------------------------------------------------------------------
# Parsing
# --------------------------------------------------------------------


def parse_legacy_schema(text: str) -> dict[str, list[str]]:
    """MySQL dump DDL -> {table: [column, ...]}."""
    out: dict[str, list[str]] = {}
    for m in re.finditer(r"CREATE TABLE `(\w+)` \((.*?)\n\)", text, re.S):
        out[m.group(1)] = re.findall(r"^\s*`(\w+)`", m.group(2), re.M)
    return out


def parse_target_schema(sql_by_version: list[str]) -> dict[str, list[str]]:
    """Flyway migrations, in version order -> {table: [column, ...]}.

    Reads ALTER TABLE ... ADD COLUMN as well as CREATE TABLE: a target
    column added by a later migration is still a target column, and
    reading only CREATE TABLE understates the schema (V27 added
    employees.expected_daily_hours exactly this way).
    """
    out: dict[str, list[str]] = {}
    reserved = {"CONSTRAINT", "PRIMARY", "UNIQUE", "FOREIGN", "CHECK"}
    for sql in sql_by_version:
        for m in re.finditer(r"CREATE TABLE (?:IF NOT EXISTS )?(\w+)\s*\((.*?)\n\);", sql, re.S):
            cols = re.findall(r"^\s+(\w+)\s+[A-Z]", m.group(2), re.M)
            out[m.group(1)] = [c for c in cols if c.upper() not in reserved]
        for m in re.finditer(r"ALTER TABLE (\w+)\s+ADD COLUMN (?:IF NOT EXISTS )?(\w+)", sql, re.I):
            out.setdefault(m.group(1), []).append(m.group(2))
    return out


def parse_exported(export_sql: str) -> dict[str, object]:
    """EXPORT_SQL -> {table: set(columns) or the string 'ALL'}.

    Statement-scoped: the SELECT list is only searched inside the
    statement that reads that table, so a column name mentioned in an
    unrelated query cannot mark it covered.
    """
    out: dict[str, object] = {}
    for stmt in export_sql.split(";"):
        for table in re.findall(r"\bFROM\s+(\w+)\b", stmt):
            if re.search(r"SELECT\s+\*\s+FROM\s+" + table + r"\b", stmt):
                out[table] = "ALL"
                continue
            found = out.setdefault(table, set())
            if found == "ALL":
                continue
            for word in re.findall(r"\b(\w+)\b", stmt):
                found.add(word)
    return out


def parse_staging(load_src: str) -> dict[str, list[str]]:
    """The STAGING dict -> {legacy table: [staged column, ...]}."""
    block = load_src.split("STAGING = {", 1)[1].split("\n}", 1)[0]
    out: dict[str, list[str]] = {}
    for m in re.finditer(r'"(\w+)":\s*(\[[^\]]*\])', block):
        out[m.group(1)] = re.findall(r'"(\w+)"', m.group(2))
    return out


def parse_inserted(load_src: str) -> dict[str, set[str]]:
    """Columns the load actually writes -> {target table: {column, ...}}.

    Both INSERT column lists and `UPDATE <table> SET <column>` are
    counted. The UPDATE half is not a nicety: `departments.manager_id`
    is deliberately inserted NULL and backfilled afterwards, because
    departments -> employees -> departments is a real cycle. Reading
    only INSERT lists reports that correct, deliberate design as a
    dropped column, and a detector that cries wolf on the load's most
    carefully-reasoned step is one nobody will keep running.
    """
    out: dict[str, set[str]] = {}
    for m in re.finditer(r"INSERT INTO (\w+) \(([^)]*)\)", load_src):
        if m.group(1).startswith("migration"):
            continue
        cols = {c.strip() for c in m.group(2).replace("\n", " ").split(",")}
        out.setdefault(m.group(1), set()).update(c for c in cols if c)
    for m in re.finditer(r"UPDATE (\w+)[^;]*?\bSET\s+(\w+)\s*=", load_src, re.S):
        if m.group(1).startswith("migration"):
            continue
        out.setdefault(m.group(1), set()).add(m.group(2))
    return out


# --------------------------------------------------------------------
# Detection
# --------------------------------------------------------------------


def find_gaps(legacy, target, exported, staging, inserted) -> list[tuple[str, str, str]]:
    """-> [(kind, key, detail)], sorted, one entry per gap."""
    gaps: list[tuple[str, str, str]] = []
    for table in sorted(legacy):
        if table in TRANSFORM_SOURCES:
            continue
        target_table = TABLE_MAP.get(table, table)
        if table not in exported:
            gaps.append(("UNEXTRACTED_TABLE", table, f"{len(legacy[table])} columns, no SELECT"))
            continue
        cols = exported[table]
        for column in legacy[table]:
            if cols != "ALL" and column not in cols:
                has_target = column in target.get(target_table, [])
                gaps.append((
                    "UNEXTRACTED_COLUMN",
                    f"{table}.{column}",
                    "target column exists" if has_target else "no target column",
                ))
            elif (
                column in staging.get(table, [])
                and target_table in inserted
                and column in target.get(target_table, [])
                and column not in inserted[target_table]
            ):
                gaps.append((
                    "UNLOADED_COLUMN",
                    f"{table}.{column}",
                    "staged and has a target column, but absent from the INSERT list",
                ))
    return sorted(gaps)


def check(gaps) -> tuple[list[str], list[str]]:
    """-> (unregistered, stale). Both must be empty for --check to pass.

    Table-level gaps are matched against the table registries, column
    gaps against the column ones, so a whole unextracted table does not
    need one entry per column saying the same thing.
    """
    registered_cols = set(ACCEPTED) | set(PENDING)
    registered_tables = set(ACCEPTED_TABLES) | set(PENDING_TABLES)
    seen_tables = {key for kind, key, _ in gaps if kind == "UNEXTRACTED_TABLE"}
    seen_cols = {key for kind, key, _ in gaps if kind != "UNEXTRACTED_TABLE"}
    unregistered = sorted(
        [k for k in seen_tables if k not in registered_tables]
        + [k for k in seen_cols if k not in registered_cols]
    )
    stale = sorted(
        [k for k in registered_tables if k not in seen_tables]
        + [k for k in registered_cols if k not in seen_cols]
    )
    return unregistered, stale


# --------------------------------------------------------------------
# Self-test: every detection class, on synthetic input
# --------------------------------------------------------------------

_FIXTURE_SCHEMA = """
CREATE TABLE `kept` (
  `id` int(10) UNSIGNED NOT NULL,
  `carried` varchar(50) NOT NULL,
  `never_extracted` varchar(50) DEFAULT NULL,
  `staged_not_loaded` timestamp NOT NULL
)
CREATE TABLE `orphan` (
  `id` int(10) UNSIGNED NOT NULL,
  `whatever` text
)
"""

_FIXTURE_EXPORT = """
SELECT id, carried, staged_not_loaded FROM kept ORDER BY id;
"""

_FIXTURE_MIGRATIONS = [
    "CREATE TABLE kept (\n    id BIGINT NOT NULL,\n    carried VARCHAR(50) NOT NULL,\n"
    "    never_extracted VARCHAR(50),\n    staged_not_loaded TIMESTAMPTZ NOT NULL\n);",
]

_FIXTURE_LOAD = '''
STAGING = {
    "kept": ["id", "carried", "staged_not_loaded"],
}
INSERT INTO kept (id, carried)
'''


def self_test() -> int:
    failures: list[str] = []

    def check_that(name: str, condition: bool) -> None:
        print(("OK   " if condition else "FAIL ") + name)
        if not condition:
            failures.append(name)

    legacy = parse_legacy_schema(_FIXTURE_SCHEMA)
    target = parse_target_schema(_FIXTURE_MIGRATIONS)
    exported = parse_exported(_FIXTURE_EXPORT)
    staging = parse_staging(_FIXTURE_LOAD)
    inserted = parse_inserted(_FIXTURE_LOAD)

    check_that("legacy DDL parses into tables and columns",
               legacy.get("kept") == ["id", "carried", "never_extracted", "staged_not_loaded"])
    check_that("target schema reads CREATE TABLE columns",
               "never_extracted" in target.get("kept", []))
    check_that("ALTER TABLE ADD COLUMN counts as a target column",
               "added_later" in parse_target_schema(
                   ["CREATE TABLE t (\n    id BIGINT\n);", "ALTER TABLE t ADD COLUMN added_later TEXT;"]
               ).get("t", []))
    check_that("staging and INSERT column lists parse",
               staging.get("kept") == ["id", "carried", "staged_not_loaded"]
               and inserted.get("kept") == {"id", "carried"})
    check_that("a column written by a backfill UPDATE counts as loaded",
               parse_inserted("INSERT INTO d (id, name)\n...\nUPDATE d SET manager_id = m.new_id\n")
               == {"d": {"id", "name", "manager_id"}})

    gaps = find_gaps(legacy, target, exported, staging, inserted)
    kinds = {kind: key for kind, key, _ in gaps}

    check_that("detects a legacy table with no SELECT at all",
               kinds.get("UNEXTRACTED_TABLE") == "orphan")
    check_that("detects a column absent from its table's SELECT",
               kinds.get("UNEXTRACTED_COLUMN") == "kept.never_extracted")
    check_that("detects a staged column left out of the INSERT (the created_at shape)",
               kinds.get("UNLOADED_COLUMN") == "kept.staged_not_loaded")
    check_that("a carried column is not reported", not any("kept.carried" == k for _, k, _ in gaps))

    # A column named only in an unrelated statement must not count as covered.
    check_that(
        "coverage is statement-scoped, not a whole-file substring search",
        any(k == "kept.never_extracted" for _, k, _ in find_gaps(
            legacy, target,
            parse_exported("SELECT id, carried, staged_not_loaded FROM kept;\n"
                           "SELECT never_extracted FROM something_else;"),
            staging, inserted)),
    )

    unregistered, stale = check([("UNEXTRACTED_COLUMN", "kept.never_extracted", "")])
    check_that("an unregistered gap is reported by --check", unregistered == ["kept.never_extracted"])
    check_that("a registry entry with no matching gap is reported as stale",
               "salary_contracts.total" in stale)
    check_that("every ACCEPTED entry carries a reason",
               all(len(v.strip()) > 20 for v in ACCEPTED.values()))
    check_that("every PENDING entry carries a note",
               all(len(v.strip()) > 20 for v in PENDING.values()))

    return 1 if failures else 0


# --------------------------------------------------------------------
# Entry point
# --------------------------------------------------------------------


def load_inputs(schema_path: str):
    with open(schema_path, encoding="utf-8", errors="replace") as handle:
        legacy = parse_legacy_schema(handle.read())
    migrations = []
    for root, _, files in os.walk(MIGRATION_DIR):
        for name in files:
            if name.endswith(".sql") and name.startswith("V"):
                migrations.append((int(re.match(r"V(\d+)", name).group(1)), os.path.join(root, name)))
    sql_by_version = []
    for _, path in sorted(migrations):
        with open(path, encoding="utf-8") as handle:
            sql_by_version.append(handle.read())
    with open(EXPORT_SCRIPT, encoding="utf-8") as handle:
        export_src = handle.read()
    export_sql = export_src.split('EXPORT_SQL = r"""', 1)[1].split('"""', 1)[0]
    with open(LOAD_SCRIPT, encoding="utf-8") as handle:
        load_src = handle.read()
    return (
        legacy,
        parse_target_schema(sql_by_version),
        parse_exported(export_sql),
        parse_staging(load_src),
        parse_inserted(load_src),
    )


def main(argv: list[str]) -> int:
    if "--self-test" in argv:
        return self_test()

    schema_path = DEFAULT_SCHEMA
    if "--schema" in argv:
        schema_path = argv[argv.index("--schema") + 1]
    if not os.path.exists(schema_path):
        print(f"legacy schema not found at {schema_path}", file=sys.stderr)
        print("pass --schema PATH, or run --self-test which needs no schema", file=sys.stderr)
        return 2

    gaps = find_gaps(*load_inputs(schema_path))
    unregistered, stale = check(gaps)

    if "--check" in argv:
        for key in unregistered:
            print(f"UNREGISTERED GAP: {key} -- record it in ACCEPTED (with a reason) "
                  f"or PENDING (with what must be decided)")
        for key in stale:
            print(f"STALE REGISTRY ENTRY: {key} -- no longer a gap; remove it")
        if unregistered or stale:
            return 1
        print(f"OK: {len(gaps)} gaps, all registered")
        return 0

    by_kind: dict[str, list[tuple[str, str]]] = {}
    for kind, key, detail in gaps:
        by_kind.setdefault(kind, []).append((key, detail))
    for kind in ("UNEXTRACTED_TABLE", "UNEXTRACTED_COLUMN", "UNLOADED_COLUMN"):
        entries = by_kind.get(kind, [])
        print(f"\n=== {kind} ({len(entries)}) ===")
        accepted = ACCEPTED_TABLES if kind == "UNEXTRACTED_TABLE" else ACCEPTED
        pending = PENDING_TABLES if kind == "UNEXTRACTED_TABLE" else PENDING
        for key, detail in entries:
            state = "ACCEPTED" if key in accepted else "PENDING " if key in pending else "UNREGISTERED"
            print(f"  [{state}] {key:52} {detail}")
    print(f"\ntotal gaps: {len(gaps)}   unregistered: {len(unregistered)}   stale: {len(stale)}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))

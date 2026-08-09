#!/usr/bin/env python3
"""Fail when a migration writes to a FORCE-ROW-LEVEL-SECURITY table without neutralizing RLS.

Why this exists: V35__weekly_rest_token_backfill.sql issued a bare
``UPDATE employee_schedules``. That table carries FORCE ROW LEVEL
SECURITY (rls/V34), and FORCE subjects the table *owner* to the policy
as well -- only a superuser or a BYPASSRLS role escapes it. The policy
resolves ``app.current_company_id``, which no migration sets, so it
evaluates to NULL for every row. Under any migration role that is not
superuser/BYPASSRLS the UPDATE matched zero rows and reported success:
a silent, environment-dependent no-op.

Nothing caught it. Flyway was happy, the SQL was valid, and the backend
test suite runs as Testcontainers' default Postgres user -- a superuser,
which bypasses RLS and made the migration appear to work. That is the
same masking effect ADR-0002 records from the PMR-07 spike, reached from
the migration side. V36 fixes the data; this check is what stops the
next backfill from repeating the mistake, in the same spirit as
check_flyway_versions.py.

A migration is considered safe for a forced table when it either wraps
its writes in ``ALTER TABLE <t> NO FORCE ROW LEVEL SECURITY`` /
``ALTER TABLE <t> FORCE ROW LEVEL SECURITY`` (the owner then bypasses
its own table's policy, and ALTER TABLE fails loudly for a role that
does not own the table), or sets ``app.current_company_id`` so the
policy resolves deliberately.

Stdlib only, matching the rest of scripts/ -- it must run on any machine
with Python and no network installs.

Usage:
    python3 scripts/check_rls_migration_safety.py              # check the repo
    python3 scripts/check_rls_migration_safety.py --self-test  # run self-tests
"""

from __future__ import annotations

import re
import sys
import tempfile
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
MIGRATION_ROOT = REPO_ROOT / "backend" / "src" / "main" / "resources" / "db" / "migration"

VERSIONED_MIGRATION = re.compile(r"^V(?P<version>[0-9][0-9._]*?)__.+\.sql$")

FORCE_RLS = re.compile(
    r"\bALTER\s+TABLE\s+(?:ONLY\s+)?(?P<table>\w+)\s+FORCE\s+ROW\s+LEVEL\s+SECURITY", re.IGNORECASE
)
NO_FORCE_RLS = re.compile(
    r"\bALTER\s+TABLE\s+(?:ONLY\s+)?(?P<table>\w+)\s+NO\s+FORCE\s+ROW\s+LEVEL\s+SECURITY", re.IGNORECASE
)
DML = re.compile(
    r"\b(?P<verb>UPDATE|DELETE\s+FROM|INSERT\s+INTO)\s+(?:ONLY\s+)?(?P<table>\w+)", re.IGNORECASE
)
TENANT_SCOPE_SET = re.compile(
    r"(?:set_config\s*\(\s*'app\.current_company_id'|SET\s+(?:LOCAL\s+)?app\.current_company_id)",
    re.IGNORECASE,
)

# Migrations that are already applied in real databases and therefore
# cannot be edited (Flyway validates checksums). Each entry must name the
# migration that supersedes it. Entries are verified: an allowlisted file
# that no longer exists, or no longer trips the check, fails this script
# so the list cannot rot into a blanket exemption.
KNOWN_UNSAFE = {
    "common/V35__weekly_rest_token_backfill.sql": (
        "immutable once applied; superseded by "
        "common/V36__weekly_rest_token_backfill_rls_safe.sql"
    ),
}


def strip_sql_comments(sql: str) -> str:
    """Drop -- line comments and /* */ block comments.

    Naive with respect to those sequences inside string literals; no
    migration in this repository contains one, and the failure mode is a
    false positive that a human reads, not a silent pass.
    """
    sql = re.sub(r"/\*.*?\*/", " ", sql, flags=re.DOTALL)
    return re.sub(r"--[^\n]*", "", sql)


def parse_version(name: str) -> tuple[int, ...] | None:
    match = VERSIONED_MIGRATION.match(name)
    if not match:
        return None
    parts = match.group("version").replace("_", ".").split(".")
    return tuple(int(part) for part in parts if part != "")


def migration_files(migration_root: Path) -> list[tuple[tuple[int, ...], Path, str]]:
    """Every versioned migration as (version, path, comment-stripped sql), version-ordered."""
    found = []
    for path in sorted(migration_root.rglob("V*.sql")):
        version = parse_version(path.name)
        if version is not None:
            found.append((version, path, strip_sql_comments(path.read_text(encoding="utf-8"))))
    return sorted(found, key=lambda entry: entry[0])


def scan(migration_root: Path) -> list[dict]:
    """Every write to a FORCE-RLS table that does not neutralize the policy."""
    files = migration_files(migration_root)

    # table -> (version, offset within that file) where FORCE was applied.
    forced_at: dict[str, tuple[tuple[int, ...], int]] = {}
    for version, _path, sql in files:
        for match in FORCE_RLS.finditer(sql):
            forced_at.setdefault(match.group("table").lower(), (version, match.start()))

    violations: list[dict] = []
    for version, path, sql in files:
        for dml in DML.finditer(sql):
            table = dml.group("table").lower()
            if table not in forced_at:
                continue
            forced_version, forced_offset = forced_at[table]
            # Writes before the table is forced are fine -- most seed data
            # lands in the same migration that creates the table.
            if forced_version > version:
                continue
            if forced_version == version and forced_offset > dml.start():
                continue
            if _neutralized(sql, table, dml.start()):
                continue
            violations.append(
                {
                    "path": path,
                    "table": table,
                    "verb": " ".join(dml.group("verb").split()).upper(),
                    "line": sql.count("\n", 0, dml.start()) + 1,
                }
            )
    return violations


def _neutralized(sql: str, table: str, dml_offset: int) -> bool:
    """True when the write is bracketed by NO FORCE/FORCE, or tenant-scoped."""
    if TENANT_SCOPE_SET.search(sql):
        return True
    released = any(
        match.group("table").lower() == table and match.start() < dml_offset
        for match in NO_FORCE_RLS.finditer(sql)
    )
    restored = any(
        match.group("table").lower() == table and match.start() > dml_offset
        for match in FORCE_RLS.finditer(sql)
    )
    return released and restored


def main() -> int:
    if not MIGRATION_ROOT.is_dir():
        print(f"FAIL migration root not found: {MIGRATION_ROOT}")
        return 1

    violations = scan(MIGRATION_ROOT)
    flagged = {v["path"].relative_to(MIGRATION_ROOT).as_posix() for v in violations}
    unexpected = [v for v in violations if v["path"].relative_to(MIGRATION_ROOT).as_posix() not in KNOWN_UNSAFE]
    stale = sorted(set(KNOWN_UNSAFE) - flagged)

    if unexpected:
        print("Migrations write to a FORCE ROW LEVEL SECURITY table without neutralizing the policy.")
        print("Under a non-superuser migration role these statements match ZERO rows and still succeed:")
        for violation in unexpected:
            relative = violation["path"].relative_to(REPO_ROOT)
            print(f"  {relative}:{violation['line']}  {violation['verb']} {violation['table']}")
        print(
            "\nFix: bracket the write with\n"
            "  ALTER TABLE <table> NO FORCE ROW LEVEL SECURITY;\n"
            "  ...\n"
            "  ALTER TABLE <table> FORCE ROW LEVEL SECURITY;\n"
            "or set app.current_company_id so the policy resolves deliberately.\n"
            "See common/V36__weekly_rest_token_backfill_rls_safe.sql for the precedent."
        )

    if stale:
        print("\nStale KNOWN_UNSAFE entries -- these no longer trip the check, so remove them:")
        for entry in stale:
            print(f"  {entry}")

    if unexpected or stale:
        return 1

    exempt = f" ({len(KNOWN_UNSAFE)} known-unsafe, superseded)" if KNOWN_UNSAFE else ""
    print(f"OK  no unguarded writes to FORCE-RLS tables{exempt}")
    return 0


def self_test() -> int:
    failures: list[str] = []

    def check(name: str, condition: bool) -> None:
        print(("OK  " if condition else "FAIL ") + name)
        if not condition:
            failures.append(name)

    force = "ALTER TABLE secrets FORCE ROW LEVEL SECURITY;"

    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        (root / "common").mkdir()
        (root / "rls").mkdir()
        (root / "rls" / "V2__force.sql").write_text(force, encoding="utf-8")

        def write(name: str, sql: str) -> None:
            (root / "common" / name).write_text(sql, encoding="utf-8")

        def tables_flagged() -> list[str]:
            return [f"{v['path'].name}:{v['table']}" for v in scan(root)]

        # Seed data in the migration that creates the table, before RLS is forced.
        write("V1__seed.sql", "INSERT INTO secrets VALUES (1);")
        check("write before the table is forced is allowed", tables_flagged() == [])

        write("V3__bare.sql", "UPDATE secrets SET a = 1 WHERE b = 2;")
        check("bare UPDATE after FORCE is flagged", tables_flagged() == ["V3__bare.sql:secrets"])

        write(
            "V3__bare.sql",
            "ALTER TABLE secrets NO FORCE ROW LEVEL SECURITY;\n"
            "UPDATE secrets SET a = 1;\n" + force,
        )
        check("NO FORCE / FORCE bracket is accepted", tables_flagged() == [])

        # Released but never restored: the table would be left unforced.
        write("V3__bare.sql", "ALTER TABLE secrets NO FORCE ROW LEVEL SECURITY;\nUPDATE secrets SET a = 1;")
        check("missing FORCE restore is flagged", tables_flagged() == ["V3__bare.sql:secrets"])

        write("V3__bare.sql", "SET LOCAL app.current_company_id = '7';\nUPDATE secrets SET a = 1;")
        check("tenant-scoped write is accepted", tables_flagged() == [])

        write("V3__bare.sql", "-- historical note: V2 ran UPDATE secrets and skipped every row\nSELECT 1;")
        check("DML named only in a comment is not flagged", tables_flagged() == [])

        write("V3__bare.sql", "DELETE FROM secrets WHERE a = 1;")
        check("DELETE is flagged too", tables_flagged() == ["V3__bare.sql:secrets"])

        write("V3__bare.sql", "UPDATE unforced_table SET a = 1;")
        check("write to a table without FORCE RLS is ignored", tables_flagged() == [])

        # Same file forces the table and then writes to it.
        write("V4__force_then_write.sql", "ALTER TABLE later FORCE ROW LEVEL SECURITY;\nUPDATE later SET a = 1;")
        check(
            "write after FORCE in the same file is flagged",
            "V4__force_then_write.sql:later" in tables_flagged(),
        )

        write("V4__force_then_write.sql", "UPDATE later SET a = 1;\nALTER TABLE later FORCE ROW LEVEL SECURITY;")
        check("write before FORCE in the same file is allowed", tables_flagged() == [])

    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(self_test() if "--self-test" in sys.argv else main())

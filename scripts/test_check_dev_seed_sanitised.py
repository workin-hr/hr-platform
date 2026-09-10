#!/usr/bin/env python3
"""Fixture-based regression tests for check_dev_seed_sanitised.py.

The gate decides whether a file derived from real customer data may be
committed permanently. A gate that has never been shown to fail is not
evidence, so most of what follows feeds it inputs written to be rejected.

    python3 scripts/test_check_dev_seed_sanitised.py
"""
from __future__ import annotations

import importlib.util
import os
import shutil
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))

spec = importlib.util.spec_from_file_location(
    "check_dev_seed_sanitised", os.path.join(HERE, "check_dev_seed_sanitised.py")
)
gate = importlib.util.module_from_spec(spec)
assert spec.loader is not None
spec.loader.exec_module(gate)

FAILURES: list[str] = []

# Assembled from segments rather than written out. It has to be genuinely
# JWT-shaped at runtime -- proving the gate detects one is the whole point of
# the case that uses it -- but a literal JWT in this file is itself flagged by
# the repository's secret scanner, which is a fair thing for a secret scanner
# to do. No single literal here carries the two dots the scanner matches on.
JWT_FIXTURE = ".".join(
    ["eyJhbGciOiJIUzI1NiJ9", "eyJzdWIiOiIxMjM0NTYifQ", "abcdefghijkl"]
)


def check(condition: bool, message: str) -> None:
    print(("OK  " if condition else "FAIL ") + message)
    if not condition:
        FAILURES.append(message)


# A minimal schema in the dump's own shape, so the column-derivation logic is
# exercised against the real grammar rather than a simplification.
SCHEMA = """
CREATE TABLE `employees` (
  `id` int(10) unsigned NOT NULL AUTO_INCREMENT,
  `first_name` varchar(255) NOT NULL,
  `phone` varchar(20) NOT NULL,
  `job_title_id` int(10) unsigned DEFAULT NULL,
  `notes` text DEFAULT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE `banners` (
  `id` int(10) unsigned NOT NULL AUTO_INCREMENT,
  `title_ar` varchar(500) NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
"""


def test_identity_columns_uses_the_type_not_only_the_name() -> None:
    found = gate.identity_columns(SCHEMA)
    check(
        "employees.first_name" in found and "employees.phone" in found,
        "textual identity columns are found",
    )
    check(
        "employees.job_title_id" not in found,
        "an integer foreign key named *_id is NOT treated as identity-bearing "
        "(this is what kept job_title_id and total_entitlements out)",
    )


def test_free_text_columns_are_found_regardless_of_name() -> None:
    found = gate.free_text_columns(SCHEMA)
    check(
        "employees.notes" in found,
        "a text column is found by TYPE, which is how assets.asset_text is caught "
        "despite a name that mentions no identity",
    )


def test_an_uncovered_identity_column_fails() -> None:
    findings: list[str] = []
    sanitiser = "-- covers: employees.phone, employees.notes\nUPDATE employees SET phone='x';"
    gate.check_column_coverage(SCHEMA, sanitiser, findings)
    check(
        any("employees.first_name" in f for f in findings),
        "a column the sanitiser does not declare is reported",
    )


def test_a_fully_covered_schema_passes() -> None:
    findings: list[str] = []
    sanitiser = (
        "-- covers: employees.first_name, employees.phone, employees.notes\n"
        "UPDATE employees SET first_name='x';"
    )
    # banners.title_ar is in the module's structural list.
    gate.check_column_coverage(SCHEMA, sanitiser, findings)
    check(not findings, f"a fully covered schema produces no findings (got {findings})")


def test_real_values_are_caught() -> None:
    cases = {
        "bcrypt hash": "INSERT INTO x VALUES ('$2y$10$abcdefghijklmnopqrstuvABCDEFGHIJKLMNOPQRSTUVWXYZ0123456');",
        "Egyptian mobile number": "INSERT INTO x VALUES ('01012345678');",
        "email address": "INSERT INTO x VALUES ('someone@gmail.com');",
        "JWT": f"INSERT INTO x VALUES ('{JWT_FIXTURE}');",
        "external upload URL": "INSERT INTO x VALUES ('https://workin.example.com/uploads/id-scan.pdf');",
    }
    for label, seed in cases.items():
        findings: list[str] = []
        gate.check_value_shapes(seed, findings)
        check(
            any(label in f for f in findings),
            f"a surviving {label} is caught",
        )


def test_the_gate_never_prints_the_value_it_found() -> None:
    findings: list[str] = []
    gate.check_value_shapes("INSERT INTO x VALUES ('01012345678');", findings)
    check(
        findings and all("01012345678" not in f for f in findings),
        "the finding names the shape and the count but NEVER the value -- this output "
        "goes to CI logs, and a gate that prints the PII it found has leaked it",
    )


def test_the_synthetic_phone_block_is_allowed() -> None:
    findings: list[str] = []
    # 010 then five zeros: format-valid for the application, dialable by nobody.
    gate.check_value_shapes("INSERT INTO x VALUES ('01000000042');", findings)
    check(not findings, f"the sanitiser's own phone block passes (got {findings})")


def test_a_near_miss_of_the_block_is_still_caught() -> None:
    findings: list[str] = []
    gate.check_value_shapes("INSERT INTO x VALUES ('01000100042');", findings)
    check(
        any("Egyptian mobile number" in f for f in findings),
        "a number one digit outside the synthetic block is still reported -- the "
        "allowance is the exact block, not anything that looks like it",
    )


def test_a_seed_missing_a_phase1_table_fails() -> None:
    findings: list[str] = []
    gate.check_seed_is_self_sufficient("CREATE TABLE `employees` (...);", findings)
    check(
        any("legacy_refresh_tokens" in f for f in findings),
        "a seed that does not create the Phase 1 tables is rejected -- it is the only "
        "init script the compose files mount, so it has to stand alone",
    )


def test_a_complete_seed_passes_self_sufficiency() -> None:
    findings: list[str] = []
    derived = gate.owned_tables(findings)
    check(not findings and len(derived) >= gate.MINIMUM_OWNED_TABLES,
          f"the table list itself derives cleanly ({len(derived)} tables, {findings})")
    complete = "".join(f"CREATE TABLE `{t}` (...);\n" for t in derived)
    gate.check_seed_is_self_sufficient(complete, findings)
    check(not findings, f"a seed with every Phase 1 table passes (got {findings})")


def test_a_folded_table_name_still_counts() -> None:
    """Phase1SchemaCheck compares case-insensitively because
    lower_case_table_names folds SPRING_SESSION on some hosts. A seed built on
    such a host must not fail a gate the application itself would accept."""
    findings: list[str] = []
    folded = "".join(f"CREATE TABLE `{t.lower()}` (...);\n" for t in gate.owned_tables([]))
    gate.check_seed_is_self_sufficient(folded, findings)
    check(not findings, f"a seed with folded table names passes (got {findings})")


def test_the_required_tables_come_from_phase1schemacheck() -> None:
    """The list is derived, not repeated.

    It was repeated once: the check named six tables, the application grew to
    owning fourteen, and the seed satisfied the gate while every stack seeded
    from it logged `8 of 14 owned tables are MISSING`.
    """
    tables = gate.owned_tables([])
    check(
        len(tables) >= 14 and "attendance_devices" in tables and "platform_admins" in tables,
        f"the required tables are read from Phase1SchemaCheck (got {len(tables)}: {tables})",
    )


def test_an_unreadable_owner_fails_rather_than_requiring_nothing() -> None:
    """If the source moves or its declaration changes shape, this check must
    say so -- not silently start requiring an empty list, which every seed
    satisfies."""
    findings: list[str] = []
    original = gate.PHASE1_SCHEMA_CHECK
    try:
        gate.PHASE1_SCHEMA_CHECK = "backend/src/main/java/com/workin/backend/config/NoSuchClass.java"
        tables = gate.owned_tables(findings)
    finally:
        gate.PHASE1_SCHEMA_CHECK = original
    check(
        tables == () and any("cannot be determined" in f for f in findings),
        f"a missing Phase1SchemaCheck fails loudly rather than requiring nothing "
        f"(tables={tables}, findings={findings})",
    )


def test_a_missing_sentinel_fails() -> None:
    findings: list[str] = []
    gate.check_sentinels("INSERT INTO x VALUES ('nothing to see');", findings)
    check(
        any("sentinel" in f for f in findings),
        "a seed without the sanitiser's marker is rejected, because nothing else "
        "the gate says about it would mean anything",
    )


def test_the_sentinel_is_a_real_bcrypt_hash_shape_and_is_not_self_reported() -> None:
    # The sentinel is bcrypt-shaped, so it must not itself trip the bcrypt rule.
    findings: list[str] = []
    gate.check_value_shapes(f"INSERT INTO x VALUES ('{gate.SENTINEL_PASSWORD_HASH}');", findings)
    check(
        not findings,
        "the sentinel hash does not trip the bcrypt rule it sits beside",
    )


def test_a_stale_structural_exception_is_reported() -> None:
    findings: list[str] = []
    original = dict(gate.STRUCTURAL_EXCEPTIONS)
    try:
        gate.STRUCTURAL_EXCEPTIONS["employees.no_such_column"] = "not a real column"
        gate.check_exception_lists_current(SCHEMA, findings)
        check(
            any("no_such_column" in f for f in findings),
            "an exception the schema no longer has is reported, so the list cannot "
            "outlive its reason",
        )
    finally:
        gate.STRUCTURAL_EXCEPTIONS.clear()
        gate.STRUCTURAL_EXCEPTIONS.update(original)


def test_the_real_repository_passes() -> None:
    schema = gate.read(gate.SCHEMA)
    sanitiser = gate.read(gate.SANITISER)
    check(schema is not None, "the vendored schema is present")
    check(sanitiser is not None, "deploy/seed/sanitise.sql is present")
    if schema is None or sanitiser is None:
        return
    findings: list[str] = []
    gate.check_column_coverage(schema, sanitiser, findings)
    gate.check_exception_lists_current(schema, findings)
    check(not findings, f"the real sanitiser covers the real schema (findings={findings})")

    seed = gate.read(gate.SEED)
    if seed is None:
        check(False, "deploy/seed/dev-seed.sql is present")
        return
    value_findings: list[str] = []
    gate.check_value_shapes(seed, value_findings)
    gate.check_sentinels(seed, value_findings)
    check(not value_findings, f"the committed seed is clean (findings={value_findings})")


def main() -> int:
    for name, function in sorted(globals().items()):
        if name.startswith("test_") and callable(function):
            function()
    print()
    if FAILURES:
        print(f"{len(FAILURES)} FAILURE(S)")
        return 1
    print("all dev-seed gate regression cases passed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

#!/usr/bin/env python3
"""Prove the port strips exactly the keys `sensitive_response_keys()` names.

This check exists because of a specific miss. `hr-legacy` `505004f` added
`ip` to `sensitive_response_keys()` -- the last-login address, a real column
on both `employees` and `companies`. The port kept the old
`password_hash, token_version` pair in FOUR independent copies, so an
unrestricted `SELECT * FROM employees` served every employee's last-login
address to any HR session. Nine tests asserted the stale pair by name and
agreed with all four copies, so the suite was green and blind.

The four copies are now one (`LegacyPublicRow.SENSITIVE_KEYS`). This check
holds that one list to PHP's, so the next key added upstream fails here
instead of shipping.

Like the sibling drift checks, this needs a `hr-legacy` checkout and so
cannot run in CI; `--self-test` needs nothing and does.

    hr-legacy apis/helpers/public_row.php   the contract
    hr-legacy apis/config/columns.php       resolves Column::* to strings
    LegacyPublicRow.java                    this port's copy

Usage:
    python3 scripts/check_legacy_sensitive_keys_drift.py
    python3 scripts/check_legacy_sensitive_keys_drift.py --legacy ../hr-legacy
    python3 scripts/check_legacy_sensitive_keys_drift.py --self-test
"""

from __future__ import annotations

import os
import re
import sys

JAVA = os.path.join(
    "backend", "src", "main", "java", "com", "workin", "legacy", "LegacyPublicRow.java"
)
PHP_FUNCTION = os.path.join("apis", "helpers", "public_row.php")
PHP_COLUMNS = os.path.join("apis", "config", "columns.php")

FUNCTION_BODY = re.compile(
    r"function\s+sensitive_response_keys\s*\(\s*\)\s*:\s*array\s*\{(.*?)\}", re.S
)
JAVA_LIST = re.compile(r"SENSITIVE_KEYS\s*=\s*List\.of\((.*?)\);", re.S)
STRING_LITERAL = re.compile(r'"([^"]*)"')


def php_column_constants(columns_source: str) -> dict[str, str]:
    """`public const PASSWORD_HASH = 'password_hash';` -> {PASSWORD_HASH: password_hash}."""
    return {
        name: value
        for name, value in re.findall(
            r"public\s+const\s+([A-Z0-9_]+)\s*=\s*'([^']*)'\s*;", columns_source
        )
    }


def php_keys(function_source: str, constants: dict[str, str]) -> list[str]:
    """The strings `sensitive_response_keys()` returns, in order.

    Entries are written `Column::PASSWORD_HASH`, so each is resolved through
    `columns.php`. An unresolvable name is returned verbatim and will fail the
    comparison loudly rather than being dropped -- a silently skipped key is
    the failure this check exists to prevent.
    """
    body = FUNCTION_BODY.search(function_source)
    if not body:
        raise ValueError("could not find sensitive_response_keys() in " + PHP_FUNCTION)
    keys = []
    for entry in re.findall(r"Column::([A-Z0-9_]+)|'([^']*)'", body.group(1)):
        constant, literal = entry
        keys.append(constants.get(constant, constant) if constant else literal)
    return keys


def java_keys(java_source: str) -> list[str]:
    listing = JAVA_LIST.search(java_source)
    if not listing:
        raise ValueError("could not find SENSITIVE_KEYS in " + JAVA)
    return STRING_LITERAL.findall(listing.group(1))


def compare(php: list[str], java: list[str]) -> list[str]:
    """Order matters only to PHP's `unset()`, which is order-free -- so compare
    as sets, and report each direction separately so the message says what to do."""
    failures = []
    missing = [key for key in php if key not in java]
    extra = [key for key in java if key not in php]
    if missing:
        failures.append(
            "in sensitive_response_keys(), missing from LegacyPublicRow: "
            + repr(missing)
            + " -- the port is serving a field PHP suppresses"
        )
    if extra:
        failures.append(
            "in LegacyPublicRow, not in sensitive_response_keys(): "
            + repr(extra)
            + " -- the port is withholding a field PHP returns"
        )
    return failures


def self_test() -> int:
    constants = php_column_constants(
        "public const PASSWORD_HASH = 'password_hash';\n"
        "public const TOKEN_VERSION = 'token_version';\n"
        "public const IP = 'ip';\n"
    )
    php = php_keys(
        "function sensitive_response_keys(): array {\n"
        "    return [Column::PASSWORD_HASH, Column::TOKEN_VERSION, Column::IP];\n"
        "}",
        constants,
    )
    assert php == ["password_hash", "token_version", "ip"], php

    java = java_keys('SENSITIVE_KEYS = List.of("password_hash", "token_version", "ip");')
    assert java == ["password_hash", "token_version", "ip"], java
    assert compare(php, java) == []

    stale = java_keys('SENSITIVE_KEYS = List.of("password_hash", "token_version");')
    failures = compare(php, stale)
    assert failures and "'ip'" in failures[0], failures
    assert "serving a field PHP suppresses" in failures[0]

    failures = compare(["password_hash"], ["password_hash", "ip"])
    assert failures and "withholding" in failures[0], failures

    # A literal entry, in case upstream ever stops using Column::.
    assert php_keys("function sensitive_response_keys(): array { return ['ip']; }", {}) == ["ip"]

    print("6/6 sensitive-key drift self-test cases passed.")
    return 0


def main(argv: list[str]) -> int:
    if "--self-test" in argv:
        return self_test()

    legacy = "../hr-legacy"
    if "--legacy" in argv:
        legacy = argv[argv.index("--legacy") + 1]

    for path in (JAVA, os.path.join(legacy, PHP_FUNCTION), os.path.join(legacy, PHP_COLUMNS)):
        if not os.path.exists(path):
            print("missing " + path, file=sys.stderr)
            return 2

    with open(os.path.join(legacy, PHP_COLUMNS), encoding="utf-8") as handle:
        constants = php_column_constants(handle.read())
    with open(os.path.join(legacy, PHP_FUNCTION), encoding="utf-8") as handle:
        php = php_keys(handle.read(), constants)
    with open(JAVA, encoding="utf-8") as handle:
        java = java_keys(handle.read())

    failures = compare(php, java)
    if failures:
        print("DRIFT between sensitive_response_keys() and LegacyPublicRow")
        for failure in failures:
            print("  " + failure)
        return 1

    print("sensitive-key parity OK (" + ", ".join(php) + ")")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))

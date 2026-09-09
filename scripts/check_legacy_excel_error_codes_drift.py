#!/usr/bin/env python3
"""Prove the port renders every employee-import error code PHP renders.

`employee_excel_error_message()` and `employee_excel_error_field_key()` are a
second message catalog, deliberately outside `t()` -- so
`check_legacy_message_drift.py`, which was added after `employees_updated`
shipped as a raw key on this very endpoint, does not see them.

Both sides end in a fall-through: PHP `default => $code`, Java
`default -> code` / `default -> null`. A code with no arm therefore does not
fail anywhere, it ships. hr-legacy `505004f` added four codes for the
bulk-update path and the port added none, so an Arabic-speaking HR user was
shown the bare token `nothing_to_update` and no cell was highlighted.

This compares the CODES each side handles, not the rendered text: the text is
interpolated and locale-specific, while a missing code is the whole failure
mode.

Needs a `hr-legacy` checkout, so like its siblings it cannot run in CI;
`--self-test` needs nothing and does.

Usage:
    python3 scripts/check_legacy_excel_error_codes_drift.py
    python3 scripts/check_legacy_excel_error_codes_drift.py --legacy ../hr-legacy
    python3 scripts/check_legacy_excel_error_codes_drift.py --self-test
"""

from __future__ import annotations

import os
import re
import sys

JAVA = os.path.join("backend", "src", "main", "java", "com", "workin", "legacy",
                    "employees", "spreadsheet", "LegacyEmployeeSpreadsheetErrors.java")
PHP = os.path.join("apis", "helpers", "employee_excel_helper.php")

PHP_FUNCTIONS = {
    "message": "employee_excel_error_message",
    "field_key": "employee_excel_error_field_key",
}
JAVA_METHODS = {"message": "message", "field_key": "fieldKey"}


def php_codes(source: str, function: str) -> set[str]:
    """The quoted keys of one function's `match ($code)` arms."""
    start = source.find("function " + function)
    if start < 0:
        raise ValueError("could not find " + function + "() in " + PHP)
    body = source[start:source.find("\n}", start)]
    # Arms look like `'code' =>` or `'a', 'b' =>`; `default =>` is the
    # fall-through and is deliberately not a code.
    codes = set()
    for arm in re.findall(r"^\s*((?:'[^']+'\s*,\s*)*'[^']+')\s*=>", body, re.M):
        codes.update(re.findall(r"'([^']+)'", arm))
    return codes


def java_codes(source: str, method: str) -> set[str]:
    """The quoted labels of one method's `switch` cases."""
    start = source.find("String " + method + "(")
    if start < 0:
        raise ValueError("could not find " + method + "() in " + JAVA)
    body = source[start:source.find("\n\t}", start)]
    codes = set()
    for arm in re.findall(r"case\s+((?:\"[^\"]+\"\s*,\s*)*\"[^\"]+\")\s*->", body):
        codes.update(re.findall(r'"([^"]+)"', arm))
    return codes


def compare(label: str, php: set[str], java: set[str]) -> list[str]:
    failures = []
    missing = sorted(php - java)
    if missing:
        failures.append(
            label + ": handled by PHP, missing from the port: " + repr(missing)
            + " -- these fall through and ship as a raw code"
        )
    extra = sorted(java - php)
    if extra:
        failures.append(label + ": handled by the port, unknown to PHP: " + repr(extra))
    return failures


def self_test() -> int:
    php_source = (
        "function employee_excel_error_message(string $code): string {\n"
        "    return match ($code) {\n"
        "        'first_name_required' => 'x',\n"
        "        'gender_invalid' => 'y',\n"
        "        'invalid_phone', 'invalid_phone_number' => 'z',\n"
        "        default => $code,\n"
        "    };\n"
        "}\n"
    )
    php = php_codes(php_source, "employee_excel_error_message")
    assert php == {"first_name_required", "gender_invalid", "invalid_phone",
                   "invalid_phone_number"}, php

    java_source = (
        '\tpublic static String message(String code, Context context) {\n'
        '\t\treturn switch (code) {\n'
        '\t\t\tcase "first_name_required" -> "x";\n'
        '\t\t\tcase "invalid_phone", "invalid_phone_number" -> "z";\n'
        '\t\t\tdefault -> code;\n'
        '\t\t};\n'
        '\t}\n'
    )
    java = java_codes(java_source, "message")
    assert java == {"first_name_required", "invalid_phone", "invalid_phone_number"}, java

    failures = compare("message", php, java)
    assert failures and "gender_invalid" in failures[0], failures
    assert "ship as a raw code" in failures[0]

    assert compare("message", php, php) == []
    failures = compare("message", set(), {"invented"})
    assert failures and "unknown to PHP" in failures[0], failures

    print("5/5 excel-error-code drift self-test cases passed.")
    return 0


def main(argv: list[str]) -> int:
    if "--self-test" in argv:
        return self_test()

    legacy = "../hr-legacy"
    if "--legacy" in argv:
        legacy = argv[argv.index("--legacy") + 1]

    php_path = os.path.join(legacy, PHP)
    for path in (JAVA, php_path):
        if not os.path.exists(path):
            print("missing " + path, file=sys.stderr)
            return 2

    with open(php_path, encoding="utf-8") as handle:
        php_source = handle.read()
    with open(JAVA, encoding="utf-8") as handle:
        java_source = handle.read()

    failures = []
    for label, php_function in PHP_FUNCTIONS.items():
        failures += compare(
            label,
            php_codes(php_source, php_function),
            java_codes(java_source, JAVA_METHODS[label]),
        )

    if failures:
        print("DRIFT in the employee-import error catalog")
        for failure in failures:
            print("  " + failure)
        return 1

    print("employee-import error catalog parity OK")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))

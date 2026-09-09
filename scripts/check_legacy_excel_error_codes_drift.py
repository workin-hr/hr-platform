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

This compares the **resolved mappings**, not just the set of codes handled.
Comparing code names alone would pass a port that answered `gender_invalid`
with the wrong column, or rendered the wrong sentence -- the code would be
present on both sides and the mapping still wrong.

So:

* `field_key` is compared as a full `code -> field` map, resolving PHP's
  `Request::` and `Column::` constants to their string values.
* `message` is compared as a `code -> text` map for every arm that is a plain
  literal on both sides. Arms that interpolate a value (`invalid_phone`,
  `employee_not_found`) cannot be compared as text and are reported as
  `compared by presence only`, so what this check does not cover is visible in
  its own output rather than assumed.

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

PHP_CONSTANT_FILES = (
    os.path.join("apis", "config", "request.php"),
    os.path.join("apis", "config", "columns.php"),
)
# An arm whose value is built from a variable rather than being a literal.
INTERPOLATED = object()


def php_constants(sources: list[str]) -> dict[str, str]:
    """`public const GENDER = \'gender\';` -> {GENDER: gender}, across every file."""
    constants: dict[str, str] = {}
    for source in sources:
        constants.update(re.findall(r"public\s+const\s+([A-Z0-9_]+)\s*=\s*'([^']*)'\s*;", source))
    return constants


def php_arms(source: str, function: str, constants: dict[str, str]) -> dict[str, object]:
    """One function's `match` arms as {code: value}.

    The value is the resolved string for a literal or a `Class::CONST`, and
    INTERPOLATED for anything built at runtime -- a ternary on a captured
    variable, a concatenation. Grouped keys (`'a', 'b' => x`) all take x.
    """
    start = source.find("function " + function)
    if start < 0:
        raise ValueError("could not find " + function + "() in " + PHP)
    body = source[start:source.find("\n}", start)]

    arms: dict[str, object] = {}
    # Keys, then the value up to the arm-terminating comma at end of line.
    pattern = re.compile(
        r"^\s*((?:'[^']+'\s*,\s*)*'[^']+')\s*=>\s*(.*?),\s*$", re.M | re.S)
    for keys, raw in pattern.findall(body):
        raw = raw.strip()
        if re.fullmatch(r"'([^']*)'", raw):
            value: object = raw[1:-1]
        elif re.fullmatch(r"(?:Request|Column)::([A-Z0-9_]+)", raw):
            name = raw.split("::")[1]
            value = constants.get(name, name)
        elif raw == "null":
            value = None
        else:
            value = INTERPOLATED
        for code in re.findall(r"'([^']+)'", keys):
            arms[code] = value
    return arms


def java_arms(source: str, method: str) -> dict[str, object]:
    """One method's `switch` arms as {code: value}, same convention."""
    start = source.find("String " + method + "(")
    if start < 0:
        raise ValueError("could not find " + method + "() in " + JAVA)
    body = source[start:source.find("\n\t}", start)]

    arms: dict[str, object] = {}
    pattern = re.compile(
        r"case\s+((?:\"[^\"]+\"\s*,\s*)*\"[^\"]+\")\s*->\s*(.*?);", re.S)
    for keys, raw in pattern.findall(body):
        raw = raw.strip()
        if re.fullmatch(r'"((?:[^"\\\\]|\\\\.)*)"', raw):
            value: object = raw[1:-1]
        elif raw == "null":
            value = None
        else:
            value = INTERPOLATED
        for code in re.findall(r'"([^"]+)"', keys):
            arms[code] = value
    return arms


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


def compare(label: str, php: dict, java: dict) -> tuple[list[str], int]:
    """Failures, plus how many arms could only be checked for presence."""
    failures = []

    missing = sorted(set(php) - set(java))
    if missing:
        failures.append(
            label + ": handled by PHP, missing from the port: " + repr(missing)
            + " -- these fall through and ship as a raw code"
        )
    extra = sorted(set(java) - set(php))
    if extra:
        failures.append(label + ": handled by the port, unknown to PHP: " + repr(extra))

    presence_only = 0
    for code in sorted(set(php) & set(java)):
        expected, actual = php[code], java[code]
        # An interpolated arm on either side cannot be compared as text. Its
        # presence is still checked above; only the wording is out of reach.
        if expected is INTERPOLATED or actual is INTERPOLATED:
            presence_only += 1
            continue
        if expected != actual:
            failures.append(
                label + ": " + repr(code) + " maps to " + repr(actual)
                + " in the port but " + repr(expected) + " in PHP"
            )
    return failures, presence_only


def self_test() -> int:
    constants = php_constants([
        "class Request { public const GENDER = 'gender';"
        " public const EMPLOYEE_CODE = 'employee_code'; }",
        "class Column { public const EXPECTED_DAILY_HOURS = 'expected_daily_hours'; }",
    ])

    php_source = (
        "function employee_excel_error_field_key(string $code): ?string {\n"
        "    return match ($code) {\n"
        "        'first_name_required' => 'first_name',\n"
        "        'gender_invalid' => Request::GENDER,\n"
        "        'employee_not_found' => Request::EMPLOYEE_CODE,\n"
        "        'expected_daily_hours_required' => Column::EXPECTED_DAILY_HOURS,\n"
        "        'shift_required',\n"
        "        'shift_not_found' => 'shift_name',\n"
        "        default => null,\n"
        "    };\n"
        "}\n"
    )
    php = php_arms(php_source, "employee_excel_error_field_key", constants)
    assert php == {
        "first_name_required": "first_name",
        "gender_invalid": "gender",
        "employee_not_found": "employee_code",
        "expected_daily_hours_required": "expected_daily_hours",
        "shift_required": "shift_name",
        "shift_not_found": "shift_name",
    }, php

    java_source = (
        '\tpublic static String fieldKey(String code) {\n'
        '\t\treturn switch (code) {\n'
        '\t\t\tcase "first_name_required" -> "first_name";\n'
        '\t\t\tcase "gender_invalid" -> "gender";\n'
        '\t\t\tcase "employee_not_found" -> "employee_code";\n'
        '\t\t\tcase "expected_daily_hours_required" -> "expected_daily_hours";\n'
        '\t\t\tcase "shift_required", "shift_not_found" -> "shift_name";\n'
        '\t\t\tdefault -> null;\n'
        '\t\t};\n'
        '\t}\n'
    )
    java = java_arms(java_source, "fieldKey")
    assert java == php, java

    failures, presence_only = compare("field_key", php, java)
    assert failures == [] and presence_only == 0, (failures, presence_only)

    # A code present on both sides but pointing at the wrong column: the exact
    # class of defect a code-set comparison cannot see.
    wrong = dict(java, gender_invalid="employee_code")
    failures, _ = compare("field_key", php, wrong)
    assert len(failures) == 1 and "maps to 'employee_code'" in failures[0], failures

    # A missing arm still reports as a fall-through.
    short = {k: v for k, v in java.items() if k != "gender_invalid"}
    failures, _ = compare("field_key", php, short)
    assert failures and "ship as a raw code" in failures[0], failures

    # An interpolated arm is counted, not compared, and not a failure.
    interp_php = {"invalid_phone": INTERPOLATED}
    interp_java = {"invalid_phone": "any wording at all"}
    failures, presence_only = compare("message", interp_php, interp_java)
    assert failures == [] and presence_only == 1, (failures, presence_only)

    print("6/6 excel-error-code drift self-test cases passed.")
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

    constants = php_constants([
        open(os.path.join(legacy, path), encoding="utf-8").read()
        for path in PHP_CONSTANT_FILES
        if os.path.exists(os.path.join(legacy, path))
    ])

    failures = []
    notes = []
    for label, php_function in PHP_FUNCTIONS.items():
        php_map = php_arms(php_source, php_function, constants)
        java_map = java_arms(java_source, JAVA_METHODS[label])
        found, presence_only = compare(label, php_map, java_map)
        failures += found
        notes.append(
            "  " + label + ": " + str(len(php_map)) + " arms, "
            + str(len(php_map) - presence_only) + " compared by value, "
            + str(presence_only) + " by presence only (interpolated)"
        )

    if failures:
        print("DRIFT in the employee-import error catalog")
        for failure in failures:
            print("  " + failure)
        return 1

    print("employee-import error catalog parity OK")
    for note in notes:
        print(note)
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))

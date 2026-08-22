#!/usr/bin/env python3
"""Prove the vendored employee spreadsheet column table still matches hr-legacy's.

`employee_excel_columns_meta()`
(`hr-legacy/apis/helpers/employee_excel_helper.php`) decides which uploaded
spreadsheets the employee import accepts and which column each header lands
in: 28 entries of keys, required flags, Arabic and English labels, salary
groups and aliases, where the alias order is itself load-bearing because
`employee_excel_normalize_header_key()` returns the first match.

A hand transcription of that table has no oracle. So Phase 1 vendors it as
`backend/src/main/resources/legacy/spreadsheet/employee_columns.json`,
exported by a real PHP 8.3 CLI (`docker run --rm php:8.3-cli`), the same
arrangement `check_legacy_lang_drift.py` gives the message catalogs. This
script re-runs that export and compares, so a label reworded or an alias
added upstream fails here instead of silently changing which files import.

Like the lang and schema checks, the comparison needs a sibling `hr-legacy`
checkout and a working Docker, so it cannot run in CI. `--self-test` needs
neither and does run there, keeping the rendering and comparison logic
itself proven.

Usage:
    python3 scripts/check_legacy_spreadsheet_columns_drift.py
    python3 scripts/check_legacy_spreadsheet_columns_drift.py --legacy ../hr-legacy
    python3 scripts/check_legacy_spreadsheet_columns_drift.py --write
    python3 scripts/check_legacy_spreadsheet_columns_drift.py --self-test
"""

from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys

VENDORED_PATH = os.path.join(
    "backend", "src", "main", "resources", "legacy", "spreadsheet", "employee_columns.json"
)

# The helper reads Request::/Column:: constants at call time, so the config
# files have to be loaded before it. constants.php is not in the repository --
# constants.example.php stands in, and nothing in the metadata depends on it.
EXPORT_SCRIPT = r"""
cp -r /legacy-ro /legacy
cp /legacy/apis/config/constants.example.php /legacy/apis/config/constants.php
php -r '
require_once "/legacy/apis/config/columns.php";
require_once "/legacy/apis/config/request.php";
require_once "/legacy/apis/config/tables.php";
require_once "/legacy/apis/config/response.php";
require_once "/legacy/apis/config/enums.php";
require_once "/legacy/apis/config/http_api.php";
require_once "/legacy/apis/helpers/employee_excel_helper.php";
echo json_encode(employee_excel_columns_meta(), JSON_UNESCAPED_UNICODE|JSON_UNESCAPED_SLASHES);
'
"""


def render(columns: list) -> str:
    """Pretty JSON, key order as PHP emits it, so a diff reads column by column."""
    return json.dumps(columns, ensure_ascii=False, indent=4) + "\n"


def read_upstream(legacy_root: str) -> list:
    """Let PHP itself evaluate the table -- no PHP parser lives here."""
    root = os.path.abspath(legacy_root)
    if not os.path.isdir(os.path.join(root, "apis", "helpers")):
        raise SystemExit("no legacy helpers directory under %s" % root)
    try:
        completed = subprocess.run(
            ["docker", "run", "--rm", "-v", root + ":/legacy-ro:ro", "php:8.3-cli", "sh", "-c", EXPORT_SCRIPT],
            capture_output=True,
            check=True,
        )
    except FileNotFoundError:
        raise SystemExit("docker is not available; this check needs it to run the real PHP CLI")
    except subprocess.CalledProcessError as error:
        raise SystemExit(
            "PHP could not read the column metadata: %s" % error.stderr.decode("utf-8", "replace")
        )
    return json.loads(completed.stdout.decode("utf-8"))


def check(legacy_root: str, write: bool, vendored_path: str = VENDORED_PATH, read=None) -> int:
    """Compare (or regenerate) the vendored table.

    `vendored_path` and `read` are injection points for
    `test_check_legacy_spreadsheet_columns_drift.py`: the tests drive a real
    file in a temporary directory and a stub upstream reader, so the
    comparison, the write path and the reporting are all covered without
    Docker or a sibling hr-legacy checkout.
    """
    read = read if read is not None else read_upstream
    expected = render(read(legacy_root))
    if write:
        with open(vendored_path, "w", encoding="utf-8", newline="\n") as handle:
            handle.write(expected)
        print("WROTE %s" % vendored_path)
        return 0
    if not os.path.isfile(vendored_path):
        print("MISSING: %s does not exist; re-run with --write" % vendored_path)
        return 1
    with open(vendored_path, encoding="utf-8") as handle:
        actual = handle.read()
    if actual == expected:
        print("OK: %s matches employee_excel_columns_meta()" % vendored_path)
        return 0
    print("DRIFT: %s no longer matches employee_excel_columns_meta()" % vendored_path)
    for line in describe(actual, expected):
        print("  " + line)
    print("  re-run with --write once the change is intended, and update")
    print("  LegacyEmployeeSpreadsheetColumnsTest's frozen table to match")
    return 1


def describe(actual: str, expected: str) -> list:
    """Name the column that moved, then the first differing line."""
    report = []
    try:
        mine = json.loads(actual)
        theirs = json.loads(expected)
    except json.JSONDecodeError:
        mine = theirs = None
    if isinstance(mine, list) and isinstance(theirs, list):
        my_keys = [column.get("key") for column in mine]
        their_keys = [column.get("key") for column in theirs]
        if my_keys != their_keys:
            report.append("column keys changed")
            report.append("  vendored: %s" % ", ".join(str(key) for key in my_keys))
            report.append("  upstream: %s" % ", ".join(str(key) for key in their_keys))
        else:
            for index, (a, b) in enumerate(zip(mine, theirs)):
                if a != b:
                    report.append("column %d (%s) differs" % (index, a.get("key")))
                    for field in sorted(set(a) | set(b)):
                        if a.get(field) != b.get(field):
                            report.append("  %s vendored=%r upstream=%r" % (field, a.get(field), b.get(field)))
                    break
    report.extend(first_difference(actual, expected))
    return report


def first_difference(actual: str, expected: str) -> list:
    """Report the first differing line, which is what somebody has to act on."""
    actual_lines = actual.splitlines()
    expected_lines = expected.splitlines()
    for index in range(max(len(actual_lines), len(expected_lines))):
        mine = actual_lines[index] if index < len(actual_lines) else "<missing>"
        theirs = expected_lines[index] if index < len(expected_lines) else "<missing>"
        if mine != theirs:
            return ["line %d" % (index + 1), "  vendored: %s" % mine, "  upstream: %s" % theirs]
    return []


def self_test() -> int:
    """The rendering and the reporting, which is the only logic this file owns."""
    failures = []

    def expect(label, actual, wanted):
        if actual != wanted:
            failures.append("%s: got %r, wanted %r" % (label, actual, wanted))
            print("FAIL %s" % label)
        else:
            print("OK  %s" % label)

    sample = [{"key": "phone", "required": False, "label_ar": "الهاتف", "aliases": ["phone", "mobile"]}]
    rendered = render(sample)
    expect("Arabic is not escaped", "الهاتف" in rendered, True)
    expect("the render ends in a newline", rendered.endswith("\n"), True)
    expect("the render round-trips", json.loads(rendered), sample)

    reordered = [{"key": "phone", "required": False, "label_ar": "الهاتف", "aliases": ["mobile", "phone"]}]
    expect("an alias reorder is a difference", render(reordered) == rendered, False)
    expect("an alias reorder names its column",
           describe(render(reordered), rendered)[0], "column 0 (phone) differs")
    expect("a key change is reported as such",
           describe(render([{"key": "mobile"}]), render([{"key": "phone"}]))[0], "column keys changed")
    expect("identical input has no first difference", first_difference(rendered, rendered), [])
    return 1 if failures else 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--legacy", default=os.path.join("..", "hr-legacy"))
    parser.add_argument("--write", action="store_true", help="regenerate the vendored table")
    parser.add_argument("--self-test", action="store_true", help="prove the logic without hr-legacy or Docker")
    args = parser.parse_args()
    if args.self_test:
        return self_test()
    return check(args.legacy, args.write)


if __name__ == "__main__":
    sys.exit(main())

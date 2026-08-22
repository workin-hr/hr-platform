#!/usr/bin/env python3
"""Deterministic regression tests for check_legacy_spreadsheet_columns_drift.py.

Run directly: `python3 scripts/test_check_legacy_spreadsheet_columns_drift.py`.
Exits 0 on success, 1 on any failure, printing every case so a broken guard
is easy to diagnose.

Fixture-based, and deliberately hermetic: no Docker, no PHP, and no sibling
`hr-legacy` checkout. Every case drives the real `check()` over a real file
in a temporary directory, with a stub in place of the one function that
shells out (`read_upstream`).

What the cases are really protecting is narrow but consequential: the
vendored table decides which uploaded spreadsheets the employee import
accepts and which column each header lands in. An alias added, reordered or
dropped upstream changes that silently, so each of those has to be a
failure here -- alias *order* especially, since
`employee_excel_normalize_header_key()` returns the first column that
matches.

Wired into: scripts/validate_phase0.py's script/test-sibling rule.
"""

from __future__ import annotations

import io
import json
import os
import sys
import tempfile
from contextlib import redirect_stdout
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import check_legacy_spreadsheet_columns_drift as drift  # noqa: E402

# Three columns standing in for the real 28. Small on purpose, but every
# shape the real table has is here: a required column, an optional one, a
# grouped one, Arabic labels, multi-line labels, and an alias list whose
# order decides a prefix match.
UPSTREAM = [
    {
        "key": "employee_code",
        "required": True,
        "label_ar": "كود الموظف (اجباري)\nأرقام فقط",
        "label_en": "Employee code (required)\nDigits only",
        "aliases": ["employee_code", "emp_code", "code", "كود الموظف"],
    },
    {
        "key": "phone",
        "required": False,
        "label_ar": "رقم التليفون (اختياري)",
        "label_en": "Phone (optional)",
        "aliases": ["phone", "mobile", "الهاتف"],
    },
    {
        "key": "salary_basic",
        "required": True,
        "label_ar": "الراتب الأساسي (اجباري)",
        "label_en": "Basic salary (required)",
        "group_ar": "استحقاقات",
        "group_en": "Entitlements",
        "aliases": ["salary_basic", "basic"],
    },
]

FAILURES: list[str] = []


def check_case(passed: bool, label: str) -> None:
    if passed:
        print("OK  %s" % label)
    else:
        FAILURES.append(label)
        print("FAIL %s" % label)


def reader(columns: list):
    """A stand-in for read_upstream() that never leaves the process."""

    def read(_legacy_root: str) -> list:
        return json.loads(json.dumps(columns))

    return read


def vendored(directory: Path, columns: list) -> str:
    path = directory / "employee_columns.json"
    path.write_text(drift.render(columns), encoding="utf-8", newline="\n")
    return str(path)


def run_check(path: str, columns: list) -> tuple[int, str]:
    buffer = io.StringIO()
    with redirect_stdout(buffer):
        code = drift.check("unused", False, vendored_path=path, read=reader(columns))
    return code, buffer.getvalue()


def mutated(change) -> list:
    columns = json.loads(json.dumps(UPSTREAM))
    change(columns)
    return columns


def test_an_identical_table_passes() -> None:
    with tempfile.TemporaryDirectory() as tmp:
        path = vendored(Path(tmp), UPSTREAM)
        code, output = run_check(path, UPSTREAM)
        check_case(code == 0, "an identical table passes (exit=%d)" % code)
        check_case("OK: " in output, "the passing run says so")


def test_a_reworded_arabic_label_fails() -> None:
    with tempfile.TemporaryDirectory() as tmp:
        path = vendored(Path(tmp), UPSTREAM)
        upstream = mutated(lambda columns: columns[1].__setitem__("label_ar", "رقم الموبايل (اختياري)"))
        code, output = run_check(path, upstream)
        check_case(code == 1, "a reworded Arabic label fails (exit=%d)" % code)
        check_case("column 1 (phone) differs" in output, "the failure names the column")
        check_case("label_ar" in output, "the failure names the field")


def test_a_dropped_alias_fails() -> None:
    with tempfile.TemporaryDirectory() as tmp:
        path = vendored(Path(tmp), UPSTREAM)
        upstream = mutated(lambda columns: columns[1]["aliases"].remove("mobile"))
        code, output = run_check(path, upstream)
        check_case(code == 1, "a dropped alias fails (exit=%d)" % code)
        check_case("aliases" in output, "the failure names the alias list")


def test_an_added_alias_fails() -> None:
    with tempfile.TemporaryDirectory() as tmp:
        path = vendored(Path(tmp), UPSTREAM)
        upstream = mutated(lambda columns: columns[0]["aliases"].append("staff_code"))
        code, output = run_check(path, upstream)
        check_case(code == 1, "an added alias fails (exit=%d)" % code)


def test_a_reordered_alias_list_fails() -> None:
    # The order decides which column a prefix match reaches first, so this is
    # a real behaviour change, not a formatting one.
    with tempfile.TemporaryDirectory() as tmp:
        path = vendored(Path(tmp), UPSTREAM)
        upstream = mutated(lambda columns: columns[1]["aliases"].reverse())
        code, output = run_check(path, upstream)
        check_case(code == 1, "a reordered alias list fails (exit=%d)" % code)
        check_case("column 1 (phone) differs" in output, "the reorder names its column")


def test_a_flipped_required_flag_fails() -> None:
    with tempfile.TemporaryDirectory() as tmp:
        path = vendored(Path(tmp), UPSTREAM)
        upstream = mutated(lambda columns: columns[0].__setitem__("required", False))
        code, output = run_check(path, upstream)
        check_case(code == 1, "a flipped required flag fails (exit=%d)" % code)
        check_case("required" in output, "the failure names the flag")


def test_a_changed_group_fails() -> None:
    with tempfile.TemporaryDirectory() as tmp:
        path = vendored(Path(tmp), UPSTREAM)
        upstream = mutated(lambda columns: columns[2].__setitem__("group_ar", "استقطاعات"))
        code, _ = run_check(path, upstream)
        check_case(code == 1, "a changed salary group fails (exit=%d)" % code)


def test_reordered_columns_fail_and_are_reported_as_such() -> None:
    with tempfile.TemporaryDirectory() as tmp:
        path = vendored(Path(tmp), UPSTREAM)
        upstream = mutated(lambda columns: columns.reverse())
        code, output = run_check(path, upstream)
        check_case(code == 1, "reordered columns fail (exit=%d)" % code)
        check_case("column keys changed" in output, "a reorder is reported as a key change")


def test_a_removed_column_fails() -> None:
    with tempfile.TemporaryDirectory() as tmp:
        path = vendored(Path(tmp), UPSTREAM)
        upstream = mutated(lambda columns: columns.pop(1))
        code, output = run_check(path, upstream)
        check_case(code == 1, "a removed column fails (exit=%d)" % code)
        check_case("column keys changed" in output, "the missing key is reported")


def test_a_missing_vendored_file_fails() -> None:
    with tempfile.TemporaryDirectory() as tmp:
        path = str(Path(tmp) / "employee_columns.json")
        code, output = run_check(path, UPSTREAM)
        check_case(code == 1, "a missing vendored file fails (exit=%d)" % code)
        check_case("MISSING" in output and "--write" in output, "the failure says how to fix it")


def test_write_regenerates_and_then_passes() -> None:
    with tempfile.TemporaryDirectory() as tmp:
        path = str(Path(tmp) / "employee_columns.json")
        buffer = io.StringIO()
        with redirect_stdout(buffer):
            code = drift.check("unused", True, vendored_path=path, read=reader(UPSTREAM))
        check_case(code == 0 and "WROTE" in buffer.getvalue(), "--write regenerates the table")
        code, _ = run_check(path, UPSTREAM)
        check_case(code == 0, "the regenerated table then passes (exit=%d)" % code)


def test_the_render_keeps_arabic_readable_and_json_valid() -> None:
    rendered = drift.render(UPSTREAM)
    check_case("كود الموظف" in rendered, "Arabic is not \\u-escaped, so diffs stay readable")
    check_case(json.loads(rendered) == UPSTREAM, "the render round-trips through json.loads")
    check_case(rendered.endswith("\n"), "the render ends in a newline")


def test_the_scripts_own_self_test_still_passes() -> None:
    buffer = io.StringIO()
    with redirect_stdout(buffer):
        code = drift.self_test()
    check_case(code == 0, "--self-test passes (exit=%d)" % code)


def test_the_real_vendored_table_is_well_formed() -> None:
    """The file the application actually loads, checked without Docker."""
    path = Path(__file__).resolve().parent.parent / drift.VENDORED_PATH
    if not path.is_file():
        check_case(False, "the vendored table exists at %s" % drift.VENDORED_PATH)
        return
    columns = json.loads(path.read_text(encoding="utf-8"))
    check_case(len(columns) == 28, "the vendored table has 28 columns (%d)" % len(columns))
    keys = [column["key"] for column in columns]
    check_case(len(set(keys)) == len(keys), "the column keys are unique")
    check_case(all(column.get("aliases") for column in columns), "every column has at least one alias")
    check_case(all(isinstance(column["required"], bool) for column in columns),
               "every required flag is a boolean")
    check_case(sum(1 for column in columns if column.get("group_ar") == "استحقاقات") == 5,
               "five columns are entitlements")
    check_case(sum(1 for column in columns if column.get("group_ar") == "استقطاعات") == 5,
               "five columns are deductions")
    # The collision LegacyEmployeeSpreadsheetColumns preserves depends on
    # `mobile` being an alias of `phone`, and on phone coming first.
    phone = columns[keys.index("phone")]
    check_case("mobile" in phone["aliases"], "phone still carries the `mobile` alias")
    check_case(keys.index("phone") < keys.index("is_mobile_attendance_enabled"),
               "phone still precedes is_mobile_attendance_enabled")
    check_case(path.read_text(encoding="utf-8") == drift.render(columns),
               "the vendored file is in the shape --write produces")


def main() -> int:
    for name, case in sorted(globals().items()):
        if name.startswith("test_") and callable(case):
            case()
    print()
    if FAILURES:
        print("%d FAILURE(S): %s" % (len(FAILURES), "; ".join(FAILURES)))
        return 1
    print("check_legacy_spreadsheet_columns_drift regression cases all passed.")
    return 0


if __name__ == "__main__":
    sys.exit(main())

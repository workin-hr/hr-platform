#!/usr/bin/env python3
"""Deterministic regression tests for scripts/check_legacy_lang_drift.py.

Run directly: `python3 scripts/test_check_legacy_lang_drift.py`. Exits 0
on success, 1 on any failure, printing every case so a broken guard is
easy to diagnose.

Fixture-based, and deliberately hermetic: no Docker, no PHP, and no
sibling `hr-legacy` checkout. Every case drives the real `check()` over
real files in a temporary directory, with a stub in place of the one
function that shells out (`read_upstream`). That is what makes the
comparison, the `--write` path and the drift reporting testable in CI,
where the real end-to-end conversion cannot run.

The escaping cases matter beyond formatting: these files are read back by
`java.util.Properties` at runtime (`LegacyMessages`), so a mis-escaped
backslash, newline or leading space would change the `message` clients
receive -- part of the wire contract D-074 makes authoritative.

Wired into: scripts/validate_phase0.py's script/test-sibling rule.
"""

from __future__ import annotations

import io
import os
import sys
import tempfile
from contextlib import redirect_stdout
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import check_legacy_lang_drift as drift  # noqa: E402

# One catalog per locale, standing in for hr-legacy/apis/lang/{en,ar}.php.
# Small on purpose: every value here exercises something the real
# catalogs contain -- a placeholder, an em dash, an apostrophe, Arabic.
UPSTREAM = {
    "en": {
        "ok": "ok",
        "field_required": "Field '{field}' is required",
        "unauthorized_no_token": "Unauthorized — no token",
    },
    "ar": {
        "ok": "تم",
        "field_required": "الحقل '{field}' مطلوب",
        "unauthorized_no_token": "غير مصرّح — لا يوجد رمز",
    },
}

FAILURES: list[str] = []


def check_case(passed: bool, label: str) -> None:
    if passed:
        print("OK  %s" % label)
    else:
        FAILURES.append(label)
        print("FAIL %s" % label)


def reader(catalogs: dict):
    """A stand-in for read_upstream() that never leaves the process."""

    def read(_legacy_root: str, name: str) -> dict:
        return dict(catalogs[name])

    return read


def unescape(text: str) -> str:
    """The `java.util.Properties` reader's side of the escaping.

    Written here rather than imported so the test proves a round trip
    against an independent implementation, not against the same helper it
    is testing.
    """
    out = []
    index = 0
    while index < len(text):
        char = text[index]
        if char == "\\" and index + 1 < len(text):
            following = text[index + 1]
            out.append({"n": "\n", "r": "\r", "t": "\t"}.get(following, following))
            index += 2
            continue
        out.append(char)
        index += 1
    return "".join(out)


def split_on_first_unescaped_separator(line: str) -> tuple[str, str]:
    """Java splits on the first unescaped `=` or `:`, not the first `=`."""
    index = 0
    while index < len(line):
        char = line[index]
        if char == "\\":
            index += 2
            continue
        if char in "=:":
            return line[:index], line[index + 1:]
        index += 1
    return line, ""


def parse_properties(text: str) -> dict:
    """Minimal Properties reader: enough to prove what Java will see."""
    parsed = {}
    for line in text.splitlines():
        if not line or line.startswith("#"):
            continue
        raw_key, raw_value = split_on_first_unescaped_separator(line)
        parsed[unescape(raw_key)] = unescape(raw_value)
    return parsed


def write_vendored(directory: Path, catalogs: dict) -> None:
    for name, catalog in catalogs.items():
        (directory / (name + ".properties")).write_text(
            drift.render(name, catalog), encoding="utf-8", newline="\n"
        )


def run_check(directory: Path, catalogs: dict) -> tuple[int, str]:
    buffer = io.StringIO()
    with redirect_stdout(buffer):
        code = drift.check("unused", False, vendored_dir=str(directory), read=reader(catalogs))
    return code, buffer.getvalue()


def test_identical_catalog_passes() -> None:
    with tempfile.TemporaryDirectory() as tmp:
        directory = Path(tmp)
        write_vendored(directory, UPSTREAM)
        code, output = run_check(directory, UPSTREAM)
        check_case(code == 0, "an identical generated catalog passes (exit=%d)" % code)
        check_case(output.count("OK: ") == 2, "both catalogs are reported OK")


def test_changed_translation_fails() -> None:
    with tempfile.TemporaryDirectory() as tmp:
        directory = Path(tmp)
        write_vendored(directory, UPSTREAM)
        upstream = {"en": dict(UPSTREAM["en"]), "ar": dict(UPSTREAM["ar"])}
        upstream["en"]["field_required"] = "Field '{field}' is now mandatory"
        code, output = run_check(directory, upstream)
        check_case(code == 1, "a reworded translation fails (exit=%d)" % code)
        check_case("DRIFT" in output, "the failure says DRIFT")
        check_case("is now mandatory" in output, "the report shows the upstream text")


def test_added_key_fails() -> None:
    with tempfile.TemporaryDirectory() as tmp:
        directory = Path(tmp)
        write_vendored(directory, UPSTREAM)
        upstream = {"en": dict(UPSTREAM["en"], employee_not_found="Employee not found"), "ar": UPSTREAM["ar"]}
        code, output = run_check(directory, upstream)
        check_case(code == 1, "an added upstream key fails (exit=%d)" % code)
        check_case("employee_not_found" in output, "the report names the added key")


def test_removed_key_fails() -> None:
    with tempfile.TemporaryDirectory() as tmp:
        directory = Path(tmp)
        write_vendored(directory, UPSTREAM)
        upstream = {"en": {k: v for k, v in UPSTREAM["en"].items() if k != "ok"}, "ar": UPSTREAM["ar"]}
        code, output = run_check(directory, upstream)
        check_case(code == 1, "a removed upstream key fails (exit=%d)" % code)


def test_missing_vendored_file_fails() -> None:
    with tempfile.TemporaryDirectory() as tmp:
        directory = Path(tmp)
        write_vendored(directory, {"en": UPSTREAM["en"]})
        code, output = run_check(directory, UPSTREAM)
        check_case(code == 1, "a vendored catalog that does not exist fails (exit=%d)" % code)
        check_case("MISSING" in output, "the failure says MISSING rather than crashing")


def test_write_regenerates_and_then_passes() -> None:
    with tempfile.TemporaryDirectory() as tmp:
        directory = Path(tmp)
        buffer = io.StringIO()
        with redirect_stdout(buffer):
            drift.check("unused", True, vendored_dir=str(directory), read=reader(UPSTREAM))
        check_case(buffer.getvalue().count("WROTE") == 2, "--write regenerates every catalog")
        code, _ = run_check(directory, UPSTREAM)
        check_case(code == 0, "a regenerated catalog then passes (exit=%d)" % code)


def test_escaping_round_trips_through_a_properties_reader() -> None:
    catalog = {
        "backslash": "a\\b",
        "newline": "one\ntwo",
        "carriage": "one\rtwo",
        "tab": "one\ttwo",
        "leading_space": " lead",
        "inner_space": "two words",
        "placeholder": "Field '{field}' is required",
        "arabic": "الموظفون",
        "em_dash": "Unauthorized — no token",
        "equals_in_value": "a=b",
        "colon_in_value": "a:b",
    }
    rendered = drift.render("en", catalog)
    check_case("\\n" in rendered and "one\ntwo\n" not in rendered.replace("one\\ntwo", ""),
               "a newline is written as an escape, not a real line break")
    check_case("=a\\\\b" in rendered, "a backslash is doubled in the file")
    check_case("=\\ lead" in rendered, "a leading space is escaped")
    check_case("=two words" in rendered, "an inner space is left alone")
    check_case("الموظفون" in rendered, "Arabic is written as UTF-8, not escaped")
    check_case(parse_properties(rendered) == catalog,
               "every value round-trips through a Properties-style reader")


def test_key_escaping_keeps_separators_out_of_keys() -> None:
    rendered = drift.render("en", {"a=b": "one", "c:d": "two", "e f": "three", "#g": "four"})
    check_case("a\\=b=one" in rendered, "an = in a key is escaped")
    check_case("c\\:d=two" in rendered, "a : in a key is escaped")
    check_case("e\\ f=three" in rendered, "a space in a key is escaped")
    check_case("\\#g=four" in rendered, "a leading # in a key is escaped, not read as a comment")
    check_case(parse_properties(rendered) == {"a=b": "one", "c:d": "two", "e f": "three", "#g": "four"},
               "escaped keys round-trip")


def test_first_difference_points_at_the_line() -> None:
    report = drift.first_difference("a=1\nb=2\nc=3", "a=1\nb=CHANGED\nc=3")
    check_case(report and report[0] == "line 2", "the first differing line is identified (%s)" % report[:1])
    check_case(any("b=2" in line for line in report), "the vendored side is shown")
    check_case(any("b=CHANGED" in line for line in report), "the upstream side is shown")

    shorter = drift.first_difference("a=1", "a=1\nb=2")
    check_case(any("<missing>" in line for line in shorter), "a truncated file reports <missing>")
    check_case(drift.first_difference("same", "same") == [], "identical text reports no difference")


def test_header_names_the_source_and_the_regeneration_command() -> None:
    rendered = drift.render("ar", {"ok": "تم"})
    check_case("hr-legacy/apis/lang/ar.php" in rendered, "the header names the upstream file")
    check_case("--write" in rendered, "the header names the regeneration command")
    check_case(rendered.splitlines()[0].startswith("#"), "the header is a comment a reader will skip")


def test_the_scripts_own_self_test_still_passes() -> None:
    buffer = io.StringIO()
    with redirect_stdout(buffer):
        code = drift.self_test()
    check_case(code == 0, "the script's --self-test passes (exit=%d)" % code)


def test_the_real_vendored_catalogs_are_well_formed() -> None:
    """Not a drift check -- that needs PHP -- but the runtime contract.

    `LegacyMessages` loads these files at startup, so a malformed one is a
    startup failure. Parsing them here keeps that provable in CI.
    """
    root = Path(__file__).resolve().parent.parent
    directory = root / "backend" / "src" / "main" / "resources" / "legacy" / "lang"
    for name in drift.CATALOGS:
        path = directory / (name + ".properties")
        if not path.is_file():
            check_case(False, "the vendored %s catalog exists" % name)
            continue
        parsed = parse_properties(path.read_text(encoding="utf-8"))
        check_case(len(parsed) > 100, "the vendored %s catalog parses (%d keys)" % (name, len(parsed)))
        check_case("field_required" in parsed, "the vendored %s catalog has field_required" % name)
    english = parse_properties((directory / "en.properties").read_text(encoding="utf-8"))
    check_case(english.get("field_required") == "Field '{field}' is required",
               "the placeholder survived vendoring intact")


def main() -> int:
    for name, case in sorted(globals().items()):
        if name.startswith("test_") and callable(case):
            case()
    print()
    if FAILURES:
        print("%d FAILURE(S): %s" % (len(FAILURES), "; ".join(FAILURES)))
        return 1
    print("check_legacy_lang_drift regression cases all passed.")
    return 0


if __name__ == "__main__":
    sys.exit(main())

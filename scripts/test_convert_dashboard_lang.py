#!/usr/bin/env python3
"""Deterministic regression tests for scripts/convert_dashboard_lang.py.

Run directly: `python3 scripts/test_convert_dashboard_lang.py`. Exits 0 on
success, 1 on any failure, printing every case.

Fixture-based and hermetic: no sibling `hr-legacy` checkout and no PHP.
Every case drives the real parser over real files in a temporary
directory.

The parsing cases are the point. The converter's failure mode is silent
under-collection -- a regex that misses a shape drops labels, and a
dropped label surfaces as a raw key in the admin UI rather than as an
error. That is why the converter refuses to write when any key-shaped
line goes unclaimed, and why the multi-line and trailing-comma shapes are
pinned here: both occur in the real lang.php, and the first draft of the
regex missed the second, losing 17 entries without saying so.

The escaping cases matter for the same reason as their counterparts in
test_check_legacy_lang_drift.py -- these files are read back by
java.util.Properties, so a mis-escaped newline or backslash changes what
renders.

Wired into: scripts/validate_phase0.py's script/test-sibling rule.
"""

from __future__ import annotations

import pathlib
import sys
import tempfile

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))

import convert_dashboard_lang as conv  # noqa: E402

FAILURES: list[str] = []


def check(name: str, condition: bool, detail: str = "") -> None:
    status = "OK " if condition else "FAIL"
    print(f"{status} {name}" + (f" ({detail})" if detail and not condition else ""))
    if not condition:
        FAILURES.append(name)


def test_single_line_entry() -> None:
    src = "'save' => ['ar' => 'حفظ', 'en' => 'Save'],"
    parsed = conv.parse(src)
    check("a single-line entry is parsed", parsed == {"save": ("حفظ", "Save")}, str(parsed))


def test_multi_line_entry_with_trailing_comma() -> None:
    """The shape that broke the first draft: value list split across lines,
    with a comma after the English value and before the closing bracket."""
    src = (
        "    'hint' => [\n"
        "        'ar' => 'نص عربي',\n"
        "        'en' => 'English text',\n"
        "    ],\n"
    )
    parsed = conv.parse(src)
    check("a multi-line entry with a trailing comma is parsed",
          parsed == {"hint": ("نص عربي", "English text")}, str(parsed))


def test_escaped_quote_survives() -> None:
    src = r"""'x' => ['ar' => 'a\'b', 'en' => 'c\'d'],"""
    parsed = conv.parse(src)
    check("an escaped single quote is unescaped, not truncated",
          parsed == {"x": ("a'b", "c'd")}, str(parsed))


def test_unparsed_reports_only_real_losses() -> None:
    """'ar' and 'en' are key-shaped; reporting them would fire on every run."""
    src = "'ok' => ['ar' => 'a', 'en' => 'b'],\n'broken' => ['ar' => 'only-arabic'],\n"
    parsed = conv.parse(src)
    missed = conv.unparsed(src, parsed)
    check("an entry the pattern cannot claim is reported", missed == ["broken"], str(missed))

    clean_src = "    'ok' => [\n        'ar' => 'a',\n        'en' => 'b',\n    ],\n"
    check("the inner 'ar'/'en' keys are not reported as losses",
          conv.unparsed(clean_src, conv.parse(clean_src)) == [], "false positive")


def test_properties_escaping() -> None:
    check("a newline becomes \\n", conv.escape_properties("a\nb") == "a\\nb")
    check("a backslash is doubled", conv.escape_properties("a\\b") == "a\\\\b")
    check("a carriage return is dropped", conv.escape_properties("a\r\nb") == "a\\nb")


def test_end_to_end_writes_both_bundles() -> None:
    with tempfile.TemporaryDirectory() as tmp:
        root = pathlib.Path(tmp)
        lang = root / "lang.php"
        lang.write_text(
            "<?php\n$GLOBALS['_lang'] = [\n"
            "    'save' => ['ar' => 'حفظ', 'en' => 'Save'],\n"
            "    'zzz'  => ['ar' => 'ي', 'en' => 'Y'],\n"
            "];\n",
            encoding="utf-8",
        )
        out = root / "i18n"
        out.mkdir()
        argv = sys.argv
        sys.argv = ["convert", "--lang-php", str(lang), "--out-dir", str(out)]
        try:
            code = conv.main()
        finally:
            sys.argv = argv

        english = (out / "admin-messages.properties").read_text(encoding="utf-8")
        arabic = (out / "admin-messages_ar.properties").read_text(encoding="utf-8")
        check("the converter exits 0", code == 0, str(code))
        check("English values land in the base bundle", "save=Save" in english)
        check("Arabic values land in the _ar bundle", "save=حفظ" in arabic)
        check("keys are sorted, so the diff is stable",
              english.index("save=") < english.index("zzz="))
        check("both bundles carry the same keys",
              sorted(l.split("=")[0] for l in english.splitlines() if "=" in l and not l.startswith("#"))
              == sorted(l.split("=")[0] for l in arabic.splitlines() if "=" in l and not l.startswith("#")))

        # --check must pass against what was just written, and fail once stale.
        sys.argv = ["convert", "--lang-php", str(lang), "--out-dir", str(out), "--check"]
        try:
            check("--check passes on freshly generated files", conv.main() == 0)
            (out / "admin-messages.properties").write_text("stale\n", encoding="utf-8")
            check("--check fails once a bundle is stale", conv.main() == 1)
        finally:
            sys.argv = argv


def test_refuses_to_write_when_an_entry_is_lost() -> None:
    with tempfile.TemporaryDirectory() as tmp:
        root = pathlib.Path(tmp)
        lang = root / "lang.php"
        lang.write_text("'ok' => ['ar' => 'a', 'en' => 'b'],\n'lost' => ['ar' => 'only'],\n",
                        encoding="utf-8")
        out = root / "i18n"
        out.mkdir()
        argv = sys.argv
        sys.argv = ["convert", "--lang-php", str(lang), "--out-dir", str(out)]
        try:
            code = conv.main()
        finally:
            sys.argv = argv
        check("an unparsable entry aborts the run", code == 2, str(code))
        check("nothing is written when an entry is lost",
              not (out / "admin-messages.properties").exists())


def main() -> int:
    test_single_line_entry()
    test_multi_line_entry_with_trailing_comma()
    test_escaped_quote_survives()
    test_unparsed_reports_only_real_losses()
    test_properties_escaping()
    test_end_to_end_writes_both_bundles()
    test_refuses_to_write_when_an_entry_is_lost()

    print()
    if FAILURES:
        print(f"{len(FAILURES)} FAILURE(S): {FAILURES}")
        return 1
    print("all convert_dashboard_lang cases passed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

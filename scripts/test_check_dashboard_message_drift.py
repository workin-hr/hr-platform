#!/usr/bin/env python3
"""Deterministic regression tests for scripts/check_dashboard_message_drift.py.

Run directly: `python3 scripts/test_check_dashboard_message_drift.py`.

Fixture-based and hermetic. The cases that carry weight are the ones the API
gate's own history supplied:

* the value shape the parser cannot read must be **fatal**, not silently
  absent -- that is how six messages were once reported as Java-only;
* a locale pair written in either order, since lang.php uses both;
* the empty-inventory case, because a check that passes when its input is
  missing is worse than no check.

Wired into: scripts/validate_phase0.py's script/test-sibling rule.
"""

from __future__ import annotations

import pathlib
import sys
import tempfile

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))

import check_dashboard_message_drift as drift  # noqa: E402

FAILURES: list[str] = []


def check(name: str, condition: bool, detail: str = "") -> None:
    print(f"{'OK ' if condition else 'FAIL'} {name}" + (f" ({detail})" if detail and not condition else ""))
    if not condition:
        FAILURES.append(name)


def build_php(root: pathlib.Path, entries: str) -> pathlib.Path:
    path = root / "lang.php"
    path.write_text("<?php\nreturn [\n" + entries + "];\n", encoding="utf-8")
    return path


def build_java(root: pathlib.Path, messages: dict[str, dict[str, str]]) -> pathlib.Path:
    lang = root / "i18n"
    lang.mkdir(parents=True, exist_ok=True)
    for locale, entries in messages.items():
        (lang / drift.JAVA_FILES[locale]).write_text(
            "".join(f"{key}={value}\n" for key, value in entries.items()), encoding="utf-8")
    return lang


def run(legacy: str, java: str, committed: str, refresh: bool = False) -> int:
    argv = sys.argv
    sys.argv = ["check_dashboard_message_drift.py",
                "--legacy-lang", legacy, "--java-lang", java, "--committed", committed]
    if refresh:
        sys.argv.append("--refresh")
    try:
        return drift.main()
    finally:
        sys.argv = argv


ONE = "    'nav_home' => ['ar' => 'الرئيسية', 'en' => 'Home'],\n"
JAVA_ONE = {"en": {"nav_home": "Home"}, "ar": {"nav_home": "الرئيسية"}}


def test_matching_catalogs_pass() -> None:
    with tempfile.TemporaryDirectory() as tmp:
        root = pathlib.Path(tmp)
        legacy = str(build_php(root, ONE))
        committed = str(root / "messages.txt")
        run(legacy, str(build_java(root, JAVA_ONE)), committed, refresh=True)
        check("a JTE catalog equal to lang.php passes",
              run(legacy, str(build_java(root, JAVA_ONE)), committed) == 0)


def test_either_locale_order_is_read() -> None:
    with tempfile.TemporaryDirectory() as tmp:
        root = pathlib.Path(tmp)
        legacy = str(build_php(root,
                               "    'a' => ['ar' => 'أ', 'en' => 'A'],\n"
                               "    'b' => ['en' => 'B', 'ar' => 'ب'],\n"))
        parsed = drift.php_messages(legacy)
        check("ar-first and en-first entries both parse",
              parsed["en"] == {"a": "A", "b": "B"} and parsed["ar"] == {"a": "أ", "b": "ب"},
              str(parsed))


def test_a_double_quoted_value_is_read_with_its_escape() -> None:
    with tempfile.TemporaryDirectory() as tmp:
        root = pathlib.Path(tmp)
        legacy = str(build_php(root, '    \'hint\' => [\'ar\' => "أ\\nب", \'en\' => "A\\nB"],\n'))
        parsed = drift.php_messages(legacy)
        check("a double-quoted value keeps its newline",
              parsed["en"].get("hint") == "A\nB", repr(parsed["en"].get("hint")))

        committed = str(root / "messages.txt")
        drift.write_committed(committed, parsed)
        check("and survives the one-line-per-message inventory",
              drift.committed_messages(committed)["en"]["hint"] == "A\nB")


def test_an_unreadable_entry_is_fatal() -> None:
    with tempfile.TemporaryDirectory() as tmp:
        root = pathlib.Path(tmp)
        # A third locale key the parser's two-slot pattern cannot hold.
        legacy = str(build_php(root, ONE + "    'odd' => ['ar' => 'x', 'en' => 'y', 'fr' => 'z'],\n"))
        try:
            drift.php_messages(legacy)
            check("an entry shape the parser cannot read is fatal", False, "no SystemExit")
        except SystemExit as exit_code:
            check("an entry shape the parser cannot read is fatal",
                  "odd" in str(exit_code), str(exit_code))


def test_a_missing_key_and_a_reworded_value_both_fail() -> None:
    with tempfile.TemporaryDirectory() as tmp:
        root = pathlib.Path(tmp)
        legacy = str(build_php(root, ONE + "    'nav_faqs' => ['ar' => 'الأسئلة', 'en' => 'FAQs'],\n"))
        committed = str(root / "messages.txt")
        run(legacy, str(build_java(root, JAVA_ONE)), committed, refresh=True)
        check("a key lang.php has and the JTE catalog does not fails",
              run(legacy, str(build_java(root, JAVA_ONE)), committed) == 1)

        both = {"en": {"nav_home": "Start", "nav_faqs": "FAQs"},
                "ar": {"nav_home": "الرئيسية", "nav_faqs": "الأسئلة"}}
        check("a key whose value drifted fails too",
              run(legacy, str(build_java(root, both)), committed) == 1)


def test_a_java_only_key_fails() -> None:
    with tempfile.TemporaryDirectory() as tmp:
        root = pathlib.Path(tmp)
        legacy = str(build_php(root, ONE))
        committed = str(root / "messages.txt")
        run(legacy, str(build_java(root, JAVA_ONE)), committed, refresh=True)
        extra = {"en": {"nav_home": "Home", "invented": "?"},
                 "ar": {"nav_home": "الرئيسية", "invented": "?"}}
        check("a JTE-only key fails", run(legacy, str(build_java(root, extra)), committed) == 1)


def test_missing_legacy_file_still_checks_against_the_committed_inventory() -> None:
    with tempfile.TemporaryDirectory() as tmp:
        root = pathlib.Path(tmp)
        legacy = str(build_php(root, ONE))
        committed = str(root / "messages.txt")
        run(legacy, str(build_java(root, JAVA_ONE)), committed, refresh=True)

        absent = str(root / "no-lang.php")
        check("with no hr-legacy, a matching catalog still passes",
              run(absent, str(build_java(root, JAVA_ONE)), committed) == 0)
        drifted = {"en": {"nav_home": "Start"}, "ar": {"nav_home": "الرئيسية"}}
        check("with no hr-legacy, a drifted catalog still fails",
              run(absent, str(build_java(root, drifted)), committed) == 1)


def test_missing_committed_inventory_fails_rather_than_passes() -> None:
    with tempfile.TemporaryDirectory() as tmp:
        root = pathlib.Path(tmp)
        check("an absent committed inventory exits 2, not 0",
              run(str(root / "none.php"), str(build_java(root, JAVA_ONE)),
                  str(root / "missing.txt")) == 2)


def test_a_stale_inventory_fails_when_hr_legacy_is_present() -> None:
    with tempfile.TemporaryDirectory() as tmp:
        root = pathlib.Path(tmp)
        committed = str(root / "messages.txt")
        run(str(build_php(root, ONE)), str(build_java(root, JAVA_ONE)), committed, refresh=True)
        grown = str(build_php(root, ONE + "    'nav_new' => ['ar' => 'ج', 'en' => 'New'],\n"))
        check("an inventory stale against lang.php fails even when the catalog matches it",
              run(grown, str(build_java(root, JAVA_ONE)), committed) == 1)


def test_refresh_needs_hr_legacy() -> None:
    with tempfile.TemporaryDirectory() as tmp:
        root = pathlib.Path(tmp)
        check("--refresh with no lang.php exits 2 rather than writing an empty inventory",
              run(str(root / "none.php"), str(build_java(root, JAVA_ONE)),
                  str(root / "out.txt"), refresh=True) == 2)
        check("and writes nothing", not (root / "out.txt").exists())


def test_the_real_repository_catalog_matches() -> None:
    # The one non-hermetic case, and it only runs where hr-legacy is present.
    if not pathlib.Path(drift.LEGACY_LANG).is_file():
        print("SKIP the real repository's dashboard catalog (no hr-legacy checkout)")
        return
    check("the real repository's dashboard catalog matches lang.php",
          run(drift.LEGACY_LANG, drift.JAVA_LANG, drift.COMMITTED) == 0)


def main() -> int:
    test_matching_catalogs_pass()
    test_either_locale_order_is_read()
    test_a_double_quoted_value_is_read_with_its_escape()
    test_an_unreadable_entry_is_fatal()
    test_a_missing_key_and_a_reworded_value_both_fail()
    test_a_java_only_key_fails()
    test_missing_legacy_file_still_checks_against_the_committed_inventory()
    test_missing_committed_inventory_fails_rather_than_passes()
    test_a_stale_inventory_fails_when_hr_legacy_is_present()
    test_refresh_needs_hr_legacy()
    test_the_real_repository_catalog_matches()
    print()
    if FAILURES:
        print(f"{len(FAILURES)} FAILURE(S): {FAILURES}")
        return 1
    print("all check_dashboard_message_drift cases passed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

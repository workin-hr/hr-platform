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
import subprocess
import sys
import tempfile

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))

import check_dashboard_message_drift as drift  # noqa: E402

FAILURES: list[str] = []


def check(name: str, condition: bool, detail: str = "") -> None:
    print(f"{'OK ' if condition else 'FAIL'} {name}" + (f" ({detail})" if detail and not condition else ""))
    if not condition:
        FAILURES.append(name)


def build_php(root: pathlib.Path, entries: str, uncommitted: str = "") -> pathlib.Path:
    """A fixture hr-legacy whose lang.php lives in a commit.

    The script reads HEAD rather than the filesystem (R-063), so the fixture
    has to be a real repository at the real path -- and that lets these tests
    cover the case that caused the risk: a label on disk and in no commit.
    """
    repo = root / "hr-legacy"
    path = repo / "dashboard" / "includes" / "lang.php"
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("<?php\nreturn [\n" + entries + "];\n", encoding="utf-8")
    subprocess.run(["git", "init", "-q", str(repo)], check=True)
    subprocess.run(["git", "-C", str(repo), "config", "user.email", "t@t"], check=True)
    subprocess.run(["git", "-C", str(repo), "config", "user.name", "t"], check=True)
    subprocess.run(["git", "-C", str(repo), "add", "-A"], check=True)
    subprocess.run(["git", "-C", str(repo), "commit", "-qm", "fixture"], check=True)
    if uncommitted:
        path.write_text("<?php\nreturn [\n" + entries + uncommitted + "];\n", encoding="utf-8")
    return path


def build_java(root: pathlib.Path, messages: dict[str, dict[str, str]]) -> pathlib.Path:
    lang = root / "i18n"
    lang.mkdir(parents=True, exist_ok=True)
    for locale, entries in messages.items():
        (lang / drift.JAVA_FILES[locale]).write_text(
            "".join(f"{key}={value}\n" for key, value in entries.items()), encoding="utf-8")
    return lang


def run(legacy: str, java: str, committed: str, refresh: bool = False,
        diverges: dict[str, str] | None = None) -> int:
    """Drive the real main() over a fixture.

    DIVERGES_FROM_BASELINE is swapped out because it names real labels in this
    repository's catalogue (R-063); left in place it would report every one of
    them as settled against a two-key fixture. Cases that mean to exercise the
    guard pass their own.
    """
    argv = sys.argv
    real_diverges = drift.DIVERGES_FROM_BASELINE
    drift.DIVERGES_FROM_BASELINE = diverges or {}
    sys.argv = ["check_dashboard_message_drift.py",
                "--legacy-lang", legacy, "--java-lang", java, "--committed", committed]
    if refresh:
        sys.argv.append("--refresh")
    try:
        return drift.main()
    finally:
        sys.argv = argv
        drift.DIVERGES_FROM_BASELINE = real_diverges


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
    # The one case that must keep the real DIVERGES_FROM_BASELINE: it is
    # checking this repository, where those labels genuinely diverge (R-063).
    check("the real repository's dashboard catalog matches lang.php",
          run(drift.LEGACY_LANG, drift.JAVA_LANG, drift.COMMITTED,
              diverges=drift.DIVERGES_FROM_BASELINE) == 0)


def test_an_uncommitted_label_is_not_a_baseline_label() -> None:
    """R-063, the case that caused all of this.

    A label added to hr-legacy's working tree and committed nowhere must not
    reach the inventory. Reading the filesystem put five `guide_videos` labels
    in, for a page that exists in no commit, and they shipped.
    """
    with tempfile.TemporaryDirectory() as tmp:
        root = pathlib.Path(tmp)
        legacy = build_php(
            root, ONE,
            uncommitted="    'nav_guide_videos' => ['ar' => 'ف', 'en' => 'Guide videos'],\n")
        parsed = drift.php_messages(str(legacy))
        check("a label on disk and in no commit is not a baseline label",
              "nav_guide_videos" not in parsed["en"] and "nav_home" in parsed["en"],
              repr(sorted(parsed["en"])))


def test_a_named_divergence_that_no_longer_diverges_fails() -> None:
    """The exemption list has to stay honest, or it becomes a hiding place."""
    with tempfile.TemporaryDirectory() as tmp:
        root = pathlib.Path(tmp)
        legacy = str(build_php(root, ONE))
        java = str(build_java(root, {"en": {"nav_home": "Home"}, "ar": {"nav_home": "الرئيسية"}}))
        committed = str(root / "messages.txt")
        run(legacy, java, committed, refresh=True)
        # nav_home is defined identically on both sides, so naming it as a
        # divergence is stale the moment it is written.
        code = run(legacy, java, committed, diverges={"nav_home": "not actually divergent"})
        check("a named divergence that no longer diverges fails", code == 1, f"exit {code}")


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
    test_an_uncommitted_label_is_not_a_baseline_label()
    test_a_named_divergence_that_no_longer_diverges_fails()
    test_the_real_repository_catalog_matches()
    print()
    if FAILURES:
        print(f"{len(FAILURES)} FAILURE(S): {FAILURES}")
        return 1
    print("all check_dashboard_message_drift cases passed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

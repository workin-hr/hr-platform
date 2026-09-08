#!/usr/bin/env python3
"""Deterministic regression tests for scripts/check_legacy_message_drift.py.

Run directly: `python3 scripts/test_check_legacy_message_drift.py`.

Fixture-based and hermetic: no sibling hr-legacy checkout. Every case writes a
small pair of lang files and a stub Java catalog in a temporary directory and
drives the real functions over them.

Two cases carry the weight:

* the double-quoted value. The first draft of the checker read only
  single-quoted PHP strings and silently reported the six `"...\\n..."`
  messages as Java-only additions. It now refuses to parse a file it cannot
  read in full, and this pins that.
* the empty-inventory case. This gate runs where hr-legacy is not checked out,
  and a check that passes when its input is missing is worse than no check.
  It exits 2.

Wired into: scripts/validate_phase0.py's script/test-sibling rule.
"""

from __future__ import annotations

import pathlib
import subprocess
import sys
import tempfile

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))

import check_legacy_message_drift as drift  # noqa: E402

FAILURES: list[str] = []


def check(name: str, condition: bool, detail: str = "") -> None:
    print(f"{'OK ' if condition else 'FAIL'} {name}" + (f" ({detail})" if detail and not condition else ""))
    if not condition:
        FAILURES.append(name)


def render_php(entries: dict[str, str], raw: str = "") -> str:
    body = "".join(
        "    '" + key + "' => '" + value.replace("\\", "\\\\").replace("'", "\\'") + "',\n"
        for key, value in entries.items())
    return "<?php\nreturn [\n" + body + raw + "];\n"


def build_php(root: pathlib.Path, messages: dict[str, dict[str, str]], raw: str = "",
              uncommitted: dict[str, dict[str, str]] | None = None) -> pathlib.Path:
    """A fixture hr-legacy whose lang files live in a commit.

    The script reads HEAD rather than the filesystem (R-063), so the fixture
    has to be a real repository -- and that lets these tests cover the case
    that caused the risk: a message present on disk and in no commit.
    """
    repo = root / "hr-legacy"
    lang = repo / "apis" / "lang"
    lang.mkdir(parents=True, exist_ok=True)
    for locale, entries in messages.items():
        (lang / f"{locale}.php").write_text(render_php(entries, raw), encoding="utf-8")
    subprocess.run(["git", "init", "-q", str(repo)], check=True)
    subprocess.run(["git", "-C", str(repo), "config", "user.email", "t@t"], check=True)
    subprocess.run(["git", "-C", str(repo), "config", "user.name", "t"], check=True)
    subprocess.run(["git", "-C", str(repo), "add", "-A"], check=True)
    subprocess.run(["git", "-C", str(repo), "commit", "-qm", "fixture"], check=True)
    for locale, entries in (uncommitted or {}).items():
        merged = dict(messages.get(locale, {}))
        merged.update(entries)
        (lang / f"{locale}.php").write_text(render_php(merged, raw), encoding="utf-8")
    return lang


def build_java(root: pathlib.Path, messages: dict[str, dict[str, str]]) -> pathlib.Path:
    lang = root / "java"
    lang.mkdir(parents=True, exist_ok=True)
    for locale, entries in messages.items():
        (lang / f"{locale}.properties").write_text(
            "".join(f"{key}={value}\n" for key, value in entries.items()), encoding="utf-8")
    return lang


def run(legacy: str, java: str, committed: str, refresh: bool = False,
        diverges: dict[str, str] | None = None) -> int:
    """Drive the real main() over a fixture.

    DIVERGES_FROM_BASELINE is swapped out because it names real messages in
    this repository's catalogue (R-063); left in place it would report every
    one of them as settled against a two-key fixture. Cases that mean to
    exercise the guard pass their own.
    """
    argv = sys.argv
    real_diverges = drift.DIVERGES_FROM_BASELINE
    drift.DIVERGES_FROM_BASELINE = diverges or {}
    sys.argv = ["check_legacy_message_drift.py",
                "--legacy-lang", legacy, "--java-lang", java, "--committed", committed]
    if refresh:
        sys.argv.append("--refresh")
    try:
        return drift.main()
    finally:
        sys.argv = argv
        drift.DIVERGES_FROM_BASELINE = real_diverges


BOTH = {"en": {"ok": "Done"}, "ar": {"ok": "تم"}}


def test_matching_catalogs_pass() -> None:
    with tempfile.TemporaryDirectory() as tmp:
        root = pathlib.Path(tmp)
        legacy = build_php(root, BOTH)
        java = build_java(root, BOTH)
        committed = str(root / "messages.txt")
        run(str(legacy), str(java), committed, refresh=True)
        check("a Java catalog equal to hr-legacy passes",
              run(str(legacy), str(java), committed) == 0)


def test_a_key_missing_from_java_fails() -> None:
    with tempfile.TemporaryDirectory() as tmp:
        root = pathlib.Path(tmp)
        legacy = build_php(root, {"en": {"ok": "Done", "gone": "Gone"}, "ar": {"ok": "تم", "gone": "ذهب"}})
        committed = str(root / "messages.txt")
        run(str(legacy), str(build_java(root, BOTH)), committed, refresh=True)
        check("a key PHP defines and Java does not fails",
              run(str(legacy), str(build_java(root, BOTH)), committed) == 1)


def test_a_reworded_value_fails() -> None:
    with tempfile.TemporaryDirectory() as tmp:
        root = pathlib.Path(tmp)
        legacy = build_php(root, BOTH)
        committed = str(root / "messages.txt")
        run(str(legacy), str(build_java(root, BOTH)), committed, refresh=True)
        java = build_java(root, {"en": {"ok": "Finished"}, "ar": {"ok": "تم"}})
        check("a key whose value drifted fails, not just a missing one",
              run(str(legacy), str(java), committed) == 1)


def test_a_java_only_key_fails_too() -> None:
    # Unlike routes, this direction is a real problem: a string with no PHP
    # counterpart is one no legacy endpoint can ever ask for.
    with tempfile.TemporaryDirectory() as tmp:
        root = pathlib.Path(tmp)
        legacy = build_php(root, BOTH)
        committed = str(root / "messages.txt")
        run(str(legacy), str(build_java(root, BOTH)), committed, refresh=True)
        java = build_java(root, {"en": {"ok": "Done", "extra": "?"}, "ar": {"ok": "تم", "extra": "?"}})
        check("a Java-only key fails", run(str(legacy), str(java), committed) == 1)


def test_double_quoted_values_are_read() -> None:
    with tempfile.TemporaryDirectory() as tmp:
        root = pathlib.Path(tmp)
        legacy = build_php(root, BOTH, raw='    \'two_lines\' => "One\\nTwo",\n')
        parsed = drift.php_messages(str(legacy))
        check("a double-quoted value is read, with its escape interpreted",
              parsed["en"].get("two_lines") == "One\nTwo", repr(parsed["en"].get("two_lines")))

        # And it survives the committed inventory's one-line-per-message shape.
        committed = str(root / "messages.txt")
        drift.write_committed(committed, parsed)
        check("a newline survives the committed round trip",
              drift.committed_messages(committed)["en"]["two_lines"] == "One\nTwo")

        java = build_java(root, {"en": {"ok": "Done", "two_lines": "One\\nTwo"},
                                 "ar": {"ok": "تم", "two_lines": "One\\nTwo"}})
        check("and the Java catalog's own \\n escape compares equal to it",
              run(str(legacy), str(java), committed) == 0)


def test_an_unreadable_value_shape_is_fatal() -> None:
    with tempfile.TemporaryDirectory() as tmp:
        root = pathlib.Path(tmp)
        # A concatenation is neither quote shape. It must stop the run, not be
        # reported as a key Java invented.
        legacy = build_php(root, BOTH, raw="    'joined' => 'a' . 'b',\n")
        try:
            drift.php_messages(str(legacy))
            check("a value shape the parser cannot read is fatal", False, "no SystemExit")
        except SystemExit as exit_code:
            check("a value shape the parser cannot read is fatal",
                  "joined" in str(exit_code), str(exit_code))


def test_missing_legacy_tree_still_checks_against_the_committed_inventory() -> None:
    with tempfile.TemporaryDirectory() as tmp:
        root = pathlib.Path(tmp)
        legacy = build_php(root, BOTH)
        committed = str(root / "messages.txt")
        run(str(legacy), str(build_java(root, BOTH)), committed, refresh=True)

        absent = str(root / "no-hr-legacy")
        check("with no hr-legacy, a matching Java catalog still passes",
              run(absent, str(build_java(root, BOTH)), committed) == 0)
        java = build_java(root, {"en": {"ok": "Finished"}, "ar": {"ok": "تم"}})
        check("with no hr-legacy, a drifted Java catalog still fails",
              run(absent, str(java), committed) == 1)


def test_missing_committed_inventory_fails_rather_than_passes() -> None:
    with tempfile.TemporaryDirectory() as tmp:
        root = pathlib.Path(tmp)
        check("an absent committed inventory exits 2, not 0",
              run(str(root / "none"), str(build_java(root, BOTH)), str(root / "missing.txt")) == 2)


def test_a_stale_committed_inventory_fails_when_hr_legacy_is_present() -> None:
    with tempfile.TemporaryDirectory() as tmp:
        root = pathlib.Path(tmp)
        legacy = build_php(root, BOTH)
        committed = str(root / "messages.txt")
        run(str(legacy), str(build_java(root, BOTH)), committed, refresh=True)

        grown = {"en": {"ok": "Done", "fresh": "New"}, "ar": {"ok": "تم", "fresh": "جديد"}}
        check("an inventory stale against hr-legacy fails even when Java matches it",
              run(str(build_php(root, grown)), str(build_java(root, BOTH)), committed) == 1)


def test_refresh_needs_hr_legacy() -> None:
    with tempfile.TemporaryDirectory() as tmp:
        root = pathlib.Path(tmp)
        check("--refresh with no hr-legacy exits 2 rather than writing an empty inventory",
              run(str(root / "none"), str(build_java(root, BOTH)), str(root / "out.txt"),
                  refresh=True) == 2)
        check("and writes nothing", not (root / "out.txt").exists())


def test_properties_comments_and_blanks_are_ignored() -> None:
    with tempfile.TemporaryDirectory() as tmp:
        root = pathlib.Path(tmp)
        lang = root / "java"
        lang.mkdir(parents=True)
        for locale in ("en", "ar"):
            (lang / f"{locale}.properties").write_text(
                "# a comment\n\nok=Done\n", encoding="utf-8")
        parsed = drift.java_messages(str(lang))
        check("comments and blank lines are not keys", parsed["en"] == {"ok": "Done"}, str(parsed["en"]))


def test_an_empty_value_is_kept() -> None:
    with tempfile.TemporaryDirectory() as tmp:
        root = pathlib.Path(tmp)
        lang = root / "java"
        lang.mkdir(parents=True)
        for locale in ("en", "ar"):
            (lang / f"{locale}.properties").write_text("blank=\n", encoding="utf-8")
        parsed = drift.java_messages(str(lang))
        check("a key with an empty value is present, not dropped",
              parsed["ar"] == {"blank": ""}, str(parsed["ar"]))


def test_an_uncommitted_message_is_not_a_baseline_message() -> None:
    """R-063, the case that caused all of this.

    A message added to hr-legacy's working tree and committed nowhere must not
    reach the inventory. Reading the filesystem put five such messages in, and
    one reworded value, and this application shipped them.
    """
    with tempfile.TemporaryDirectory() as tmp:
        root = pathlib.Path(tmp)
        legacy = build_php(root, BOTH, uncommitted={"en": {"wip": "Draft"},
                                                    "ar": {"wip": "مسودة"}})
        parsed = drift.php_messages(str(legacy))
        check("a message on disk and in no commit is not a baseline message",
              "wip" not in parsed["en"] and "ok" in parsed["en"],
              repr(sorted(parsed["en"])))


def test_a_named_divergence_that_no_longer_diverges_fails() -> None:
    """The exemption list has to stay honest, or it becomes a hiding place."""
    with tempfile.TemporaryDirectory() as tmp:
        root = pathlib.Path(tmp)
        legacy = build_php(root, BOTH)
        java = build_java(root, BOTH)
        committed = root / "messages.txt"
        run(str(legacy), str(java), str(committed), refresh=True)
        # "ok" is defined identically on both sides, so naming it as a
        # divergence is stale the moment it is written.
        code = run(str(legacy), str(java), str(committed),
                   diverges={"ok": "not actually divergent"})
        check("a named divergence that no longer diverges fails", code == 1, f"exit {code}")


def main() -> int:
    test_matching_catalogs_pass()
    test_a_key_missing_from_java_fails()
    test_a_reworded_value_fails()
    test_a_java_only_key_fails_too()
    test_double_quoted_values_are_read()
    test_an_unreadable_value_shape_is_fatal()
    test_missing_legacy_tree_still_checks_against_the_committed_inventory()
    test_missing_committed_inventory_fails_rather_than_passes()
    test_a_stale_committed_inventory_fails_when_hr_legacy_is_present()
    test_refresh_needs_hr_legacy()
    test_properties_comments_and_blanks_are_ignored()
    test_an_empty_value_is_kept()
    test_an_uncommitted_message_is_not_a_baseline_message()
    test_a_named_divergence_that_no_longer_diverges_fails()
    print()
    if FAILURES:
        print(f"{len(FAILURES)} FAILURE(S): {FAILURES}")
        return 1
    print("all check_legacy_message_drift cases passed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

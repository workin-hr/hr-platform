#!/usr/bin/env python3
"""Prove the vendored legacy message catalogs still match hr-legacy's.

D-074 makes the legacy wire contract authoritative, and every legacy
response carries a `message` that `t()` resolves out of
`hr-legacy/apis/lang/{en,ar}.php`. Phase 1 therefore vendors those
catalogs as
`backend/src/main/resources/legacy/lang/{en,ar}.properties`, the same way
`check_legacy_schema_drift.py`'s subject vendors the schema -- CI checks
out only this repository, and the contract has to be present at runtime.

The conversion is not a hand transcription: the PHP arrays are read by a
real PHP 8.3 CLI (`docker run --rm php:8.3-cli`), dumped as JSON, and
rewritten as `java.util.Properties` text. This script re-runs exactly
that pipeline and compares, so a key added or reworded upstream fails
here instead of silently changing what clients see.

Like `check_legacy_schema_drift.py`, the comparison needs a sibling
`hr-legacy` checkout and a working Docker, so it cannot run in CI.
`--self-test` needs neither and does run there, keeping the escaping
logic itself proven.

Usage:
    python3 scripts/check_legacy_lang_drift.py
    python3 scripts/check_legacy_lang_drift.py --legacy ../hr-legacy
    python3 scripts/check_legacy_lang_drift.py --write
    python3 scripts/check_legacy_lang_drift.py --self-test
"""

from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys

VENDORED_DIR = os.path.join("backend", "src", "main", "resources", "legacy", "lang")
CATALOGS = ("en", "ar")
HEADER = (
    "# Generated from hr-legacy/apis/lang/{name}.php by scripts/check_legacy_lang_drift.py --write.\n"
    "# Do not hand-edit: the drift check re-runs that conversion under a real PHP 8.3 CLI\n"
    "# and fails when this file and the legacy catalog disagree.\n"
)
SPECIAL_KEY_CHARS = set("\\=:#!")


def escape_key(key: str) -> str:
    """`java.util.Properties` key escaping: separators and comment starts."""
    out = []
    for char in key:
        if char in SPECIAL_KEY_CHARS:
            out.append("\\" + char)
        elif char == " ":
            out.append("\\ ")
        else:
            out.append(char)
    return "".join(out)


def escape_value(value: str) -> str:
    """Value escaping: backslashes, the line breaks, and a leading space."""
    out = []
    for index, char in enumerate(value):
        if char == "\\":
            out.append("\\\\")
        elif char == "\n":
            out.append("\\n")
        elif char == "\r":
            out.append("\\r")
        elif char == "\t":
            out.append("\\t")
        elif char == " " and index == 0:
            out.append("\\ ")
        else:
            out.append(char)
    return "".join(out)


def render(name: str, catalog: dict) -> str:
    lines = [HEADER.format(name=name)]
    for key, value in catalog.items():
        lines.append(escape_key(key) + "=" + escape_value(str(value)) + "\n")
    return "".join(lines)


def read_upstream(legacy_root: str, name: str) -> dict:
    """Let PHP itself evaluate the catalog -- no PHP parser lives here."""
    lang_dir = os.path.abspath(os.path.join(legacy_root, "apis", "lang"))
    if not os.path.isdir(lang_dir):
        raise SystemExit("no legacy language directory at %s" % lang_dir)
    script = 'echo json_encode(require "/lang/%s.php", JSON_UNESCAPED_UNICODE|JSON_UNESCAPED_SLASHES);' % name
    try:
        completed = subprocess.run(
            ["docker", "run", "--rm", "-v", lang_dir + ":/lang:ro", "php:8.3-cli", "php", "-r", script],
            capture_output=True,
            check=True,
        )
    except FileNotFoundError:
        raise SystemExit("docker is not available; this check needs it to run the real PHP CLI")
    except subprocess.CalledProcessError as error:
        raise SystemExit("PHP could not read %s.php: %s" % (name, error.stderr.decode("utf-8", "replace")))
    return json.loads(completed.stdout.decode("utf-8"))


def check(legacy_root: str, write: bool, vendored_dir: str = VENDORED_DIR, read=None) -> int:
    """Compare (or regenerate) every vendored catalog.

    `vendored_dir` and `read` are injection points for
    `test_check_legacy_lang_drift.py`: the tests drive real files in a
    temporary directory and a stub upstream reader, so the comparison,
    the write path and the reporting are all covered without Docker or a
    sibling hr-legacy checkout.
    """
    read = read if read is not None else read_upstream
    failures = 0
    for name in CATALOGS:
        expected = render(name, read(legacy_root, name))
        path = os.path.join(vendored_dir, name + ".properties")
        if write:
            with open(path, "w", encoding="utf-8", newline="\n") as handle:
                handle.write(expected)
            print("WROTE %s" % path)
            continue
        if not os.path.isfile(path):
            failures += 1
            print("MISSING: %s does not exist; re-run with --write" % path)
            continue
        with open(path, encoding="utf-8") as handle:
            actual = handle.read()
        if actual == expected:
            print("OK: %s matches hr-legacy/apis/lang/%s.php" % (path, name))
            continue
        failures += 1
        print("DRIFT: %s no longer matches hr-legacy/apis/lang/%s.php" % (path, name))
        for line in first_difference(actual, expected):
            print("  " + line)
        print("  re-run with --write once the change is intended")
    return 1 if failures else 0


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
    """The escaping, which is the only logic this file owns."""
    failures = []

    def expect(label, actual, wanted):
        if actual != wanted:
            failures.append("%s: got %r, wanted %r" % (label, actual, wanted))
            print("FAIL %s" % label)
        else:
            print("OK  %s" % label)

    expect("a plain key is untouched", escape_key("employee_not_found"), "employee_not_found")
    expect("a separator in a key is escaped", escape_key("a=b"), "a\\=b")
    expect("a placeholder value survives", escape_value("Field '{field}' is required"),
           "Field '{field}' is required")
    expect("a newline becomes an escape", escape_value("one\ntwo"), "one\\ntwo")
    expect("a backslash is doubled", escape_value("a\\b"), "a\\\\b")
    expect("a leading space is escaped", escape_value(" lead"), "\\ lead")
    expect("an inner space is not", escape_value("two words"), "two words")
    expect("Arabic passes through", escape_value("الموظفون"), "الموظفون")
    expect("rendering keeps insertion order",
           render("en", {"b": "second", "a": "first"}).splitlines()[-2:], ["b=second", "a=first"])
    return 1 if failures else 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--legacy", default=os.path.join("..", "hr-legacy"))
    parser.add_argument("--write", action="store_true", help="regenerate the vendored catalogs")
    parser.add_argument("--self-test", action="store_true", help="prove the escaping without hr-legacy or Docker")
    args = parser.parse_args()
    if args.self_test:
        return self_test()
    return check(args.legacy, args.write)


if __name__ == "__main__":
    sys.exit(main())

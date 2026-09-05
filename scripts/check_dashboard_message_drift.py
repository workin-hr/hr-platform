#!/usr/bin/env python3
"""Fail when the JTE dashboard's message catalog drifts from hr-legacy's.

The sibling of scripts/check_legacy_message_drift.py, for the other catalog.
Same three-way shape, same reason, different source file: the API's strings
come from `apis/lang/{en,ar}.php` as one key per locale file, while the
dashboard's come from `dashboard/includes/lang.php` as one entry holding both
locales at once:

    'nav_home' => ['ar' => 'الرئيسية', 'en' => 'Home'],

scripts/convert_dashboard_lang.py turns that into the two properties files the
templates read. Running a converter is not a gate, though -- it produces the
right answer on the day it is run and says nothing afterwards. hr-legacy added
five `guide_video*` strings after the conversion, and the only symptom was
that a sidebar entry would have rendered as the literal `nav_guide_videos`.
That is the same failure the API catalog had, found the same way, which is why
it now has the same check.

Deliberately a separate script from the API one rather than a flag on it. The
two catalogs have different parsers, different files and different owners, and
folding them together would mean a change to either one's shape could break
the other's gate.

Usage:
    python3 scripts/check_dashboard_message_drift.py            # check
    python3 scripts/check_dashboard_message_drift.py --refresh  # rewrite the inventory
"""
from __future__ import annotations

import argparse
import os
import re
import sys

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
LEGACY_LANG = os.path.join(REPO_ROOT, "..", "hr-legacy", "dashboard", "includes", "lang.php")
JAVA_LANG = os.path.join(REPO_ROOT, "backend", "src", "main", "resources", "i18n")
COMMITTED = os.path.join(REPO_ROOT, "contracts", "dashboard-php-messages.txt")

LOCALES = ("en", "ar")

# admin-messages.properties is English; the Arabic file carries the _ar suffix,
# which is Spring's own convention and not ours to change.
JAVA_FILES = {"en": "admin-messages.properties", "ar": "admin-messages_ar.properties"}

COMMITTED_HEADER = """\
# Every message hr-legacy's dashboard/includes/lang.php defines, as
# "<locale>\\t<key>\\t<value>", sorted by locale then key.
#
# GENERATED -- refresh with:
#   python3 scripts/check_dashboard_message_drift.py --refresh
#
# Committed so the drift gate can run where hr-legacy is not checked out,
# which includes CI. Without it a string added to the dashboard would render
# in the JTE port as its own key -- "nav_guide_videos" in a sidebar -- with
# every check green.
"""

# `'key' => ['ar' => '...', 'en' => '...'],` in either locale order, with
# either quote style on each value.
ENTRY = re.compile(
    r"'(?P<key>[a-z0-9_]+)'\s*=>\s*\[\s*"
    r"'(?P<first_locale>ar|en)'\s*=>\s*(?P<first>'(?:[^'\\]|\\.)*'|\"(?:[^\"\\]|\\.)*\")\s*,\s*"
    r"'(?P<second_locale>ar|en)'\s*=>\s*(?P<second>'(?:[^'\\]|\\.)*'|\"(?:[^\"\\]|\\.)*\")\s*,?\s*\]",
    re.S)

# Every line that starts an entry, so a value shape the regex cannot read
# fails loudly instead of shrinking the inventory. The API gate learned this
# the hard way; the lesson is copied, not re-learned.
ENTRY_START = re.compile(r"^\s*'([a-z0-9_]+)'\s*=>\s*\[", re.M)


def unquote_php(literal: str) -> str:
    r"""One PHP string literal, single- or double-quoted, as its value."""
    body = literal[1:-1]
    if literal.startswith("'"):
        return body.replace("\\'", "'").replace("\\\\", "\\")
    escapes = {"n": "\n", "t": "\t", "r": "\r", "\\": "\\", '"': '"', "$": "$"}
    out = []
    index = 0
    while index < len(body):
        char = body[index]
        if char == "\\" and index + 1 < len(body) and body[index + 1] in escapes:
            out.append(escapes[body[index + 1]])
            index += 2
            continue
        out.append(char)
        index += 1
    return "".join(out)


def php_messages(lang_file: str) -> dict[str, dict[str, str]]:
    """`{locale: {key: value}}` from lang.php, or `{}` when it is absent."""
    if not os.path.isfile(lang_file):
        return {}
    with open(lang_file, encoding="utf-8") as handle:
        text = handle.read()

    found: dict[str, dict[str, str]] = {locale: {} for locale in LOCALES}
    for match in ENTRY.finditer(text):
        key = match.group("key")
        found[match.group("first_locale")][key] = unquote_php(match.group("first"))
        found[match.group("second_locale")][key] = unquote_php(match.group("second"))

    started = set(ENTRY_START.findall(text))
    for locale in LOCALES:
        unread = sorted(started - set(found[locale]))
        if unread:
            raise SystemExit(
                f"FATAL: {len(unread)} entr(y/ies) in {lang_file} could not be read for "
                f"'{locale}': {', '.join(unread[:10])}. The file's shape changed; fix this "
                "parser rather than letting it report them as absent."
            )
    return found


PROPERTIES_ESCAPES = {"n": "\n", "t": "\t", "r": "\r", "f": "\f",
                      "\\": "\\", "=": "=", ":": ":", " ": " ", "#": "#", "!": "!"}


def unescape_properties(value: str) -> str:
    out = []
    index = 0
    while index < len(value):
        char = value[index]
        if char == "\\" and index + 1 < len(value):
            following = value[index + 1]
            out.append(PROPERTIES_ESCAPES.get(following, following))
            index += 2
            continue
        out.append(char)
        index += 1
    return "".join(out)


def java_messages(lang_dir: str) -> dict[str, dict[str, str]]:
    found: dict[str, dict[str, str]] = {}
    for locale, filename in JAVA_FILES.items():
        entries: dict[str, str] = {}
        with open(os.path.join(lang_dir, filename), encoding="utf-8") as handle:
            for line in handle:
                stripped = line.rstrip("\n")
                if not stripped.strip() or stripped.lstrip().startswith("#"):
                    continue
                key, _, value = stripped.partition("=")
                entries[key.strip()] = unescape_properties(value)
        found[locale] = entries
    return found


def committed_messages(path: str) -> dict[str, dict[str, str]]:
    if not os.path.exists(path):
        return {}
    found: dict[str, dict[str, str]] = {locale: {} for locale in LOCALES}
    with open(path, encoding="utf-8") as handle:
        for line in handle:
            if not line.strip() or line.startswith("#"):
                continue
            locale, _, rest = line.rstrip("\n").partition("\t")
            key, _, value = rest.partition("\t")
            found.setdefault(locale, {})[key] = value.replace("\\n", "\n").replace("\\\\", "\\")
    return found


def write_committed(path: str, messages: dict[str, dict[str, str]]) -> None:
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as handle:
        handle.write(COMMITTED_HEADER)
        for locale in LOCALES:
            for key in sorted(messages[locale]):
                value = messages[locale][key].replace("\\", "\\\\").replace("\n", "\\n")
                handle.write(f"{locale}\t{key}\t{value}\n")


def compare(expected: dict[str, str], actual: dict[str, str], locale: str,
            expected_name: str, actual_name: str) -> list[str]:
    problems = []
    for key in sorted(set(expected) - set(actual)):
        problems.append(f"  [{locale}] {key}: in {expected_name}, missing from {actual_name}")
    for key in sorted(set(actual) - set(expected)):
        problems.append(f"  [{locale}] {key}: in {actual_name}, not in {expected_name}")
    for key in sorted(set(expected) & set(actual)):
        if expected[key] != actual[key]:
            problems.append(
                f"  [{locale}] {key}: value differs\n"
                f"      {expected_name}: {expected[key]}\n"
                f"      {actual_name}: {actual[key]}"
            )
    return problems


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--legacy-lang", default=LEGACY_LANG)
    parser.add_argument("--java-lang", default=JAVA_LANG)
    parser.add_argument("--committed", default=COMMITTED)
    parser.add_argument("--refresh", action="store_true",
                        help="rewrite the committed inventory from hr-legacy")
    args = parser.parse_args()

    php = php_messages(args.legacy_lang)

    if args.refresh:
        if not php:
            print(f"FATAL: {args.legacy_lang} is not there -- nothing to refresh from.",
                  file=sys.stderr)
            return 2
        write_committed(args.committed, php)
        print(f"wrote {sum(len(v) for v in php.values())} messages to {args.committed}")
        return 0

    committed = committed_messages(args.committed)
    if not committed or not any(committed.values()):
        print(f"FATAL: {args.committed} is missing or empty. Run --refresh beside an "
              "hr-legacy checkout; without it this gate proves nothing.", file=sys.stderr)
        return 2

    java = java_messages(args.java_lang)
    status = 0

    problems = []
    for locale in LOCALES:
        problems += compare(committed.get(locale, {}), java[locale], locale,
                            "hr-legacy", "the JTE catalog")
    print("dashboard catalog  " + "   ".join(f"{loc}: {len(java[loc])}" for loc in LOCALES))
    if problems:
        print(f"\nFAIL: {len(problems)} dashboard message(s) drifted from hr-legacy:",
              file=sys.stderr)
        for problem in problems:
            print(problem, file=sys.stderr)
        print("\nRegenerate with: python3 scripts/convert_dashboard_lang.py", file=sys.stderr)
        status = 1

    if php:
        stale = []
        for locale in LOCALES:
            stale += compare(php[locale], committed.get(locale, {}), locale,
                             "hr-legacy", "the committed inventory")
        if stale:
            print("\nFAIL: the committed inventory is stale against hr-legacy.", file=sys.stderr)
            for problem in stale:
                print(problem, file=sys.stderr)
            print("\nRefresh it: python3 scripts/check_dashboard_message_drift.py --refresh",
                  file=sys.stderr)
            status = 1
        else:
            print(f"hr-legacy present: its {sum(len(v) for v in php.values())} dashboard "
                  "messages match the committed inventory.")
    else:
        print("hr-legacy not checked out: comparing against the committed inventory only.")

    if status == 0:
        print("OK: the dashboard catalog matches hr-legacy.")
    return status


if __name__ == "__main__":
    raise SystemExit(main())

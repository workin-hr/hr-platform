#!/usr/bin/env python3
"""Fail when this application's message catalog drifts from hr-legacy's.

Every legacy endpoint answers with a message *key*, and the string a client
shows its user comes from the catalog that key is looked up in. Java's
`LegacyMessages.translate()` falls back to `getOrDefault(key, key)`, which is
PHP's own `t()` fallback -- so a key present in PHP and absent here does not
fail, it ships the raw key to the user's screen.

That is exactly what happened: `employees/update_bulk` was ported, wired and
tested, and answered `"employees_updated"` instead of "Updated 3 employees",
because nobody added the two strings the endpoint names. Every check was
green. `MessageCatalogSyncTest` compares en against ar -- Java against Java,
the same blind spot `check_legacy_route_drift.py` was written to close for
routes -- and both files were equally missing the key.

This closes it in the same three-way shape, so it works with or without a
sibling hr-legacy checkout:

    committed inventory  contracts/legacy-php-messages.txt
    Java catalog         backend/src/main/resources/legacy/lang/{en,ar}.properties
    hr-legacy on disk    apis/lang/{en,ar}.php, when present

CI has no hr-legacy beside it, so the gate CI can enforce is Java against the
committed inventory. When hr-legacy *is* present the committed inventory is
checked against it too, so a stale inventory fails locally before it can hide
a changed string from CI.

Values are compared, not just keys. A reworded message is a contract change
for any client that matches on the text, and a key whose value silently
diverges is the harder half of this bug to find.

Usage:
    python3 scripts/check_legacy_message_drift.py            # check
    python3 scripts/check_legacy_message_drift.py --refresh  # rewrite from hr-legacy
"""
from __future__ import annotations

import argparse
import os
import re
import sys

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
LEGACY_LANG = os.path.join(REPO_ROOT, "..", "hr-legacy", "apis", "lang")
JAVA_LANG = os.path.join(REPO_ROOT, "backend", "src", "main", "resources", "legacy", "lang")
COMMITTED = os.path.join(REPO_ROOT, "contracts", "legacy-php-messages.txt")

LOCALES = ("en", "ar")

COMMITTED_HEADER = """\
# Every message hr-legacy's apis/lang/{en,ar}.php defines, as
# "<locale>\\t<key>\\t<value>", sorted by locale then key.
#
# GENERATED -- refresh with:
#   python3 scripts/check_legacy_message_drift.py --refresh
#
# Committed so the drift gate can run where hr-legacy is not checked out,
# which includes CI. Without it a key PHP defines and Java does not would
# reach the user's screen as the raw key, with every check green -- see the
# script's own docstring for the time that happened.
"""

# `'key' => 'value',` and `'key' => "value",`. Both quote styles appear, and
# they do not mean the same thing: PHP interprets escapes inside a
# double-quoted string, so the six entries carrying a `\n` are all written
# that way.
ENTRY = re.compile(
    r"^\s*'([a-z0-9_]+)'\s*=>\s*"
    r"(?:'((?:[^'\\]|\\.)*)'|\"((?:[^\"\\]|\\.)*)\")\s*,",
    re.M)

# Every line that starts an entry, whatever its value looks like. The parse is
# checked against this rather than against a magic minimum: a value shape the
# regex above cannot read must fail loudly, not shrink the inventory by six
# and leave the rest looking healthy. That is not hypothetical -- the first
# draft of this script read only single quotes and reported the six
# double-quoted messages as Java-only additions.
ENTRY_START = re.compile(r"^\s*'([a-z0-9_]+)'\s*=>", re.M)


def php_messages(lang_dir: str) -> dict[str, dict[str, str]]:
    """`{locale: {key: value}}` from hr-legacy, or `{}` when it is absent."""
    found: dict[str, dict[str, str]] = {}
    for locale in LOCALES:
        path = os.path.join(lang_dir, f"{locale}.php")
        if not os.path.isfile(path):
            return {}
        with open(path, encoding="utf-8") as handle:
            text = handle.read()
        entries = {}
        for key, single, double in ENTRY.findall(text):
            entries[key] = unescape_single(single) if single or not double else unescape_double(double)
        started = {key for key in ENTRY_START.findall(text)}
        unread = sorted(started - set(entries))
        if unread:
            raise SystemExit(
                f"FATAL: {len(unread)} entr(y/ies) in {path} could not be read: "
                f"{', '.join(unread)}. The file's value syntax changed; fix this parser "
                "rather than letting it report them as absent."
            )
        found[locale] = entries
    return found


def unescape_single(value: str) -> str:
    r"""A single-quoted PHP string escapes only \' and \\."""
    return value.replace("\\'", "'").replace("\\\\", "\\")


DOUBLE_ESCAPES = {"n": "\n", "t": "\t", "r": "\r", "e": "\x1b", "v": "\v", "f": "\f",
                  "\\": "\\", '"': '"', "$": "$"}


def unescape_double(value: str) -> str:
    r"""The escapes a double-quoted PHP string interprets, `\n` above all.

    Anything else keeps its backslash, which is what PHP does with an
    unrecognised escape."""
    out = []
    index = 0
    while index < len(value):
        char = value[index]
        if char == "\\" and index + 1 < len(value):
            following = value[index + 1]
            if following in DOUBLE_ESCAPES:
                out.append(DOUBLE_ESCAPES[following])
                index += 2
                continue
            out.append(char)
            index += 1
            continue
        out.append(char)
        index += 1
    return "".join(out)


# `.properties` escapes, as java.util.Properties reads them back. Only these
# appear in the two catalogs; a `\uXXXX` would need adding here, and the
# round-trip assertion in --refresh would catch its absence.
PROPERTIES_ESCAPES = {"n": "\n", "t": "\t", "r": "\r", "f": "\f",
                      "\\": "\\", "=": "=", ":": ":", " ": " ", "#": "#", "!": "!"}


def unescape_properties(value: str) -> str:
    out = []
    index = 0
    while index < len(value):
        char = value[index]
        if char == "\\" and index + 1 < len(value):
            following = value[index + 1]
            if following in PROPERTIES_ESCAPES:
                out.append(PROPERTIES_ESCAPES[following])
                index += 2
                continue
            out.append(following)
            index += 2
            continue
        out.append(char)
        index += 1
    return "".join(out)


def java_messages(lang_dir: str) -> dict[str, dict[str, str]]:
    found: dict[str, dict[str, str]] = {}
    for locale in LOCALES:
        path = os.path.join(lang_dir, f"{locale}.properties")
        entries: dict[str, str] = {}
        with open(path, encoding="utf-8") as handle:
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
                # One message per line, so an embedded newline is escaped back
                # out on the way in and unescaped again on the way out.
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
            print(f"FATAL: no lang files under {args.legacy_lang} -- nothing to refresh from.",
                  file=sys.stderr)
            return 2
        write_committed(args.committed, php)
        total = sum(len(entries) for entries in php.values())
        print(f"wrote {total} messages to {args.committed}")
        return 0

    committed = committed_messages(args.committed)
    if not committed or not any(committed.values()):
        print(f"FATAL: {args.committed} is missing or empty. Run --refresh beside an "
              "hr-legacy checkout; without it this gate proves nothing.", file=sys.stderr)
        return 2

    java = java_messages(args.java_lang)
    status = 0

    # The half CI can run: the Java catalog against the committed inventory.
    problems = []
    for locale in LOCALES:
        problems += compare(committed.get(locale, {}), java[locale], locale,
                            "hr-legacy", "the Java catalog")
    counts = "   ".join(f"{locale}: {len(java[locale])}" for locale in LOCALES)
    print(f"java catalog  {counts}")
    if problems:
        print(f"\nFAIL: {len(problems)} message(s) drifted from hr-legacy:", file=sys.stderr)
        for problem in problems:
            print(problem, file=sys.stderr)
        print("\nA key PHP defines and Java does not is shipped to the user as the raw key,\n"
              "because both catalogs fall back to the key itself. Add it, or correct the value.",
              file=sys.stderr)
        status = 1

    # And, when hr-legacy is beside us, that the committed inventory is current.
    if php:
        stale = []
        for locale in LOCALES:
            stale += compare(php[locale], committed.get(locale, {}), locale,
                             "hr-legacy", "the committed inventory")
        if stale:
            print("\nFAIL: the committed inventory is stale against hr-legacy.", file=sys.stderr)
            for problem in stale:
                print(problem, file=sys.stderr)
            print("\nRefresh it: python3 scripts/check_legacy_message_drift.py --refresh",
                  file=sys.stderr)
            status = 1
        else:
            total = sum(len(entries) for entries in php.values())
            print(f"hr-legacy present: its {total} messages match the committed inventory.")
    else:
        print("hr-legacy not checked out: comparing against the committed inventory only.")

    if status == 0:
        print("OK: the message catalog matches hr-legacy.")
    return status


if __name__ == "__main__":
    raise SystemExit(main())

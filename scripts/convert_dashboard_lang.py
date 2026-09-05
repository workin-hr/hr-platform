#!/usr/bin/env python3
"""Convert hr-legacy's dashboard/includes/lang.php into Java message bundles.

The admin web reproduces the PHP dashboard's labels exactly (ADR-0016). Doing
that by hand for 770-odd keys in two languages would guarantee drift and typos
in Arabic text nobody reviewing the diff can proofread, so the catalog is
generated from the legacy file and regenerated when it changes.

Deliberately a separate bundle from i18n/messages: that one is the API's
wire-visible catalog the PHP parity work pins, and MessageCatalogSyncTest
guards it. UI chrome must not dilute it.
"""
import argparse
import io
import re
import sys
from pathlib import Path

ENTRY = re.compile(
    r"'(?P<key>[a-z0-9_]+)'\s*=>\s*\[\s*"
    r"'ar'\s*=>\s*'(?P<ar>(?:[^'\\]|\\.)*)'\s*,\s*"
    r"'en'\s*=>\s*'(?P<en>(?:[^'\\]|\\.)*)'\s*,?\s*\]",
    re.S,
)

HEADER = (
    "# Admin web UI labels. GENERATED -- do not hand-edit.\n"
    "#   python3 scripts/convert_dashboard_lang.py\n"
    "# Source: hr-legacy/dashboard/includes/lang.php (ADR-0016).\n"
    "# Separate from i18n/messages, the API's wire-visible catalog.\n\n"
)


def unescape(value: str) -> str:
    return value.replace("\\'", "'").replace("\\\\", "\\")


def escape_properties(value: str) -> str:
    return value.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "")


def parse(source: str) -> dict[str, tuple[str, str]]:
    return {
        m.group("key"): (unescape(m.group("ar")), unescape(m.group("en")))
        for m in ENTRY.finditer(source)
    }


def unparsed(source: str, parsed: dict) -> list[str]:
    """Key-shaped lines the entry pattern did not claim.

    'ar' and 'en' match the key shape too, so they are excluded rather than
    reported as losses on every run.
    """
    candidates = dict.fromkeys(re.findall(r"^\s*'([a-z0-9_]+)'\s*=>", source, flags=re.M))
    return [k for k in candidates if k not in parsed and k not in ("ar", "en")]


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--lang-php", default="../hr-legacy/dashboard/includes/lang.php")
    ap.add_argument("--out-dir", default="backend/src/main/resources/i18n")
    ap.add_argument("--check", action="store_true",
                    help="fail if the generated files differ from what is committed")
    args = ap.parse_args()

    source = Path(args.lang_php).read_text(encoding="utf-8")
    entries = parse(source)
    missed = unparsed(source, entries)
    if missed:
        print(f"FATAL: {len(missed)} entries did not parse: {missed[:10]}", file=sys.stderr)
        return 2

    status = 0
    for suffix, index in (("", 1), ("_ar", 0)):
        target = Path(args.out_dir) / f"admin-messages{suffix}.properties"
        body = HEADER + "".join(
            f"{k}={escape_properties(entries[k][index])}\n" for k in sorted(entries)
        )
        if args.check:
            if not target.exists() or target.read_text(encoding="utf-8") != body:
                print(f"FATAL: {target} is stale -- rerun without --check", file=sys.stderr)
                status = 1
        else:
            with io.open(target, "w", encoding="utf-8") as f:
                f.write(body)
    if not args.check:
        print(f"wrote {len(entries)} keys to {args.out_dir}/admin-messages[_ar].properties")
    return status


if __name__ == "__main__":
    raise SystemExit(main())

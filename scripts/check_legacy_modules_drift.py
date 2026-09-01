#!/usr/bin/env python3
"""Drift detector for ApiModule::allowedList().

`LegacyPhpModules.ALLOWED` decides routing status and is emitted verbatim
into client-visible `module_not_found` bodies, so an omitted, reordered or
misspelled module silently changes the compatibility contract. The Java
constant is checked against the vendored
`backend/src/test/resources/legacy/allowed_modules.txt` by
`LegacyPhpModulesDriftTest`, which runs in CI; this script is what keeps
that vendored copy honest against the real legacy source.

Same shape as check_legacy_schema_drift.py: the real comparison needs a
hr-legacy checkout and therefore cannot run in CI, while `--self-test`
needs nothing and proves the detector itself still works.

    python3 scripts/check_legacy_modules_drift.py --legacy ../hr-legacy
    python3 scripts/check_legacy_modules_drift.py --legacy ../hr-legacy --write
    python3 scripts/check_legacy_modules_drift.py --self-test
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
VENDORED = ROOT / "backend/src/test/resources/legacy/allowed_modules.txt"
SOURCE_REL = "apis/config/http_api.php"


def parse_allowed(php: str) -> list[str]:
    """The literal values of allowedList(), in PHP's own order."""
    consts = dict(re.findall(r"const\s+([A-Z_]+)\s*=\s*'([^']+)'", php))
    body = re.search(r"allowedList\(\):\s*array\s*\{\s*return\s*\[(.*?)\];", php, re.S)
    if not body:
        raise ValueError("allowedList() not found -- did the legacy source move?")
    names = re.findall(r"self::([A-Z_]+)", body.group(1))
    missing = [n for n in names if n not in consts]
    if missing:
        raise ValueError(f"unresolved ApiModule constants: {missing}")
    return [consts[n] for n in names]


def read_vendored() -> list[str]:
    return [ln.strip() for ln in VENDORED.read_text(encoding="utf-8").splitlines()
            if ln.strip() and not ln.startswith("#")]


def report(actual: list[str], expected: list[str]) -> int:
    if actual == expected:
        print(f"pass: {len(expected)} modules, same values and same order")
        return 0
    print("DRIFT between the vendored allow-list and the legacy source", file=sys.stderr)
    only_legacy = [m for m in expected if m not in actual]
    only_vendor = [m for m in actual if m not in expected]
    if only_legacy:
        print(f"  in legacy, missing from vendored: {only_legacy}", file=sys.stderr)
    if only_vendor:
        print(f"  in vendored, missing from legacy: {only_vendor}", file=sys.stderr)
    if not only_legacy and not only_vendor:
        # Order alone -- which still changes the module_not_found body.
        for i, (a, b) in enumerate(zip(actual, expected)):
            if a != b:
                print(f"  same set, different order: index {i} "
                      f"vendored={a!r} legacy={b!r}", file=sys.stderr)
                break
    return 1


def self_test() -> int:
    php = """<?php
    class ApiModule {
        const AUTH = 'auth';
        const CONFIGS = 'configs';
        const REPORTS = 'reports';
        public static function allowedList(): array {
            return [ self::AUTH, self::CONFIGS, self::REPORTS ];
        }
    }"""
    parsed = parse_allowed(php)
    assert parsed == ["auth", "configs", "reports"], parsed
    assert report(parsed, parsed) == 0
    assert report(["auth", "configs"], parsed) == 1, "a missing module must fail"
    assert report(["configs", "auth", "reports"], parsed) == 1, "a reorder must fail"
    assert report(["auth", "config", "reports"], parsed) == 1, "a typo must fail"
    try:
        parse_allowed("<?php class ApiModule {}")
    except ValueError:
        pass
    else:
        raise AssertionError("a missing allowedList() must raise")
    print("self-test passed")
    return 0


def main() -> int:
    argv = sys.argv[1:]
    if "--legacy" not in argv:
        print("pass --legacy PATH, or run --self-test which needs no checkout",
              file=sys.stderr)
        return 2
    legacy = Path(argv[argv.index("--legacy") + 1]).resolve()
    source = legacy / SOURCE_REL
    if not source.is_file():
        print(f"not found: {source}", file=sys.stderr)
        return 2
    expected = parse_allowed(source.read_text(encoding="utf-8"))
    if "--write" in argv:
        header = VENDORED.read_text(encoding="utf-8").split("\n\n")[0] \
            if VENDORED.is_file() else ""
        head = "\n".join(ln for ln in VENDORED.read_text(encoding="utf-8").splitlines()
                         if ln.startswith("#")) if VENDORED.is_file() else ""
        VENDORED.write_text((head + "\n" if head else "") + "\n".join(expected) + "\n",
                            encoding="utf-8")
        print(f"wrote {len(expected)} modules to {VENDORED.relative_to(ROOT)}")
        return 0
    return report(read_vendored(), expected)


if __name__ == "__main__":
    sys.exit(self_test() if "--self-test" in sys.argv else main())

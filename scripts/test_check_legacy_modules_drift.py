#!/usr/bin/env python3
"""Deterministic regression tests for scripts/check_legacy_modules_drift.py.

Run directly: `python3 scripts/test_check_legacy_modules_drift.py`. Exits 0
on success, 1 on any failure, printing every case so a broken guard is easy
to diagnose.

Fixture-based and hermetic: no sibling `hr-legacy` checkout. Every case
drives the real `parse_allowed()` and `report()` over literal PHP text.

Why the ordering cases carry their weight: `index.php` emits
`implode(', ', $allowedModules)` straight into the `module_not_found` body,
so a reordering is a client-visible wire change and not a cosmetic one. A
guard that compared sets rather than sequences would pass it silently --
which is the specific failure this file exists to prevent.

Wired into: scripts/validate_phase0.py's script/test-sibling rule, and
.github/workflows/backend-validate.yml via `--self-test`.
"""

from __future__ import annotations

import io
import sys
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import check_legacy_modules_drift as drift  # noqa: E402

PHP = """<?php
final class ApiModule {
    const AUTH     = 'auth';
    const CONFIGS  = 'configs';
    const REPORTS  = 'reports';
    const EMPLOYEES = 'employees';
    public static function allowedList(): array {
        return [
            self::AUTH,
            self::CONFIGS,
            self::REPORTS,
            self::EMPLOYEES,
        ];
    }
}"""

EXPECTED = ["auth", "configs", "reports", "employees"]

failures: list[str] = []


def case(name: str, condition: bool) -> None:
    print(f"{'OK  ' if condition else 'FAIL'} {name}")
    if not condition:
        failures.append(name)


def quiet_report(actual, expected) -> int:
    """report() prints its diagnosis; the tests care about the exit code."""
    with redirect_stdout(io.StringIO()), redirect_stderr(io.StringIO()):
        return drift.report(actual, expected)


def diagnosis(actual, expected) -> str:
    err = io.StringIO()
    with redirect_stdout(io.StringIO()), redirect_stderr(err):
        drift.report(actual, expected)
    return err.getvalue()


def main() -> int:
    parsed = drift.parse_allowed(PHP)
    case("parses the literal values, resolving self:: constants", parsed == EXPECTED)
    case("preserves PHP's declaration order, not alphabetical",
         parsed != sorted(parsed) and parsed[0] == "auth")

    case("identical lists pass", quiet_report(EXPECTED, EXPECTED) == 0)
    case("a missing module fails", quiet_report(EXPECTED[:-1], EXPECTED) == 1)
    case("an extra module fails", quiet_report(EXPECTED + ["ghost"], EXPECTED) == 1)
    case("a typo fails", quiet_report(["auth", "config", "reports", "employees"], EXPECTED) == 1)

    reordered = ["configs", "auth", "reports", "employees"]
    case("a reorder fails even though the set is identical",
         quiet_report(reordered, EXPECTED) == 1)
    case("a reorder is diagnosed as an ordering difference, not a missing module",
         "different order" in diagnosis(reordered, EXPECTED))
    case("a missing module names the module",
         "reports" in diagnosis(["auth", "configs", "employees"], EXPECTED))

    for bad, why in [
        ("<?php final class ApiModule {}", "no allowedList()"),
        ("<?php class ApiModule { public static function allowedList(): array "
         "{ return [ self::GHOST ]; } }", "an unresolved constant"),
    ]:
        try:
            drift.parse_allowed(bad)
        except ValueError:
            case(f"raises on {why}", True)
        else:
            case(f"raises on {why}", False)

    real = drift.VENDORED
    case("the vendored file this guard protects exists", real.is_file())
    if real.is_file():
        vendored = drift.read_vendored()
        case("the vendored file has the 38 modules allowedList() declares",
             len(vendored) == 38)
        case("comments and blank lines are not read as modules",
             all(m and not m.startswith("#") for m in vendored))
        case("`reports` is present despite having no directory (C4)",
             "reports" in vendored)
        case("`time` is absent, which is what makes time/now a 404 (O-3)",
             "time" not in vendored)

    print()
    if failures:
        print(f"{len(failures)} FAILURE(S): {failures}")
        return 1
    print("all cases passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())

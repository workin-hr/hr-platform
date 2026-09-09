#!/usr/bin/env python3
"""Deterministic regression tests for check_legacy_sensitive_keys_drift.py.

Run directly: `python3 scripts/test_check_legacy_sensitive_keys_drift.py`.
Fixture-based and hermetic: no sibling `hr-legacy` checkout. Every case drives
the real `php_column_constants()`, `php_keys()`, `java_keys()` and `compare()`
over literal source text.

The case that carries the most weight is `a_stale_java_list_is_caught`: it is
the exact shape that shipped. `505004f` added `Column::IP`, four copies of the
list in the port kept the old pair, and every test that named the pair agreed
with them. A guard that reported only "the lists differ" would have been
enough to catch it, but the direction matters for the operator reading it --
serving a field PHP suppresses is a disclosure, withholding one PHP returns is
a missing field -- so both directions are asserted separately.

Wired into: scripts/validate_phase0.py's script/test-sibling rule.
"""

from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import check_legacy_sensitive_keys_drift as drift  # noqa: E402

COLUMNS = """
class Column {
    public const PASSWORD_HASH = 'password_hash';
    public const TOKEN_VERSION = 'token_version';
    public const IP = 'ip';
}
"""

TODAYS_PHP = """
function sensitive_response_keys(): array {
    return [Column::PASSWORD_HASH, Column::TOKEN_VERSION, Column::IP];
}
"""

PRE_505004F_PHP = """
function sensitive_response_keys(): array {
    return [Column::PASSWORD_HASH, Column::TOKEN_VERSION];
}
"""


def case(failures: list[str], name: str, ok: bool) -> None:
    print(("OK  " if ok else "FAIL ") + name)
    if not ok:
        failures.append(name)


def main() -> int:
    failures: list[str] = []
    constants = drift.php_column_constants(COLUMNS)
    today = drift.php_keys(TODAYS_PHP, constants)

    case(failures, "Column:: constants resolve to their string values",
         constants == {"PASSWORD_HASH": "password_hash",
                       "TOKEN_VERSION": "token_version", "IP": "ip"})

    case(failures, "today's PHP names all three keys, in order",
         today == ["password_hash", "token_version", "ip"])

    case(failures, "the pre-505004f function names only two",
         drift.php_keys(PRE_505004F_PHP, constants) == ["password_hash", "token_version"])

    case(failures, "a matching Java list passes",
         drift.compare(today, drift.java_keys(
             'SENSITIVE_KEYS = List.of("password_hash", "token_version", "ip");')) == [])

    stale = drift.compare(today, drift.java_keys(
        'SENSITIVE_KEYS = List.of("password_hash", "token_version");'))
    case(failures, "a_stale_java_list_is_caught -- the shape that actually shipped",
         len(stale) == 1 and "'ip'" in stale[0] and "serving a field PHP suppresses" in stale[0])

    extra = drift.compare(["password_hash"], ["password_hash", "ip"])
    case(failures, "the opposite direction is reported as withholding, not serving",
         len(extra) == 1 and "withholding a field PHP returns" in extra[0])

    both = drift.compare(["password_hash", "ip"], ["password_hash", "token_version"])
    case(failures, "both directions at once are reported separately", len(both) == 2)

    case(failures, "order is not compared, because unset() is order-free",
         drift.compare(["a", "b"], ["b", "a"]) == [])

    case(failures, "a literal entry works, in case upstream stops using Column::",
         drift.php_keys("function sensitive_response_keys(): array { return ['ip']; }", {}) == ["ip"])

    unresolved = drift.php_keys(
        "function sensitive_response_keys(): array { return [Column::NEW_SECRET]; }", constants)
    case(failures, "an unresolvable constant is kept, never silently dropped",
         unresolved == ["NEW_SECRET"])

    def refuses(parse) -> bool:
        """A source the parser cannot read must raise, never return nothing:
        an empty result would read as "no keys" and compare clean."""
        try:
            parse()
            return False
        except ValueError:
            return True

    case(failures, "php_keys() refuses a source with no such function",
         refuses(lambda: drift.php_keys("nothing here", {})))
    case(failures, "java_keys() refuses a source with no such list",
         refuses(lambda: drift.java_keys("nothing here")))

    print()
    if failures:
        print(f"{len(failures)} FAILURE(S): {failures}")
        return 1
    print("all cases passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())

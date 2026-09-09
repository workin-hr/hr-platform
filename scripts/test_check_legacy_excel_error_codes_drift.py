#!/usr/bin/env python3
"""Deterministic regression tests for check_legacy_excel_error_codes_drift.py.

Run directly: `python3 scripts/test_check_legacy_excel_error_codes_drift.py`.
Fixture-based and hermetic: no sibling `hr-legacy` checkout. Every case drives
the real `php_codes()`, `java_codes()` and `compare()` over literal source.

Why this guard exists at all: both catalogs end in a fall-through
(`default => $code` / `default -> code`), so a code with no arm ships as a raw
token rather than failing anywhere.

Why it compares MAPPINGS and not code names: a code-set comparison passes a
port that answers `gender_invalid` with the wrong column, or renders the wrong
sentence. The code is present on both sides and the mapping is still wrong.
`a_wrong_mapping_is_caught` is that case.

Both sides also group codes -- PHP `'a', 'b' => ...` and Java
`case "a", "b" ->` -- so a parser reading only the first key of a group would
under-report one side and invent drift on the other; and PHP writes its values
three ways (a literal, `Request::X`, `Column::X`), all of which must resolve to
the same string the Java literal holds.

Wired into: scripts/validate_phase0.py's script/test-sibling rule.
"""

from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import check_legacy_excel_error_codes_drift as drift  # noqa: E402

PHP = """
function employee_excel_error_message(string $code, array $row = []): string {
    return match ($code) {
        'first_name_required' => 'a',
        'gender_invalid' => 'b',
        'invalid_phone', 'invalid_phone_number' => 'c',
        'employee_not_found' => $empCode !== '' ? "d ($empCode)" : 'd',
        default => $code,
    };
}

function employee_excel_error_field_key(string $code): ?string {
    return match ($code) {
        'employee_not_found' => Request::EMPLOYEE_CODE,
        'gender_invalid' => Request::GENDER,
        default => null,
    };
}
"""

JAVA_COMPLETE = '''
	public static String message(String code, Context context) {
		return switch (code) {
			case "first_name_required" -> "a";
			case "gender_invalid" -> "b";
			case "invalid_phone", "invalid_phone_number" -> "c";
			case "employee_not_found" -> "d";
			default -> code;
		};
	}

	public static String fieldKey(String code) {
		return switch (code) {
			case "employee_not_found" -> "employee_code";
			case "gender_invalid" -> "gender";
			default -> null;
		};
	}
'''

JAVA_PRE_505004F = '''
	public static String message(String code, Context context) {
		return switch (code) {
			case "first_name_required" -> "a";
			case "invalid_phone", "invalid_phone_number" -> "c";
			default -> code;
		};
	}
'''


def case(failures: list[str], name: str, ok: bool) -> None:
    print(("OK  " if ok else "FAIL ") + name)
    if not ok:
        failures.append(name)


CONSTANTS = drift.php_constants([
    "class Request { public const GENDER = 'gender';"
    " public const EMPLOYEE_CODE = 'employee_code'; }",
    "class Column { public const EXPECTED_DAILY_HOURS = 'expected_daily_hours'; }",
])

PHP_FIELD_KEY = """
function employee_excel_error_field_key(string $code): ?string {
    return match ($code) {
        'first_name_required' => 'first_name',
        'gender_invalid' => Request::GENDER,
        'expected_daily_hours_required' => Column::EXPECTED_DAILY_HOURS,
        'shift_required',
        'shift_not_found' => 'shift_name',
        default => null,
    };
}
"""

JAVA_FIELD_KEY = '''
	public static String fieldKey(String code) {
		return switch (code) {
			case "first_name_required" -> "first_name";
			case "gender_invalid" -> "gender";
			case "expected_daily_hours_required" -> "expected_daily_hours";
			case "shift_required", "shift_not_found" -> "shift_name";
			default -> null;
		};
	}
'''


def main() -> int:
    failures: list[str] = []

    php = drift.php_arms(PHP_FIELD_KEY, "employee_excel_error_field_key", CONSTANTS)
    java = drift.java_arms(JAVA_FIELD_KEY, "fieldKey")

    case(failures, "PHP arms resolve a bare literal", php.get("first_name_required") == "first_name")
    case(failures, "PHP arms resolve Request::", php.get("gender_invalid") == "gender")
    case(failures, "PHP arms resolve Column::",
         php.get("expected_daily_hours_required") == "expected_daily_hours")
    case(failures, "a grouped PHP arm gives every key the same value",
         php.get("shift_required") == "shift_name" and php.get("shift_not_found") == "shift_name")
    case(failures, "`default =>` is not an arm", "default" not in php)

    case(failures, "a grouped Java arm gives every label the same value",
         java.get("shift_required") == "shift_name" and java.get("shift_not_found") == "shift_name")
    case(failures, "`default ->` is not an arm", "default" not in java)

    found, presence_only = drift.compare("field_key", php, java)
    case(failures, "an agreeing catalog passes with nothing left uncompared",
         found == [] and presence_only == 0)

    # The finding this guard was strengthened for: same codes, wrong target.
    wrong = dict(java, gender_invalid="employee_code")
    found, _ = drift.compare("field_key", php, wrong)
    case(failures, "a_wrong_mapping_is_caught -- what a code-set comparison misses",
         len(found) == 1 and "maps to 'employee_code'" in found[0] and "'gender' in PHP" in found[0])

    short = {k: v for k, v in java.items() if k != "gender_invalid"}
    found, _ = drift.compare("field_key", php, short)
    case(failures, "a missing arm is still reported as a fall-through",
         bool(found) and "ship as a raw code" in found[0])

    found, _ = drift.compare("field_key", php, dict(java, invented="x"))
    case(failures, "an arm the port invented is reported the other way",
         bool(found) and "unknown to PHP" in found[0])

    # An interpolated arm cannot be compared as text; it must be counted rather
    # than silently passed, so the output says what was not checked.
    found, presence_only = drift.compare(
        "message", {"invalid_phone": drift.INTERPOLATED}, {"invalid_phone": "anything"})
    case(failures, "an interpolated arm is counted, not compared, and not a failure",
         found == [] and presence_only == 1)

    found, _ = drift.compare(
        "message", {"a": drift.INTERPOLATED, "b": "x"}, {"a": "anything", "b": "y"})
    case(failures, "a literal arm beside an interpolated one is still compared",
         len(found) == 1 and "'b'" in found[0])

    def refuses(parse) -> bool:
        """An unparseable source must raise: an empty map would read as "this
        catalog handles nothing" and compare as total drift, or as clean when
        both sides fail to parse."""
        try:
            parse()
            return False
        except ValueError:
            return True

    case(failures, "php_arms() refuses a function it cannot find",
         refuses(lambda: drift.php_arms(PHP_FIELD_KEY, "no_such_function", CONSTANTS)))
    case(failures, "java_arms() refuses a method it cannot find",
         refuses(lambda: drift.java_arms(JAVA_FIELD_KEY, "noSuchMethod")))

    print()
    if failures:
        print(f"{len(failures)} FAILURE(S): {failures}")
        return 1
    print("all cases passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())

#!/usr/bin/env python3
"""Deterministic regression tests for check_legacy_excel_error_codes_drift.py.

Run directly: `python3 scripts/test_check_legacy_excel_error_codes_drift.py`.
Fixture-based and hermetic: no sibling `hr-legacy` checkout. Every case drives
the real `php_codes()`, `java_codes()` and `compare()` over literal source.

Why this guard exists at all, and why the multi-key cases matter: both
catalogs end in a fall-through (`default => $code` / `default -> code`), so a
code with no arm ships as a raw token rather than failing anywhere. Both sides
also group codes -- PHP `'a', 'b' => ...` and Java `case "a", "b" ->` -- so a
parser that read only the first key of a group would under-report one side and
invent drift on the other.

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


def main() -> int:
    failures: list[str] = []

    php_message = drift.php_codes(PHP, "employee_excel_error_message")
    case(failures, "PHP arms are read, grouped keys included",
         php_message == {"first_name_required", "gender_invalid", "invalid_phone",
                         "invalid_phone_number", "employee_not_found"})

    case(failures, "`default =>` is not mistaken for a code", "default" not in php_message)

    case(failures, "the field-key function is read independently of the message one",
         drift.php_codes(PHP, "employee_excel_error_field_key")
         == {"employee_not_found", "gender_invalid"})

    java_message = drift.java_codes(JAVA_COMPLETE, "message")
    case(failures, "Java cases are read, grouped labels included",
         java_message == php_message)

    case(failures, "`default ->` is not mistaken for a code", "default" not in java_message)

    case(failures, "fieldKey() is read without picking up message()'s cases",
         drift.java_codes(JAVA_COMPLETE, "fieldKey") == {"employee_not_found", "gender_invalid"})

    case(failures, "a complete catalog passes",
         drift.compare("message", php_message, java_message) == [])

    stale = drift.compare("message", php_message, drift.java_codes(JAVA_PRE_505004F, "message"))
    case(failures, "the shape that shipped is caught, naming every missing code",
         len(stale) == 1
         and "gender_invalid" in stale[0] and "employee_not_found" in stale[0]
         and "ship as a raw code" in stale[0])

    extra = drift.compare("message", set(), {"invented"})
    case(failures, "a code the port knows and PHP does not is reported the other way",
         len(extra) == 1 and "unknown to PHP" in extra[0])

    case(failures, "both directions at once are two separate lines",
         len(drift.compare("message", {"a"}, {"b"})) == 2)

    def refuses(parse) -> bool:
        """An unparseable source must raise: returning an empty set would read
        as "this catalog handles nothing" and compare as total drift, or worse,
        as clean when both sides fail to parse."""
        try:
            parse()
            return False
        except ValueError:
            return True

    case(failures, "php_codes() refuses a function it cannot find",
         refuses(lambda: drift.php_codes(PHP, "no_such_function")))
    case(failures, "java_codes() refuses a method it cannot find",
         refuses(lambda: drift.java_codes(JAVA_COMPLETE, "noSuchMethod")))

    print()
    if failures:
        print(f"{len(failures)} FAILURE(S): {failures}")
        return 1
    print("all cases passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())

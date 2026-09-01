#!/usr/bin/env python3
"""Deterministic regression tests for scripts/check_client_endpoints_drift.py.

Run directly: `python3 scripts/test_check_client_endpoints_drift.py`.

Fixture-based and hermetic: no Flutter submodule checkout. Every case drives
the real extract()/report() over literal Dart text.

Why this guard matters: all three harness sweeps treat client-endpoints.txt as
the complete client-addressable surface. A stale list does not fail loudly --
the sweeps keep iterating the same lines and report full coverage while
silently omitting a route the clients added or renamed.

Wired into: scripts/validate_phase0.py's script/test-sibling rule, and
.github/workflows/backend-validate.yml.
"""
from __future__ import annotations

import io
import sys
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import check_client_endpoints_drift as drift  # noqa: E402

DART = """
class ApiConstants {
  static const String baseUrl        = 'https://workin.company/apis/api/';
  static const String loginEndpoint  = 'auth/login_employee';
  static const String listEndpoint   = "employees/list";
  static const String deepEndpoint   = 'attendance/employee_monthly_attendance';
  static const String leadingSlash   = '/not/an/endpoint';
  static const String plain          = 'plainword';
  static const int    pageSize       = 20;
}"""

EXPECTED = {"auth/login_employee", "employees/list",
            "attendance/employee_monthly_attendance"}

failures: list[str] = []


def case(name: str, ok: bool) -> None:
    print(f"{'OK  ' if ok else 'FAIL'} {name}")
    if not ok:
        failures.append(name)


def quiet(actual, expected) -> int:
    with redirect_stdout(io.StringIO()), redirect_stderr(io.StringIO()):
        return drift.report(set(actual), set(expected))


def diagnosis(actual, expected) -> str:
    err = io.StringIO()
    with redirect_stdout(io.StringIO()), redirect_stderr(err):
        drift.report(set(actual), set(expected))
    return err.getvalue()


def main() -> int:
    got = drift.extract(DART)
    case("extracts module/action constants", got == EXPECTED)
    case("ignores a full URL", "https://workin.company/apis/api/" not in got)
    case("ignores a leading-slash path", not any(e.startswith("/") for e in got))
    case("ignores a value with no slash", "plainword" not in got)
    case("accepts double-quoted values", "employees/list" in got)

    case("identical sets pass", quiet(EXPECTED, EXPECTED) == 0)
    case("a client-added endpoint fails", quiet(EXPECTED, EXPECTED | {"new/one"}) == 1)
    case("a removed endpoint fails", quiet(EXPECTED | {"stale/one"}, EXPECTED) == 1)
    case("an added endpoint is named in the diagnosis",
         "new/one" in diagnosis(EXPECTED, EXPECTED | {"new/one"}))
    case("a removed endpoint is named in the diagnosis",
         "stale/one" in diagnosis(EXPECTED | {"stale/one"}, EXPECTED))

    try:
        drift.extract_all(Path("/nonexistent-clients-dir"))
    except FileNotFoundError:
        case("raises when no api_constants.dart is present", True)
    else:
        case("raises when no api_constants.dart is present", False)

    if drift.VENDORED.is_file():
        v = drift.read_vendored()
        case("the vendored list is populated", len(v) > 100)
        case("every vendored entry is module/action", all("/" in e for e in v))
        case("no vendored entry carries a scheme or leading slash",
             not any(e.startswith("/") or "://" in e for e in v))

    print()
    if failures:
        print(f"{len(failures)} FAILURE(S): {failures}")
        return 1
    print("all cases passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())

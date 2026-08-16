#!/usr/bin/env python3
"""Regression test sibling for scripts/check_legacy_schema_drift.py.

Required by validate_phase0.py's "every validator/guard script must ship
with fixture-based regression tests" rule. The fixture-based cases
already live in check_legacy_schema_drift.py's own self_test() -- header
stripping, headerless input, a changed line reported with both sides and
a line number, and an added upstream line reported rather than silently
truncated by the zip() comparison -- so this is a thin, direct
invocation of that same logic rather than a duplicate suite, and the two
cannot drift apart.

Deliberately does not exercise the real comparison: that needs a sibling
hr-legacy checkout, which neither CI nor this test has. Same split as
scripts/etl/coverage_audit.py, whose --check is likewise operator-only.

Run directly: `python3 scripts/test_check_legacy_schema_drift.py`.
Also runnable as
`python3 scripts/check_legacy_schema_drift.py --self-test`
(wired into .github/workflows/backend-validate.yml).
"""

from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import check_legacy_schema_drift  # noqa: E402

if __name__ == "__main__":
    sys.exit(check_legacy_schema_drift.self_test())

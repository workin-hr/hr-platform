#!/usr/bin/env python3
"""Regression test sibling for scripts/check_flyway_versions.py.

Required by validate_phase0.py's "every validator/guard script must ship
with fixture-based regression tests" rule. The actual fixture-based cases
already live in check_flyway_versions.py's own self_test() (temp-directory
fixtures covering the clean-tree, cross-directory-duplicate,
underscore/dot-normalization, and non-versioned-file-ignored shapes) --
this is a thin, direct invocation of that same logic, not a duplicate
suite, so the two can never drift apart.

Run directly: `python3 scripts/test_check_flyway_versions.py`.
Also runnable as `python3 scripts/check_flyway_versions.py --self-test`
(already wired into .github/workflows/backend-validate.yml).

Wired into: scripts/verify-bootstrap.sh (required, not skippable).
"""

from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import check_flyway_versions  # noqa: E402

if __name__ == "__main__":
    sys.exit(check_flyway_versions.self_test())

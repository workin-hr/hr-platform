#!/usr/bin/env python3
"""Regression test sibling for scripts/migration_diff.py.

Required by validate_phase0.py's "every validator/guard script must ship
with fixture-based regression tests" rule. The actual fixture-based cases
already live in migration_diff.py's own self_test() (temp-directory CSV
fixtures covering identical exports, baseline-count match/mismatch,
row-count mismatch, changed cells, missing/duplicate keys, and NULL-vs-
empty-string) -- this is a thin, direct invocation of that same logic,
not a duplicate suite, so the two can never drift apart.

Run directly: `python3 scripts/test_migration_diff.py`.
Also runnable as `python3 scripts/migration_diff.py --self-test`
(already wired into .github/workflows/backend-validate.yml).

Wired into: scripts/verify-bootstrap.sh (required, not skippable).
"""

from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import migration_diff  # noqa: E402

if __name__ == "__main__":
    sys.exit(migration_diff.self_test())

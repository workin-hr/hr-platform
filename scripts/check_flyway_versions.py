#!/usr/bin/env python3
"""Fail when two Flyway migrations claim the same version number.

Why this exists: two branches cut from the same base each claimed
V8-V10 (PR #37 payroll-group-schema, PR #38 sessions/revocation/audit).
Git merged them without textual conflict -- different filenames -- but
Flyway rejects duplicate versions, so every context boot on main failed
(fixed in PR #40 by renumbering). Per-branch CI cannot catch this
before merge when the branches are not up to date with main; this check
makes the post-merge failure mode explicit and instant on main, and
fails the offending PR the moment its branch is rebased/updated. The
structural prevention is the "Require branches to be up to date before
merging" branch-protection setting, which is a manual GitHub setting,
not something this repository's files can enforce.

All configured Flyway location directories share one version space
(spring.flyway.locations lists them together), so versions are
collected across every directory under MIGRATION_ROOT, not per
directory.

Usage:
    python3 scripts/check_flyway_versions.py              # check the repo
    python3 scripts/check_flyway_versions.py --self-test  # run self-tests
"""

from __future__ import annotations

import re
import sys
import tempfile
from collections import defaultdict
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
MIGRATION_ROOT = REPO_ROOT / "backend" / "src" / "main" / "resources" / "db" / "migration"

# Flyway versioned-migration filename: V<version>__<description>.sql,
# where <version> may use dots or underscores as separators (V1, V1.1,
# V1_1 are all valid; V1_1 and V1.1 are the SAME version to Flyway).
VERSIONED_MIGRATION = re.compile(r"^V(?P<version>[0-9][0-9._]*?)__.+\.sql$")


def collect_duplicates(migration_root: Path) -> dict[str, list[Path]]:
    """Map normalized version -> file paths, keeping only duplicates."""
    by_version: dict[str, list[Path]] = defaultdict(list)
    for path in sorted(migration_root.rglob("V*.sql")):
        match = VERSIONED_MIGRATION.match(path.name)
        if match:
            normalized = match.group("version").replace("_", ".")
            by_version[normalized].append(path)
    return {version: paths for version, paths in by_version.items() if len(paths) > 1}


def main() -> int:
    if not MIGRATION_ROOT.is_dir():
        print(f"FAIL migration root not found: {MIGRATION_ROOT}")
        return 1
    duplicates = collect_duplicates(MIGRATION_ROOT)
    if duplicates:
        print("Duplicate Flyway migration versions found -- Flyway will refuse to run:")
        for version, paths in sorted(duplicates.items()):
            print(f"  V{version}:")
            for path in paths:
                print(f"    {path.relative_to(REPO_ROOT)}")
        print(
            "Renumber one side to the next free version. If this is a merged PR "
            "racing another schema branch, see PR #40 for the precedent."
        )
        return 1
    print("OK  no duplicate Flyway migration versions")
    return 0


def self_test() -> int:
    failures: list[str] = []

    def check(name: str, condition: bool) -> None:
        print(("OK  " if condition else "FAIL ") + name)
        if not condition:
            failures.append(name)

    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        (root / "common").mkdir()
        (root / "rls").mkdir()
        (root / "common" / "V1__a.sql").write_text("select 1;")
        (root / "common" / "V2__b.sql").write_text("select 1;")
        (root / "rls" / "V3__c.sql").write_text("select 1;")
        check("clean tree has no duplicates", collect_duplicates(root) == {})

        # Same version in two different directories -- the real-world
        # failure shape (common vs rls share one version space).
        (root / "rls" / "V2__other.sql").write_text("select 1;")
        check("cross-directory duplicate is detected", set(collect_duplicates(root)) == {"2"})

        # V1_1 and V1.1 are the same version to Flyway.
        (root / "common" / "V1_1__x.sql").write_text("select 1;")
        (root / "common" / "V1.1__y.sql").write_text("select 1;")
        check(
            "underscore and dot separators normalize to the same version",
            "1.1" in collect_duplicates(root),
        )

        # Non-versioned files (repeatable R__, docs, etc.) are ignored.
        (root / "common" / "R__repeatable.sql").write_text("select 1;")
        (root / "common" / "notes.txt").write_text("not sql")
        check("non-versioned files are ignored", set(collect_duplicates(root)) == {"2", "1.1"})

    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(self_test() if "--self-test" in sys.argv else main())

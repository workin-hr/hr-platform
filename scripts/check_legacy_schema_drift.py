#!/usr/bin/env python3
"""Prove the vendored legacy schema still matches hr-legacy's.

Phase 1 runs the Java application against the legacy MySQL schema as an
external contract, so `backend/src/test/resources/legacy/` carries a
vendored copy -- CI checks out only this repository, and the contract
has to be present for tests to run against a real MariaDB.

A vendored copy drifts. This compares the two, ignoring only the
provenance header the vendored file carries, and reports the first
difference in a form somebody can act on.

Like `coverage_audit.py --check`, this needs a sibling `hr-legacy`
checkout and therefore cannot run in CI. `--self-test` needs nothing and
does run there, so the comparison logic itself stays proven.

Usage:
    python3 scripts/check_legacy_schema_drift.py
    python3 scripts/check_legacy_schema_drift.py --legacy ../hr-legacy
    python3 scripts/check_legacy_schema_drift.py --self-test
"""

from __future__ import annotations

import os
import sys

HEADER_END = "-- ---------------------------------------------------------------------"
VENDORED = os.path.join(
    "backend", "src", "test", "resources", "legacy", "mysql_workin.schema.sql"
)
UPSTREAM = os.path.join("..", "hr-legacy", "mysql_workin.schema.sql")


def strip_provenance(text: str) -> str:
    """Drop the vendored file's header block, keep everything after it.

    The header is delimited by a pair of rule lines. Anything before the
    second rule is provenance this repository added; the schema proper
    starts after it. A file with no header (i.e. upstream's) is returned
    unchanged, which is what makes the same function correct for both
    sides of the comparison.
    """
    if not text.startswith(HEADER_END):
        return text
    rest = text[len(HEADER_END):]
    end = rest.find(HEADER_END)
    if end == -1:
        return text
    return rest[end + len(HEADER_END):].lstrip("\n")


def first_difference(vendored: str, upstream: str) -> str | None:
    """-> a human-actionable description, or None when identical."""
    left = strip_provenance(vendored).splitlines()
    right = upstream.splitlines()
    for index, (a, b) in enumerate(zip(left, right), start=1):
        if a != b:
            return (f"line {index} differs\n"
                    f"  vendored: {a!r}\n"
                    f"  hr-legacy: {b!r}")
    if len(left) != len(right):
        longer, name = ((left, "vendored") if len(left) > len(right)
                        else (right, "hr-legacy"))
        return (f"{name} has {abs(len(left) - len(right))} extra line(s), "
                f"first at {min(len(left), len(right)) + 1}: "
                f"{longer[min(len(left), len(right))]!r}")
    return None


def self_test() -> int:
    failures: list[str] = []

    def check(name: str, condition: bool) -> None:
        print(("OK  " if condition else "FAIL ") + name)
        if not condition:
            failures.append(name)

    header = f"{HEADER_END}\n-- Source: somewhere\n{HEADER_END}\n\nCREATE TABLE a (id INT);"
    bare = "CREATE TABLE a (id INT);"
    check("the provenance header is stripped from the vendored side",
          strip_provenance(header) == bare)
    check("a file without a header is returned unchanged",
          strip_provenance(bare) == bare)
    check("identical schemas report no drift",
          first_difference(header, bare) is None)
    check("a changed line is reported with both sides and a line number",
          "line 1 differs" in (first_difference(header, "CREATE TABLE a (id BIGINT);") or ""))
    # The failure that matters most: upstream gaining a column the
    # vendored copy has never seen. Length-only drift is invisible to a
    # zip() comparison, so it needs its own branch and its own check.
    check("an added upstream line is reported, not silently truncated",
          "extra line" in (first_difference(header, bare + "\nALTER TABLE a ADD b INT;") or ""))
    return 1 if failures else 0


def main() -> int:
    legacy_root = os.path.join("..", "hr-legacy")
    if "--legacy" in sys.argv:
        legacy_root = sys.argv[sys.argv.index("--legacy") + 1]
    upstream_path = os.path.join(legacy_root, "mysql_workin.schema.sql")

    if not os.path.exists(VENDORED):
        print(f"vendored schema not found at {VENDORED}", file=sys.stderr)
        return 2
    if not os.path.exists(upstream_path):
        print(f"hr-legacy schema not found at {upstream_path}", file=sys.stderr)
        print("pass --legacy PATH, or run --self-test which needs no checkout",
              file=sys.stderr)
        return 2

    with open(VENDORED, encoding="utf-8") as handle:
        vendored = handle.read()
    with open(upstream_path, encoding="utf-8") as handle:
        upstream = handle.read()

    difference = first_difference(vendored, upstream)
    if difference is None:
        print(f"OK: {VENDORED} matches {upstream_path}")
        return 0
    print("LEGACY SCHEMA DRIFT -- re-vendor rather than patch the copy:")
    print(difference)
    return 1


if __name__ == "__main__":
    sys.exit(self_test() if "--self-test" in sys.argv else main())

#!/usr/bin/env python3
"""Differential reconciliation harness for the MySQL->PostgreSQL migration (PMR-10).

Compares canonical CSV exports of the same logical tables from the
legacy (source) and migrated (target) databases and fails loudly on any
divergence: row counts (optionally against a recorded expected
baseline), duplicate/missing/extra business keys, per-column cell
differences (named by table, key, and column), and a whole-table
checksum over canonicalized rows. Stdlib only, so it runs on any
machine with Python -- including an operator's machine near production,
where this repository's tooling must never need network installs.

The export convention the CSVs must follow (full detail:
docs/migration/migration-correctness-test-plan.md): explicit column
list in a fixed order; rows ordered by the declared key; NULL encoded
as the literal ``\\N`` (distinct from the empty string); timestamps in
UTC ISO-8601; booleans as 0/1; UTF-8 text.

Usage:
    python3 scripts/migration_diff.py --source <dir> --target <dir> --manifest <manifest.json>
    python3 scripts/migration_diff.py --self-test

Manifest format (JSON):
    {
      "tables": [
        {"file": "employees.csv", "key": ["id"], "expected_count": 2871},
        {"file": "hr_permissions_overrides.csv", "key": ["membership_id", "permission_key"]}
      ]
    }

``expected_count`` is the measured legacy baseline
(docs/migration/table-volume-analysis.md) for the snapshot being
migrated; omit it when counts are only compared between the two sides.
"""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import sys
import tempfile
from pathlib import Path

NULL = "\\N"
MAX_NAMED_MISMATCHES_PER_TABLE = 20


class TableReport:
    def __init__(self, name: str):
        self.name = name
        self.findings: list[str] = []
        self.source_count = 0
        self.target_count = 0
        self.checksum_match = False

    def finding(self, message: str) -> None:
        self.findings.append(message)


def read_export(path: Path, key_columns: list[str]) -> tuple[list[str], dict[tuple, list[str]], list[str]]:
    """Return (header, rows keyed by business key, problems)."""
    problems: list[str] = []
    with path.open(newline="", encoding="utf-8") as handle:
        reader = csv.reader(handle)
        try:
            header = next(reader)
        except StopIteration:
            return [], {}, [f"{path.name}: file is empty (no header)"]
        missing_key_columns = [column for column in key_columns if column not in header]
        if missing_key_columns:
            return header, {}, [f"{path.name}: key column(s) {missing_key_columns} not in header"]
        key_indexes = [header.index(column) for column in key_columns]
        rows: dict[tuple, list[str]] = {}
        for line_number, row in enumerate(reader, start=2):
            if len(row) != len(header):
                problems.append(f"{path.name}:{line_number}: {len(row)} fields, header has {len(header)}")
                continue
            key = tuple(row[index] for index in key_indexes)
            if key in rows:
                problems.append(f"{path.name}: duplicate key {key}")
                continue
            rows[key] = row
    return header, rows, problems


def table_checksum(header: list[str], rows: dict[tuple, list[str]]) -> str:
    digest = hashlib.sha256()
    digest.update("\x1f".join(header).encode("utf-8"))
    for key in sorted(rows):
        digest.update(b"\x1e")
        digest.update("\x1f".join(rows[key]).encode("utf-8"))
    return digest.hexdigest()


def compare_table(source_dir: Path, target_dir: Path, spec: dict) -> TableReport:
    file_name = spec["file"]
    key_columns = spec["key"]
    report = TableReport(file_name)

    source_path = source_dir / file_name
    target_path = target_dir / file_name
    for side, path in (("source", source_path), ("target", target_path)):
        if not path.is_file():
            report.finding(f"{side} export missing: {path}")
    if report.findings:
        return report

    source_header, source_rows, source_problems = read_export(source_path, key_columns)
    target_header, target_rows, target_problems = read_export(target_path, key_columns)
    for problem in source_problems + target_problems:
        report.finding(problem)

    if source_header != target_header:
        report.finding(f"header mismatch: source={source_header} target={target_header}")
        return report

    report.source_count = len(source_rows)
    report.target_count = len(target_rows)
    if report.source_count != report.target_count:
        report.finding(f"row count mismatch: source={report.source_count} target={report.target_count}")
    expected_count = spec.get("expected_count")
    if expected_count is not None and report.source_count != expected_count:
        report.finding(
            f"source count {report.source_count} != recorded baseline {expected_count} "
            "(snapshot drift or wrong export)")

    missing_in_target = sorted(set(source_rows) - set(target_rows))
    extra_in_target = sorted(set(target_rows) - set(source_rows))
    for key in missing_in_target[:MAX_NAMED_MISMATCHES_PER_TABLE]:
        report.finding(f"key {key} missing in target")
    for key in extra_in_target[:MAX_NAMED_MISMATCHES_PER_TABLE]:
        report.finding(f"key {key} present only in target")
    if len(missing_in_target) > MAX_NAMED_MISMATCHES_PER_TABLE:
        report.finding(f"... {len(missing_in_target) - MAX_NAMED_MISMATCHES_PER_TABLE} more keys missing in target")
    if len(extra_in_target) > MAX_NAMED_MISMATCHES_PER_TABLE:
        report.finding(f"... {len(extra_in_target) - MAX_NAMED_MISMATCHES_PER_TABLE} more keys only in target")

    named_cell_mismatches = 0
    total_cell_mismatches = 0
    for key in sorted(set(source_rows) & set(target_rows)):
        source_row, target_row = source_rows[key], target_rows[key]
        for column_index, column_name in enumerate(source_header):
            if source_row[column_index] != target_row[column_index]:
                total_cell_mismatches += 1
                if named_cell_mismatches < MAX_NAMED_MISMATCHES_PER_TABLE:
                    report.finding(
                        f"key {key} column '{column_name}': "
                        f"source={source_row[column_index]!r} target={target_row[column_index]!r}")
                    named_cell_mismatches += 1
    if total_cell_mismatches > named_cell_mismatches:
        report.finding(f"... {total_cell_mismatches - named_cell_mismatches} more cell mismatches")

    report.checksum_match = table_checksum(source_header, source_rows) == table_checksum(target_header, target_rows)
    if not report.checksum_match and not report.findings:
        report.finding("checksum mismatch without a named difference -- investigate export ordering/encoding")
    return report


def run(source_dir: Path, target_dir: Path, manifest_path: Path) -> int:
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    failures = 0
    for spec in manifest["tables"]:
        report = compare_table(source_dir, target_dir, spec)
        status = "OK  " if not report.findings else "FAIL"
        print(f"{status} {report.name}: source={report.source_count} target={report.target_count} "
              f"checksum={'match' if report.checksum_match else 'MISMATCH'}")
        for finding in report.findings:
            print(f"     - {finding}")
        if report.findings:
            failures += 1
    print(f"SUMMARY: {len(manifest['tables'])} tables compared, {failures} with findings")
    return 1 if failures else 0


# ---------------------------------------------------------------- self-test

def self_test() -> int:
    failures: list[str] = []

    def check(name: str, condition: bool) -> None:
        print(("OK  " if condition else "FAIL ") + name)
        if not condition:
            failures.append(name)

    def write_csv(directory: Path, name: str, rows: list[list[str]]) -> None:
        with (directory / name).open("w", newline="", encoding="utf-8") as handle:
            csv.writer(handle).writerows(rows)

    header = ["id", "first_name", "note"]

    def scenario(source_rows: list[list[str]], target_rows: list[list[str]], expected_count: int | None = None) -> int:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "source").mkdir()
            (root / "target").mkdir()
            write_csv(root / "source", "t.csv", [header] + source_rows)
            write_csv(root / "target", "t.csv", [header] + target_rows)
            spec: dict = {"file": "t.csv", "key": ["id"]}
            if expected_count is not None:
                spec["expected_count"] = expected_count
            (root / "manifest.json").write_text(json.dumps({"tables": [spec]}), encoding="utf-8")
            return run(root / "source", root / "target", root / "manifest.json")

    identical = [["1", "Nour", NULL], ["2", "Sami", ""]]
    check("identical exports pass", scenario(identical, list(identical)) == 0)
    check("recorded-baseline match passes", scenario(identical, list(identical), expected_count=2) == 0)
    check("recorded-baseline mismatch fails", scenario(identical, list(identical), expected_count=3) == 1)
    check("row-count mismatch fails", scenario(identical, identical[:1]) == 1)
    check("changed cell fails", scenario(identical, [["1", "Noura", NULL], ["2", "Sami", ""]]) == 1)
    check("missing key fails", scenario(identical, [["1", "Nour", NULL], ["3", "Sami", ""]]) == 1)
    check("duplicate key fails", scenario(identical, [["1", "Nour", NULL], ["1", "Nour", NULL]]) == 1)
    check("NULL vs empty string is a difference", scenario([["1", "Nour", NULL]], [["1", "Nour", ""]]) == 1)

    stable_rows = {("1",): ["1", "Nour", NULL]}
    check("checksum is stable across runs",
          table_checksum(header, stable_rows) == table_checksum(header, dict(stable_rows)))

    return 1 if failures else 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path)
    parser.add_argument("--target", type=Path)
    parser.add_argument("--manifest", type=Path)
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        return self_test()
    if not (args.source and args.target and args.manifest):
        parser.error("--source, --target, and --manifest are required (or use --self-test)")
    return run(args.source, args.target, args.manifest)


if __name__ == "__main__":
    sys.exit(main())

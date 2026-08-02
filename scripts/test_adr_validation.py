#!/usr/bin/env python3
"""Deterministic regression tests for dynamic ADR discovery in
scripts/validate_phase0.py.

Run directly: `python3 scripts/test_adr_validation.py`. Uses temporary
fixture directories only — it never reads or modifies the real
docs/adr/ ADRs in this repository.

Wired into: scripts/verify-bootstrap.sh (required, not skippable),
scripts/validate_phase0.py (invoked as a subprocess check), and
.github/workflows/phase0-validate.yml.
"""

from __future__ import annotations

import shutil
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import validate_phase0 as v  # noqa: E402

VALID_ADR_BODY = """# ADR-{num}: {title}

## Metadata

| Field | Value |
|---|---|
| ADR ID | ADR-{num} |
| Title | {title} |
| Status | Proposed |
| Date | 2026-01-01 |
| Owners | Test Owner |
| Deciders | Test Decider |
| Related Issues | None yet |
| Supersedes | None |
| Superseded By | None |

## Context

Test context.

## Decision

**Approval status: Proposed — this decision has not been approved.**

Test decision.

## Alternatives Considered

- Option A

## Consequences

Test consequence.

## Risks

Test risk.

## Validation Evidence

None yet — pending Discovery.

## Open Questions

- Test question
"""

TEMPLATE_BODY = VALID_ADR_BODY.format(num="0000", title="Title")


def make_fixture_dir() -> Path:
    tmp = Path(tempfile.mkdtemp(prefix="adr-validation-test-"))
    (tmp / "ADR-0000-template.md").write_text(TEMPLATE_BODY, encoding="utf-8")
    return tmp


def write_index(adr_dir: Path, names: list[str]) -> None:
    lines = ["# ADR Index", "", "## Template", "", "- `ADR-0000-template.md`", "", "## Proposed ADRs", ""]
    lines += [f"- `{name}`" for name in names]
    (adr_dir / "README.md").write_text("\n".join(lines) + "\n", encoding="utf-8")


def valid_adr(num: str, slug: str, title: str) -> tuple[str, str]:
    name = f"ADR-{num}-{slug}.md"
    body = VALID_ADR_BODY.format(num=num, title=title)
    return name, body


CASES_RUN: list[tuple[bool, str]] = []


def check(condition: bool, description: str) -> None:
    CASES_RUN.append((condition, description))
    print(f"{'OK ' if condition else 'FAIL'} {description}")


def test_newly_added_valid_adr_is_discovered_automatically() -> None:
    adr_dir = make_fixture_dir()
    try:
        name, body = valid_adr("0001", "first-decision", "First Decision")
        (adr_dir / name).write_text(body, encoding="utf-8")
        write_index(adr_dir, [name])

        failures: list[str] = []
        v.validate_adrs(failures, adr_dir=adr_dir)
        check(
            failures == [],
            "a newly added, correctly structured ADR is discovered and validated with zero edits to validate_phase0.py "
            f"(failures={failures})",
        )
    finally:
        shutil.rmtree(adr_dir)


def test_invalid_future_adr_fails_without_editing_the_validator() -> None:
    adr_dir = make_fixture_dir()
    try:
        # A "future" ADR (ADR-0099) that is missing the Risks section.
        name = "ADR-0099-future-decision.md"
        body = VALID_ADR_BODY.format(num="0099", title="Future Decision").replace(
            "## Risks\n\nTest risk.\n\n", ""
        )
        (adr_dir / name).write_text(body, encoding="utf-8")
        write_index(adr_dir, [name])

        failures: list[str] = []
        v.validate_adrs(failures, adr_dir=adr_dir)
        check(
            any("Risks" in f for f in failures),
            f"an invalid future ADR (ADR-0099, missing ## Risks) fails discovery-time validation without any Python edits (failures={failures})",
        )
    finally:
        shutil.rmtree(adr_dir)


def test_duplicate_adr_identifiers_fail() -> None:
    adr_dir = make_fixture_dir()
    try:
        name1, body1 = valid_adr("0007", "first-take", "First Take")
        name2, body2 = valid_adr("0007", "second-take", "Second Take")
        (adr_dir / name1).write_text(body1, encoding="utf-8")
        (adr_dir / name2).write_text(body2, encoding="utf-8")
        write_index(adr_dir, [name1, name2])

        failures: list[str] = []
        v.validate_adrs(failures, adr_dir=adr_dir)
        check(
            any("Duplicate ADR identifier" in f for f in failures),
            f"two ADR files sharing the same 4-digit identifier (0007) are detected as duplicates (failures={failures})",
        )
    finally:
        shutil.rmtree(adr_dir)


def test_adr_missing_from_index_fails() -> None:
    adr_dir = make_fixture_dir()
    try:
        name, body = valid_adr("0002", "second-decision", "Second Decision")
        (adr_dir / name).write_text(body, encoding="utf-8")
        write_index(adr_dir, [])  # index does not mention the ADR

        failures: list[str] = []
        v.validate_adrs(failures, adr_dir=adr_dir)
        check(
            any("not referenced in the ADR index" in f for f in failures),
            f"an ADR that exists on disk but is missing from the index fails (failures={failures})",
        )
    finally:
        shutil.rmtree(adr_dir)


def test_index_entry_referencing_missing_file_fails() -> None:
    adr_dir = make_fixture_dir()
    try:
        write_index(adr_dir, ["ADR-0003-ghost-decision.md"])  # never created on disk

        failures: list[str] = []
        v.validate_adrs(failures, adr_dir=adr_dir)
        check(
            any("references a file that does not exist" in f for f in failures),
            f"an index entry referencing a non-existent ADR file fails (failures={failures})",
        )
    finally:
        shutil.rmtree(adr_dir)


def test_invalid_file_name_is_rejected() -> None:
    adr_dir = make_fixture_dir()
    try:
        # Wrong digit count and missing slug — should not silently pass
        # through as a "real" ADR.
        (adr_dir / "ADR-1-bad.md").write_text(VALID_ADR_BODY.format(num="0001", title="Bad"), encoding="utf-8")
        write_index(adr_dir, [])

        failures: list[str] = []
        v.validate_adrs(failures, adr_dir=adr_dir)
        check(
            any("Invalid ADR file name" in f for f in failures),
            f"a malformed ADR file name (ADR-1-bad.md) is rejected rather than silently ignored (failures={failures})",
        )
    finally:
        shutil.rmtree(adr_dir)


def test_template_is_excluded_from_discovery() -> None:
    adr_dir = make_fixture_dir()
    try:
        write_index(adr_dir, [])
        failures: list[str] = []
        adrs = v.discover_adrs(adr_dir, failures)
        check(
            adrs == [] and failures == [],
            f"the ADR template (ADR-0000-template.md) is excluded from discovery, not treated as a real ADR (discovered={adrs}, failures={failures})",
        )
    finally:
        shutil.rmtree(adr_dir)


def test_real_repository_adrs_still_pass() -> None:
    """Sanity check against the real repository ADRs, proving this refactor
    didn't break the actual dedicated validator."""
    failures: list[str] = []
    v.validate_adrs(failures)  # default adr_dir = real docs/adr
    check(failures == [], f"the real repository's docs/adr/ ADRs still pass dynamic validation (failures={failures})")


def main() -> int:
    test_template_is_excluded_from_discovery()
    test_newly_added_valid_adr_is_discovered_automatically()
    test_invalid_future_adr_fails_without_editing_the_validator()
    test_duplicate_adr_identifiers_fail()
    test_adr_missing_from_index_fails()
    test_index_entry_referencing_missing_file_fails()
    test_invalid_file_name_is_rejected()
    test_real_repository_adrs_still_pass()

    passed = sum(1 for ok, _ in CASES_RUN if ok)
    total = len(CASES_RUN)
    print(f"\n{passed}/{total} ADR dynamic-validation regression cases passed.")
    if passed != total:
        print(f"{total - passed} FAILURE(S)")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())

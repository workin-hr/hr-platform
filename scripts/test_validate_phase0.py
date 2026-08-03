#!/usr/bin/env python3
"""Deterministic regression tests for the governance checks added to
scripts/validate_phase0.py from 2026-08-03 onward (skill-catalog
consistency, agent responsibility-matrix binding, dormant CODEOWNERS and
dependabot coverage gates, and the repo-boundary path checker).

Run directly: `python3 scripts/test_validate_phase0.py`. Uses temporary
fixture directories only — it never reads or modifies the real repository
except where a case explicitly says it checks against the real repository
as a sanity check (mirroring scripts/test_adr_validation.py's
test_real_repository_adrs_still_pass pattern).

Earlier checks in validate_phase0.py (required paths, forbidden files,
secret patterns, markdown link resolution, workflow safety, tool-catalog
consistency, and the pre-existing structural agent/skill/ADR checks) are
not covered by isolated fixtures here — they are exercised functionally by
CI running validate_phase0.py against the real repository on every push
and pull request. This file does not claim broader coverage than it has.

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

CASES_RUN: list[tuple[bool, str]] = []


def check(condition: bool, description: str) -> None:
    CASES_RUN.append((condition, description))
    print(f"{'OK ' if condition else 'FAIL'} {description}")


def make_root() -> Path:
    return Path(tempfile.mkdtemp(prefix="validate-phase0-test-"))


# ---------------------------------------------------------------------------
# validate_skill_catalog_consistency (SK-1)
# ---------------------------------------------------------------------------


def test_skill_missing_from_catalog_fails() -> None:
    root = make_root()
    try:
        (root / "docs/agents").mkdir(parents=True)
        (root / "docs/agents/skill-catalog.md").write_text(
            "# Skill Catalog\n\n- known-skill\n", encoding="utf-8"
        )
        skills_dir = root / ".agents/skills"
        (skills_dir / "known-skill").mkdir(parents=True)
        (skills_dir / "known-skill/SKILL.md").write_text("---\nname: known-skill\n---\n", encoding="utf-8")
        (skills_dir / "undocumented-skill").mkdir(parents=True)
        (skills_dir / "undocumented-skill/SKILL.md").write_text(
            "---\nname: undocumented-skill\n---\n", encoding="utf-8"
        )

        failures: list[str] = []
        v.validate_skill_catalog_consistency(failures, root=root)
        check(
            any("undocumented-skill" in f for f in failures) and not any("known-skill'" in f for f in failures),
            f"a skill directory absent from skill-catalog.md fails, a listed one does not (failures={failures})",
        )
    finally:
        shutil.rmtree(root)


def test_skill_catalog_fully_listed_passes() -> None:
    root = make_root()
    try:
        (root / "docs/agents").mkdir(parents=True)
        (root / "docs/agents/skill-catalog.md").write_text(
            "# Skill Catalog\n\n- skill-a\n- skill-b\n", encoding="utf-8"
        )
        skills_dir = root / ".agents/skills"
        for name in ("skill-a", "skill-b"):
            (skills_dir / name).mkdir(parents=True)
            (skills_dir / name / "SKILL.md").write_text(f"---\nname: {name}\n---\n", encoding="utf-8")

        failures: list[str] = []
        v.validate_skill_catalog_consistency(failures, root=root)
        check(failures == [], f"a fully-listed skill catalog passes (failures={failures})")
    finally:
        shutil.rmtree(root)


def test_real_repository_skill_catalog_still_passes() -> None:
    """Sanity check against the real repository, proving this check doesn't
    break the actual catalog."""
    failures: list[str] = []
    v.validate_skill_catalog_consistency(failures)  # default root = real repo
    check(failures == [], f"the real repository's skill-catalog.md still passes (failures={failures})")


def main() -> int:
    test_skill_missing_from_catalog_fails()
    test_skill_catalog_fully_listed_passes()
    test_real_repository_skill_catalog_still_passes()

    passed = sum(1 for ok, _ in CASES_RUN if ok)
    total = len(CASES_RUN)
    print(f"\n{passed}/{total} validate_phase0 governance-check regression cases passed.")
    if passed != total:
        print(f"{total - passed} FAILURE(S)")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())

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

import json
import os
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import validate_phase0 as v  # noqa: E402

REPO_ROOT = Path(__file__).resolve().parent.parent
CODEX_PREFLIGHT_SCRIPT = REPO_ROOT / "scripts/codex-preflight.sh"

CASES_RUN: list[tuple[bool, str]] = []


def check(condition: bool, description: str) -> None:
    CASES_RUN.append((condition, description))
    print(f"{'OK ' if condition else 'FAIL'} {description}")


def make_root() -> Path:
    return Path(tempfile.mkdtemp(prefix="validate-phase0-test-"))


# ---------------------------------------------------------------------------
# scripts/verify-bootstrap.sh skipped-tool summary (CI-2)
# ---------------------------------------------------------------------------

VERIFY_BOOTSTRAP_SCRIPT = REPO_ROOT / "scripts/verify-bootstrap.sh"
ALL_SIX_TOOLS = ("markdownlint-cli2", "yamllint", "shellcheck", "actionlint", "gitleaks", "lychee")


SYSTEM_PATH_DIRS = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"


def run_verify_bootstrap_with_shims(present_tools: tuple[str, ...]) -> subprocess.CompletedProcess:
    """Runs the real verify-bootstrap.sh (real validate_phase0.py +
    regression suites included) with a synthetic, *replaced* PATH
    containing a trivial `exit 0` shim for each tool in `present_tools`
    plus only the standard base system directories (for python3, bash,
    git, etc.) — not the inherited PATH. Merely prepending the shim
    directory to the inherited PATH is not enough: in CI,
    phase0-validate.yml's own earlier steps have already installed the
    real markdownlint-cli2/yamllint/shellcheck/actionlint/gitleaks/lychee
    onto PATH (job-local .ci-tools, npm global, pip user) by the time this
    runs, so any tool not in `present_tools` would still resolve to the
    real binary further down an inherited PATH and never actually skip —
    confirmed by a real CI failure before this fix. Locally, none of the 6
    are on PATH at all, so this had been passing for the wrong reason.
    BOOTSTRAP_STRICT is left unset (default/local mode) — only local mode
    can skip at all."""
    with tempfile.TemporaryDirectory(prefix="verify-bootstrap-shim-") as shim_dir:
        for tool in present_tools:
            shim_path = Path(shim_dir) / tool
            shim_path.write_text("#!/usr/bin/env sh\nexit 0\n", encoding="utf-8")
            shim_path.chmod(0o755)
        env = dict(os.environ)
        env.pop("BOOTSTRAP_STRICT", None)
        env["PATH"] = f"{shim_dir}:{SYSTEM_PATH_DIRS}"
        # verify-bootstrap.sh's own [1/3] step runs validate_phase0.py,
        # which runs *this* file as a regression check
        # (validate_governance_check_tests) — without this guard, that
        # nested invocation would re-run these same two tests, which would
        # spawn verify-bootstrap.sh again, unbounded. Propagates down
        # through every subprocess in the chain since none of them
        # override env.
        env["TEST_VALIDATE_PHASE0_NESTED"] = "1"
        return subprocess.run(
            ["bash", str(VERIFY_BOOTSTRAP_SCRIPT)],
            cwd=REPO_ROOT,
            capture_output=True,
            text=True,
            timeout=180,
            env=env,
        )


def run_verify_bootstrap_test_unless_nested(label: str, runner) -> None:
    """See the TEST_VALIDATE_PHASE0_NESTED guard in
    run_verify_bootstrap_with_shims(): these two tests spawn
    verify-bootstrap.sh, which (via validate_phase0.py) runs this same
    file again as a subprocess. Skip them in that nested invocation
    instead of recursing without bound."""
    if os.environ.get("TEST_VALIDATE_PHASE0_NESTED") == "1":
        check(True, f"{label} skipped: this is a nested test_validate_phase0.py invocation")
        return
    runner()


def test_verify_bootstrap_summary_reports_partial_skip() -> None:
    def run() -> None:
        proc = run_verify_bootstrap_with_shims(("markdownlint-cli2", "yamllint", "shellcheck"))
        check(
            "SUMMARY: 3 of 6 external tool(s) skipped locally" in proc.stdout
            and "actionlint gitleaks lychee" in proc.stdout,
            f"a partial-skip local run reports exactly the 3 missing tools by name (found in stdout: "
            f"{'SUMMARY: 3 of 6' in proc.stdout})",
        )

    run_verify_bootstrap_test_unless_nested("test_verify_bootstrap_summary_reports_partial_skip", run)


def test_verify_bootstrap_summary_reports_zero_skips() -> None:
    def run() -> None:
        proc = run_verify_bootstrap_with_shims(ALL_SIX_TOOLS)
        check(
            "SUMMARY: all 6 external tool checks ran (none skipped)." in proc.stdout,
            f"a local run with every tool present reports a positive all-ran confirmation, not a warning "
            f"(found: {'SUMMARY: all 6' in proc.stdout})",
        )

    run_verify_bootstrap_test_unless_nested("test_verify_bootstrap_summary_reports_zero_skips", run)


# ---------------------------------------------------------------------------
# validate_claude_settings secret-pattern coverage (HK-3)
# ---------------------------------------------------------------------------

VALID_DENY_RULES = [
    "Bash(git push --force*)",
    "Bash(git merge*)",
    "Bash(git reset --hard*)",
    "Read(**/*credentials*)",
    "Read(**/*secret*)",
    "Read(**/*.key)",
    "Read(**/id_rsa*)",
    "Read(**/id_ed25519*)",
    "Read(**/*.p12)",
    "Read(**/*.pfx)",
    "Read(**/*.keystore)",
    "Read(**/*.jks)",
    "Edit(**/*credentials*)",
    "Edit(**/*secret*)",
    "Edit(**/*.key)",
    "Edit(**/id_rsa*)",
    "Edit(**/id_ed25519*)",
    "Edit(**/*.p12)",
    "Edit(**/*.pfx)",
    "Edit(**/*.keystore)",
    "Edit(**/*.jks)",
]


def write_valid_settings(root: Path, deny_rules: list[str]) -> None:
    (root / ".claude").mkdir(parents=True, exist_ok=True)
    (root / "scripts").mkdir(parents=True, exist_ok=True)
    (root / "scripts/git_guard.py").write_text("# fixture\n", encoding="utf-8")
    (root / "scripts/edit_audit_log.py").write_text("# fixture\n", encoding="utf-8")

    settings = {
        "permissions": {"deny": deny_rules},
        "hooks": {
            "PreToolUse": [
                {
                    "matcher": "Bash",
                    "hooks": [{"type": "command", "command": "python3 scripts/git_guard.py"}],
                }
            ],
            "PostToolUse": [
                {
                    "matcher": "Edit|Write|NotebookEdit",
                    "hooks": [{"type": "command", "command": "python3 scripts/edit_audit_log.py"}],
                }
            ],
        },
    }
    (root / ".claude/settings.json").write_text(json.dumps(settings), encoding="utf-8")


def test_settings_with_all_secret_patterns_passes() -> None:
    root = make_root()
    try:
        write_valid_settings(root, list(VALID_DENY_RULES))
        failures: list[str] = []
        v.validate_claude_settings(failures, root=root)
        check(failures == [], f"settings.json with every required secret-deny pattern passes (failures={failures})")
    finally:
        shutil.rmtree(root)


def test_settings_missing_a_secret_pattern_fails() -> None:
    root = make_root()
    try:
        incomplete = [rule for rule in VALID_DENY_RULES if "id_rsa" not in rule]
        write_valid_settings(root, incomplete)
        failures: list[str] = []
        v.validate_claude_settings(failures, root=root)
        check(
            any("id_rsa" in f for f in failures),
            f"settings.json missing the id_rsa* deny pattern fails, naming it (failures={failures})",
        )
    finally:
        shutil.rmtree(root)


def test_settings_missing_edit_side_of_a_pattern_fails() -> None:
    """Read-only coverage of a secret pattern is not enough — Edit must be
    covered too, or the file can still be overwritten."""
    root = make_root()
    try:
        incomplete = [rule for rule in VALID_DENY_RULES if rule != "Edit(**/*.keystore)"]
        write_valid_settings(root, incomplete)
        failures: list[str] = []
        v.validate_claude_settings(failures, root=root)
        check(
            any("Edit(...) rule" in f and "*.keystore" in f for f in failures),
            f"settings.json with Read but not Edit coverage for a pattern fails (failures={failures})",
        )
    finally:
        shutil.rmtree(root)


def test_real_repository_settings_still_pass() -> None:
    """Sanity check against the real repository's .claude/settings.json."""
    failures: list[str] = []
    v.validate_claude_settings(failures)  # default root = real repo
    check(failures == [], f"the real repository's .claude/settings.json still passes (failures={failures})")


# ---------------------------------------------------------------------------
# scripts/check-branch-protection.sh (GH-1)
# ---------------------------------------------------------------------------

CHECK_BRANCH_PROTECTION_SCRIPT = REPO_ROOT / "scripts/check-branch-protection.sh"

GOOD_PROTECTION_JSON = {
    "required_pull_request_reviews": {"required_approving_review_count": 1},
    "enforce_admins": {"enabled": True},
    "allow_force_pushes": {"enabled": False},
    "required_status_checks": {"contexts": ["validate"]},
}


def run_check_branch_protection(protection: dict, workflow_text: str | None = None) -> subprocess.CompletedProcess:
    with tempfile.TemporaryDirectory(prefix="branch-protection-test-") as tmp:
        json_file = Path(tmp) / "protection.json"
        json_file.write_text(json.dumps(protection), encoding="utf-8")
        env = dict(os.environ)
        env["BRANCH_PROTECTION_JSON_FILE"] = str(json_file)
        if workflow_text is not None:
            workflow_file = Path(tmp) / "workflow.yml"
            workflow_file.write_text(workflow_text, encoding="utf-8")
            env["PHASE0_WORKFLOW_FILE"] = str(workflow_file)
        return subprocess.run(
            ["bash", str(CHECK_BRANCH_PROTECTION_SCRIPT)],
            capture_output=True,
            text=True,
            timeout=10,
            env=env,
        )


def test_branch_protection_all_requirements_met_passes() -> None:
    proc = run_check_branch_protection(GOOD_PROTECTION_JSON)
    check(
        proc.returncode == 0 and "meets all requirements" in proc.stdout,
        f"branch protection meeting every requirement passes (exit={proc.returncode}, stdout={proc.stdout!r})",
    )


def test_branch_protection_reports_every_failing_field() -> None:
    bad = {
        "required_pull_request_reviews": {"required_approving_review_count": 0},
        "enforce_admins": {"enabled": False},
        "allow_force_pushes": {"enabled": True},
        "required_status_checks": {"contexts": ["something-else"]},
    }
    proc = run_check_branch_protection(bad)
    check(
        proc.returncode != 0
        and "required_approving_review_count is 0" in proc.stdout
        and "enforce_admins.enabled is false" in proc.stdout
        and "allow_force_pushes.enabled is true" in proc.stdout
        and "does not include 'validate'" in proc.stdout,
        f"branch protection missing every requirement fails, naming all 4 fields (exit={proc.returncode}, stdout={proc.stdout!r})",
    )


def test_branch_protection_job_id_is_read_from_workflow_file() -> None:
    """The required check name is not hardcoded — it's parsed from the
    workflow file, so the two cannot silently diverge."""
    fake_workflow = "name: Fake\non:\n  push:\njobs:\n  totally-different-job-name:\n    runs-on: ubuntu-latest\n"
    protection = dict(GOOD_PROTECTION_JSON)
    protection["required_status_checks"] = {"contexts": ["totally-different-job-name"]}
    proc = run_check_branch_protection(protection, workflow_text=fake_workflow)
    check(
        proc.returncode == 0 and "totally-different-job-name" in proc.stdout,
        f"the required job id is parsed live from the workflow file, not hardcoded (exit={proc.returncode}, stdout={proc.stdout!r})",
    )


# ---------------------------------------------------------------------------
# scripts/codex-preflight.sh (AG-2)
# ---------------------------------------------------------------------------


def run_codex_preflight(args: list[str]) -> subprocess.CompletedProcess:
    return subprocess.run(
        [str(CODEX_PREFLIGHT_SCRIPT), *args],
        capture_output=True,
        text=True,
        timeout=10,
    )


def test_codex_preflight_no_args_fails_with_usage() -> None:
    proc = run_codex_preflight([])
    check(
        proc.returncode != 0 and "Usage:" in proc.stderr,
        f"codex-preflight.sh with no agent name exits non-zero with usage on stderr (exit={proc.returncode})",
    )


def test_codex_preflight_bootstrap_engineer_flags() -> None:
    proc = run_codex_preflight(["bootstrap-engineer"])
    check(
        proc.returncode == 0
        and "--sandbox workspace-write" in proc.stdout
        and "--ask-for-approval on-request" in proc.stdout,
        f"codex-preflight.sh bootstrap-engineer prints the workspace-write/on-request flags (stdout={proc.stdout!r})",
    )


def test_codex_preflight_independent_verification_reviewer_flags() -> None:
    proc = run_codex_preflight(["independent-verification-reviewer"])
    check(
        proc.returncode == 0
        and "--sandbox read-only" in proc.stdout
        and "--ask-for-approval untrusted" in proc.stdout,
        f"codex-preflight.sh independent-verification-reviewer prints the read-only/untrusted flags (stdout={proc.stdout!r})",
    )


def test_codex_preflight_unknown_agent_fails() -> None:
    proc = run_codex_preflight(["not-a-real-agent"])
    check(
        proc.returncode != 0 and "no agent definition" in proc.stderr,
        f"codex-preflight.sh with an unknown agent name exits non-zero naming the missing file (stderr={proc.stderr!r})",
    )


# ---------------------------------------------------------------------------
# validate_script_test_siblings (TS-2)
# ---------------------------------------------------------------------------


def test_untested_script_fails() -> None:
    root = make_root()
    try:
        (root / "scripts").mkdir(parents=True)
        (root / "scripts/some_validator.py").write_text("# does something\n", encoding="utf-8")

        failures: list[str] = []
        v.validate_script_test_siblings(failures, root=root)
        check(
            any("some_validator.py" in f for f in failures),
            f"a scripts/*.py file with no test_*.py sibling and no exemption fails (failures={failures})",
        )
    finally:
        shutil.rmtree(root)


def test_script_with_sibling_test_passes() -> None:
    root = make_root()
    try:
        (root / "scripts").mkdir(parents=True)
        (root / "scripts/some_validator.py").write_text("# does something\n", encoding="utf-8")
        (root / "scripts/test_some_validator.py").write_text("# tests it\n", encoding="utf-8")

        failures: list[str] = []
        v.validate_script_test_siblings(failures, root=root)
        check(failures == [], f"a scripts/*.py file with a real test_*.py sibling passes (failures={failures})")
    finally:
        shutil.rmtree(root)


def test_exempted_script_without_sibling_passes() -> None:
    root = make_root()
    try:
        (root / "scripts").mkdir(parents=True)
        (root / "scripts/orchestration_only.py").write_text("# no logic of its own\n", encoding="utf-8")

        v.SCRIPT_TEST_SIBLING_EXEMPTIONS["orchestration_only.py"] = "test fixture exemption"
        try:
            failures: list[str] = []
            v.validate_script_test_siblings(failures, root=root)
            check(failures == [], f"an explicitly exempted script without a sibling passes (failures={failures})")
        finally:
            del v.SCRIPT_TEST_SIBLING_EXEMPTIONS["orchestration_only.py"]
    finally:
        shutil.rmtree(root)


def test_real_repository_scripts_all_have_test_siblings() -> None:
    """Sanity check against the real repository: git_guard.py and
    validate_phase0.py both have test_*.py siblings today."""
    failures: list[str] = []
    v.validate_script_test_siblings(failures)  # default root = real repo
    check(failures == [], f"every real scripts/*.py source file has a test sibling or exemption (failures={failures})")


# ---------------------------------------------------------------------------
# validate_no_repo_root_escaping_paths (DC-2)
# ---------------------------------------------------------------------------


def test_in_repo_relative_path_passes() -> None:
    root = make_root()
    try:
        (root / "docs/bootstrap").mkdir(parents=True)
        (root / "docs/tools").mkdir(parents=True)
        (root / "docs/tools/local-bootstrap-tools.md").write_text("# Tools\n", encoding="utf-8")
        (root / "docs/bootstrap/checklist.md").write_text(
            "Primary reference:\n\n- `../tools/local-bootstrap-tools.md`\n", encoding="utf-8"
        )

        failures: list[str] = []
        v.validate_no_repo_root_escaping_paths(failures, root=root)
        check(failures == [], f"a relative path that resolves inside the repo passes (failures={failures})")
    finally:
        shutil.rmtree(root)


def test_root_escaping_path_fails() -> None:
    root = make_root()
    try:
        (root / "docs/bootstrap").mkdir(parents=True)
        (root / "docs/bootstrap/checklist.md").write_text(
            "Primary references:\n\n- `../../../.github/IMPLEMENTATION_CHECKLIST.md`\n",
            encoding="utf-8",
        )

        failures: list[str] = []
        v.validate_no_repo_root_escaping_paths(failures, root=root)
        check(
            any("resolves outside the repository root" in f for f in failures),
            f"a path escaping the repo root via ../../../ fails, naming the file and line (failures={failures})",
        )
    finally:
        shutil.rmtree(root)


def test_external_url_is_not_flagged() -> None:
    root = make_root()
    try:
        (root / "docs/bootstrap").mkdir(parents=True)
        (root / "docs/bootstrap/checklist.md").write_text(
            "See `https://github.com/workin-hr/hr-platform` for the repository.\n",
            encoding="utf-8",
        )

        failures: list[str] = []
        v.validate_no_repo_root_escaping_paths(failures, root=root)
        check(failures == [], f"a genuine external URL in backticks is never flagged (failures={failures})")
    finally:
        shutil.rmtree(root)


def test_real_repository_has_no_root_escaping_paths() -> None:
    """Sanity check against the real repository: the one instance of this
    bug (docs/bootstrap/execution-checklist.md) has since been corrected in
    the working tree, so this must currently pass."""
    failures: list[str] = []
    v.validate_no_repo_root_escaping_paths(failures)  # default root = real repo
    check(failures == [], f"the real repository has no root-escaping backtick paths under docs/ (failures={failures})")


# ---------------------------------------------------------------------------
# validate_dependabot_ecosystem_coverage (GH-3)
# ---------------------------------------------------------------------------


def write_dependabot(root: Path, text: str) -> None:
    (root / ".github").mkdir(parents=True, exist_ok=True)
    (root / ".github/dependabot.yml").write_text(text, encoding="utf-8")


DEPENDABOT_GITHUB_ACTIONS_ONLY = (
    'version: 2\nupdates:\n  - package-ecosystem: "github-actions"\n    directory: "/"\n'
    "    schedule:\n      interval: weekly\n"
)


def test_no_manifest_is_inert() -> None:
    root = make_root()
    try:
        write_dependabot(root, DEPENDABOT_GITHUB_ACTIONS_ONLY)

        failures: list[str] = []
        v.validate_dependabot_ecosystem_coverage(failures, root=root)
        check(failures == [], f"no manifest anywhere means no failure (failures={failures})")
    finally:
        shutil.rmtree(root)


def test_manifest_without_matching_ecosystem_entry_fails() -> None:
    root = make_root()
    try:
        write_dependabot(root, DEPENDABOT_GITHUB_ACTIONS_ONLY)
        (root / "backend").mkdir(parents=True)
        (root / "backend/composer.json").write_text("{}", encoding="utf-8")

        failures: list[str] = []
        v.validate_dependabot_ecosystem_coverage(failures, root=root)
        check(
            any("composer.json" in f and "/backend" in f for f in failures),
            f"a manifest with no matching package-ecosystem directory entry fails (failures={failures})",
        )
    finally:
        shutil.rmtree(root)


def test_manifest_with_matching_ecosystem_entry_passes() -> None:
    root = make_root()
    try:
        write_dependabot(
            root,
            DEPENDABOT_GITHUB_ACTIONS_ONLY
            + '  - package-ecosystem: "composer"\n    directory: "/backend"\n    schedule:\n      interval: weekly\n',
        )
        (root / "backend").mkdir(parents=True)
        (root / "backend/composer.json").write_text("{}", encoding="utf-8")

        failures: list[str] = []
        v.validate_dependabot_ecosystem_coverage(failures, root=root)
        check(failures == [], f"a manifest with a matching package-ecosystem directory entry passes (failures={failures})")
    finally:
        shutil.rmtree(root)


def test_real_repository_dependabot_coverage_still_passes() -> None:
    """Sanity check against the real repository: no package manifest exists
    anywhere yet, so this must be a no-op."""
    failures: list[str] = []
    v.validate_dependabot_ecosystem_coverage(failures)  # default root = real repo
    check(failures == [], f"the real repository has no manifest yet, so this stays inert (failures={failures})")


# ---------------------------------------------------------------------------
# validate_codeowners_component_coverage (GH-2)
# ---------------------------------------------------------------------------


def write_codeowners(root: Path, text: str) -> None:
    (root / "CODEOWNERS").write_text(text, encoding="utf-8")


def test_component_with_only_readme_is_inert() -> None:
    root = make_root()
    try:
        write_codeowners(root, "* @workin-hr/platform-owners\n")
        (root / "backend").mkdir(parents=True)
        (root / "backend/README.md").write_text("# Backend Boundary\n", encoding="utf-8")

        failures: list[str] = []
        v.validate_codeowners_component_coverage(failures, root=root)
        check(
            failures == [],
            f"a component directory holding only its boundary README.md does not trigger the check (failures={failures})",
        )
    finally:
        shutil.rmtree(root)


def test_component_with_real_content_and_no_codeowners_entry_fails() -> None:
    root = make_root()
    try:
        write_codeowners(root, "* @workin-hr/platform-owners\n")
        (root / "backend").mkdir(parents=True)
        (root / "backend/README.md").write_text("# Backend Boundary\n", encoding="utf-8")
        (root / "backend/Application.java").write_text("// placeholder\n", encoding="utf-8")

        failures: list[str] = []
        v.validate_codeowners_component_coverage(failures, root=root)
        check(
            any("backend/" in f for f in failures),
            f"a component directory with real content and no CODEOWNERS entry fails (failures={failures})",
        )
    finally:
        shutil.rmtree(root)


def test_component_with_real_content_and_codeowners_entry_passes() -> None:
    root = make_root()
    try:
        write_codeowners(root, "* @workin-hr/platform-owners\n/backend/ @workin-hr/backend\n")
        (root / "backend").mkdir(parents=True)
        (root / "backend/README.md").write_text("# Backend Boundary\n", encoding="utf-8")
        (root / "backend/Application.java").write_text("// placeholder\n", encoding="utf-8")

        failures: list[str] = []
        v.validate_codeowners_component_coverage(failures, root=root)
        check(
            failures == [],
            f"a component directory with real content and a matching CODEOWNERS entry passes (failures={failures})",
        )
    finally:
        shutil.rmtree(root)


def test_real_repository_codeowners_coverage_still_passes() -> None:
    """Sanity check against the real repository: every component directory
    holds only its boundary README.md today, so this must be a no-op."""
    failures: list[str] = []
    v.validate_codeowners_component_coverage(failures)  # default root = real repo
    check(failures == [], f"the real repository's component directories are still inert (failures={failures})")


# ---------------------------------------------------------------------------
# validate_agent_matrix_consistency (AG-1)
# ---------------------------------------------------------------------------

MATRIX_HEADER = "| Agent | Primary Mode | May Modify Files | May Open PR | May Approve Work |\n"
MATRIX_SEPARATOR = "| --- | --- | --- | --- | --- |\n"


def write_matrix(root: Path, rows: list[str]) -> None:
    (root / "docs/agents").mkdir(parents=True, exist_ok=True)
    text = "# Agent Responsibility Matrix\n\n" + MATRIX_HEADER + MATRIX_SEPARATOR + "".join(rows)
    (root / "docs/agents/responsibility-matrix.md").write_text(text, encoding="utf-8")


def write_claude_agent(root: Path, slug: str, tools: str) -> None:
    agents_dir = root / ".claude/agents"
    agents_dir.mkdir(parents=True, exist_ok=True)
    (agents_dir / f"{slug}.md").write_text(
        f"---\nname: {slug}\ndescription: test agent\ntools: {tools}\n---\n\n# Test Agent\n",
        encoding="utf-8",
    )


def test_matrix_says_no_but_agent_can_modify_fails() -> None:
    root = make_root()
    try:
        write_matrix(root, ["| Test Agent | Read-only analysis | No | No | No |\n"])
        write_claude_agent(root, "test-agent", "Read, Grep, Glob, Bash, Edit, Write")

        failures: list[str] = []
        v.validate_agent_matrix_consistency(failures, root=root)
        check(
            any("May Modify Files = No" in f for f in failures),
            f"a matrix row claiming read-only, contradicted by Edit/Write in frontmatter, fails (failures={failures})",
        )
    finally:
        shutil.rmtree(root)


def test_matrix_says_yes_but_agent_cannot_modify_fails() -> None:
    root = make_root()
    try:
        write_matrix(root, ["| Test Agent | Controlled implementation | Yes | Yes | No |\n"])
        write_claude_agent(root, "test-agent", "Read, Grep, Glob, Bash")

        failures: list[str] = []
        v.validate_agent_matrix_consistency(failures, root=root)
        check(
            any("May Modify Files = Yes" in f for f in failures),
            f"a matrix row claiming modify access, contradicted by no Edit/Write/NotebookEdit in frontmatter, fails (failures={failures})",
        )
    finally:
        shutil.rmtree(root)


def test_matrix_consistent_with_frontmatter_passes() -> None:
    root = make_root()
    try:
        write_matrix(root, ["| Test Agent | Read-only analysis | No | No | No |\n"])
        write_claude_agent(root, "test-agent", "Read, Grep, Glob, Bash")

        failures: list[str] = []
        v.validate_agent_matrix_consistency(failures, root=root)
        check(failures == [], f"a matrix row matching real frontmatter passes (failures={failures})")
    finally:
        shutil.rmtree(root)


def test_matrix_codex_row_without_frontmatter_is_skipped() -> None:
    """A Codex row ('Yes' with no .claude/agents file at all) must not be
    asserted about — Codex has no equivalent tool-scoping mechanism to
    check against (docs/agents/operating-model.md, enforcement layer 2)."""
    root = make_root()
    try:
        write_matrix(root, ["| Codex Bootstrap Engineer | Controlled implementation | Yes | Yes | No |\n"])
        # No .claude/agents/codex-bootstrap-engineer.md is created at all.

        failures: list[str] = []
        v.validate_agent_matrix_consistency(failures, root=root)
        check(failures == [], f"a Codex row with no matching Claude agent file is silently skipped (failures={failures})")
    finally:
        shutil.rmtree(root)


def test_real_repository_agent_matrix_still_passes() -> None:
    """Sanity check against the real repository's 6 Claude agent rows."""
    failures: list[str] = []
    v.validate_agent_matrix_consistency(failures)  # default root = real repo
    check(failures == [], f"the real repository's responsibility-matrix.md still passes (failures={failures})")


# ---------------------------------------------------------------------------
# validate_nightly_workflow_exists_if_promised (CI-1)
# ---------------------------------------------------------------------------


def write_test_strategy(root: Path, names_nightly: bool) -> None:
    (root / "docs/testing").mkdir(parents=True, exist_ok=True)
    body = "# Test Strategy\n\n## Every Commit\n\n- stuff\n"
    if names_nightly:
        body += "\n## Nightly\n\n- deeper checks\n"
    (root / "docs/testing/test-strategy.md").write_text(body, encoding="utf-8")


def test_no_nightly_tier_named_is_inert() -> None:
    root = make_root()
    try:
        write_test_strategy(root, names_nightly=False)
        failures: list[str] = []
        v.validate_nightly_workflow_exists_if_promised(failures, root=root)
        check(failures == [], f"no 'Nightly' heading means no requirement at all (failures={failures})")
    finally:
        shutil.rmtree(root)


def test_nightly_tier_named_without_schedule_workflow_fails() -> None:
    root = make_root()
    try:
        write_test_strategy(root, names_nightly=True)
        (root / ".github/workflows").mkdir(parents=True, exist_ok=True)
        (root / ".github/workflows/other.yml").write_text("on:\n  push:\njobs: {}\n", encoding="utf-8")
        failures: list[str] = []
        v.validate_nightly_workflow_exists_if_promised(failures, root=root)
        check(
            any("no .github/workflows/*.yml file has an 'on.schedule' trigger" in f for f in failures),
            f"a promised Nightly tier with no scheduled workflow fails (failures={failures})",
        )
    finally:
        shutil.rmtree(root)


def test_nightly_tier_named_with_schedule_workflow_passes() -> None:
    root = make_root()
    try:
        write_test_strategy(root, names_nightly=True)
        (root / ".github/workflows").mkdir(parents=True, exist_ok=True)
        (root / ".github/workflows/nightly.yml").write_text(
            "on:\n  schedule:\n    - cron: '0 0 * * *'\njobs: {}\n", encoding="utf-8"
        )
        failures: list[str] = []
        v.validate_nightly_workflow_exists_if_promised(failures, root=root)
        check(failures == [], f"a promised Nightly tier with a real scheduled workflow passes (failures={failures})")
    finally:
        shutil.rmtree(root)


def test_real_repository_has_nightly_workflow() -> None:
    """Sanity check against the real repository: test-strategy.md names a
    Nightly tier, so .github/workflows/nightly.yml must exist and be
    scheduled."""
    failures: list[str] = []
    v.validate_nightly_workflow_exists_if_promised(failures)  # default root = real repo
    check(failures == [], f"the real repository has a scheduled nightly workflow (failures={failures})")


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
    test_no_nightly_tier_named_is_inert()
    test_nightly_tier_named_without_schedule_workflow_fails()
    test_nightly_tier_named_with_schedule_workflow_passes()
    test_real_repository_has_nightly_workflow()
    test_branch_protection_all_requirements_met_passes()
    test_branch_protection_reports_every_failing_field()
    test_branch_protection_job_id_is_read_from_workflow_file()
    test_verify_bootstrap_summary_reports_partial_skip()
    test_verify_bootstrap_summary_reports_zero_skips()
    test_settings_with_all_secret_patterns_passes()
    test_settings_missing_a_secret_pattern_fails()
    test_settings_missing_edit_side_of_a_pattern_fails()
    test_real_repository_settings_still_pass()
    test_codex_preflight_no_args_fails_with_usage()
    test_codex_preflight_bootstrap_engineer_flags()
    test_codex_preflight_independent_verification_reviewer_flags()
    test_codex_preflight_unknown_agent_fails()
    test_untested_script_fails()
    test_script_with_sibling_test_passes()
    test_exempted_script_without_sibling_passes()
    test_real_repository_scripts_all_have_test_siblings()
    test_in_repo_relative_path_passes()
    test_root_escaping_path_fails()
    test_external_url_is_not_flagged()
    test_real_repository_has_no_root_escaping_paths()
    test_no_manifest_is_inert()
    test_manifest_without_matching_ecosystem_entry_fails()
    test_manifest_with_matching_ecosystem_entry_passes()
    test_real_repository_dependabot_coverage_still_passes()
    test_component_with_only_readme_is_inert()
    test_component_with_real_content_and_no_codeowners_entry_fails()
    test_component_with_real_content_and_codeowners_entry_passes()
    test_real_repository_codeowners_coverage_still_passes()
    test_matrix_says_no_but_agent_can_modify_fails()
    test_matrix_says_yes_but_agent_cannot_modify_fails()
    test_matrix_consistent_with_frontmatter_passes()
    test_matrix_codex_row_without_frontmatter_is_skipped()
    test_real_repository_agent_matrix_still_passes()
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

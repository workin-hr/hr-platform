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
# validate_instruction_source_of_truth (INS-1)
# ---------------------------------------------------------------------------


def write_instruction_entrypoints(root: Path, claude_imports_agents: bool) -> None:
    (root / "AGENTS.md").write_text(
        "# Repository Engineering Instructions\n\n"
        "## Canonical Source Of Truth\n\nCanonical.\n\n"
        "## Mandatory Change Propagation\n\nPropagate.\n",
        encoding="utf-8",
    )
    claude = "# Claude Repository Entry Point\n"
    if claude_imports_agents:
        claude += "\n@AGENTS.md\n"
    (root / "CLAUDE.md").write_text(claude, encoding="utf-8")


def test_claude_without_agents_import_fails() -> None:
    root = make_root()
    try:
        write_instruction_entrypoints(root, claude_imports_agents=False)
        failures: list[str] = []
        v.validate_instruction_source_of_truth(failures, root=root)
        check(
            any("@AGENTS.md" in failure for failure in failures),
            f"CLAUDE.md without the canonical AGENTS.md import fails (failures={failures})",
        )
    finally:
        shutil.rmtree(root)


def test_canonical_agents_import_passes() -> None:
    root = make_root()
    try:
        write_instruction_entrypoints(root, claude_imports_agents=True)
        failures: list[str] = []
        v.validate_instruction_source_of_truth(failures, root=root)
        check(failures == [], f"canonical AGENTS.md plus Claude import passes (failures={failures})")
    finally:
        shutil.rmtree(root)


def test_real_repository_instruction_source_still_passes() -> None:
    failures: list[str] = []
    v.validate_instruction_source_of_truth(failures)
    check(failures == [], f"the real repository instruction source still passes (failures={failures})")


# ---------------------------------------------------------------------------
# scripts/verify-bootstrap.sh skipped-tool summary (CI-2)
# ---------------------------------------------------------------------------

VERIFY_BOOTSTRAP_SCRIPT = REPO_ROOT / "scripts/verify-bootstrap.sh"
ALL_SIX_TOOLS = ("markdownlint-cli2", "yamllint", "shellcheck", "actionlint", "gitleaks", "lychee")


SYSTEM_PATH_DIRS = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"

# Tools the test-running chain itself needs to work at all: verify-bootstrap.sh
# is a bash script, it shells out to python3, and validate_phase0.py's own
# nested regression suite (scripts/test_git_guard.py) calls
# `git branch --show-current` for real (get_current_branch()) against this
# actual repository.
ESSENTIAL_RUNTIME_TOOLS = ("git", "bash", "sh", "python3")


def _essential_runtime_path_dirs() -> str:
    """Directories, resolved from the real inherited PATH via shutil.which,
    that contain the essential runtimes above — deduplicated and joined
    with ':'. SYSTEM_PATH_DIRS is used only as a last-resort fallback when
    *none* of the essential runtimes resolve at all (a maximally broken
    PATH) — never appended unconditionally.

    A fixed, hardcoded directory list (the original approach — just
    SYSTEM_PATH_DIRS on its own) breaks on any environment where these
    tools live somewhere nonstandard, e.g. a snap-confined git at
    /snap/codex/34/usr/bin/git: verify-bootstrap.sh's nested
    test_git_guard.py regression suite calls the real `git`, gets
    `FileNotFoundError`/a nonzero exit, `get_current_branch()` returns
    None, and the whole subprocess exits before ever reaching the SUMMARY
    line this test is actually trying to check — a real, demonstrated
    failure found by independent review, not a hypothetical one. That's
    why dynamic shutil.which() resolution exists at all.

    An *unconditional* SYSTEM_PATH_DIRS append (the first fix attempt)
    reintroduced a narrower version of the same leaked-real-tool bug
    run_verify_bootstrap_with_shims's own docstring describes: on
    GitHub Actions' ubuntu-latest runners, git/python3/bash/sh all
    resolve to /usr/bin or /bin, which are also exactly the directories
    SYSTEM_PATH_DIRS names — so appending the *entire* fallback list
    regardless of whether it was needed put every other binary living in
    those directories back on PATH, including (confirmed by a real CI
    failure investigating hr-platform#26) at least one of the six
    tracked external tools, breaking the exact-N-tools-skipped
    assertions this function exists to make possible. Falling back to
    SYSTEM_PATH_DIRS only when zero essential runtimes resolved at all
    keeps the genuine safety net (a completely empty/broken PATH still
    gets *something* to work with) without ever reintroducing extra
    directories in the common case where resolution already succeeded."""
    dirs: list[str] = []
    for tool in ESSENTIAL_RUNTIME_TOOLS:
        found = shutil.which(tool)
        if not found:
            continue
        tool_dir = str(Path(found).resolve().parent)
        if tool_dir not in dirs:
            dirs.append(tool_dir)
    if not dirs:
        dirs.extend(SYSTEM_PATH_DIRS.split(":"))
    return ":".join(dirs)


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
        env["PATH"] = f"{shim_dir}:{_essential_runtime_path_dirs()}"
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


def test_essential_runtime_path_dirs_finds_real_git() -> None:
    """_essential_runtime_path_dirs() must actually resolve git dynamically
    (via shutil.which against the real environment), not just return the
    hardcoded SYSTEM_PATH_DIRS fallback list — otherwise it doesn't fix
    anything on an environment where git isn't in one of those standard
    directories."""
    real_git = shutil.which("git")
    if real_git is None:
        check(True, "test_essential_runtime_path_dirs_finds_real_git skipped: no git on PATH in this environment")
        return
    real_git_dir = str(Path(real_git).resolve().parent)
    resolved = _essential_runtime_path_dirs().split(":")
    check(
        real_git_dir in resolved,
        f"_essential_runtime_path_dirs() includes git's real resolved directory {real_git_dir!r} (resolved={resolved})",
    )


def test_essential_runtime_path_dirs_finds_git_in_nonstandard_location() -> None:
    """Regression test for the actual Codex-environment finding: simulates
    git living somewhere outside SYSTEM_PATH_DIRS (like the real
    /snap/codex/34/usr/bin/git case) by putting a fake `git` shim in a
    throwaway directory and making it the *only* PATH entry, then
    confirming _essential_runtime_path_dirs() still finds it — proving
    this is genuine dynamic resolution, not reliance on the hardcoded
    fallback list that was the actual bug."""
    with tempfile.TemporaryDirectory(prefix="fake-git-location-") as fake_dir:
        fake_git = Path(fake_dir) / "git"
        fake_git.write_text("#!/usr/bin/env sh\necho fake\n", encoding="utf-8")
        fake_git.chmod(0o755)

        original_path = os.environ.get("PATH", "")
        try:
            os.environ["PATH"] = fake_dir
            resolved = _essential_runtime_path_dirs().split(":")
            check(
                fake_dir in resolved,
                f"a git shim in a nonstandard-only PATH is still found by _essential_runtime_path_dirs() (resolved={resolved})",
            )
        finally:
            os.environ["PATH"] = original_path


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
    "Write(**/*credentials*)",
    "Write(**/*secret*)",
    "Write(**/*.key)",
    "Write(**/id_rsa*)",
    "Write(**/id_ed25519*)",
    "Write(**/*.p12)",
    "Write(**/*.pfx)",
    "Write(**/*.keystore)",
    "Write(**/*.jks)",
    "NotebookEdit(**/*credentials*)",
    "NotebookEdit(**/*secret*)",
    "NotebookEdit(**/*.key)",
    "NotebookEdit(**/id_rsa*)",
    "NotebookEdit(**/id_ed25519*)",
    "NotebookEdit(**/*.p12)",
    "NotebookEdit(**/*.pfx)",
    "NotebookEdit(**/*.keystore)",
    "NotebookEdit(**/*.jks)",
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


def test_settings_missing_write_side_of_a_pattern_fails() -> None:
    """Read+Edit coverage of a secret pattern is not enough — Write must be
    covered too, or the file can still be created/overwritten via Write.
    Regression test for a real gap found by independent review: the deny
    list had Read/Edit rules but no Write/NotebookEdit rules, and the
    validator did not check for them either, so CI passed anyway."""
    root = make_root()
    try:
        incomplete = [rule for rule in VALID_DENY_RULES if rule != "Write(**/*.keystore)"]
        write_valid_settings(root, incomplete)
        failures: list[str] = []
        v.validate_claude_settings(failures, root=root)
        check(
            any("Write(...) rule" in f and "*.keystore" in f for f in failures),
            f"settings.json with Read/Edit but not Write coverage for a pattern fails (failures={failures})",
        )
    finally:
        shutil.rmtree(root)


def test_settings_missing_notebookedit_side_of_a_pattern_fails() -> None:
    """Same regression coverage as the Write test above, for NotebookEdit."""
    root = make_root()
    try:
        incomplete = [rule for rule in VALID_DENY_RULES if rule != "NotebookEdit(**/*.keystore)"]
        write_valid_settings(root, incomplete)
        failures: list[str] = []
        v.validate_claude_settings(failures, root=root)
        check(
            any("NotebookEdit(...) rule" in f and "*.keystore" in f for f in failures),
            f"settings.json with Read/Edit but not NotebookEdit coverage for a pattern fails (failures={failures})",
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
    "required_conversation_resolution": {"enabled": True},
    "required_status_checks": {"contexts": ["validate", "independent-review"]},
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
        "required_conversation_resolution": {"enabled": False},
        "required_status_checks": {"contexts": ["something-else"]},
    }
    proc = run_check_branch_protection(bad)
    check(
        proc.returncode != 0
        and "required_approving_review_count is 0" in proc.stdout
        and "enforce_admins.enabled is false" in proc.stdout
        and "allow_force_pushes.enabled is true" in proc.stdout
        and "required_conversation_resolution.enabled is false" in proc.stdout
        and "does not include 'validate'" in proc.stdout
        and "does not include 'independent-review'" in proc.stdout,
        f"branch protection missing every requirement fails, naming all 6 fields (exit={proc.returncode}, stdout={proc.stdout!r})",
    )


def test_branch_protection_without_the_independent_review_context_fails() -> None:
    """Conversation resolution cannot prove a round happened on the final head,
    so protection carrying only the validation context is incomplete (D-121)."""
    bad = dict(GOOD_PROTECTION_JSON)
    bad["required_status_checks"] = {"contexts": ["validate"]}
    proc = run_check_branch_protection(bad)
    check(
        proc.returncode != 0 and "does not include 'independent-review'" in proc.stdout,
        f"branch protection without the independent-review context fails "
        f"(exit={proc.returncode}, stdout={proc.stdout!r})",
    )


def test_branch_protection_without_conversation_resolution_fails() -> None:
    """A required approving review does not gate on the named independent
    reviewer, which cannot approve -- unresolved-thread blocking is what
    actually stops a merge outrunning a review round (R-008, PR #126)."""
    bad = dict(GOOD_PROTECTION_JSON)
    bad["required_conversation_resolution"] = {"enabled": False}
    proc = run_check_branch_protection(bad)
    check(
        proc.returncode != 0 and "required_conversation_resolution.enabled is false" in proc.stdout,
        f"branch protection with every other field set but conversation resolution off fails "
        f"(exit={proc.returncode}, stdout={proc.stdout!r})",
    )


def test_branch_protection_job_id_is_read_from_workflow_file() -> None:
    """The required check name is not hardcoded — it's parsed from the
    workflow file, so the two cannot silently diverge."""
    fake_workflow = "name: Fake\non:\n  push:\njobs:\n  totally-different-job-name:\n    runs-on: ubuntu-latest\n"
    protection = dict(GOOD_PROTECTION_JSON)
    # The independent-review context is required independently of this one and
    # is read from its own workflow, so it stays in the fixture.
    protection["required_status_checks"] = {
        "contexts": ["totally-different-job-name", "independent-review"],
    }
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


def test_untracked_manifest_does_not_trigger_check() -> None:
    """Regression test for the same tracked-file finding as
    test_untracked_file_in_component_dir_does_not_trigger_check, applied to
    the manifest-scanning side. An untracked local scratch composer.json
    must not trip this check."""
    root = make_root()
    try:
        subprocess.run(["git", "init", "-q"], cwd=root, check=True)
        write_dependabot(root, DEPENDABOT_GITHUB_ACTIONS_ONLY)
        (root / "backend").mkdir(parents=True)
        # Deliberately NOT `git add`-ed: an untracked scratch file.
        (root / "backend/composer.json").write_text("{}", encoding="utf-8")

        failures: list[str] = []
        v.validate_dependabot_ecosystem_coverage(failures, root=root)
        check(
            failures == [],
            f"an untracked scratch manifest does not trigger the check (failures={failures})",
        )
    finally:
        shutil.rmtree(root)


def test_tracked_manifest_still_triggers_check() -> None:
    """Companion to the untracked-manifest test above: a genuinely tracked
    manifest must still trigger the check."""
    root = make_root()
    try:
        subprocess.run(["git", "init", "-q"], cwd=root, check=True)
        write_dependabot(root, DEPENDABOT_GITHUB_ACTIONS_ONLY)
        (root / "backend").mkdir(parents=True)
        (root / "backend/composer.json").write_text("{}", encoding="utf-8")
        subprocess.run(["git", "add", "backend/composer.json"], cwd=root, check=True)

        failures: list[str] = []
        v.validate_dependabot_ecosystem_coverage(failures, root=root)
        check(
            any("composer.json" in f and "/backend" in f for f in failures),
            f"a genuinely tracked manifest still triggers the check (failures={failures})",
        )
    finally:
        shutil.rmtree(root)


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


def test_untracked_file_in_component_dir_does_not_trigger_check() -> None:
    """Regression test for a real finding from independent review: this
    check's docstring describes tracked-file semantics, but the
    implementation scanned the raw filesystem, so an untracked local
    scratch file could trip a failure that doesn't reflect real repository
    content. Uses a real git repo (git init + no `git add`) so
    `_git_tracked_files` actually has something to filter against, unlike
    the plain-tempdir fixtures used elsewhere in this file."""
    root = make_root()
    try:
        subprocess.run(["git", "init", "-q"], cwd=root, check=True)
        write_codeowners(root, "* @workin-hr/platform-owners\n")
        (root / "backend").mkdir(parents=True)
        (root / "backend/README.md").write_text("# Backend Boundary\n", encoding="utf-8")
        # Deliberately NOT `git add`-ed: an untracked scratch file.
        (root / "backend/scratch.tmp").write_text("not part of the repo\n", encoding="utf-8")

        failures: list[str] = []
        v.validate_codeowners_component_coverage(failures, root=root)
        check(
            failures == [],
            f"an untracked scratch file in a component directory does not trigger the check (failures={failures})",
        )
    finally:
        shutil.rmtree(root)


def test_tracked_file_in_component_dir_still_triggers_check() -> None:
    """Companion to the untracked-file test above: a genuinely tracked
    (git-added) file in the same setup must still trigger the check —
    proving the fix filters by tracked status rather than accidentally
    disabling the check altogether."""
    root = make_root()
    try:
        subprocess.run(["git", "init", "-q"], cwd=root, check=True)
        write_codeowners(root, "* @workin-hr/platform-owners\n")
        (root / "backend").mkdir(parents=True)
        (root / "backend/README.md").write_text("# Backend Boundary\n", encoding="utf-8")
        (root / "backend/Application.java").write_text("// placeholder\n", encoding="utf-8")
        subprocess.run(["git", "add", "backend/Application.java"], cwd=root, check=True)

        failures: list[str] = []
        v.validate_codeowners_component_coverage(failures, root=root)
        check(
            any("backend/" in f for f in failures),
            f"a genuinely tracked file in a component directory still triggers the check (failures={failures})",
        )
    finally:
        shutil.rmtree(root)


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
# validate_independent_reviewer_declaration (REV-1)
# ---------------------------------------------------------------------------


def write_reviewer_declaration(
    root: Path,
    agents_names_reviewer: bool,
    matrix_rows: list[str],
    *,
    workflow_section: bool = True,
    reviewer_outside_section: bool = False,
    demoted_heading: bool = False,
    reversed_order: bool = False,
    lookalike_in_workflow: bool = False,
    gate_workflow: str | None = "",
) -> None:
    body = "# Repository Engineering Instructions\n\n"
    if workflow_section:
        steps = (
            "`Issue -> ... -> Human merge -> Independent review`"
            if reversed_order
            else "`Issue -> ... -> Independent review -> Human merge`"
        )
        body += (
            f"{'###' if demoted_heading else '##'} Mandatory Workflow\n\n"
            f"{steps}\n\n"
            + (
                f"Independent review is performed by `impersonator-{v.INDEPENDENT_REVIEWER}` (D-121).\n"
                if lookalike_in_workflow
                else f"Independent review is performed by `{v.INDEPENDENT_REVIEWER}` (D-121).\n"
                if agents_names_reviewer
                else "Independent review is performed by somebody.\n"
            )
        )
    body += "\n## Global Rules\n\nRules.\n"
    if reviewer_outside_section:
        body += f"\nSomething unrelated mentions `{v.INDEPENDENT_REVIEWER}` here.\n"
    (root / "AGENTS.md").write_text(body, encoding="utf-8")
    write_matrix(root, matrix_rows)

    # `gate_workflow=None` omits the file entirely; "" writes the canonical
    # one; any other string is written verbatim, for drift cases.
    if gate_workflow is not None:
        gate = root / v.REVIEW_GATE_WORKFLOW
        gate.parent.mkdir(parents=True, exist_ok=True)
        gate.write_text(
            gate_workflow
            or (
                "name: Independent Review Gate\n"
                f"# D-121 names `{v.INDEPENDENT_REVIEWER}` as the reviewer.\n"
                f'          REVIEWER: "{v.INDEPENDENT_REVIEWER}"\n'
                f'            -f context="{v.REVIEW_GATE_CONTEXT}" \\\n'
            ),
            encoding="utf-8",
        )


REVIEWER_ROW = f"| `{v.INDEPENDENT_REVIEWER}` (pull-request review) | Read-only review | No | No | No |\n"


def test_reviewer_named_and_declared_read_only_passes() -> None:
    root = make_root()
    try:
        write_reviewer_declaration(root, agents_names_reviewer=True, matrix_rows=[REVIEWER_ROW])
        failures: list[str] = []
        v.validate_independent_reviewer_declaration(failures, root=root)
        check(failures == [], f"a named, read-only reviewer declared on both sides passes (failures={failures})")
    finally:
        shutil.rmtree(root)


def test_workflow_without_named_reviewer_fails() -> None:
    """AGENTS.md may not gate merges on an independent review it does not staff."""
    root = make_root()
    try:
        write_reviewer_declaration(root, agents_names_reviewer=False, matrix_rows=[REVIEWER_ROW])
        failures: list[str] = []
        v.validate_independent_reviewer_declaration(failures, root=root)
        check(
            any("does not name" in f for f in failures),
            f"a Mandatory Workflow that names no reviewer fails (failures={failures})",
        )
    finally:
        shutil.rmtree(root)


def test_workflow_section_deleted_fails() -> None:
    """Deleting the heading must not be a way to delete the gate."""
    root = make_root()
    try:
        write_reviewer_declaration(
            root, agents_names_reviewer=True, matrix_rows=[REVIEWER_ROW], workflow_section=False,
        )
        failures: list[str] = []
        v.validate_independent_reviewer_declaration(failures, root=root)
        check(
            any("no longer defines" in f for f in failures),
            f"AGENTS.md with its Mandatory Workflow section removed fails (failures={failures})",
        )
    finally:
        shutil.rmtree(root)


def test_reviewer_named_only_outside_the_workflow_fails() -> None:
    """A mention elsewhere in AGENTS.md does not staff the gate."""
    root = make_root()
    try:
        write_reviewer_declaration(
            root, agents_names_reviewer=False, matrix_rows=[REVIEWER_ROW],
            reviewer_outside_section=True,
        )
        failures: list[str] = []
        v.validate_independent_reviewer_declaration(failures, root=root)
        check(
            any("does not name" in f for f in failures),
            f"a reviewer named outside the Mandatory Workflow section fails (failures={failures})",
        )
    finally:
        shutil.rmtree(root)


def test_demoted_workflow_heading_fails() -> None:
    """`### Mandatory Workflow` must not satisfy a check that requires `## `:
    a plain substring search matches it starting at the second '#'."""
    root = make_root()
    try:
        write_reviewer_declaration(
            root, agents_names_reviewer=True, matrix_rows=[REVIEWER_ROW], demoted_heading=True,
        )
        failures: list[str] = []
        v.validate_independent_reviewer_declaration(failures, root=root)
        check(
            any("no longer defines" in f for f in failures),
            f"a demoted '### Mandatory Workflow' heading fails (failures={failures})",
        )
    finally:
        shutil.rmtree(root)


def test_review_after_merge_fails() -> None:
    """Presence is not the property that matters — review must precede merge."""
    root = make_root()
    try:
        write_reviewer_declaration(
            root, agents_names_reviewer=True, matrix_rows=[REVIEWER_ROW], reversed_order=True,
        )
        failures: list[str] = []
        v.validate_independent_reviewer_declaration(failures, root=root)
        check(
            any("after" in f and "human merge" in f for f in failures),
            f"a workflow reviewing after merge fails (failures={failures})",
        )
    finally:
        shutil.rmtree(root)


def test_lookalike_reviewer_row_does_not_satisfy_the_check() -> None:
    """A row whose identity merely contains the reviewer name is a different
    agent, and must not stand in for the named reviewer's own row."""
    root = make_root()
    try:
        lookalike = f"| `impersonator-{v.INDEPENDENT_REVIEWER}` (pull-request review) | Read-only review | No | No | No |\n"
        write_reviewer_declaration(root, agents_names_reviewer=True, matrix_rows=[lookalike])
        failures: list[str] = []
        v.validate_independent_reviewer_declaration(failures, root=root)
        check(
            any("has no row for" in f for f in failures),
            f"a look-alike matrix identity does not satisfy the check (failures={failures})",
        )
    finally:
        shutil.rmtree(root)


def test_lookalike_reviewer_in_the_workflow_fails() -> None:
    """The prose side needs the same exact-identity rule as the matrix row:
    assigning review to `impersonator-...[bot]` must not pass merely because
    the matrix still carries the real reviewer's row."""
    root = make_root()
    try:
        write_reviewer_declaration(
            root, agents_names_reviewer=True, matrix_rows=[REVIEWER_ROW],
            lookalike_in_workflow=True,
        )
        failures: list[str] = []
        v.validate_independent_reviewer_declaration(failures, root=root)
        check(
            any("does not name" in f for f in failures),
            f"a look-alike reviewer identity in the workflow fails (failures={failures})",
        )
    finally:
        shutil.rmtree(root)


def test_reviewer_missing_from_matrix_fails() -> None:
    """The gap validate_agent_matrix_consistency() cannot catch: the bot has no
    .claude/agents file, so that function skips it and only this check binds it."""
    root = make_root()
    try:
        write_reviewer_declaration(
            root, agents_names_reviewer=True,
            matrix_rows=["| Bootstrap Auditor | Read-only review | No | No | No |\n"],
        )
        failures: list[str] = []
        v.validate_independent_reviewer_declaration(failures, root=root)
        check(
            any("has no row for" in f for f in failures),
            f"a reviewer named in AGENTS.md but absent from the matrix fails (failures={failures})",
        )
    finally:
        shutil.rmtree(root)


def test_reviewer_row_widened_fails() -> None:
    """A read-only reviewer that acquires approval authority must not pass silently."""
    root = make_root()
    try:
        widened = f"| `{v.INDEPENDENT_REVIEWER}` (pull-request review) | Read-only review | No | No | Yes |\n"
        write_reviewer_declaration(root, agents_names_reviewer=True, matrix_rows=[widened])
        failures: list[str] = []
        v.validate_independent_reviewer_declaration(failures, root=root)
        check(
            any("May Approve Work" in f for f in failures),
            f"a reviewer row widened to grant approval fails (failures={failures})",
        )
    finally:
        shutil.rmtree(root)


def test_duplicate_reviewer_rows_fail() -> None:
    """A read-only row must not shield a permissive duplicate: both the
    duplication and the widened row are reported."""
    root = make_root()
    try:
        permissive = f"| `{v.INDEPENDENT_REVIEWER}` (second entry) | Controlled implementation | Yes | Yes | Yes |\n"
        write_reviewer_declaration(
            root, agents_names_reviewer=True, matrix_rows=[REVIEWER_ROW, permissive],
        )
        failures: list[str] = []
        v.validate_independent_reviewer_declaration(failures, root=root)
        check(
            any("2 rows" in f for f in failures),
            f"two rows naming the reviewer fail as a duplicate (failures={failures})",
        )
        check(
            any("May Modify Files" in f for f in failures),
            f"the permissive duplicate row is still checked, not skipped (failures={failures})",
        )
    finally:
        shutil.rmtree(root)


def test_missing_review_gate_workflow_fails() -> None:
    """Deleting the workflow deletes the executable half of the gate."""
    root = make_root()
    try:
        write_reviewer_declaration(
            root, agents_names_reviewer=True, matrix_rows=[REVIEWER_ROW], gate_workflow=None,
        )
        failures: list[str] = []
        v.validate_independent_reviewer_declaration(failures, root=root)
        check(
            any("is missing" in f for f in failures),
            f"a deleted independent-review workflow fails (failures={failures})",
        )
    finally:
        shutil.rmtree(root)


def test_review_gate_workflow_naming_a_different_reviewer_fails() -> None:
    """The workflow carries its own copy of the login; it must not drift from
    the one AGENTS.md declares."""
    root = make_root()
    try:
        # The header comment still names the real reviewer -- exactly the shape
        # the live workflow has, and the reason a whole-file search was vacuous.
        drifted = (
            "name: Independent Review Gate\n"
            f"# D-121 names `{v.INDEPENDENT_REVIEWER}` as the reviewer.\n"
            '          REVIEWER: "some-other-bot"\n'
            f'            -f context="{v.REVIEW_GATE_CONTEXT}" \\\n'
        )
        write_reviewer_declaration(
            root, agents_names_reviewer=True, matrix_rows=[REVIEWER_ROW], gate_workflow=drifted,
        )
        failures: list[str] = []
        v.validate_independent_reviewer_declaration(failures, root=root)
        check(
            any("have diverged" in f and "some-other-bot" in f for f in failures),
            f"a workflow whose REVIEWER assignment names a different account fails, even though "
            f"its comments still name the real one (failures={failures})",
        )
    finally:
        shutil.rmtree(root)


def test_review_gate_workflow_without_a_reviewer_assignment_fails() -> None:
    """Deleting the assignment must not pass on the strength of the comments."""
    root = make_root()
    try:
        no_assignment = (
            "name: Independent Review Gate\n"
            f"# D-121 names `{v.INDEPENDENT_REVIEWER}` as the reviewer.\n"
            f'            -f context="{v.REVIEW_GATE_CONTEXT}" \\\n'
        )
        write_reviewer_declaration(
            root, agents_names_reviewer=True, matrix_rows=[REVIEWER_ROW], gate_workflow=no_assignment,
        )
        failures: list[str] = []
        v.validate_independent_reviewer_declaration(failures, root=root)
        check(
            any("has no `REVIEWER:" in f for f in failures),
            f"a workflow with no REVIEWER assignment fails despite naming the reviewer in a "
            f"comment (failures={failures})",
        )
    finally:
        shutil.rmtree(root)


def test_review_gate_workflow_dropping_the_status_context_fails() -> None:
    """check-branch-protection.sh requires that context; publishing a
    different one would make the required check permanently pending."""
    root = make_root()
    try:
        renamed = (
            "name: Independent Review Gate\n"
            f'          REVIEWER: "{v.INDEPENDENT_REVIEWER}"\n'
            '            -f context="something-else" \\\n'
        )
        write_reviewer_declaration(
            root, agents_names_reviewer=True, matrix_rows=[REVIEWER_ROW], gate_workflow=renamed,
        )
        failures: list[str] = []
        v.validate_independent_reviewer_declaration(failures, root=root)
        check(
            any("requires 'independent-review'" in f for f in failures),
            f"a workflow publishing a different status context fails (failures={failures})",
        )
    finally:
        shutil.rmtree(root)


def test_review_gate_workflow_with_a_decoy_context_comment_fails() -> None:
    """The live `-f context=` argument is what counts. A commented-out example
    naming the required context must not stand in for it -- the same vacuity
    the reviewer binding had before it was parsed by value."""
    root = make_root()
    try:
        decoy = (
            "name: Independent Review Gate\n"
            f"# D-121 names `{v.INDEPENDENT_REVIEWER}` as the reviewer.\n"
            f'          REVIEWER: "{v.INDEPENDENT_REVIEWER}"\n'
            f'          #   -f context="{v.REVIEW_GATE_CONTEXT}" \\\n'
            '            -f context="some-other-context" \\\n'
        )
        write_reviewer_declaration(
            root, agents_names_reviewer=True, matrix_rows=[REVIEWER_ROW], gate_workflow=decoy,
        )
        failures: list[str] = []
        v.validate_independent_reviewer_declaration(failures, root=root)
        check(
            any("some-other-context" in f for f in failures),
            f"a decoy comment does not rescue a workflow publishing another context "
            f"(failures={failures})",
        )
    finally:
        shutil.rmtree(root)


def test_real_repository_reviewer_declaration_still_passes() -> None:
    failures: list[str] = []
    v.validate_independent_reviewer_declaration(failures)  # default root = real repo
    check(failures == [], f"the real repository's reviewer declaration passes (failures={failures})")


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


def test_product_code_outside_spike_still_fails() -> None:
    """Regression baseline: the spike/ exclusion must not weaken the
    scanner for everywhere else in the repository. Uses admin-web/, not
    backend/ -- backend/ is unlocked by PHASE1_UNLOCKED_DIRS (D-028) and
    is covered by its own dedicated tests below."""
    root = make_root()
    try:
        (root / "admin-web").mkdir(parents=True)
        (root / "admin-web/App.tsx").write_text("// placeholder\n", encoding="utf-8")

        failures: list[str] = []
        v.validate_forbidden_files(failures, root=root)
        check(
            any("admin-web/App.tsx" in f for f in failures),
            f"product code outside spike/ still fails the forbidden-file scanner (failures={failures})",
        )
    finally:
        shutil.rmtree(root)


def test_product_code_inside_backend_is_excluded() -> None:
    """D-028 (docs/bootstrap/decision-log.md) lifts the Phase 0
    implementation lock for backend/ specifically -- real Spring
    Boot/Java 25 source under backend/ must not trigger the product-code
    scanner (see PHASE1_UNLOCKED_DIRS in validate_phase0.py)."""
    root = make_root()
    try:
        (root / "backend/src/main/java/com/workin/backend").mkdir(parents=True)
        (root / "backend/build.gradle").write_text("// placeholder\n", encoding="utf-8")
        (root / "backend/src/main/java/com/workin/backend/Application.java").write_text(
            "// placeholder\n", encoding="utf-8"
        )

        failures: list[str] = []
        v.validate_forbidden_files(failures, root=root)
        check(
            failures == [],
            f"real Spring Boot/Java files under backend/ do not trigger the forbidden-file scanner (failures={failures})",
        )
    finally:
        shutil.rmtree(root)


def test_initialized_submodule_content_is_excluded() -> None:
    """Pinned git submodules are repository-boundary pointers, not
    product code owned by this repository. If a human explicitly
    initializes one locally, the parent repository's forbidden-file
    scanner must not recurse into its Android/Gradle/Kotlin content and
    treat that external checkout as hr-platform code."""
    root = make_root()
    try:
        (root / "flutter-integration/workin_mobile/android/app/src/main/kotlin/com/app/workin").mkdir(parents=True)
        (root / ".gitmodules").write_text(
            '[submodule "flutter-integration/workin_mobile"]\n'
            "    path = flutter-integration/workin_mobile\n"
            "    url = git@github.com:example/workin_mobile.git\n",
            encoding="utf-8",
        )
        (root / "flutter-integration/workin_mobile/android/build.gradle.kts").write_text(
            "// placeholder\n", encoding="utf-8"
        )
        (root / "flutter-integration/workin_mobile/android/app/src/main/kotlin/com/app/workin/MainActivity.kt").write_text(
            "// placeholder\n", encoding="utf-8"
        )

        failures: list[str] = []
        v.validate_forbidden_files(failures, root=root)
        check(
            failures == [],
            f"initialized content inside a declared git submodule is excluded from the forbidden-file scanner (failures={failures})",
        )
    finally:
        shutil.rmtree(root)


def test_product_code_in_other_component_dirs_still_fails() -> None:
    """Regression baseline: backend/'s Phase 1 unlock (D-028) must not
    leak to any other component directory -- each remaining component
    (admin-web/, edge-gateway/, infrastructure/) needs its own separate,
    explicit transition decision before real files are allowed there."""
    root = make_root()
    try:
        (root / "edge-gateway").mkdir(parents=True)
        (root / "edge-gateway/Program.cs").write_text("// placeholder\n", encoding="utf-8")
        (root / "infrastructure").mkdir(parents=True)
        (root / "infrastructure/Setup.java").write_text("// placeholder\n", encoding="utf-8")

        failures: list[str] = []
        v.validate_forbidden_files(failures, root=root)
        check(
            any("edge-gateway/Program.cs" in f for f in failures)
            and any("infrastructure/Setup.java" in f for f in failures),
            f"product code in edge-gateway/ and infrastructure/ still fails the forbidden-file scanner, unaffected by backend/'s unlock (failures={failures})",
        )
    finally:
        shutil.rmtree(root)


def test_product_code_inside_spike_is_excluded() -> None:
    """docs/migration/technical-spike-plan.md's H2 experiment lives in a
    real Spring Boot/Java project under spike/ while it runs -- this one
    top-level directory is deliberately excluded from the product-code
    scanner (see SPIKE_DIR_NAME in validate_phase0.py), not silently."""
    root = make_root()
    try:
        (root / "spike/tenant-isolation-spike/src/main/java/com/workin/spike").mkdir(parents=True)
        (root / "spike/tenant-isolation-spike/build.gradle").write_text("// placeholder\n", encoding="utf-8")
        (root / "spike/tenant-isolation-spike/src/main/java/com/workin/spike/App.java").write_text(
            "// placeholder\n", encoding="utf-8"
        )

        failures: list[str] = []
        v.validate_forbidden_files(failures, root=root)
        check(
            failures == [],
            f"real Spring Boot/Java files under spike/ do not trigger the forbidden-file scanner (failures={failures})",
        )
    finally:
        shutil.rmtree(root)


def main() -> int:
    test_claude_without_agents_import_fails()
    test_canonical_agents_import_passes()
    test_real_repository_instruction_source_still_passes()
    test_no_nightly_tier_named_is_inert()
    test_nightly_tier_named_without_schedule_workflow_fails()
    test_nightly_tier_named_with_schedule_workflow_passes()
    test_real_repository_has_nightly_workflow()
    test_branch_protection_all_requirements_met_passes()
    test_branch_protection_reports_every_failing_field()
    test_branch_protection_without_conversation_resolution_fails()
    test_branch_protection_without_the_independent_review_context_fails()
    test_branch_protection_job_id_is_read_from_workflow_file()
    test_essential_runtime_path_dirs_finds_real_git()
    test_essential_runtime_path_dirs_finds_git_in_nonstandard_location()
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
    test_untracked_manifest_does_not_trigger_check()
    test_tracked_manifest_still_triggers_check()
    test_component_with_only_readme_is_inert()
    test_component_with_real_content_and_no_codeowners_entry_fails()
    test_component_with_real_content_and_codeowners_entry_passes()
    test_real_repository_codeowners_coverage_still_passes()
    test_untracked_file_in_component_dir_does_not_trigger_check()
    test_tracked_file_in_component_dir_still_triggers_check()
    test_matrix_says_no_but_agent_can_modify_fails()
    test_matrix_says_yes_but_agent_cannot_modify_fails()
    test_matrix_consistent_with_frontmatter_passes()
    test_matrix_codex_row_without_frontmatter_is_skipped()
    test_real_repository_agent_matrix_still_passes()
    test_reviewer_named_and_declared_read_only_passes()
    test_workflow_without_named_reviewer_fails()
    test_workflow_section_deleted_fails()
    test_demoted_workflow_heading_fails()
    test_review_after_merge_fails()
    test_lookalike_reviewer_row_does_not_satisfy_the_check()
    test_lookalike_reviewer_in_the_workflow_fails()
    test_reviewer_named_only_outside_the_workflow_fails()
    test_reviewer_missing_from_matrix_fails()
    test_reviewer_row_widened_fails()
    test_duplicate_reviewer_rows_fail()
    test_missing_review_gate_workflow_fails()
    test_review_gate_workflow_naming_a_different_reviewer_fails()
    test_review_gate_workflow_without_a_reviewer_assignment_fails()
    test_review_gate_workflow_dropping_the_status_context_fails()
    test_review_gate_workflow_with_a_decoy_context_comment_fails()
    test_real_repository_reviewer_declaration_still_passes()
    test_skill_missing_from_catalog_fails()
    test_skill_catalog_fully_listed_passes()
    test_real_repository_skill_catalog_still_passes()
    test_product_code_outside_spike_still_fails()
    test_product_code_inside_spike_is_excluded()
    test_product_code_inside_backend_is_excluded()
    test_product_code_in_other_component_dirs_still_fails()

    passed = sum(1 for ok, _ in CASES_RUN if ok)
    total = len(CASES_RUN)
    print(f"\n{passed}/{total} validate_phase0 governance-check regression cases passed.")
    if passed != total:
        print(f"{total - passed} FAILURE(S)")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())

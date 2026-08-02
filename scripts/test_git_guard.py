#!/usr/bin/env python3
"""Deterministic regression tests for scripts/git_guard.py.

Run directly: `python3 scripts/test_git_guard.py`. Exits 0 on success, 1 on
any failure, printing every case (not just the first failure) so a broken
guard is easy to diagnose. No network access, no real Git repository, and
no real Claude Code hook invocation is used — this tests the guard's pure
classification logic and its stdin-JSON parsing directly.

Wired into: scripts/verify-bootstrap.sh (required, not skippable),
scripts/validate_phase0.py (invoked as a subprocess check), and
.github/workflows/phase0-validate.yml.
"""

from __future__ import annotations

import json
import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import git_guard  # noqa: E402

ROOT = Path(__file__).resolve().parent.parent
GUARD_SCRIPT = Path(__file__).resolve().parent / "git_guard.py"

# (command, expected_verdict) — the exact set required by the audit finding,
# plus additional cases for compound commands, malformed input, and
# unrelated safe commands that must not be broken.
CASES: list[tuple[str, str]] = [
    # Required destructive forms — must all be blocked.
    ("git push", "block"),
    ("git push --force", "block"),
    ("git -C /repo push", "block"),
    ("git -C /repo push --force-with-lease", "block"),
    ("/usr/bin/git push", "block"),
    ("env git push", "block"),
    ("/usr/bin/env git -C /repo push", "block"),
    ("git merge feature", "block"),
    ("git rebase main", "block"),
    ("git reset --hard HEAD", "block"),
    ("git branch -D feature", "block"),
    ("git clean -fd", "block"),
    # Required read-only forms — must all be allowed.
    ("git status", "allow"),
    ("git log --oneline", "allow"),
    ("git diff", "allow"),
    ("git -C /repo status", "allow"),
    ("/usr/bin/git show HEAD", "allow"),
    # Additional destructive-equivalent forms named in the finding.
    ("git -c user.name=x push", "block"),
    ("env -i git push", "block"),
    ("GIT_DIR=.git git push origin main", "block"),
    ("git --git-dir=/repo/.git push", "block"),
    ("git checkout -- .", "block"),
    ("git checkout --force main", "block"),
    ("git switch --discard-changes main", "block"),
    ("git restore app.py", "block"),
    ("git restore --staged --worktree app.py", "block"),
    ("git commit --amend -m fix", "block"),
    ("git tag --delete v1.0.0", "block"),
    ("git reflog expire --expire=now --all", "block"),
    ("git filter-branch --force", "block"),
    ("git push origin +main", "block"),
    ("git push -f origin main", "block"),
    # Additional safe-equivalent forms that must not be broken.
    ("git branch", "allow"),
    ("git branch feature-x", "allow"),
    ("git branch -v", "allow"),
    ("git tag", "allow"),
    ("git tag v1.2.3", "allow"),
    ("git checkout main", "allow"),
    ("git switch main", "allow"),
    ("git restore --staged app.py", "allow"),
    ("git commit -m 'add feature'", "allow"),
    ("git reset", "allow"),
    ("git rev-parse HEAD", "allow"),
    ("git ls-files", "allow"),
    ("git --version", "allow"),
    ("git -C /repo -c core.pager=cat log", "allow"),
    # Commands entirely unrelated to Git must never be touched by this guard.
    ("ls -la", "allow"),
    ("python3 scripts/validate_phase0.py", "allow"),
    ("echo 'no git here'", "allow"),
    ("cat README.md | wc -l", "allow"),
    # Compound / multiple-command forms.
    ("git status && git push", "block"),
    ("git push ; git status", "block"),
    ("git log | grep fix", "allow"),
    ("echo start && git status && echo done", "allow"),
    ("git status; git diff; git push --force", "block"),
    # Reset with a bare pathspec is still blocked (documented, deliberate
    # over-blocking — see git_guard.py's classify_git_invocation docstring
    # comment for "reset").
    ("git reset path/to/file", "block"),
]

MALFORMED_CASES = [
    "git commit -m \"unterminated",
    "git push 'unterminated",
]


def run_case(command: str, expected: str) -> tuple[bool, str]:
    verdict, reason = git_guard.evaluate_command(command)
    ok = verdict == expected
    return ok, f"{'OK ' if ok else 'FAIL'} {command!r} -> {verdict} (expected {expected}): {reason}"


def run_malformed(command: str) -> tuple[bool, str]:
    try:
        verdict, reason = git_guard.evaluate_command(command)
    except Exception as exc:  # noqa: BLE001 - this is exactly what must not happen
        return False, f"FAIL malformed input raised an exception instead of handling it safely: {command!r} -> {exc!r}"
    if verdict not in ("allow", "block"):
        return False, f"FAIL malformed input produced an unrecognized verdict: {command!r} -> {verdict!r}"
    return True, f"OK  malformed input handled safely and predictably: {command!r} -> {verdict} ({reason})"


def run_hook_subprocess_case(payload: dict, expect_exit: int) -> tuple[bool, str]:
    proc = subprocess.run(
        [sys.executable, str(GUARD_SCRIPT)],
        input=json.dumps(payload),
        capture_output=True,
        text=True,
        timeout=10,
    )
    ok = proc.returncode == expect_exit
    return ok, (
        f"{'OK ' if ok else 'FAIL'} hook subprocess payload={payload!r} -> "
        f"exit {proc.returncode} (expected {expect_exit}); stderr={proc.stderr.strip()!r}"
    )


def main() -> int:
    failures = 0
    total = 0

    for command, expected in CASES:
        total += 1
        ok, message = run_case(command, expected)
        print(message)
        if not ok:
            failures += 1

    for command in MALFORMED_CASES:
        total += 1
        ok, message = run_malformed(command)
        print(message)
        if not ok:
            failures += 1

    # End-to-end proof that the actual hook entry point (stdin JSON in,
    # exit code + stderr JSON out) behaves correctly, not just the library
    # function — this is what Claude Code actually invokes.
    subprocess_cases = [
        ({"tool_name": "Bash", "tool_input": {"command": "git push --force"}}, 2),
        ({"tool_name": "Bash", "tool_input": {"command": "git status"}}, 0),
        ({"tool_name": "Bash", "tool_input": {}}, 0),  # missing command: must not crash
        ({"not_the_expected_shape": True}, 0),  # malformed payload: must not crash
    ]
    for payload, expect_exit in subprocess_cases:
        total += 1
        ok, message = run_hook_subprocess_case(payload, expect_exit)
        print(message)
        if not ok:
            failures += 1

    total += 1
    # Genuinely malformed (non-JSON) stdin must also not crash the process.
    proc = subprocess.run(
        [sys.executable, str(GUARD_SCRIPT)],
        input="not json at all {{{",
        capture_output=True,
        text=True,
        timeout=10,
    )
    ok = proc.returncode == 0
    print(f"{'OK ' if ok else 'FAIL'} hook subprocess with non-JSON stdin -> exit {proc.returncode} (expected 0)")
    if not ok:
        failures += 1

    print(f"\n{total - failures}/{total} git_guard regression cases passed.")
    if failures:
        print(f"{failures} FAILURE(S)")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())

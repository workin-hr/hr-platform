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
import os
import subprocess
import sys
import tempfile
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
    # Regression cases for a real false-positive bug found by independent
    # review: the obfuscation-heuristic regex used to scan the *entire* raw
    # command text, so any command merely printing or mentioning
    # git-looking text was denied even though none of these actually
    # invoke git. Fixed by scoping the heuristic to genuine $(...)/backtick
    # regions only (see git_guard.py's _extract_command_substitutions).
    ('echo "git push"', "allow"),
    ("printf 'git push\\n'", "allow"),
    ("python3 -c \"print('git push')\"", "allow"),
    ("echo 'reverts the git merge behavior from last week'", "allow"),
    # The command-substitution case the heuristic exists for must still be
    # caught — this is the true positive the narrower scoping must not lose.
    ('some_wrapper "$(git push origin main)"', "block"),
    ("some_wrapper \"`git push origin main`\"", "block"),
    ("some_wrapper \"$(git log $(date))\"", "allow"),  # nested $(...), no dangerous subcommand inside — must not false-positive on nesting itself
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

# (command, current_branch_value_or_None_for_lookup_failure, expected_verdict)
# Exercises the branch-aware `git commit` rule via an injected branch_getter
# so these cases never depend on which branch this test happens to run on
# (see git_guard.py, "Repository-state-dependent rule").
BRANCH_CASES: list[tuple[str, str | None, str]] = [
    ("git commit -m 'add feature'", "main", "block"),
    ("git commit -m 'add feature'", "bootstrap/engineering-foundation", "allow"),
    ("git commit -m 'add feature'", "", "block"),  # detached HEAD
    ("git commit -m 'add feature'", None, "block"),  # branch lookup failed: fail closed
    ("git commit --amend -m fix", "main", "block"),  # already blocked by the amend rule
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


def run_branch_case(command: str, branch_value: str | None, expected: str) -> tuple[bool, str]:
    verdict, reason = git_guard.evaluate_command(command, branch_getter=lambda _target_dir: branch_value)
    ok = verdict == expected
    return ok, (
        f"{'OK ' if ok else 'FAIL'} {command!r} (current_branch={branch_value!r}) -> "
        f"{verdict} (expected {expected}): {reason}"
    )


def run_c_flag_forwards_target_dir_case() -> tuple[bool, str]:
    """Regression test for a real finding from independent review:
    `git -C <repo> commit ...` must check the branch of <repo>, not the
    hook process's own cwd. Verifies both that the resolved -C directory
    is what gets passed to branch_getter, and that per-directory branch
    values actually drive the verdict (not just that some argument is
    passed)."""
    received: list[str | None] = []

    def recording_getter(target_dir: str | None) -> str | None:
        received.append(target_dir)
        # /tmp/on-main is deliberately "on main" (blocked); the hook's own
        # cwd is irrelevant here — this getter never checks it.
        return "main" if target_dir == "/tmp/on-main" else "feature-x"

    verdict, _ = git_guard.evaluate_command(
        "git -C /tmp/on-main commit -m test", branch_getter=recording_getter
    )
    ok = received == ["/tmp/on-main"] and verdict == "block"
    return ok, (
        f"{'OK ' if ok else 'FAIL'} 'git -C /tmp/on-main commit' forwards the -C target dir to "
        f"branch_getter and blocks based on *that* repo's branch (received={received!r}, verdict={verdict!r})"
    )


def run_c_flag_allows_non_main_target_case() -> tuple[bool, str]:
    """Same mechanism as above, confirming a -C target repo that is not on
    main is allowed even if it were on main were true for some other repo
    — proves the check is genuinely per-directory, not a fixed answer."""
    verdict, _ = git_guard.evaluate_command(
        "git -C /tmp/on-main commit -m test",
        branch_getter=lambda target_dir: "feature-x" if target_dir == "/tmp/elsewhere" else "main",
    )
    # /tmp/on-main was not the queried "/tmp/elsewhere" branch, so this
    # getter returns "main" for it -> still correctly blocked.
    ok = verdict == "block"
    return ok, (
        f"{'OK ' if ok else 'FAIL'} per-directory branch_getter genuinely drives the verdict "
        f"per target directory, not a fixed value (verdict={verdict!r})"
    )


def run_no_c_flag_passes_none_case() -> tuple[bool, str]:
    """A commit with no -C must pass None (meaning "hook's own cwd"),
    preserving prior behavior exactly for the common case."""
    received: list[str | None] = []

    def recording_getter(target_dir: str | None) -> str | None:
        received.append(target_dir)
        return "feature-x"

    git_guard.evaluate_command("git commit -m test", branch_getter=recording_getter)
    ok = received == [None]
    return ok, (
        f"{'OK ' if ok else 'FAIL'} 'git commit' with no -C passes None to branch_getter "
        f"(received={received!r})"
    )


def run_lazy_branch_getter_case() -> tuple[bool, str]:
    """branch_getter must only be invoked for a non-amend `git commit`
    segment — never for unrelated commands — since it shells out to Git."""

    def explode(_target_dir: str | None) -> str | None:
        raise AssertionError("branch_getter must not be called for a non-commit command")

    try:
        verdict, _ = git_guard.evaluate_command("git status", branch_getter=explode)
    except AssertionError as exc:
        return False, f"FAIL branch_getter laziness: {exc}"
    ok = verdict == "allow"
    return ok, (
        f"{'OK ' if ok else 'FAIL'} branch_getter laziness: 'git status' -> {verdict} "
        "(expected allow, getter never called)"
    )


def run_get_current_branch_type_case() -> tuple[bool, str]:
    """Sanity check against this actual repository (a real Git working
    tree): must return a str without raising. Does not assert the specific
    branch name, so the suite never depends on which branch happens to be
    checked out when tests run."""
    branch = git_guard.get_current_branch()
    ok = isinstance(branch, str)
    return ok, f"{'OK ' if ok else 'FAIL'} get_current_branch() in real repo -> {branch!r} (expected a str)"


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


def _write_script(directory: Path, name: str, body: str) -> Path:
    path = directory / name
    path.write_text(body, encoding="utf-8")
    return path


def run_script_scanning_cases() -> list[tuple[bool, str]]:
    """Regression tests for a real incident on 2026-08-12.

    The guard inspects the Bash tool's *command string*. `bash deploy.sh`
    contains no blocked verb, so a script performing `git push` executed
    with the guard active and nobody's finger on the trigger. Closing that
    means resolving the invoked script and evaluating its contents.

    The hard requirement is that this must NOT reintroduce the
    false-positive class the obfuscation heuristic was already narrowed to
    avoid (see `_extract_command_substitutions`): a script that merely
    *mentions* `git push` — in an echo, a heredoc, a PR body — is not
    performing one. So the script's contents go through the same structural
    evaluation as a command line, never a raw text search."""
    results: list[tuple[bool, str]] = []

    def check(label: str, command: str, expected: str) -> None:
        verdict, reason = git_guard.evaluate_command(command)
        ok = verdict == expected
        results.append(
            (
                ok,
                f"{'OK ' if ok else 'FAIL'} script-scan {label}: -> {verdict} "
                f"(expected {expected}): {reason}",
            )
        )

    with tempfile.TemporaryDirectory() as tmp:
        d = Path(tmp)
        push = _write_script(d, "push.sh", "#!/usr/bin/env bash\nset -e\ngit push -u origin main\n")
        safe = _write_script(d, "safe.sh", '#!/usr/bin/env bash\ngit status\necho "done"\n')

        # Mentions, not invocations. All three must stay allowed.
        mentions = _write_script(
            d,
            "mentions.sh",
            "#!/usr/bin/env bash\n"
            'echo "git push is blocked here"\n'
            "printf 'git merge\\n'\n"
            "# remember to git push when the review lands\n",
        )
        heredoc = _write_script(
            d,
            "heredoc.sh",
            "#!/usr/bin/env bash\n"
            "gh pr create --body-file - <<'PRBODY'\n"
            "This PR explains why git push --force is never acceptable.\n"
            "git rebase main would also rewrite history.\n"
            "PRBODY\n"
            "echo done\n",
        )

        # An apostrophe in a comment reads as an unterminated quote. Before
        # comments were stripped this swallowed the rest of the file into
        # one blob and failed the whole script closed — which blocked
        # scripts/verify-bootstrap.sh, a script that touches no blocked
        # operation at all. Both directions are pinned here: the innocent
        # script stays allowed, and the apostrophe must not become a place
        # to hide a real push.
        apostrophe_safe = _write_script(
            d,
            "apostrophe-safe.sh",
            "#!/usr/bin/env bash\n"
            "# Each PR's base is the branch below it; GitHub's UI shows this.\n"
            "git status\n"
            "echo done\n",
        )
        apostrophe_push = _write_script(
            d,
            "apostrophe-push.sh",
            "#!/usr/bin/env bash\n"
            "# Each PR's base is the branch below it.\n"
            "git push -u origin main\n",
        )
        # A `#` that is not a comment must survive: $# and a quoted hash.
        hash_literal = _write_script(
            d,
            "hash-literal.sh",
            '#!/usr/bin/env bash\necho "count=$#"\necho "issue #42"\ngit log --oneline\n',
        )

        p = push.as_posix()
        nested = _write_script(d, "outer.sh", f'#!/usr/bin/env bash\necho starting\nbash "{p}"\n')
        cycle_path = (d / "cycle.sh").as_posix()
        cycle = _write_script(d, "cycle.sh", f'#!/usr/bin/env bash\nbash "{cycle_path}"\n')

        # A chain deeper than the recursion limit cannot be cleared, so it
        # must fail closed rather than be waved through.
        deep_last = _write_script(d, "deep3.sh", "#!/usr/bin/env bash\ngit push\n")
        deep2 = _write_script(d, "deep2.sh", f'#!/usr/bin/env bash\nbash "{deep_last.as_posix()}"\n')
        deep1 = _write_script(d, "deep1.sh", f'#!/usr/bin/env bash\nbash "{deep2.as_posix()}"\n')
        deep0 = _write_script(d, "deep0.sh", f'#!/usr/bin/env bash\nbash "{deep1.as_posix()}"\n')

        check("bash <script that pushes>", f'bash "{p}"', "block")
        check("sh <script that pushes>", f'sh "{p}"', "block")
        check("bash -n <script that pushes>", f'bash -n "{p}"', "block")
        check("bash <script that only reads>", f'bash "{safe.as_posix()}"', "allow")
        check("bash <script that only echoes git text>", f'bash "{mentions.as_posix()}"', "allow")
        check("bash <script with git text in a heredoc>", f'bash "{heredoc.as_posix()}"', "allow")
        check("bash <script that runs a pushing script>", f'bash "{nested.as_posix()}"', "block")
        check("bash <self-referencing script>", f'bash "{cycle.as_posix()}"', "allow")
        check("bash <chain deeper than the limit>", f'bash "{deep0.as_posix()}"', "block")
        check("bash <nonexistent script>", f'bash "{(d / "nope.sh").as_posix()}"', "allow")
        check("bash <apostrophe in comment, no push>", f'bash "{apostrophe_safe.as_posix()}"', "allow")
        check("bash <apostrophe in comment, real push>", f'bash "{apostrophe_push.as_posix()}"', "block")
        check("bash <non-comment # characters>", f'bash "{hash_literal.as_posix()}"', "allow")

        # Relative and sourced forms, evaluated from inside the temp dir.
        previous_cwd = os.getcwd()
        try:
            os.chdir(d)
            check("./<script that pushes>", "./push.sh", "block")
            check("source <script that pushes>", "source ./push.sh", "block")
            check(". <script that pushes>", ". ./push.sh", "block")
            check("./<script that only reads>", "./safe.sh", "allow")
        finally:
            os.chdir(previous_cwd)

    # `bash -c` is the same hole without a file: the blocked verb sits in a
    # quoted argument, so the outer tokenizer sees only `bash`. Found while
    # implementing the script-file layer, fixed by the same mechanism.
    check("bash -c <inline push>", 'bash -c "git push origin main"', "block")
    check("bash -ec <inline push>", "bash -ec 'git push'", "block")
    check("sh -c <inline reset --hard>", "sh -c 'git reset --hard HEAD'", "block")
    check("bash -c <inline safe command>", 'bash -c "echo hello"', "allow")
    check("sh -c <inline git status>", "sh -c 'git status'", "allow")
    check("bash -c <inline text mentioning push>", "bash -c 'echo \"git push\"'", "allow")

    # Must not regress the interpreter cases already relied on elsewhere in
    # this repository: these Python files contain the literal text "git push"
    # in abundance (this file most of all), and running them is routine.
    check("python3 running this very test file", f'python3 "{Path(__file__).as_posix()}"', "allow")
    check("python3 validate_phase0.py", "python3 scripts/validate_phase0.py", "allow")
    check("real git binary by absolute path", "/usr/bin/git status", "allow")

    # This repository's own shell scripts, evaluated as real files. These are
    # run routinely (verify-bootstrap.sh is a required gate), so if the
    # script layer ever starts blocking them it must fail here rather than
    # in someone's terminal.
    for name in sorted(p.name for p in (ROOT / "scripts").glob("*.sh")):
        check(f"repo script {name}", f'bash "{(ROOT / "scripts" / name).as_posix()}"', "allow")

    return results


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

    for command, branch_value, expected in BRANCH_CASES:
        total += 1
        ok, message = run_branch_case(command, branch_value, expected)
        print(message)
        if not ok:
            failures += 1

    total += 1
    ok, message = run_c_flag_forwards_target_dir_case()
    print(message)
    if not ok:
        failures += 1

    total += 1
    ok, message = run_c_flag_allows_non_main_target_case()
    print(message)
    if not ok:
        failures += 1

    total += 1
    ok, message = run_no_c_flag_passes_none_case()
    print(message)
    if not ok:
        failures += 1

    total += 1
    ok, message = run_lazy_branch_getter_case()
    print(message)
    if not ok:
        failures += 1

    total += 1
    ok, message = run_get_current_branch_type_case()
    print(message)
    if not ok:
        failures += 1

    for ok, message in run_script_scanning_cases():
        total += 1
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

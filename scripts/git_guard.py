#!/usr/bin/env python3
"""Parser-based Git command guard for Claude Code's PreToolUse hook.

Replaces the previous single-regex PreToolUse hook in .claude/settings.json,
which only matched literal substrings like "git push" or "git merge" and
missed equivalent invocations such as `git -C <repo> push --force`,
`/usr/bin/git push`, `env git push`, or compound command lines. See
docs/bootstrap/audit-remediation.md (Codex re-audit, P1-01) for the finding
this responds to.

Design, in order:

1. Read the PreToolUse hook JSON payload from stdin (the real, documented
   hook input contract: {"tool_name": ..., "tool_input": {"command": ...}}).
   Falls back to the $TOOL_INPUT_COMMAND environment variable some Claude
   Code versions also set, for defense in depth.
2. Split the raw command string into top-level segments on unquoted shell
   control operators (;, &&, ||, |, newline), respecting quoting and basic
   grouping so a single Bash tool call containing multiple commands or a
   pipeline is evaluated command-by-command.
3. Tokenize each segment with shlex (POSIX mode) instead of regex matching,
   so quoting and escaping are handled the way a real shell would.
4. Normalize the invocation: skip leading VAR=value assignments, unwrap
   `env`/`/usr/bin/env` (and its own flags/assignments), and resolve the
   executable to its basename so `/usr/bin/git`, `git`, and `env git` are
   all recognized as Git.
5. Walk past Git's global options (-C, -c, --git-dir=, --work-tree=, etc.)
   to find the effective subcommand.
6. Classify the subcommand: an explicit deny set for always-destructive
   operations (push, merge, rebase, clean, history-rewriting commands), a
   handful of conditional rules for operations that are destructive only
   with certain flags/arguments (reset, branch/tag deletion,
   checkout/switch/restore, commit --amend), one rule that depends on live
   repository state rather than only the command line (`git commit`
   without `--amend`, blocked when the current branch is `main` or a
   detached HEAD — see "Repository-state-dependent rule" below), and
   default-allow for anything else — including ordinary commands like
   `add`, `fetch`, `remote`, `stash`, `cherry-pick` that are not in scope
   for this guard and must not be broken by it.
7. As defense in depth against command substitution / backticks hiding a
   dangerous invocation inside what looks like a single argument (which
   step 3's tokenizer cannot see into — a real limitation, documented
   below and in docs/bootstrap/audit-remediation.md), a secondary regex
   scan runs specifically over the extracted contents of every `$(...)`
   and backtick region in the command, looking for `git ...
   <dangerous-subcommand>` patterns. This is intentionally scoped to those
   regions rather than the entire raw command string — an earlier version
   scanned the whole string and blocked any command merely printing or
   mentioning git-looking text (e.g. `echo "git push"`), which is not
   this guard's purpose and was a real false-positive bug, not a
   deliberate broader safety margin. If the scoped scan fires where the
   structural analysis did not already block, the command is blocked as
   "possibly obfuscated" rather than allowed — this is the "fail closed
   when ambiguous" behavior applied to what the parser cannot fully see.
8. Any command this guard cannot parse at all (unbalanced quotes, decode
   errors) is treated as a Git-related ambiguity only if it looks like it
   might contain a "git" token; otherwise it fails open, since blocking
   *all* Bash commands whenever this guard has a parsing hiccup is a much
   larger, unrelated blast radius than its purpose justifies. See
   "Residual limitations" below and in the audit-remediation document.

This module is both the hook entry point (`python3 scripts/git_guard.py`,
reading stdin) and an importable library for the regression tests in
scripts/test_git_guard.py — no test imports Claude Code or spawns a real
hook process.

## Repository-state-dependent rule

`git commit` (without `--amend`, which is already always blocked) is the one
rule in this guard that is not decided from the command line alone: it also
looks at the current branch, fetched lazily via `git branch --show-current`
only when a segment actually parses as a non-amend commit — not on every
Bash call — through an injectable `branch_getter` callable so the
regression tests never need a real Git repository or real subprocess call.
`branch_getter` is called with the `-C`-resolved target directory (`None`
if the command had no `-C`), so `git -C <other-repo> commit ...` checks
the *target* repo's branch, not whatever repo the hook process's own cwd
happens to be — see `find_git_subcommand`'s docstring for how that
directory is resolved (fixing a real finding from independent review:
this previously always checked the hook's own cwd regardless of `-C`).
Branch protection on `main` is otherwise only GitHub-enforced, pending
manual setup (see docs/agents/operating-model.md, enforcement layer 4); this
is local, tool-enforced defense in depth ahead of that, not a replacement
for it. `main` and a detached HEAD (`git branch --show-current` returns an
empty string) are blocked; a failure to determine the branch at all (not a
Git repository, `git` not on `PATH`, timeout) is also blocked, fail-closed,
rather than treated as a green light. Callers that do not pass a
`branch_getter` at all (every pre-existing test case in
scripts/test_git_guard.py, and any other importer of this module) keep the
prior, branch-agnostic behavior unchanged — this is an opt-in addition, not
a change to `evaluate_command`'s existing default behavior.

## Residual limitations (cannot be technically enforced by this guard)

- Command substitution and backticks (`$(...)`, `` `...` ``) are not
  recursively parsed; the secondary regex scan (scoped to the extracted
  contents of those regions specifically — see `_extract_command_substitutions`)
  is a heuristic safety net for this case, not a guarantee. Deeply
  obfuscated forms (base64 + eval, building the string via variable
  concatenation, calling through a wrapper script this guard has never
  seen, or invoking git indirectly through another language runtime's
  subprocess call with no `$(...)`/backtick in the Bash string at all)
  can defeat both layers.
- Git global-option recognition is a practical subset of real Git's
  option grammar, not a full reimplementation of Git's argument parser.
- This guard only inspects the Bash tool's `command` string before
  execution. It has no visibility into what a previously-approved command
  actually did, and no way to intercept non-Bash tool calls or MCP-server
  actions that might reach Git through another path.
- `.claude/settings.json`'s `permissions.deny` literal patterns remain in
  place as an additional, independent layer — this guard does not replace
  them, it is the primary, more precise mechanism the documentation now
  describes as authoritative.
- The `git commit` branch check runs `git branch --show-current` in this
  hook process's own working directory. If the command under evaluation
  changes directory first (e.g. `cd ../other-repo && git commit ...`), the
  branch actually checked is not necessarily the branch the commit will
  land on — the same class of blind spot already documented above for
  command substitution, not a new one.
"""

from __future__ import annotations

import json
import os
import re
import shlex
import subprocess
import sys
from typing import Callable

# ---------------------------------------------------------------------------
# Git invocation normalization
# ---------------------------------------------------------------------------

ASSIGNMENT_RE = re.compile(r"^[A-Za-z_][A-Za-z0-9_]*=")

ENV_NO_VALUE_FLAGS = {"-i", "--ignore-environment", "-0", "--null", "-v", "--verbose"}
ENV_VALUE_FLAGS = {"-u", "--unset", "-C", "--chdir", "-S", "--split-string"}

GIT_GLOBAL_VALUE_OPTS = {
    "-C", "-c", "--git-dir", "--work-tree", "--namespace", "--exec-path", "--super-prefix",
}
GIT_GLOBAL_FLAG_OPTS = {
    "--bare", "--no-replace-objects", "--literal-pathspecs", "--no-literal-pathspecs",
    "--glob-pathspecs", "--noglob-pathspecs", "--icase-pathspecs", "--no-optional-locks",
    "-p", "--paginate", "--no-pager", "--no-advice", "--no-lazy-fetch",
}

ALWAYS_BLOCK_SUBCOMMANDS = {
    "push": "push operations are always blocked; only a human may push, per the reviewed pull-request workflow",
    "merge": "merge is always blocked; only a human may merge, per the reviewed pull-request workflow",
    "rebase": "rebase rewrites commit history and is always blocked",
    "clean": "clean permanently deletes untracked files and is always blocked regardless of flags",
    "filter-branch": "filter-branch rewrites repository history and is always blocked",
    "filter-repo": "filter-repo rewrites repository history and is always blocked",
}

READ_ONLY_SUBCOMMANDS = {
    "status", "log", "show", "diff", "rev-parse", "ls-files", "ls-remote",
    "cat-file", "describe", "shortlog", "blame", "grep", "count-objects",
    "name-rev", "show-ref", "for-each-ref", "help", "version",
}


def basename_of(token: str) -> str:
    return token.rsplit("/", 1)[-1]


def strip_env_wrapper(tokens: list[str]) -> list[str]:
    """Skip leading VAR=value assignments and an `env`/`.../env` wrapper
    (with its own flags/assignments), returning tokens starting at the
    real command the caller intended to run."""
    i = 0
    n = len(tokens)
    while i < n and ASSIGNMENT_RE.match(tokens[i]):
        i += 1
    if i < n and basename_of(tokens[i]) == "env":
        i += 1
        while i < n:
            t = tokens[i]
            if ASSIGNMENT_RE.match(t):
                i += 1
                continue
            if t in ENV_NO_VALUE_FLAGS:
                i += 1
                continue
            if t in ENV_VALUE_FLAGS:
                i += 2
                continue
            if t.startswith("--unset=") or t.startswith("--chdir="):
                i += 1
                continue
            if t == "--":
                i += 1
                break
            break
        while i < n and ASSIGNMENT_RE.match(tokens[i]):
            i += 1
    return tokens[i:]


def find_git_subcommand(args: list[str]) -> tuple[str | None, list[str], bool, str | None]:
    """`args` are the tokens after the `git` executable itself.

    Returns (subcommand_or_None, remaining_args, ambiguous, target_dir).
    `ambiguous=True` means the guard could not confidently determine the
    effective subcommand and must fail closed. `target_dir` is the
    directory `-C`/`--chdir`-equivalent effective directory once every
    `-C <path>` option before the subcommand has been applied in order
    (git chains repeated `-C` the same way repeated `cd` would — each one
    is resolved relative to the previous, and an absolute path resets it;
    `os.path.join` already has that exact behavior for POSIX paths), or
    `None` if no `-C` was present. This exists so the branch-protection
    rule for `git commit` (see "Repository-state-dependent rule" below)
    can check the branch of the repo the command actually targets instead
    of always checking the hook process's own cwd — see
    docs/bootstrap/audit-remediation.md for the finding this fixes:
    `git -C <other-repo> commit ...` was previously classified using
    whatever branch the hook happened to be running in, not the target
    repo's branch.
    """
    i = 0
    n = len(args)
    target_dir: str | None = None
    while i < n:
        t = args[i]
        if t in ("--version", "-h", "--help"):
            return (None, args[i + 1 :], False, target_dir)  # informational, not mutating
        if t.startswith("--") and "=" in t:
            i += 1
            continue
        if t in GIT_GLOBAL_VALUE_OPTS:
            if i + 1 >= n:
                return (None, [], True, target_dir)  # option expects a value that is missing
            if t == "-C":
                target_dir = args[i + 1] if target_dir is None else os.path.join(target_dir, args[i + 1])
            i += 2
            continue
        if t in GIT_GLOBAL_FLAG_OPTS:
            i += 1
            continue
        if t.startswith("-"):
            # Unrecognized flag-shaped token before any subcommand was
            # found. Could be a global option this guard doesn't know
            # about, or a malformed invocation. Fail closed rather than
            # guess.
            return (None, args[i:], True, target_dir)
        return (t, args[i + 1 :], False, target_dir)
    return (None, [], False, target_dir)  # bare `git` with nothing else: not mutating


def classify_git_invocation(
    args: list[str],
    branch_getter: Callable[[str | None], str | None] | None = None,
) -> tuple[str, str]:
    """`args` are the tokens after the `git` executable. Returns
    (verdict, reason) where verdict is "allow" or "block".

    `branch_getter`, if given, is only called for a `git commit` segment
    without `--amend` (see module docstring, "Repository-state-dependent
    rule"), and is called with one argument: the `-C`-resolved target
    directory (see `find_git_subcommand`'s docstring), or `None` if the
    command had no `-C`, meaning "check the hook process's own cwd" as
    before. Omitting `branch_getter` entirely (the default) preserves this
    function's prior, branch-agnostic behavior for `commit` exactly as
    before."""
    subcommand, rest, ambiguous, target_dir = find_git_subcommand(args)
    if ambiguous:
        return ("block", "ambiguous git invocation: could not confidently determine the effective subcommand")
    if subcommand is None:
        return ("allow", "no mutating subcommand identified (e.g. bare `git`, `git --version`, `git --help`)")

    rest_set = set(rest)

    if subcommand in ALWAYS_BLOCK_SUBCOMMANDS:
        return ("block", ALWAYS_BLOCK_SUBCOMMANDS[subcommand])

    if subcommand == "reset":
        if not rest:
            return ("allow", "bare `git reset` only unstages; it does not move history or touch the working tree")
        return (
            "block",
            "`git reset` with any argument is blocked: reliably distinguishing a safe "
            "pathspec-only unstage from a history-moving or --hard working-tree reset "
            "requires repository state this guard does not have, so it fails closed",
        )

    if subcommand == "branch":
        if rest_set & {"-d", "-D", "--delete"}:
            return ("block", "git branch deletion is blocked")
        return ("allow", "git branch without a deletion flag is safe (list/create)")

    if subcommand == "tag":
        if rest_set & {"-d", "--delete"}:
            return ("block", "git tag deletion is blocked")
        return ("allow", "git tag without a deletion flag is safe (list/create)")

    if subcommand in ("checkout", "switch"):
        if rest_set & {"-f", "--force", "--discard-changes", "-D"}:
            return ("block", f"git {subcommand} with a force/discard flag can destroy uncommitted work")
        if "--" in rest:
            return ("block", f"git {subcommand} -- <pathspec> discards working-tree changes to those paths")
        return ("allow", f"git {subcommand} without force/discard flags does not destroy uncommitted work")

    if subcommand == "restore":
        if "--staged" in rest_set and not (rest_set & {"--worktree", "-W"}):
            return ("allow", "git restore --staged only unstages; it does not touch the working tree")
        return ("block", "git restore without --staged (or combined with --worktree) can discard working-tree changes")

    if subcommand == "commit":
        if "--amend" in rest_set:
            return ("block", "git commit --amend rewrites the previous commit")
        if branch_getter is None:
            return ("allow", "git commit without --amend does not rewrite existing history")
        current_branch = branch_getter(target_dir)
        if current_branch is None:
            return (
                "block",
                "git commit could not determine the current branch; failing closed so a "
                "commit cannot silently land on main or an untracked detached HEAD",
            )
        if current_branch == "main":
            return (
                "block",
                "git commit while on `main` is blocked; commit on a feature branch and "
                "open a reviewed pull request instead",
            )
        if current_branch == "":
            return (
                "block",
                "git commit while in a detached HEAD is blocked; the commit would not be "
                "reachable from any branch and is easy to lose",
            )
        return (
            "allow",
            f"git commit without --amend, on branch `{current_branch}` (not main), does "
            "not rewrite existing history",
        )

    if subcommand == "reflog":
        if rest_set & {"expire", "delete"}:
            return ("block", "git reflog expire/delete can permanently remove recovery history")
        return ("allow", "git reflog show/list is read-only")

    if subcommand in READ_ONLY_SUBCOMMANDS:
        return ("allow", f"git {subcommand} is a recognized read-only command")

    return ("allow", f"git {subcommand} is not in the destructive-operation list this guard blocks")


# ---------------------------------------------------------------------------
# Shell command-line splitting and tokenization
# ---------------------------------------------------------------------------


def split_top_level(command: str) -> list[str]:
    """Split a shell command line into segments on unquoted ;, &&, ||, |,
    and newlines, without descending into quotes or $(...)/(...)/{...}
    grouping. This is intentionally not a full shell grammar; see the
    module docstring's "Residual limitations" section."""
    segments: list[str] = []
    buf: list[str] = []
    i = 0
    n = len(command)
    quote: str | None = None
    depth = 0
    while i < n:
        c = command[i]
        if quote:
            buf.append(c)
            if c == "\\" and quote == '"' and i + 1 < n:
                buf.append(command[i + 1])
                i += 2
                continue
            if c == quote:
                quote = None
            i += 1
            continue
        if c in ("'", '"'):
            quote = c
            buf.append(c)
            i += 1
            continue
        if c == "\\" and i + 1 < n:
            buf.append(c)
            buf.append(command[i + 1])
            i += 2
            continue
        if c in "({":
            depth += 1
            buf.append(c)
            i += 1
            continue
        if c in ")}":
            depth = max(0, depth - 1)
            buf.append(c)
            i += 1
            continue
        if depth == 0:
            if c == "&" and i + 1 < n and command[i + 1] == "&":
                segments.append("".join(buf))
                buf = []
                i += 2
                continue
            if c == "|" and i + 1 < n and command[i + 1] == "|":
                segments.append("".join(buf))
                buf = []
                i += 2
                continue
            if c == "|":
                segments.append("".join(buf))
                buf = []
                i += 1
                continue
            if c in ";\n":
                segments.append("".join(buf))
                buf = []
                i += 1
                continue
        buf.append(c)
        i += 1
    segments.append("".join(buf))
    return [s.strip() for s in segments if s.strip()]


class UnparsableCommand(Exception):
    pass


def analyze_segment(
    segment: str,
    branch_getter: Callable[[str | None], str | None] | None = None,
) -> tuple[str, str] | None:
    """Returns (verdict, reason) if this segment invokes Git, else None."""
    try:
        tokens = shlex.split(segment, posix=True)
    except ValueError as exc:
        raise UnparsableCommand(f"could not tokenize shell segment ({exc}): {segment!r}") from exc
    if not tokens:
        return None
    cmd_tokens = strip_env_wrapper(tokens)
    if not cmd_tokens:
        return None
    if basename_of(cmd_tokens[0]) != "git":
        return None
    return classify_git_invocation(cmd_tokens[1:], branch_getter)


# A heuristic, non-authoritative safety net for dangerous git subcommands
# hidden inside command substitution / backticks, which the tokenizer above
# cannot see into (it treats `$(git push)` as a single opaque argument to
# whatever it's passed to). See "Residual limitations" in the module
# docstring.
_OBFUSCATION_RE = re.compile(
    r"\bgit\b[^|;&\n]{0,80}?\b(push|merge|rebase|clean|filter-branch|filter-repo)\b"
)


def _extract_command_substitutions(raw_command: str) -> list[str]:
    """Extract the inner text of every `$(...)` and `` `...` `` region in
    raw_command, with simple depth-tracking so nested parentheses inside
    `$(...)` (e.g. `$(git log $(date))`) don't truncate the match early.

    This exists to scope the obfuscation heuristic below to only the parts
    of a command the structural tokenizer genuinely cannot see into.
    Scanning the *entire* raw command text (the prior behavior) flagged
    any command that merely printed or mentioned git-looking text — e.g.
    `echo "git push"`, `printf 'git push\\n'`, or
    `python3 -c "print('git push')"` were all denied even though none of
    them invoke git — which is a real, demonstrated false-positive class,
    not the command-substitution case this heuristic exists for. See the
    regression tests in scripts/test_git_guard.py for both the
    false-positive fix and continued detection of the real case."""
    regions: list[str] = []
    i = 0
    n = len(raw_command)
    while i < n:
        if raw_command[i : i + 2] == "$(":
            depth = 1
            j = i + 2
            while j < n and depth > 0:
                if raw_command[j] == "(":
                    depth += 1
                elif raw_command[j] == ")":
                    depth -= 1
                j += 1
            regions.append(raw_command[i + 2 : max(j - 1, i + 2)])
            i = j
            continue
        if raw_command[i] == "`":
            end = raw_command.find("`", i + 1)
            if end == -1:
                break
            regions.append(raw_command[i + 1 : end])
            i = end + 1
            continue
        i += 1
    return regions


def evaluate_command(
    raw_command: str,
    branch_getter: Callable[[str | None], str | None] | None = None,
) -> tuple[str, str]:
    """Top-level entry point used by both the hook and the tests. Returns
    (verdict, reason).

    `branch_getter` is forwarded to the `git commit` branch-protection rule
    (see module docstring). Omitting it — every pre-existing call site in
    this module's tests — leaves that rule's prior behavior unchanged."""
    if not raw_command or not raw_command.strip():
        return ("allow", "empty command")

    try:
        segments = split_top_level(raw_command)
        blocked_reasons = []
        for segment in segments:
            result = analyze_segment(segment, branch_getter)
            if result is not None and result[0] == "block":
                blocked_reasons.append(result[1])
        if blocked_reasons:
            return ("block", "; ".join(blocked_reasons))
    except UnparsableCommand as exc:
        # A command we cannot tokenize at all. Fail closed only if it looks
        # like it might involve git — blocking every unparsable Bash
        # command regardless of content (e.g. one with unusual quoting in
        # an unrelated one-liner) would be a disproportionate blast radius
        # for a git-specific guard.
        if re.search(r"\bgit\b", raw_command):
            return ("block", f"unparsable command mentions git; failing closed ({exc})")
        return ("allow", f"unparsable command does not appear to involve git ({exc})")

    # Defense-in-depth heuristic for substitution-hidden invocations. Scoped
    # to actual $(...)/backtick regions only (see
    # _extract_command_substitutions's docstring for why scanning the whole
    # raw command was a real false-positive bug, not intended behavior).
    for region in _extract_command_substitutions(raw_command):
        if _OBFUSCATION_RE.search(region):
            return (
                "block",
                "a command-substitution region contains a dangerous git pattern "
                "this guard cannot structurally verify; failing closed on this ambiguity",
            )

    return ("allow", "no blocked git operation detected")


# ---------------------------------------------------------------------------
# Hook entry point
# ---------------------------------------------------------------------------


def extract_command_from_hook_payload(raw_stdin: str) -> str | None:
    if raw_stdin.strip():
        try:
            payload = json.loads(raw_stdin)
        except json.JSONDecodeError:
            payload = None
        if isinstance(payload, dict):
            tool_input = payload.get("tool_input")
            if isinstance(tool_input, dict):
                command = tool_input.get("command")
                if isinstance(command, str):
                    return command
    # Defense in depth: some Claude Code versions also expose the Bash
    # command via this environment variable.
    env_command = os.environ.get("TOOL_INPUT_COMMAND")
    if env_command:
        return env_command
    return None


def get_current_branch(cwd: str | None = None) -> str | None:
    """Best-effort current branch for the `git commit` branch-protection
    rule. Returns "" for a detached HEAD, or None if it could not be
    determined at all (not a Git repository, `git` not on `PATH`, timeout,
    or any other failure) — the caller treats None as "unknown", not as
    permission to proceed.

    `cwd`, when given, is the `-C`-resolved directory the actual `git`
    invocation under evaluation targets (see `find_git_subcommand`'s
    docstring) — checking that directory instead of always the hook
    process's own working directory is what makes `git -C <other-repo>
    commit ...` get classified against the *target* repo's branch rather
    than whatever repo the hook happens to be running in."""
    try:
        result = subprocess.run(
            ["git", "branch", "--show-current"],
            capture_output=True,
            text=True,
            timeout=5,
            cwd=cwd,
        )
    except (OSError, subprocess.SubprocessError):
        return None
    if result.returncode != 0:
        return None
    return result.stdout.strip()


def main() -> int:
    try:
        raw_stdin = sys.stdin.read()
    except Exception:  # pragma: no cover - defensive; stdin should be a pipe
        raw_stdin = ""

    command = extract_command_from_hook_payload(raw_stdin)
    if command is None:
        # Nothing we can evaluate. Do not block Bash calls this guard was
        # never able to inspect — see module docstring.
        return 0

    verdict, reason = evaluate_command(command, branch_getter=get_current_branch)
    if verdict == "block":
        message = (
            "Blocked by scripts/git_guard.py: "
            + reason
            + ". Per AGENTS.md, CLAUDE.md, and docs/agents/operating-model.md, "
            "destructive or history-mutating Git operations are not permitted "
            "for any agent in this repository — only a human may perform them, "
            "through the reviewed pull-request workflow."
        )
        output = {
            "hookSpecificOutput": {"permissionDecision": "deny"},
            "systemMessage": message,
        }
        print(json.dumps(output), file=sys.stderr)
        return 2

    return 0


if __name__ == "__main__":
    sys.exit(main())

# Agent Operating Model

## Roles

- Humans own final product, architecture, security, and production decisions.
- Claude focuses on planning, analysis, and independent review.
- Codex focuses on controlled implementation within approved scope.

## Mandatory Rules

- planning agents are read-only unless assigned documentation work
- review agents are always read-only
- implementers cannot approve their own work
- no agent can merge its own pull request
- no agent may access production data or unrestricted credentials

## Workflow

Issue -> Specification -> Clarification -> Architecture and test impact -> Human approval -> Isolated implementation branch -> Automated verification -> Independent review -> Human merge

## Enforcement Layers

The rules above are enforced at different layers, with different real
strength. Do not read any of them as stronger than they are — see
`docs/bootstrap/audit-remediation.md` (P1-2) for how each was verified.

1. **Runtime-enforced (Claude).** Every `.claude/agents/*.md` file carries
   real YAML frontmatter (`tools:`) that Claude Code's subagent system
   technically restricts to when the agent is invoked via the Task/Agent
   tool — confirmed against the installed Claude Code CLI. All six Claude
   agents are scoped to `Read, Grep, Glob, Bash` only; none can call Edit or
   Write. `.claude/settings.json` additionally wires a `PreToolUse` hook on
   every Bash call to `scripts/git_guard.py`, a parser-based guard (not a
   single regex) that tokenizes the command with a real shell-aware
   splitter, normalizes `env`/absolute-path/global-option forms of Git
   invocation, and blocks push, merge, rebase, clean, history-rewriting
   commands, and conditionally-destructive forms of reset/checkout/switch/
   restore/branch/tag/commit — see `scripts/git_guard.py`'s module
   docstring for the full design and its documented residual limitations
   (command substitution can hide a command from the tokenizer; a
   secondary regex-based heuristic is a safety net for that case, not a
   guarantee). Also blocks `git commit` (without `--amend`, already always
   blocked) while on `main` or a detached HEAD, as local defense in depth
   ahead of GitHub branch protection (layer 4 below) — fetched lazily via
   an injectable `branch_getter`, fail-closed if the current branch cannot
   be determined at all. Regression-tested by `scripts/test_git_guard.py`
   (70 cases: every form named in the audit finding, the commit/branch
   rule above, plus compound commands, malformed input, and unrelated safe
   commands that must not be broken), run in CI and required by
   `scripts/verify-bootstrap.sh`. The static
   `permissions.deny` literal patterns remain as a coarse, secondary
   backstop, not the primary mechanism. `.claude/settings.json` also denies
   reads of known secret file patterns for every Claude Code session in
   this repository, regardless of which agent issues the command.
2. **Runtime-enforced (Codex), operator-applied.** Codex has no equivalent
   of Claude's per-agent tool scoping, and does not read a project-local
   `.codex/config.toml` — confirmed against the installed Codex CLI.
   `--sandbox read-only` / `--sandbox workspace-write` and
   `--ask-for-approval` are real, OS-level enforced settings, but they must
   be applied by the human operator at invocation time (see the profile
   reference in `.codex/config.toml` and each Codex agent file's "Runtime
   Tool Enforcement" section). This layer only holds if a human actually
   applies it.
3. **CI-enforced.** `scripts/validate_phase0.py`, run in
   `.github/workflows/phase0-validate.yml`, mechanically checks agent file
   structure, skill structure, ADR structure, forbidden file types, secret
   patterns, and the presence and shape of `.claude/settings.json` on every
   push and pull request. This catches drift and missing declarations; it
   cannot observe what an agent actually did during a session.
4. **GitHub-enforced, pending manual setup.** Branch protection on `main`
   (required PR review, blocked direct/force pushes, required status
   checks) is configured in GitHub itself, not in this repository's files —
   see `docs/bootstrap/manual-setup-checklist.md`. This is what actually
   prevents self-merge; nothing above does.
5. **Human procedural controls.** "Read-only unless a human explicitly
   assigns documentation work," "no agent may approve or merge its own
   work," and "escalate when evidence is missing" depend on a human
   following the stated process — they are not, and in some cases (Claude's
   static per-agent tool scoping) cannot be, enforced by any tool. Treat
   them as requirements on human behavior, not guarantees.

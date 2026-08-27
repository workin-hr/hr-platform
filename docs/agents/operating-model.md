# Agent Operating Model

## Instruction Authority

Repository-root `AGENTS.md` is the single canonical policy and workflow
source. This document explains the operating model but cannot override it.
`CLAUDE.md` imports `AGENTS.md`; all agent definitions and repository-authored
skills must declare that they inherit it. Policy changes begin in `AGENTS.md`
and propagate to enforcement, catalogs, definitions, and validation in the
same branch.

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
- every change must complete `AGENTS.md`'s Mandatory Change Propagation check
  before handoff

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
   guarantee). It also resolves indirect execution — `bash deploy.sh`,
   `./deploy.sh`, `source deploy.sh`, `bash -c "git push"` — by reading the
   referenced script and evaluating its contents through the same
   pipeline, bounded in recursion depth and cycle-safe. That layer was
   added on 2026-08-12 after an agent ran a prepared push script in this
   repository and pushed five branches: the command string held no blocked
   verb, so the guard allowed it. Contents are *evaluated*, never text-
   searched, so a script that merely discusses pushing in a comment,
   `echo`, or heredoc is not blocked. Also blocks `git commit` (without
   `--amend`, already always blocked) while on `main` or a detached HEAD,
   as local defense in depth ahead of GitHub branch protection (layer 4
   below) — fetched lazily via an injectable `branch_getter`, fail-closed
   if the current branch cannot be determined at all. Regression-tested by
   `scripts/test_git_guard.py` (110 cases: every form named in the audit
   finding, the commit/branch rule above, the script-resolution layer and
   its false-positive guards, plus compound commands, malformed input, and
   unrelated safe commands that must not be broken), run in CI and
   required by `scripts/verify-bootstrap.sh`. The static
   `permissions.deny` literal patterns remain as a coarse, secondary
   backstop, not the primary mechanism. `.claude/settings.json` also denies
   Read and Edit of known secret file patterns for every Claude Code
   session in this repository, regardless of which agent issues the
   command — `.env*`, `.gh-token`, `*.pem`, plus generic (not
   product-specific) credential/key patterns (`*credentials*`, `*secret*`,
   `*.key`, `id_rsa*`, `id_ed25519*`, `*.p12`, `*.pfx`, `*.keystore`,
   `*.jks`) added ahead of discovery evidence landing under `evidence/`,
   per `AGENTS.md`'s boundary against storing production credentials or
   customer-sensitive data. Each pattern must cover both Read and Edit —
   Read-only coverage would still leave the file overwritable —
   regression-tested in `scripts/test_validate_phase0.py`.
2. **Runtime-enforced (Codex), operator-applied.** Codex has no equivalent
   of Claude's per-agent tool scoping, and does not read a project-local
   `.codex/config.toml` — confirmed against the installed Codex CLI.
   `--sandbox read-only` / `--sandbox workspace-write` and
   `--ask-for-approval` are real, OS-level enforced settings, but they must
   be applied by the human operator at invocation time (see the profile
   reference in `.codex/config.toml` and each Codex agent file's "Runtime
   Tool Enforcement" section). This layer only holds if a human actually
   applies it. Run `scripts/codex-preflight.sh <agent-name>` (e.g.
   `bootstrap-engineer` or `independent-verification-reviewer`) before
   invoking Codex to print the exact flags for that role, extracted
   directly from the agent file's own "Recommended invocation" block —
   this does not make the layer tool-enforced, it just removes reliance on
   remembering the right flags.
3. **CI-enforced.** `scripts/validate_phase0.py`, run in
   `.github/workflows/phase0-validate.yml`, mechanically checks agent file
   structure, skill structure, ADR structure, forbidden file types, secret
   patterns, and the presence and shape of `.claude/settings.json` on every
   push and pull request. This catches drift and missing declarations; it
   cannot observe what an agent actually did during a session.
4. **GitHub-enforced — Deferred, not merely pending.** Branch protection on
   `main` (required PR review, blocked direct/force pushes, required status
   checks) would be configured in GitHub itself, not in this repository's
   files — see `docs/bootstrap/manual-setup-checklist.md`. As of
   `docs/bootstrap/decision-log.md` D-013, this is an explicitly accepted
   plan limitation, not a "not yet configured" gap: `workin-hr` is a GitHub
   Free organization and `hr-platform` is private, so both the classic
   branch-protection API and the Rulesets API return `403` on this repo,
   and the repository owner has decided neither an organization plan
   upgrade nor making the repository public is in scope. Nothing in this
   layer currently prevents self-merge, force push, or a direct push to
   `main` at the platform level — see R-008 in
   `docs/bootstrap/risk-register.md` for the resulting risk and its
   temporary, non-platform-enforced mitigation. `scripts/check-branch-protection.sh`
   (GH-1) remains built and regression-tested but pending indefinitely
   under this constraint — requires `gh` authenticated against the live
   organization/repository, plus `jq`, and would mechanically confirm
   required review count, `enforce_admins`, no force pushes, and that the
   required status check matches the actual job id in
   `.github/workflows/phase0-validate.yml`, if this is ever revisited.
5. **Human procedural controls.** "Read-only unless a human explicitly
   assigns documentation work," "no agent may approve or merge its own
   work," and "escalate when evidence is missing" depend on a human
   following the stated process — they are not, and in some cases (Claude's
   static per-agent tool scoping) cannot be, enforced by any tool. Treat
   them as requirements on human behavior, not guarantees.

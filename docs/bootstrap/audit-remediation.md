# Phase 0 Audit Remediation

This records the response to the independent Claude audit that returned
`REQUEST CHANGES` on `bootstrap/engineering-foundation`. Every P1 and P2
finding is mapped to what changed, how it was verified, and what (if
anything) still requires a human. P3 items were intentionally not
implemented — see the end of this document.

## P1 findings

### P1-1: ADR structure and validator mismatch

- **Original issue**: The ADR template and its dedicated validator
  (`.agents/skills/create-adr/scripts/validate-adr.sh`) required a
  `## Decision` heading, but all 8 real ADRs used `## Proposed Direction`
  instead. The validator failed on every real ADR when actually run, while
  the master validator (`scripts/validate_phase0.py`) only checked for
  `## Status\n\nProposed` and never caught the mismatch — so "Phase 0
  validation passed" did not mean what it appeared to mean.
- **Remediation**: Adopted one authoritative ADR format — a `## Metadata`
  table (ADR ID, Title, Status, Date, Owners, Deciders, Related Issues,
  Supersedes, Superseded By) plus `## Context`, `## Decision`,
  `## Alternatives Considered`, `## Consequences`, `## Risks`,
  `## Validation Evidence`, `## Open Questions`. All 8 ADRs kept their
  `Proposed` status and now carry an explicit "Approval status: Proposed —
  not yet approved" marker in `## Decision`. Renamed the template
  `0000-template.md` -> `ADR-0000-template.md` for naming consistency.
  Rewrote `.agents/skills/create-adr/scripts/validate-adr.sh` to check file
  naming, all required sections, all required metadata fields, valid
  `Status` values, non-empty section content, and the unapproved-marker
  requirement for `Proposed` ADRs. Retired the duplicate template asset
  (`.agents/skills/create-adr/assets/adr-template.md` now redirects to the
  canonical one) and updated the skill's ordered workflow. Rewrote
  `scripts/validate_phase0.py::validate_adrs()` to perform the same full
  structural validation, so the master validator now actually fails when
  any individual ADR fails.
- **Files changed**: `docs/adr/ADR-0000-template.md` (renamed from
  `0000-template.md`), `docs/adr/ADR-0001-*.md` through `ADR-0008-*.md`,
  `docs/adr/README.md`, `.agents/skills/create-adr/SKILL.md`,
  `.agents/skills/create-adr/assets/adr-template.md`,
  `.agents/skills/create-adr/scripts/validate-adr.sh`,
  `scripts/validate_phase0.py`.
- **Validation command**: `bash .agents/skills/create-adr/scripts/validate-adr.sh docs/adr/ADR-000N-*.md` (run for all 8 ADRs plus the template) and `python3 scripts/validate_phase0.py`.
- **Validation result**: All 9 files (template + 8 ADRs) pass
  `validate-adr.sh` individually; `python3 scripts/validate_phase0.py`
  prints `Phase 0 validation passed.` Negative tests confirmed the new
  checks actually fire: an ADR with an empty `## Risks` section, an invalid
  `Status` value, and a missing naming pattern were each rejected with a
  specific error before being reverted.
- **Status**: Fixed.
- **Remaining human action**: None for this finding specifically. ADRs
  remain `Proposed` pending real Discovery evidence and human review before
  any can move to `Accepted` — that is expected, not a gap.

### P1-2: Agent permission enforcement

- **Original issue**: Every `.claude/agents/*.md` file was Markdown prose
  with no YAML frontmatter — Claude Code's real subagent system (confirmed
  against the installed CLI, v2.1.220) never loaded these as tool-scoped
  subagents at all; "Read-only" was pure documentation. `.codex/config.toml`
  declared `forbid_self_merge`, `forbid_self_approval`,
  `requires_approved_specification`, and `allowed_roots`, none of which are
  real Codex CLI settings (confirmed against the installed Codex CLI,
  v0.114.0) — Codex only loads `~/.codex/config.toml` (the operator's
  global config) plus CLI flags, never a project-local config file, so
  every one of these keys was inert.
- **Remediation**: Added real YAML frontmatter (`name`, `description`,
  `tools: Read, Grep, Glob, Bash`, `model`) to all six `.claude/agents/*.md`
  files, confirmed against real working examples on the installed Claude
  Code CLI (subagent tool-scoping is genuinely enforced when a subagent is
  invoked via the Task/Agent tool). Added `.claude/settings.json` with
  `permissions.deny` rules blocking force-push, merge, hard-reset,
  branch -D, and secret-file reads, plus a `PreToolUse` hook that inspects
  Bash commands and blocks the same destructive Git operations regardless
  of which agent issues them — both mechanisms and their exact JSON shape
  were verified against real, working `.claude/settings.json` files found
  on the local machine before being written here. Added
  `validate_claude_settings()` and an extension to `validate_agent_files()`
  in `scripts/validate_phase0.py` so CI fails if the frontmatter or
  settings file regresses. Rewrote `.codex/config.toml` to remove every
  fabricated key and replace it with an explicit statement that the file is
  not read by the Codex CLI, plus real, confirmed-valid `sandbox_mode` /
  `approval_policy` profile snippets for a human operator to copy into
  their own `~/.codex/config.toml`. Added a "Runtime Tool Enforcement"
  section to all 8 agent files (6 Claude, 2 Codex) explicitly labeling what
  is technically enforced versus what remains a
  "Procedural control — not technically enforceable by the current
  runtime," per the instruction not to claim enforcement that doesn't
  exist. Added an "Enforcement Layers" section to
  `docs/agents/operating-model.md` distinguishing runtime-enforced,
  CI-enforced, GitHub-enforced, and human-procedural controls.
- **Files changed**: `.claude/agents/*.md` (6 files), `.claude/settings.json`
  (new), `.codex/agents/*.md` (2 files), `.codex/config.toml`,
  `docs/agents/operating-model.md`, `scripts/validate_phase0.py`.
- **Validation command**: `python3 scripts/validate_phase0.py`;
  `python3 -c "import json; json.load(open('.claude/settings.json'))"`;
  `python3 -c "import tomllib; tomllib.load(open('.codex/config.toml','rb'))"`.
- **Validation result**: All three commands succeed; `validate_phase0.py`
  prints `Phase 0 validation passed.` The Bash-blocking hook's regex was
  tested against 12 real and safe commands (force push, `-f` push, `+ref`
  push, merge, hard reset, `branch -D`, `clean -f`, plus safe look-alikes
  like `git log --merges` and `git commit -m 'merge conflict fix'`) and
  correctly blocked only the destructive ones.
- **Status**: Fixed for what a repository can technically control. Codex
  enforcement is honestly documented as operator-dependent, not fixed,
  because no repository file can control it.
- **Remaining human action**: A human operator must actually copy the
  `[profiles.*]` blocks from `.codex/config.toml` into their own
  `~/.codex/config.toml` (or pass the equivalent `--sandbox`/
  `--ask-for-approval` flags directly) for Codex-side enforcement to take
  effect — this cannot be done from inside the repository.

### P1-3: Real Spec Kit integration

- **Original issue**: `.specify/` self-described as a "Spec Kit Integration
  Placeholder" with no real templates, scripts, or workflow behind the
  named Constitution -> Specify -> Clarify -> Plan -> Tasks -> Analyze
  sequence.
- **Remediation**: Confirmed `specify-cli` 0.8.15 was installed. Before
  touching the real repository, ran `specify init` in an isolated scratch
  copy (twice — once against an empty directory, once against a directory
  with pre-existing `AGENTS.md`/`CLAUDE.md` content) to verify it only
  appends within `<!-- SPECKIT START -->` / `<!-- SPECKIT END -->` markers
  and never overwrites existing content. Then ran, against the real
  repository: `specify init --here --integration claude --no-git --force`
  followed by `specify integration install codex --force`. `--no-git`
  because this repository already has its own Git history; `--force`
  because the directory is not empty (verified safe beforehand, not
  assumed). This installed real `.specify/templates/`,
  `.specify/scripts/bash/`, `.specify/workflows/`, and `speckit-*` skills
  under `.claude/skills/` and `.agents/skills/`. Migrated the existing 15
  constitution principles into the canonical
  `.specify/memory/constitution.md` (the non-standard
  `.specify/constitution.md` was removed to avoid two sources of truth for
  the same content), explicitly marking every principle `Proposed` pending
  human ratification rather than claiming it was already ratified. Updated
  `.specify/README.md` and `.specify/install-plan.md` to record exactly
  what was executed, and `.specify/workflow.md` to map each phase to its
  real installed skill command, restating that `/speckit-implement` remains
  forbidden during Phase 0.
- **Files changed**: `.specify/memory/constitution.md` (new, canonical),
  `.specify/constitution.md` (removed), `.specify/README.md`,
  `.specify/install-plan.md`, `.specify/workflow.md`, plus the
  vendor-installed `.specify/templates/`, `.specify/scripts/bash/`,
  `.specify/workflows/`, `.specify/integrations/`, `.specify/integration.json`,
  `.specify/init-options.json`, `.claude/skills/speckit-*/`,
  `.agents/skills/speckit-*/`, and marker-block appends to `CLAUDE.md` and
  `AGENTS.md`. Updated `scripts/validate_phase0.py` to require the new
  canonical paths and to validate vendor `speckit-*` skills only for
  frontmatter presence, not this repository's custom skill schema.
- **Validation command**: `specify --version`; `git diff AGENTS.md CLAUDE.md`
  (to confirm only marker blocks were appended); `python3 scripts/validate_phase0.py`.
- **Validation result**: `specify-cli` 0.8.15 confirmed; `git diff` showed
  only the expected marker-block appends with all pre-existing content
  intact; `validate_phase0.py` prints `Phase 0 validation passed.`
- **Status**: Fixed — Spec Kit is genuinely operational, not a placeholder.
- **Remaining human action**: Formal ratification of the constitution (see
  its Governance section) is a human decision, not something this
  remediation can or should do on its own.

### P1-4: Human approval and merge evidence

- **Original issue**: The entire repository history was 2 commits, both
  authored by the same automated identity, zero merge commits, and no
  `main` branch existed locally or on `origin` — the documented human
  approval/independent review/human merge workflow had never observably
  run.
- **Remediation**: This cannot be fixed by an agent, and no fake evidence
  was generated. `docs/bootstrap/manual-setup-checklist.md` now spells out
  the exact 10-step sequence (establish and protect `main`; push the
  branch; open a real PR; run required checks; obtain independent audit;
  obtain human approval; resolve findings; human merge; record PR URL,
  approver, and merge commit in the decision log; run post-merge
  validation) and is explicitly classified `Pending human acceptance gate`.
- **Files changed**: `docs/bootstrap/manual-setup-checklist.md`.
- **Validation command**: `git log --all --merges`; `git branch -a`.
- **Validation result**: As of this remediation, still zero merge commits
  and no `main` branch — unchanged from the original audit, because this
  finding cannot be resolved by editing files.
- **Status**: **Pending human GitHub approval and merge.** Not marked
  complete, and must not be marked complete by any agent.
- **Remaining human action**: All 10 steps in
  `docs/bootstrap/manual-setup-checklist.md`'s "Human Approval And Merge
  Sequence" section.

## P2 findings

### P2-1 / P2-2: Risk register and decision log

- **Original issue**: Both were one-line-per-item lists with no owner,
  severity, status, date, or rationale fields.
- **Remediation**: Rewrote both with the full field set requested (Risk ID,
  Description, Category, Probability, Impact, Severity, Owner, Mitigation,
  Trigger, Contingency, Status, Target Date, Evidence, Last Reviewed for
  risks; Decision ID, Date, Decision, Status, Owner, Related ADR, Reason,
  Impact, Follow-up, Evidence for decisions). Populated only real,
  already-known bootstrap risks/decisions — including three new ones
  discovered during this remediation itself (R-007 tooling drift, R-008
  unexercised approval process; D-006/D-007/D-008 for the Spec Kit
  install, ADR format unification, and agent enforcement changes). Decision
  statuses that merely restate an ADR's direction (D-003, D-004, D-005) were
  corrected from an implied "settled" status to `Proposed`, matching the
  ADRs they depend on.
- **Files changed**: `docs/bootstrap/risk-register.md`,
  `docs/bootstrap/decision-log.md`.
- **Validation command**: `python3 scripts/validate_phase0.py` (both files
  are required paths; link/markdown checks also cover them).
- **Validation result**: Passed; markdownlint-cli2 and lychee report 0
  issues against both files.
- **Status**: Fixed.
- **Remaining human action**: Assign real individual owners (currently role
  names, e.g. "Solution Architect", not named people) once team membership
  exists — tracked as an open item, not blocking.

### P2-3: Migration discovery templates

- **Original issue**: `docs/migration/` only templated schema objects and a
  combined stored-procedure/trigger file — no coverage for views, events,
  data quality, character sets, invalid dates, orphan references, duplicate
  keys, table volume, sequences, validation queries, or cutover/rollback
  assumptions.
- **Remediation**: Split the combined file into
  `stored-procedure-and-function-inventory.md` and `trigger-inventory.md`,
  and added 11 more discovery-only templates (all header-only, explicitly
  labeled as not-yet-populated). Updated `docs/migration/README.md` to
  index all 14 templates and updated ADR-0004's Validation Evidence section
  to reference the full set instead of the retired filename.
- **Files changed**: `docs/migration/README.md`, 13 new template files
  under `docs/migration/`, removal of
  `docs/migration/stored-procedure-trigger-inventory.md`,
  `docs/adr/ADR-0004-mysql-to-postgresql-migration-approach.md`.
- **Validation command**: `python3 scripts/validate_phase0.py`.
- **Validation result**: Passed (no broken links to the removed filename).
- **Status**: Fixed.
- **Remaining human action**: None — these remain templates until real
  Discovery happens.

### P2-4 / P2-5: Operations readiness and threat-model readiness

- **Original issue**: `docs/operations/` was a one-line README stub with no
  sub-templates. `docs/security/README.md` promised a threat model that did
  not exist.
- **Remediation**: Added 11 operations planning templates covering
  environment/deployment strategy, release readiness, cutover/rollback,
  backup/restore, incident response, monitoring/alerting, runbook
  standards, smoke/post-deployment validation, gateway operational
  support, customer communication, and recovery objectives — each
  explicitly warns against fabricating real topology or RTO/RPO values.
  Added `docs/security/threat-model.md` with an explicit STRIDE method
  choice, empty threat register, and a `Not started — pending Discovery`
  status; updated `docs/security/README.md` to state current status
  accurately instead of implying a completed threat model.
- **Files changed**: `docs/operations/README.md` + 11 new files,
  `docs/security/threat-model.md` (new), `docs/security/README.md`.
- **Validation command**: `python3 scripts/validate_phase0.py`.
- **Validation result**: Passed.
- **Status**: Fixed.
- **Remaining human action**: None — templates remain empty until
  Discovery.

### P2-6: Architecture wording

- **Original issue**: `docs/architecture/system-context.md`'s "Target
  Context" stated the Java/Spring/Next.js/.NET/PostgreSQL stack as flat
  fact, without the "unless an approved ADR" hedge used in
  `architecture-principles.md`.
- **Remediation**: Renamed the section "Target Context (Intended Direction
  — Subject To Discovery And ADR Approval)" and annotated every bullet with
  its backing ADR and current `Proposed` status.
- **Files changed**: `docs/architecture/system-context.md`.
- **Validation command**: `python3 scripts/validate_phase0.py`.
- **Validation result**: Passed.
- **Status**: Fixed.
- **Remaining human action**: None.

### P2-7: Tool catalog consistency

- **Original issue**: `tool-catalog.md` listed "Git worktrees" as an
  "Install During Phase 0" item; `tool-decision-matrix.md` classified it as
  "Approve now, install later" — worktrees need no installation at all,
  being a built-in Git feature.
- **Remediation**: Removed the standalone "Git worktrees" bullet from
  `tool-catalog.md` (folded into the "Git" entry with a clarifying note)
  and reclassified the matrix row as "Approved — no install needed (built
  into Git)". Added `validate_tool_catalog_consistency()` to
  `scripts/validate_phase0.py` as a permanent regression guard.
- **Files changed**: `docs/tools/tool-catalog.md`,
  `docs/tools/tool-decision-matrix.md`, `scripts/validate_phase0.py`.
- **Validation command**: `python3 scripts/validate_phase0.py`.
- **Validation result**: Passed.
- **Status**: Fixed.
- **Remaining human action**: None.

### P2-8 / P2-9: CI tool enforcement and secret scanning

- **Original issue**: `.github/workflows/phase0-validate.yml` only ran
  `scripts/verify-bootstrap.sh`, which silently skipped markdownlint-cli2,
  yamllint, ShellCheck, actionlint, Gitleaks, and lychee whenever they
  weren't installed — so `docs/testing/test-strategy.md`'s "Every Commit"
  claims for secret detection and markdown/link checks were not actually
  enforced. The five regex patterns in `validate_phase0.py` were the only
  secret scanning that ever really ran, and were not described as limited.
- **Remediation**: Rewrote the CI workflow to install a pinned,
  checksum-verified copy of every required tool and run each as a
  non-skippable step: `actions/checkout`, `actions/setup-node`, and
  `actions/setup-python` pinned to commit SHAs resolved and verified via
  the GitHub API (not guessed); markdownlint-cli2 and yamllint installed at
  pinned versions via npm/pip; ShellCheck, actionlint, Gitleaks, and lychee
  downloaded as pinned release binaries with SHA-256 checksums verified
  before extraction. No `pull_request_target` is used; `permissions:
  contents: read` is kept at the top level. Added
  `validate_workflow_safety()` to `scripts/validate_phase0.py` as a
  permanent guard against `pull_request_target` and undeclared permissions.
  Added `.yamllint.yml` (relaxes only the two purely cosmetic rules that
  would otherwise fail on completely standard GitHub Actions/issue-form
  YAML: `document-start` and `truthy` key-checking; kept line-length at a
  generous 160 rather than disabling it) and `.markdownlint-cli2.jsonc`
  (disables MD013/MD060, pure formatting preferences, and excludes
  vendor-provided `speckit-*` skill files from linting, since they are not
  authored by this repository) and `lychee.toml`. Fixed the real issues
  these tools found in files this repository actually owns: a ShellCheck
  false-positive-prone `CDPATH=` idiom in `scripts/verify-bootstrap.sh`, a
  missing top-level heading in `.github/pull_request_template.md`, missing
  code-fence language tags in 4 files, and missing blank lines around
  headings in the new constitution file. Rewrote
  `docs/security/security-boundaries.md` to state Gitleaks as the
  authoritative scanner with an explicit baseline-handling and
  false-positive review procedure, and clarified in
  `scripts/validate_phase0.py` that its five regex patterns are a fast
  supplementary check, not comprehensive scanning. Updated
  `scripts/verify-bootstrap.sh`'s comments to state plainly that it is a
  lenient local developer convenience script, and that CI (not this
  script) is the actual enforcement point.
- **Files changed**: `.github/workflows/phase0-validate.yml`,
  `.yamllint.yml` (new), `.markdownlint-cli2.jsonc` (new), `lychee.toml`
  (new), `scripts/validate_phase0.py`, `scripts/verify-bootstrap.sh`,
  `docs/security/security-boundaries.md`, `docs/testing/test-strategy.md`,
  `.github/pull_request_template.md`, `.specify/memory/constitution.md`,
  `.specify/README.md`, `.specify/install-plan.md`,
  `.codex/agents/bootstrap-engineer.md`,
  `.codex/agents/independent-verification-reviewer.md`.
- **Validation command**: Every tool the CI workflow runs was independently
  downloaded, checksum-verified, and executed locally against this exact
  repository state before being wired into the workflow:
  `markdownlint-cli2 "**/*.md"`; `yamllint -s .`; `shellcheck scripts/*.sh
  .agents/skills/*/scripts/*.sh`; `actionlint`; `gitleaks detect --no-git
  --source . --redact --exit-code 1`; `lychee "**/*.md"`; `python3
  scripts/validate_phase0.py`.
- **Validation result**: All seven commands exit 0 against the current
  repository state (checksums for ShellCheck, actionlint, Gitleaks, and
  lychee verified via `sha256sum -c` before use; the same pinned versions
  and checksums are embedded in the CI workflow).
- **Status**: Fixed.
- **Remaining human action**: None to close this finding. Ongoing: review
  Dependabot/version bumps for these pinned tools periodically (not
  currently automated for non-GitHub-Actions binaries).

### P2-10: Product documentation validation

- **Original issue**: A prior sub-agent report claimed `docs/product/` was
  missing from `scripts/validate_phase0.py`'s `REQUIRED_DIRS`. On direct
  inspection during remediation, `docs/product` was already present in
  `REQUIRED_DIRS` — that part of the original finding did not reproduce.
  What was genuinely missing: individual required files under
  `docs/product/` were not validated, only the directory's existence.
- **Remediation**: Added the six actual files under `docs/product/`
  (`README.md`, `discovery-templates.md`,
  `existing-user-journey-inventory.md`, `non-functional-requirements.md`,
  `mvp-scope-prioritization.md`, `customer-impact-analysis.md`) to
  `REQUIRED_FILES` in `scripts/validate_phase0.py`, matching how other
  agent-scoped directories are treated. Confirmed CODEOWNERS already covers
  `docs/product/` via its `/docs/` wildcard — no CODEOWNERS change needed.
- **Files changed**: `scripts/validate_phase0.py`.
- **Validation command**: `python3 scripts/validate_phase0.py`.
- **Validation result**: Passed.
- **Status**: Fixed (file-level validation added; directory-level
  requirement confirmed already present).
- **Remaining human action**: None.

## Deferred P3 recommendations (not implemented)

Per instruction, P3 items were recorded for human review, not
auto-implemented:

- Four "planning" Claude agents (`program-bootstrap-architect`,
  `product-discovery-analyst`, `solution-architect`, `test-architect`) are
  conditionally read-only ("unless a human explicitly assigns documentation
  work"), which — as documented in each file's new "Runtime Tool
  Enforcement" section — cannot actually be enforced conditionally by
  Claude Code. Whether to accept this as-is, or to remove the conditional
  language since it cannot be technically honored, is a human call.
- `solution-architect.md` and `test-architect.md` both list
  `docs/architecture/` in scope — a shared read scope, not a write
  conflict, but a human may want single-owner-per-directory strictness.
- Wording drift between `AGENTS.md` ("Architecture and test impact",
  "Isolated implementation branch") and
  `docs/agents/operating-model.md` ("Architecture and testing impact",
  "Isolated implementation") remains — cosmetic, not fixed.
- `.github/dependabot.yml` still covers only the `github-actions`
  ecosystem — correct for now since no npm/maven/gradle manifests exist,
  but will need expansion once real code lands.

No agent count or scope was expanded during this remediation, as instructed.

## Scope confirmation

No Java, Spring Boot, Maven/Gradle, Next.js, Node application code, Flutter,
.NET, SQL/Flyway, Docker, Kubernetes, Kafka, Redis, or business/domain code
was added. Re-ran the same exhaustive forbidden-file search used in the
original audit (`scripts/validate_phase0.py`'s `FORBIDDEN_*` checks, plus a
manual `find`/`grep` sweep) after all changes above — clean. `gitleaks
detect --no-git --source . --redact` and the master validator's own secret
scan both reported no secrets. No fake human-review, approval, or merge
evidence was created anywhere in this remediation.

---

## Codex Re-Audit Remediation (Second Audit)

This section responds to a second independent audit — a Codex re-audit of
`bootstrap/engineering-foundation` — that also returned `REQUEST CHANGES`.
It is appended below the first audit's record without altering anything
above; the two audits and their remediations are historically distinct.
**`P1-4: Human approval and merge` remains `Pending human GitHub approval
and merge`** — this second remediation did not touch it, and no agent may
mark it complete.

### P1-01: Claude destructive-Git enforcement is incomplete

- **Severity**: P1
- **Confirmed issue**: The previous `.claude/settings.json` `PreToolUse`
  hook was a single regex matching only literal forms (`git push`,
  `git merge`, `git reset --hard`). It missed `git -C <repo> push --force`,
  `/usr/bin/git push`, `env git push`, `/usr/bin/env git ... push`, Git
  global options before the subcommand, and compound/multi-command Bash
  invocations. Documentation described this as blocking "destructive Git
  operations" generally, overstating what a literal-string regex can
  actually catch.
- **Remediation**: Replaced the regex with `scripts/git_guard.py`, a
  parser-based guard: it tokenizes the Bash command with `shlex` (not
  regex matching), splits compound commands on unquoted `;`/`&&`/`||`/`|`,
  normalizes `env`/absolute-path/global-option forms of Git invocation,
  identifies the effective subcommand past Git's global options
  (`-C`, `-c`, `--git-dir=`, etc.), and classifies it against an explicit
  deny set (push, merge, rebase, clean, `filter-branch`/`filter-repo`) and
  conditional rules (reset, branch/tag deletion, checkout/switch/restore
  force or discard forms, `commit --amend`, `reflog expire`/`delete`).
  Unrecognized-but-ordinary commands (`add`, `fetch`, `remote`, `stash`,
  etc.) are left alone by design. A secondary raw-regex scan across the
  full command string is a defense-in-depth heuristic for dangerous
  keywords hidden inside command substitution, which the tokenizer cannot
  see into. `.claude/settings.json`'s `PreToolUse` hook now calls
  `python3 "$CLAUDE_PROJECT_DIR/scripts/git_guard.py"` instead of the
  inline regex; the old literal `permissions.deny` patterns remain as a
  coarse secondary backstop, not the primary mechanism. Updated all 6
  Claude agent files' "Runtime Tool Enforcement" sections and
  `docs/agents/operating-model.md`'s "Enforcement Layers" to describe the
  real, current mechanism and its documented residual limitations, instead
  of the old regex's narrower behavior.
- **Files changed**: `scripts/git_guard.py` (new), `scripts/test_git_guard.py`
  (new), `.claude/settings.json`, `scripts/validate_phase0.py`
  (`validate_claude_settings()` now requires the hook to invoke
  `scripts/git_guard.py`), `scripts/verify-bootstrap.sh`,
  `.github/workflows/phase0-validate.yml`, all 6 `.claude/agents/*.md`,
  `docs/agents/operating-model.md`.
- **Tests added**: `scripts/test_git_guard.py` — 63 deterministic cases:
  every command form listed in the audit finding (all blocked forms and
  all read-only forms), additional destructive-equivalent forms (`-c`
  assignment, `env -i`, `GIT_DIR=... git push`, `--git-dir=`, force
  checkout/switch, non-staged restore, `commit --amend`, `tag --delete`,
  `reflog expire`, `filter-branch`), additional safe-equivalent forms that
  must not be broken (`branch`/`tag` without deletion, plain
  checkout/switch, `restore --staged`, bare `reset`, `rev-parse`,
  `ls-files`, `--version`), commands entirely unrelated to Git (`ls`,
  `python3 ...`, `echo`, piped `cat | wc`), compound/multi-command forms
  (`&&`, `;`, `|`), and malformed-input handling (unterminated quotes;
  non-JSON stdin; a payload missing `tool_input.command`; a
  differently-shaped payload) proving the hook process itself never
  crashes and always exits 0 or 2 predictably. Also includes end-to-end
  subprocess tests that invoke the real hook entry point (stdin JSON in,
  exit code + stderr JSON out), not just the internal classification
  function.
- **Commands executed**:
  `python3 scripts/test_git_guard.py`;
  manual end-to-end check:
  `echo '{"tool_name":"Bash","tool_input":{"command":"git push --force"}}' | python3 scripts/git_guard.py` (exit 2, denial JSON on stderr) and the same with `git status` (exit 0).
- **Results**: 63/63 regression cases pass. Manual end-to-end checks
  confirmed the exact denial JSON shape
  (`{"hookSpecificOutput": {"permissionDecision": "deny"}, ...}`) and exit
  codes (2 for block, 0 for allow) that Claude Code's real `PreToolUse`
  hook contract expects.
- **Residual limitation**: Documented in `scripts/git_guard.py`'s module
  docstring and repeated in `docs/agents/operating-model.md`: command
  substitution (`$(...)`, backticks) is not recursively parsed — the
  tokenizer sees it as one opaque argument, and the secondary regex scan
  is a heuristic safety net for that case, not a guarantee. Deeply
  obfuscated forms (base64 + eval, string concatenation, an unknown
  wrapper script) can still defeat both layers. `git reset` with *any*
  argument is deliberately over-blocked (including safe pathspec-only
  unstage operations) because reliably distinguishing a pathspec from a
  revision without repository state is not practical in a static hook —
  documented as a conscious fail-closed tradeoff, not an oversight. This
  guard only inspects the Bash tool's `command` string; it has no
  visibility into non-Bash tool calls or MCP-server actions.
- **Closure status**: Fixed.

### P2-01: CI and documentation truthfulness

- **Severity**: P2
- **Confirmed issue**: Documentation claimed "installs a pinned,
  checksum-verified copy of every tool" in CI, but markdownlint-cli2 and
  yamllint were only version-pinned (npm/PyPI install, no checksum check),
  `runs-on: ubuntu-latest` floats to whatever image GitHub currently
  serves under that label, Node `"22"` and Python `"3.12"` were floating
  minor-version ranges, and `scripts/verify-bootstrap.sh` invoked
  `shellcheck`/`actionlint`/`gitleaks`/`lychee` by bare name with no
  guarantee it was the same binary CI's own steps had just downloaded.
- **Remediation**: `runs-on: ubuntu-24.04` (a fixed runner generation, not
  a byte-identical image — documented as such, not oversold).
  `actions/checkout`, `actions/setup-node`, `actions/setup-python` remain
  pinned to immutable commit SHAs (re-verified against the GitHub API, not
  reused blindly). Node pinned to exact `22.23.2`, Python to exact
  `3.12.13`. ShellCheck, actionlint, Gitleaks, and Lychee remain pinned to
  an exact version and SHA-256-checksum-verified before use, but are now
  installed into a job-local `$GITHUB_WORKSPACE/.ci-tools` directory that
  is added to `$GITHUB_PATH` — every later step in the job, including the
  final `scripts/verify-bootstrap.sh` invocation, resolves these tools to
  the exact same binaries CI's own dedicated steps already ran, with
  nothing re-downloaded. markdownlint-cli2 and yamllint remain
  version-pinned only, now explicitly labeled as such (not
  checksum-verified — installed from the npm/PyPI registries). Introduced
  `BOOTSTRAP_STRICT` (set `"true"` at the workflow level): in strict mode,
  `scripts/verify-bootstrap.sh` treats a missing required tool as a hard
  failure instead of a skip; in default (unset) mode, used for local
  development, it prints install guidance and skips. Rewrote
  `docs/testing/test-strategy.md`'s "Every Commit" section to state each
  tool's actual guarantee precisely: `checksum-verified` only for the four
  tools that are, `version-pinned only` for the two that are,
  `immutable action reference` for the three GitHub Actions, `fixed
  runner generation` (not full OS reproducibility) for `ubuntu-24.04`.
- **Files changed**: `.github/workflows/phase0-validate.yml`,
  `scripts/verify-bootstrap.sh`, `docs/testing/test-strategy.md`,
  `scripts/validate_phase0.py` (extended `validate_scripts_exist()` for
  the new scripts).
- **Tests added**: Manual strict/non-strict mode verification (see
  Commands executed) rather than a dedicated regression script — this
  finding is about CI/shell wiring and documentation accuracy, not a unit
  of classification logic like P1-01/P2-02.
- **Commands executed**: `python3 scripts/validate_phase0.py`;
  `bash scripts/verify-bootstrap.sh` (default mode, all 6 external tools
  correctly skipped with guidance since none are installed in this shell);
  `BOOTSTRAP_STRICT=true bash scripts/verify-bootstrap.sh` with all 6
  pinned tool binaries placed on `PATH` under their canonical names
  (mirroring exactly what the CI job's tools directory does) — confirmed
  it runs all 6 tools successfully using those exact binaries with zero
  re-downloads; `env -i PATH="/usr/bin:/bin" BOOTSTRAP_STRICT=true bash
  scripts/verify-bootstrap.sh` with none of the 6 tools available,
  confirming a hard failure (exit 1) listing every missing required tool,
  not a silent skip; `python3 -c "import yaml; yaml.safe_load(...)"`,
  actionlint, and yamllint against the rewritten workflow file itself.
- **Results**: All commands passed as designed. Strict mode with tools
  present: exit 0, all 6 tools ran. Strict mode with tools absent: exit 1,
  explicit per-tool failure messages, zero silent skips. Workflow YAML is
  syntactically valid and passes both actionlint and yamllint.
- **Residual limitation**: `ubuntu-24.04` is a runner *generation*, not an
  exact OS image — GitHub updates installed packages within that image
  over time; this is now stated explicitly rather than implied away.
  markdownlint-cli2 and yamllint remain checksum-unverified by design
  (no official per-release checksum artifact was located for either in
  the time available for this remediation); this is disclosed, not fixed
  further.
- **Closure status**: Fixed.

### P2-02: Dynamic ADR discovery

- **Severity**: P2
- **Confirmed issue**: `scripts/validate_phase0.py::validate_adrs()`
  hardcoded the list `ADR-0001` through `ADR-0008`. A new ADR added to
  `docs/adr/` would never be discovered or validated unless a human also
  remembered to edit this Python list.
- **Remediation**: Replaced the hardcoded list with dynamic discovery:
  `discover_adrs()` globs `docs/adr/ADR-*.md`, excludes the template
  (`ADR-0000-template.md`) explicitly, and rejects anything matching
  `ADR-*.md` that isn't the strict `ADR-NNNN-slug.md` pattern as an
  invalid name (rather than silently ignoring it).
  `check_duplicate_adr_numbers()` fails if two files share the same
  4-digit identifier. `check_adr_index()` cross-checks `docs/adr/README.md`
  against the discovered set in both directions: a real ADR missing from
  the index fails, and an index entry pointing at a file that doesn't
  exist fails. Every discovered ADR is validated with the same
  `_validate_one_adr()` used before (metadata fields, required sections,
  valid `Status`, non-empty content, unapproved-decision marker for
  `Proposed`). To avoid maintaining two divergent ADR validators (the root
  cause of the *first* audit's P1-1 finding), added a
  `--validate-adr <file>` CLI mode to `scripts/validate_phase0.py` and
  rewrote `.agents/skills/create-adr/scripts/validate-adr.sh` to delegate
  to it (`exec python3 .../validate_phase0.py --validate-adr "$file"`)
  instead of reimplementing the rules in shell.
- **Files changed**: `scripts/validate_phase0.py` (`discover_adrs()`,
  `check_duplicate_adr_numbers()`, `check_adr_index()`, rewritten
  `validate_adrs()`, new `--validate-adr` CLI mode),
  `.agents/skills/create-adr/scripts/validate-adr.sh` (now a thin
  delegating wrapper), `.agents/skills/create-adr/SKILL.md`,
  `docs/adr/README.md`, `.github/workflows/phase0-validate.yml` (added a
  step running the dedicated validator against every discovered ADR).
- **Tests added**: `scripts/test_adr_validation.py` — 8 cases using
  temporary fixture directories (`tempfile.mkdtemp`, cleaned up after each
  test; the real `docs/adr/` is never modified except by one sanity check
  that only reads it): the template is excluded from discovery; a newly
  added, correctly structured ADR is discovered and validated
  automatically; an invalid future ADR (missing a required section) fails
  without any Python edit; two ADRs sharing one identifier are detected as
  duplicates; an ADR present on disk but absent from the index fails; an
  index entry referencing a non-existent file fails; a malformed file name
  is rejected; and a final sanity check that the real repository's actual
  ADRs still pass through the refactored dynamic path.
- **Commands executed**: `python3 scripts/test_adr_validation.py`;
  `python3 scripts/validate_phase0.py`;
  `.agents/skills/create-adr/scripts/validate-adr.sh` run against each of
  the 8 real ADRs individually, and in a loop mirroring the new CI step.
- **Results**: 8/8 regression cases pass. All 8 real ADRs pass both the
  full validator and the per-file delegated wrapper. The CI loop step
  (added to `.github/workflows/phase0-validate.yml`) was reproduced
  locally and exits 0.
- **Residual limitation**: None identified for this finding specifically.
- **Closure status**: Fixed.

### P2-03: Spec Kit operational status

- **Severity**: P2
- **Confirmed issue**: `.specify/README.md` stated "Status: **Operational**"
  without qualifying which environment that was verified in. A Codex
  re-audit reported it could not execute `specify --version` in its own
  environment, which — taken together with this repository's unqualified
  claim — looked like a contradiction rather than the expected, normal
  fact that CLI availability varies by environment.
- **Remediation**: Rewrote `.specify/README.md`'s status block into four
  explicitly separate lines — `Repository integration: Installed` (a
  repository fact, always true), `CLI availability: Operator-environment
  dependent` (never a repository fact), `Last verified CLI version: 0.8.15`
  (scoped to the one environment and date it was checked), `Constitution
  status: Proposed and unratified` (cross-referencing
  `.specify/memory/constitution.md`, unchanged) — with prose explicitly
  naming the Claude/Codex discrepancy as the reason these must stay
  separate. Built `scripts/check-bootstrap-prerequisites.sh`, which
  reports, per tool: found-or-missing, resolved executable path, version,
  expected version range, and install guidance if missing — treating
  `git`/`python3` as genuinely required (fails under
  `BOOTSTRAP_STRICT=true`) and `specify`/`claude`/`codex`/the local lint
  tools as operator-environment-dependent (always a warning, never a
  strict-mode failure, because Phase 0 CI does not itself install or
  depend on any of them). `.github/workflows/phase0-validate.yml` runs
  this script for visibility but its result never gates the build.
- **Files changed**: `.specify/README.md`, `scripts/check-bootstrap-prerequisites.sh`
  (new), `.github/workflows/phase0-validate.yml`, `scripts/verify-bootstrap.sh`
  (references the new script in its skip-guidance messages).
- **Commands executed and evidence recorded** (this remediation's own
  environment, 2026-08-02):

  ```text
  $ specify --version
  specify 0.8.15

  $ specify check
  ...
  Check Available Tools
  ├── ● Git version control (available)
  ├── ● Claude Code (available)
  ├── ● Codex CLI (available)
  ...
  Specify CLI is ready to use!
  Tip: Run 'specify self check' to verify you have the latest CLI version
  ```

  `specify check` is non-destructive — it only inspects which coding-agent
  CLIs are present on `PATH` and writes nothing.
  Also ran: `./scripts/check-bootstrap-prerequisites.sh` and
  `BOOTSTRAP_STRICT=true ./scripts/check-bootstrap-prerequisites.sh` —
  both exit 0 in this environment (git, python3, specify, claude, and
  codex are all present here; the local lint tools are reported `[ABSENT]`
  with install guidance and do not affect the exit code either way).
- **Results**: `specify` confirmed present and functional (v0.8.15) in
  this remediation's environment. Repository integration artifacts were
  never in question — they are static committed files, verifiable with or
  without the CLI. `check-bootstrap-prerequisites.sh` behaves correctly in
  both default and strict mode.
- **Residual limitation**: CLI availability is fundamentally
  environment-dependent and cannot be made a fixed repository fact — this
  is disclosed as the expected behavior, not treated as a defect to
  eliminate. Phase 0 CI does not install `specify` itself, so it cannot
  verify the CLI beyond what `check-bootstrap-prerequisites.sh` reports
  informationally.
- **Closure status**: Fixed (documentation and tooling now make the
  distinction the finding required; the underlying environment-dependency
  is inherent, not a defect to close).

### P2-04: Architecture wording

- **Severity**: P2
- **Confirmed issue**: `docs/architecture/architecture-principles.md` and
  `docs/architecture/data-principles.md` stated PostgreSQL as the target
  database as flat fact ("PostgreSQL is the target database unless an
  approved ADR changes the direction" / "PostgreSQL is the planned target
  database") while ADR-0004 remains `Proposed`, not `Accepted`.
- **Remediation**: `architecture-principles.md` principle 5 now reads "The
  intended target database is PostgreSQL, subject to Discovery and
  ADR-0004 approval," with an explicit cross-reference to ADR-0004's
  current `Proposed` status and a sentence stating this is an intended
  direction, not an accepted decision. `data-principles.md` now separates
  a confirmed current-state fact (the existing production database is
  MySQL) from the intended, not-yet-accepted migration direction
  ("MySQL-to-PostgreSQL migration is a proposed modernization direction,
  not yet an accepted architecture decision"), explicitly noting the
  business goal itself is not being weakened — only its acceptance status
  is being stated accurately.
  `docs/architecture/system-context.md` already carried the correct
  hedged wording from the first audit's remediation (P2-6) and required no
  further change; confirmed by re-reading it during this remediation.
- **Files changed**: `docs/architecture/architecture-principles.md`,
  `docs/architecture/data-principles.md`.
- **Commands executed**: `grep -rn -i "postgresql" docs/architecture/*.md`
  and a repository-wide grep excluding already-reviewed files, to confirm
  no other file carried the same overstatement (`docs/tools/tool-catalog.md`
  and `docs/tools/tool-decision-matrix.md` already correctly classify
  PostgreSQL as "Approve now, install later" / "Target system component,
  not bootstrap," not as accepted).
- **Results**: Both files now clearly separate confirmed fact, intended
  direction, and proposed-not-accepted decision. Markdownlint and the
  master validator both pass against the edited files.
- **Residual limitation**: None identified for this finding.
- **Closure status**: Fixed.

### P3 items implemented (only where they directly supported a required fix)

- The regression-test harness for Claude hook matching
  (`scripts/test_git_guard.py`) was implemented as an integral part of
  P1-01 — it is not separable from that fix.
- Lychee's step name and `docs/testing/test-strategy.md` both now state
  explicitly that it "checks repository-local links only... does not
  prove external URL reachability," since `lychee.toml` runs it `offline`.

No other P3 work, additional agents, application dependencies, or broader
architecture changes were introduced.

### Second-audit validation summary

```text
python3 scripts/validate_phase0.py           -> Phase 0 validation passed.
bash scripts/verify-bootstrap.sh             -> exit 0 (default mode; external
                                                 tools correctly skipped with
                                                 guidance, none installed here)
BOOTSTRAP_STRICT=true bash scripts/verify-bootstrap.sh
                                              -> exit 0 with all 6 pinned tool
                                                 binaries on PATH; exit 1 with
                                                 none present (verified both)
python3 scripts/test_git_guard.py            -> 63/63 passed
python3 scripts/test_adr_validation.py       -> 8/8 passed
.agents/skills/create-adr/scripts/validate-adr.sh
  run against all 8 real ADRs               -> all pass
markdownlint-cli2 "**/*.md"                  -> 0 issues (137 files linted)
yamllint -s .                                -> 0 issues
shellcheck scripts/*.sh .agents/skills/*/scripts/*.sh
                                              -> 0 issues
actionlint                                   -> 0 issues
gitleaks detect --no-git --source . --redact --exit-code 1
                                              -> no leaks found
lychee "**/*.md"                             -> 17/17 links OK (1 excluded:
                                                 vendor speckit skill files)
./scripts/check-bootstrap-prerequisites.sh   -> exit 0 (git, python3, specify,
                                                 claude, codex all present in
                                                 this environment)
forbidden product-implementation file scan   -> clean, no matches
```

### Remaining human actions after this second remediation

- Everything under `P1-4: Human approval and merge` in the first audit's
  section above — unchanged, still `Pending human GitHub approval and
  merge`.
- No new human actions were introduced by this remediation beyond that.

## Codex Change-Review Remediation (Third Audit)

This section responds to two Codex change reviews on 2026-08-04 —
`evidence/change-review-2026-08-04.md` (working-tree scope) and
`evidence/change-review-main-2026-08-04.md` (`main`-branch scope,
`26b1ffa..main`) — both independent of, and later than, the two audits
above. All 10 findings were independently re-verified against the actual
code/docs before any fix, not applied on the review's word alone; every
fix below has a regression test proving it, not just a manual check.

### P1-101: Secret-pattern deny rules covered Read/Edit but not Write/NotebookEdit

- **Severity**: P1
- **Confirmed issue**: `.claude/settings.json`'s `permissions.deny` had
  `Read(...)`/`Edit(...)` rules for every secret-shaped filename pattern
  (`*.key`, `id_rsa*`, `*secret*`, `*.p12`, `*.pfx`, `*.keystore`, `*.jks`,
  `*credentials*`) but no `Write(...)`/`NotebookEdit(...)` rules at all,
  even though the same file treats `Edit|Write|NotebookEdit` as distinct
  tool types elsewhere (`hooks.PostToolUse`'s matcher). `validate_claude_settings()`
  in `scripts/validate_phase0.py` only checked `Read`/`Edit` coverage, so
  CI reported the incomplete policy as valid. An agent could still create
  or overwrite a matching file via `Write` or `NotebookEdit`.
- **Remediation**: Added a `Write(...)`/`NotebookEdit(...)` deny rule
  mirroring every existing `Edit(...)` rule (generic patterns plus
  `.env`/`.gh-token`). Extended `validate_claude_settings()`'s coverage
  loop from `("Read", "Edit")` to `("Read", "Edit", "Write", "NotebookEdit")`
  for every generic secret-pattern fragment.
- **Files changed**: `.claude/settings.json`, `scripts/validate_phase0.py`,
  `scripts/test_validate_phase0.py`.
- **Tests added**: `test_settings_missing_write_side_of_a_pattern_fails`,
  `test_settings_missing_notebookedit_side_of_a_pattern_fails` — both
  construct a settings fixture missing exactly one tool's coverage for one
  pattern and confirm the validator names it.
- **Commands executed**: `python3 scripts/test_validate_phase0.py`.
- **Results**: 47/47 (was 41/41 before this remediation pass; +2 for this
  finding, +4 more for P2-102 below).
- **Residual limitation**: None identified for this finding.
- **Closure status**: Fixed.

### P1-102: `git_guard.py`'s obfuscation heuristic blocked unrelated commands

- **Severity**: P1
- **Confirmed issue**: The defense-in-depth regex for command-substitution-hidden
  git invocations ran across the *entire* raw command string, not just
  substitution regions. Reproduced exactly as the review described:
  `echo "git push"`, `printf 'git push\n'`, and
  `python3 -c "print('git push')"` were all denied even though none of
  them invoke git. This contradicted the guard's own stated goal that
  unrelated commands "must not be broken by it."
- **Remediation**: Added `_extract_command_substitutions()`, which
  extracts the inner text of every `$(...)` (depth-tracked, so nested
  substitutions don't truncate early) and backtick region in the raw
  command. The obfuscation regex now runs only against those extracted
  regions, not the full raw string. Updated the module docstring (step 7
  and "Residual limitations") to describe the corrected, scoped behavior
  and explicitly note the false-positive bug this fixes, so a future
  reader doesn't reintroduce it believing the broad scan was intentional.
- **Files changed**: `scripts/git_guard.py`, `scripts/test_git_guard.py`.
- **Tests added**: 4 false-positive regression cases (`echo "git push"`,
  `printf 'git push\n'`, `python3 -c "print('git push')"`, a sentence
  mentioning "git merge" in prose) plus 3 true-positive/edge cases
  proving the fix doesn't lose the protection it exists for:
  `$(git push origin main)` and a backtick equivalent still block, and
  nested `$(git log $(date))` with no dangerous subcommand inside still
  allows (proving depth-tracking works, not just single-level matching).
- **Commands executed**: `python3 scripts/test_git_guard.py`.
- **Results**: 80/80 (was 77/77 before this finding; +7 including P2-101's
  3 cases below, tracked together since they're the same test run).
- **Residual limitation**: Invoking git indirectly through another
  language runtime's subprocess call with no `$(...)`/backtick anywhere
  in the Bash command string (e.g. a Python `subprocess.run(['git',
  'push'])` with no shell substitution) is no longer caught by this
  heuristic. This is a deliberate, documented trade-off: that class of
  obfuscation was never reliably catchable by a Bash-string regex anyway
  (trivially defeated by base64/string-building, already documented as a
  residual limitation before this fix), while the false-positive class
  this fix removes was a real, everyday problem for ordinary commands.
- **Closure status**: Fixed.

### P1-103: Execution checklist's H1 completion criterion required something D-013 says is Deferred

- **Severity**: P1
- **Confirmed issue**: `docs/bootstrap/execution-checklist.md`'s H1 "Done
  when" list included "`main` is protected" as a completion requirement.
  D-013 (`docs/bootstrap/decision-log.md`, Accepted) explicitly records
  branch-protection enforcement on `hr-platform` as Deferred — not
  achievable under the current GitHub Free/private-repo plan, and the
  repository owner has decided not to change the plan or repo visibility
  to unblock it. The checklist's top-priority item required a condition
  the same repository's canonical decision record says is intentionally
  unavailable.
- **Remediation**: Rewrote H1's "Do" and "Done when" sections to carve out
  branch protection/rulesets on `hr-platform` specifically as Deferred per
  D-013, with a direct citation, rather than an unqualified completion
  requirement. Added a top-of-document note establishing that later,
  Accepted decision-log entries take precedence over this checklist where
  they conflict, so the same class of staleness is easier to recognize and
  correct next time instead of silently accumulating.
- **Files changed**: `docs/bootstrap/execution-checklist.md`.
- **Tests added**: None — documentation-only fix; verified by direct
  comparison against D-013's exact language in `decision-log.md` and by
  `markdownlint-cli2`.
- **Commands executed**: `markdownlint-cli2 docs/bootstrap/execution-checklist.md`.
- **Results**: 0 issues.
- **Residual limitation**: None identified for this finding.
- **Closure status**: Fixed.

### P2-101: `git -C <repo> commit` branch-protection check inspected the wrong repository

- **Severity**: P2
- **Confirmed issue**: `find_git_subcommand()` correctly walked past
  `-C <repo>` as a recognized global option, but discarded the actual
  path value. The `git commit` branch-protection rule called
  `branch_getter()` with no arguments, which (via `get_current_branch()`)
  always ran `git branch --show-current` in the hook process's own `cwd`
  — never the `-C`-targeted repository. Reproduced exactly as described:
  `git -C /tmp/repo commit -m test` with a `branch_getter` faking the
  hook's own branch as non-`main` was allowed, regardless of what branch
  `/tmp/repo` was actually on.
- **Remediation**: `find_git_subcommand()` now also resolves and returns
  the effective `-C`-chained target directory (`os.path.join`-accumulated
  across repeated `-C` flags, matching git's own `cd`-chaining semantics
  for free). `classify_git_invocation()` passes that directory to
  `branch_getter` as its one required argument (`None` when no `-C` was
  present, preserving prior default behavior exactly).
  `get_current_branch()` gained an optional `cwd` parameter forwarded to
  `subprocess.run`. Every existing test call site's zero-argument
  `branch_getter` lambdas were updated to accept the new argument.
- **Files changed**: `scripts/git_guard.py`, `scripts/test_git_guard.py`.
- **Tests added**: `run_c_flag_forwards_target_dir_case` (proves the
  resolved `-C` directory is what's actually passed to `branch_getter`),
  `run_c_flag_allows_non_main_target_case` (proves the check is genuinely
  per-directory, not a fixed answer), `run_no_c_flag_passes_none_case`
  (proves the no-`-C` case still passes `None`, preserving prior default
  behavior for the common case).
- **Commands executed**: `python3 scripts/test_git_guard.py`.
- **Results**: Included in P1-102's 80/80 total above (same test run).
- **Residual limitation**: None identified for this finding — the fix
  covers the exact reproduction case from the review plus the
  no-`-C` regression case.
- **Closure status**: Fixed.

### P2-102: Two "tracked-file" validators scanned the raw filesystem instead

- **Severity**: P2
- **Confirmed issue**: `validate_codeowners_component_coverage()`'s
  docstring says it activates "once a component directory contains any
  other **tracked** file," but the implementation used
  `component_dir.rglob("*")` — the raw filesystem, including untracked
  scratch files. `validate_dependabot_ecosystem_coverage()` had the same
  gap via `root.rglob(manifest_name)`. Both could fail locally on content
  that was never part of the actual repository under review.
- **Remediation**: Added `_git_tracked_files(root)`, a shared helper that
  runs `git ls-files -z` once and returns the tracked-file set as absolute
  paths, or `None` if `root` isn't a real git repository (git unavailable,
  or a synthetic test fixture) — callers treat `None` as "no filtering
  possible" and fall back to the prior raw-filesystem behavior, which is
  what keeps every existing plain-tempdir test fixture passing unchanged.
  Both validators now filter their candidate file lists through this
  helper when it returns a real set.
- **Files changed**: `scripts/validate_phase0.py`, `scripts/test_validate_phase0.py`.
- **Tests added**: `test_untracked_file_in_component_dir_does_not_trigger_check`
  / `test_tracked_file_in_component_dir_still_triggers_check` and the
  dependabot-side equivalents `test_untracked_manifest_does_not_trigger_check`
  / `test_tracked_manifest_still_triggers_check` — each pair uses a real
  `git init`-ed fixture (not a plain tempdir) with one file `git add`-ed
  and one left untracked, proving the filter is genuine (both an
  untracked-passes and a tracked-still-fails case), not an accidental
  full disable.
- **Commands executed**: `python3 scripts/test_validate_phase0.py`.
- **Results**: Included in P1-101's 47/47 total above (same test run; +4
  for this finding specifically).
- **Residual limitation**: None identified for this finding.
- **Closure status**: Fixed.

### P2-103: `verify-bootstrap.sh` summary tests hard-reset `PATH` to a fixed directory list

- **Severity**: P2
- **Confirmed issue**: `run_verify_bootstrap_with_shims()` built an
  isolated `PATH` as `{shim_dir}:{SYSTEM_PATH_DIRS}`, where
  `SYSTEM_PATH_DIRS` was a hardcoded standard-location list
  (`/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin`). In the
  Codex review environment, `git` resolves to
  `/snap/codex/34/usr/bin/git`, outside that list — so the nested
  `test_git_guard.py` regression suite (which this same test-running chain
  invokes) called a `git` that didn't exist on the constrained `PATH`,
  `get_current_branch()` returned `None`, and the subprocess exited before
  ever reaching the `SUMMARY:` line this test exists to check.
- **Remediation**: Replaced the fixed list with
  `_essential_runtime_path_dirs()`, which resolves the actual directories
  containing `git`/`bash`/`sh`/`python3` via `shutil.which()` against the
  real inherited `PATH` (with `SYSTEM_PATH_DIRS` appended as a fallback
  safety net, not removed). This targets only the specific directories
  essential runtimes are actually in — it does not reintroduce the
  original leaked-real-lint-tool bug this test's own docstring describes,
  since `PATH` resolution is per exact command name: adding `git`'s
  resolved directory only risks a collision if a binary in that same
  directory happens to be named e.g. `shellcheck`, a much narrower
  coincidence than "somewhere on the whole inherited PATH."
- **Files changed**: `scripts/test_validate_phase0.py`.
- **Tests added**: `test_essential_runtime_path_dirs_finds_real_git`
  (confirms git's real resolved directory is actually included, proving
  genuine dynamic resolution rather than reliance on the fallback list)
  and `test_essential_runtime_path_dirs_finds_git_in_nonstandard_location`
  (directly simulates the Codex scenario: a fake `git` shim as the *only*
  `PATH` entry, confirming it's still found).
- **Commands executed**: `python3 scripts/test_validate_phase0.py`;
  `python3 scripts/validate_phase0.py`; `bash scripts/verify-bootstrap.sh`.
- **Results**: Included in P1-101's 47/47 total above (same test run; +2
  for this finding). Full `validate_phase0.py` and `verify-bootstrap.sh`
  runs both still pass end-to-end.
- **Residual limitation**: This environment's `git` already resolves to a
  standard location (`/usr/bin/git`), so the exact Codex failure mode
  (`/snap/codex/34/usr/bin/git`) could not be reproduced verbatim here —
  the nonstandard-location regression test simulates the same class of
  failure (git resolvable only outside `SYSTEM_PATH_DIRS`) rather than
  that literal path. A human or agent with access to the original Codex
  environment should re-run `python3 scripts/test_validate_phase0.py`
  there to confirm the literal reported failure is gone.
- **Closure status**: Fixed, pending confirmation in the original
  environment per the residual limitation above.

### P2-104: Execution checklist sequenced Discovery behind H2, contradicting D-015

- **Severity**: P2
- **Confirmed issue**: `docs/bootstrap/execution-checklist.md`'s
  "Suggested Execution Sequence" step 2 said to "resolve H2 before opening
  substantial discovery work." D-015 (`docs/bootstrap/decision-log.md`,
  Accepted) explicitly authorized Discovery (A1) to begin while H2 remains
  unresolved, listing H2 as "explicitly outside the Phase 0 completion
  gate" — a Discovery input, not a Discovery blocker. `main` contained two
  directly conflicting instructions about the same question.
- **Remediation**: Rewrote the sequence section to state D-015's actual
  authorization directly, cite it, and note that A1 Discovery work has in
  fact already substantially proceeded without H2 being resolved first
  (linking the real evidence documents this produced). Reordered H2 to
  run in parallel with Discovery rather than before it.
- **Files changed**: `docs/bootstrap/execution-checklist.md`.
- **Tests added**: None — documentation-only fix; verified against D-015's
  exact language and by `markdownlint-cli2`.
- **Commands executed**: `markdownlint-cli2 docs/bootstrap/execution-checklist.md`.
- **Results**: 0 issues (same run as P1-103, one file).
- **Residual limitation**: None identified for this finding.
- **Closure status**: Fixed.

### P2-105: R-008 still asserted the human-merge workflow had never run, contradicting D-014

- **Severity**: P2
- **Confirmed issue**: `docs/bootstrap/risk-register.md` R-008's
  `Description` and `Evidence` fields still said the human-approval
  workflow "has never actually run" with "zero merges" and "no merge
  commits exist." D-014 (`docs/bootstrap/decision-log.md`, Accepted)
  records pull request #1, merge commit `cf997818fbabb6f02f9b15c845da06757713a97a`,
  merged by the repository owner on 2026-08-03. R-008's own `Status`
  field had already been correctly updated to reflect this — only
  `Description` and `Evidence` still asserted the pre-D-014 state,
  contradicting the same row's own `Status` field.
- **Remediation**: Rewrote `Description` to state the workflow has run
  once (citing D-014 and the PR), while preserving the legitimate residual
  risk this row tracks (a single evidenced run doesn't mechanically
  guarantee every future merge follows the same process, since
  branch-protection enforcement remains Deferred under D-013). Rewrote
  `Evidence` to include the PR/merge-commit/merger record alongside the
  original pre-PR-#1 observation, explicitly labeled as a historical
  baseline rather than the current state.
- **Files changed**: `docs/bootstrap/risk-register.md`.
- **Tests added**: None — documentation-only fix; verified against D-014's
  exact language and by `markdownlint-cli2`.
- **Commands executed**: `markdownlint-cli2 docs/bootstrap/risk-register.md`.
- **Results**: 0 issues.
- **Residual limitation**: None identified for this finding.
- **Closure status**: Fixed.

### P3 items implemented (third audit)

- **A2 stale stub claim**: `docs/bootstrap/execution-checklist.md`'s A2
  said to "replace the stub structure in `release-readiness.md`," but that
  file (138 lines: release gate, minimum gate expectations, cross-references
  to the rest of the operations document set) is no longer a stub. Marked
  A2 "Status: Done" with the original requirements preserved for
  historical traceability, rather than describing completed work as
  pending.
- **`test-layer-activation.md` inconsistent dependency reference**: the
  DAST row's "Depends On" column used a bare filename
  (`environment-and-deployment-strategy.md`) where every adjacent
  reference in the same document uses either an ADR number or a full
  repository path. Changed to the full path
  (`docs/operations/environment-and-deployment-strategy.md`) for
  consistency with the rest of the table.

No other P3 work, additional agents, application dependencies, or broader
architecture changes were introduced.

### Third-audit validation summary

```text
python3 scripts/validate_phase0.py           -> Phase 0 validation passed.
python3 scripts/test_git_guard.py            -> 80/80 passed (was 77/77)
python3 scripts/test_validate_phase0.py      -> 47/47 passed (was 41/41)
bash scripts/verify-bootstrap.sh             -> exit 0 (default mode; all 6
                                                 external tools correctly
                                                 skipped with guidance, none
                                                 installed in this environment)
markdownlint-cli2 (all files changed in this
  remediation pass)                          -> 0 issues
```

### Remaining human actions after this third remediation

- Everything under `P1-4: Human approval and merge` in the first audit's
  section — unchanged, still `Pending human GitHub approval and merge`
  for whatever branch carries this remediation.
- Re-run `python3 scripts/test_validate_phase0.py` in the original Codex
  environment (where `git` is at `/snap/codex/34/usr/bin/git`) to confirm
  P2-103's fix resolves the literal reported failure, per that finding's
  residual limitation.
- No other new human actions were introduced by this remediation.

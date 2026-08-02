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

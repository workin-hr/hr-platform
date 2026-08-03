# Decision Log

Each decision is tracked with: Decision ID, Date, Decision, Status, Owner,
Related ADR, Reason, Impact, Follow-up, Evidence. Status is always one of
`Proposed`, `Accepted`, `Rejected`, `Superseded`, `Deferred` — matching the
vocabulary used for ADRs. A decision that restates an ADR's direction
carries the same status as that ADR; it is not allowed to claim more
certainty than the ADR it depends on.

## D-001: Repository Source Of Truth

| Field | Value |
|---|---|
| Decision | Repository files are authoritative. Chat history is not. |
| Status | Accepted |
| Owner | Human requester, via the original Phase 0 bootstrap instructions |
| Related ADR | None — this is a Phase 0 process rule, not a target-system architecture decision |
| Reason | Bootstrap work must be independently auditable from the repository alone, without relying on unrecorded conversation context |
| Impact | Every agent definition and skill treats repository files as the evidence source; see `.specify/memory/constitution.md` Principle I |
| Follow-up | None |
| Evidence | Consistently reflected in `README.md`, `AGENTS.md`, `CLAUDE.md`, `.specify/memory/constitution.md` since the initial bootstrap commit |

## D-002: Phase 0 Scope

| Field | Value |
|---|---|
| Decision | Phase 0 is limited to bootstrap governance, documentation, agent model, skills, validation, and empty future-component boundaries. |
| Status | Accepted |
| Owner | Human requester, via the original Phase 0 bootstrap instructions |
| Related ADR | None |
| Reason | Directly instructed scope: no product implementation until Discovery and approved architecture decisions exist |
| Impact | Enforced mechanically by `scripts/validate_phase0.py`'s forbidden-file checks; confirmed clean by two independent audits (original and this remediation) |
| Follow-up | None |
| Evidence | `docs/bootstrap/audit-remediation.md` scope-compliance section; `scripts/validate_phase0.py` `FORBIDDEN_*` constants |

## D-003: Architecture Starting Position

| Field | Value |
|---|---|
| Decision | The initial planning baseline is a modular monolith unless Discovery and an approved ADR demonstrate a better option. |
| Status | Proposed |
| Owner | Solution Architect (analysis); human engineering lead (approval) |
| Related ADR | ADR-0002 (Modular Monolith Baseline) |
| Reason | Matches ADR-0002's own status; this decision must not be read as more settled than the ADR it restates |
| Impact | Guides `docs/architecture/module-boundaries.md`; does not yet justify any implementation |
| Follow-up | Revisit when ADR-0002 has real Discovery evidence and moves toward Accepted |
| Evidence | `docs/adr/ADR-0002-modular-monolith-baseline.md` |

## D-004: Flutter Repository Boundary

| Field | Value |
|---|---|
| Decision | Flutter remains outside `hr-platform` during Phase 0. |
| Status | Proposed |
| Owner | Solution Architect (analysis); human engineering lead (approval) |
| Related ADR | ADR-0001 (Repository Strategy) |
| Reason | Matches ADR-0001's own status; repository boundaries are a candidate direction pending Discovery, not a settled decision |
| Impact | No Flutter source is present in this repository; `flutter-integration/` remains a boundary README only |
| Follow-up | Revisit when ADR-0001 has real Discovery evidence and moves toward Accepted |
| Evidence | `docs/adr/ADR-0001-repository-strategy.md`; `flutter-integration/README.md` |

## D-005: Legacy Repository Boundary

| Field | Value |
|---|---|
| Decision | Legacy PHP remains a separate repository and is treated as Discovery input, not as code to relocate into this repository during Phase 0. |
| Status | Proposed |
| Owner | Solution Architect (analysis); human engineering lead (approval) |
| Related ADR | ADR-0001 (Repository Strategy) |
| Reason | Matches ADR-0001's own status |
| Impact | No legacy PHP source is present in this repository; `docs/legacy/` holds discovery templates only |
| Follow-up | Revisit when ADR-0001 has real Discovery evidence and moves toward Accepted |
| Evidence | `docs/adr/ADR-0001-repository-strategy.md` |

## D-006: Install Real Spec Kit Tooling

| Field | Value |
|---|---|
| Decision | Run the official `specify-cli` against this repository (`specify init --here --integration claude --no-git --force`, then `specify integration install codex --force`) rather than continuing to treat `.specify/` as a hand-written placeholder. |
| Status | Accepted |
| Owner | Codex Bootstrap Engineer (executed); human requester (authorized via this remediation's explicit instructions) |
| Related ADR | None — this is bootstrap tooling, not target-system architecture |
| Reason | The prior `.specify/` had no real templates behind the named Constitution -> Specify -> ... workflow; a prior independent audit (P1-3) required either a genuine installation or an honest "not operational" label. `specify-cli` 0.8.15 was confirmed installed and its file-merge behavior was verified safe in an isolated copy before running it against this repository. |
| Impact | `.specify/memory/constitution.md`, `.specify/templates/`, `.specify/scripts/bash/`, `.specify/workflows/`, and `speckit-*` skills under `.claude/skills/` and `.agents/skills/` now exist and are real. The prior `.specify/constitution.md` was migrated to the canonical location and removed to avoid duplicate sources of truth. No `/speckit-*` workflow command was invoked to generate product content. |
| Follow-up | The constitution's principles are marked Proposed pending formal human ratification (see `.specify/memory/constitution.md` Governance section) |
| Evidence | `docs/bootstrap/audit-remediation.md` (P1-3); `.specify/README.md` |

## D-007: Unify ADR Format

| Field | Value |
|---|---|
| Decision | Retire the `## Proposed Direction` heading used by all 8 real ADRs in favor of a single authoritative format: a `## Metadata` table plus `## Context`, `## Decision`, `## Alternatives Considered`, `## Consequences`, `## Risks`, `## Validation Evidence`, `## Open Questions`, with Proposed decisions carrying an explicit "not yet approved" marker. |
| Status | Accepted |
| Owner | Codex Bootstrap Engineer |
| Related ADR | None — this is a documentation-format decision |
| Reason | A prior independent audit (P1-1) found the dedicated ADR validator (`.agents/skills/create-adr/scripts/validate-adr.sh`) failed on all 8 real ADRs because they used `## Proposed Direction` while the template and validator required `## Decision`; the master validator never checked this, so CI's "passed" result was misleading |
| Impact | All 8 ADRs, `docs/adr/ADR-0000-template.md` (renamed from `0000-template.md`), `docs/adr/README.md`, `.agents/skills/create-adr/SKILL.md`, and `.agents/skills/create-adr/scripts/validate-adr.sh` were rewritten; `scripts/validate_phase0.py::validate_adrs()` now performs full structural validation and is wired into the master validator |
| Follow-up | None open |
| Evidence | `docs/bootstrap/audit-remediation.md` (P1-1) |

## D-008: Technically Enforce Claude Agent Tool Scope; Treat Codex Enforcement As Procedural

| Field | Value |
|---|---|
| Decision | Add real YAML frontmatter (`tools: Read, Grep, Glob, Bash`) to all six `.claude/agents/*.md` files so Claude Code's subagent system technically restricts them, and add `.claude/settings.json` permission-deny rules plus a `PreToolUse` hook blocking destructive Git operations repository-wide. For Codex, remove the fabricated keys from `.codex/config.toml` (Codex does not read a project-local config file) and document the real, operator-applied `sandbox_mode`/`approval_policy` settings instead. |
| Status | Accepted |
| Owner | Codex Bootstrap Engineer |
| Related ADR | None |
| Reason | A prior independent audit (P1-2) found `.claude/agents/*.md` had no frontmatter at all — they were inert prose, not real subagent definitions — and `.codex/config.toml`'s `forbid_self_merge`/`forbid_self_approval`/`allowed_roots` keys were never valid Codex settings and were never enforced by anything |
| Impact | All 6 Claude agents are now genuinely tool-scoped when invoked via the Task/Agent tool; `.claude/settings.json` is validated by `scripts/validate_phase0.py::validate_claude_settings()`; `.codex/config.toml` is now honest reference documentation, not fake enforcement |
| Follow-up | Codex-side enforcement remains dependent on the human operator applying the documented `--sandbox`/`--ask-for-approval` flags — this cannot be closed from repository files alone (see R-006 in `docs/bootstrap/risk-register.md`) |
| Evidence | `docs/bootstrap/audit-remediation.md` (P1-2) |

## D-009: CI/CD & Observability Domain Ownership

| Field | Value |
|---|---|
| Decision | The repository owner is the accountable human for CI/CD pipeline design and observability baseline decisions (ADR-0007, ADR-0008, and any future CI/CD-domain ADRs). No new Claude agent is added for this domain in Phase 0; Solution Architect's and Test Architect's existing documented remits already cover the read-only analysis work (architecture/ADR analysis and testing-strategy/quality-gate analysis respectively) and now say so explicitly. |
| Status | Accepted |
| Owner | Repository owner (human requester) |
| Related ADR | ADR-0007, ADR-0008 |
| Reason | The Engineering Enablement Plan's AG-3 item found that no agent's stated remit named CI/CD pipeline design or observability baseline ownership explicitly, even though ADR-0007's Owner (Test Architect) and ADR-0008's Owners (Solution Architect, Test Architect) already implied it. The repository owner confirmed direct accountability for this domain rather than delegating it to a new agent; adding a dedicated agent before any real CI/CD pipeline exists beyond `phase0-validate.yml` would be scope ahead of need. |
| Impact | `.claude/agents/solution-architect.md` and `.claude/agents/test-architect.md` Purpose sections now explicitly name observability baseline design and CI/CD pipeline/quality-gate design, respectively, as in scope. `docs/agents/responsibility-matrix.md` is unchanged — no new row, no change to either agent's tool scope or approval authority. |
| Follow-up | None open |
| Evidence | `.claude/agents/solution-architect.md`, `.claude/agents/test-architect.md`, this entry |

## D-010: Do Not Duplicate Repository-Authored Skills Under `.claude/skills/`

| Field | Value |
|---|---|
| Decision | The 14 repository-authored skills under `.agents/skills/` are not also copied into `.claude/skills/` for direct `/name` invocation, unlike the 9 vendor `speckit-*` skills (which `specify-cli` already installs as near-duplicate copies in both locations). |
| Status | Accepted |
| Owner | Repository owner (human requester), via direct instruction during this engineering-enablement session |
| Related ADR | None — this is bootstrap tooling, not target-system architecture |
| Reason | SK-2 in the Engineering Enablement Plan raised this as an open option. Building it would mean maintaining two copies of every skill body with no automated content-parity check — a real drift risk, and `validate_skill_files` would need a new carve-out before a thinner, non-duplicating pointer file could pass structural validation. Phase 0 has no day-to-day skill-invocation workload yet to justify that cost. |
| Impact | `docs/agents/skill-catalog.md` records the reasoning explicitly so this reads as a considered decision, not a gap; `.claude/skills/` continues to hold only the 9 `speckit-*` skills. |
| Follow-up | Revisit if direct `/name` invocation of these skills inside Claude Code sessions becomes valuable enough to justify building and validating a non-duplicating pointer mechanism. |
| Evidence | `docs/agents/skill-catalog.md` ("Why The 14 Repository-Authored Skills Are Not Also Under `.claude/skills/`"), this entry |

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
| Follow-up | Revisit if CI/CD or observability tooling grows beyond `phase0-validate.yml` and `nightly.yml` (e.g. a third workflow or a dedicated pipeline-as-code component) to the point read-only analysis is no longer sufficient, or once ADR-0007/ADR-0008 move to Accepted and assign a formal Decider for this domain. |
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

## D-011: Draft Structured-Logging Field Contract Ahead Of Implementation

| Field | Value |
|---|---|
| Decision | Publish `docs/operations/logging-conventions.md` as a Proposed structured-logging field contract (required fields, explicit exclusions, format) now, ahead of any backend/gateway implementation, so the first real code has a spec to conform to. |
| Status | Proposed — this document is not Accepted; formal approval routes through ADR-0008 (Observability Baseline), which is itself still Proposed |
| Owner | Repository owner, per D-009's CI/CD & observability ownership decision |
| Related ADR | ADR-0008 |
| Reason | OB-2 in the Engineering Enablement Plan called for this; explicitly did not invent a specific logging library, framework, or CI enforcement mechanism, since backend/edge-gateway have no real source files yet and doing so would encode a product-domain assumption ahead of Discovery evidence (see the document's own Open Questions). |
| Impact | `docs/operations/logging-conventions.md` is a new document; `docs/operations/README.md`'s template list now references it. No code, CI check, or ADR content was changed. |
| Follow-up | Move to Accepted only once ADR-0008 itself has real Discovery evidence and a Decider reviews the field list; a CI check enforcing "uses the shared logging wrapper" should only be added once a backend language/framework direction is itself Accepted, not guessed at now. |
| Evidence | `docs/operations/logging-conventions.md`, this entry |

## D-012: Publish Test-Layer Activation Triggers

| Field | Value |
|---|---|
| Decision | Publish `docs/testing/test-layer-activation.md`, mapping all 28 entries in `docs/testing/test-strategy.md`'s Planned Test Layers list to a concrete, structural activation trigger, so each has a defined path from "planned" to "running" instead of staying aspirational indefinitely. |
| Status | Proposed — a planning document, not an enforcement mechanism; no trigger is wired into `scripts/validate_phase0.py` unless already noted as active (secrets scanning, static analysis of this repository's own scripts, and the GH-3 dependency-scanning gate) |
| Owner | Repository owner, per D-009's CI/CD & observability ownership decision |
| Related ADR | ADR-0007 |
| Reason | TS-3 in the Engineering Enablement Plan called for this. Triggers are phrased structurally (file/directory existence, document evidence state) rather than naming specific tools or frameworks not yet chosen by an Accepted ADR, to avoid encoding product-domain assumptions ahead of Discovery. |
| Impact | New document only; no code or CI behavior changed. |
| Follow-up | Decide, per trigger, whether it should become a real dormant `scripts/validate_phase0.py` check (mirroring GH-2/GH-3) or stay a human review-checklist item — left as this document's own Open Question rather than decided unilaterally here. Also revisit the "differential PHP-versus-Java" row specifically once a backend-language ADR exists (Accepted or newly Proposed) that can be cited in place of "None." |
| Evidence | `docs/testing/test-layer-activation.md`, this entry |

## D-013: Defer H1 Branch-Protection Enforcement (GitHub Free Plan Limitation)

| Field | Value |
|---|---|
| Decision | H1 branch-protection enforcement on `main` is explicitly Deferred, not Completed. The `workin-hr` organization will not be upgraded from GitHub Free, and `hr-platform` will not be made public, in order to unblock it. |
| Status | Accepted |
| Owner | Repository owner (human requester) |
| Related ADR | None — this is bootstrap/GitHub-governance tooling, not target-system architecture |
| Reason | `workin-hr` is a GitHub Free organization and `hr-platform` is private. Checked directly against the live GitHub API during the pre-merge integrity review, not assumed: both the classic branch-protection endpoint (`GET /repos/workin-hr/hr-platform/branches/main/protection`) and the modern Rulesets endpoint (`GET /repos/workin-hr/hr-platform/rulesets`) return `403 Upgrade to GitHub Pro or make this repository public`. The repository owner has explicitly decided neither an organization plan upgrade nor making the repository public is in scope. |
| Impact | `scripts/check-branch-protection.sh` (GH-1) remains built and regression-tested but pending — it cannot be run against the real organization under this constraint, and there is no target date. `docs/agents/operating-model.md`'s enforcement-layer 4 is downgraded from "GitHub-enforced, pending manual setup" to explicitly Deferred. `docs/bootstrap/risk-register.md` R-008 is updated: it remains partially open specifically because review and merge governance cannot be mechanically enforced — no platform-level required-reviewer count, no required status check, no protection against force-push or direct push to `main`. Temporary mitigation until this is revisited: manual PR review before every merge, a green required CI run before every merge, restricted `main` write access limited to trusted human owners, and no direct pushes to `main` by team convention (not platform-enforced). |
| Follow-up | Revisit only if the organization's plan changes for reasons unrelated to this decision, or if GitHub changes free-plan branch-protection availability. Until then, treat this as closed, not merely postponed. |
| Evidence | Live `gh api repos/workin-hr/hr-platform/branches/main/protection` and `gh api repos/workin-hr/hr-platform/rulesets` responses (both `403`, checked during the pre-merge integrity review); this entry. |

## D-014: Record First Human-Approved Merge Into `main`

| Field | Value |
|---|---|
| Decision | Record completion of steps 2–10 of the Human Approval And Merge Sequence (`docs/bootstrap/manual-setup-checklist.md`) for the first pull request into `main`, per that section's step 9. |
| Status | Accepted |
| Owner | Repository owner (human requester) |
| Related ADR | None — this is a bootstrap process-evidence record, not a target-system architecture decision |
| Reason | The branch was pushed, pull request #1 was opened, the required `validate` check passed on every push to the branch, and the repository owner merged the pull request directly. No formal GitHub pull-request review (`Approve`) was recorded before merge (`reviewDecision` was empty via the GitHub API) — consistent with D-013's temporary mitigation being direct trusted-owner merge authority rather than a platform-enforced required review, not a process violation. Step 1 of the sequence (branch protection) remains explicitly Deferred per D-013 and is not part of this record. |
| Impact | Pull request: `https://github.com/workin-hr/hr-platform/pull/1`. Merging human: Karim Taha (GitHub `karimtismail`). Merge commit: `cf997818fbabb6f02f9b15c845da06757713a97a` on `main` — a real merge commit (not squashed), confirmed as a clean ancestor of `main` with no history rewrite. Merged at 2026-08-03T12:10:18Z. Post-merge validation (sequence step 10) run against the real `main` in an isolated git worktree: `python3 scripts/validate_phase0.py` passed; `bash scripts/verify-bootstrap.sh` passed. CI on `main` itself (push trigger, workflow "Phase 0 Bootstrap Validate", run `30812471911`) also passed independently. |
| Follow-up | None open for this record. The overall Human Approval And Merge Sequence classification in `docs/bootstrap/manual-setup-checklist.md` still cannot be marked fully "Complete" in the sequence's original sense, since step 1 (branch protection) is permanently Deferred under the accepted GitHub Free plan limitation (D-013), not merely postponed. A human should decide how to classify "9 of 10 steps evidenced, 1 step permanently out of scope" going forward. |
| Evidence | `https://github.com/workin-hr/hr-platform/pull/1`; merge commit `cf997818fbabb6f02f9b15c845da06757713a97a`; CI run `https://github.com/workin-hr/hr-platform/actions/runs/30812471911`; this entry. |

## D-015: Declare Phase 0 Complete and Authorize Discovery

| Field | Value |
|---|---|
| Decision | Phase 0 is complete against all 18 criteria in `docs/bootstrap/definition-of-done.md`, and evidence-driven Discovery may begin. |
| Status | Accepted |
| Owner | Repository owner (human requester) |
| Related ADR | None — this is a Phase 0 process decision, not a target-system architecture decision |
| Reason | All 18 Definition of Done criteria have been evaluated against the current state of `main`, not assumed. Phase 0 CI is green on `main` (workflow "Phase 0 Bootstrap Validate", run `https://github.com/workin-hr/hr-platform/actions/runs/30814048525`). No product implementation exists and the prohibition remains mechanically enforced (`FORBIDDEN_FILE_NAMES`/`FORBIDDEN_SUFFIXES` in `scripts/validate_phase0.py`). Pull request #1 and D-014 evidence that the manual review-and-merge sequence has run at least once. GitHub branch-protection enforcement remains Deferred under D-013 — an accepted plan limitation, not an unresolved gap. R-008 remains Open only as an accepted, non-blocking residual risk for missing mechanical enforcement of review and merge governance; it no longer blocks Phase 0 completion, since the specific condition its own Contingency field required — the sequence "followed once and evidenced" — is now satisfied by D-014. DoD criterion 3, "GitHub governance instructions are complete," is interpreted as requiring complete and accurate governance instructions plus explicit recording of unavailable enforcement (D-013) — not requiring paid GitHub enforcement to actually be enabled. H2 open questions (`docs/bootstrap/open-questions.md`), all 8 Proposed ADRs, and missing Discovery evidence (the in-flight, still-uncommitted A1 scaffolding) are explicitly outside the Phase 0 completion gate — they become Discovery inputs or future decision gates, not Phase 0 blockers. |
| Impact | Discovery (A1 in `docs/bootstrap/execution-checklist.md` and equivalent work) may now begin. `docs/bootstrap/risk-register.md` R-008's Status is updated to "Open — Accepted Residual Risk / Non-blocking," and its stale reference to a "Pending human acceptance gate" phrase no longer present in `docs/bootstrap/manual-setup-checklist.md` is corrected. No ADR status changed. No H2 open question resolved. No product code or Discovery evidence added by this entry. |
| Follow-up | Before any individual Discovery entry (e.g. a specific legacy-behavior or schema finding) is treated as authoritative, it still needs its own citation to source evidence per the existing per-document conventions (e.g. `docs/legacy/production-behavior-evidence.md`'s Confidence field, `docs/migration/database-schema-inventory.md`'s Source Of Evidence fields). This decision authorizes Discovery to begin; it does not pre-approve any specific Discovery finding. |
| Evidence | `docs/bootstrap/definition-of-done.md`; CI run `https://github.com/workin-hr/hr-platform/actions/runs/30814048525`; pull request `https://github.com/workin-hr/hr-platform/pull/1` and D-014; D-013; R-008 (`docs/bootstrap/risk-register.md`); this entry. |

## D-016: Accept ADR-0002 Part A (Modular Monolith Strategic Direction)

| Field | Value |
|---|---|
| Decision | The system's baseline backend architecture is a modular monolith — a single deployable unit with explicit internal module boundaries, not microservices from day one and not an undifferentiated layered monolith. |
| Status | Accepted |
| Owner | Repository owner (human requester) |
| Related ADR | ADR-0002 (Modular Monolith Baseline) — **Part A only**. Part B (the tenant-isolation implementation detail — PostgreSQL Row-Level Security vs. a repository-guard pattern) is explicitly **not** decided by this entry; it remains open pending the H2 spike (`docs/migration/technical-spike-plan.md`). |
| Reason | ADR-0002's strategic direction was recommended for immediate acceptance in this session's 2026-08-04 classification pass — it does not depend on the technical spike, and a first-pass module boundary diagram already exists (`docs/architecture/module-boundaries.md`), informed by real Discovery (the legacy module inventory and the capability/ownership matrix). The repository owner accepted directly. |
| Impact | `docs/adr/ADR-0002-modular-monolith-baseline.md`'s `Status` field moves to `Accepted`, with the Decision section's Part A marked accepted and Part B explicitly still `Proposed`/pending. Module implementation work may proceed against the 9-module candidate boundary diagram; nothing in Part B (which pattern enforces tenant isolation) is authorized to be assumed yet. |
| Follow-up | Part B moves to its own acceptance once the H2 spike (`docs/migration/technical-spike-plan.md`) reports a recommendation. |
| Evidence | `docs/adr/ADR-0002-modular-monolith-baseline.md`; `docs/architecture/module-boundaries.md`; direct repository-owner acceptance, this conversation, 2026-08-04. |

## D-017: Accept ADR-0005 (Authentication Direction)

| Field | Value |
|---|---|
| Decision | The new system's authentication direction is: self-managed JWT authentication for the MVP (no external identity provider); short-lived access tokens; rotating, server-side-tracked refresh tokens with revocation; secure client storage via `flutter_secure_storage`; forced re-authentication for all existing users at cutover (no dual-validation); no Keycloak or other external IdP for the MVP. |
| Status | Accepted |
| Owner | Repository owner (human requester) |
| Related ADR | ADR-0005 (Authentication Direction — renamed from "Authentication And Authorization Direction" on 2026-08-04; the authorization half of the original scope is tracked separately and remains open in `docs/adr/ADR-0010-authorization-model.md`, not covered by this acceptance) |
| Reason | ADR-0005's Decision section was corrected on 2026-08-04 to state the real approved direction (previously it held only a Discovery-stage placeholder). With the real direction stated and evidenced (`docs/security/authentication-remediation-design.md`), and exact token lifetimes/multi-session policy explicitly carved out as non-blocking open refinements, the repository owner accepted directly. |
| Impact | `docs/adr/ADR-0005-authentication-direction.md`'s `Status` field moves to `Accepted`. The auth module may be implemented against this direction. Exact access/refresh-token lifetimes and multi-session policy remain open — implementation should use reasonable placeholder values pending that refinement, not block on it. |
| Follow-up | Resolve exact token lifetimes and multi-session policy (both explicitly non-blocking open refinements per the ADR's Decision section) before or during auth-module implementation. |
| Evidence | `docs/adr/ADR-0005-authentication-direction.md`; `docs/security/authentication-remediation-design.md`; direct repository-owner acceptance, this conversation, 2026-08-04. |

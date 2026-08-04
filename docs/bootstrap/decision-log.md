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

## D-018: Accept ADR-0002 Part B (RLS As The Tenant-Isolation Mechanism)

| Field | Value |
|---|---|
| Decision | The new system uses PostgreSQL Row-Level Security (RLS) as the primary structural mechanism for tenant isolation, on the explicit, non-optional condition that (1) the application's runtime database role is never a superuser — ideally enforced by a startup-time check, (2) repository-layer scoping is still applied where practical as a secondary defense-in-depth layer, and (3) a test proving RLS's fail-closed behavior when the session-variable-setting call is omitted is added before the pattern is trusted in real implementation. |
| Status | Accepted |
| Owner | Repository owner (human requester) |
| Related ADR | ADR-0002 (Modular Monolith Baseline) — **Part B**. Completes the ADR's acceptance; Part A was already accepted under D-016. |
| Reason | The H2 spike (`docs/migration/technical-spike-plan.md`) was executed for real, not just planned, per direct instruction on 2026-08-05: a working Spring Boot 4.1/Java 25 vertical slice against real Postgres (Testcontainers), with both RLS and a repository-guard pattern implemented and a deliberate cross-tenant attack test for each. Result: 6/6 tests passing, reproduced on a clean rebuild. The spike also surfaced and fixed a real, dangerous misconfiguration along the way — Postgres RLS is always bypassed for a superuser connection, which Testcontainers' default Postgres user is by default — directly informing condition (1) above. Full findings are recorded permanently in `docs/migration/technical-spike-plan.md`'s "Full Spike Findings" section (promoted from the now-deleted `spike/tenant-isolation-spike/`). The repository owner accepted the recommendation in full. |
| Impact | `docs/adr/ADR-0002-modular-monolith-baseline.md`'s `Status` field now reflects full acceptance (both Part A and Part B); the ADR's Decision, Validation Evidence, and Open Questions sections were updated accordingly. `docs/adr/ADR-0010-authorization-model.md` Dimension 2 (tenant-membership validation) is now informed by an accepted data-layer mechanism, though Dimension 2 itself remains open pending its own decision. The `spike/` directory has been deleted per the spike plan's Rollback Strategy, now that its findings are permanently promoted. |
| Follow-up | The three conditions in Decision above are implementation acceptance criteria, not optional follow-ups — they must be satisfied by whoever builds the first module using RLS. Track via `docs/migration/consolidated-task-matrix.md`. |
| Evidence | `docs/adr/ADR-0002-modular-monolith-baseline.md`; `docs/migration/technical-spike-plan.md` ("Full Spike Findings" section); real `./gradlew clean test` output, 2026-08-05, 6/6 tests passing; direct repository-owner acceptance, this conversation, 2026-08-05. |

## D-019: Accept ADR-0001 (Repository Strategy)

| Field | Value |
|---|---|
| Decision | `hr-platform` remains the repository for bootstrap, planning, and future implementation, while `hr-legacy` and the Flutter clients (`workin_desktop`, `workin_mobile`) remain permanently separate repositories, not collapsed into a monorepo. Flutter separation is enforced via pinned git submodule references (`.gitmodules`), not just a `.gitignore` convention. |
| Status | Accepted |
| Owner | Repository owner (human requester) |
| Related ADR | ADR-0001 (Repository Strategy) |
| Reason | This decision did not depend on the technical spike or on production/device access. Flutter Discovery (2026-08-04) confirmed both Flutter repositories carry independent git history and their own build/release tooling, and this repository's own `CLAUDE.md` already scopes it to planning/documentation/governance, not product source hosting — nothing found during Discovery suggested collapsing the repositories would simplify anything. The repository owner accepted directly. |
| Impact | `docs/adr/ADR-0001-repository-strategy.md`'s `Status` field moves to `Accepted`. The repository-boundary question is closed as "permanently separate" for Flutter; the only remaining open question is whether a *new* repository boundary is needed once a Java backend implementation repository is created, which is unrelated to this decision. |
| Follow-up | None blocking. Revisit repository boundaries again only if/when a dedicated backend implementation repository is created. |
| Evidence | `docs/adr/ADR-0001-repository-strategy.md`; `docs/security/pre-migration-flutter-credential-inventory.md` ("Safeguard Applied"); `.gitmodules`; direct repository-owner acceptance, this conversation, 2026-08-05. |

## D-020: Accept ADR-0007 (Testing And Quality-Gate Strategy)

| Field | Value |
|---|---|
| Decision | Adopt layered quality gates that escalate in cost from every commit to pre-release, with independent review and evidence capture, per the taxonomy and cadence already documented in `docs/testing/test-strategy.md` and `docs/testing/quality-gate-cadence.md`. |
| Status | Accepted |
| Owner | Repository owner (human requester) |
| Related ADR | ADR-0007 (Testing And Quality-Gate Strategy) |
| Reason | The strategic taxonomy/cadence decision does not depend on the technical spike — H5 (JUnit 5 + Testcontainers + ArchUnit + REST Assured) was already downgraded from "required spike" to "adopt directly" as mature, standard 2026 Spring Boot tooling. Real CI wiring of each tier is separable implementation work, not a precondition of the strategic decision. The repository owner accepted directly. |
| Impact | `docs/adr/ADR-0007-testing-and-quality-gate-strategy.md`'s `Status` field moves to `Accepted`. "Real CI implementation of each tier" is tracked as implementation task P2-8, not an ADR-acceptance blocker. |
| Follow-up | Wire each quality-gate tier into real GitHub Actions CI as part of first-milestone backend implementation (P2-8). |
| Evidence | `docs/adr/ADR-0007-testing-and-quality-gate-strategy.md`; `docs/testing/test-strategy.md`; `docs/testing/quality-gate-cadence.md`; `docs/migration/technical-spike-plan.md`'s Revision Summary (H5 downgrade rationale); direct repository-owner acceptance, this conversation, 2026-08-05. |

## D-021: Accept ADR-0003 (API Versioning And Flutter Compatibility)

| Field | Value |
|---|---|
| Decision | The new backend preserves the exact current API contract (field names, types, response shapes) at the exact current, unversioned URL surface (`https://workin.company/apis/api/`) for MVP — no new client-selectable API-versioning scheme (URL-path segment, header, media-type negotiation) is introduced, since neither Flutter client has any mechanism to select or send one. The existing remote-config-driven forced-update/maintenance-mode capability (`min*BuildNumberKey`/`*UnderMaintenanceKey` fields, served via whatever replaces `configs/get`) is the migration's mechanism for any future breaking client change, not a new versioning scheme. |
| Status | Accepted |
| Owner | Repository owner (human requester) |
| Related ADR | ADR-0003 (API Versioning And Flutter Compatibility) |
| Reason | Direct evidence in `docs/api/flutter-request-response-compatibility.md` confirms both Flutter clients are fixed (no client-side changes planned), call a single hardcoded unversioned `baseUrl` with no environment-switching mechanism, and already have a working forced-update/maintenance-mode gate. A version-selection scheme the fixed clients cannot use would not be usable in practice; exact contract preservation is the only strategy consistent with the real client constraints found. The repository owner accepted directly. |
| Impact | `docs/adr/ADR-0003-api-versioning-and-flutter-compatibility.md`'s `Status` field moves to `Accepted`. First-milestone API implementation must match exact current field names/types for every `Yes`-marked row in `docs/api/three-frontend-api-usage-matrix.md`. The min-build-number/maintenance-mode fields must be preserved in the new backend. A real API-versioning scheme remains a normal future decision once new client builds exist that could use one — not foreclosed, just not needed for MVP. |
| Follow-up | Directly test strict-vs-tolerant JSON parsing behavior in both Flutter clients before the first real cutover (remains an open question, not resolved by this acceptance). Confirm how the mobile client registers its FCM push token, per the noted follow-up in `docs/api/flutter-request-response-compatibility.md`. |
| Evidence | `docs/adr/ADR-0003-api-versioning-and-flutter-compatibility.md`; `docs/api/flutter-request-response-compatibility.md`; `docs/api/three-frontend-api-usage-matrix.md`; direct repository-owner acceptance, this conversation, 2026-08-05. |

## D-022: Accept ADR-0004 (MySQL-To-PostgreSQL Migration Approach)

| Field | Value |
|---|---|
| Decision | The database migration approach is a single-cutover bulk copy, not chunked/online replication or a long-term dual-database strategy, with a known pre-migration data-cleanup checklist (stray `configs` collation, 45 invalid zero-dates, duplicate-name groups in 4 tables). |
| Status | Accepted |
| Owner | Repository owner (human requester) |
| Related ADR | ADR-0004 (MySQL-To-PostgreSQL Migration Approach) |
| Reason | Real, measured evidence from the actual schema and a real (throwaway, isolated) data dump supports this directly: zero server-side MySQL logic exists to port (views/events/procedures/triggers all confirmed empty), ~62K total rows is small enough for straightforward bulk copy, and zero orphan references / zero cross-tenant inconsistencies mean there is no broken-reference reconciliation to design around. The repository owner accepted directly, with an explicit non-optional condition. |
| Impact | `docs/adr/ADR-0004-mysql-to-postgresql-migration-approach.md`'s `Status` field moves to `Accepted`. Migration implementation may proceed against the single-cutover bulk-copy approach and the named pre-migration cleanup checklist. |
| Follow-up | **Non-optional**: re-verify volume and data-quality findings against a fresh data snapshot immediately before actual cutover — the accepted evidence is a single point-in-time snapshot (dated 2026-08-03) and production may have grown or changed since. Rollback/reconciliation model (`docs/migration/cutover-and-rollback-assumptions.md`) remains a separate open item. |
| Evidence | `docs/adr/ADR-0004-mysql-to-postgresql-migration-approach.md`; `docs/migration/table-volume-analysis.md`; `docs/migration/orphan-reference-analysis.md`; `docs/migration/tenant-boundary-verification.md`; the four confirmed-empty MySQL-logic inventories; direct repository-owner acceptance, this conversation, 2026-08-05. |

## D-023: Accept ADR-0006 Part A (Vendor-Neutral Adapter/SPI Architectural Pattern)

| Field | Value |
|---|---|
| Decision | Attendance device ingestion uses a vendor-neutral core with per-vendor adapters (`DeviceEventAdapter` SPI) translating vendor-native events into one canonical event shape before a single, vendor-agnostic ingestion pipeline handles all business logic. Whether a given vendor uses a local `.NET` edge gateway, direct cloud API, or push webhooks becomes a per-adapter implementation choice, not an architecture-wide commitment. |
| Status | Accepted |
| Owner | Repository owner (human requester) |
| Related ADR | ADR-0006 (Attendance Edge-Gateway Direction) — **Part A only**. Part B (which specific vendors need a local gateway vs. direct API, final protocol selection) remains explicitly `Proposed`, blocked on PMR-04 (real vendor/hardware access). |
| Reason | The architectural pattern does not require knowing which real vendors this system integrates with — only that vendor diversity should be isolated behind an adapter boundary rather than baked into core business logic, a design judgment already worked out in `docs/devices/device-integration-architecture.md`. This did not depend on PMR-04 or the technical spike. The repository owner accepted directly. |
| Impact | `docs/adr/ADR-0006-attendance-edge-gateway-direction.md`'s `Status` field moves to `Accepted`, with Part A marked accepted and Part B explicitly still `Proposed`/blocked. Attendance-module implementation may proceed against the adapter/SPI pattern; no specific vendor/protocol decision is authorized yet. |
| Follow-up | Part B moves to its own acceptance once PMR-04 (real vendor/hardware access) is resolved. |
| Evidence | `docs/adr/ADR-0006-attendance-edge-gateway-direction.md`; `docs/devices/device-integration-architecture.md`; direct repository-owner acceptance, this conversation, 2026-08-05. |

## D-024: Accept ADR-0008 (Observability Baseline — MVP Minimum)

| Field | Value |
|---|---|
| Decision | Minimum MVP observability baseline: structured logging, a correlation/request ID propagated across every request, and OpenTelemetry auto-instrumented traces sent to a lightweight/throwaway collector. A full Prometheus/Grafana/Loki/Tempo deployment is explicitly not adopted now and is deliberately deferred to a separate, later, evidence-informed decision once real production load and cost data exist. |
| Status | Accepted |
| Owner | Repository owner (human requester) |
| Related ADR | ADR-0008 (Observability Baseline) |
| Reason | This ADR's own Risks section already identified the two competing failure modes (a too-heavy stack before real load/cost data exists, vs. no observability at all risking undetected migration/attendance issues) — the minimal baseline is the option that avoids both. The technical-spike plan's H6 hypothesis was already downgraded from "required spike" to "adopt directly" as mature, standard 2026 practice. The repository owner accepted directly. |
| Impact | `docs/adr/ADR-0008-observability-baseline.md`'s `Status` field moves to `Accepted`. First-milestone implementation must include structured logging, correlation ID propagation, and OpenTelemetry tracing from the start. The heavier stack question remains open and deliberately undecided. |
| Follow-up | Revisit the heavier observability stack (Prometheus/Grafana/Loki/Tempo or equivalent) once real production load and cost data exist — no specific trigger threshold set yet. |
| Evidence | `docs/adr/ADR-0008-observability-baseline.md`; `docs/operations/monitoring-and-alerting.md`; `docs/tools/tool-catalog.md`; `docs/migration/technical-spike-plan.md`'s Revision Summary (H6 downgrade rationale); direct repository-owner acceptance, this conversation, 2026-08-05. |

## D-025: Accept ADR-0009 (Fate Of The PHP Dashboard — Option E, Role-Based Split)

| Field | Value |
|---|---|
| Decision | Platform-level administration of Workin itself stays web (existing dashboard `admin`-role surface, or a narrower Next.js replacement). Every subscribed/joined company's own administration (company-owner and HR/Manager staff) consolidates onto the native desktop app, retiring the dashboard's `company_logged_in`/`hr_logged_in` session paths. Individual employees remain mobile-only. |
| Status | Accepted |
| Owner | Repository owner (human requester) |
| Related ADR | ADR-0009 (Fate Of The PHP Dashboard Relative To The Flutter Desktop Admin Client) |
| Reason | The core decision (Option E) was made directly by the product/business owner on 2026-08-04. Of the four Validation Evidence items, Engineering sign-off (Next.js), the Manager-role login-parity gap, and the feature-disposition question (`salary_calculator`, `setting_templates`, `activities`) were resolved by 2026-08-05. The last genuine open item — whether every current dashboard company/HR user has realistic desktop-app access — was confirmed directly by the product/business owner on 2026-08-05: the desktop app is distributed as a built `.exe`/`.dmg` installer and opens like any normal desktop application, no special hardware/OS requirement. |
| Impact | `docs/adr/ADR-0009-dashboard-vs-desktop-admin-client.md`'s `Status` field moves to `Accepted`. Admin-surface module cutover work may proceed against Option E. Dashboard's company/HR-facing pages (everything reached via `doCompanyLogin()`/`doHrLogin()`) become retirement targets once desktop reaches parity; the platform-admin subset (`pages/companies/`, the `admin` branch of `pages/login/`) is kept or rebuilt as a narrower Next.js app. |
| Follow-up | Define the cutover sequence (fix-then-retire-module-by-module vs. all-at-once) and whether a target retirement date exists — both remain open per this ADR's Open Questions, non-blocking for acceptance. |
| Evidence | `docs/adr/ADR-0009-dashboard-vs-desktop-admin-client.md`; `docs/api/flutter-request-response-compatibility.md`; `docs/api/three-frontend-api-usage-matrix.md`; direct repository-owner statements, this conversation, 2026-08-04 and 2026-08-05. |

## D-026: Accept ADR-0010 (Authorization Model — All Six Dimensions)

| Field | Value |
|---|---|
| Decision | Separate platform and tenant authorization domains; four fixed tenant roles (`COMPANY_ADMIN`/`HR`/`MANAGER`/`EMPLOYEE`) with membership-scoped roles/permissions/resource-scopes (not identity-global); mandatory server-side tenant-membership validation on every request (RLS is data-layer-only, not a substitute); a hybrid RBAC + normalized capability-permission model (no external policy engine for MVP); layered enforcement with application-service method authorization as the single authoritative boundary; authorization changes (membership/role/permission/scope) take effect on the very next request, no token-expiry dependency, no cross-request cache for MVP; access tokens carry only minimal identity/context selectors, never embedded role/permission/scope data — all authorization data is loaded and validated server-side on every request. Full text: `docs/adr/ADR-0010-authorization-model.md`; full mechanical detail: `docs/architecture/authorization-model.md`. |
| Status | Accepted |
| Owner | Repository owner (human requester) |
| Related ADR | ADR-0010 (Authorization Model) — all six dimensions, in full. |
| Reason | The repository owner gave a complete, detailed architecture decision directly in this conversation, explicitly stating "Treat the following as my human architecture decision for ADR-0010," covering every one of the six dimensions this ADR had left genuinely open since 2026-08-04. This is a real architecture decision with concrete schemas, precedence rules, enforcement layering, and token-claim policy — not a restatement of the open-question framing. |
| Impact | `docs/adr/ADR-0010-authorization-model.md`'s `Status` field moves to `Accepted`. A new detailed reference document, `docs/architecture/authorization-model.md`, is created. The legacy `hr_permissions` column count is corrected from an earlier "18... and one more" placeholder to the confirmed real count of 17, with a full column-by-column mapping to the new canonical permission catalog. 12 required implementation/validation tasks are defined and tracked (`docs/migration/consolidated-task-matrix.md` rows F-14–F-25). **This acceptance approves architecture and constraints only — no production backend code, database schema, migration, or authorization catalog exists as a result of this decision.** |
| Follow-up | All 12 required implementation/validation tasks remain to be done during real backend implementation, out of `hr-platform`'s planning-only scope absent separate, explicit authorization. |
| Evidence | `docs/adr/ADR-0010-authorization-model.md`; `docs/architecture/authorization-model.md`; `mysql_workin.schema.sql` (`hr_permissions`, 17 columns, recounted 2026-08-05); `dashboard/includes/hr_access.php`, `org_helper.php`, `company_hr_helper.php`, `payroll_helper.php`; direct repository-owner architecture decision, this conversation, 2026-08-05 (verbatim, in full). |

## D-027: Three Follow-On Decisions — ZKTeco Vendor Correction, `can_employees` Kept As One Bundle, Platform-Admin Shared Password Retained For MVP

| Field | Value |
|---|---|
| Decision | (1) ADR-0006 Part B's attendance-device vendor is corrected from an earlier same-day "FK fingerprint" note to **ZKTeco** (`https://www.zkteco.com/en/documents`) — Part B still not `Accepted`, since ZKTeco's public documents page (fetched directly) did not contain integration-protocol/connectivity detail, and the repository owner also stated not knowing the protocol/connectivity answer. (2) ADR-0010's `can_employees` permission mapping is kept as **one bundle** (`employees.manage`, covering employees/administrative-decisions/notifications/complaints together) rather than split into independent keys. (3) The platform-admin identity model keeps `hr-legacy`'s existing **shared password** for the MVP; independent per-admin accounts (individual login, MFA, distinguishable audit trail) is an explicit backlog item, not MVP scope. |
| Status | Accepted (2 and 3 are settled architecture refinements within the already-`Accepted` ADR-0010; 1 is a correction to ADR-0006's still-`Proposed` Part B, not itself a Part B acceptance) |
| Owner | Repository owner (human requester) |
| Related ADR | ADR-0006 (Part B, still not `Accepted`); ADR-0010 (Dimension 1 and Dimension 3 refinements, ADR remains `Accepted` from D-026) |
| Reason | Direct repository-owner statements, this conversation, 2026-08-05: "for fk device i don't know, but use this device zkteco `https://www.zkteco.com/en/documents`" (vendor correction); "for can_employee the same one bundle" (permission-bundling decision); "Platform Admin identities for now use shared password and put enhancments to be independent account in backlog" (platform-admin identity decision). |
| Impact | `docs/adr/ADR-0006-attendance-edge-gateway-direction.md`'s Decision, Validation Evidence, and Open Questions sections corrected to name ZKTeco, with the real (inconclusive) documentation-page research recorded as evidence — Part B remains open pending real SDK/protocol documentation or vendor support contact. `docs/adr/ADR-0010-authorization-model.md` and `docs/architecture/authorization-model.md` updated: the permission-mapping table's row 13 simplified to one bundle; a new "Platform-admin identity model" paragraph added recording the shared-password decision and backlog item. `docs/migration/consolidated-task-matrix.md` gets a new row, F-26 (independent platform-admin accounts, backlog, non-blocking). |
| Follow-up | ADR-0006 Part B remains open — needs ZKTeco's actual SDK/integration documentation (not just the public landing page) or vendor support contact, or physical device access. F-26 (independent platform-admin accounts) has no target date — genuine backlog, not scheduled. |
| Evidence | `https://www.zkteco.com/en/documents` (fetched directly, 2026-08-05 — whitepaper titles only, no protocol detail); `docs/adr/ADR-0006-attendance-edge-gateway-direction.md`; `docs/adr/ADR-0010-authorization-model.md`; `docs/architecture/authorization-model.md` §7; `docs/migration/consolidated-task-matrix.md` F-26; direct repository-owner statements, this conversation, 2026-08-05. |

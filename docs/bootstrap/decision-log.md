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

## D-027: Three Follow-On Decisions — ZKTeco Vendor Correction, `can_employees` Split Preserved (Migration-Only Bundling), Platform-Admin Identity Made A P0 Requirement (Revised 2026-08-05)

**Revised same day, before merge**: this entry's items 2 and 3 were
corrected in place after further direct instruction — the original
text (which collapsed `can_employees` into one permanent permission and
accepted a shared platform-admin password for MVP) is superseded by the
corrected text below, not left standing alongside it. Nothing referencing
the original wording had propagated beyond this repository's own
unmerged PR.

| Field | Value |
|---|---|
| Decision | (1) ADR-0006 Part B's attendance-device vendor is corrected from an earlier same-day "FK fingerprint" note to **ZKTeco** (`https://www.zkteco.com/en/documents`) — Part B still not `Accepted`, since ZKTeco's public documents page (fetched directly) did not contain integration-protocol/connectivity detail, and the repository owner also stated not knowing the protocol/connectivity answer. **The accepted Part A adapter/SPI boundary is sufficient for the rest of the backend to proceed independently of this remaining gap.** (2) **Corrected**: the canonical authorization model keeps **four separate permissions** — `employees.manage`, `employees.decisions.manage`, `notifications.manage`, `complaints.manage` — not one bundle. Legacy's `can_employees=1` maps to granting all four **as a migration-compatibility rule only**, preserving current behavior at cutover without permanently coupling the four capabilities in the new schema or forcing a future redesign to separate them. (3) **Corrected**: the shared platform-admin password is **not accepted as MVP architecture**. Platform administrators must have individual identities, credentials, sessions, revocation, and audit attribution. `docs/migration/consolidated-task-matrix.md` F-26 is elevated from a non-blocking backlog item to a **P0 security requirement**: it does not block development of unrelated tenant modules, but it does block production readiness and the implementation or release of any privileged platform-admin operation. |
| Status | Accepted (2 and 3 are corrections within the already-`Accepted` ADR-0010, replacing this entry's original text; 1 is a correction to ADR-0006's still-`Proposed` Part B, not itself a Part B acceptance) |
| Owner | Repository owner (human requester) |
| Related ADR | ADR-0006 (Part B, still not `Accepted`); ADR-0010 (Dimension 1 and Dimension 3 corrected, ADR remains `Accepted` from D-026) |
| Reason | Direct repository-owner statements, this conversation, 2026-08-05: vendor correction to ZKTeco (`https://www.zkteco.com/en/documents`); "Do not collapse the canonical authorization model into a single `employees.manage` permission... For legacy migration compatibility, map `can_employees = 1` to grant all of those permissions initially. The legacy bundle is a migration rule, not the new authorization model" (permission-splitting correction — least-privilege and future-schema-redesign risk of permanent bundling); "Do not approve the shared platform-admin password as an acceptable MVP architecture... Convert F-26 into a P0 security requirement" (platform-admin identity correction). |
| Impact | `docs/adr/ADR-0006-attendance-edge-gateway-direction.md`'s Decision, Validation Evidence, and Open Questions sections corrected to name ZKTeco, with the real (inconclusive) documentation-page research recorded as evidence — Part B remains open pending real SDK/protocol documentation or vendor support contact. `docs/adr/ADR-0010-authorization-model.md` and `docs/architecture/authorization-model.md` corrected: the permission-mapping table's row 13 restored to four separate canonical keys with an explicit migration-only bundling rule; the "Platform-admin identity model" paragraph rewritten to require individual identity/credentials/sessions/revocation/audit attribution, with no MVP exception. `docs/migration/consolidated-task-matrix.md` F-26 reclassified from Low/P3/backlog/non-blocking to **P0**, blocking production readiness and any privileged platform-admin operation, not blocking unrelated tenant-module development. |
| Follow-up | ADR-0006 Part B remains open — needs ZKTeco's actual SDK/integration documentation (not just the public landing page) or vendor support contact, or physical device access; does not block core backend development. F-26 (independent platform-admin identity) must be complete before any platform-admin functionality reaches production — tracked as a release gate, not backlog. |
| Evidence | `https://www.zkteco.com/en/documents` (fetched directly, 2026-08-05 — whitepaper titles only, no protocol detail); `docs/adr/ADR-0006-attendance-edge-gateway-direction.md`; `docs/adr/ADR-0010-authorization-model.md`; `docs/architecture/authorization-model.md` §7, §9; `docs/migration/consolidated-task-matrix.md` F-26; direct repository-owner statements, this conversation, 2026-08-05 (both the original and the same-day correction). |

## D-028: Declare Phase 0's Implementation Lock Lifted For `backend/` — Phase 1 (Core Backend Development) Begins

| Field | Value |
|---|---|
| Decision | The Phase 0 prohibition on application implementation (`docs/bootstrap/definition-of-done.md` criterion "No application implementation exists," mechanically enforced by `scripts/validate_phase0.py`'s `FORBIDDEN_FILE_NAMES`/`FORBIDDEN_SUFFIXES` scanner) is lifted **for `backend/` specifically, and only `backend/`.** `admin-web/`, `edge-gateway/`, `infrastructure/`, `contracts/`, and `specs/` remain Phase-0-locked — each needs its own explicit transition decision before real files may land there. This is a scope-narrow unlock, not a blanket "Phase 0 is over" declaration. |
| Status | Accepted |
| Owner | Repository owner (human requester) |
| Related ADR | None — this is a bootstrap/Phase-transition process decision, not a target-system architecture decision. It relies on every architecture ADR already `Accepted` (D-016 through D-026) as its precondition. |
| Reason | Direct repository-owner instruction ("ok start"), given immediately after a full readiness report confirming every Migration-Readiness Gate condition for starting core backend development was satisfied (H2 spike executed and accepted, ADR-0002/ADR-0005/ADR-0010 all `Accepted`, PMR-05 closed on production). When the repository's own mechanically-enforced Phase 0 lock on `backend/` was discovered (not previously checked against this specific instruction) and surfaced directly, the repository owner chose "Formally transition Phase 0 → Phase 1," the option matching this repository's own established governance discipline — the same rigor as every ADR acceptance in this decision log, not a silent bypass of the enforcement script. |
| Impact | `scripts/validate_phase0.py`'s forbidden-file scanner gains a deliberate, narrow, documented exclusion for `backend/` (mirroring the `spike/` exclusion pattern already proven in this session, with equivalent regression-test coverage) — real `.java`/`build.gradle`/`Dockerfile`/`src/` content may now exist under `backend/`. `CODEOWNERS` gains a `/backend/` routing entry, and `.github/dependabot.yml` gains a `gradle` ecosystem entry for `/backend/`, both required the moment real tracked files land there per already-existing (previously dormant) checks. `backend/README.md` is updated to state the current, accurate boundary instead of the now-superseded blanket Phase 0 statement. Real Spring Boot/Java 25 project scaffolding and the first real module (tenant identity) begin under `backend/` as separate, immediately following work. |
| Follow-up | The remaining component directories (`admin-web/`, `edge-gateway/`, `infrastructure/`, `contracts/`, `specs/`) each need their own explicit Phase 0 → Phase 1 decision before real files may land there — this entry does not pre-authorize any of them. |
| Evidence | `docs/bootstrap/definition-of-done.md`; `backend/README.md` (pre-transition text); `scripts/validate_phase0.py` (`FORBIDDEN_FILE_NAMES`/`FORBIDDEN_SUFFIXES`, `SPIKE_DIR_NAME` exclusion precedent); `docs/bootstrap/decision-log.md` D-015 (original Phase 0 completion, explicitly scoped to Discovery only); direct repository-owner instruction, this conversation, 2026-08-05. |

## D-029: Three Readiness-Gate Product Decisions (Manager Scope, Bulk-Delete Safety, Housing Allowance)

| Field | Value |
|---|---|
| Decision | Repository owner answered three pending decisions from `docs/migration/pending-decisions-brief.md` (2026-08-07): **(A1)** `MANAGER` role access in the new platform is **branch/department-scoped**, not company-wide — build `membership_resource_scopes` enforcement so a manager sees and acts only within assigned scope; unblocks F-16/F-25 and the MANAGER cutover. **(A3)** The future bulk attendance `delete_range` endpoint (`hr-legacy#25`) must add **dry-run/preview + explicit confirm + audit-log entry** before the destructive delete, not port legacy's one-shot bulk delete. **(A4)** `salary_contracts.housing_allowance` (`hr-legacy#14`) is a **real, settable contract field** — the already-shipped payroll-group behavior is confirmed correct, closing the row with no code change. |
| Status | Accepted |
| Owner | Repository owner (human requester) |
| Related ADR | ADR-0010 (A1 realizes Dimension "resource scopes"/§7 F-16/F-25, ADR remains `Accepted`); none for A3/A4 (business-rule decisions). |
| Reason | Direct repository-owner selection, 2026-08-07, against the options and recommendations in `pending-decisions-brief.md`. A1: legacy's company-wide manager access contradicts its own doc-comments, the scoped pattern already exists in-codebase (`penalties`' `sql_manager_same_branch_scope`), and 0 live `manager`-role employees exist (`hr-legacy#26`), so the correct scoped model is chosen without migration pressure. A3: a pure safety improvement given attendance's payroll blast radius. A4: keeps the enhancement already provisionally shipped. |
| Impact | A1 unblocks the manager-scoping implementation track (F-16 resource-scope migration/enforcement, F-25 scope tests) — its first slice begins as separate, immediately-following work. A3 becomes the acceptance criterion recorded on `hr-legacy#25` for when the bulk-delete endpoint is built (no endpoint exists yet). A4 marks `hr-legacy#14` Done in the consolidated matrix. |
| Follow-up | Manager-scoping first slice: spec → plan → implement on the established rhythm. A3 has no code until the attendance bulk-delete slice is scoped. |
| Evidence | `docs/migration/pending-decisions-brief.md` (the three questions and options); `docs/migration/consolidated-task-matrix.md` rows `hr-legacy#14`/`#25`, F-16/F-25; `docs/legacy/business-rule-extraction.md` (manager-scope and bulk-delete findings); direct repository-owner selection, this conversation, 2026-08-07. |

## D-030: Employee Removal Is Deactivation-Only; No Known Live QR Check-In Caller

| Field | Value |
|---|---|
| Decision | Repository owner answered the last two open readiness-gate product decisions (`docs/migration/pending-decisions-brief.md` A2, A5), 2026-08-08: **(A2, `hr-legacy#20`)** employee removal in the new platform is **deactivation-only** — the rewrite ships **no employee hard-delete endpoint**; deactivate/reactivate (`PUT /api/tenant/employees/{id}/status`, already shipped) is the sole removal operation, so all payroll/financial history is always retained and legacy's cascade-delete-around-RESTRICT has no equivalent code path. **(A5, `hr-legacy#16`/F-04)** **no live QR-check-in caller is known**, so the QR-skips-the-2-hour-gap finding is not currently exploitable; a standing acceptance criterion is recorded for the future QR self-check-in slice (apply the same 2-hour guard), which is itself blocked on the employee↔identity link. |
| Status | Accepted |
| Owner | Repository owner (human requester) |
| Related ADR | None — business-rule/scope decisions, not target-system architecture. |
| Reason | Direct repository-owner selection, 2026-08-08, against the options in `pending-decisions-brief.md`. A2: retention safety (financial records always preserved; no destructive path to misuse) and simplicity (deactivate already exists — no new code, no compliance exposure). A5: the owner confirmed no known live QR caller; the safe default is to treat `#16` as not-currently-exploitable rather than build speculative guards for a surface that does not yet exist in the rewrite. |
| Impact | `hr-legacy#20` marked **Closed by construction** in the consolidated matrix — no employee hard-delete endpoint is on the roadmap; deactivation is the removal mechanism. `hr-legacy#16`/F-04 recorded as **no live caller confirmed**, parked with a standing acceptance criterion for the future QR slice. No code change: A2 is satisfied by the absence of a delete endpoint; A5 is a finding-status update. With D-029 and D-030 recorded, **no product decision remains open on the Migration-Readiness Gate** — every remaining item needs client source (Flutter), device hardware, or the cutover window. |
| Follow-up | If a live QR caller is later identified, reopen `#16`/F-04 and add the 2-hour guard to the QR self-check-in slice. If a genuine hard-delete requirement ever appears, it would need its own decision superseding A2. |
| Evidence | `docs/migration/pending-decisions-brief.md` (A2/A5 questions and options); `docs/migration/consolidated-task-matrix.md` rows `hr-legacy#20`/`#16`/F-04; `docs/legacy/business-rule-extraction.md` (employee-deletion and QR-gap findings); direct repository-owner selection, this conversation, 2026-08-08. |

## D-031: Payroll Uses A Fixed 30-Day Divisor, Not Real Calendar Days

| Field | Value |
|---|---|
| Decision | Payroll's day rate is `gross_salary / 30` in every month, and the daily-wage-to-monthly conversion is `daily_wage * 30`. The divisor is a fixed 30, never the period's real calendar length. Penalties are priced at the same rate. |
| Status | Accepted |
| Owner | Repository owner (standing instruction, 2026-08-09: hr-platform's business rules match hr-legacy exactly) |
| Related ADR | None — a business-rule finding, not a target-system architecture choice. |
| Reason | `docs/bootstrap/open-questions.md` recorded this as undecided ("fixed 30-day divisor vs real calendar days"). It turned out not to be a decision to make: reading `payroll_compute_employee_payslip` in full (`hr-legacy/apis/helpers/payroll_calculation.php:1162-1172` @ `d113204`) settles it as a fact of the system being replaced. The constant is `PENALTY_CALENDAR_DAYS_PER_MONTH = 30` (`penalties_amount_helper.php:8`), applied to the day rate, the daily-wage conversion and the penalty rate alike. Adopting it follows from the standing match-legacy instruction, and it is what the migration-correctness reconciliation compares against. |
| Impact | An absent day costs the same in February as in a 31-day month. Implemented in `PayrollCalculationService` (PR #79) and pinned by a dedicated case in `PayrollCalculationServiceTest`. Closes the open question; no further product input required. |
| Follow-up | If the business ever wants real calendar days, that is a deliberate change visible on every payslip, and it must wait until reconciliation against real legacy data is green — before then, the change is indistinguishable from a migration fault. |
| Evidence | `hr-legacy/apis/helpers/payroll_calculation.php:1162-1172` and `penalties_amount_helper.php:8` @ `d113204`; `backend/src/main/java/com/workin/backend/payroll/PayrollCalculationService.java`; `docs/bootstrap/open-questions.md`. |

## D-032: ETL Coverage — Q1–Q8 Answered; Most Gaps Resolve To Migrate, Not Drop

| Field | Value |
|---|---|
| Decision | Repository owner answered all eight questions in `docs/migration/etl-coverage-decisions-brief.md`, 2026-08-12, closing the 47-gap decision backlog. **Q1**: companies do not authenticate as standalone principals; legacy company logins become individual `COMPANY_ADMIN` identities linked to the company, preserving phone, email and onboarding state. **Q2**: nine unmigrated tables migrate with full history; `employee_docs` migrates metadata *and* the `uploads/` files; `push_tokens` and `otp_codes` drop; `phone_countries` reseeds canonically; the three lookup tables map by value, not legacy id. **Q3**: `company_code`, contact name, `commercial_reg_url`, `logo_url` and `rejection_reason` all preserved; `main_branch_address` folds into the main branch; phones normalize to E.164. **Q4**: `deduction_type` semantics preserved — a migration assertion instead of a duplicate column *if* equivalence is proven against real data; `deduction_installments_json` normalizes into schedule records. **Q5**: `updated_at` on all mutable business entities under one auditing model. **Q6**: `exception_types.is_active` migrates and must filter selectors. **Q7**: `token_version` drops; `address` (as PII), `photo_url` and `contract_duration_months` are preserved. **Q8**: no generic `configs` table; proven-required keys become typed platform configuration. |
| Status | Accepted |
| Owner | Repository owner (human requester) |
| Related ADR | None directly. Q1 operates within ADR-0009's role split and ADR-0010's authorization model; Q7's `address` PII handling lands in the ADR-0010 authorization catalog. |
| Reason | Direct repository-owner answers, 2026-08-12, against the questions in `etl-coverage-decisions-brief.md`. The brief was written because 47 registered coverage gaps were 47 unanswered questions, and the answers were not engineering's to invent. |
| Impact | **Inverts the remaining work from decisions to engineering.** Only six items are deliberate drops; everything else migrates, and most of it has no target schema. Derived work: 9 new tenant-scoped modules, lookup normalization, 6 added `companies` columns, `COMPANY_ADMIN` identity minting, `updated_at` across ~35 tables, `advance_installment` normalization, `exception_types.is_active` plus its read-path filters, and contract-term normalization. **A file-migration work stream is now required and does not exist in any form** — every artifact in `scripts/etl/` emits SQL and nothing moves bytes, while four answers require files to travel from legacy `uploads/` into platform-controlled storage. Two answers resolved against repository evidence rather than needing further input: legacy hashes are bcrypt (`password_hash($x, PASSWORD_BCRYPT)`, 9 call sites, no MD5/SHA1), so Q1's password-reset branch is not needed and hashes port unchanged. |
| Follow-up | Four open questions the answers created, each needing a human call before the corresponding work is built: **OQ-1** phone collision between a minted `COMPANY_ADMIN` identity and an existing employee identity (both `identities.phone` and `companies.phone` are `UNIQUE`; population count needs the data dump); **OQ-2** what happens when E.164 normalization fails on a login phone; **OQ-3** JPA auditing versus database trigger for `updated_at`, which disagree exactly when the ETL writes rows; **OQ-4** which roles may read employee `address`, and whether it appears in list endpoints. Separately, `coverage_audit.py` needs a third registry state — "decided to migrate, not yet migrated" is neither `ACCEPTED` nor `PENDING`, and the existing `PENDING` notes are now stale in wording. Q4's assertion-versus-column branch is blocked on a data dump; only a 54 KB schema dump exists. |
| Evidence | `docs/migration/etl-coverage-decisions-brief.md` (questions, answers recorded verbatim, derived work, OQ-1–OQ-4); `scripts/etl/coverage_audit.py` (the 47-gap ledger); `../hr-legacy/apis` and `../hr-legacy/dashboard` (bcrypt call sites); `backend/src/main/resources/db/migration` (target `companies`/`identities` shape, `updated_at` in 3 of 40 migrations); direct repository-owner answers, this conversation, 2026-08-12. |

## D-033: OQ-1–OQ-4 Answered; The Coverage Ledger Gains A Third State

| Field | Value |
|---|---|
| Decision | Repository owner answered the four open questions D-032 created, 2026-08-12, and approved a third registry state in `scripts/etl/coverage_audit.py`. **OQ-1**: when a legacy company login phone matches an existing employee identity for the same tenant/person, **reuse that identity** and grant it the `COMPANY_ADMIN` membership/role — never create a duplicate identity for the same person. A phone matching an identity belonging to a different person or tenant context that cannot be safely reconciled is **flagged for explicit migration remediation rather than guessed at**. **OQ-2**: a login phone that cannot be normalized to valid E.164 keeps its original legacy value, is marked **migration-invalid**, and is blocked from automatic activation/login until corrected — never silently rewritten, discarded, or fabricated. **OQ-3**: `updated_at` is **database-enforced** so ETL, administrative-script and application writes share identical semantics; JPA auditing may remain an application convenience but not the sole mechanism or source of truth. **OQ-4**: employee `address` is readable only by the employee themself, `COMPANY_ADMIN`, authorized HR roles, and `SUPER_ADMIN`; managers and unrelated employees do not receive it by default, enforcement is at the service/API layer, and it is excluded from general employee list responses. |
| Status | Accepted |
| Owner | Repository owner (human requester) |
| Related ADR | OQ-4 belongs in the ADR-0010 authorization catalog (F-14–F-25). OQ-1 operates within ADR-0009's role split. |
| Reason | Direct repository-owner answers, 2026-08-12, to the four questions recorded as follow-up on D-032. The third registry state was approved in the same exchange. |
| Impact | **Ledger**: `coverage_audit.py` now has `ACCEPTED` (decided not to migrate), `SCHEDULED` (decided to migrate, not yet migrated, naming the decision that settled it), and `PENDING` (undecided). `--check` reads **47 gaps — 8 accepted, 36 scheduled, 3 pending a decision**, replacing "47 gaps, all registered", which described settled questions as open. Every `SCHEDULED` entry must cite a decision id, enforced by `--self-test`. **A conflict check was added and immediately caught a pre-existing defect**: `salary_contracts.total` was registered in both `ACCEPTED` and `PENDING`, and because `check` unions the registries the contradiction had registered as fine. **Three columns were never asked about**: the brief claimed to convert all 47 gaps into questions and converted 44 — `employees.is_mobile_attendance_enabled`, `can_check_in_any_branch` and `join_request_status` were omitted from Q7 and remain `PENDING`. **New requirements**: OQ-1 needs a per-record remediation channel (today every guard is a load-level abort); OQ-2 needs a migration-invalid state on the target row and means `companies.country_code` cannot be dropped unconditionally; OQ-3 means a database trigger must be suppressed during load or migrated rows carry the load timestamp — the same trap `created_at` fell into with `DEFAULT now()`. |
| Follow-up | Decide the three unasked employee columns; two of them (`is_mobile_attendance_enabled`, `can_check_in_any_branch`) change what an employee can do, so dropping either silently changes system behaviour. Build the OQ-1 remediation channel and the OQ-2 migration-invalid state before the `COMPANY_ADMIN` minting slice. Record OQ-4 in the authorization catalog. |
| Evidence | `docs/migration/etl-coverage-decisions-brief.md` (OQ answers, the omission disclosure); `scripts/etl/coverage_audit.py` (three-state ledger, `_conflicting`, self-test assertions); `scripts/etl/README.md`; direct repository-owner answers, this conversation, 2026-08-12. |

## D-034: The Three Omitted Employee Columns All Migrate; No Coverage Gap Remains Undecided

| Field | Value |
|---|---|
| Decision | Repository owner decided the three `employees` columns omitted from the 2026-08-12 brief's Q7, 2026-08-12. All three **preserve and migrate**. **`is_mobile_attendance_enabled`** — mobile-attendance eligibility is an employee-level business rule and must remain unchanged after cutover. **`can_check_in_any_branch`** — an employee-level attendance permission that must retain its legacy behaviour. **`join_request_status`** — migrated **even though the new join-request workflow is not implemented**; legacy values map into an explicit onboarding/join status in the target so existing employee state is not lost, and the future workflow builds on that state rather than reconstructing it. |
| Status | Accepted |
| Owner | Repository owner (human requester) |
| Related ADR | None — business-rule and data-retention decisions. |
| Reason | These three were never put to the owner: the brief claimed to convert all 47 coverage gaps into questions and covered 44, because Q7 was headed "Four employee columns with no target" when there were seven. The omission was found while moving the D-032 answers into the ledger and is disclosed in `etl-coverage-decisions-brief.md`. Two of the three change what an employee can actually do — whether they may check in from a phone, and whether they are exempt from the branch geofence — which is the same class of risk as the `created_at` defect: a value that disappears without anything failing. |
| Impact | `PENDING` in `scripts/etl/coverage_audit.py` is **empty**. The ledger reads **47 gaps — 8 accepted, 39 scheduled, 0 pending a decision**: every known gap is now either a recorded drop or a recorded commitment to migrate, and none is waiting on a human. `join_request_status` is the first entry migrated for a feature that does not exist in the rewrite — correct because the workflow can be built at any time while the legacy values vanish with the legacy database. Empty `PENDING` is a state to defend rather than a finish line: a new legacy column or a new detection class lands there first and `--check` fails until somebody decides. |
| Follow-up | Nothing is owed a decision. All 39 scheduled entries are owed an implementation, and the largest untested assumption in the migration remains that the ETL has never run against real data — `scripts/etl/README.md` records "No production data has been moved". Target columns are still needed for all three of these before their loads can be written. |
| Evidence | `docs/migration/etl-coverage-decisions-brief.md` ("Three Columns This Brief Never Asked About", and the answers); `scripts/etl/coverage_audit.py` (`SCHEDULED` entries citing D-034, empty `PENDING`); direct repository-owner decision, this conversation, 2026-08-12. |

## D-035: Q1–Q6 Of The Real-Data Findings Brief Answered

| Field | Value |
|---|---|
| Decision | Repository owner answered Q1–Q6 in `docs/migration/2026-08-13-etl-real-data-findings-decision-brief.md`, 2026-08-13 — the six data-quality findings (C–H) the first real end-to-end ETL run surfaced. **Q1 (companies with no name / status collapse)**: **all** legacy companies migrate, including incomplete `pending` signups — no exclusion, no fabricated placeholder name. The legacy `status` lifecycle (`active`/`pending`/`rejected`/`suspended`) is preserved as an explicit target status field, not collapsed into a boolean. A `pending` company with no name is a **valid legacy lifecycle state**, not a migration-invalid one (explicitly distinguished from OQ-2's phone-normalization-failure shape) — `name` stays nullable at the data layer, with a non-null name **required before activation** as an application-level rule, not a migration-time fabrication. **Q2 (`salary_contracts.effective_from` zero-dates, 23 rows)**: migrate the contracts; use the row's own `created_at` date as the deterministic fallback; record the repair in migration remediation/audit output so the synthesized value stays traceable to "this was repaired," not indistinguishable from real data. **Q3 (`attendance` punches-AND-exception, 13 rows)**: legacy data-quality defects, not a real exception to the documented XOR rule. Preserve the real `check_in`/`check_out` timestamps, clear `exception_type_id`, record each remediation individually, and **keep the target `CHECK` constraint** (it is correct; these 13 rows were wrong). **Q4 (`leave_balance.year = '0000'`, 6 rows)**: **do not synthesize a year.** Preserve the 6 rows in migration remediation/quarantine output; exclude them from the operational `leave_balances` load until a correct year is supplied by a human — this is the one finding where guessing was explicitly rejected, unlike Q2/Q5. **Q5 (`employee_shift_assignments.effective_from` zero-dates, 23 rows)**: same fallback as Q2 — the row's own `created_at` date, recorded explicitly as a repair. **Q6 (orphaned `exception_types` ids 1, 3, 4, `company_id = 19`)**: exclude all three from the operational migration; record the exclusion explicitly in migration reconciliation/remediation output — not a silent drop. |
| Status | Accepted — **amended by D-037 (2026-08-15)**: A3 and A4 hold as migration decisions, but findings C–H were re-verified against live production and two of them (Q3/A3, Q4/A4) are ongoing source-system bugs, not the fixed sets this decision assumed. Read D-037 with this entry. |
| Owner | Repository owner (human requester) |
| Related ADR | None directly. Q1's explicit status field is a target-schema addition, not a new architectural decision. |
| Reason | Direct repository-owner answers, 2026-08-13, against the six questions in `2026-08-13-etl-real-data-findings-decision-brief.md`, itself written because the first real end-to-end ETL run (against `mysql_workin.data.sql`, dated 2026-08-03) found six load-blocking data-quality defects with no prior decision to resolve them against. |
| Impact | **A consistent remediation posture emerges across all six, not six unrelated calls**: real, traceable data (Q1's `pending` companies, Q2/Q5's zero-dates) migrates with the repair recorded, never silently fabricated or silently dropped; genuine defects with no safe inference (Q4's zero-year — a wrong guess misstates real entitlement; Q3's contradictory rows; Q6's orphaned parent) are excluded or corrected, explicitly, not guessed at. This is the **third distinct remediation pattern this migration has needed** (D-033's OQ-1/OQ-2 per-record channel and migration-invalid state were the first two), and Q2–Q6 all now depend on that same remediation/audit output existing — it does not yet. `companies.status` moves from `PENDING` to `SCHEDULED` in `scripts/etl/coverage_audit.py`, citing this decision. Q2–Q6 have no coverage-ledger entry to move (they are row-level data defects, not column-coverage gaps), so their "done" state is a future `load_postgres.py` change plus its own test coverage, tracked in the punch list, not the ledger. |
| Follow-up | Engineering owed, not yet built: (1) `companies` schema needs a real status column plus the `is_active`-style rename `find_gaps()` already flagged is fine to keep, migrated alongside it; (2) `load_postgres.py` needs a remediation/audit output mechanism — referenced by name in Q2, Q3, Q5, Q6's answers, and by the quarantine requirement in Q4's — that does not exist in any form today, is its own design question (a table? a report? both?), and blocks implementing any of Q2–Q6 correctly; (3) the "non-null name required before activation" rule from Q1 is an application-layer validation, not implemented anywhere yet. **10 of the 11 `PENDING` coverage-ledger gaps found alongside this run remain undecided** (7 `employees` columns, plus `attendance`/`advances`/`requests.updated_at`) — `--check` still reports pending gaps after this decision, and per the repository owner's explicit sequencing, OQ-1/OQ-2/OQ-3 and the A/B/J tooling fixes wait for those too, then a clean ETL re-run, before implementation of any of this begins. |
| Evidence | `docs/migration/2026-08-13-etl-real-data-findings-decision-brief.md` (Q1–Q6, A1–A6 verbatim); `docs/migration/2026-08-13-etl-next-steps-punch-list.md` (findings C–H); `scripts/etl/coverage_audit.py` (`companies.status` moved to `SCHEDULED`); direct repository-owner answers, this conversation, 2026-08-13. |

## D-036: Six `employees` Business Fields Migrate; The 10 Remaining `PENDING` Gaps Close

| Field | Value |
|---|---|
| Decision | Repository owner decided the 10 coverage-ledger gaps `PENDING` after D-035, 2026-08-13, closing this ledger's `PENDING` state to empty. **Six genuinely needed a decision** — `employees.employee_code`, `.country_code`, `.national_id`, `.birth_date`, `.gender`, `.hire_date`, none of which had ever been put to the owner before finding I's fix surfaced them. **The other four did not need a new decision** — `employees.updated_at`, `attendance.updated_at`, `advances.updated_at`, `requests.updated_at` are already covered by D-033's OQ-3 (database-enforced `updated_at` across every mutable business entity); the owner directed these be moved citing D-033, not treated as a fresh product call. All six business fields: **preserve and migrate.** `employee_code` — migrate and preserve exactly, **do not renumber**; the existing `(company_id, employee_code)` uniqueness in legacy (`unique_employee_code_per_company`, `uq_employees_company_code` — two constraints for the same pair, legacy's own duplication) carries the value as-is. `country_code` — preserve its semantics for phone normalization; **do not drop it until E.164 normalization succeeds**, and retain the original components (this column plus the bare `phone` digits) for remediation when normalization fails — directly reusable by OQ-2's migration-invalid-phone mechanism once built. `national_id` — migrate as-is. `birth_date`/`hire_date` — migrate valid values; legacy zero-dates (`'0000-00-00'`, both columns nullable per `mysql_workin.schema.sql`) map to `NULL` with the remediation recorded, **never an invented date** — consistent with `invalid-date-analysis.md`'s original proposal for these two columns (22 `hire_date` and 2 `birth_date` zero-date rows), now with a target column to land in. `gender` — migrate with an explicit legacy-to-target value mapping; legacy is a clean 3-value `enum('male','female','other')`, nullable — the target mapping is 1:1 by value, not a collapse. |
| Status | Accepted |
| Owner | Repository owner (human requester) |
| Related ADR | None directly. `country_code`'s retention-for-remediation requirement operates within the same phone-normalization space as OQ-2 (D-033). |
| Reason | Direct repository-owner answers, 2026-08-13, resolving the six genuinely-undecided fields from `scripts/etl/coverage_audit.py`'s `PENDING` registry (added by the `UNTARGETED_COLUMN` fix alongside D-032/033/034). The four `updated_at` columns were explicitly identified by the owner as already-decided under D-033/OQ-3 rather than requiring separate treatment — recorded here as a ledger-registration action, not a new product decision. |
| Impact | **`PENDING` in `scripts/etl/coverage_audit.py` returns to empty.** All 10 gaps move to `SCHEDULED`: the six business fields cite D-036; the four `updated_at` columns cite D-033. Combined with D-035's `companies.status`, the ledger now reads **60 gaps — 10 accepted, 50 scheduled, 0 pending a decision**. Every known gap is again either a recorded drop or a recorded commitment to migrate, and — as of this decision — none is invisible to the tool either, closing the loop D-035's Follow-up opened. `employees`, `attendance`, `advances`, and `requests` all need real target-schema columns added before any of these ten loads can be written; none has one today. |
| Follow-up | Six target columns need adding to `employees` (`employee_code`, `country_code`, `national_id`, `birth_date`, `gender`, `hire_date`) plus one each to `attendance`, `advances`, `requests` (`updated_at`) and the already-tracked `employees.updated_at` — none exist yet. `gender`'s legacy-to-target value mapping needs to be written down explicitly wherever the migration/target schema records enums (not yet specified beyond "1:1 by value" here). `country_code`'s remediation-retention requirement is a real dependency for OQ-2 (D-033) — whoever builds OQ-2's migration-invalid-phone mechanism needs `country_code` to already have a target column and be loaded, or the retention this decision requires has nowhere to live. Per the repository owner's explicit sequencing: with `PENDING` now empty, next is fixing findings A/B/J (tooling, prepared not applied — `2026-08-13-etl-real-data-findings-decision-brief.md`'s "Prepared fixes" section) with tests, then a clean full-ETL re-run, before OQ-1/OQ-2/OQ-3 implementation begins. |
| Evidence | `scripts/etl/coverage_audit.py` (`PENDING` entries pre-decision, `SCHEDULED` entries post-decision citing D-036/D-033); `hr-legacy/mysql_workin.schema.sql` (`employees` column definitions: `employee_code varchar(64)`, `country_code varchar(10)`, `national_id varchar(50)`, `birth_date date`, `gender enum('male','female','other')`, `hire_date date`, all nullable except as noted; the two employee_code uniqueness constraints); `docs/migration/invalid-date-analysis.md` (original `hire_date`/`birth_date` zero-date counts and `NULL`-remediation proposal); D-033 (OQ-3, `updated_at`); direct repository-owner decision, this conversation, 2026-08-13. |

## D-037: Findings C–H Re-Verified Against Live Production; Migration Remediation And Source-System Prevention Split

| Field | Value |
|---|---|
| Decision | Findings C–H were re-verified against the **live production database** (MariaDB 11.8.8) on 2026-08-15, read-only, rather than the 2026-08-03 snapshot D-035 was decided from. **Four hold unchanged** — C (`companies` missing name: now 81 of 317, still exclusively `pending`, every `active` company named), D (`salary_contracts.effective_from` zero-dates: still exactly 23, now of 3,242), G (`employee_shift_assignments.effective_from`: still exactly 23, now of 4,184), H (orphaned `exception_types` ids 1/3/4 → `company_id 19`: unchanged). D and G being frozen in absolute terms while their tables grew confirms both are closed historical defects. **Two do not hold** — E (`attendance` punches-AND-exception) went from 13 of 36,316 to **39 of 39,881**, tripling in twelve days with new rows arriving 1–5 per day; F (`leave_balance.year = '0000'`) went from 6 of 2,980 to **16 of 3,501**, the original six untouched plus ten new. Both are **live source-system bugs still producing corrupt rows**, not the fixed sets D-035 assumed. Accordingly, every finding's answer is now split into two separately-tracked halves: **migration remediation** (what D-035 already decided — unchanged) and **prevention**, which per the standing rule that legacy PHP is not patched happens **in the new platform, not in legacy**. `workin-hr/hr-legacy` issues #28 (E) and #29 (F) document the defects and their root causes for the rewrite; they are not requests to change legacy. E's prevention already exists target-side as `V21__create_attendance.sql:32`'s `CHECK (exception_type_id IS NULL OR check_out IS NULL)`; F's is largely structural, since PostgreSQL will not silently coerce `0` into a date the way MySQL's `YEAR` does. **Legacy therefore keeps producing both defects until cutover, and that is an accepted consequence of not patching it, not an open task.** |
| Status | Accepted |
| Owner | Repository owner (human requester) — directed the live re-verification and the remediation/prevention split, 2026-08-15. |
| Related ADR | None. This amends D-035's factual basis and scope; it does not change any of its remediation rules. |
| Reason | D-035 was decided against a point-in-time snapshot, and both the punch list and `table-volume-analysis.md` carried a standing caveat that every finding needed re-checking against a fresher dump before cutover. The snapshot was 12 days stale and the local copy of `mysql_workin.data.sql` no longer exists on the working machine; read-only production credentials were supplied instead. Re-verification was the cheapest way to establish whether the decisions still applied — and it found that for two of six, the underlying assumption ("a fixed set of defective rows") was false. |
| Impact | **A3 and A4's remediation rules are unchanged and still correct**, but neither is sufficient alone. A3 repairs whatever the violating set is *at extraction time* — a moving number, not 13 — and after any delta sync or phased cutover the set refills at roughly 2/day until #28 ships. A4's quarantine was scoped as a one-time queue of six; it is a continuously fed queue, so the quarantine output must keep accepting rows rather than being a one-shot cutover artifact. **A single frozen cutover window makes A3/A4 sufficient on their own; a phased cutover makes #28/#29 prerequisites.** Separately: every `expected_count` in `export_legacy.py`'s `MANIFEST` is stale — all 21 tables grew (+4.3% to +59.6%), and `attendance` grew by 11 rows *during* the verification session itself — so the manifest must measure at extraction time rather than carry snapshot constants. |
| Follow-up | Root causes are identified and documented for the rewrite — **no legacy change is owed for either**: **#28** — the XOR invariant is enforced nowhere in legacy (no DB `CHECK`, no server validation, no client validation); `dashboard/pages/attendance/page.php:31-36` writes `check_in`/`check_out`/`exception_type_id` straight from `$_POST`, with three further unguarded manual paths. Investigation also found the likely **dominant** source is automatic rather than manual: `attendance_auto_close_stale_open_sessions()` (`apis/helpers/attendance_session_helper.php`) fills `check_out` on any open row whose `check_in` is not exactly midnight without clearing `exception_type_id` — behaviour the rewrite must not reproduce. **#29** — `apis/helpers/employee_create_helper.php:207` and `apis/api/employees/create.php:202` use `??`, which guards only a missing or null key, so an explicit `""`/`0` casts to `0` and MySQL's `YEAR` stores `0000`; the adjacent `period_from_month`/`period_to_month`/`monthly_cap_days` lines use the correct `isset(...) && ... !== ''` guard. **One decision is reopened by this investigation and is NOT settled here**: A4 chose to quarantine the zero-year rows on the premise that they could not be stored, but `leave_balances.year` is `SMALLINT NOT NULL` (`V25:55`) and SMALLINT stores `0` fine — only the load's derived `created_at` (`make_timestamptz(s.year::INT, ...)`, `load_postgres.py:817`) actually fails. Carrying `year = 0` through as-is and fixing only that synthesis is a phase-1 answer that matches the standing preference for faithful migration with correction after transformation; it needs an explicit call against A4's quarantine. **Shared enabler, not yet decided**: production `sql_mode` is `NO_AUTO_CREATE_USER,NO_ENGINE_SUBSTITUTION` — no `STRICT_TRANS_TABLES`, no `NO_ZERO_DATE`, no `NO_ZERO_IN_DATE` — which is why zero dates and zero years land silently, and is the common enabler behind A2, A4 and A5 alike. Tightening it would close the class at the database layer but would begin rejecting writes the application currently gets away with; that needs its own assessment and is **not** decided here. One question is left open by #28 rather than settled: an unused language key `attendance_punches_or_exception` reads "Enter check-in/check-out **and/or** an exception type", which is a require-at-least-one message rather than a mutual-exclusion one — weak evidence worth confirming, though the target schema's `CHECK (exception_type_id IS NULL OR check_out IS NULL)` and `business-rule-extraction.md` both take the exclusive reading. |
| Evidence | Live production queries, 2026-08-15, read-only (`SELECT`/`SHOW` only), against `u856167371_workin` @ MariaDB 11.8.8; `workin-hr/hr-legacy` issues #28 and #29; `hr-legacy` commit `d113204c8a2cf83b997c5e65c6c86e4f59b3f8f6` for all cited legacy line numbers; `docs/migration/2026-08-13-etl-real-data-findings-decision-brief.md` (A3 and A4 amendments, 2026-08-15); `backend/src/main/resources/db/migration/common/V21__create_attendance.sql:32` (the target `CHECK`). |

## D-038: The ETL Extraction Path Drops The Stdlib-Only Rule And Takes A Driver Dependency

| Field | Value |
|---|---|
| Decision | `scripts/etl/export_legacy.py` gains a `--extract` mode that **connects to the legacy database with a real driver** (PyMySQL, pinned in the new `scripts/etl/requirements.txt`) and writes CSV directly, replacing the documented `mysql --batch --raw` redirect procedure. This **overrides this repository's stdlib-only tooling rule**, which the script's own docstring previously cited as the reason it emitted SQL instead of connecting ("stdlib-only by rule, so it must run on an operator's machine near production with no network installs and no MySQL driver"). The override is **scoped to `--extract` alone**: PyMySQL is imported lazily inside the extraction function, so `--print-sql`, `--manifest` and `--self-test` keep running on a bare Python install with zero dependencies, and a self-test asserts PyMySQL is never imported merely by loading the module. |
| Status | Accepted |
| Owner | Repository owner (human requester) — directed the redesign to driver-based extraction, 2026-08-15. |
| Related ADR | None. This is a tooling-dependency policy change, not an architectural one; it does not affect `backend/`, which has always had real dependencies. |
| Reason | **The prepared "Fix A" in `2026-08-13-etl-real-data-findings-decision-brief.md` cannot work.** It proposed MySQL's `SELECT ... INTO OUTFILE`; live production has `secure_file_priv = /dev/null/`, which disables `INTO OUTFILE` outright. It had appeared viable only because it was tested against a local Docker MySQL container where that variable was controllable. Independently, `INTO OUTFILE` writes to the **database server's** filesystem, and production is managed shared hosting — the file could never be retrieved even if permitted. The originally documented `--batch --raw` method was separately broken: `--raw` disables escaping, so the 29 `requests.notes` and 6 `requests.reply` rows containing embedded newlines (live, 2026-08-15) corrupt row boundaries for any line-based reader, and NULL renders as the literal 4-character string `NULL` rather than `\N`. **No connectionless extraction path exists against this host**, so the rule and the requirement were in direct conflict and the rule gave way. |
| Impact | Python's `csv` module now handles quoting and embedded newlines, which makes both corruption classes **structurally impossible** rather than re-verified per snapshot — retiring the brief's own caveat that its proposal depended on "this snapshot has no embedded double-quote characters." Credentials come from the environment only (`DB_HOST`/`DB_USER`/`DB_PASS`/`DB_NAME`), never a CLI argument, never written to disk. Read-only is enforced in two layers: the statement classifier refuses anything not starting with `SELECT`/`SET`, and the session runs under `SET SESSION TRANSACTION READ ONLY`. **The server-side half was verified against live production 2026-08-15** — an attempted `CREATE TEMPORARY TABLE` was rejected with `(1792, 'Cannot execute statement in a READ ONLY transaction')`. That layer is not decorative: the credential supplied for this work holds `GRANT ALL PRIVILEGES` on the schema despite being described as read-only, so the session-level restriction is the only thing making the extractor structurally unable to write. Also verified live: `SET time_zone = '+00:00'` applies to the session, and temporal columns return as `str` (the driver's DATE/DATETIME/TIMESTAMP converters are disabled), so THE TIMEZONE RULE holds by construction rather than by reasoning about driver datetime semantics. |
| Follow-up | An operator running a real extraction must now `pip install -r scripts/etl/requirements.txt` first — `scripts/etl/README.md` is updated. **The `--extract` path has never been run end to end against a live database**: only its pure functions, a mocked-connection integration script, and the four live session-level checks above have been exercised. A first real run remains outstanding and is gated on the same write freeze as the full ETL rerun (D-037). `MANIFEST`'s static `expected_count` values are retained only as a historical baseline — `build_measured_manifest()` now produces the manifest an actual run hands to `migration_diff.py`, because every static count was stale within twelve days (D-037). |
| Evidence | `scripts/etl/export_legacy.py` (`--extract`, `run_extraction`, `_classify_statement`, `build_measured_manifest`, and its extended `--self-test`); `scripts/etl/requirements.txt`; `scripts/etl/README.md`; live production checks 2026-08-15 (`secure_file_priv`, `SHOW GRANTS`, read-only transaction rejection, session time zone); `docs/migration/2026-08-13-etl-real-data-findings-decision-brief.md` §"Fix A" (the superseded `INTO OUTFILE` proposal). |

## D-039: The ETL Ran End To End Against Real Data For The First Time; Reconciliation Cannot Yet Distinguish A Deliberate Transform From A Defect

| Field | Value |
|---|---|
| Decision | A full migration rehearsal was run on 2026-08-15 in an isolated local PostgreSQL 17.6 (portable binaries, no Docker, no admin, port 15432) against real data extracted read-only from live production: all 45 Flyway migrations applied, the complete ETL loaded, and `scripts/migration_diff.py` reconciled all 21 tables. **The load completed cleanly** — the first time it has ever done so. `docs/migration/2026-08-13-etl-next-steps-punch-list.md`'s standing verdict, "the load does not complete cleanly", is superseded as of this run. Reaching that required implementing four decisions that had been **recorded but never built**: D-035's A2, A3, A5 and A6. Each one aborted the load in turn and was only discovered by running it — no amount of static self-testing had surfaced them, because every self-test asserts against emitted SQL text rather than against a database. |
| Status | Accepted |
| Owner | Repository owner (human requester) — directed the rehearsal on isolated local PostgreSQL and explicitly ruled out cloud Postgres holding production PII, 2026-08-15. |
| Related ADR | None. This validates existing decisions rather than making architectural ones. |
| Reason | D-037 established that the findings needed re-verification and that the load had never run past a scratch-patched state. The owner directed a rehearsal to validate V41–V45, the catalog load, the full extraction/load flow and reconciliation before any cutover, and to plan cutover as a single controlled write-freeze window rather than relying on repeated remediation during a phased one. |
| Impact | **Verified against real data, not asserted**: 45 migrations apply cleanly including V41–V45; `leave_balances_year_check` accepts the `0` sentinel and rejects `1500`/`-1`; a NULL-name `pending` company inserts and a bogus status is rejected (D-035/A1); the first live `--extract` produced 26 CSVs / 104,922 rows in ~5s; `requests.csv` has 392 physical lines but 342 real CSV rows, so the superseded `--batch --raw` method would have mis-parsed by **50 rows** (D-038); all extracted files are rectangular. Post-load: **0** surviving attendance XOR violations and **0** preserved punches left without a `method` (A3); 16 `year = 0` rows carried with `created_at` drawn from real employee timestamps rather than the load clock (A4-superseded, D-037); **0** orphaned `exception_types` id_map rows with the exclusion count recorded in `migration.load_counts` (A6); catalog (6 definitions, 81 allowed values) and the 122-row `company_settings` pivot both populated without the catalog cannibalising the pivot. Reconciliation compared **all 21 tables** — before Fix J, 18 of them failed on header mismatch without a single cell compared. |
| Follow-up | **New gap, not previously known: `migration_diff.py` cannot distinguish a deliberate transformation from a defect.** Seven tables report cell mismatches that are all correct-by-design — `UPPER()` enum normalisation (`status`, `role`, `salary_mode`), MySQL `tinyint` to Postgres `boolean` (`counts_as_paid_leave` `1`/`0` becoming `t`/`f`), `COALESCE(...,0)` (`daily_wage` `\N` becoming `0.00`), A2/A5's `effective_from` repair, and exception-only rows carrying no `method`. Reconciliation therefore produces roughly a hundred expected findings, and a genuine regression would be buried among them. The tool needs a declared set of expected per-column transformations so that only *undeclared* differences are findings; deciding that declaration is outstanding work. **Also still outstanding**: D-035's shared per-row remediation/audit output — A2/A3/A5/A6 all require each repair or exclusion be recorded individually, and only aggregate counts exist today; the `employees` INSERT still does not populate the six D-036 business fields that V42 added; and `--extract`'s live run has not been repeated under a write freeze. **Cutover plan, owner-directed: a single controlled write-freeze window**, which makes the ongoing legacy defects (E and F, `hr-legacy` #28/#29) non-blocking because the remediation runs once against a final set. |
| Evidence | Rehearsal on isolated PostgreSQL 17.6, 2026-08-15; `scripts/etl/load_postgres.py` (A2/A3/A5/A6 implementations and their self-test assertions); `migration.load_counts` artifact including `exception_types_excluded_orphaned_company = 3`; reconciliation output over 21 tables; live production reads (read-only) for extraction. |

## D-040: Strategy Reset — Implementation First, Storage Second, Modernization Third

| Field | Value |
|---|---|
| Decision | The repository owner reset the migration sequencing on 2026-08-16: **first replace PHP with Java without changing the storage contract, then migrate storage, then modernize.** The rewrite had been running six transformations concurrently (PHP→Java, MySQL→PostgreSQL, schema redesign, data cleanup, business-rule correction, modernization), which made every failure ambiguous. Phase 1 now runs the full Java backend against the **existing legacy MySQL schema** — same tables, columns, meanings, relationships, ids and representations — with no PostgreSQL dependency and no ETL required to launch. Three scope calls were taken with the discovery numbers visible: **strict legacy API contract parity**; **full 38-module replacement**, explicitly not a strangler (PHP and Java never share ownership of a module); and **freeze, do not delete** the PostgreSQL/ETL work. Read compatibility is separated from write correctness — Java must read the malformed legacy values that exist, must not generate new ones, and every intentional difference must be explicit, justified and tested. |
| Status | Accepted |
| Owner | Repository owner (human requester) — directed the reset and the three scope calls, this conversation, 2026-08-16. |
| Related ADR | `docs/adr/ADR-0011-phase-sequencing.md` (new). Amends the implicit ordering in ADR-0002, ADR-0004, ADR-0005; ADR-0003's contract promise becomes blocking rather than documentation debt (`hr-platform#72`). |
| Reason | Concurrency had already produced four decisions recorded but never built (D-035 A2/A3/A5/A6), discovered only by running the ETL (D-039) — the signature of a plan too wide to verify. Sequencing one transformation at a time makes `PHP + MySQL` vs `Java + MySQL` a single-variable comparison, and keeps rollback to PHP available for the whole of Phase 1 because the storage contract never changes. |
| Impact | Repository-wide discovery (`workin-phase1-strategy-reset.html`) established that the Java application is **not** coupled to PostgreSQL as a dialect — all 31 entities use portable `GenerationType.IDENTITY`, zero `columnDefinition`, zero `nativeQuery`, and exactly one PostgreSQL-only statement in 242 files — but **is** coupled to a redesigned schema: 11 tables absent from legacy, `company_settings` a name collision with an incompatible shape, `leave_balance` renamed, `is_active` become `active`. 19 of 38 legacy modules and 23 of 42 legacy tables are unbuilt and move onto the Phase 1 critical path, which roughly doubles remaining Phase 1 engineering versus the previous trajectory. Existing work splits three ways — business logic largely survives (`PayrollCalculationService` is a verified line-referenced port; `docs/legacy/` holds 1,049 lines of evidence-backed rules), the identity/tenancy/authorization stack needs rework, the PostgreSQL/ETL assets freeze. **No artifact was classified obsolete.** |
| Follow-up | ADR-0003, ADR-0004, ADR-0005, ADR-0010 and ADR-0002 each need amending to state which phase they belong to. `docs/migration/`'s sequencing artifacts need re-framing as Phase 2. The Phase 1 authentication contract (legacy 10-year JWT vs ADR-0005's short-lived tokens) is **not resolved** — strict contract parity argues one way, ADR-0005 the other. The Phase 1 acceptance threshold is undecided. |
| Evidence | Discovery against `hr-platform` @ `da853f1` and `hr-legacy` @ `d113204`; legacy login behaviour read at `hr-legacy/apis/api/auth/login_employee.php:18-48,:90-107`; `company_settings` divergence at `V27__create_company_settings.sql` vs `mysql_workin.schema.sql:262-267`; first Phase 1 slice merged green (`#100`, `cfef222`) with 298 tests, 0 failed, 0 skipped including 5 MariaDB-backed adapter tests. |

## D-041: Phase 1 Tenant Isolation Is Application-Enforced, With Compensating Controls

| Field | Value |
|---|---|
| Decision | While Phase 1 runs against MySQL, tenant isolation is **enforced in the application and verified by tests**, because MySQL and MariaDB have no row-level security and D-040's Database Rule forbids changing production storage to compensate. Four mandatory parts, all load-bearing: **(1)** one enforcement point — a Hibernate filter activated per transaction from the authenticated `AuthorizationContext`, not a predicate remembered at each call site; **(2)** the context is the only source of the tenant id, so a request-supplied company id never reaches the filter; **(3)** fail closed and prove it — every `RlsFailClosedTest`/`TenantContextIsolationTest` scenario ported assertion-for-assertion, a build-failing architecture test that every tenant-owned repository read is scoped, and a test that a query with no scope established **denies** rather than returning all tenants' rows; **(4)** the gap is stated in this log, the ADR, the threat model and the Phase 1 exit criteria. Adopting the filter without the controls is explicitly not this decision. The RLS migrations, two-DataSource split and `SuperuserStartupCheck` are **frozen, not deleted** — profile-gated so they stay compiled — and Phase 2 returns to them. |
| Status | Accepted |
| Owner | Repository owner (human requester) — directed temporary application-enforced tenant isolation on MySQL and required the posture, compensating controls and fail-closed tests be defined as part of the auth/authz work rather than as a follow-up, 2026-08-16. |
| Related ADR | `docs/adr/ADR-0012-phase-1-tenant-isolation.md` (new). Scopes ADR-0002 Part B and ADR-0010 Dimension 7 to Phase 2 rather than reopening the tenant-isolation pattern. |
| Reason | The 12 `db/migration/rls/` migrations, the non-superuser runtime role and `SuperuserStartupCheck` have no MySQL analogue, and there is no partial substitute at the database layer. This is **not** a regression against production — the PHP system Phase 1 replaces enforces scoping entirely in application code with no database backstop — but it **is** a regression against what the Java system currently has, so it needs a recorded decision and named controls rather than being a side effect of the datasource swap. |
| Impact | Defence in depth is reduced for the duration of Phase 1: a query written without scope is caught by an architecture test rather than refused by the database. On a multi-tenant HR system holding salaries, national ids and attendance, the blast radius of a miss is cross-tenant exposure — which is what every compensating control above is justified by. `docs/security/threat-model.md` currently describes RLS as the control and must record the Phase 1 posture and its expiry. `hr-platform#74` becomes moot for Phase 1 (no table has RLS) and returns unchanged in Phase 2. |
| Follow-up | Phase 2 should **keep** the filter alongside restored RLS — two independent controls, rather than one replacing the other — but that is a Phase 2 decision, noted here so it is not settled by omission. Open: whether to also log queries executed without a tenant scope as a detection control; whether the architecture test should reject service methods taking a raw `companyId`; and whether any legitimate cross-tenant read exists in the legacy contract besides the deliberately pre-tenant login-by-phone lookup. |
| Evidence | RLS surface measured directly — 12 migrations (V5, V6, V14, V19, V22, V24, V26, V28, V30, V32, V34, V39), `RlsDataSourceConfig.java:24-59`, `SuperuserStartupCheck.java:13-38`, `TenantSessionVariable.java:29-34`; the sole PostgreSQL-only application statement confirmed by inspecting all 11 `createNativeQuery` sites; MariaDB 11.8 exercised in CI (`#100`, `cfef222`) and providing no RLS feature to target; `hr-platform#74` as the documented decay mode; `AuthorizationPolicyArchTest` as in-repo precedent that a build-failing architecture test works here. |

# Risk Register

Each risk is tracked with: Risk ID, Description, Category, Probability,
Impact, Severity, Owner, Mitigation, Trigger, Contingency, Status, Target
Date, Evidence, and Last Reviewed. Only known bootstrap-phase risks are
listed below — no production facts, legacy behavior, or migration specifics
are asserted, since no Discovery evidence exists yet for those areas.
Severity is Probability x Impact, rated qualitatively (Low / Medium / High).

## R-001: Legacy False Assumptions

| Field | Value |
|---|---|
| Description | Discovery may incorrectly simplify PHP behavior or production-only coupling. |
| Category | Discovery / Legacy |
| Probability | Medium |
| Impact | High |
| Severity | High |
| Owner | Legacy PHP Analyst (analysis); human engineering lead (acceptance) |
| Mitigation | Require production-behavior evidence (`docs/legacy/production-behavior-evidence.md`) before treating any legacy behavior as understood; separate fact from hypothesis per `CLAUDE.md` |
| Trigger | A legacy behavior is documented as fact without a cited evidence source |
| Contingency | Revert the affected assumption to an Open Question in `docs/legacy/`, redo discovery for that area before it feeds an ADR |
| Status | Open — Discovery not yet started |
| Target Date | Revisit at Discovery kickoff |
| Evidence | None yet — `docs/legacy/` templates exist and are unpopulated |
| Last Reviewed | 2026-08-02 |

## R-002: Flutter Contract Drift

| Field | Value |
|---|---|
| Description | API changes may break mobile or desktop Flutter clients if compatibility evidence is weak. |
| Category | API / Compatibility |
| Probability | Medium |
| Impact | High |
| Severity | High |
| Owner | Product Discovery Analyst, Solution Architect (analysis); human engineering lead (acceptance) |
| Mitigation | Populate `docs/api/existing-endpoint-inventory.md` and `docs/api/flutter-request-response-compatibility.md` before any API versioning ADR (ADR-0003) moves past Proposed |
| Trigger | An API change is proposed without a corresponding compatibility-inventory entry |
| Contingency | Block the change at review; require the inventory entry and a compatibility test before proceeding |
| Status | Open — Discovery not yet started |
| Target Date | Revisit at Discovery kickoff |
| Evidence | None yet — `docs/api/` templates exist and are unpopulated |
| Last Reviewed | 2026-08-02 |

## R-003: Migration Underestimation

| Field | Value |
|---|---|
| Description | MySQL features, procedures, or operational patterns may complicate the PostgreSQL migration more than expected. |
| Category | Migration / Database |
| Probability | High |
| Impact | High |
| Severity | High |
| Owner | Solution Architect, Legacy PHP Analyst (analysis); human engineering lead (acceptance) |
| Mitigation | Populate the full `docs/migration/` discovery template set (schema, views, events, procedures/functions, triggers, data-quality, character-set/collation, invalid-date, orphan-reference, duplicate-key, table-volume, sequence/identity, validation queries, cutover/rollback — see P2-3 remediation) before ADR-0004 moves past Proposed |
| Trigger | A migration approach is proposed citing fewer than the full discovery template set as evidence |
| Contingency | Hold ADR-0004 at Proposed; extend discovery scope; re-estimate migration effort |
| Status | Open — Discovery not yet started |
| Target Date | Revisit at Discovery kickoff |
| Evidence | None yet — `docs/migration/` templates exist and are unpopulated |
| Last Reviewed | 2026-08-02 |

## R-004: Attendance Device Variability

| Field | Value |
|---|---|
| Description | Device protocols, firmware differences, and vendor limitations may expand edge-integration scope beyond the current candidate direction. |
| Category | Integration / Devices |
| Probability | Medium |
| Impact | Medium |
| Severity | Medium |
| Owner | Solution Architect (analysis); human engineering lead (acceptance) |
| Mitigation | Populate `docs/devices/attendance-device-model-and-firmware-inventory.md` and `docs/devices/vendor-capability-matrix.md` before ADR-0006 moves past Proposed |
| Trigger | A gateway or integration pattern is chosen for a vendor without a corresponding capability-matrix entry |
| Contingency | Hold ADR-0006 at Proposed; request vendor-specific discovery before committing to a pattern |
| Status | Open — Discovery not yet started |
| Target Date | Revisit at Discovery kickoff |
| Evidence | None yet — `docs/devices/` templates exist and are unpopulated |
| Last Reviewed | 2026-08-02 |

## R-005: Governance Weakness

| Field | Value |
|---|---|
| Description | If branch rulesets, review requirements, and team ownership are not applied in GitHub before Discovery begins, unsafe practices (unreviewed merges, missing status checks) may spread before they are caught. |
| Category | Governance / Process |
| Probability | Medium |
| Impact | High |
| Severity | High |
| Owner | Human repository owner (only a human can configure GitHub org/branch settings) |
| Mitigation | Complete every item in `docs/bootstrap/manual-setup-checklist.md`, including the Human Approval And Merge Sequence, before Discovery work is merged |
| Trigger | A pull request merges into `main` without required status checks or human review |
| Contingency | Revert the merge; apply branch protection; re-audit the affected change |
| Status | Open — confirmed via this remediation: no `main` branch exists yet, and no branch protection can have been configured |
| Target Date | Before the first Discovery-phase merge |
| Evidence | `git branch -a` (2026-08-02): no `main` ref exists locally or on `origin` |
| Last Reviewed | 2026-08-02 |

## R-006: Agent Boundary Erosion

| Field | Value |
|---|---|
| Description | Without explicit, enforced constraints, planning agents may drift into implementation, or reviewers may lose independence. |
| Category | Governance / Agent Boundaries |
| Probability | Medium |
| Impact | High |
| Severity | High |
| Owner | Human repository owner; enforced in part by Claude Code subagent tool scoping (see `docs/agents/operating-model.md` Enforcement Layers) |
| Mitigation | `.claude/agents/*.md` now carry real `tools:` frontmatter restricting each planning/review agent to `Read, Grep, Glob, Bash`; `.claude/settings.json` denies destructive Git operations repository-wide; `scripts/validate_phase0.py` fails CI if an agent file's declared boundary is missing or malformed |
| Trigger | An agent file is edited to remove its `tools:` restriction, or a subagent is observed calling Edit/Write despite its declared scope |
| Contingency | Revert the agent file change; treat any observed boundary violation as a P0/P1 audit finding |
| Status | Mitigated for Claude (technical enforcement added); open for Codex (no equivalent technical mechanism exists — see `docs/bootstrap/audit-remediation.md` P1-2) |
| Target Date | Ongoing; re-verify at every bootstrap-related PR |
| Evidence | `docs/bootstrap/audit-remediation.md` (P1-2): confirmed Claude Code subagent frontmatter enforcement; confirmed Codex has no equivalent |
| Last Reviewed | 2026-08-02 |

## R-007: Governance Tooling Silently Diverges From What It Validates

| Field | Value |
|---|---|
| Description | A validator or skill script can silently stop matching the real content it is meant to check (as happened with the ADR `## Decision` / `## Proposed Direction` mismatch found in the prior audit), giving false confidence that Phase 0 checks passed. |
| Category | Governance / Tooling |
| Probability | Medium |
| Impact | Medium |
| Severity | Medium |
| Owner | Whoever changes a template, skill, or validator must run all related validators before committing (documented in `.agents/skills/create-adr/SKILL.md` step 6-7 as the pattern to follow for future templates) |
| Mitigation | Wire per-artifact validators (e.g. `validate-adr.sh`) into the master validator (`scripts/validate_phase0.py`) rather than leaving them unreferenced; run every validator locally before commit |
| Trigger | A structural validator and its target template diverge without CI catching it |
| Contingency | Fix the drifted template or validator immediately; treat the gap as a P1 audit finding, not a P2/P3 |
| Status | Mitigated for ADRs (see P1-1 in `docs/bootstrap/audit-remediation.md`); open in general as a recurring risk for any future template/validator pair |
| Target Date | Ongoing |
| Evidence | `docs/bootstrap/audit-remediation.md` (P1-1) |
| Last Reviewed | 2026-08-02 |

## R-008: Human Approval Process Remains Unexercised

| Field | Value |
|---|---|
| Description | The documented Issue -> Specification -> ... -> Human merge workflow has never actually run in this repository; both commits on this branch were authored by the same automated identity with zero merges. |
| Category | Governance / Process |
| Probability | High (currently true, not speculative) |
| Impact | High |
| Severity | High |
| Owner | Human repository owner |
| Mitigation | Follow the Human Approval And Merge Sequence in `docs/bootstrap/manual-setup-checklist.md` for the first real pull request into `main`. Branch-protection enforcement (required reviewers, required status checks, no force-push/direct-push) is explicitly Deferred, not merely pending — see D-013 in `docs/bootstrap/decision-log.md` — so until revisited, rely on temporary, non-platform-enforced mitigation instead: manual PR review before every merge, a green required CI run before every merge, restricted `main` write access limited to trusted human owners, and no direct pushes to `main` by team convention. |
| Trigger | Phase 0 is declared complete without a real, evidenced human-approved merge |
| Contingency | Do not declare Phase 0 complete; keep `docs/bootstrap/definition-of-done.md` unmet until the sequence is followed once and evidenced |
| Status | Open — classified "Pending human acceptance gate" in `docs/bootstrap/manual-setup-checklist.md`; must not be marked complete by any agent. Partially open for a second, distinct reason as of D-013: review and merge governance cannot be mechanically enforced at the platform level (branch protection is Deferred, an accepted plan limitation, not a configuration gap), so this risk depends entirely on the temporary mitigation above actually being followed rather than on any GitHub-enforced control. |
| Target Date | Before Phase 0 is declared complete |
| Evidence | `git log --all --merges` (2026-08-02): no merge commits exist; `git log --all --format="%an %ae"`: single author `Codex <codex@local>`; D-013 (`docs/bootstrap/decision-log.md`) for the branch-protection deferral |
| Last Reviewed | 2026-08-03 |

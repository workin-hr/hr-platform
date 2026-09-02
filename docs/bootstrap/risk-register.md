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
| Status | Open — documentation-level discovery completed 2026-09-02 (D-156, accepted): the connectivity pattern and protocol are documented and decided; hardware verification on the customers' actual models is the remaining step (`docs/superpowers/specs/2026-09-02-attendance-device-ingestion-design.md` §4.3). |
| Target Date | The first real terminal connected to a development receiver; before Slice A ships. |
| Evidence | `docs/devices/vendor-capability-matrix.md` and `attendance-device-model-and-firmware-inventory.md` populated from documentation evidence (evidence level marked per field, model/firmware still `Not yet discovered`); D-156. |
| Last Reviewed | 2026-09-02 |

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
| Status | Mitigated for ADRs (see P1-1 in `docs/bootstrap/audit-remediation.md`); open in general as a recurring risk for any future template/validator pair. A second, code-level instance of the same pattern was found and fixed 2026-08-25: `LegacyPhpRoutes.CONTROLLER_GUARDED` (`backend/src/main/java/com/workin/legacy/wire/LegacyPhpRoutes.java`) added `/apis/api/advances/**`, `/apis/api/penalties/**` and `/apis/api/salary_contracts/**` (all `com.workin.legacy.payroll`) on PR #120, but the paired `LegacyWireExceptionHandler.basePackages` list was not updated to match -- exactly the failure mode the handler's own javadoc warns about. `LegacyApiException` thrown from that package had no handler in scope and fell through to a raw, unenveloped 500 instead of PHP's 401/405 denial. Caught by `LegacyEmployeeReadEndToEndTest.noMappedPhpRouteAnswersAnUnauthenticatedRequest()` (which enumerates every mapped `/apis/**` route), not by any static parity check between the two lists -- the two are still not mechanically linked, so a future wave can reintroduce the same drift. Fixed by adding `com.workin.legacy.payroll` to `basePackages`; full suite green afterward, zero regressions. |
| Target Date | Ongoing |
| Evidence | `docs/bootstrap/audit-remediation.md` (P1-1); `backend/src/main/java/com/workin/legacy/wire/LegacyWireExceptionHandler.java` commit `f45dfc8` on `phase1/wave-12-complete` (PR #120), `gh run view` on the `Backend Validate` `test` job for the pre-fix failure (`LegacyEmployeeReadEndToEndTest > noMappedPhpRouteAnswersAnUnauthenticatedRequest() FAILED: /apis/api/advances/approve.php answered 500 to an unauthenticated GET`). |
| Last Reviewed | 2026-08-25 |

## R-008: Human Approval Process Remains Unexercised

| Field | Value |
|---|---|
| Description | The documented Issue -> Specification -> ... -> Human merge workflow has run once (pull request #1, merged 2026-08-03 by the repository owner — see D-014), but review and merge governance is not mechanically enforced at the platform level (branch-protection is Deferred under D-013). A single evidenced run does not guarantee every future merge follows the same process without a human continuing to follow it deliberately. |
| Category | Governance / Process |
| Probability | High (currently true, not speculative) |
| Impact | High |
| Severity | High |
| Owner | Human repository owner |
| Mitigation | **Mechanically enforced as of 2026-08-29 (D-125).** Branch protection is applied to `main`: `validate` and `independent-review` required, conversation resolution required, `enforce_admins` on, force-pushes and deletions forbidden, stale approvals dismissed on push. Two settings from the first attempt were corrected the same day and both are recorded in D-125: `test` was dropped as a required context because `Backend Validate` is path-filtered and would deadlock docs-only pull requests, and the approving-review count was dropped to `0` because GitHub forbids self-approval and a single write-access human makes `1` unsatisfiable — that count is now derived from the collaborator list and is **alerted, not applied**, when a second maintainer is added: `check-branch-protection.sh` exits non-zero and nothing changes the repository setting, and no workflow invokes it, so an operator must run the check *and* update branch protection by hand (D-142), so step 6 is un-enforced rather than abandoned. `scripts/check-branch-protection.sh` passes against the live configuration for the first time since it was written. All three of this risk's realizations were merges that procedure alone did not stop; the same sequence would now be blocked rather than regretted. The procedural mitigations below remain as the description of *how* to work, not as the only thing standing between a bad merge and `main`. Historically: **mechanical enforcement is what this risk needed; every mitigation below is procedural, and PR #126 showed procedure losing a ten-second race.** The mechanism has to be chosen carefully: a **required approving review does not help here**, because the named independent reviewer is read-only and cannot approve (`docs/agents/responsibility-matrix.md`), so a human approval satisfies that count while a review round is still in flight — exactly PR #126. Two settings are needed, and each covers what the other misses. **`required_conversation_resolution`** blocks a merge while threads are open — but it reports nothing when the reviewer has not posted yet, and nothing after fixes are pushed and the previous head's threads are resolved, so on its own it does not prove the *final* head was reviewed. The **`independent-review` status check** (`.github/workflows/independent-review-gate.yml`) covers exactly that: it fails until the named reviewer has submitted a round on the pull request's current head SHA, for every open pull request pointing at that commit. **Stated precisely, because the obvious summary overclaims:** together they prove *a round happened on this head* and *no thread is left open*. They do **not** prove the findings were addressed. Thread resolution is a state anyone with write access can set, including the author, without changing a line or answering — so a pull request can satisfy both settings with every finding ignored. Step 7 of the merge sequence is therefore still a **human** obligation, and no mechanical signal in this repository currently verifies it. Closing that would need a qualifying-answer check (a reply per resolved thread, or a clean follow-up round) which does not exist yet and is not proposed here; **an owner decision is owed on whether to build one.** Until then, do not read a green merge box as evidence that findings were handled. `scripts/check-branch-protection.sh` requires conversation resolution alongside the review count, `enforce_admins`, no force pushes, and the required status check, so whenever D-013's deferral is revisited the protection applied is verified against the failure that actually occurred. `scripts/check-branch-protection.sh` requires **both** contexts, so protection that carries only the validation check no longer reports as complete. **Done 2026-08-29 (D-125).** The `independent-review` status is now a required context on `main`. Its precondition was cleared by D-122, and D-013's deferral was superseded once `hr-platform` became public — Free has always offered branch protection on public repositories, so the half of D-013's premise that was about visibility no longer held. It previously carried a second blocker: the gate ran on `pull_request`, so a run executed the workflow file *from the pull request* while holding `statuses: write`, and a revision could have published the status green before the named reviewer saw that change — the gate certifying its own bypass. **D-122 (2026-08-28) resolved that**: the workflow moved to the privileged `_target` trigger, which runs the base branch's trusted copy, under a conditional exception that `validate_workflow_safety()` withdraws the moment the file gains a checkout. Making the context required is therefore once again purely the D-013 question, not two questions. Until then: follow the Human Approval And Merge Sequence in `docs/bootstrap/manual-setup-checklist.md` for the first real pull request into `main`. Branch-protection enforcement (required reviewers, required status checks, no force-push/direct-push) is explicitly Deferred, not merely pending — see D-013 in `docs/bootstrap/decision-log.md` — so until revisited, rely on temporary, non-platform-enforced mitigation instead: manual PR review before every merge, a green required CI run before every merge, restricted `main` write access limited to trusted human owners, and no direct pushes to `main` by team convention. **Strengthened 2026-08-28 by D-121**, which names the independent reviewer the workflow previously left unassigned: `chatgpt-codex-connector[bot]` reviews the whole pull request, its findings are addressed or answered on the thread before merge, and its quota being exhausted (R-009) makes the gate unavailable rather than waived. This closes the specific gap that produced the PR #120 deviation; it remains convention rather than platform enforcement, so the risk stays Open. |
| Trigger | Phase 0 is declared complete without a real, evidenced human-approved merge; or a pull request merges to `main` **without a completed independent review round on its final head whose findings have all been fixed or answered** — which covers both no round at all (PR #120) and a round that had posted but whose findings were still unaddressed at merge (PR #126) |
| Contingency | Do not declare Phase 0 complete; keep `docs/bootstrap/definition-of-done.md` unmet until the sequence is followed once and evidenced |
| Status | Open — Accepted Residual Risk / Non-blocking. The manual review-and-merge sequence (steps 2–10 of the Human Approval And Merge Sequence in `docs/bootstrap/manual-setup-checklist.md`) has run and is evidenced by D-014; mechanical branch-protection enforcement (step 1) remains Deferred under D-013, an accepted plan limitation, not a configuration gap. This risk stays Open because review and merge governance still cannot be mechanically enforced at the platform level and depends on the temporary mitigation above actually being followed — but per D-015 it no longer blocks Phase 0 completion; it is tracked here as an accepted, non-blocking residual risk, not an open blocker. **Realized twice.** *2026-08-27, PR #120*: the merge proceeded on the owner's own approval with no independent review exercised as a distinct gate, recorded in `docs/legacy/WAVE12_COMPLETION_AUDIT.md`. Corrective action: D-121 named the reviewer the workflow had left unassigned. *2026-08-28, PR #126*: the round existed but its findings did not survive to the merge — six findings, four P1, posted ten seconds before the squash. The first realization showed the gate had nobody assigned; the second showed that assigning somebody is not sufficient while nothing mechanically blocks the merge. Both are this risk's stated mitigation failing in exactly the way it predicts. Reviewed 2026-08-28. |
| Target Date | Before Phase 0 is declared complete |
| Evidence | Historical baseline: `git log --all --merges` (2026-08-02) found no merge commits and `git log --all --format="%an %ae"` found a single author (`Codex <codex@local>`), before pull request #1 existed. Current: pull request #1 (`https://github.com/workin-hr/hr-platform/pull/1`), merge commit `cf997818fbabb6f02f9b15c845da06757713a97a`, merged by Karim Taha (`karimtismail`) on 2026-08-03 — see D-014 (`docs/bootstrap/decision-log.md`) for the full record. D-013 (`docs/bootstrap/decision-log.md`) for the branch-protection deferral. **Second instance, 2026-08-27**: pull request #120 (merge commit `4caff98`) merged on the owner's own approval with no independent review round; recorded in `docs/legacy/WAVE12_COMPLETION_AUDIT.md` under "Validation outcome". Corrective action: D-121 (`docs/bootstrap/decision-log-wave12r.md`) names `chatgpt-codex-connector[bot]` as the independent reviewer. **Step 6's evidence is missing for PRs #126-#129 (2026-08-28)**: GitHub records zero `APPROVED` reviews on any of the four, so D-123's merge record leaves the approving-human field open rather than inferring it from the merger. Whether that is a third realization or simply an unrecorded-but-performed approval is the open question D-123 states; the register does not guess between them. **Third instance, 2026-08-28, PR #126** — a race rather than an omission, and the reason this mitigation needs a mechanical component: the review round on head `1a3a190` posted at 06:49:16 with six findings including four P1s, and the squash merge (`2f67c47`) completed at 06:49:26, ten seconds later. Both required checks were green and the pull request had already been through three review rounds, so nothing in the GitHub UI signalled a round was in flight. The six findings were fixed on the branch as `16ec1a2` and land in a follow-up pull request; `main` carried them in the meantime. |
| Last Reviewed | 2026-08-29 |

## R-009: CI And Automated Review Depend On Two Externally-Billed Accounts With No Balance Alerting

| Field | Value |
|---|---|
| Description | Both required GitHub Actions checks (`Backend Validate`'s `test`, `Phase 0 Bootstrap Validate`'s `validate`) and the Codex automated code-review bot (`chatgpt-codex-connector[bot]`) depend on org- or account-level billing/quota that has no low-balance alert wired to a human. Both silently stopped working within the same working day with no repository-side symptom other than the check or review simply not running. |
| Category | Governance / Tooling / CI |
| Probability | High (currently true, not speculative) |
| Impact | High. **The two quotas are on separate accounts and fail independently — each blocks merging, but through a different missing capability and with a different remedy.** *GitHub Actions billing exhausted (org `workin-hr`):* no required check produces a real pass/fail; Codex review still runs, so a pull request can be fully reviewed and still unmergeable for want of a green check. *Codex review quota exhausted (the ChatGPT/OpenAI account):* CI still gives a real signal, but no independent review round is possible, and since **D-121 (2026-08-28)** makes Codex the named reviewer, the workflow gate is *unavailable* rather than waived — affected pull requests are **unmergeable**, not merely unreviewed. Both exhausted at once produces both. Measured during the 2026-08-24/27 outage, when both were out together: five simultaneously blocked pull requests (#117, #118, #119, #120, #121). Both were restored on 2026-08-27 and nothing is blocked today, which is why this row is stated conditionally rather than in the present tense. |
| Severity | High |
| Owner | Human repository/org owner (GitHub Actions billing is an org-level `workin-hr` setting `gh api` cannot read or fix without `admin:org` scope, and cannot be fixed by any in-repo change even with that scope; Codex usage is billed on a separate ChatGPT/OpenAI account) |
| Mitigation | **GitHub Actions**: a human with billing access must open `github.com/organizations/workin-hr/settings/billing` and clear the failed payment or raise the spending limit; every run since then fails in 1-3s with the same job-annotation, so no further diagnosis is possible from inside the repo. **Codex reviews**: a human must open `https://chatgpt.com/codex/cloud/settings/usage` and either upgrade the account or add and enable credits for code review under `.../settings/code-review`. Until both are cleared, treat every "all checks failing" / "no review comments" PR state as this risk, not as a real code or CI-config regression — confirm via `gh run view <id>` for the exact annotation text before assuming otherwise. |
| Trigger | A GitHub Actions check fails in under ~5 seconds on every job across every open PR regardless of what changed; or a PR's only Codex activity is the literal "You have reached your Codex usage limits for code reviews" issue comment instead of inline findings |
| Contingency | Do not attempt to work around either quota in-repo (no self-hosted-runner fallback, no disabling of required checks, no silently merging past a red/quota-blocked check) — wait for the human mitigation above, then re-run the affected checks (`gh run rerun <id>`) and re-request Codex review |
| Status | **Realised 2026-08-30 — no longer a forecast.** The Codex quota ran out mid-review and stayed out; the owner lifted `independent-review` from `main`'s required contexts and twelve pull requests merged without an independent round (**D-142**). This risk was written about billing; its actual cost was a governance exception covering the entire Item 13 port. Reassess severity accordingly: the mitigation is **restoring or funding the named reviewer**, not monitoring. **Not a substitute reviewer** — D-121 names `chatgpt-codex-connector[bot]` specifically, and the canonical workflow's rule is that quota exhaustion makes the gate *unavailable, not substitutable and not waived: the merge waits*. Reading this row as licence to appoint another reviewer and merge is how D-142 happens a second time. Changing who may satisfy the gate requires a separately approved policy change that updates the canonical workflow first. |
| Target Date | As soon as a human with billing access on both accounts is available |
| Evidence | `gh run view 32828590627` (PR #121, `Phase 0 Bootstrap Validate`) and `gh run view 32813684440` (PR #120, `Backend Validate`) both show the identical annotation: *"The job was not started because recent account payments have failed or your spending limit needs to be increased. Please check the 'Billing & plans' section in your settings."* `gh api /orgs/workin-hr/settings/billing/actions` returns `410` (endpoint moved) and a follow-up attempt needs `admin:org` scope this session's token does not have, confirming the fix is out of `gh`'s and this session's reach entirely. `gh api repos/workin-hr/hr-platform/issues/117/comments` and `.../121/comments` both show only the Codex usage-limit bot comment, no inline review. `gh run list --json ... --jq 'select(.name=="Backend Validate")'` gives the full success/failure timeline used for the trigger window above. **Cleared by 2026-08-27**: both required checks are green again on pull request #126 (`Backend Validate` run `33092550732`, `Phase 0 Bootstrap Validate` run `33092550642`) and Codex produced four inline review rounds on the same pull request, so both quotas were restored by a human outside this repository. The risk stays Open because neither account has low-balance alerting wired to a human, which is what the risk actually describes; nothing prevents a recurrence. |
| Last Reviewed | 2026-08-28 |

## R-010: `assets` Reproduces An Unenforced `hr_permissions` Flag

| Field | Value |
|---|---|
| Description | The five `assets` endpoints enforce **no** `hr_permissions` check server-side, while the desktop client gates its Assets screen on `hrPermission: HrPermissionFlag.assets`. The Phase-1 Java port reproduces that faithfully (D-058, D-130), so the flag remains advisory rather than enforced after cutover. **Scope is narrower than it first appears**: the three write routes are `requireAuth([COMPANY_ADMIN, HR])`, so MANAGER and EMPLOYEE are refused — what is unenforced is only the finer-grained flag, meaning an admin or HR user with `can_assets` unset is hidden the screen by the client and served by the server. A privilege gap within an already-privileged role, not open access. |
| Category | Security / Authorization / Legacy parity |
| Probability | High — it is current behavior in both systems, not a hypothetical. |
| Impact | Low-to-moderate, **but the responses do carry personal data** — an earlier revision of this row wrongly said they did not. Every asset row is joined to its employee and returns `employee_name` and `employee_code`, and the list additionally returns `photo_url`. So a `can_assets`-unset admin or HR user reads employee identifiers and a photo URL, not only equipment text. What bounds it is the audience rather than the content: the exposure stays inside one tenant and is reachable only by roles already trusted with the rest of that tenant's HR data, including the employee directory itself. The realistic harm is an administrator acting outside an internal separation of duties their UI implies is enforced. |
| Severity | Low-to-moderate |
| Owner | Governed by **D-044** (Phase 1 reproduces `hr-legacy#8`'s enforcement gap) and **D-045** (no `can_*` flag is enforced unless the endpoint's PHP enforces it — `can_assets` is listed among the fifteen never used as a gate). D-130 records the endpoint-specific evidence under those decisions rather than making a new one. The underlying legacy defect is tracked upstream as `hr-legacy#8`. |
| Mitigation | **None applied in Phase 1, deliberately.** Enforcing the flag in Java alone would make the two systems answer differently for the same request, which is what the phase exists to prevent, and would mask rather than resolve the legacy gap. The fix is a legacy change first (`hr-legacy#8`) and a port second. Until then the mitigation is organizational: `can_assets` should not be relied on as a security boundary, only as a UI convenience. |
| Trigger | A `can_assets`-unset admin or HR account successfully calling `assets/create`, `assets/update` or `assets/delete`; or any requirement that treats `can_assets` as an enforced control. |
| Contingency | If the flag must become enforceable before `hr-legacy#8` lands, change **legacy first** and port the change in the same wave, so the two systems stay comparable. Do not add the check to the Java side alone. |
| Status | Open — accepted residual, non-blocking for Phase 1. Recorded rather than mitigated. |
| Target Date | None while Phase 1 runs: the fix is `hr-legacy#8`, an upstream change this repository does not own, and closing it here first would create the divergence the phase exists to prevent. Reviewed with each Item 13 wave that touches an unenforced module, and re-dated the moment `hr-legacy#8` is scheduled. |
| Evidence | `hr-legacy@d113204` `apis/api/assets/*.php` — no `require_hr_permission()` call in any of the five files; `requireAuth([COMPANY_ADMIN, HR])` on `create`/`update`/`delete`. Client: `workin_desktop/lib/presentation/layouts/main/controllers/main_desktop_provider.dart:82`. Discovery: `docs/migration/2026-08-29-c3-c8-bounded-discovery.md`. Decision: D-130. Upstream: `hr-legacy#8`. |
| Last Reviewed | 2026-08-29 |

## R-011: An Unauthenticated, Unthrottled Public Write Accepts And Stores PII

| Field | Value |
|---|---|
| Description | `complaints/create.php` accepts a complaint with **no authentication**, stores the caller-supplied `name`, `phone` and `message`, and applies **no rate limit, throttle or spam control** of any kind. When no token is present the row is written with `company_id = NULL` and `employee_id = NULL`. The Phase-1 Java port reproduces this faithfully (D-058, D-132). |
| Category | Security / Abuse / Data protection |
| Probability | Moderate. It requires only that someone find the route; it is reachable from the public internet with a single POST and no credential. |
| Impact | Moderate. Three distinct exposures, none of them catastrophic on its own: **(1) unbounded writes** — no cooldown or cap, unlike the OTP endpoints which have both, so the table is an open write target; **(2) unowned PII** — a name and phone number with no `company_id`, so no tenant-scoped deletion or subject-access path reaches it; **(3) invisibility** — `list.php` filters `company_id = ?`, so anonymous rows are unreadable through the API and accumulate unnoticed. No existing data is exposed and no tenant boundary is crossed. |
| Severity | Moderate |
| Owner | Repository owner. Ported as-is by explicit decision on 2026-08-29 (D-132); the open questions are recorded in `docs/bootstrap/open-questions.md`. |
| Mitigation | **None applied in Phase 1, deliberately.** Adding a rate limit or a retention rule in Java alone would diverge from legacy, which is what this phase exists to prevent. The change belongs in legacy first and the port second. What is applied is visibility: the route is called out explicitly in `LegacyPhpRoutes` as the only public entry that mutates, so it cannot be mistaken for the read-only public routes beside it. |
| Trigger | Growth in `complaints` rows with `company_id IS NULL`; any abuse report naming the endpoint; or a data-protection request that cannot be satisfied because a row has no owning tenant. |
| Contingency | Rate limiting has an existing pattern to copy — `otp_assert_can_send()`'s per-phone cooldown and per-phone/per-IP hourly caps. Retention needs a decision first (see open questions), since deleting rows nothing can currently read is a product question, not a technical one. Change legacy first in both cases. |
| Status | Open — accepted residual, non-blocking for Phase 1. Recorded rather than mitigated. |
| Target Date | Gated on the owner answering the readability question in `docs/bootstrap/open-questions.md`: until it is known whether an anonymous complaint is meant to be readable at all, neither a retention rule nor a rate limit can be specified. Re-dated when that answer lands. |
| Evidence | `hr-legacy@d113204` `apis/api/complaints/create.php:21` — `if ($auth = getAuth())`, optional rather than required; no `otp_assert_can_send()` or equivalent anywhere in the file. `apis/api/complaints/list.php:16` — `c.company_id = ?`. Schema: `complaints.company_id` is nullable. Regression: `anAnonymousComplaintIsStoredAndThenUnreachableThroughTheApi` asserts both halves. Decision: D-132. |
| Last Reviewed | 2026-08-29 |

## R-012: A Confirmed Cross-Tenant Disclosure Ships On Two Surfaces — Parity Accepted, Residual Tracked

| Field | Value |
|---|---|
| Description | `workforce_planning` joins `branches`, `departments` and `job_titles` on id alone with **no tenant predicate**, while two of its three write paths (`save_target.php`, `update.php`) store caller-supplied foreign ids without ownership validation. A `company_admin` or `hr` user of company A can therefore write company B's `branch_id`/`department_id`/`job_title_id` into their own planning row and read company B's **names** back from `list.php`. **The same unscoped department join is in `dashboard/stats.php:91-99`**, which additionally returns that department's **active headcount** through a correlated subquery. `workforce_planning.department_id` carries no foreign key (`mysql_workin.schema.sql:939-948`), so nothing in the schema prevents the mismatched row. The Phase-1 Java port reproduces both faithfully under D-058. |
| Category | Security / Tenant isolation / Legacy parity |
| Probability | High — two ordinary authenticated API calls, no timing or special conditions. The `stats.php` surface needs no write of its own once such a row exists. |
| Impact | Medium. Names of another tenant's branches, departments and job titles, enumerable by iterating ids; plus an aggregate active headcount per department from `stats.php`. No per-employee, payroll or personal data crosses. The harm is competitive intelligence and the breach of an isolation guarantee customers reasonably assume, not exposure of individuals. |
| Severity | Medium |
| Owner | Repository owner — **decided 2026-08-30 (D-141)**: parity stands. D-131 is `Accepted`. The entry stays open as a tracking row against `hr-legacy#33`, not as a pending decision. |
| Mitigation | **None applied, deliberately.** Fixing it in Java alone would make the two systems answer differently for the same request, which is what Phase 1 exists to prevent, and would mask rather than resolve the legacy defect. Filed upstream as `hr-legacy#33`, which must cover **both** surfaces or the port cannot follow it. |
| Trigger | Any cutover of `workforce_planning` or `dashboard/stats.php`; any customer or audit question about tenant isolation; `hr-legacy#33` being scheduled. |
| Contingency | Two options, and they are not symmetric: accept the disclosure on both surfaces for Phase 1, or hold **Item 13 as a whole**. Holding PR #141 alone does **not** work — `stats.php` ships in Wave 13.5, below 13.4b in the stack — and would give the appearance of waiting for a fix while still shipping the vulnerable route. |
| Status | **Accepted 2026-08-30 by the repository owner** — parity stands on both surfaces, Item 13 is not held. Recorded under the owner's general direction that the port reproduce legacy for *any* such issue: *"i need java to be like php fot fix any issue"*, given alongside an explicit parity ruling on R-016. **The owner named R-016, not this risk**, so this acceptance is an application of the general rule rather than a ruling on this entry by name — flagged here so it can be corrected if that is not what was meant. What is accepted: a `company_admin`/`hr` user of one tenant can read another tenant's branch, department and job-title **names** via `workforce_planning/list.php`, and a foreign department's name and **active headcount** via `dashboard/stats.php`. See D-141. |
| Target Date | No longer blocking — the decision is made. The entry stays open as a **tracking** row against `hr-legacy#33`, which must fix both surfaces before the port can follow. |
| Evidence | `hr-legacy@d113204` `apis/api/workforce_planning/{save_target,update,list}.php` and `apis/api/dashboard/stats.php:91-99`, against `mysql_workin.schema.sql:939-948`. Regression: `LegacyWorkforcePlanningEndToEndTest#saveTargetLeaksAnotherCompanysBranchNameThroughTheUntenantedJoin`, which performs the attack and asserts the leak, carrying an instruction to invert rather than delete it once legacy is fixed. Decision: D-131. Threat model: the tenant ↔ tenant row. Upstream: `hr-legacy#33`. The `stats.php` surface was found by review on PR #138. |
| Last Reviewed | 2026-08-30 |

## R-013: `profile/register_push_token.php` Cannot Succeed Against The Frozen Schema

| Field | Value |
|---|---|
| Description | `register_push_token.php` executes `INSERT INTO push_tokens (employee_id, company_id, token, platform) ... ON DUPLICATE KEY UPDATE`. The `push_tokens` table in `hr-legacy@d113204` has **no `company_id` column** and **no unique key** on `token` or on any other column. The statement therefore fails on an unknown column for every caller — employee session and company session alike — and, were the column present, the `ON DUPLICATE KEY UPDATE` clause would still never fire, appending a row per call instead of upserting. The Java port reproduces the failure rather than repairing the statement (D-058). |
| Category | Correctness / Schema drift |
| Probability | Certain, on every call. Not a race or an edge case: no request to this endpoint can have ever succeeded against this schema. |
| Impact | Low today, and the reason is that nothing depends on it. Push delivery does not work end to end in either direction (F-08); mobile's `register_push_token` call is commented out; the ETL decision drops `push_tokens` entirely; and `FCM_SERVER_KEY` is a placeholder (`hr-platform#22`). The endpoint is dead code with a live route. It becomes blocking the moment push is built for real, because the client half will call this route first. |
| Severity | Low (Phase 1); a prerequisite for `hr-platform#22` |
| Owner | Repository owner. **Three questions are open** — see `docs/bootstrap/open-questions.md`: whether production's `push_tokens` has drifted from the dump; whether a company-owned push token is intended at all; and whether the upsert key is `UNIQUE(token)` or `UNIQUE(employee_id, token)`. The third is not a schema detail — it decides whether a device belongs to one employee at a time, and the Contingency row below already turns on it, so omitting it here could make the work read as decision-complete when it is not. |
| Mitigation | None applied, deliberately. Adding `company_id` and a unique key is a **schema change to a live table** plus a behaviour change to an endpoint, and doing it in Java alone would make the two systems answer differently for the same request. The parity port keeps the divergence visible instead of papering over it. |
| Trigger | Any plan to enable push notifications; any 500 observed on this route in production logs; confirmation that production's `push_tokens` differs from the dump. |
| Contingency | Resolve as part of `hr-platform#22` rather than separately, since that issue owns push delivery end to end. The schema change is expand-only (add a nullable `company_id`, add a unique key on `token`) and therefore deployable ahead of any code, but it needs the intent question answered first: a unique key on `token` alone and a unique key on `(employee_id, token)` are different products. |
| Status | Open — not accepted, not blocking. Recorded so that whoever picks up `hr-platform#22` finds it before writing the client half. |
| Evidence | `hr-legacy@d113204` `apis/api/profile/register_push_token.php:33-49` (the INSERT) against `mysql_workin.schema.sql:802-808` (the table) and `:1247-1249` (its only indexes: PRIMARY on `id`, a non-unique key on `employee_id`). Regression: `LegacyProfileEndToEndTest#registerPushTokenFailsAgainstTheFrozenSchema` asserts the 500 and that nothing is written. Corroboration: F-08; `docs/api/three-frontend-api-usage-matrix.md:73`; `docs/migration/etl-coverage-decisions-brief.md:119`. Related: `hr-platform#22`. |
| Last Reviewed | 2026-08-29 |

## R-014: The OTP Limiter's Per-IP Cap Is A Platform-Wide 20-Per-Hour Cap

| Field | Value |
|---|---|
| Description | `otp_assert_can_send()` ends with what reads as a per-IP hourly cap: `otp_count_recent_sends(null, $ip, '', 3600) >= 20`. `otp_count_recent_sends()` drops any predicate whose backing column is absent, and against `hr-legacy@d113204` **all three of its predicates are absent** — `otp_request_logs` does not exist, and `otp_codes` has neither `ip_address` nor `purpose`. The call has no phone argument either. What actually executes is `SELECT COUNT(*) FROM otp_codes WHERE created_at > NOW() - INTERVAL 3600 SECOND` — **every OTP the platform issued in the last hour, for every phone and every IP**. At twenty, every subsequent OTP request is answered 429 `otp_too_many_requests`, for everyone. Rows accumulate because `otp_clear_for_phone()` soft-invalidates (`is_used = 1`) rather than deleting, deliberately, to keep history. |
| Category | Availability / Capacity |
| Probability | Certain at modest volume. Twenty OTPs in a rolling hour is roughly twenty registrations, password resets and phone verifications combined — reachable on an ordinary weekday morning, and trivially reachable by one attacker who simply issues twenty. |
| Impact | High while it lasts, and self-inflicted denial of service is its own worst case: **nobody can register a company, verify a phone, reset a password, or complete a phone change** until the hour rolls forward. Every affected user sees "too many requests", which reads as if *they* were throttled, so the failure is also hard to diagnose from a support ticket. The per-phone cooldown and per-phone hourly cap are unaffected and keep working; only the third check misfires. It does not fire at all when the request has no resolvable client IP, since the guard is behind `$ip !== ''`. |
| Severity | High |
| Owner | Repository owner. The fix is a legacy change, not a Java one. |
| Mitigation | **None applied.** Phase 1's contract is parity, and a Java-side fix would mean the two systems throttle different callers — the port would look healthier than the system it replaces while masking a live capacity limit. Adding `otp_codes.ip_address` (or creating `otp_request_logs`) restores the intended behaviour with **no code change at all**, because the helper already probes for both. That is the cheapest real fix and it is expand-only. |
| Trigger | Any burst of `otp_too_many_requests` responses affecting unrelated phones; a registration or password-reset funnel that stalls on the hour; twenty or more `otp_codes` rows inside any rolling hour. |
| Contingency | Add the columns rather than change the code: `ALTER TABLE otp_codes ADD COLUMN ip_address VARCHAR(45) NULL, ADD COLUMN purpose VARCHAR(32) NULL` makes both degraded predicates real and the per-purpose cap correct at the same time. It is additive, takes no lock worth planning around on a table this size, and needs no deployment ordering because both systems read the schema at call time. Verify against production first — this register entry is derived from the frozen dump. |
| Status | Open — not accepted. Recorded ahead of Wave 13.1, which ports the limiter, so the finding is not discovered and lost inside a large wave. |
| Evidence | `hr-legacy@d113204` `apis/helpers/otp_helper.php:105-172` (`otp_count_recent_sends`, `otp_assert_can_send`) and `:72-86` (`otp_clear_for_phone` soft-invalidates). Schema: `mysql_workin.schema.sql:679-686` — `otp_codes` is `id, phone, code, is_used, expires_at, created_at`; no `CREATE TABLE otp_request_logs` exists anywhere in the dump. Regression: `LegacyOtpAuthEndToEndTest#thePerIpCapIsActuallyAPlatformWideCap` seeds twenty OTP rows across twenty unrelated phones and shows a twenty-first, previously-unseen phone refused with 429 — then recovering once the global count drops (added with the limiter in Wave 13.1a, D-134). |
| Last Reviewed | 2026-08-29 |

## R-015: The Java Deployment Has No WhatsApp Credentials, And Without Them Every OTP Flow Returns 503

| Field | Value |
|---|---|
| Description | Wave 13.1a ports `sendWhatsAppText()` faithfully, including its configuration gate: when the Whats360 token or instance id is absent or still the committed placeholder, `whatsapp_is_configured()` is false, the send returns false, and `otp_issue_and_send_whatsapp()` answers **503 `otp_delivery_failed`**. `hr-platform` currently has **no** `app.legacy-whatsapp.*` values configured — the properties default to empty, exactly as `hr-legacy`'s committed `constants.example.php` holds placeholders. So on the Java side today, every route that issues an OTP fails: `auth/resend_otp`, `auth/forgot_password`, `profile/request_phone_change`, `auth/register_company`, and the verify-first branch of **both** `auth/login_company` and `auth/login_desktop` (`login_as=company`) — the desktop route was missing from the first version of this list, which mattered because desktop auth is a separate cutover check. |
| Category | Cutover readiness / External integration |
| Probability | Certain until the credentials are provisioned. This is a configuration gap, not a defect: the code is correct and the input is missing. |
| Impact | High **at cutover**, nil before it. No company can register, no password can be reset, no phone can be verified or changed. It is the same class of gap as `hr-platform#22` (FCM push) with one important difference: push does not work in legacy either (F-08), whereas **WhatsApp OTP delivery does work in production today**, so this is a real capability that would be lost rather than one never built. |
| Severity | High (cutover blocker); no production impact while Phase 1 runs alongside legacy |
| Owner | Repository owner — the credentials are an operational secret this repository must not hold. |
| Mitigation | The seam is in place and the failure is loud rather than silent: `LegacyWhatsAppHttpSender` logs `WhatsApp is not configured; OTP delivery will fail with otp_delivery_failed` at ERROR on every attempt, so a misconfigured deployment is visible in the first log line rather than in a user report. Legacy's dev escape hatch — returning **true** when unconfigured and `DEBUG` is on, marking undelivered OTPs as sent — is deliberately **not** ported (D-134), because it would convert this loud failure into a silent one. |
| Trigger | Any `otp_delivery_failed` in a Java deployment; the cutover checklist reaching the auth surface. |
| Contingency | Provision `app.legacy-whatsapp.api-token`, `app.legacy-whatsapp.instance-id` and (optionally) `app.legacy-whatsapp.instance-id-fallback` from the same Whats360 account legacy uses, through the deployment's secret mechanism — never committed. The API base defaults to legacy's `https://pro.whats360.live/api/v1/send-text` and needs no override. Verify with one real send before cutover: a 503 on this path is indistinguishable to a user from the platform being down. |
| Status | Open — a known, owned configuration gap. Not blocking Phase 1 development; blocking cutover. |
| Evidence | `hr-legacy@d113204` `apis/helpers/whatsapp_helper.php:42-47` (`whatsapp_is_configured()`), `:183-215` (`sendWhatsAppText()`'s unconfigured branch), `apis/helpers/otp_helper.php:353-355` (the 503). Port: `LegacyWhatsAppHttpSender`. Regression: `LegacyOtpAuthEndToEndTest#aFailedDeliveryIs503AndStillConsumedTheSlot` asserts the 503 and that the OTP row is written first. Related: `hr-platform#22` (the same shape for FCM). |
| Last Reviewed | 2026-08-29 |

## R-016: `complete_company_registration.php` Hands An Unauthenticated Caller A Company-Admin Session

| Field | Value |
|---|---|
| Description | The endpoint is public — no `requireAuth()`, `getAuth()` or `requireCompanyActive()` call appears anywhere in the file — and it takes `company_id` straight from `$_POST`. Its only gates are that the row exists, `otp_verified = 1`, and `profile_completed ≠ 1`. On success it returns `Response::TOKEN => jwtEncode([type => company, company_id => <caller-supplied>, role => company_admin])`. So any caller who names a company inside that window is handed **a working company-admin session for it**, alongside the ability to set its name, address, classification and both uploaded documents. The Java port reproduces this exactly (D-058). |
| Category | Security / AuthN / Tenant isolation |
| Probability | High within the window. Company ids are small sequential integers (~292 rows per the schema inventory), so the id space is enumerated in seconds; nothing else about the target need be known, and there is no rate limiting on this route. What bounds it is the window itself: a company is exposed only between verifying its OTP and completing its profile. An attacker who registers nothing can simply sweep the id range repeatedly and take whichever company happens to be mid-onboarding. |
| Impact | **Critical.** This is tenant takeover, not tampering. The returned token is accepted by every other legacy endpoint, so the attacker reads and writes that company's employees, attendance, payroll and settings. The original company admin, meanwhile, may never learn their registration was completed by someone else. The document injection (arbitrary logo and commercial-registration file) is the lesser half. |
| Severity | Critical — unchanged by the acceptance. Accepting a risk does not lower it; it records that the owner chose to carry it. |
| Owner | Repository owner. **The fix belongs in `hr-legacy` first**; a Java-only fix would make the two systems answer differently for the same request, which is what Phase 1 exists to prevent. |
| Mitigation | **None applied, deliberately.** The port is faithful and the finding is now recorded in three places instead of one: this entry, the corrected threat-model row, and the endpoint inventory. The previous threat-model row rated it Medium on the explicit reasoning that it "does not grant login access to the hijacked company" — the token block four lines from the end of the file shows that it does. That correction, not the finding, is what Wave 13.1b contributes. |
| Trigger | Any completed registration the company itself did not perform; a company whose logo or commercial-registration document it does not recognise; repeated `complete_company_registration` traffic against ids that do not belong to the caller. |
| Contingency | The minimal upstream fix needs no new concept: the OTP the company just verified is already possession proof, so require it again here (phone plus code) instead of a bare id, and drop the returned token in favour of making the client log in. Either half alone closes the takeover; the first also closes the tampering. Until then, the window is the only control, so anything that shortens it — completing registration in one step, or expiring `otp_verified` — reduces exposure without a code change. |
| Status | **Accepted 2026-08-30 by the repository owner** — parity stands. The owner's direction, verbatim: *"r-016 parity (i need java to be like php)"*. The endpoint ships reproducing legacy exactly, with no Java-side authentication added. This is now a recorded acceptance rather than an open finding: the residual is knowingly carried into Phase 1, the fix belongs in `hr-legacy` first, and the entry stays open as a **tracking** row against that upstream work. See D-141. |
| Evidence | `hr-legacy@d113204` `apis/api/auth/complete_company_registration.php`: lines 13–21 (`$_POST` inputs including `company_id`), 53–68 (the only three gates), **179–190** (the JWT minted from the caller-supplied id and returned). `grep -c 'requireAuth\|requireCompanyActive\|getAuth'` over the file returns **0**. Threat model: the `Public ↔ complete_company_registration.php` row, severity corrected Medium → Critical on 2026-08-29 with this evidence. |
| Last Reviewed | 2026-08-29 |

## R-017: `auth/register_employee.php` Cannot Succeed — Its INSERT Omits A NOT NULL Foreign Key

| Field | Value |
|---|---|
| Description | The endpoint's INSERT names only `(company_id, phone, password_hash, role)`. `employees.branch_id` is `NOT NULL` with **no default**, so under legacy's non-strict `sql_mode=''` it takes an implicit `0`, and `fk_employee_branch` requires it to reference an existing `branches.id`. Auto-increment ids start at 1, so `0` never exists and the constraint always rejects the row. Every call is a foreign-key failure. The Java port reproduces it (D-058). |
| Category | Correctness / Dead surface |
| Probability | Certain, on every call. Not a race and not data-dependent: no input can make this insert succeed while the constraint exists. |
| Impact | Low, because nothing depends on it. `join_company.php` is the working join path — it resolves the company's first active branch and writes it explicitly — and the two endpoints differ in more than that: `register_employee.php` keys off the company's **phone** rather than its public code, and writes no `join_request_status`, so its employee would be immediately `accepted` where `join_company.php` creates a `pending` one. It reads as a superseded first attempt that was never removed. |
| Severity | Low |
| Owner | Repository owner. Deleting the route upstream is probably the right answer, but that is a product call, not a port decision. |
| Mitigation | None applied. The port fails the same way for the same reason, and the regression asserts it, so the endpoint cannot quietly start "working" differently in Java than in PHP. |
| Trigger | Any client still calling `auth/register_employee.php`; any 500 on that route in production logs. Worth checking before the route is deleted — a client calling it is currently receiving a 500 and may have built a workaround. |
| Contingency | If the endpoint is to live, the minimal upstream fix is the one `join_company.php` already makes: resolve the first active branch and include `branch_id`. If it is to be retired, confirm no client calls it first — the three frontends' usage matrix is the place to check. |
| Status | Open — not accepted, not blocking. Recorded so the dead route is a known dead route. |
| Evidence | `hr-legacy@d113204` `apis/api/auth/register_employee.php:52-59` (the INSERT) against `mysql_workin.schema.sql:426` (`branch_id int(10) UNSIGNED NOT NULL`, no default) and `:1601` (`ADD CONSTRAINT fk_employee_branch FOREIGN KEY (branch_id) REFERENCES branches (id)`). Observed directly: the Java port's first run returned `Cannot add or update a child row: a foreign key constraint fails (employees, CONSTRAINT fk_employee_branch ...)`. Regression: `LegacyRegistrationEndToEndTest#registerEmployeeCannotSucceedAgainstTheFrozenSchema` asserts the 500 and that no row is written. Sibling findings: R-013 (`register_push_token`), R-014 (the OTP limiter). |
| Last Reviewed | 2026-08-29 |

## R-018: OTP Verification Has No Attempt Cap — 10,000 Guesses Take Over An Account

| Field | Value |
|---|---|
| Description | `otp_assert_can_send()` limits how many codes are **issued**. Nothing limits how many are **tried**. `auth/verify_otp.php` and `auth/reset_password.php` are unauthenticated, accept a four-digit code, and apply no per-phone or per-IP attempt counter, no lockout and no backoff. An attacker who triggers `forgot_password.php` for a phone can then submit all 10,000 values against the active code. Worse, `verify_otp.php` with `purpose=password_reset` deliberately leaves a correct code **active** — that is by design, so `reset_password.php` can consume it — so a successful guess is directly usable to set a new password. The Java port reproduces this (D-058). |
| Category | Security / AuthN — brute force |
| Probability | High. Ten thousand requests is minutes of unthrottled traffic and needs no special access, no session and no knowledge beyond a phone number. The only accidental friction is R-014's platform-wide issuance cap, which limits how many *codes exist* per hour — not how many *guesses* are made against one, so it does not help here. |
| Impact | **Critical** — full account takeover of any company or employee whose phone the attacker knows. The guessed code completes `reset_password.php`, which sets a password the attacker chooses. Legacy's 10-minute expiry is the real limit: the attack must finish inside the code's lifetime, which at any reasonable request rate it comfortably does. |
| Severity | Critical |
| Owner | Repository owner. The fix belongs in `hr-legacy` first. |
| Mitigation | **None applied.** Adding an attempt cap in Java alone would make the two systems reject different requests, which is the divergence Phase 1 exists to prevent. Tracked upstream as `hr-legacy#10`, which **explicitly blocks auth-module cutover** — that row is the control, and it is now synchronized to say the parity ports do not satisfy it. |
| Trigger | Any burst of `invalid_expired_otp` responses for one phone; a password reset the account owner did not perform; verification traffic disproportionate to issuance. |
| Contingency | The counting machinery already exists: `otp_count_recent_sends()` and the `otp_codes` table. A per-phone attempt counter with a lockout after a handful of failures is the minimal upstream change, and it composes with the same optional columns R-014 wants (`otp_codes.ip_address`, `purpose`). Lengthening the code from four digits is the other half and is a client-visible change, so it needs a product decision rather than an engineering one. Change legacy first, port in the same wave. |
| Status | Open — **not accepted**. Recorded during the Wave 13.1 review round; the endpoints ship in parity form and `#10` continues to block auth cutover. |
| Evidence | `hr-legacy@d113204` `apis/helpers/otp_helper.php:239-261` (`otp_verify_latest_for_phone()` — four conditions, no attempt counter) and `:151-172` (`otp_assert_can_send()`, reached only from the issuance path). `apis/api/auth/verify_otp.php:36-39` — `if (!$is_password_reset) { otp_clear_for_phone($phone); }`, which is why a password-reset guess survives. Upstream: `hr-legacy#10`; matrix row `#10`. Raised by the independent review of PR #144 and confirmed against the source. |
| Last Reviewed | 2026-08-29 |

## R-019: `join_company.php` Validates A Dial Code And Then Discards It

| Field | Value |
|---|---|
| Description | The endpoint resolves `country_code` from the body (or the configured default), **validates the submitted phone against it**, and then omits it from the INSERT. Nine columns are written — `company_id`, `branch_id`, `first_name`, `last_name`, `phone`, `password_hash`, `role`, `is_active`, `join_request_status` — and `country_code` is not among them, so a joined employee's row carries SQL NULL. The Java port reproduces this (D-058). |
| Category | Correctness / Data completeness |
| Probability | Certain for every join. Invisible for Egyptian numbers, because `+20` is the fallback everything else uses. |
| Impact | Low but real, and it surfaces far from its cause. A non-Egyptian employee who joins and later uses `forgot_password.php` has no stored dial code, so `otp_resolve_country_code_for_phone()` falls back to `+20`, `phone_to_whatsapp_jid()` builds an Egyptian JID from a Saudi number, and the OTP is delivered nowhere. The user sees a password reset that silently never arrives, and the logs show a successful send. The row's own responses also lose the dial code the caller submitted. |
| Severity | Low |
| Owner | Repository owner. Upstream fix. |
| Mitigation | None applied — adding the column in Java would make a joined employee's row differ between the two systems on a column other endpoints read. The regression `aNonEgyptianJoinerHasNoCountryCodeStored` pins the current behaviour so it is a known gap rather than an assumed one. |
| Trigger | A non-Egyptian employee reporting that a password reset never arrives; any `employees` row with a non-`+20`-shaped phone and a NULL `country_code`. |
| Contingency | One column in one INSERT: add `country_code` with the already-resolved `$country_code`. It is additive and needs no schema change (the column exists and is nullable). Worth landing with the other upstream auth fixes rather than alone, and worth a backfill decision for existing rows — which is a data question, since the correct value for an existing NULL can only be inferred from the phone's shape. |
| Status | Open — not accepted, low priority. |
| Evidence | `hr-legacy@d113204` `apis/api/auth/join_company.php:21-22` (resolve and validate) against `:79-99` (the nine-column INSERT). The same failure mode reached `forgot_password.php` as a **port** defect in the first Wave 13.1 review round and was fixed there (D-136); this one is legacy's and is not. Raised by the independent review of PR #144 and confirmed against the source. |
| Last Reviewed | 2026-08-29 |

## R-020: Interleaved Field-Name Aliases In A URL-Encoded Body Resolve To The Wrong Value

| Field | Value |
|---|---|
| Description | PHP normalizes external field names as it parses, so `doc_type=A&doc.type=B&doc_type=C` populates `$_POST['doc_type']` three times in wire order and keeps the last, `C`. `LegacyPostFields.field()` reads `getParameterMap()`, which groups values by **raw** key: within a key the order is the wire order, but across keys it is lost, so the three are reassembled as `[A, C, B]` and the method returns `B`. |
| Category | Correctness / Wire compatibility |
| Probability | Very low. It needs a single request carrying **two different spellings of one field name** — `doc_type` and `doc.type`, or `doc type` — in one URL-encoded body. No client this repository has read does that, and the normalization exists to tolerate one spelling at a time rather than several at once. |
| Impact | Low, and bounded to the two routes that read `$_POST`: `employee_docs/update.php` would update a document with the wrong `doc_type`, and `complete_company_registration.php` would take a wrong scalar. No tenant boundary is crossed and nothing is written that the caller did not supply — it is the wrong one of *their own* values. |
| Severity | Low |
| Owner | Repository owner. |
| Mitigation | None, and the reason is structural rather than a judgement call: for a URL-encoded request the servlet container has already consumed the input stream to build the parameter map by the time a controller runs, so the raw body — the only record of the ordering — is gone. The method cannot recover it. The limit is documented at `LegacyPostFields.field()` so the next reader does not mistake it for an oversight. |
| Trigger | Any client observed sending two spellings of one field name in one body; a support report of a document saved with the wrong type. |
| Contingency | Capture the body upstream with a caching request wrapper (Spring's `ContentCachingRequestWrapper`, or a small filter) applied to the `$_POST`-reading routes, then parse the pairs in wire order and normalize each key as PHP does. That is a change to the request pipeline, not to this method, and it needs its own review — a wrapper that buffers request bodies has memory and streaming implications for the upload routes it would sit in front of, which is exactly why it was not bolted on at the end of a wave. |
| Status | Open — documented limit, not accepted as correct. Deliberately not fixed under time pressure at the end of Wave 13.1. |
| Evidence | `hr-legacy@d113204` `apis/helpers/functions.php` (`parse_str()` semantics) against `LegacyPostFields.field()`'s use of `getParameterMap()`. The **multipart** branch has no such limit — `getParts()` preserves arrival order, and both `field()` and `file()` resolve the true final duplicate there, fixed in the same review round (D-140). Raised by the independent review of PR #144. |
| Last Reviewed | 2026-08-30 |

## R-022: The OTP Client Identity Is Taken From Attacker-Controlled Headers

| Field | Value |
|---|---|
| Description | `LegacyClientAddress.clientIp()` resolves the caller's address from `CF-Connecting-IP`, then `X-Forwarded-For`, then `X-Real-IP`, and only then the socket peer — with **no trusted-proxy check** on any of them. Any client can therefore choose the value the platform treats as its identity, simply by setting a header. This is a byte-faithful port of `apis/helpers/otp_helper.php:43-46`, which has the same four sources in the same order and the same absence of a proxy allowlist. |
| Category | Security / Rate-limit evasion / Legacy parity |
| Probability | Certain, in the sense that the header is trusted unconditionally. |
| Impact | **Currently none, and the reason is R-014, not any control here.** The per-IP predicate in `otp_assert_can_send()` is dropped against `hr-legacy@d113204` because its backing columns are absent, so the resolved address is not used as a rate-limit key at all today — a spoofed header changes nothing. The exposure becomes real on any deployment whose `otp_*` tables have drifted to include those columns: there the attacker picks the bucket, so the per-IP cap is evaded by rotating a header value, and the address recorded against an OTP send is whatever the caller claimed. |
| Severity | Low today; **Medium the moment the OTP IP columns exist**. It is listed separately from R-014 because they fail in opposite directions: R-014 is the cap being *too broad* (platform-wide), this is the key being *forgeable*. Fixing R-014 by adding the columns activates this one. |
| Owner | Repository owner. Governed by **D-141**: the owner's standing direction is that the port reproduces legacy for any such issue. |
| Mitigation | **None applied, deliberately.** Adding a trusted-proxy allowlist in Java alone would make the two systems answer differently for the same request, and would silently change which callers share a rate-limit bucket. The correct fix is infrastructure, not code: strip `CF-Connecting-IP`, `X-Forwarded-For` and `X-Real-IP` at the edge before they reach the application, so the header can only be set by the proxy that is entitled to set it. That is a deployment change and can be made without touching either system. |
| Trigger | Any schema change adding the `otp_*` IP columns; any plan to rely on the per-IP cap; any deployment that exposes the application directly rather than behind a proxy that rewrites these headers. |
| Contingency | Do the edge fix first, since it is the only one that does not diverge from legacy. If application-level validation is later wanted, change `hr-legacy` first and port it, as with every other finding in this register. |
| Status | Open — recorded, not accepted. Surfaced by an automated security review on 2026-08-30 and confirmed against the legacy source rather than taken on trust. Filed because nothing else in the register said the client identity is caller-chosen; R-014 covers the cap's breadth and not the key's forgeability. |
| Target Date | Before the OTP IP columns are added, whichever change introduces them. |
| Evidence | `hr-legacy@d113204` `apis/helpers/otp_helper.php:43-46`; port at `LegacyClientAddress.clientIp()`; the inert-predicate mechanism in R-014. Related: `LegacyClientAddressTest#anIpv4TailIsRejectedAnywhereButTheEndOfTheLiteral`, which pins the validator that decides whether a supplied header is used at all. |
| Last Reviewed | 2026-08-30 |

## R-023: Phase 1 Cutover Has An Unprovisioned Schema Prerequisite On The Production Legacy Database

| Field | Value |
|---|---|
| Description | Phase 1 adds tables to the legacy MariaDB: `legacy_refresh_tokens` (`backend/src/test/resources/legacy/phase1_extensions.schema.sql`), authorised as a narrow exception by **D-043 amendment 3**, and — since **D-156** (2026-09-02) — the five attendance-device tables in the same file, required only where the device receiver is enabled and sharing this risk's treatment in full. None exists in production legacy MySQL, and **nothing creates them**: Flyway owns no MariaDB location, `LegacyPersistenceConfig` sets `hibernate.hbm2ddl.auto` to `none`, and ADR-0013's Open Questions record that the provisioning mechanism for a real, non-test instance is undecided. Today the table exists only where a test container applies the extension schema out-of-band. |
| Category | Migration / Cutover readiness / Production safety |
| Probability | Certain — the table is required by code that ships in the cutover. Note the failure point is **not** first login: the parity login route (`LegacyPhpLoginService`) never touches the refresh-token repository and succeeds without the table. What breaks is every path calling `LegacyRefreshTokenService.revokeAllForEmployee()` — password change and logout (`LegacyProfileService`) and **`reset_password.php` in employee mode** (`LegacyOtpAuthService.resetPassword()`; **not** `verifyOtp()`, which never touches the table, and **not** its company branch, which stops at `updateCompanyPasswordByPhone()`) — so a missing table surfaces as scattered failures in live use rather than a clean startup error. **The reset path fails partially, not cleanly**: `resetPassword()` commits the new password via `updateEmployeePasswordByPhone()` *before* calling `revokeAllForEmployee()`, and `LegacyOtpAuthService` carries no `@Transactional`. A missing or unwritable table therefore leaves the password changed, the sessions un-revoked, and the caller told the operation failed. |
| Impact | **Nothing fails at cutover time, which is what makes this dangerous.** Login succeeds without the table, so the cutover looks clean; the failures appear afterwards, scattered across password change, logout and **employee-mode `reset_password.php`** — the paths that call `revokeAllForEmployee()`. Two nearby routes do **not** reach it and will pass with the table absent: `verifyOtp()`, which never touches the repository, and a **company-mode** reset, whose branch stops at `updateCompanyPasswordByPhone()`. An operator watching the cutover for a clean startup and a successful login gets no signal at all. More importantly, provisioning is a **DDL statement against the production legacy database** executed by an undecided mechanism, which is precisely the class of action that needs a named owner, a rehearsal against a restored copy, and a stated lock duration rather than an ad hoc `CREATE TABLE` on the night. |
| Severity | Medium. The change itself is trivial and additive; the risk is that it is treated as trivial and therefore performed without the controls a production schema change requires. |
| Owner | Repository owner. |
| Mitigation | Decide and approve the provisioning mechanism before the cutover window, per ADR-0013's Open Questions. Rehearse it against a restored copy of the production legacy database and record the mechanism, owner and lock duration in `docs/operations/release-cutover-and-rollback.md`, which now carries this as an explicit pre-cutover step. |
| Trigger | Scheduling the Phase 1 cutover; or, for the five attendance-device tables specifically, any move to enable `app.devices.ingest.enabled` in production — **D-157 makes solving this a precondition of turning that flag on**, not something to discover during the change. |
| Contingency | **Rollback does not need to reverse it.** The table is purely additive and no PHP code references it, so after a rollback it is simply orphaned — harmless in place, and leaving it preserves the option of rolling forward again without repeating the DDL. Dropping it is deliberately *not* part of the rollback procedure. |
| Status | Open. Surfaced by independent review of PR #147 on 2026-08-30, which correctly rejected that PR's original claim that Phase 1 changes no tables. |
| Target Date | Before the Phase 1 cutover window is scheduled. |
| Evidence | `phase1_extensions.schema.sql`; `LegacyPersistenceConfig` (`hbm2ddl.auto=none`, "No Flyway ownership of any MariaDB schema"); ADR-0013 Open Questions; D-043 amendment 3; decision-log entries D-050/D-051 declining to widen the exception. |
| Last Reviewed | 2026-09-02 — scope widened to the D-156 device tables; treatment unchanged. |

## R-024: A Signing-Secret Mismatch At Phase 1 Cutover Logs Out Every User, Twice

| Field | Value |
|---|---|
| Description | Phase 1's zero-client-change property (**D-111**) depends on Java and PHP sharing one HS256 signing secret. `LegacyPhpJwtService` binds `app.jwt.secret`; PHP uses `AppConfig::JWT_SECRET`. Nothing verifies the two are byte-identical — `JwtSecretStartupCheck` rejects a known placeholder value and nothing more — and until PR #147 no document stated the requirement at all. |
| Category | Security / Cutover readiness / Customer impact |
| Probability | Unknown, and that is the point: it is a single configuration value that no check compares, so a mismatch would be discovered by users rather than by the release. |
| Impact | If the secrets differ, **cutover invalidates every live PHP-issued session at once** — a mass forced logout of the entire active user base — and a **rollback invalidates every session Java issued since cutover, logging everyone out a second time**. This inverts the phase's central risk assumption: the rollback that is supposed to be cheap becomes the second-most disruptive event of the release. If they match, both transitions are transparent. |
| Severity | High if it occurs; the exposure is one unverified config value and the verification is a single request. |
| Owner | Repository owner. |
| Mitigation | The pre-cutover token exchange recorded in `docs/operations/release-cutover-and-rollback.md`: mint a token in Java, present it to PHP on an authenticated endpoint, then reverse the direction. Rejection means the deployments disagree — stop the cutover. Wire compatibility either side of that secret is already pinned by `LegacyPhpJwtWireCompatibilityTest` (codec) and `LegacyLoginEndToEndTest` (real HTTP through the production filter chain), both building expectations from an independent reimplementation of `jwtEncode()`. |
| Trigger | Any Phase 1 cutover; any rotation of either secret. |
| Contingency | If a mismatch is found before cutover, align the configured value and re-run the exchange. If it is discovered *after* cutover, rolling back does not restore the logged-out sessions — the population is already forced to re-authenticate — so the decision becomes forward-fix versus rollback on other grounds, and a user communication is required either way. |
| Status | Open. Recorded 2026-08-30 with PR #147; the secondary constraint below was surfaced by independent review of that PR. |
| Target Date | Before the Phase 1 cutover window is scheduled. |
| Evidence | `LegacyPhpJwtService` and `JwtService` both bind `app.jwt.secret`; `apis/helpers/functions.php:420-430`. **Secondary constraint:** `JwtService` is component-scanned into the `phase1-mysql` context and its constructor calls `Keys.hmacShaKeyFor()`, which rejects keys under 256 bits, whereas PHP's `hash_hmac` accepts any length — so a legacy secret shorter than 32 bytes would prevent Java from starting rather than merely mismatching. Checked: the legacy secret is 65 bytes, so this is satisfied today, but it constrains any future rotation. The value is not recorded in this repository. |
| Last Reviewed | 2026-08-30 |

## R-025: Phase 1's Rollback Target Has Never Been Shown To Be Restorable

| Field | Value |
|---|---|
| Description | G11's rollback claim has two halves — "the database is unchanged" and **"PHP still runs"** — and only the first has been examined. Nothing verifies that the PHP artifact, its runtime configuration, or the traffic-routing path back to it is available and working at the moment a rollback is called. `release-cutover-and-rollback.md`'s own release/rollback procedure is still an unfilled template: there is no PHP deployment step, no routing reversal, no health check, and no post-rollback smoke evidence anywhere. |
| Category | Cutover readiness / Production safety / Rollback |
| Probability | Unknown, and untested — which is the finding. The legacy application is frozen at `d113204` and is expected to run, but "expected to" is exactly the standard G11 exists to replace. |
| Impact | Session compatibility (**R-024**) is necessary for a transparent rollback and nowhere near sufficient. Every token check can pass while the rollback is impossible because there is nothing to roll back *to*. This is the failure mode where the release has no way out and discovers it only under pressure. |
| Severity | High. Phase 1's entire risk posture rests on the rollback being cheap; an unrehearsed rollback is not known to be cheap or even available. |
| Owner | Repository owner. |
| Mitigation | An end-to-end rollback rehearsal on a non-production environment: route traffic back to PHP, confirm it serves, run the smoke checks against it. Recorded as pre-cutover step 4. Separately, fill in the release/rollback procedure the template is still waiting for — cutover steps, sequencing, triggers, owners. |
| Trigger | Scheduling the Phase 1 cutover. |
| Contingency | None available if it fails at cutover time, which is the point of rehearsing it beforehand. If the rehearsal shows PHP cannot be restored quickly, Phase 1's risk acceptance has to be revisited: the phase was approved on the strength of a cheap rollback that would not exist. |
| Status | Open. Surfaced by independent review of PR #147 on 2026-08-30, which correctly observed that the PR substituted session compatibility for G11's whole claim. |
| Target Date | Before the Phase 1 cutover window is scheduled. |
| Evidence | `docs/operations/release-cutover-and-rollback.md` — Claim 2b, and the unfilled Cutover Step / Rollback Procedure / Owner / Evidence sections below it. Tracked separately from R-023 (schema prerequisite) and R-024 (signing secret) because the three fail independently. |
| Last Reviewed | 2026-08-30 |

## R-026: A Deactivated Platform Administrator Kept Access Until Their Token Expired

| Field | Value |
|---|---|
| Description | `PlatformAdminAuthenticationFilter` built `AuthenticatedPlatformAdminPrincipal` from the JWT subject alone — it never loaded the `platform_admins` row and never checked `active`. `PlatformAdminSessionService` checks deactivation only on rotation, which is what F-26 means by "fail-closed rotation for deactivated admins": a narrower guarantee than per-request enforcement. |
| Category | Security / AuthN-AuthZ / Platform administration |
| Probability | Was certain whenever an admin was deactivated while holding a live access token. |
| Impact | The deactivated administrator retained **full platform-admin access for up to the access-token TTL** (`app.platform-admin.jwt.access-token-ttl-seconds`, 900s). Deactivation is precisely the control an operator reaches for when someone must stop having access *now* — a departure, a suspected compromise. **Scoped honestly**: the only authenticated route on this surface today is `GET /api/platform-admin/me`, so the realised exposure was continued identity disclosure, not continued destructive capability. The destructive operations this surface is *for* — company suspension and deletion (ADR-0009 Option E) — do not exist yet, which is why the severity below is Medium rather than High. The defect mattered because it would have been inherited silently by the first such endpoint. Rotation refusal bounded the window to 15 minutes but did not close it. |
| Severity | Medium. Bounded and requiring an already-issued token, but a silent gap in the highest-privilege surface in the system, where the mitigation an operator would believe in did not do what they expected. |
| Owner | Repository owner. |
| Mitigation | **Fixed.** The filter now loads the row and verifies `active` on every request, failing closed on a missing row. One indexed primary-key lookup per platform-admin request — the trade ADR-0010 already makes deliberately for tenant routes: immediate revocation over cached authorization state. Pinned by `PlatformAdminAuthFlowTest#aTokenIssuedBeforeDeactivationStopsWorkingImmediately`, which logs in, confirms the token works, deactivates the row, and asserts the same unexpired token is refused. |
| Trigger | Any new entry point onto `/api/platform-admin/**` — including the BFF proposed by ADR-0014 — inherits this filter and therefore this check; a future entry point that bypasses the filter would reintroduce the gap. |
| Contingency | If the per-request lookup ever proves too costly, the fallback is a shorter access-token TTL, which shrinks the window rather than closing it. Caching the active flag would reintroduce the defect. |
| Status | **Closed 2026-08-30**, decision recorded as **D-145**. Surfaced by independent review of PR #148, which correctly rejected that ADR's claim that ADR-0010's per-request authorization already covered this path. Fixed on its own branch with a regression test falsified against the unfixed filter. |
| Target Date | Met. |
| Evidence | `backend/src/main/java/com/workin/backend/security/PlatformAdminAuthenticationFilter.java`; `PlatformAdminAuthFlowTest`; F-26 in `docs/migration/consolidated-task-matrix.md`. **Note for a future reader:** admin *deletion* is not a reachable path — `platform_admin_audit_events` holds a NOT NULL FK to `platform_admins`, so an admin with any recorded action cannot be deleted. The filter's `orElse(false)` is the correct default for an authentication decision, not a guard against an observed case. |
| Last Reviewed | 2026-08-30 |

## R-027: Logout Did Not Invalidate The Live Access Token, On Either Surface

| Field | Value |
|---|---|
| Description | **State as identified, before the 2026-08-31 fix.** Logout revokes the **refresh family** and nothing else, on both surfaces. Each access token carries a `sid` claim naming its session family, and no filter reads it. **Platform admin**: `PlatformAdminSessionService.logout(String)` revokes the family; `PlatformAdminAuthenticationFilter` authenticates on signature plus `active` only. **Tenant**: `RefreshTokenService.logout(String)` revokes the family; `JwtAuthenticationFilter` contains no reference to `sid` at all. `JwtService:69` issues the claim. A logged-out access token therefore keeps authenticating on either surface until `exp`. |
| Category | Security / Session revocation / **Both the tenant and platform-admin surfaces** |
| Probability | Certain, on **both** surfaces: logout always leaves the current access token valid for the remainder of its TTL. |
| Impact | An operator responding to a suspected **token theft** by logging the session out does not achieve what they intend — the stolen access token continues to work for up to that surface's own access-token TTL — `app.jwt.access-token-ttl-seconds` for tenant tokens, `app.platform-admin.jwt.access-token-ttl-seconds` for platform admin. Both are 900s today, but they are **independently configurable**, so quoting one property for both would report the wrong window the moment either moves. On the **platform-admin** surface the realised exposure is `GET /api/platform-admin/me` only, since the destructive operations it is intended to carry do not exist yet. On the **tenant** surface it is not theoretical: 58 mutating endpoints are live behind the same defect, so a token revoked by logout can still create, alter and delete payroll and organisational records for the remainder of its TTL. Deactivating the admin *does* now stop it immediately (**R-026**), so the stronger kill switch works; the weaker one does not, which is the wrong way round from an operator's mental model. Note `revokeAllForPlatformAdmin()` has **no production caller** — only tests — so the "revoke all sessions" variant is latent rather than reachable. |
| Severity | **Medium**, raised from Low on 2026-08-31. The first assessment scoped this to the platform-admin surface, where the only authenticated route is `/me` and the exposure really is small. That was too narrow: **the tenant path has the identical defect and a real write surface behind it** — 58 non-auth mutating endpoints including payslip create/update/delete, salary contracts and branch deletion. A logged-out tenant token can perform all of them until `exp`. Still bounded by the access-token TTL and still requiring a token in the wrong hands, which is why it is not High. |
| Owner | Repository owner. |
| Mitigation | **Applied 2026-08-31 on both surfaces** — option (a) of the two recorded below. The filter resolves the token's `sid` claim and refuses to authenticate when that session family is `REVOKED`: `JwtAuthenticationFilter.sessionIsLive` on the tenant surface, and the same check folded into `PlatformAdminAuthenticationFilter`'s existing active-admin gate (`active && sessionIsLive(claims)`). Both read `existsByFamilyIdAndStatusNot(familyId, REVOKED)`, one indexed lookup against `refresh_tokens_family_id_idx` (V15) and `platform_admin_refresh_tokens_family_id_idx` (V16). Option (b) — accepting access-token-survives-logout as the standard stateless-JWT trade — was **rejected**: the tenant surface has 58 live mutating endpoints behind it, and an operator logging out a suspected-stolen session is entitled to expect that to be what stops it. **Cost accepted deliberately**: this adds one indexed query to every authenticated request on both surfaces. That is the same trade ADR-0010 already makes for authorization and R-026 already pays for the admin lookup — immediate revocation over cached session state. **One gap remains by design**: a token carrying no `sid` is treated as live, so tokens minted before the claim existed keep working rather than every session being logged out on deploy. That ages out with the tokens themselves, within one access-token TTL of the deploy. |
| Trigger | **Already triggered on the tenant surface** — the write endpoints exist today. On platform admin, the first route performing a destructive action inherits this the moment it ships; any production caller being added for `revokeAllForPlatformAdmin()`; any incident response that relies on logout rather than deactivation. |
| Contingency | Now the intuitive action works: logging the session out stops the access token immediately on both surfaces. The previously documented workarounds remain valid as stronger controls — deactivating the administrator (platform admin) and revoking or suspending the membership (tenant) — and both still act immediately. The former asymmetry, where logout was the weaker control on the surface carrying the live write endpoints, is resolved. |
| Status | **Closed — mitigated 2026-08-31**, both surfaces, in the same change. Surfaced 2026-08-31 by an independent security review of PR #152, then widened by a second round of the same review which pointed out that the tenant path shares the shape. Deliberately not fixed inside #152, which existed to close R-026; the behaviour change was given its own branch and decision (**D-149**) rather than riding along in a security fix. Fixed on both surfaces together because R-027 is one defect on two paths — splitting it would have left the two surfaces with different revocation semantics and the risk open. |
| Target Date | Met. The tenant decision was owed immediately because the 58 mutating endpoints were already live; it was taken and implemented on 2026-08-31. The platform-admin half, which was due before any destructive operation shipped on that surface, was closed in the same change rather than deferred. |
| Evidence | **Fix**: `JwtAuthenticationFilter.sessionIsLive` and `RefreshTokenRepository.familyIsLive` (tenant); `PlatformAdminAuthenticationFilter.sessionIsLive` and `PlatformAdminRefreshTokenRepository.familyIsLive` (platform admin). **Regression tests**: `AuthSessionFlowTest.logoutAlsoStopsTheAccessTokenImmediately` and `PlatformAdminSessionFlowTest.logoutAlsoStopsTheAccessTokenImmediately` — each asserts the access token works before logout and is refused after it. Both were **verified to fail with the fix reverted and pass with it applied**, so they test the behaviour rather than restating it. **Original defect**: `JwtService:69` and `PlatformAdminJwtService:55` issued `sid`; neither filter read it. Write surface counted by enumerating non-auth mutating mappings outside `platformadmin`. Related: **R-026**, the same defect class, closed earlier. |

## R-028: The Port Was Mapped On File Paths, Not On The URLs Clients Call

| Field | Value |
|---|---|
| Description | Every legacy controller mapped the PHP **file** path (`/apis/api/configs/get.php`) because the endpoint inventory was built from the source tree. Both Flutter clients call the **router** path (`/apis/api/configs/get`) — `api_constants.dart` joins `https://workin.company/apis/api/` with paths like `auth/login_employee`, and none of its 266 endpoint constants ends in `.php`. `apis/.htaccess` rewrites to `index.php` only when the target does not exist, so a direct `.php` request bypasses the bootstrap those files assume and fatals. |
| Category | Migration / API contract / Cutover readiness |
| Probability | Was certain. Every client request at cutover would have hit an unmapped path. |
| Impact | **Total failure of D-111's zero-client-change premise, at the routing layer, before any business logic ran.** Measured against the **190 endpoint constants the clients declare** — declaration coverage, not call-site coverage, since at least one is a dead reference: Java answered the client URL form correctly for **9**. The same endpoints with `.php` appended: 188. Verified against production — `/apis/api/configs/get` answers 200 and `/apis/api/configs/get.php` answers 500. |
| Severity | **Was Critical.** Not a degradation but a complete outage of the mobile and desktop clients from the first request after cutover, with a rollback needed to restore service. |
| Owner | Repository owner. |
| Mitigation | **Fixed.** `LegacyPhpRouterFilter` ports `apis/api/index.php`'s router: a two-segment route under `/apis/api/` resolves to the `.php` file serving it. Registered at `HIGHEST_PRECEDENCE` **outside** the Spring Security chain, because the permit-list in `LegacyPhpRoutes` is written in `.php` paths and authorization must evaluate the rewritten path or it would 401 endpoints legacy serves anonymously. After the fix the sweep matches **188/190**, and `configs/get` returns byte-identical JSON from both stacks. The remaining two were the same defect wearing two faces — the router had no behaviour at all for a path it does not serve — closed by **D-148**, which reproduces `index.php`'s 404/501 refusals before authentication and takes the sweep to **190/190, differing = 0**. |
| Trigger | Any new legacy endpoint: it must be reachable in the client form, which the filter now guarantees by construction rather than per-controller. |
| Contingency | None needed post-fix. Had it shipped, the only remedy would have been rollback — the clients are frozen and cannot be pointed at a different URL shape. |
| Status | **Closed 2026-08-31**, mechanism and ordering recorded as **D-147**. Found by the PHP↔Java parity harness (`docs/migration/2026-08-31-php-java-parity-harness.md`) on its first sweep. Not findable by reading either codebase: the tests exercised the same file paths the controllers mapped, so they passed against a backend no client could reach. |
| Target Date | Met, before cutover. |
| Evidence | `LegacyPhpRouterFilter`, `LegacyPhpRouterConfig`; five regression cases in `LegacyReferenceEndToEndTest` (three fail with the filter disabled); `flutter-integration/*/lib/core/network/api_constants.dart`; production status codes recorded above; sweep results in `parity-harness/`. |
| Last Reviewed | 2026-08-31 |

> **What this says about the evidence base.** The endpoint inventory, the
> 198-endpoint count and every wave's delivery claim were built from the PHP
> *source tree* rather than from the URLs clients request. `LegacyLoginEndToEndTest`
> hits `login_employee.php` and passes; it would have passed forever. A port
> verified against its own source tree verifies the wrong contract.

## R-029: Legacy's Payslip Ordering Is Non-Deterministic, So Both Systems Are Arbitrary

| Field | Value |
|---|---|
| Description | `payslips/list.php` orders by numeric `employee_code`, then `employee_code`, then **`e.id`** — the employee id. Two payslips belonging to the same employee therefore tie on every ORDER BY column, and MariaDB returns them in whatever order the plan produces. Confirmed on the parity harness: payslips 3725 and 5714 both belong to employee 6245 (`employee_code` `3`), and PHP and Java return them in opposite order **from the same database**. |
| Category | API contract / Parity / Client-visible behaviour |
| Probability | Certain whenever a result page contains more than one payslip for one employee — the normal case for any multi-month query. |
| Impact | A client rendering the list sees an arbitrary order that can differ between the two systems, and can differ between two calls to the same system if the plan changes. With `LIMIT`, an unstable sort can also change **which** rows land on a page, so pagination may skip or repeat a row. Not data loss, and no value is wrong — every field matched exactly once ordering was accounted for. |
| Severity | Low. Legacy has always behaved this way and no client is known to depend on the order. It is recorded because the parity harness would otherwise report a permanent, unfixable difference on this endpoint, and a future reader would waste time chasing it. |
| Owner | Repository owner. |
| Mitigation | **None applied, deliberately.** Adding a deterministic final tie-break (`p.id`) would make Java stable and *diverge from legacy*, which **D-058** puts the burden of proof on. The correct sequence is to change `hr-legacy` first and port the change, as with every other finding of this class. |
| Trigger | Any complaint about payslip list ordering; any client that starts depending on order; any decision to add pagination guarantees. |
| Contingency | If a client does depend on it, the tie-break must be added to **both** systems in the same change, not to Java alone. |
| Status | Open — recorded, not accepted. Found by the parity harness on 2026-08-31. **Correction, same day:** this entry originally claimed a Java ordering bug had been found and fixed alongside it — `p.id` where PHP uses `e.id`. That was wrong. `list()` already ended on `e.id` and always had; the line changed was in `exportRows()`, whose PHP counterpart (`data_export_helper.php:552`, inside `data_export_payslips_csv`) genuinely does end on `p.id`. The two legacy queries differ on purpose, and the 'fix' altered a correct one — reverted after independent review of PR #153 caught it. There was never a Java ordering defect here; the residual below is the whole of it. |
| Target Date | None. Recorded for recognition rather than remediation. |
| Evidence | `hr-legacy/apis/api/payslips/list.php:134-141` (list ends on `e.id`) and `apis/helpers/data_export_helper.php:552` (export ends on `p.id`); `LegacyPayslipStore.list()` and `.exportRows()` mirror that split. Harness measurement: same 20-row set, **0 value differences and 0 type differences** when compared keyed by id — the difference is order alone. The 12/20 positional agreement recorded earlier was attributed to a code change that could not have caused it, and is not evidence of anything. |
| Last Reviewed | 2026-08-31 |

## R-030: Legacy Accepts Out-Of-Range Payroll Months And Negative Leave Balances

| Field | Value |
|---|---|
| Description | Two write endpoints validate less than their callers assume, and the Java port reproduces both faithfully (D-058), so this is a **legacy** defect that Phase 1 inherits rather than introduces. `payroll_batches/create` accepts `month: 13` — it returns 201 and computes a fiscal period from it (`period_from 2026-11-21`, `period_to 2026-12-20`), so the batch is real and covers **shifted dates** rather than being rejected. `leave_balances/create` accepts `total_days: -5`, persisting `remaining_days: -5.0`. |
| Category | Data integrity / Input validation / Payroll |
| Probability | Unknown in production, but nothing prevents it: both are plain API calls with no client-side guarantee behind them, and the desktop client is not the only possible caller. |
| Impact | A mistyped month creates a payroll batch whose period silently does not match its label — the batch says month 13 while covering late November to late December. Anything downstream that groups or reconciles by month sees a batch that does not belong to any real month. Negative leave entitlement propagates into balance arithmetic and into whatever the client renders. Neither fails loudly; both produce plausible-looking rows. |
| Severity | Medium. No evidence either has occurred, and both require an unusual request — but payroll data that is wrong and looks right is the expensive kind, and the absence of validation means the only thing preventing it today is that nobody has typed it. |
| Owner | Repository owner. |
| Mitigation | **None applied, deliberately.** Adding validation in Java alone would make the two systems answer differently for the same request, which **D-058** puts the burden of proof on — and during Phase 1 a request PHP accepts must not become an error in Java. The correct sequence is to fix `hr-legacy` first and port the change, as with every other finding of this class. |
| Trigger | Any payroll reconciliation that finds a batch outside months 1–12; any leave report showing a negative entitlement; any decision to add input validation to the legacy API. |
| Contingency | A read-only query over `payroll_batches` for `month NOT BETWEEN 1 AND 12`, and over `leave_balance` for `total_days < 0`, would establish whether either has already happened. Neither has been run — that needs explicit authorization for production access. |
| Status | Open — recorded, not accepted. Found on 2026-08-31 by the parity harness's mutation sweep, which sends deliberately invalid input to both stacks and compares the outcome. Both accepted it identically, so the sweep passed on parity while surfacing the shared gap. |
| Target Date | Before any hardening pass on the legacy write API, or sooner if a reconciliation finds an affected row. |
| Evidence | `hr-legacy/apis/api/payroll_batches/create.php` (`required()` covers presence, not range); `leave_balances/create.php` (same); harness cases `payroll_batches (month 13)` and `leave_balances (negative days)`, both 201/201 with identical rows. |
| Last Reviewed | 2026-08-31 |

## R-032: `company_settings/options` Emits A `label` Key PHP Does Not

| Field | Value |
|---|---|
| Description | Every option row Java returns from `company_settings/options.php` carries an extra `label` key that PHP does not emit. PHP returns `{value, label_ar, label_en}`; Java returns `{value, label, label_ar, label_en}`, where `label` holds the already-resolved localized text. Affects all five option groups (`month_start_day`, `month_end_day`, `monthly_leave_accrual`, `overtime_rate`, `weekly_off_days`), so the response is 5650 bytes against PHP's 4418. |
| Category | API contract / Parity / Response shape |
| Probability | Certain — every call, every group. |
| Impact | **Additive, so no client breaks today**: a Flutter model that reads `label_ar`/`label_en` ignores an unknown key, and no client is known to read `label`. It matters for three reasons anyway. It is a **response-shape divergence under D-074**, where the whole premise is that Java's body is PHP's body. It is a **28% larger payload** on a settings screen. And it is the shape a client could start depending on, at which point the divergence becomes a migration blocker rather than noise. |
| Severity | **Low.** No known client reads the key and nothing fails. Recorded because D-058 puts the burden of proof on the change, not the port: an extra field is a deliberate-looking addition that no decision authorizes. |
| Owner | Repository owner. |
| Mitigation | **None yet.** The fix is to stop emitting `label` from the options serializer, or to record a decision permitting it. Not folded into the router work (**D-148**) that found it: that change is about paths nothing serves, and this is a delivered endpoint's response body. |
| Trigger | Already present. Becomes blocking the moment any client reads `label`. |
| Contingency | If a client has already come to depend on `label`, the parity direction reverses — PHP would need it too, or the client changed — so confirm client usage before deleting the key. |
| Status | **Open.** |
| Target Date | Before Phase 1 cutover; it is a response-contract item, so it belongs with the other D-074 shape checks rather than after them. |
| Evidence | Found by the harness's authenticated sweep (Level 3) on 2026-08-31, which reported `company_settings/options` as one of two differing bodies. Keyed comparison of the parsed responses: identical top-level keys and identical group names; every group's rows differ only by the presence of `label` in Java, e.g. PHP `{'value': 'Fri', 'label_ar': 'الجمعة', 'label_en': 'Friday'}` against Java `{'value': 'Fri', 'label': 'Friday', 'label_ar': 'الجمعة', 'label_en': 'Friday'}`. The other differing body was `payslips/list`, which is **R-029** (tie-break ordering) and not a new finding: same 20 ids, all values equal, only the order of tied pairs differs. |
| Last Reviewed | 2026-08-31 |

## R-034: Malformed Percent Encoding Is Rejected By Tomcat — Reference To D-070

| Field | Value |
|---|---|
| Description | A malformed percent sequence in a query string (`?x=%`, `?x=%zz`, a truncated `%A`) is rejected by Tomcat with **400 Bad Request** before the dispatcher, on every `/apis/**` endpoint, where PHP's `parse_str` keeps the literal `%` and serves the request. Measured with raw sockets: `GET /apis/api/phone_countries/list?x=%` is **200** in PHP and **400** in Java; `?x=%25` is 200 on both. |
| Category | Parity / Request handling — **already decided, see D-070** |
| Probability | Certain for that input shape, on every endpoint. |
| Impact | The response is Tomcat's, not the D-074 envelope, and the rejection happens before any code in this repository can see it. |
| Severity | **Not an open defect. Accepted.** |
| Owner | Repository owner. |
| Mitigation | **None, deliberately — and none is to be attempted.** **D-070** accepted this exact behaviour as an explicit, narrowly scoped Phase-1 divergence on 2026-08-19, with the owner's instruction recorded verbatim: *"Do not relax, replace, intercept, or bypass Tomcat request-target parsing"*, and *"Invalid percent encoding rejected by the embedded HTTP server before controller execution is outside the Phase-1 application/business compatibility contract."* |
| Trigger | None outstanding. |
| Contingency | Not applicable. |
| Status | **Closed on discovery — duplicate of an accepted decision.** This entry was first written as an open pre-cutover defect proposing Tomcat connector changes, which would have driven work directly contrary to D-070. It is retained, corrected, as a pointer: the behaviour is real and someone re-measuring it should find the decision rather than re-open it. The failure was mine — I measured a divergence and recorded it without checking whether it had already been decided. |
| Target Date | None. |
| Evidence | Raw-socket measurement above, and **D-070** in `docs/bootstrap/decision-log.md`. Related: **D-148**, whose `safeLocale` handles the same decode failure on the one path that runs *before* Tomcat's parameter parsing — that path is inside the application, so it is the router's contract rather than the container's, and is not covered by D-070. |
| Last Reviewed | 2026-09-01 (corrected) |

## R-031: A Text Field Named `file` Was Accepted As A Spreadsheet Upload

| Field | Value |
|---|---|
| Description | Two endpoints mis-ported PHP's upload guard, which is `isset($_FILES['file'])` **and** `error === UPLOAD_ERR_OK`. **`leave_balances/analyze_excel`** used `request.getPart("file")`, which returns any multipart part with that name — including a plain text field carrying no filename, a `$_POST` field in PHP that never reaches `$_FILES`. It parsed the text as a spreadsheet and answered **200 "Leave balances file analyzed"** where PHP answers **400 `no_file_uploaded`**; a part present with `filename=""` diverged too. **`employees/analyze_excel`** guarded on `file.isEmpty()`, which tests the **bytes** rather than the filename, so it was wrong in both directions: a zero-byte file with a real name is `UPLOAD_ERR_OK` in PHP and reaches the format check (`Empty or unreadable file`), while `filename=""` with content is `UPLOAD_ERR_NO_FILE` and is refused. Java answered the first as `no_file_uploaded` and let the second through to format validation. |
| Category | Parity / Input handling / `apis/api/leave_balances/analyze_excel.php` |
| Probability | Certain, for that request shape. Not reached by any current client, which sends a real file — this is a malformed or hostile request, not a normal one. |
| Impact | A divergence in the direction that matters on the leave-balance side: PHP **refuses** and Java **accepted**. Those endpoints only analyse and return a preview, so nothing is persisted and the blast radius is bounded — but the analysis result is what the client feeds to `import_bulk`, so garbage accepted here is one step closer to a write. On the employees side the divergence runs the other way and is a plain wrong-answer: a legitimate empty file was reported as a missing upload. Under **D-111** no client can be adjusted around either, so the server has to match. The wider point is the class of defect: the servlet API's `getPart`, Spring's `MultipartFile.isEmpty()` and PHP's `$_FILES` do not mean the same thing, and three endpoints each got it wrong differently. |
| Severity | **Low.** Bounded to one non-persisting endpoint and not reachable through any client's normal flow. Recorded because it is a *class* of defect — the servlet API's `getPart` and PHP's `$_FILES` do not mean the same thing — and because the endpoint had no end-to-end test at all, which is why it survived. |
| Owner | Repository owner. |
| Mitigation | **Applied 2026-08-31, over two rounds.** `leave_balances/analyze_excel` now resolves through `LegacyPostFields.file` — the shared helper the other multipart endpoints use, which skips parts whose `getSubmittedFileName()` is null and brings PHP's field-name normalisation and last-duplicate-wins rule with it — and the now-unused private `part()` helper was deleted so the divergent path cannot return via a new call site. Both endpoints then take the same three-part filename test `LegacyAttendanceImportService` already carried: a null part, a null `getOriginalFilename()`, or an empty one. Deliberately **not** an emptiness test on the bytes, which is precisely the bug on the employees side: a zero-byte file the user actually chose is `UPLOAD_ERR_OK` and must fall through to the format check. The second round came from independent review; the first mitigation covered only the text-field case and claimed the other spreadsheet endpoints already carried the guard, which was true of `attendance` and false of `employees`. |
| Trigger | Already triggered; found by the parity harness rather than by a client report. |
| Contingency | None needed — the fix removes the accepting path entirely. |
| Status | **Closed — fixed 2026-08-31**, in two rounds and across two endpoints: `leave_balances` first, then `employees` after independent review showed the first mitigation overstated parity and that the endpoint I had cited as already correct was not. |
| Target Date | Met. |
| Evidence | **Measured against both running stacks, not inferred.** `leave_balances/analyze_excel`, four shapes, all byte-identical after the fix: text field → 400 `No file uploaded`; `filename=""` → 400 `No file uploaded`; zero bytes with a real name → 400 **`Empty or unreadable file`**; genuine CSV → 200. `employees/analyze_excel`, five shapes, all byte-identical after the fix, including the two that diverged. Regression tests: `LegacyLeaveBalanceEndToEndTest#aTextFieldNamedFileIsNotAnUpload` (verified to fail against the pre-fix controller), `#anEmptyFilenameIsNotAnUploadEither`, `#aZeroByteFileWithARealNameFallsThroughToTheFormatCheck`, `#aRealUploadIsStillAccepted`; `LegacyEmployeeAnalyzeExcelEndToEndTest#anEmptyFilenameIsNoFileUploadedEvenWithContent`. **An existing test pinned one of the divergences as the contract** — `anEmptyUploadAndAMissingPartAreBothNoFileUploaded` asserted `No file uploaded` for a zero-byte named file, which is what Java's wrong guard produced; it now asserts PHP's `Empty or unreadable file`. Contract recorded in `docs/api/existing-endpoint-inventory.md`. |
| Last Reviewed | 2026-08-31 (closed) |

## R-033: ~~The BFF Credential Store Holds Every Live Platform-Admin Refresh Token~~ — Closed, Architecture Removed

| Field | Value |
|---|---|
| Description | **This risk existed only under ADR-0014's Next.js + BFF design**, where a server-side BFF held the raw refresh token for every logged-in platform administrator so it could replay them. That store concentrated into one place what had previously been distributed across browsers, and a read of it — or of any replica, snapshot or backup — yielded every live platform-admin session at once. |
| Category | Security / Credential custody — **no longer applicable** |
| Probability | None. There is no such store. |
| Impact | None, for the same reason. |
| Severity | **Closed — the architecture that created it was removed**, not mitigated. **ADR-0015** supersedes ADR-0014: the platform-admin web surface is server-rendered **JTE inside the existing Spring application**, authenticated by a server-side session. No platform-admin token is issued to, stored in, or replayed by a separate frontend, so there is nothing to concentrate. |
| Owner | Repository owner. |
| Mitigation | Not applicable. The controls this entry required — encryption under an external key, restricted access, protected replicas and backups, read auditing, a tested global revocation path — were properties of the BFF store and retire with it. **What did not depend on the BFF is carried forward in ADR-0015**: TOTP seed custody has the same custody requirements for the same reason (a recoverable secret at rest), and session invalidation must still be immediate on logout, deactivation and password change. |
| Trigger | None. Re-opens only if a separate frontend holding platform-admin credentials is reintroduced, which would be a new decision superseding ADR-0015. |
| Contingency | Not applicable. Note that the **absence of a global revocation operation is a real finding independent of this risk**: `revokeAllForPlatformAdmin(Long)` is per-administrator and unwired, so "revoke every admin session" is still an ad-hoc procedure. That is carried into ADR-0015's session-invalidation requirement rather than left here. |
| Status | **Closed 2026-09-01 — not applicable.** Raised by independent review of PR #148 against a design that was superseded before it was built. Retained rather than deleted so the reasoning survives: it is the record of why a browser-facing admin app would have needed those controls, and it should be read before anyone proposes one again. |
| Target Date | None. |
| Evidence | **ADR-0015** (JTE, in-process) superseding **ADR-0014** (Next.js + BFF), on the repository owner's instruction of 2026-09-01. `PlatformAdminRefreshTokenRepository.setStatusForPlatformAdmin` remains the per-administrator revocation; `revokeAllForPlatformAdmin` remains unwired and per-administrator despite its name. Related: **R-024** (signing-secret custody), which is unaffected — the signing key exists regardless of the frontend. |
| Last Reviewed | 2026-09-01 (closed) |

## R-035: PHP Payroll Calculation Exceeds Its Own Execution Limit And Commits A Partial Batch With HTTP 200

| Field | Value |
|---|---|
| Description | `payroll_batches/calculate` in frozen PHP takes materially longer than its default execution limit allows for a realistic batch. Measured on batch 78 (1,070 employees) in the parity harness: **PHP ~108 seconds, Java ~16.8 seconds**. PHP's stock `max_execution_time` is **30 seconds**. When the limit fires, PHP is terminated *mid-loop* — but the payslips written before the cut are already committed, because there is **no transaction at all**: neither `calculate.php` nor `payroll_calculation.php` contains a `begin_transaction`, `commit`, or `rollback`, so each payslip is an individual autocommitted `INSERT ... ON DUPLICATE KEY UPDATE`. The caller can receive a **200 with a truncated body, or a connection close, while a partially-calculated batch is left in the database** looking like a completed one. |
| Category | Operations / Cutover / Data integrity — **frozen-PHP behaviour, not a Java parity defect** |
| Probability | Certain for any company whose batch exceeds the configured limit, on every attempt, until the limit or the batch size changes. Whether production hits it depends on the deployed `max_execution_time`, which is **not recorded here and should be checked before cutover**. The harness raised it to 600 seconds specifically so the parity comparison could complete — meaning the harness does **not** reproduce the production failure mode by default. |
| Impact | A payroll batch that reports success while holding payslips for only some employees. The rest are missing rather than wrong, so totals under-report and nothing flags an error. Recalculating is the natural operator response and is safe, but only if someone notices. |
| Severity | Medium — bounded and recoverable, but silent. It is raised now because the Java port **removes** the exposure by being roughly 6.4x faster on the same batch, which means the risk lives entirely in the pre-cutover window and in any rollback to PHP. |
| Owner | Repository owner. |
| Mitigation | **None applied in Java, deliberately.** Java does not need one at this size: 16.8 seconds against a 30-second limit, and its calculate path is transactional. The mitigation is operational and belongs to PHP for as long as PHP serves this endpoint — raise `max_execution_time` for that route, or cap batch size. Changing frozen PHP is out of scope under **D-058**, so this is recorded rather than fixed. |
| Trigger | Any payroll batch that returns success with fewer payslips than the company has active employees; any operator report of a batch needing recalculation; any decision to roll back to PHP after cutover. |
| Contingency | A read-only count comparing payslips written per batch against active employees at the period end identifies affected batches. Recalculating the batch is the remedy and is idempotent. |
| Status | Open — recorded, not accepted. Measured 2026-09-01 during the batch-78 parity comparison. |
| Target Date | Before cutover, and before any rollback plan that puts PHP back in front of payroll. |
| Evidence | Parity harness batch-78 run, 2026-09-01: PHP ~108s, Java ~16.8s over 1,070 employees. Absence of transaction control in PHP verified by search across `apis/api/payroll_batches/calculate.php` and `apis/helpers/payroll_calculation.php` (no match for begin/commit/rollback); the per-employee write is the `ON DUPLICATE KEY UPDATE` at `payroll_calculation.php:1380-1420`, which is what makes recalculation idempotent. Java's calculate path is transactional at `LegacyPayrollBatchService:546-561` (`setAutoCommit(false)` ... `commit()`). `spike/parity-harness/docker-compose.yml` sets `max_execution_time=600`, which is why the harness completes and production may not. PHP's documented default is 30 seconds. Related: **D-150**, the payroll parity deviation found in the same run; **D-058**, which places the burden of proof on changes to the port rather than on frozen PHP. |
| Last Reviewed | 2026-09-01 |

## R-036: Frozen PHP's `branches/update` Discloses Another Company's Branch, And Crashes On An Unknown Id

| Field | Value |
|---|---|
| Description | `apis/api/branches/update.php` scopes its **UPDATE** to `id = ? AND company_id = ?` — correctly, so a foreign branch is never modified — but then re-reads the row with `get_one(... WHERE id = ?)`, **keyed on `id` alone with no company filter**, and returns it. Two consequences follow from that one missing predicate. **(a) Cross-tenant disclosure.** A company admin who supplies another company's branch id receives `success: true`, message `Branch updated`, and that company's full branch row — `name`, `address`, `latitude`, `longitude`, and **`qr_code`**, the branch check-in code. Nothing is modified, so the write side is sound; the read side is not. **(b) HTTP 500 on an unknown id.** When no row exists at all the re-read returns `null` and `public_row(null)` raises `TypeError: public_row(): Argument #1 ($row) must be of type array, null given`. |
| Category | Security / Tenant isolation — **frozen-PHP defect, not present in the Java port** |
| Probability | Certain and trivially reachable: authenticate as any company admin, HR or manager, and call `PUT /apis/api/branches/update?id=<any branch id>`. Branch ids are small sequential integers, so enumeration is not a barrier. No special crafting is required. |
| Impact | Branch names, street addresses, GPS coordinates and check-in QR codes of every company on the platform are readable by any authenticated company admin. The `qr_code` is the more serious field: it is the value a branch's attendance QR encodes, so disclosure is not only informational. |
| Severity | **High for PHP, none for Java.** Verified in the harness: as the company-214 admin, `branches/update?id=344` (a company-244 branch) returned company 244's row and left it unmodified. The same request against Java returned **404 `Branch not found`** — `LegacyBranchService` re-reads through `findByIdAndCompanyId`, so the predicate PHP omits is present. |
| Owner | Repository owner. |
| Mitigation | **None applied, deliberately, and none possible within this programme's scope.** `hr-legacy` is frozen at `d113204` and **D-058** places the burden of proof on changes to the port rather than on legacy; fixing PHP is a change to the oracle. The exposure ends when PHP stops serving this route — which is the cutover this port exists to enable. Until then it is an open production issue **in the system that is live today**, and it is recorded here so that fact is not lost in a document about the port. |
| Trigger | Any decision about the cutover date; any security review of the live platform; any report of branch data or QR codes appearing where they should not. If PHP is to remain live for an extended period, the missing `AND company_id = ?` is a one-line change to `branches/update.php:80-86` and the owner may decide it is worth breaking the freeze for. |
| Contingency | A read-only query over the access log for `branches/update` requests whose `id` resolves to a branch outside the caller's company would identify whether this has been exercised. Rotating branch QR codes (`branches/generate_qr`) invalidates any code already disclosed. |
| Status | Open — recorded, not accepted. Found 2026-09-01 by the parity harness's mutation sweep, from a status difference (PHP 500 vs Java 404) whose root cause turned out to be the same unscoped re-read. The class was swept: four PHP endpoints follow a company-scoped UPDATE with a re-read keyed on `id` alone (`branches/create`, `branches/generate_qr`, `branches/update`, `departments/update`), and **only `branches/update` is exploitable** — `departments/update` answers `Department not found`, `generate_qr` answers `Forbidden`, and `branches/create` re-reads a row it just inserted. Each was tested rather than reasoned about. |
| Target Date | Before cutover, or sooner if the owner decides to patch frozen PHP. |
| Evidence | `hr-legacy/apis/api/branches/update.php:58-86` (UPDATE scoped, re-read not). Harness runs, 2026-09-01: PHP returned company 244's branch 344 to a company-214 admin with the row unchanged; PHP returned 500 with a `TypeError` for `id=99999999`; Java returned 404 for both. `LegacyBranchService.update` uses `findByIdAndCompanyId`. Related: **D-058**; **R-034** for the other place a PHP-vs-Java status pair was recorded rather than reconciled. |
| Last Reviewed | 2026-09-01 |

## R-037: Frozen PHP's `advances` Endpoints Let One Company Create, Approve, Reject, Part-Pay And Delete Another Company's Advances

| Field | Value |
|---|---|
| Description | Five of the six `advances` mutations resolve or write without a company predicate. `create.php` resolves `employee_id` with no check that the employee belongs to the caller's company. `approve.php`, `reject.php`, `pay.php` and `delete.php` write with `WHERE id = ?` and no company predicate, and none performs an ownership preflight. The caller's `company_id` is read and used for `requireCompanyActive()`, then never applied to the statement that mutates. `update.php` is the single endpoint of the six that checks, and correctly answers 404. |
| Category | **Security / Broken object-level authorization (cross-tenant) / Data integrity** — a live defect in the production PHP system, independent of the migration |
| Probability | Certain and trivially reachable. Any ordinary company-admin or HR login — the role every customer is given — can exercise it. Advance ids are small sequential integers, so enumeration is not a barrier. No crafting, no race, no privileged role. |
| Impact | **Unauthorised financial writes across tenants.** Measured in the harness as a company-214 admin against company 21's advance `id=2`, and against an employee of company 8, one reseed per case: `create` inserted an advance for the foreign employee (**201**, row written); `approve` moved `pending -> approved` (200); `reject` moved `pending -> rejected` (200); `pay` moved `remaining` from `5050.00` to `5049.00` (200); `delete` **removed the row** (200). An approved advance is a payroll deduction, so this reaches money; `create` manufactures the obligation and `approve` authorises it, both cross-tenant; `delete` destroys the record. |
| Severity | **High in PHP. None in Java.** The same six requests against Java returned **403** (create) and **404** (the rest) and mutated nothing — `LegacyAdvanceService` resolves through company-scoped store methods throughout. Strictly more serious than **R-036**, which only discloses. |
| Owner | Repository owner. |
| Java disposition | **Java stays exactly as it is. The defect is not to be reproduced.** Repository owner instruction, 2026-09-01. The status pairs are registered in the mutation sweep's accepted-divergence table naming this risk, so they are not noise; the row snapshots still run on those cases, so an unintended Java write cannot hide behind the acceptance. |
| Legacy disposition | **Open and unmitigated in production.** **D-058 freezes legacy behaviour as the migration's oracle; it does not waive an active authorization vulnerability.** Those are different questions and were being conflated in this entry's first draft. The parity programme's answer — reproduce nothing, record everything — is complete. The production question is separate and is stated under Trigger. |
| Exposure assessment | **Not determined here, and not determinable from this repository.** Whether the vulnerability is live depends on whether the PHP `apis/` surface is reachable by customers today, which is a deployment fact this repo does not contain. **No production system was accessed** — that requires the owner's explicit authorization for a specific read-only check, which has not been given for this. What is known: the code is present and defective at the frozen revision `d113204`, the routes are ordinary authenticated API routes rather than internal ones, and the Flutter clients call the `advances` module, so the surface is one clients reach in normal use. |
| Mitigation | **None applied, and none should be applied silently.** No change to `hr-legacy` is part of any parity pull request. If mitigation is required, it is an explicit security change with its own review, not a side effect of migration work. The smallest correct fix is the predicate the sibling `update.php` already applies: scope the write and the employee resolution by the caller's `company_id` in the five files. A deployment-level mitigation — restricting the PHP `advances` routes at the edge until cutover — avoids touching frozen code at the cost of removing the endpoints from PHP clients. |
| Trigger | **Immediate, if the PHP surface is reachable by customers.** The owner should determine that first; the answer decides whether this is an incident or a scheduled cutover item. If reachable: decide between patching the five files and restricting the routes, as an explicit security change. If not reachable: it becomes a pre-cutover item that closes when PHP stops serving these routes. |
| Contingency | A read-only query joining `advances` to `employees` and comparing each advance's owning company against the actor recorded for the change would identify whether this has been exercised — if the access log retains the actor. Deleted rows are not recoverable from the table and would need a backup. |
| Status | Open — recorded, not accepted. Found 2026-09-01 by the parity harness's mutation sweep, from an `advances/approve` status difference (PHP 500 vs Java 404) on an unknown id; the 500 shared **R-036**'s unscoped re-read, and investigating it exposed that the writes are unscoped too. `create.php` was added on 2026-09-01 after independent review pointed out the entry covered four files while the defect spans five. All six mutations were tested individually against foreign rows, each from a fresh reseed, rather than reasoned about. **Reclassified 2026-09-01** from an accepted parity divergence to a legacy production security risk, on the owner's instruction that D-058 does not by itself waive an active cross-tenant authorization vulnerability. |
| Target Date | Determined by the exposure assessment above. |
| Evidence | `hr-legacy/apis/api/advances/create.php` (employee resolved with no company predicate); `{approve,reject,pay,delete}.php` (`UPDATE`/`DELETE ... WHERE id = ?`); `update.php` for the contrasting preflight. Harness measurements, 2026-09-01: company-214 admin against advance `id=2` (company 21) and employee `id=2` (company 8), one reseed per case; Java answered 403/404 and mutated nothing in all six. `LegacyAdvanceService` + `LegacyAdvanceStore` company-scoped lookups. Sweep cases `advances/create (foreign employee)` and `advances/approve (unknown id)`. Related: **R-036** (the read-only sibling in `branches/update`); **D-058** (scope of the freeze, which this entry no longer treats as a waiver). |
| Last Reviewed | 2026-09-01 |

## R-038: Frozen PHP's `employees/analyze_excel` Answers 200 With An Empty Body

| Field | Value |
|---|---|
| Description | `employees/analyze_excel` returns **HTTP 200 with `Content-Length: 0`** — no JSON at all. The mechanism is `respond()` (`functions.php:373`), which does `echo json_encode($response_body, JSON_UNESCAPED_UNICODE \| JSON_UNESCAPED_SLASHES);` with **no check on the return value**. `json_encode()` returns `false` when it cannot encode its input, and `echo false` prints the empty string, so the endpoint emits a well-formed 200 carrying nothing. The headers are all present, which is why it does not look like a crash. |
| Category | Correctness / API contract — **frozen-PHP defect, not present in the Java port** |
| Probability | Reproduced on every attempt in the parity harness, for **two different companies** (214 and 244) with the application's own template as input, so it is not specific to one company's data. Whether it also occurs in production depends on the deployed PHP build and the company's data reaching the encoder; that has **not** been checked and no production system was accessed. |
| Impact | A client calling this endpoint receives nothing to parse. The upload succeeded and the analysis ran; only the response is lost. Java returns the analysis — 5,043 bytes for company 214 and 13,703 for company 244 — so the two stacks disagree completely on this endpoint's output. |
| Severity | Medium in PHP, none in Java. Bounded: it affects one endpoint's response, writes nothing, and destroys nothing. It is recorded because the parity harness cannot call this endpoint covered — an empty body is not a contract Java should reproduce, and comparing "PHP returns nothing" against "Java returns the analysis" is a divergence, not parity. |
| Owner | Repository owner. |
| Java disposition | **Java stays as it is.** Returning the analysis is the behaviour the endpoint is for, and no client can depend on an empty body. Reproducing the defect would mean deliberately discarding a computed result. The divergence is registered in the mutation sweep's accepted table naming this risk, so it is visible rather than silent, and the endpoint is **not** counted as covered. |
| Legacy disposition | Open. Not patched — `hr-legacy` is frozen and **D-058** places the burden of proof on the port. |
| Not yet determined | **Which value fails to encode.** The obvious candidate is invalid UTF-8 reaching the encoder from the company's own rows (`employee_excel_build_lookups()` reads branches, departments and job titles), but that was not confirmed: a CLI reproduction needs the request-scoped database connection the helper expects, and the check was not pursued further. The entry says what was measured and stops there rather than asserting a cause. Sibling endpoints `leave_balances/analyze_excel` (4,291 bytes) and `attendance/analyze_excel` (1,054 bytes) return identical bodies on both stacks, so the defect is specific to this one and not to the analyzer family or to `respond()` in general. |
| Mitigation | None applied. The one-line fix in legacy would be to check `json_encode()`'s return and fail loudly instead of emitting an empty 200 — which would surface the underlying encoding problem rather than hide it. That is a change to frozen code and is not part of any parity work. |
| Trigger | Any report of an empty response from the employee spreadsheet analysis; any decision to patch frozen PHP; the cutover, after which the endpoint is served by Java and the defect is gone. |
| Contingency | None needed: nothing is written and nothing is lost but the response. |
| Status | Open — recorded, not accepted. Found 2026-09-02 while building multipart coverage. Confirmed reproducible: `Content-Length: 0` in the response headers, and Apache's access log recording a 502-byte *response* (headers only) for the same request. |
| Target Date | Closes at cutover unless the owner decides to patch legacy sooner. |
| Evidence | `hr-legacy/apis/helpers/functions.php:373` (`echo json_encode(...)`, return value unchecked); `apis/api/employees/analyze_excel.php:30`. Harness measurements, 2026-09-02: PHP 200/0 bytes and Java 200/5,043 bytes for company 214; PHP 200/0 and Java 200/13,703 for company 244; `leave_balances` and `attendance` analyzers byte-identical on both stacks in the same run. Sweep case `employees/analyze_excel`. |
| Last Reviewed | 2026-09-02 |

## R-039: Frozen PHP Stores An Upload Under A Client-Chosen Extension In The Served Webroot

| Field | Value |
|---|---|
| Description | `uploadFile()` (`functions.php:636-664`) validates an upload by **sniffing its bytes** with `mime_content_type()` against an allowlist of JPEG, PNG, WebP and PDF — and then names the stored file using the extension from the **client-supplied filename**, `pathinfo($_FILES[...]['name'], PATHINFO_EXTENSION)` (`:655-656`). The two are unrelated. A file whose bytes sniff as an allowed type but which is *named* `x.php` is stored as `<uniqid>.php` under `AppConfig::UPLOAD_PATH`, which is the same `/uploads` tree the frozen stack serves. Reached through `company/upload_logo`, `company/upload_commercial_reg`, `employees/upload_photo` and `employee_docs/upload`. |
| Category | **Security / Unrestricted file upload leading to code execution** — a live defect in the production PHP system, independent of the migration |
| Probability | Reachable by any authenticated company admin, HR user, manager or employee, depending on the endpoint — `employees/upload_photo` and `employee_docs/upload` admit `EMPLOYEE`. It needs a file that sniffs as an allowed type while carrying a chosen extension, which is a polyglot rather than an accident. |
| Impact | Depends entirely on whether the web server executes PHP under `/uploads`. **That has not been determined**, and it is the fact that decides between "informational" and "critical": if the uploads directory is served by the same PHP handler, this is arbitrary code execution as the web user; if it is served statically, it is stored-XSS at worst for the types a browser will render. No production system was accessed to find out. |
| Severity | **Not rated, deliberately.** Rating it without knowing whether `/uploads` executes PHP would be a guess presented as an assessment. The exposure question below is the first thing to settle. |
| Owner | Repository owner. |
| Java disposition | **Not reproduced, by design — see D-154.** `LegacyFileUploads` derives the stored extension from the *sniffed* type, so an upload named `x.php` whose bytes are a PNG is stored as `<random>.png`. For every legitimate upload the two agree, which is why this changes nothing for real traffic; it closes only the mismatched case. The parity harness deliberately keeps the extension visible in both the response and the row comparison, so the divergence surfaces rather than being normalised away. |
| Legacy disposition | Open and unmitigated. **D-058** freezes legacy behaviour as the migration's oracle; as with **R-037**, that governs what the *port* does and does not waive a live vulnerability in the system serving customers. |
| Exposure assessment | **Not determined, and not determinable from this repository.** Two facts are needed and neither is in the tree: whether `/uploads` is served by a handler that executes PHP, and whether the endpoints are reachable by customers today. Establishing them requires looking at the production deployment, which needs the owner's explicit authorization for a specific read-only check — not given, and not taken. |
| Mitigation | None applied, and none belongs in a parity pull request. The options, in increasing order of disruption: serve `/uploads` with PHP execution disabled (a web-server config change, no code change, and it neutralises the code-execution half outright); or derive the extension from the sniffed type as the Java port does (a one-line change to `functions.php:655-656`, but a change to frozen code). |
| Trigger | **Settle the exposure question first.** If `/uploads` executes PHP and the endpoints are reachable, this is an incident rather than a cutover item. Otherwise it closes when PHP stops serving these routes. |
| Contingency | A read-only listing of `/uploads` for entries whose extension is outside the allowlist would show whether this has been exercised. Anything found should be treated as potentially attacker-placed. |
| Status | Open — recorded 2026-09-02, on independent review of PR #161 pointing out that **D-154** established the exposure while deciding only what *Java* would do about it. Deciding not to reproduce a vulnerability is not managing it. |
| Target Date | Determined by the exposure assessment. |
| Evidence | `hr-legacy/apis/helpers/functions.php:641` (sniff) and `:655-656` (extension from the filename) — the two lines that disagree. `apis/config/upload_slots.php` for the four affected endpoints' subdirectories. **D-154** for the Java decision and its reasoning. Related: **R-037** and **R-036**, the other legacy defects this programme records rather than ports. |
| Last Reviewed | 2026-09-02 |

## R-040: The ZKTeco Push Protocol Identifies A Device By Serial Number Alone, Over Plain HTTP By Default

| Field | Value |
|---|---|
| Description | The ADMS / PUSH SDK protocol D-156 adopts as the primary attendance-device path has no device authentication beyond the serial number in the query string, runs over plain HTTP unless the firmware offers HTTPS, and the terminals themselves carry documented injection, buffer-overflow and server-impersonation vulnerabilities including a root `SHELL` command handler (Kaspersky, 2024 — cited in `docs/superpowers/specs/2026-09-02-attendance-device-ingestion-design.md` §1.2). Anyone who learns a claimed serial number and can reach the ingest hostname can, absent controls, submit punches that flow into payroll. |
| Category | Security / Integration boundary |
| Probability | Medium once the receiver is public — serial numbers are printed on the units and appear in device menus and support tickets. |
| Impact | Medium–High: fabricated or suppressed attendance for a tenant; payroll consequences. Bounded per tenant by the registry binding (a serial resolves to exactly one company and branch), so no cross-tenant write is reachable through it. |
| Severity | Medium |
| Owner | Repository owner; design controls owned by the attendance-device slice. |
| Mitigation | **Implemented in Slice A** (specification §8, verified by the tests in §13): claim-before-ingest, so an unclaimed or deactivated serial is refused and stores nothing and its handshake carries neither stamp nor zone; serial numbers validated for shape and length before they can reach a query or create a row; the `ATTLOGStamp` **never echoed back to a device at all** (D-158 — the earlier "digits, and only from a delivery carrying punches" guard was bypassable with one fabricated punch, so the resume bookmark is given up and the handshake always answers `0`, which the content-hash idempotency makes free); metric tags drawn from a closed set; every logged value control-character-stripped and bounded; a body cap enforced ahead of the security chain **and a record-count cap, since bytes alone do not bound how many statements one request creates**; biometric template records discarded before storage. **Still to do, outside the code:** TLS at the edge, per-IP rate limiting at the reverse proxy, and soft-binding of serial to observed address with alerting. Residual risk for HTTP-only firmware is recorded per device in the inventory. |
| Trigger | Slice A implementation; any punch accepted for an unclaimed or inactive serial; any ingest traffic for a serial from an unexpected network. |
| Contingency | Deactivate the device row (ingest stops for that serial immediately); set `app.devices.ingest.enabled=false` (the `/iclock` surface disappears, devices buffer); review `device_punches` for the affected window before pairing. |
| Status | Open — recorded 2026-09-02 with D-156. The in-application controls landed with Slice A after an independent review round; the edge-side ones (TLS, rate limiting) are deployment work and remain open. |
| Target Date | Controls verified by the Slice A end-to-end tests (specification §13) before any production deployment. |
| Evidence | Specification §1.2 (Securelist analysis; protocol documentation and independent implementations showing SN-only identity and `Encrypt=None`), §8. Related: R-004. |
| Last Reviewed | 2026-09-02 |

## R-041: A Device Serial Number Is A Public Identifier, So One Tenant Can Claim Another Tenant's Terminal

| Field | Value |
|---|---|
| Description | Claiming a device (D-156, Q6: any `company_admin`/`hr` user, `POST /api/v1/devices`) is first-come and global, because the ADMS protocol offers no proof of possession -- a terminal cannot display a pairing code, and the serial number is printed on the unit and appears in its menus, support tickets and delivery notes. A tenant who learns a serial that has not yet been claimed can register it to their own company. |
| Category | Security / Multi-tenancy |
| Probability | Low. It needs a serial number and a window: a terminal that is installed and reaching the receiver but whose owner has not yet claimed it. A serial already claimed answers 409 to everyone else. |
| Impact | High within that window, in two directions at once: the squatter receives the other company's punches (attendance data for people who are not their employees), and the rightful owner cannot claim their own device and sees no punches at all. |
| Severity | Medium — high impact, narrow and self-announcing window. |
| Owner | Repository owner. **Decided 2026-09-02 (D-157)**: supervised tenant claiming is accepted for the pilot; production must not permit it. |
| Mitigation | **Pilot (accepted, D-157)**: supervised claiming by tenant `company_admin`/`hr`, with the controls Slice A carries — the claim records `registered_by_employee_id`, so the act is attributable; a serial resolves to exactly one company, so no cross-tenant read is reachable by any other route; and `GET /api/v1/devices/unclaimed` answers for a serial owned by another company exactly as for one never seen, so the API cannot be used to hunt for claimable serials. **Production (required, D-157, not yet built)**: tenant admins may not claim by serial number at all. Platform staff pre-allocate device ownership to a company and the tenant only assigns an already-owned device to one of its branches, which removes the race this risk describes rather than narrowing it. |
| Trigger | Any report of "our device is registered to another company", or a device that reaches the receiver and cannot be claimed. |
| Contingency | Deactivate the squatted registration (`PATCH is_active:false`) to stop ingestion immediately. **There is no unclaim or transfer path in Slice A**, so correcting ownership today means a manual database change. D-157 rules that unacceptable as a long-term answer: an **audited unclaim / transfer / replace-device path is required before broad production rollout**, and this risk stays open until it and the platform-mediated allocation above both exist. |
| Status | Open — recorded 2026-09-02 from an independent review of the Slice A implementation; the answer is decided (**D-157**) and the work to satisfy it is not built. Closes when platform-mediated allocation and the audited unclaim/transfer/replace path both ship. |
| Target Date | Pilot may proceed as-is; both remedies are due before device ingestion is enabled for production traffic. |
| Evidence | `docs/superpowers/specs/2026-09-02-attendance-device-ingestion-design.md` §4.2, §8, §12 (Q8); `DeviceManagementController.claim`; `AttendanceDeviceStore.claim`'s global unique key. Related: R-040. |
| Last Reviewed | 2026-09-02 |

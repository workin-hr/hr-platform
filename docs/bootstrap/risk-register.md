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

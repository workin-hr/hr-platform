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
| Mitigation | **Mechanical enforcement is what this risk needs; every mitigation below is procedural, and PR #126 showed procedure losing a ten-second race.** The mechanism has to be chosen carefully: a **required approving review does not help here**, because the named independent reviewer is read-only and cannot approve (`docs/agents/responsibility-matrix.md`), so a human approval satisfies that count while a review round is still in flight — exactly PR #126. Two settings are needed, and each covers what the other misses. **`required_conversation_resolution`** blocks a merge while threads are open — but it reports nothing when the reviewer has not posted yet, and nothing after fixes are pushed and the previous head's threads are resolved, so on its own it does not prove the *final* head was reviewed. The **`independent-review` status check** (`.github/workflows/independent-review-gate.yml`) covers exactly that: it fails until the named reviewer has submitted a round on the pull request's current head SHA, for every open pull request pointing at that commit. **Stated precisely, because the obvious summary overclaims:** together they prove *a round happened on this head* and *no thread is left open*. They do **not** prove the findings were addressed. Thread resolution is a state anyone with write access can set, including the author, without changing a line or answering — so a pull request can satisfy both settings with every finding ignored. Step 7 of the merge sequence is therefore still a **human** obligation, and no mechanical signal in this repository currently verifies it. Closing that would need a qualifying-answer check (a reply per resolved thread, or a clean follow-up round) which does not exist yet and is not proposed here; **an owner decision is owed on whether to build one.** Until then, do not read a green merge box as evidence that findings were handled. `scripts/check-branch-protection.sh` requires conversation resolution alongside the review count, `enforce_admins`, no force pushes, and the required status check, so whenever D-013's deferral is revisited the protection applied is verified against the failure that actually occurred. `scripts/check-branch-protection.sh` requires **both** contexts, so protection that carries only the validation check no longer reports as complete. **Owner step still outstanding, and its precondition is now cleared.** The `independent-review` status is advisory until a human adds it to `main`'s required contexts, which cannot happen while branch protection itself is Deferred (D-013). It previously carried a second blocker: the gate ran on `pull_request`, so a run executed the workflow file *from the pull request* while holding `statuses: write`, and a revision could have published the status green before the named reviewer saw that change — the gate certifying its own bypass. **D-122 (2026-08-28) resolved that**: the workflow moved to the privileged `_target` trigger, which runs the base branch's trusted copy, under a conditional exception that `validate_workflow_safety()` withdraws the moment the file gains a checkout. Making the context required is therefore once again purely the D-013 question, not two questions. Until then: follow the Human Approval And Merge Sequence in `docs/bootstrap/manual-setup-checklist.md` for the first real pull request into `main`. Branch-protection enforcement (required reviewers, required status checks, no force-push/direct-push) is explicitly Deferred, not merely pending — see D-013 in `docs/bootstrap/decision-log.md` — so until revisited, rely on temporary, non-platform-enforced mitigation instead: manual PR review before every merge, a green required CI run before every merge, restricted `main` write access limited to trusted human owners, and no direct pushes to `main` by team convention. **Strengthened 2026-08-28 by D-121**, which names the independent reviewer the workflow previously left unassigned: `chatgpt-codex-connector[bot]` reviews the whole pull request, its findings are addressed or answered on the thread before merge, and its quota being exhausted (R-009) makes the gate unavailable rather than waived. This closes the specific gap that produced the PR #120 deviation; it remains convention rather than platform enforcement, so the risk stays Open. |
| Trigger | Phase 0 is declared complete without a real, evidenced human-approved merge; or a pull request merges to `main` **without a completed independent review round on its final head whose findings have all been fixed or answered** — which covers both no round at all (PR #120) and a round that had posted but whose findings were still unaddressed at merge (PR #126) |
| Contingency | Do not declare Phase 0 complete; keep `docs/bootstrap/definition-of-done.md` unmet until the sequence is followed once and evidenced |
| Status | Open — Accepted Residual Risk / Non-blocking. The manual review-and-merge sequence (steps 2–10 of the Human Approval And Merge Sequence in `docs/bootstrap/manual-setup-checklist.md`) has run and is evidenced by D-014; mechanical branch-protection enforcement (step 1) remains Deferred under D-013, an accepted plan limitation, not a configuration gap. This risk stays Open because review and merge governance still cannot be mechanically enforced at the platform level and depends on the temporary mitigation above actually being followed — but per D-015 it no longer blocks Phase 0 completion; it is tracked here as an accepted, non-blocking residual risk, not an open blocker. **Realized twice.** *2026-08-27, PR #120*: the merge proceeded on the owner's own approval with no independent review exercised as a distinct gate, recorded in `docs/legacy/WAVE12_COMPLETION_AUDIT.md`. Corrective action: D-121 named the reviewer the workflow had left unassigned. *2026-08-28, PR #126*: the round existed but its findings did not survive to the merge — six findings, four P1, posted ten seconds before the squash. The first realization showed the gate had nobody assigned; the second showed that assigning somebody is not sufficient while nothing mechanically blocks the merge. Both are this risk's stated mitigation failing in exactly the way it predicts. Reviewed 2026-08-28. |
| Target Date | Before Phase 0 is declared complete |
| Evidence | Historical baseline: `git log --all --merges` (2026-08-02) found no merge commits and `git log --all --format="%an %ae"` found a single author (`Codex <codex@local>`), before pull request #1 existed. Current: pull request #1 (`https://github.com/workin-hr/hr-platform/pull/1`), merge commit `cf997818fbabb6f02f9b15c845da06757713a97a`, merged by Karim Taha (`karimtismail`) on 2026-08-03 — see D-014 (`docs/bootstrap/decision-log.md`) for the full record. D-013 (`docs/bootstrap/decision-log.md`) for the branch-protection deferral. **Second instance, 2026-08-27**: pull request #120 (merge commit `4caff98`) merged on the owner's own approval with no independent review round; recorded in `docs/legacy/WAVE12_COMPLETION_AUDIT.md` under "Validation outcome". Corrective action: D-121 (`docs/bootstrap/decision-log-wave12r.md`) names `chatgpt-codex-connector[bot]` as the independent reviewer. **Step 6's evidence is missing for PRs #126-#129 (2026-08-28)**: GitHub records zero `APPROVED` reviews on any of the four, so D-123's merge record leaves the approving-human field open rather than inferring it from the merger. Whether that is a third realization or simply an unrecorded-but-performed approval is the open question D-123 states; the register does not guess between them. **Third instance, 2026-08-28, PR #126** — a race rather than an omission, and the reason this mitigation needs a mechanical component: the review round on head `1a3a190` posted at 06:49:16 with six findings including four P1s, and the squash merge (`2f67c47`) completed at 06:49:26, ten seconds later. Both required checks were green and the pull request had already been through three review rounds, so nothing in the GitHub UI signalled a round was in flight. The six findings were fixed on the branch as `16ec1a2` and land in a follow-up pull request; `main` carried them in the meantime. |
| Last Reviewed | 2026-08-28 |

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
| Status | Open — **both quotas were restored by 2026-08-27** (see Evidence); the risk itself is the missing low-balance alerting, which is unchanged. Historical detail from when it was firing: both accounts confirmed blocked as of 2026-08-25 08:49 UTC (PR #121's checks). GitHub Actions failures started between the last green `Backend Validate` run (2026-08-24T16:43:39Z, `phase1/wave-12.7-complete`) and the first billing-blocked run (2026-08-24T17:38:08Z, same branch) — roughly a one-hour window that day. A separate, unrelated, and already-fixed issue was found and closed in the same audit: `dependabot.yml` referenced a `dependencies` label that did not exist in the repo, so Dependabot could not label PRs #118/#119 — the label was created and backfilled onto both PRs on 2026-08-25; this did not affect either check's pass/fail and did not require the org owner. |
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

## R-012: `workforce_planning` Discloses Another Company's Organizational Names

| Field | Value |
|---|---|
| Description | `save_target.php` and `update.php` accept unvalidated foreign `branch_id`, `department_id` and `job_title_id` (only `create.php` validates them), and the three `LEFT JOIN`s supplying `branch_name`, `department_name` and `job_title_name` match on id alone with **no tenant predicate**. A `company_admin` or `hr` user in any tenant can therefore write another company's id into their own planning row and read that company's **name** back out of `list.php`. Iterating ids enumerates a competitor's branches, departments and job titles. |
| Category | Security / Tenant isolation |
| Probability | High — two ordinary API calls by an already-authenticated user, no special conditions or timing. Demonstrated by a regression that performs the attack. |
| Impact | Medium. A genuine **cross-tenant read**, which is a boundary this platform otherwise holds. Bounded to organizational **names**: no employee, payroll or personal data crosses. |
| Severity | Medium |
| Owner | Repository owner. **A decision is outstanding** — see D-131 and `docs/bootstrap/open-questions.md`. |
| Mitigation | None applied. The port reproduces legacy exactly, and fixing it in Java alone would make the two systems answer differently for the same request — masking the defect rather than resolving it. Filed upstream as `hr-legacy#33` with a proposed fix (validate in the write paths, tenant the joins, and check existing rows for ids already written across tenants). |
| Trigger | Any `workforce_planning` row whose `branch_id`/`department_id`/`job_title_id` does not belong to its own `company_id`; a `list.php` response carrying a name from another tenant. |
| Contingency | Change legacy first (`hr-legacy#33`) and port the change in the same wave. If the owner elects to wait rather than ship parity, Wave 13.4b holds until that lands. |
| Status | **Open — NOT accepted.** D-131 is `PROPOSED` and PR #141 must not merge on a green gate alone. This entry deliberately does not claim acceptance: a confirmed cross-tenant disclosure belongs in the register that security triage reads, and recording it as unresolved is the honest way to do that — omitting it until a decision arrives would hide it from exactly the review that should see it. |
| Evidence | `hr-legacy@d113204` `apis/api/workforce_planning/{save_target,update,list}.php`; regression `LegacyWorkforcePlanningEndToEndTest#saveTargetLeaksAnotherCompanysBranchNameThroughTheUntenantedJoin`; `docs/security/threat-model.md`'s tenant ↔ tenant row; upstream `hr-legacy#33`. |
| Target Date | Gated on the owner's D-131 decision. |
| Last Reviewed | 2026-08-29 |

## R-013: `profile/register_push_token.php` Cannot Succeed Against The Frozen Schema

| Field | Value |
|---|---|
| Description | `register_push_token.php` executes `INSERT INTO push_tokens (employee_id, company_id, token, platform) ... ON DUPLICATE KEY UPDATE`. The `push_tokens` table in `hr-legacy@d113204` has **no `company_id` column** and **no unique key** on `token` or on any other column. The statement therefore fails on an unknown column for every caller — employee session and company session alike — and, were the column present, the `ON DUPLICATE KEY UPDATE` clause would still never fire, appending a row per call instead of upserting. The Java port reproduces the failure rather than repairing the statement (D-058). |
| Category | Correctness / Schema drift |
| Probability | Certain, on every call. Not a race or an edge case: no request to this endpoint can have ever succeeded against this schema. |
| Impact | Low today, and the reason is that nothing depends on it. Push delivery does not work end to end in either direction (F-08); mobile's `register_push_token` call is commented out; the ETL decision drops `push_tokens` entirely; and `FCM_SERVER_KEY` is a placeholder (`hr-platform#22`). The endpoint is dead code with a live route. It becomes blocking the moment push is built for real, because the client half will call this route first. |
| Severity | Low (Phase 1); a prerequisite for `hr-platform#22` |
| Owner | Repository owner. **Two questions are open** — see `docs/bootstrap/open-questions.md`: whether production's `push_tokens` has drifted from the dump, and whether a company-owned push token is intended at all. |
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
| Evidence | `hr-legacy@d113204` `apis/helpers/otp_helper.php:105-172` (`otp_count_recent_sends`, `otp_assert_can_send`) and `:72-86` (`otp_clear_for_phone` soft-invalidates). Schema: `mysql_workin.schema.sql:679-686` — `otp_codes` is `id, phone, code, is_used, expires_at, created_at`; no `CREATE TABLE otp_request_logs` exists anywhere in the dump. **No regression asserts this yet**: the limiter is ported in Wave 13.1 and the assertion lands with it. Until then this entry rests on the source and schema read above, not on an executed test. |
| Last Reviewed | 2026-08-29 |

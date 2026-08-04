# Consolidated Task Matrix

## Purpose

Converts every finding and open question produced by Discovery so far —
the 24 `hr-legacy` security/correctness findings, the 8 `hr-platform`
pre-migration readiness gaps (PMR-01..10, minus PMR-05/06 which are
tracked as sub-items of PMR-06 rather than standalone GitHub issues), and
the 11 new findings from the Flutter client Discovery pass — into one
place with a consistent field set, so nothing has to be rediscovered by
reading prose across five different documents. This document does not
replace the source documents (`docs/security/threat-model.md`,
`docs/legacy/business-rule-extraction.md`,
`docs/migration/pre-migration-readiness-gap-analysis.md`,
`docs/api/flutter-request-response-compatibility.md`) — it indexes them.

This is a planning artifact. It does not itself close, fix, or implement
anything, per this repository's [`CLAUDE.md`](../../CLAUDE.md) boundary.

## Field Definitions

- **Severity**: technical/business impact if the finding is never
  addressed (`Critical`/`High`/`Medium`/`Low`), inherited from the
  originating issue where one exists.
- **Priority**: sequencing recommendation independent of severity —
  a `Low`-severity item can still be `P0` if it blocks something else
  (e.g. an orphaned-endpoint question that must be answered before
  writing an OpenAPI contract for that module).
  - `P0` — blocks the technical spike or any backend implementation start
  - `P1` — blocks cutover of the specific module it concerns
  - `P2` — should be designed/fixed correctly during the rewrite, not
    independently blocking
  - `P3` — backlog / opportunistic, no migration dependency
- **Owner Type**: who must act to close this — `Engineering` (write code
  or a technical design), `Product` (make a scope/priority call),
  `Security` (approve a remediation approach or verify a live control),
  or `Manual Operator` (perform a one-time action outside any codebase,
  e.g. a GCP Console check or a credential-custody confirmation).
- **Blocking?**: `Blocking` (names what it blocks) or `Non-blocking`.
- **Evidence For Closure**: what must exist for this row to be marked
  closed — not "fixed the bug" in the abstract, but the concrete artifact
  (a merged PR against the Java rewrite with a specific test, a signed-off
  ADR, a screenshot, a product decision recorded in writing).
- **Dependency / Target Milestone**: what has to happen first, and which
  named milestone from the
  [Migration-Readiness Gate](./pre-migration-readiness-gap-analysis.md#migration-readiness-gate)
  this row is tied to.

## Section A — `hr-legacy` Security And Correctness Findings (Issues #2–#25)

All 24 filed 2026-08-04, `bug` label, repo `workin-hr/hr-legacy`. Full
finding text lives in the issues themselves and in
`docs/security/threat-model.md` / `docs/legacy/business-rule-extraction.md`.

| # | Title | Severity | Priority | Owner Type | Blocking? | Evidence For Closure | Dependency / Target Milestone |
|---|---|---|---|---|---|---|---|
| [#2](https://github.com/workin-hr/hr-legacy/issues/2) | Any HR-role user can delete/suspend/view any other company platform-wide | Critical | P0 | Engineering + Security | Blocking: any-implementation gate | Java tenant-isolation module has an automated test proving cross-tenant access is rejected for this exact operation class | Before any backend implementation begins |
| [#3](https://github.com/workin-hr/hr-legacy/issues/3) | Cross-tenant employee password takeover via dashboard employee edit | Critical | P0 | Engineering + Security | Blocking: any-implementation gate | Same as #2 — tenant-isolation test suite covers password-mutation endpoints specifically | Before any backend implementation begins |
| [#4](https://github.com/workin-hr/hr-legacy/issues/4) | OTP code returned in API response when `DEBUG=true` | Critical (unconfirmed-live) | P0 | Security (verify) then Engineering | Blocking: PMR-05 closure, any-implementation gate | Manual operator confirms live `DEBUG` value (screenshot/config export); Java rewrite never echoes OTP under any config flag | Before any backend implementation begins; depends on PMR-05 |
| [#5](https://github.com/workin-hr/hr-legacy/issues/5) | Cross-tenant IDOR in API `advances` module | High | P0 | Engineering + Security | Blocking: any-implementation gate | Same tenant-isolation test suite covers `advances` approve/reject/pay/delete/create | Before any backend implementation begins |
| [#6](https://github.com/workin-hr/hr-legacy/issues/6) | Cross-tenant IDOR across 10 dashboard modules | High | P0 | Engineering + Security | Blocking: any-implementation gate | Tenant-isolation test suite parameterized across all 10 named modules | Before any backend implementation begins |
| [#7](https://github.com/workin-hr/hr-legacy/issues/7) | JWT valid 10 years; no admin token revocation; password change/reset never invalidates sessions | High | P0 | Engineering + Security | Blocking: PMR-08/ADR-0005 acceptance, auth-remediation design closure | ADR-0005 accepted referencing `docs/security/authentication-remediation-design.md`; Java rewrite implements short-lived JWT + refresh + revocation | Before any backend implementation begins |
| [#8](https://github.com/workin-hr/hr-legacy/issues/8) | `hr_permissions` matrix enforced on ~21 endpoints, ignored on ~130+ others | Medium | P1 | Engineering | Blocking: cutover of any permission-gated module | New system enforces permissions structurally (query-layer guard, not per-endpoint opt-in) — architecture review confirms this before first module cutover | Before any module's cutover |
| [#9](https://github.com/workin-hr/hr-legacy/issues/9) | Unauthenticated `complete_company_registration.php` trusts a guessable `company_id` | Medium | P1 | Engineering | Blocking: cutover of company-onboarding module | New onboarding flow uses a non-guessable, single-use token instead of sequential ID | Before onboarding module cutover |
| [#10](https://github.com/workin-hr/hr-legacy/issues/10) | OTP verification has no rate limiting / brute-force protection | Medium | P1 | Engineering | Blocking: cutover of auth module | Rate limiting implemented and load-tested on OTP verify path in new system | Before auth module cutover |
| [#11](https://github.com/workin-hr/hr-legacy/issues/11) | Single shared platform-admin password — no per-admin identity, no MFA, no audit trail | Low | P2 | Product + Security | Non-blocking | Product decision recorded on whether platform-admin identity model changes in the new system | Design-time input, not a gate |
| [#12](https://github.com/workin-hr/hr-legacy/issues/12) | `payslips/create.php` silently drops base pay for daily-wage-mode employees | High | P1 | Engineering | Blocking: cutover of payroll module | Payslip-total calculation has one canonical implementation with a test covering daily-wage mode explicitly | Before payroll module cutover |
| [#13](https://github.com/workin-hr/hr-legacy/issues/13) | Three independent, divergent implementations of payslip-total math | Medium | P1 | Engineering | Blocking: cutover of payroll module | Single shared calculation function/service, unit-tested, used by every payslip code path | Before payroll module cutover |
| [#14](https://github.com/workin-hr/hr-legacy/issues/14) | `salary_contracts.housing_allowance` cannot be set to nonzero anywhere in the API | Medium | P2 | Engineering + Product | Non-blocking | Product confirms intended behavior (dead field vs. missing feature) before the new schema is finalized | Before payroll module schema freeze |
| [#15](https://github.com/workin-hr/hr-legacy/issues/15) | Mobile "logout" silently deactivates the employee's account | High | P1 | Engineering | Blocking: cutover of auth/employee-lifecycle module | New logout endpoint never mutates account-active status; regression test asserts this | Before auth module cutover |
| [#16](https://github.com/workin-hr/hr-legacy/issues/16) | QR check-in skips the 2-hour minimum-gap anti-fraud rule | Medium | P2 | Engineering + Product | Non-blocking (real-world exploitability depends on whether a third client uses it — see row F-04) | Product confirms whether QR check-in has a live caller; if yes, promote to P1 and add the same anti-fraud gap check used by GPS/manual check-in | Depends on F-04 answer; before attendance module cutover if a live caller is confirmed |
| [#17](https://github.com/workin-hr/hr-legacy/issues/17) | Manager role gets unscoped company-wide attendance visibility despite doc-comments claiming department scoping | Medium | P2 | Engineering + Product | Non-blocking | Product confirms intended scoping; new system's manager-role query is scoped to match whichever answer is confirmed | Before attendance module cutover |
| [#18](https://github.com/workin-hr/hr-legacy/issues/18) | Manager can approve/reject any employee's leave/permission request company-wide | Medium | P2 | Engineering + Product | Non-blocking | Same pattern as #17 — product confirms intended scope, new system enforces it | Before leave/requests module cutover |
| [#19](https://github.com/workin-hr/hr-legacy/issues/19) | Two parallel, non-identical employee self-registration endpoints | Medium | P1 | Engineering | **Resolved by F-01** (`join_company` confirmed canonical) — remaining action is confirming `register_employee` is fully dead before dropping it | `join_company` behavior ported as the single registration flow; `register_employee` confirmed dead across all callers, not just the two Flutter clients | Before auth/onboarding module cutover |
| [#20](https://github.com/workin-hr/hr-legacy/issues/20) | Employee deletion cascade-deletes payroll/financial history, working around the schema's own RESTRICT constraint | Low | P2 | Engineering + Product | Non-blocking | Product confirms whether financial-history retention on deletion is a compliance requirement; new schema encodes whichever answer is given as a real constraint, not an app-level workaround | Before employee-lifecycle module cutover |
| [#21](https://github.com/workin-hr/hr-legacy/issues/21) | Payroll batch creation uniqueness is app-level only — no DB constraint | Low | P2 | Engineering | Non-blocking | New schema has a real unique constraint (or equivalent) on the batch-period key | Before payroll module cutover |
| [#22](https://github.com/workin-hr/hr-legacy/issues/22) | `payroll_batches/calculate.php` isn't transactional, unlike `finalize`/`reopen` | Low | P2 | Engineering | Non-blocking | New payroll-calculation code path is transactional, verified by a test that simulates a mid-calculation failure | Before payroll module cutover |
| [#23](https://github.com/workin-hr/hr-legacy/issues/23) | `penalties/report.php`'s "CSV" export is actually XLSX | Low | P3 | Engineering | Non-blocking | New export endpoint's content-type and extension match its actual format | Backlog |
| [#24](https://github.com/workin-hr/hr-legacy/issues/24) | `attendance/stats.php` has mislabeled/placeholder response fields | Low | P3 | Engineering | Non-blocking | New stats endpoint's field names match their actual semantics; no hardcoded placeholder values | Backlog |
| [#25](https://github.com/workin-hr/hr-legacy/issues/25) | Bulk attendance deletion has no dry-run/audit trail | Low | P2 | Engineering + Product | Non-blocking | Product confirms whether a dry-run/confirm step and audit log are required; new endpoint implements whichever is decided | Before attendance module cutover |

## Section B — `hr-platform` Pre-Migration Readiness Gaps (Issues #9–#16)

Full gap text lives in
[`pre-migration-readiness-gap-analysis.md`](./pre-migration-readiness-gap-analysis.md).
This section only restates the tracking fields; it does not duplicate the
Description/Why-It-Matters prose already there.

| # | Title | Severity | Priority | Owner Type | Blocking? | Evidence For Closure | Dependency / Target Milestone |
|---|---|---|---|---|---|---|---|
| [#9](https://github.com/workin-hr/hr-platform/issues/9) | PMR-01: Dashboard Discovery coverage incomplete (~75 of 92 files unread) | Medium | P1 | Engineering | Blocking: full-confidence ADR-0002/ADR-0003 acceptance | Remaining ~75 dashboard files read and cross-referenced against `existing-endpoint-inventory.md` | Before ADR-0002/0003 acceptance |
| [#10](https://github.com/workin-hr/hr-platform/issues/10) | PMR-02: Flutter mobile client contract unknown | — | — | — | **Closed by this Discovery pass** | Superseded — see `docs/api/flutter-request-response-compatibility.md`, status updated to `Ready` in the gap-analysis doc (Update 2026-08-04) | Complete |
| [#11](https://github.com/workin-hr/hr-platform/issues/11) | PMR-03: Production data inaccessible | Critical-High | P0 | Manual Operator + Engineering | Blocking: data-migration phase | Someone with production DB access runs the query set already documented in the empty migration-template docs (`data-quality-analysis.md` etc.) and results are recorded | Before data-migration phase |
| [#12](https://github.com/workin-hr/hr-platform/issues/12) | PMR-04: Attendance device/hardware Discovery not started | High | P1 | Manual Operator + Engineering | Blocking: device-integration phase | Device Discovery doc filled in with real hardware/protocol evidence | Before device integration |
| [#13](https://github.com/workin-hr/hr-platform/issues/13) | PMR-07: Technical spike not yet executed | High | P0 | Engineering | Blocking: any-implementation gate | Spike executed per `technical-spike-plan.md`, exit criteria evaluated and recorded | Before any backend implementation begins |
| [#14](https://github.com/workin-hr/hr-platform/issues/14) | PMR-08: All 8 (now 9, see ADR-0009 below) architecture ADRs remain Proposed | High | P0 | Product + Engineering | Blocking: any-implementation gate | Each ADR moved to `Accepted` by an explicit human decision, recorded in the ADR file itself | Before any backend implementation begins |
| [#15](https://github.com/workin-hr/hr-platform/issues/15) | PMR-09: No detailed per-module migration execution plan | Medium | P1 | Engineering | Blocking: first module cutover | Per-module execution plan doc exists, covering sequencing, rollback, and acceptance criteria per module | Before any module's cutover |
| [#16](https://github.com/workin-hr/hr-platform/issues/16) | PMR-10: No migration-correctness test plan or differential-testing harness | High | P0 | Engineering | Blocking: data-migration phase | Differential-testing harness exists and has run at least once against a non-trivial data sample | Before data-migration phase |

## Section C — New Flutter Discovery Findings (F-01–F-12)

Findings identified during the 2026-08-04 read-only Flutter client
Discovery pass. Full text in
[`flutter-request-response-compatibility.md`](../api/flutter-request-response-compatibility.md)
unless noted. IDs are internal to this matrix (not GitHub issue numbers)
except where a GitHub issue was filed to track the action item.

| ID | Title | Severity | Priority | Owner Type | Blocking? | Evidence For Closure | Dependency / Target Milestone |
|---|---|---|---|---|---|---|---|
| F-01 | `join_company` confirmed canonical registration flow | Low (informational — resolves an ambiguity) | P1 | Engineering | Non-blocking, closes an open question on #19 | Tracked via `hr-legacy#19` comment (2026-08-04) | Before auth/onboarding module cutover |
| F-02 | Plaintext SharedPreferences token storage + no client-side refresh capability | High | P0 | Engineering + Security | Blocking: ADR-0005 acceptance, auth-remediation rollout | GitHub issue [`hr-platform#18`](https://github.com/workin-hr/hr-platform/issues/18); closes when `docs/security/authentication-remediation-design.md`'s client-side workstream is implemented and shipped | Before/alongside JWT lifetime change in new backend |
| F-03 | *(merged into F-02 — client storage and refresh capability are one workstream)* | — | — | — | — | — | — |
| F-04 | QR check-in has no confirmed live client caller — real-world exploitability of #16 depends on this | Low (informational) | P2 | Product | Non-blocking until answered | Tracked via `hr-legacy#16` comment (2026-08-04); closes when product confirms whether a third client (kiosk/tablet/dashboard) calls `check_in_qr` | Before attendance module cutover, if a live caller is confirmed |
| F-05 | Desktop client references a server endpoint that was never built (`set_employee_attendance_method`) | Low | P2 | Product | Non-blocking | GitHub issue [`hr-platform#19`](https://github.com/workin-hr/hr-platform/issues/19); closes when product confirms abandoned-vs-planned | Before attendance-config module cutover, if confirmed planned |
| F-06 | No client-side environment/staging switch — both apps hardcode a single production `baseUrl` | Low | P2 | Engineering | Blocking: staged cutover testing, if wanted | GitHub issue [`hr-platform#20`](https://github.com/workin-hr/hr-platform/issues/20); closes when environment configuration is added to the client, if a staged-cutover test plan requires it | Before data-migration/cutover phase, only if staged testing against the new backend is chosen |
| F-07 | Desktop forced-update/maintenance-mode remote-config mechanism must be preserved | Medium | P1 | Engineering | Blocking: desktop-facing module cutover | GitHub issue [`hr-platform#21`](https://github.com/workin-hr/hr-platform/issues/21); closes when the new backend's `configs`-equivalent surface serves the same version-gate fields, verified against a real desktop build | Before any module cutover that desktop clients use |
| F-08 | FCM push notification delivery must be preserved as a backend integration requirement | Medium | P1 | Engineering | Blocking: notifications module cutover | GitHub issue [`hr-platform#22`](https://github.com/workin-hr/hr-platform/issues/22); closes when new backend registers/sends via FCM and mobile push-token registration gap is separately confirmed | Before notifications module cutover |
| F-09 | No offline persistence in either client — confirmed absent | Low | P3 | Product | Non-blocking | GitHub issue [`hr-platform#23`](https://github.com/workin-hr/hr-platform/issues/23); closes when product records an explicit decision (in scope for a future release, or not) | Backlog |
| F-10 | Three real frontends exist (PHP dashboard, Flutter desktop, Flutter mobile), not two — dashboard-vs-desktop fate | High (architecture-level) | P0 | Product | **Decision recorded** 2026-08-04 (Option E, role-based split); remaining work is closing `hr-legacy#26` and the parity/feature-disposition items below, not the decision itself | Tracked via `docs/adr/ADR-0009-dashboard-vs-desktop-admin-client.md`; closes when `Status` moves to `Accepted` (pending Engineering sign-off + `hr-legacy#26` + feature-disposition confirmation, per that ADR's Validation Evidence) | Before ADR-0009 `Accepted`, before any-implementation gate for admin-surface modules |
| F-11 | `dsa_priv.pem` (desktop auto-update signing private key) custody unverified from source | Low | P3 | Manual Operator | Non-blocking | Tracked via [`hr-platform#24`](https://github.com/workin-hr/hr-platform/issues/24) (`docs/security/gcp-firebase-credential-verification-checklist.md`, item 8); closes when whoever performs desktop releases confirms secure storage (password manager / CI secret, not a plain file) | Non-blocking, verify opportunistically |
| F-12 | Manager-role employees can log into the PHP dashboard (`doHrLogin()`, `role IN ('hr','manager')`) but cannot log into the desktop app (`login_desktop.php`'s HR branch only accepts `role = 'hr'`) | Medium | P0 (for the ADR-0009 retirement path specifically) | Engineering | Blocking: any retirement of dashboard's company/HR session paths under ADR-0009 | [`hr-legacy#26`](https://github.com/workin-hr/hr-legacy/issues/26); closes when `login_desktop.php` accepts Manager-role employees with authorization parity confirmed across the rest of the desktop-consumed API surface | Before dashboard company/HR page retirement (`hr-platform#25`) |

## Summary Counts

| Priority | Count |
|---|---|
| P0 (blocks spike/any-implementation) | 10 |
| P1 (blocks specific module cutover) | 12 |
| P2 (fix correctly during rewrite, non-gating) | 11 |
| P3 (backlog) | 4 |

| Owner Type | Count (rows where this owner type appears) |
|---|---|
| Engineering | 31 |
| Product | 14 |
| Security | 6 |
| Manual Operator | 3 |

Counts are informational (rows can have more than one owner type) — use
the per-row table as the source of truth, not this summary.

## Evidence

Section A: `hr-legacy` issues #2–#25, `docs/security/threat-model.md`,
`docs/legacy/business-rule-extraction.md`. Section B:
`docs/migration/pre-migration-readiness-gap-analysis.md`, `hr-platform`
issues #9–#16. Section C:
`docs/api/flutter-request-response-compatibility.md`,
`docs/security/pre-migration-flutter-credential-inventory.md`,
`docs/adr/ADR-0009-dashboard-vs-desktop-admin-client.md`, `hr-platform`
issues #18–#25, `hr-legacy` issue #26 and issue comments on #16 and #19
(2026-08-04).

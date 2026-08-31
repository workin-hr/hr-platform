# ADR-0009: Fate Of The PHP Dashboard Relative To The Flutter Desktop Admin Client

## Metadata

| Field | Value |
|---|---|
| ADR ID | ADR-0009 |
| Title | Fate of the PHP dashboard relative to the Flutter desktop admin client |
| Status | Accepted |
| Date | 2026-08-04 (accepted 2026-08-05 — see `docs/bootstrap/decision-log.md` D-025) |
| Owners | Product (primary), Engineering (feasibility input) |
| Deciders | Product/business owner(s) with authority over admin-surface scope; Engineering lead for feasibility sign-off |
| Related Issues | `hr-platform#9` (PMR-01), `hr-platform#14` (PMR-08, ADR acceptance), `hr-platform#25` (retirement follow-up), `hr-legacy#2`/`#3`/`#6` (dashboard-side security findings that this decision affects the fix location of), `hr-legacy#26` (Manager-role desktop-login parity gap, blocks retirement) |
| Supersedes | None |
| Superseded By | None |

Valid `Status` values: `Proposed`, `Accepted`, `Rejected`, `Superseded`,
`Deferred`. New ADRs must start `Proposed`.

## Context

Read-only Discovery into the real Flutter client source (2026-08-04,
`docs/api/flutter-request-response-compatibility.md`, "Desktop/Mobile
Divergence" finding) established a fact that changes this system's shape:
**there are three real frontends against the `hr-legacy` backend, not
two.** `workin_desktop` is not a platform variant of the employee mobile
app — it is a full, independent, native company-admin/HR management
client, authenticating separately (`login_company`/`login_desktop`), with
an `api_constants.dart` covering nearly the entire admin surface:
employees, branches, departments, shifts, job titles, attendance
administration, payroll batch lifecycle, payslips, penalties, advances
(including the exact operations with the confirmed cross-tenant IDOR in
`hr-legacy#5`), workforce planning, company settings, HR
employee/permission management, and company account
management/deletion — largely overlapping the PHP dashboard's own scope.

This means the system currently has **two independent, non-code-sharing
admin surfaces** doing much of the same job: the PHP session-based
`dashboard/` (92 files, direct MySQL access, its own auth/query layer) and
the native `workin_desktop` app (JWT-based, its own auth/query layer).
Neither shares code with the other. Migration planning cannot proceed on
an assumption about which of these survives, because that assumption
determines: how much of `docs/legacy/business-rule-extraction.md`'s
dashboard-side findings need a *new* fix location versus an *existing*
one being retired; whether the new backend needs to serve one admin API
consumer or continue serving two with different auth/contract shapes;
and whether any new admin web application needs to be built at all, or
only a backend.

**A relevant signal was given directly in this conversation (2026-08-04,
verbatim, unedited)**: *"flutter mobile & desktop don't change anything,
we ha[ve]n't admin dashboard you should make it using nextjs or using jte
choose the best of thing in 2026 and in future."* This is recorded here
as a real product-owner input, not discarded — but per the explicit
instruction accompanying it ("Do not choose an option without evidence"),
it is treated as **one input requiring confirmation**, not as a closed
decision, for two reasons: (1) the phrasing is informal and could
plausibly mean either "replace the PHP dashboard with a new web app" or
"the PHP dashboard doesn't need attention, focus elsewhere" — these lead
to materially different migration plans, and (2) "don't change" the
Flutter apps is a statement about client-side modification scope, which
is a different question from whether the *dashboard* is retired,
coexists, or is replaced — the ADR needs an explicit answer to that
second question specifically, not an inference from the first.

## Decision

**Accepted 2026-08-05** (`docs/bootstrap/decision-log.md` D-025). The
product/business owner made the core decision directly in conversation
on 2026-08-04 (see the direct quotes in Context above and in Option E
below); it is captured here as **Option E — Role-based split**,
described in full under Alternatives Considered. The one remaining
factual item (desktop-access-universality) was confirmed directly on
2026-08-05 — see Validation Evidence.

**Recorded decision, in one sentence**: platform-level administration of
Workin itself stays web (the existing dashboard's `admin`-role surface,
or a narrower purpose-built replacement — see Consequences); every
subscribed/joined company's own administration — company-owner and
HR/Manager staff alike — consolidates onto the native desktop app,
retiring the dashboard's `company_logged_in`/`hr_logged_in` session paths;
individual employees remain mobile-only. This directly resolves the
"Desktop/Mobile Divergence" open question this ADR was created to track.

## Alternatives Considered

### Option A — Coexist permanently

The new backend serves both the desktop app's admin API contract and a
new (or ported) dashboard-equivalent web experience, indefinitely, as two
supported admin surfaces.

- **Evidence needed**: confirmation that both a native desktop
  installable app *and* a browser-based admin panel are genuinely
  required by different user segments (e.g. IT-managed desktop rollout
  for on-site HR staff vs. browser access for remote/traveling admins) —
  not just historical accident. Cost/maintenance-burden sign-off from
  whoever owns the engineering budget, since this means permanently
  maintaining two admin client codebases against one backend.

### Option B — Desktop replaces the dashboard

The PHP dashboard is retired; `workin_desktop` becomes the sole
administrative surface, and the new backend only needs to serve the
desktop app's existing contract shape (already substantially documented
in `docs/api/three-frontend-api-usage-matrix.md`).

- **Evidence needed**: confirmation that every current dashboard user
  can install and run a native Windows/Mac desktop application (no
  browser-only or locked-down-machine admin users who need dashboard
  access); confirmation that dashboard-only capability with no desktop
  equivalent (`salary_calculator`, `setting_templates`, `activities`, the
  5-tab `company_settings` granularity — see
  `docs/api/three-frontend-api-usage-matrix.md`) is either already
  covered by desktop, acceptable to drop, or scheduled to be added to
  desktop before cutover.

### Option C — Dashboard remains primary, desktop retired or frozen

The PHP dashboard (or a like-for-like modern replacement web app) becomes
the sole administrative surface; `workin_desktop` is frozen (no further
feature work) or retired.

- **Evidence needed**: this directly conflicts with the "Flutter desktop
  doesn't change" signal recorded above, read as "desktop stays as an
  active, maintained client" — if that reading is correct, this option is
  effectively foreclosed unless the product owner confirms otherwise.
  Needs explicit reconciliation with that statement before this option
  can be pursued.

### Option D — Phased retirement (either direction)

Both surfaces are kept during a transition window with an explicit
sunset date/criteria for one of them (either direction — dashboard
retiring in favor of desktop, or desktop being frozen in favor of a new
web app), rather than an immediate cutover.

- **Evidence needed**: a target sunset trigger (date, user-migration
  percentage, or feature-parity checklist) and an owner accountable for
  tracking it — phased plans without an explicit exit condition tend to
  become permanent coexistence (Option A) by default, so this option
  specifically needs that exit condition documented to be distinct from
  Option A in practice.

### Option E — Role-based split (Recorded decision, 2026-08-04)

Not a choice between dashboard and desktop as competing wholesale
replacements for the same population (as Options A–D frame it) — instead,
the three real frontends split cleanly by **who** is being administered,
not by client capability:

- **Workin's own platform-level administration stays web.** This is
  already the *only* path that has ever existed for it: direct code
  reads of `dashboard/includes/auth.php` confirm `doAdminLogin()` (a
  single shared password, `hr-legacy#11`) has no JWT, desktop, or mobile
  equivalent anywhere in `apis/api/auth/` — there is no `login_admin.php`
  or similar. Platform-admin capability (e.g. `pages/companies/` —
  approve/reject/suspend any company platform-wide) is web-only today and
  stays that way under this decision.
- **Every subscribed/joined company's own administration — company-owner
  and HR/Manager staff alike — consolidates onto desktop.** Today this is
  *not yet* the case: the same dashboard also has live `doCompanyLogin()`
  (queries `companies.password_hash` directly) and `doHrLogin()` (queries
  `employees` for `role IN ('hr','manager')`) functions, meaning a
  company owner or HR/Manager employee can currently log into the web
  dashboard directly, in addition to desktop. Under this decision, those
  two dashboard session paths are **retirement targets**, not permanent
  parallel infrastructure — see Consequences for what has to be true
  first.
- **Individual employees stay mobile-only** — unchanged, already the
  case today (`login_employee`, no employee-role dashboard or desktop
  path exists).

**Direct product-owner statement this decision is based on** (2026-08-04,
this conversation, verbatim): *"admin page for workin company is made
web, but for compan[ies] subscri[bed] or joined have desktop, but for
normal employees is mobile."* Confirmed by the same person named as
`Deciders` in this ADR's Metadata, in response to a direct question
distinguishing "record this as the decision" from "just checking my
understanding of current behavior" — they chose the former.

## Technology For The Platform-Admin Web Surface

Option E keeps a web surface, but scoped much narrower than "the entire
admin experience" — only Workin's own platform-level administration
(`pages/companies/` and equivalents: approve/reject/suspend companies,
platform-wide oversight), not the company/HR-facing capability that is
consolidating onto desktop. Whether that web surface stays the existing
PHP dashboard (scoped down) or becomes a new purpose-built application is
a separate, smaller decision than originally framed, and the
Next.js-vs-JTE question applies to it at this narrower scope:

**Recommendation: Next.js**, as already recorded in
`docs/tools/tool-catalog.md` prior to this ADR, for these reasons:

- **2026 ecosystem/hiring**: React/Next.js has a materially larger talent
  pool, component-library ecosystem, and long-term maintenance outlook
  than JTE, which is a small, relatively new, Java-ecosystem-specific
  project — this outweighs JTE's operational-simplicity advantage now
  that the surface being built is a small, focused platform-admin tool
  rather than the full richly-interactive admin experience originally
  envisioned (that full experience is now desktop's job under Option E).
- **Independent deployability**: decoupled from the Java backend's
  release cadence.

**JTE remains a legitimate alternative**, arguably a stronger one at this
narrower scope than it was for a full admin app: a small platform-admin
tool (a handful of pages: company approval/suspension, oversight lists)
is a much better match for JTE's minimal-operational-surface strength
than the richly interactive company/HR admin surface this ADR is now
routing to desktop instead.

**Engineering sign-off received 2026-08-05** (see Validation Evidence)
— Next.js is confirmed as the direction for this narrowed
platform-admin web surface.

## Consequences

- **Dashboard's company/HR-facing pages become retirement targets, not
  permanent infrastructure.** Of the 34 `dashboard/pages/` directories
  (`docs/legacy/existing-php-module-inventory.md`), the platform-admin
  subset (`pages/companies/`, the `admin` branch of `pages/login/`, and
  any purely platform-authored content pages) is kept or rebuilt; the
  rest (`pages/employees/`, `pages/attendance/`, `pages/payroll/`,
  `pages/advances/`, `pages/company_settings/`, etc. — everything reached
  via `doCompanyLogin()`/`doHrLogin()`) is retired once desktop reaches
  parity. This directly changes the "where does the fix land" answer for
  every dashboard-side security finding currently filed against
  `hr-legacy` (`#2`, `#3`, `#6`, and others touching those pages): the fix
  now belongs in desktop's already-existing equivalent capability (per
  `docs/api/three-frontend-api-usage-matrix.md`), not a dashboard patch.
- **The Manager-role desktop-login asymmetry is not a retirement
  blocker** (revised 2026-08-04, superseding the initial framing below):
  `login_desktop.php`'s HR-employee branch only accepts `role = 'hr'`,
  while the dashboard's `doHrLogin()` accepts `role IN ('hr','manager')`
  — but investigating *intent*, not just parity, found direct evidence
  in `workin_mobile`'s real source
  (`profile_manager_mode_button.dart`) of a named "Manager Mode" mobile
  feature — currently an unimplemented "coming soon" stub — indicating
  Manager-role capability was designed for a **mobile** channel, not
  desktop. Cross-referenced against the real data:
  **zero employees currently have `role='manager'`** in the entire
  dataset (`docs/migration/data-quality-analysis.md`). `login_desktop.php`
  excluding Manager looks like intentional design, not an oversight;
  dashboard's broader acceptance is more likely a legacy default than an
  intended parallel access path. Full investigation:
  `hr-legacy#26`. **The real, separate backlog item is that mobile's
  "Manager Mode" is unimplemented** — a product feature gap, not a
  migration-parity blocker. Tracked as
  `docs/migration/consolidated-task-matrix.md` row F-12 (reclassified).
- `docs/api/three-frontend-api-usage-matrix.md`'s "PHP Dashboard" column
  needs a follow-up pass to mark which of its `Yes` entries are
  retirement targets under this decision versus platform-admin capability
  that stays.
- All Validation Evidence items are resolved as of 2026-08-05 (see
  below), unblocking admin-surface backend implementation per the
  Migration-Readiness Gate (`hr-platform#14`).

## Risks

- **Risk of retiring dashboard access before desktop has full parity for
  capabilities companies actually rely on**: the Manager-role login gap
  turned out not to be a real instance of this risk (see Consequences),
  but the underlying risk pattern is real — this pass checked one
  specific login-role asymmetry, not full capability parity across every
  module. Mitigation: the capability/ownership matrix in
  `docs/api/three-frontend-api-usage-matrix.md` (added 2026-08-04)
  classifies every feature as platform-admin / tenant-admin / employee /
  shared / legacy-only, so retirement only targets genuine tenant-admin
  capability, not platform-only functionality that was never supposed to
  move to desktop in the first place.
- **Risk of assuming desktop-app access is universal**: **resolved
  2026-08-05** — the product/business owner confirmed the desktop app
  is distributed as a standard installable `.exe`/`.dmg`, opening
  normally like any desktop application (see Validation Evidence). No
  special hardware/OS/install-permission constraint was identified.

## Validation Evidence

### Classification (2026-08-04 revision)

**Accepted 2026-08-05 — all four Validation Evidence items resolved.**
The core question (which option) was decided 2026-08-04; Engineering
sign-off, the Manager-role gap, and the feature-disposition question
were resolved by 2026-08-05, and the desktop-access-universality
question — the one item this repository's own Discovery could not
answer on its own — was confirmed directly by the product/business
owner the same day. Nothing about this ADR remains open.

**Core decision confirmed** (2026-08-04, this conversation, by the named
Decider) — Option E, role-based split, as recorded in the Decision
section above, backed by direct code evidence
(`dashboard/includes/auth.php`'s `doAdminLogin()`/`doCompanyLogin()`/
`doHrLogin()`, `apis/api/auth/login_desktop.php`) gathered in this same
verification pass. Status as of 2026-08-05:

1. ~~Engineering-lead sign-off on the narrowed Next.js-vs-JTE
   recommendation above~~ — **Resolved 2026-08-05**: the repository
   owner, who is also the named `Deciders` for this ADR, gave blanket
   approval covering this recommendation. Next.js is confirmed as the
   direction for the narrowed platform-admin web surface.
2. ~~The Manager-role desktop-login parity gap closed before any
   dashboard retirement~~ — **Resolved 2026-08-04**: investigated and
   found not to be a retirement blocker (see Consequences); Manager-role
   capability is a mobile-app "Manager Mode" feature gap instead,
   tracked separately, non-blocking for this ADR.
3. ~~Disposition of dashboard-only capability with no desktop
   equivalent (`salary_calculator`, `setting_templates`, `activities`,
   the 5-tab `company_settings` split)~~ — **Fully resolved 2026-08-05**:
   `docs/api/three-frontend-api-usage-matrix.md`'s capability/ownership
   matrix already found `setting_templates` is not a distinct capability
   (a tab within `company_settings`, already tenant-admin) and
   `activities` is shared/company-scoped (tenant-admin for its
   company-scoped view). `pages/salary_calculator/` (2 files, 94 lines
   of actual calculation logic in `egypt_salary_calculator.php`) is now
   also read directly: it is a **standalone, read-only, gross-to-net
   estimator utility** — it never writes to the database (no
   `payslips`/`salary_contracts` mutation anywhere in the file), and
   `EgyptMonthlySalaryCalculator` is not referenced from any other file
   in `hr-legacy` (confirmed via a repository-wide grep) — it does not
   feed real payslip generation and is not itself one of the "three
   divergent implementations of payslip-total math" that actually
   produce stored payroll data (`hr-legacy` finding on payslip-math
   divergence), just a UI convenience calculator alongside them.
   **Disposition: low-risk, tenant-admin capability, portable to
   desktop with no server-side dependency** — it can be added to
   desktop as a small, self-contained feature (or reimplemented purely
   client-side, since it has no state beyond the calculation itself)
   whenever desktop parity work reaches it; nothing about its
   implementation blocks this ADR's acceptance.
4. ~~Confirmation that every current dashboard company/HR user has a
   realistic path to desktop-app access~~ — **Resolved 2026-08-05,
   directly by the product/business owner** (this conversation,
   verbatim): *"we send application after build it as .exe so can open
   dektop application normally."* The desktop app is distributed as a
   built installer (`.exe`/Windows, `.dmg`/Mac — the direct download
   links already confirmed in
   `docs/api/flutter-request-response-compatibility.md`'s "Hardcoded
   URLs And Forced-Update Assumptions" finding), installs and opens like
   any ordinary desktop application, with no special hardware or access
   requirement beyond a normal Windows/Mac machine. This closes the
   last open item — **no further confirmation is pending.**

## Open Questions

- ~~Does the platform-admin web surface stay the existing PHP dashboard
  (scoped down to just its `admin`-role pages) or get rebuilt as a new,
  smaller Next.js app from scratch?~~ **Answered 2026-08-31 by
  [ADR-0014](ADR-0014-platform-admin-web-authentication.md) (D-146): rebuilt
  as a Next.js application**, with its server-side BFF as the authentication
  boundary — the browser never receives a platform-admin token. That decision
  assumes this application exists, so leaving the question open here left the
  owning surface ADR contradicting an accepted one. **Consequence for this
  ADR**: the `admin`-role pages of the PHP dashboard are superseded rather
  than scoped down, and whether the two run in parallel during Phase 2 — and
  if so whether they authenticate independently — is ADR-0014's prerequisite 4,
  not a question this ADR still owns.
- What is the cutover sequence: fix the Manager-role parity gap first,
  then retire dashboard pages module-by-module, or an all-at-once
  cutover once full parity is confirmed?
- Is there a target date for retiring dashboard's `company_logged_in`/
  `hr_logged_in` session paths, or is this open-ended pending desktop
  parity confirmation?

## Evidence

`docs/api/flutter-request-response-compatibility.md` ("Desktop/Mobile
Divergence" finding); `docs/legacy/existing-php-module-inventory.md`
("Three Frontends, One Backend Data Model" section);
`docs/api/three-frontend-api-usage-matrix.md`; `docs/tools/tool-catalog.md`
(existing Next.js recommendation, predating this ADR); direct user
statement, this conversation, 2026-08-04 (quoted verbatim above);
`workin-hr/hr-legacy` commit `83c326e40f68dd0d560595a6c4e465eb681f2ce8`:
`dashboard/includes/auth.php` (`doAdminLogin()` lines 126–135,
`doCompanyLogin()` lines 138–163, `doHrLogin()` lines 165–200),
`apis/api/auth/login_desktop.php` (HR-employee branch, `role = 'hr'`
only), `apis/config/enums.php` (`UserRoleEnum`, confirming `HR` and
`MANAGER` are distinct role values) — all read directly in this
verification pass, 2026-08-04.

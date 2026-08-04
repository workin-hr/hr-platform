# ADR-0009: Fate Of The PHP Dashboard Relative To The Flutter Desktop Admin Client

## Metadata

| Field | Value |
|---|---|
| ADR ID | ADR-0009 |
| Title | Fate of the PHP dashboard relative to the Flutter desktop admin client |
| Status | Proposed |
| Date | 2026-08-04 |
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

**Approval status: Proposed — recorded, pending final review of this
written text.** The product/business owner made this decision directly
in conversation on 2026-08-04 (see the direct quotes in Context above and
in Option E below); it is captured here as **Option E — Role-based
split**, described in full under Alternatives Considered. `Status` moves
to `Accepted` on explicit confirmation that this written ADR accurately
reflects that decision — not automatically from the conversation alone.

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

**This recommendation is not self-approving.** It requires Engineering
sign-off before being treated as decided (see Validation Evidence).

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
- **A real parity gap blocks that retirement today**: this same
  verification pass found that `login_desktop.php`'s HR-employee branch
  only accepts `role = 'hr'` — Manager-role employees, who *can* log into
  the dashboard (`doHrLogin()` accepts `role IN ('hr','manager')`),
  **cannot** currently log into desktop at all. Retiring dashboard's HR
  session path before closing this gap would lock Manager-role users out
  of company administration entirely. Tracked as
  `docs/migration/consolidated-task-matrix.md` row F-12,
  `hr-legacy#26`.
- `docs/api/three-frontend-api-usage-matrix.md`'s "PHP Dashboard" column
  needs a follow-up pass to mark which of its `Yes` entries are
  retirement targets under this decision versus platform-admin capability
  that stays.
- Delaying full closure (Engineering sign-off, parity-gap fix, feature
  disposition below) still has a real cost:
  `docs/migration/technical-spike-plan.md` and the Migration-Readiness
  Gate (`hr-platform#14`) both list ADR acceptance as a precondition for
  admin-surface backend implementation.

## Risks

- **Risk of retiring dashboard access before desktop has full parity**:
  the Manager-role gap above is the concrete, confirmed instance of this
  risk today — there may be others not yet found (this pass checked
  login role parity specifically, not full capability parity across
  every module). Mitigation: treat "desktop capability audit vs.
  dashboard, role by role" as a required gate before any dashboard
  company/HR page is actually removed, not just before this ADR is
  accepted.
- **Risk of assuming desktop-app access is universal**: not yet confirmed
  whether every current dashboard company/HR user can realistically run a
  native Windows/Mac desktop app (device, OS, install permissions) — see
  Validation Evidence.

## Validation Evidence

**Core decision confirmed** (2026-08-04, this conversation, by the named
Decider) — Option E, role-based split, as recorded in the Decision
section above, backed by direct code evidence
(`dashboard/includes/auth.php`'s `doAdminLogin()`/`doCompanyLogin()`/
`doHrLogin()`, `apis/api/auth/login_desktop.php`) gathered in this same
verification pass. What remains before `Status` moves to `Accepted`:

1. Engineering-lead sign-off on the narrowed Next.js-vs-JTE
   recommendation above.
2. The Manager-role desktop-login parity gap
   (`docs/migration/consolidated-task-matrix.md` row F-12) closed, or an
   explicit, accepted plan to close it before any dashboard retirement.
3. Disposition of dashboard-only capability with no desktop equivalent
   (`salary_calculator`, `setting_templates`, `activities`, the 5-tab
   `company_settings` split) — does each get built into desktop, or
   deliberately dropped with product sign-off.
4. Confirmation that every current dashboard company/HR user has a
   realistic path to desktop-app access (device/OS/install permissions).

## Open Questions

- Does the platform-admin web surface stay the existing PHP dashboard
  (scoped down to just its `admin`-role pages) or get rebuilt as a new,
  smaller Next.js app from scratch?
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

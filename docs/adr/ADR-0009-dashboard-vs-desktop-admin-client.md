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
| Related Issues | `hr-platform#9` (PMR-01), `hr-platform#14` (PMR-08, ADR acceptance), `hr-legacy#2`/`#3`/`#6` (dashboard-side security findings that this decision affects the fix location of) |
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

**Approval status: Proposed — this decision has not been approved.** No
option below is selected. This ADR exists to make the decision
trackable and to specify exactly what's needed to close it — not to
close it.

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

## If A New Web Admin App Is Built (Any Option That Isn't Pure B)

The product-owner signal above names two candidate technologies for a
new admin web application: **Next.js** and **JTE** (Java Template Engine,
a compiled, type-checked server-side templating library commonly paired
with Spring Boot). Recording an engineering recommendation here, since
"choose the best of thing in 2026 and in future" was a direct request for
a technical call — this recommendation is independent of, and does not
resolve, the Option A–D decision above:

**Recommendation: Next.js**, as already recorded in
`docs/tools/tool-catalog.md` prior to this ADR, for these reasons:

- **UI complexity match**: the admin surface (per
  `docs/api/three-frontend-api-usage-matrix.md`) covers batch payroll
  review, Excel import/export flows, complex filtering/pagination,
  file uploads, and multi-tab settings management — the kind of
  richly interactive, stateful UI a modern SPA framework handles more
  naturally than server-rendered templates, even template engines with
  partial-interactivity patterns.
- **Independent deployability**: Next.js runs as its own Node.js
  deployment, decoupled from the Java backend's release cadence — useful
  if frontend and backend end up on different teams or ship schedules.
- **2026 ecosystem/hiring**: React/Next.js has a materially larger talent
  pool, component-library ecosystem (e.g. shadcn/ui-class libraries), and
  long-term maintenance outlook than JTE, which is a small, relatively
  new, Java-ecosystem-specific project.

**JTE is a legitimate alternative worth recording, not dismissing**: if
ADR-0002's modular-monolith, minimal-operational-surface philosophy is
prioritized over UI richness for the MVP window, JTE (or a similar
server-rendered approach) removes an entire runtime/language/deployment
pipeline from the system — "one fewer independently-auth'd frontend
codebase" directly addresses the root complexity this ADR is about
(three non-code-sharing frontends today). A JTE-based admin UI could
also be revisited and replaced by a Next.js app later if/when its
interactivity needs outgrow server-rendered templates, without that
later migration being wasted work (the backend API contract would be
the same either way, since JTE here would still consume the same REST
API a Next.js app would, not embed business logic in the view layer).

**This recommendation is not self-approving.** It requires the same
Engineering-lead + Product sign-off as the rest of this ADR before being
treated as decided, and only becomes relevant once Options A, B, or D
(anything requiring a *new* web admin surface) is selected over Option B
alone.

## Consequences

- Every dashboard-side security/correctness finding currently filed
  against `hr-legacy` (`#2`, `#3`, `#6`, and others touching `dashboard/`
  pages) has its "where does the fix land" answer determined by this
  decision: a genuinely new implementation (if dashboard is retired) vs.
  a direct port-and-fix (if dashboard/its replacement remains primary).
- `docs/api/three-frontend-api-usage-matrix.md`'s contract-testing
  scope changes materially by option: Option B needs contract tests only
  against desktop's existing contract; any option requiring a new web
  admin app needs a **new** contract designed and tested, since (per that
  matrix) the current dashboard has no REST contract to preserve.
- Delaying this decision has a real cost: `docs/migration/technical-spike-plan.md`
  and `docs/migration/pre-migration-readiness-gap-analysis.md`'s
  Migration-Readiness Gate both list ADR acceptance as a precondition for
  starting backend implementation (`hr-platform#14`) — this ADR remaining
  open blocks that gate for any module touching the admin surface.

## Risks

- **Risk of assuming Option B from the informal statement alone**: if
  "flutter mobile & desktop don't change anything" is read too broadly as
  "desktop is the definitive future admin client," and that turns out
  wrong (e.g. some admin users genuinely need browser-only access), the
  team could build a backend contract exclusively shaped around desktop's
  existing calls and then need a second, late redesign for a web app.
  Mitigated by explicitly not selecting an option in this ADR yet.
- **Risk of decision paralysis**: this is now the second explicit
  reminder (after `docs/legacy/existing-php-module-inventory.md`'s
  original "Two Frontends" correction) that this question is unresolved.
  Leaving it open indefinitely blocks the any-implementation gate
  (`hr-platform#14`) for admin-surface modules specifically — flagged as
  a blocker requiring human input in this round's report, not something
  to keep re-deferring silently.

## Validation Evidence

None yet — pending explicit product/business confirmation of:

1. Which of Options A/B/C/D reflects actual intended product direction,
   given the "Flutter desktop doesn't change" statement's precise
   meaning for the *dashboard's* fate specifically (not just the
   Flutter apps' own change scope).
2. Whether every current PHP-dashboard user has desktop-app access
   (device, OS, install permissions) — required to evaluate Option B.
3. Sign-off from an engineering owner on the Next.js-vs-JTE
   recommendation above, if any option requiring a new web admin app is
   chosen.
4. Disposition of dashboard-only capability with no desktop equivalent
   (`salary_calculator`, `setting_templates`, `activities`, the 5-tab
   `company_settings` split) under whichever option is chosen.

## Open Questions

- Does "flutter mobile & desktop don't change anything" mean the
  dashboard is being replaced (Option B or D-toward-B), or does it mean
  only that the *Flutter apps themselves* are out of scope for this
  phase, leaving the dashboard's fate independently undecided?
- If a new web admin app is built, does it need full feature parity with
  the PHP dashboard on day one, or can dashboard-only capability be
  deferred/dropped with product sign-off?
- Is there a target sunset date or trigger condition in mind for
  whichever surface is not chosen as primary (relevant to Option D)?

## Evidence

`docs/api/flutter-request-response-compatibility.md` ("Desktop/Mobile
Divergence" finding); `docs/legacy/existing-php-module-inventory.md`
("Three Frontends, One Backend Data Model" section);
`docs/api/three-frontend-api-usage-matrix.md`; `docs/tools/tool-catalog.md`
(existing Next.js recommendation, predating this ADR); direct user
statement, this conversation, 2026-08-04 (quoted verbatim above).

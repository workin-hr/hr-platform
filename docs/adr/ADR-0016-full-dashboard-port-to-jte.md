# ADR-0016: The Whole PHP Dashboard Ports To JTE, For All Three Audiences

## Metadata

| Field | Value |
|---|---|
| ADR ID | ADR-0016 |
| Title | The whole PHP dashboard ports to JTE, for all three audiences |
| Status | Accepted |
| Date | 2026-09-04 |
| Owners | Solution Architect (primary), Product (scope decision) |
| Deciders | Repository owner |
| Related Issues | `hr-platform#25` (dashboard retirement) |
| Supersedes | ADR-0009 Option E (the role-based split), in scope only |
| Superseded By | None |

## Context

**ADR-0009 Option E split the dashboard by audience**: Workin's own
platform administration stays web; each subscribed company's own
administration — owner and HR/Manager alike — consolidates onto the
Flutter desktop client; employees stay mobile. It was recorded from the
owner's own words on 2026-08-04, and `doCompanyLogin()` and `doHrLogin()`
were designated *retirement targets*.

ADR-0015 then built the web half of that: a JTE surface inside the
backend, with the companies list, detail, and the approve / reject /
suspend / restore lifecycle.

**Two things changed on 2026-09-04.**

First, the owner decided the production VPS runs **Java and MySQL only —
no PHP, and no rollback**. That converts every unported dashboard
capability from "later" into "gone".

Second, reviewing what that would actually cost surfaced a gap this
programme had not recorded. Nine dashboard pages are gated by
`isAdmin()`, and only one of them — `companies` — exists here. Four of
the other eight perform writes:

| Page | Write operations | Reachable any other way? |
|---|---|---|
| `faqs` | 6 | No |
| `banners` | 3 | No |
| `phone_countries` | 3 | No |
| `notifications` | 1 | No |

The Flutter clients only ever **read** these — `banners/list`,
`faqs/list`, `phone_countries/list`, `app_content/one`. There is no
create, update or delete endpoint for any of them anywhere in `apis/`.
The write side exists in the PHP dashboard and nowhere else. Switching
PHP off with only `companies` ported would freeze the app's banners,
FAQs and dial codes at whatever rows they hold, permanently, short of
hand-editing MySQL.

The owner, told this, asked for the whole dashboard: *"انا عايز java jte
بنفس الديزاين والصفحات وكل حاجه موجوده ف php"* — the same design, the
same pages, everything PHP has. Asked specifically whether that includes
the company and HR web logins ADR-0009 had marked for retirement, they
chose to keep all three audiences.

## Decision

**The PHP dashboard is reproduced in JTE inside the backend, in full: the
same pages, the same design, and all three login audiences — platform
admin, company owner, and HR/Manager.**

This supersedes ADR-0009 Option E's *scope*. Option E's reasoning about
*who* is being administered still describes the product accurately, and
the Flutter desktop client keeps its own path to the same
company-scoped capability — 190 endpoints, already ported and parity-
verified. What changes is that the web surface is no longer narrowed to
platform administration: `doCompanyLogin()` and `doHrLogin()` stop being
retirement targets and become things this surface implements.

### Design comes from the dashboard, not from a redesign

The dashboard's stylesheets and scripts are copied verbatim to
`backend/src/main/resources/static/admin/assets/`, and the templates
reproduce its class names. This is the cheapest possible way to satisfy
"the same design" and the only one that cannot drift into an
approximation of it. It also means the markup is constrained: a template
that invents its own classes gets no styling, which is a useful forcing
function.

Its 772 translation keys are converted from `lang.php` by
`scripts/convert_dashboard_lang.py` into `i18n/admin-messages`, a bundle
separate from `i18n/messages` — that one is the API's wire-visible
catalog the parity work pins, and UI chrome must not dilute it. Keys this
surface needs that PHP has no equivalent for live in `i18n/admin-own`,
hand-maintained, so the generated file stays purely generated.

### What does not change

- **The identity model.** Legacy authenticates the platform admin with
  one shared password (`hr-legacy#11`); this surface keeps the individual
  administrators of F-26/D-027, with TOTP and step-up. The login page
  therefore asks for a phone as well as a password where PHP's admin tab
  asks only for a password.
- **ADR-0015's security posture** in full: order-0 chain, CSRF on,
  session revalidation per request, step-up for privileged operations,
  audit in the same transaction.
- **The desktop client.** It is not deprecated by this and keeps serving
  the same company-scoped work.

## Consequences

**The port is large and lands incrementally.** 32 pages, ~9,900 lines of
page PHP over ~10,300 lines of dashboard helpers. Sidebar entries for
pages that do not exist yet render **disabled rather than hidden**
(`AdminNav.Item.implemented`), because a sidebar that silently omits half
the product looks finished when it is not.

**Order is driven by what disappears when PHP does**, not by page size:
the four write-only-in-PHP pages above come first, since they are the
only capability with no other route.

**A new security surface.** Company and HR sessions authenticate against
`companies.password_hash` and `employees` respectively, and each new page
is a new authenticated route. Every one of them needs the tenant scoping
the API already has — and the platform admin acting *as* a company
(PHP's `$_SESSION['company_id']`) is a deliberate cross-tenant path that
must be explicit, audited, and impossible to reach by accident. This is
the largest risk the decision carries and is tracked as **R-044**.

**`/admin/assets/**` is public.** A stylesheet is not a secret, and
inlining 2,500 lines of CSS per page is not an alternative. The prefix
holds no handler and resolves only against the static classpath;
`PlatformAdminAssetsExposureTest` pins both that the assets are served
and that the exception reaches nothing else.

## Implementation Status

*As of 2026-09-04. The sidebar is the live version of this table --
`AdminPageAvailability` reads what is routable from the handler mapping, so
an entry here that is not implemented shows as disabled rather than as a
link that fails.*

### Done

| Page | Notes |
|---|---|
| Login / MFA / enrolment | The dashboard's own full-page design, from its copied `login.css`. Phone **and** password, unlike PHP's shared-password admin tab (F-26/D-027). |
| `companies` | List, detail, approve / reject / suspend / restore, behind step-up (D-163). |
| `phone_countries` | Full CRUD. Validation reproduces `dashboard_phone_country_validate_post()` rule for rule. |
| `faqs` | Categories and items, six actions. Category delete relies on the schema's own `ON DELETE CASCADE` and records the count it took. |
| `banners` | Full CRUD with image upload through `LegacyFileUploads`, so the stored extension comes from the sniffed type (**D-154**), not the filename as the dashboard's own helper does (**R-039**). The 20-key internal-route allowlist is carried across whole. |
| `notifications` | Platform broadcast, admin audiences only. Set-based insert rather than the dashboard's per-recipient loop (**R-045**). |
| `sessions` | This surface's own, with no dashboard counterpart. |

Those four content pages came first for one reason: their write side exists
**nowhere else**. `banners/list`, `faqs/list`, `phone_countries/list` and
`app_content/one` are read-only, so switching PHP off without them would
have frozen that content permanently.

### Not yet ported

The eighteen company-scoped pages (`employees`, `branches`, `departments`,
`job_titles`, `shifts`, `requests`, `leave_balances`, `penalties`,
`administrative_decisions`, `assets`, `advances`, `workforce_planning`,
`salary_calculator`, `attendance`, `payroll`, `complaints`,
`company_settings`, `activities`), plus `home`, `app_content`,
`setting_templates`, `settings`, `profile` and `change_password`.

The company and HR **login paths** are also outstanding. Until they exist,
`AdminViewModelAdvice.isAdmin()` is hardcoded true and the sidebar's
admin-only gating is present but untested against a non-admin session.

### The gate on the next stage

**R-044 must have its control before the first company-scoped page merges.**
Everything shipped so far is platform-level: one tenant-independent
catalogue each. The moment a page reads a company's employees, the
admin-acting-as-a-company path exists, and that is the one this ADR
identifies as its largest risk.

## Alternatives Considered

**Port only the four write-only pages.** Cheapest, and closes the
capability loss. Rejected by the owner, who asked for the whole
dashboard; it would also leave two admin surfaces to operate during the
port rather than one.

**Keep PHP running for the unported pages.** The natural answer, and what
ADR-0009 assumed. Ruled out by the owner's deployment decision: the VPS
runs Java and MySQL only.

**Redesign rather than copy.** Rejected on the owner's explicit
instruction ("بنفس الديزاين"), and independently the wrong trade: a
redesign spends design effort during a migration whose whole risk story
is that nothing else changes at once.

## Risks

**R-044 — the authenticated surface multiplies, including a deliberate
cross-tenant path.** Two new authentication paths and roughly thirty new
authenticated routes, plus the platform admin acting *as* a company. A
missing company predicate on any one page discloses another tenant's
data, silently. Filed before the surface exists so its control is
designed rather than retrofitted; the register carries the three
mitigations.

**The port is large enough to be abandoned half-done.** 32 pages is
months of incremental work, and a surface stuck at 60% with PHP already
switched off is worse than either end state. This is why the order is
driven by what disappears when PHP does, and why unported pages are
visible in the sidebar rather than hidden — the remaining distance stays
in front of whoever is deciding when to cut over.

**Copied assets go stale against their source.** `hr-legacy` is frozen at
`d113204`, so the stylesheets cannot drift today. If it is ever unfrozen,
the copies in `static/admin/assets/` become a fork nothing reconciles.
The generated message bundle has `scripts/convert_dashboard_lang.py
--check` for exactly this; the CSS has no equivalent and would need one.

## Validation Evidence

Verified 2026-09-04 against a MariaDB 11.8 seeded from the legacy
snapshot (269 companies, 2,873 employees, 36,316 attendance rows), with
the packaged jar under `phase1-mysql`:

- the login page renders the dashboard's own design — hero panel,
  language pill, Arabic RTL — from the copied `login.css`;
- signing in produces the dashboard shell: dark sidebar, five nav groups,
  Arabic labels resolved from the converted catalog, RTL direction;
- `/admin/companies` renders 200 real companies in the dashboard's
  `.data-table-card` with its status badges and kebab row-actions menu;
- `/admin/assets/{style,app-ui,sidebar,login}.css` and the scripts serve
  200 without a session, while `/admin/companies` still answers 302 and a
  missing asset answers 404 — held by `PlatformAdminAssetsExposureTest`;
- `scripts/convert_dashboard_lang.py --check` passes, so the committed
  bundle matches what `lang.php` would generate;
- the platform-admin test suite, `SecurityPolicyAgreementTest` and
  `MessageCatalogSyncTest` pass unchanged.

Screenshots of the login page and the companies list were reviewed
against the PHP dashboard rather than inferred from the markup.

## Open Questions

1. **Where the admin's selected company lives.** PHP keeps it in
   `$_SESSION['company_id']`. This surface needs it to be explicit,
   audited, and impossible to reach by default — the shape is not yet
   decided, and R-044's first mitigation depends on it.
2. **Whether company and HR sessions share the platform admin's session
   store and timeouts**, or get their own. They have different risk
   profiles: a platform admin can reach every tenant.
3. **What happens to `hr_permissions`.** The dashboard gates HR pages
   through `HrAccess`, which reads a flag legacy never enforced
   consistently (**R-010**). Reproducing it faithfully reproduces the
   hole; not reproducing it changes behaviour. Needs a decision before
   the HR audience lands.
4. **Whether the copied CSS gets a drift check** if `hr-legacy` is ever
   unfrozen.

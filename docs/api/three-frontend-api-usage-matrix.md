# Three-Frontend API Usage Matrix

## Purpose And Scope

Documents, for every `apis/api/` module (and every `dashboard/`-only page
group with no API equivalent), which of the three real frontends —
**PHP Dashboard**, **Flutter Desktop** (`workin_desktop`), **Flutter
Mobile** (`workin_mobile`) — consumes it, how, and what that implies for
migration compatibility and contract testing.

**Granularity note**: this matrix operates at module level (38 API
modules + dashboard-only page groups), not at the level of all 199
individual API endpoints. Full per-endpoint detail already exists in
`docs/api/existing-endpoint-inventory.md` (server-side contract) and
`docs/api/flutter-request-response-compatibility.md` (Flutter-confirmed
contract detail for the specific endpoints called out there); this
document is a cross-frontend rollup, not a replacement for either.

**A structural fact that shapes every row below**: the PHP Dashboard does
**not** consume the `apis/api/` REST layer at all. It is a second,
independent PHP application (`dashboard/`) with its own
`db.php`/`auth.php`/`constants.php`, querying the same MySQL database
directly. "Dashboard consumes module X" below means "the dashboard has
page(s) implementing equivalent capability via direct DB access," not
"the dashboard calls the module's REST endpoints." This is itself a
migration-relevant fact: there is no existing dashboard-side API contract
to preserve, because none exists today — a new admin web app would be a
**new** API consumer, with a new contract, not a lift-and-shift of an
existing one. Confirmed in `docs/legacy/existing-php-module-inventory.md`
("Data Dependencies" section) and re-confirmed here.

## Column Definitions

- **Frontend Consumer**: which of the three call this module/capability,
  and how (REST endpoint(s) vs. direct-DB dashboard page(s)).
- **Auth Method**: JWT bearer (`apis/`) vs. PHP session (`dashboard/`),
  per frontend.
- **Roles/Permissions Used**: role model each frontend exercises for this
  module, per existing Discovery (see `docs/security/threat-model.md` and
  `docs/legacy/business-rule-extraction.md` for the underlying per-role
  findings this summarizes).
- **Request/Response Contract Dependency**: what a Java rewrite must
  preserve for each consuming frontend not to break, given clients are not
  being changed (mobile and desktop client source stays as-is per current
  direction).
- **Migration Compatibility Requirement**: concrete constraint on the new
  backend.
- **Contract Tests Required?**: `Yes` for any module a Flutter client
  consumes via a fixed REST contract (mobile/desktop cannot be changed, so
  drift is a silent breakage); `New` for dashboard-equivalent capability
  that will need contract tests only once a *new* web admin app is built
  against a *new* API (there's nothing existing to test against today);
  `No` where no live client consumes the module at all.

## Matrix

| Module | PHP Dashboard | Flutter Desktop | Flutter Mobile | Auth Method(s) | Roles/Permissions Used | Contract Dependency | Migration Compatibility Requirement | Contract Tests? |
|---|---|---|---|---|---|---|---|---|
| `auth` | Yes — `pages/login/` (session login, separate flow) | Yes — `login_company`/`login_desktop`, `join_company`(?) | Yes — `login_employee`, `join_company` (confirmed canonical; `register_employee` unreferenced, see `hr-legacy#19`) | Session (dashboard); JWT (desktop, mobile) | Dashboard: platform-admin/company-admin session role. Desktop: company-admin/HR. Mobile: employee | Mobile/desktop: exact request/response shape of `login_employee`/`login_company`/`join_company`, JWT claim shape, `_isPublicAuthEndpoint()` list | New backend must issue tokens Flutter clients can parse unchanged, or ship client-side changes alongside (see F-02, auth-remediation design) | Yes |
| `attendance` | Yes — `pages/attendance/` (admin views, bulk delete) | Yes — admin attendance surface incl. `delete_range` | Yes — self check-in/out only (`{latitude, longitude, method:'app'}` exact shape confirmed), read-only own history | Session; JWT; JWT | Dashboard/desktop: HR-role/Manager, company-wide (Manager scoping gap, see `hr-legacy#17`). Mobile: employee, own-record only | Mobile: exact check-in payload shape (confirmed low-risk, direct match). Desktop: `delete_range` and admin query shapes | Preserve exact mobile check-in contract; QR check-in (`check_in_qr`) has no confirmed live caller in either client — see F-04 before deciding whether to port | Yes (mobile check-in); Open pending F-04 (QR) |
| `employees` | Yes — `pages/employees/` (incl. the cross-tenant password-edit bug, `hr-legacy#3`) | Yes — full CRUD, bulk import, photo upload, deactivate | No (mobile has no employee-management capability) | Session; JWT; N/A | Dashboard/desktop: HR-role/Manager, company-scoped (correct scoping confirmed for API, dashboard scoping is the known bug) | Desktop: full CRUD contract shape | New backend must fix the cross-tenant password-edit bug (`hr-legacy#3`) while preserving desktop's CRUD contract | Yes (desktop) |
| `profile` | No (no self-service admin concept) | Partial — company/desktop-user profile actions | Yes — change password, delete account (w/ preview), phone-change, push-token registration, **logout (confirmed to silently deactivate the account, `hr-legacy#15`)** | N/A; JWT; JWT | Desktop: company-admin, own profile. Mobile: employee, own profile | Mobile: exact logout/delete-account/push-token-registration request shapes | Fix the logout-deactivates-account bug (`hr-legacy#15`) without changing the client-visible logout call shape (mobile can't change) | Yes |
| `payroll_batches` | Yes — `pages/payroll/` (also see the separate, unverified `egypt_salary_calculator.php` tool) | Yes — create/calculate/finalize/reopen, stats | No | Session; JWT; N/A | Dashboard/desktop: HR-role/company-admin, company-scoped | Desktop: full batch-lifecycle contract | Preserve transactional guarantees the legacy `calculate` step lacks (`hr-legacy#22`) without changing desktop's request/response shape | Yes (desktop) |
| `leave_balances` | Yes — dashboard leave/balance pages | Yes — balance CRUD, bulk import | Yes — read-only own balance | Session; JWT; JWT | Dashboard/desktop: HR-role, company-scoped. Mobile: employee, own-record | Mobile: read contract for own balance | Preserve mobile's read contract exactly | Yes |
| `advances` | Yes — `pages/advances/` (also affected by the dashboard-side cross-tenant IDOR, `hr-legacy#6`) | Yes — approve/reject/pay/create/delete/update (**the exact operation set with the confirmed cross-tenant IDOR, `hr-legacy#5`**) | No | Session; JWT; N/A | Dashboard/desktop: HR-role, company-scoped (intended) — confirmed broken | Desktop: full lifecycle contract, same shapes the IDOR fix must preserve | The new backend must close the IDOR (`hr-legacy#5`/`#6`) while keeping desktop's request/response shapes unchanged | Yes (desktop) |
| `penalties` | Yes — `pages/penalties/` | Yes — CRUD, reporting | No | Session; JWT; N/A | Dashboard/desktop: HR-role, company-scoped (correctly scoped per API Discovery) | Desktop: report export shape — note the CSV/XLSX content-type mismatch (`hr-legacy#23`) is a real behavior the desktop client currently receives | Fixing the CSV/XLSX mislabel is a breaking change for any consumer relying on the current (wrong) content-type — confirm desktop client tolerance before fixing | Yes (desktop) |
| `requests` | Yes — `pages/requests/` | Likely (leave/permission approval is part of desktop's admin surface; not individually confirmed endpoint-by-endpoint in this pass) | Yes — create/read own requests | Session; JWT; JWT | Dashboard: HR/Manager (Manager approve/reject not branch-scoped, `hr-legacy#18`). Mobile: employee, own | Mobile: create/read own-request contract | Fix Manager scoping (`hr-legacy#18`) without changing mobile's own-request contract | Yes (mobile) |
| `workforce_planning` | **Confirmed 2026-08-04**: `dashboard/pages/workforce_planning/` exists as a real page directory | Yes — headcount targets | No | Session; JWT; N/A | Desktop: HR-role/company-admin, company-scoped | Desktop: create/update/save_target/summary contract | Preserve desktop contract exactly | Yes (desktop) |
| `payslips` | Yes — `pages/payslips/` (subject to the 3-divergent-implementations finding, `hr-legacy#13`, and the daily-wage base-pay bug, `hr-legacy#12`) | Yes — full CRUD, export | Yes — read-only own payslips | Session; JWT; JWT | Dashboard/desktop: HR-role, company-scoped. Mobile: employee, own-record | Mobile: read contract for own payslips. Desktop: full CRUD | Consolidate payslip-total math to one implementation (`hr-legacy#13`) whose output matches what both desktop (CRUD) and mobile (read) already expect for existing records | Yes |
| `branches` | Yes — `pages/branches/` | Yes — CRUD, `generate_qr` (desktop generates QR codes; **no client consumes them**, see F-04) | No (mobile doesn't manage branches, though it likely reads branch data for check-in context — not individually re-verified this pass) | Session; JWT; N/A | Dashboard/desktop: HR-role, company-scoped | Desktop: CRUD + QR-generation contract | Preserve desktop's `generate_qr` capability even if nothing currently consumes the generated codes (F-04 is the open question on this) | Yes (desktop) |
| `company_settings` | Yes — `pages/company_settings/` (5 sub-tab partials, more granular than the API module) | Yes — CRUD, options | No | Session; JWT; N/A | Dashboard/desktop: HR-role/company-admin | Desktop: CRUD contract. Dashboard: no existing API contract to preserve (direct DB) | A new admin web app replacing dashboard functionality needs a **new** contract here — nothing existing to port for this frontend specifically | Yes (desktop); New (future web admin) |
| `notifications` | No confirmed dashboard equivalent this pass | Likely (not individually re-verified) | Yes — list, send(?), mark read, unread count | N/A; JWT (likely); JWT | Mobile: employee, own notifications | Mobile: list/read/unread-count contract | Preserve mobile's list/read contract | Yes (mobile) |
| FCM push-token registration | N/A | N/A | **Confirmed 2026-08-04: not currently wired up.** Mobile has `firebase_messaging` as a dependency and UI/service scaffolding (`notification_service.dart`, `shared_provider.dart`), but the actual `register_push_token` API call is **commented out** in both `shared_provider.dart` (`// DependencyInjection.addFcmTokenUsecase.call(...)`) and `authentication_provider.dart` (`final String? fcmToken = ''; // await NotificationService.getDeviceToken();` — the token fetch itself is stubbed to an empty string) | N/A (no live call) | N/A | Mobile does not currently send its device token to the backend at all | Combined with the confirmed server-side `FCM_SERVER_KEY` placeholder (`hr-platform#22`), push notification delivery is **not functionally working end-to-end today on either side** — this is "build for the first time," not "port existing behavior," and the mobile-side gap is a client-code fix similar in kind to the already-scoped auth-storage exception, not a backend-only fix | New (backend); client fix required (mobile) — see `hr-platform#22` |
| `job_titles`, `departments`, `shifts`, `request_types`, `attendance_exception_types`, `company_official_holidays`, `assets`, `administrative_decisions` | Yes — each has a dashboard page group | Yes — desktop's `api_constants.dart` covers the admin/config surface broadly (not individually re-verified per sub-module this pass) | No | Session; JWT; N/A | Dashboard/desktop: HR-role, company-scoped. **`hr_permissions` enforcement present on `administrative_decisions`/`attendance_exception_types`/`company_official_holidays`, absent on the other five (`hr-legacy#8`)** | Desktop: CRUD contract per sub-module | Fixing the permission-enforcement gap (`hr-legacy#8`) must not change desktop's CRUD contract shapes, only what's authorized | Yes (desktop) |
| `salary_contracts` | Yes — likely via `pages/employees/` or a dedicated compensation page (not individually re-verified) | Yes (part of admin employee-management surface) | No | Session; JWT; N/A | Dashboard/desktop: HR-role, company-scoped | Desktop: versioned-contract CRUD shape | `housing_allowance` currently cannot be set nonzero anywhere (`hr-legacy#14`) — confirm with product whether desktop's UI already exposes this as a dead field before assuming it needs backend support | Yes (desktop) |
| `employee_docs` | Likely (`pages/employees/` sub-scope, not individually re-verified) | Likely (not individually re-verified) | Yes — confirmed in the module inventory as one of mobile's self-service surfaces | Session (likely); JWT; JWT | Mobile: employee, own documents | Mobile: read contract | Preserve mobile's own-documents read contract | Yes (mobile) |
| `company_join_requests`, `hr_employees`, `complaints`, `schedules` | Yes — each maps to a dashboard concern (join-request approval, HR permission management, complaint handling, schedule assignment) | Likely, for the HR-permission and schedule-management pieces specifically (desktop's admin surface explicitly includes "HR employee/permission management" per `docs/legacy/existing-php-module-inventory.md`) | Complaints: **Confirmed 2026-08-04** — `submitComplaintEndpoint = 'complaints/create'` is declared in mobile's `api_constants.dart`; others: no | Session; JWT (partial); JWT (complaints, submit only) | Dashboard/desktop: HR-role/company-admin. Mobile: employee, submit own | Desktop: HR-permission-management contract, where applicable. Mobile: complaint-submission contract | Complaints has a real mobile-consumed contract (`complaints/create`) — preserve it; the complaint-*handling* side remains dashboard/desktop only | Yes (desktop, where applicable); Yes (mobile, complaint submission) |
| `company` | Yes — company profile pages | Yes — `company/update`, logo upload; **also `profile/delete_account` for company account deletion**, confirmed in the Desktop/Mobile Divergence finding | No | Session; JWT; N/A | Dashboard/desktop: company-admin | Desktop: profile-update/delete-account contract | Company self-deletion is a real, live desktop capability — preserve or explicitly redesign with product sign-off, not silently drop | Yes (desktop) |
| `app_content`, `banners`, `faqs`, `configs`, `phone_countries`, `setting_allowed_values`, `setting_definitions`, `time`, `dashboard` | Partial (some are dashboard-authored content, e.g. banners/FAQs management) | Yes — `configs` specifically carries the forced-update/maintenance-mode fields desktop depends on (F-07) | Likely (`configs`/`app_content`/`banners`/`faqs` are typical mobile-consumed reference data; not individually re-verified per endpoint this pass) | Session (content mgmt); JWT; JWT | Read-mostly for both Flutter clients | Desktop: `configs` version-gate field names (`min*BuildNumberKey`, `*UnderMaintenanceKey`) are load-bearing, see F-07 | `configs`-equivalent endpoint in the new backend must serve the same forced-update fields desktop already depends on | Yes (desktop, for `configs` specifically) |
| Dashboard-only: `salary_calculator` (incl. `egypt_salary_calculator.php`) | Yes — no API equivalent | No | No | Session | Dashboard: HR-role/company-admin | No existing API contract to preserve — this is dashboard-internal logic only | Open question (per `docs/legacy/existing-php-module-inventory.md`): confirm whether this duplicates `payroll_calculation.php`'s rules independently (drift risk) before deciding whether/how to port | New (if ported into a future web admin) |
| Dashboard-only: `setting_templates`, `activities` | Yes — no API equivalent | No | No | Session | Dashboard: HR-role/company-admin | No existing API contract | New capability decision for any future web admin — nothing to port a contract for | New (if built) |
| Orphaned: `set_employee_attendance_method` | No | Client declares the endpoint constant but never calls it (dead reference) | No | N/A | N/A | No live contract — client-declared, server-nonexistent | See F-05 — confirm abandoned vs. planned before deciding whether the new backend needs this at all | No |

## `assets` And `administrative_decisions` Consumers (Added 2026-08-29, C8)

These two modules **are** covered by the grouped reference row above, which
records them at module granularity. What that row could not say — and what C8
recorded as "10 endpoints with no recorded client consumer" — is *which
endpoints* each client calls. The bounded C3/C8 pass
(`docs/migration/2026-08-29-c3-c8-bounded-discovery.md`) established that per
endpoint.

**This table corrects the grouped row's Mobile column for these two modules.**
That row reads Mobile = "No", which is right for the other six modules in it and
wrong for these two: mobile has feature directories for both and declares
`assets/list` and `administrative_decisions/list` in its own `api_constants.dart`.
The grouped row's own caveat — "not individually re-verified per sub-module this
pass" — is exactly the gap this closes. Where the two disagree for `assets` or
`administrative_decisions`, **this table is authoritative**; the grouped row
remains authoritative for the other six.

| Endpoint | Desktop | Mobile | Evidence |
|---|---|---|---|
| `assets/list` | yes | yes | `api_constants.dart:190`; desktop `features/_/assets/`, mobile profile screens |
| `assets/one` | yes | — | `api_constants.dart` |
| `assets/create` | yes | — | `api_constants.dart:192` |
| `assets/update` | yes | — | `api_constants.dart` |
| `assets/delete` | yes | — | `api_constants.dart` |
| `administrative_decisions/list` | yes | yes | `api_constants.dart:120` |
| `administrative_decisions/create` | yes | — | `api_constants.dart:121` |
| `administrative_decisions/update` | yes | — | `api_constants.dart` |
| `administrative_decisions/delete` | yes | — | `api_constants.dart` |
| `administrative_decisions/one` | **none declared** | **none declared** | — |

**Every `yes` above is a live call site, not just a declaration.** That
distinction matters here because this matrix's own F-05 row records a constant
the client declares and never calls, so a declaration alone would not settle
C8. Each constant was traced into
`data/data_source/remote/remote_data_source.dart` — desktop around lines
1306–1445, mobile at 188 (`assets/list`) and 456
(`administrative_decisions/list`) — and every one is passed as an `endPoint:`
to a real request method.

`administrative_decisions/one` is **not** treated as dead surface on this
evidence. Absence from two clients is not proof of no consumer, and C4 records
what happens when reachability is inferred from one artifact's silence.

**`assets` enforces its permission flag client-side only.** The desktop sidebar
carries `hrPermission: HrPermissionFlag.assets`, while the endpoint inventory
records `assets` among the modules where the `hr_permissions` matrix is **not**
enforced server-side — the gap already tracked as `hr-legacy#8` in the grouped
row above.

Precisely: the three write routes are `requireAuth([COMPANY_ADMIN, HR])`, so
MANAGER and EMPLOYEE are refused. What is unenforced is the narrower flag — an
**admin or HR user with `can_assets` unset** is hidden the screen by the client
and served by the server. A privilege gap within an already-privileged role, not
open access. A faithful Phase-1 port reproduces it (D-058) and must do so as a
recorded decision, not by accident.

## Capability And Ownership Matrix (Added 2026-08-04)

**Reframes the module list above by who owns each capability, not just
which client calls it.** Per direct product-owner decision (recorded in
`docs/adr/ADR-0009-dashboard-vs-desktop-admin-client.md`, Option E): the
dashboard is Workin's own internal platform-administration interface
(the system/platform operator's tool); the desktop app is used by
customer companies and their authorized employees/managers. **A missing
dashboard capability in desktop is therefore not automatically a parity
gap** — it may correctly belong to the platform operator only. Desktop
parity is only required for capabilities that genuinely belong to
company users.

| Capability | Owner Class | Desktop Parity Required? |
|---|---|---|
| `pages/companies/` (approve/reject/suspend any company platform-wide) | **Platform administration** | No — this is explicitly not a company-user capability |
| `pages/login/` (admin branch, `doAdminLogin()`) | **Platform administration** | No |
| `app_content`, `banners`, `faqs` (platform-authored marketing/content) | **Platform administration** | No |
| `configs` (remote version-gating, forced-update fields) | **Platform administration** — managed platform-side, *consumed* by desktop (`hr-platform#21`) | No management UI needed on desktop; desktop must keep *reading* this data |
| `phone_countries`, `setting_allowed_values`, `setting_definitions` | **Platform administration** (definition/management side) / **Shared** (read side, all clients) | Read-only reference data — no management UI needed on desktop |
| `pages/activities/` (recent-activity feed) | **Shared, company-scoped** — confirmed 2026-08-04: `page.php` calls `org_resolve_company_id()`, which returns the *viewer's own* company for a company/HR session, or an admin-selected (or all-companies) scope for a platform-admin session (`org_helper.php`). Not platform-exclusive as first assumed. | **Yes, for the company-scoped view** — a company's own activity feed is tenant-admin capability; only the cross-company/unscoped admin view is platform-only |
| `pages/salary_calculator/` (`egypt_salary_calculator.php`) | **Legacy-only/deprecated candidate** — open question (`docs/legacy/existing-php-module-inventory.md`) whether it duplicates the real payroll engine independently | No — needs a disposition decision (build into desktop, or drop) before being counted either way |
| `pages/setting_templates/` | **Not a separate capability** — confirmed 2026-08-04: `page.php` is a pure redirect to `settings.php?tab=setting_templates`, i.e. this is a tab within `company_settings`, already classified as Tenant/company administration above, not a distinct dashboard-only feature | **Yes** — already covered by the `company_settings` row above |
| `employees`, `branches`, `departments`, `shifts`, `job_titles`, `request_types`, `attendance_exception_types`, `company_official_holidays`, `assets`, `administrative_decisions`, `salary_contracts`, `company_settings` (incl. its 5-tab dashboard split), `company_join_requests`, `hr_employees`, `schedules`, `company` (profile/logo/delete) | **Tenant/company administration** | **Yes** — these belong to company owners and their HR staff; desktop is the intended channel per ADR-0009 |
| `payroll_batches`, `advances`, `penalties`, `workforce_planning` | **Tenant/company administration** | **Yes** |
| `attendance` (admin views, bulk delete) | **Tenant/company administration** | **Yes** |
| `attendance` (self check-in/out) | **Employee self-service** | N/A — mobile-only by design |
| `profile` (change password, delete account, logout) | **Employee self-service** | N/A — mobile-only |
| `leave_balances`, `payslips`, `employee_docs` (read own) | **Employee self-service** | N/A — mobile-only |
| `requests` (create/read own) | **Employee self-service** | N/A — mobile-only |
| `requests` (approve/reject) | **Tenant/company administration** | **Yes** |
| `complaints` (submit) | **Employee self-service** | N/A |
| `complaints` (handle) | **Tenant/company administration** | **Yes** |
| `notifications` (receive) | **Shared** — every client receives notifications relevant to its own identity | N/A |
| Manager-role elevated capability (company-wide attendance visibility, request approval — `hr-legacy#17`/`#18`) | **Employee self-service, elevated** — per the "Manager Mode" investigation (`hr-legacy#26`), this was designed as a **mobile** feature, not desktop or dashboard | **No** — confirmed not a desktop capability by design, not by gap (see `hr-legacy#26`) |

**What this changes from the earlier framing**: the original
`three-frontend-api-usage-matrix.md` pass (and the first version of
ADR-0009) implicitly treated "does the dashboard have it and desktop
doesn't" as a gap to close. Under this ownership classification, only
the **Tenant/company administration** row is actually in scope for
desktop-parity work under ADR-0009's retirement plan — Platform
administration and the unresolved Legacy-only candidates are explicitly
**not** desktop's job.

## Open Questions (Ownership Classification)

- ~~`pages/activities/` and `pages/setting_templates/` ownership~~ —
  **Resolved 2026-08-04** by direct code read (see table above):
  `activities` is shared/company-scoped, `setting_templates` is not a
  distinct capability at all (a tab within `company_settings`). Both
  reclassified from the initial "likely platform-internal" guess.
- `pages/salary_calculator/` — the existing open question (independent
  payroll-logic duplication risk) still needs its own read-through
  regardless of ownership classification.

## Cross-Cutting Migration Compatibility Requirements

These apply across every row above, not just one module:

1. **Flutter clients are fixed.** Per current direction, `workin_mobile`
   and `workin_desktop` are not being changed as part of this migration.
   Every REST contract either client currently exercises (marked `Yes` in
   the Contract Tests column) must be preserved byte-for-byte in request
   shape and response field names/types, or the corresponding client
   behavior breaks in production with no client-side fix available except
   a full app-store/installer release cycle.
2. **The PHP Dashboard has no REST contract to preserve**, because it
   never had one — it queries the database directly. Whatever replaces
   dashboard functionality (see `docs/adr/ADR-0009-dashboard-vs-desktop-admin-client.md`)
   is building a **new** API surface for a **new or existing** consumer,
   not maintaining backward compatibility with an existing one. This
   meaningfully lowers the migration-compatibility risk for
   dashboard-equivalent capability compared to Flutter-consumed capability
   — it's a design decision, not a compatibility constraint.

   **Update 2026-08-04**: ADR-0009 now records a decision (Option E,
   pending final `Accepted` sign-off) on exactly what "whatever replaces
   dashboard functionality" means: company/HR-facing modules in the
   "PHP Dashboard" column above (every row backed by `doCompanyLogin()`/
   `doHrLogin()` — i.e. everything except `pages/companies/` and the
   `admin` branch of `pages/login/`) are retirement targets, consolidating
   onto the Desktop column's existing contract instead of getting a new
   one — **conditional on `hr-legacy#26` being closed first** (Manager-role
   employees can log into the dashboard today but not desktop at all;
   retiring dashboard's HR session path before fixing this would lock
   Manager-role users out entirely). See `hr-platform#25` for the
   retirement tracking issue.
3. Several rows above are marked "likely" or "not individually
   re-verified" rather than "confirmed" — this matrix was built by
   cross-referencing existing Discovery documents (which read the full
   `api_constants.dart` files but did not exhaustively trace every single
   call site for every module) rather than a fresh full re-read. Treat
   `Likely` rows as needing a targeted confirmation check before being
   used as a hard contract-test requirement, not as false.

## Evidence

`docs/legacy/existing-php-module-inventory.md` (module list, dashboard
page structure, "Three Frontends" section);
`docs/api/flutter-request-response-compatibility.md` (all Flutter-confirmed
contract detail cited by module/endpoint name above);
`docs/api/existing-endpoint-inventory.md` (per-endpoint server contract,
referenced but not repeated here); `docs/security/threat-model.md` and
`docs/legacy/business-rule-extraction.md` (the per-role/per-module
findings this matrix's "Roles/Permissions Used" and "Migration
Compatibility Requirement" columns summarize); `hr-legacy` issues #2–#25;
`hr-platform` issues #18–#23.

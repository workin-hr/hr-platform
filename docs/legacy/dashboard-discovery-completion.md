# Dashboard Discovery — Completion (PMR-01)

## Purpose

Closes PMR-01
(`docs/migration/pre-migration-readiness-gap-analysis.md`,
`hr-platform#9`): the ~75 `hr-legacy` `dashboard/` files never read in
the original Discovery pass have now been read in full. Source: pinned
checkout of `workin-hr/hr-legacy` commit
`83c326e40f68dd0d560595a6c4e465eb681f2ce8`, every file read directly —
not inferred from naming. This document records the coverage and the
findings; the load-bearing security findings are cross-referenced into
`docs/security/threat-model.md` and the business rules into
`docs/legacy/business-rule-extraction.md`, per PMR-01's exit criteria.

## Coverage

Read in full this pass (previously unread or partial): all of
`dashboard/includes/` (30 helper/infrastructure files),
`dashboard/sidebar/` (6), `dashboard/index.php`, and the page
controllers/partials for `attendance`, `settings` (+3 tab partials),
`setting_templates`, `activities`, `profile` (+6 partials),
`join_requests`, `home` (topbar + `home_service.php`), `login`,
`change_password`, `content`, `banners`, `faqs`, `app_content`,
`phone_countries`, `branches` (+form +QR modal), `departments`
(+form), `job_titles` (+form), `shifts` (+form),
`workforce_planning`, `administrative_decisions`, and
`notifications/helper.php`. The `payroll` page controller was
re-read this pass specifically to resolve an IDOR hypothesis (see
Finding 1c). Combined with the original pass, **every dashboard file
is now read**.

## Architecture Confirmed

- **Three session principal types** (`admin_logged_in` /
  `company_logged_in` / `hr_logged_in`, `dashboard/includes/auth.php`),
  entirely separate from the API's JWT auth. 30-day sliding session
  (`SESSION_TIMEOUT=2592000`).
- **`dashboard/includes/org_helper.php` is the single tenant-scoping
  authority.** `org_resolve_company_id()` (L51-58) never consults
  request input for company/HR sessions — it always returns the
  session's own `company_id`; only `isAdmin()` sessions get a
  GET-param-driven, session-persisted company filter. The pervasive
  `if ($companyId > 0) { …scope… } else { …all companies… }` pattern
  across `hr_list_helper.php`/`employee_helper.php`/`payroll_list_helper.php`
  is therefore an **admin-only cross-company view by design**, not an
  IDOR — the unscoped branch is unreachable for company/HR sessions.
- **`org_assert_company_row()` / `org_verify_post_row()`
  (org_helper.php L74-135) are the per-row ownership primitives** the
  org pages use before mutations. The IDOR findings below are exactly
  the pages that skip them.
- Query layer (`dashboard/includes/db.php`, `query.php`) does **no
  automatic tenant filtering**; every helper is individually
  responsible for the `company_id` predicate. Parameterized (`?`)
  binding is used consistently — no SQL-injection vectors found (the
  one string-built pattern, `home_service.php` chart queries, always
  `(int)`-casts first).

## Findings — Security (detail in `threat-model.md`)

### Finding 1 — Confirmed cross-tenant IDOR instances (hr-legacy#6)

hr-legacy#6 ("Cross-tenant IDOR across 10 dashboard modules") is a
documented blanket finding; this pass confirms **three concrete
instances with line citations**, each a POST/GET mutation on a raw
`$id` with no owning-company check, in code whose siblings *do* call
`org_verify_post_row()`:

- **1a — `attendance/page.php`** (L31-40, 78-92): `edit_attendance`
  and `delete` call `dbUpdate`/`dbDelete('attendance', $id)`, and
  `add_attendance` inserts with a POST-supplied `employee_id`, none
  checking the row's `employee_id → company_id` against the caller.
  A company/HR session with `can_attendance` can edit/delete/inject
  any tenant's attendance rows by id. (`delete_range` L41-77 *is*
  correctly scoped — the outlier is the single-row actions.)
- **1b — `workforce_planning/page.php`** (L73-86): `edit_wp` does
  `dbUpdate('workforce_planning', $payload, $id)` with a
  caller-controlled `company_id` in the payload and no check that
  `$id` belongs to the caller — hijacking/reassigning a victim row —
  and `delete_wp` does a bare `dbDelete('workforce_planning', $id)`.
  Every sibling org page (`branches`/`departments`/`job_titles`/
  `shifts`) calls `org_verify_post_row()`; this one does not.
- **1c — `payroll/page.php`** (L94-107, 115-116): the batch-detail
  *list* view **is** guarded (L127-144 adds `AND pr.company_id` for
  scoped sessions), but the POST actions are not: `calculate`
  (L94-100), `finalize` (L101), `reopen` (L102), `delete_run` (L103),
  and `edit_detail` (L104-107, rewriting 12 salary/attendance fields)
  all act on a raw `$id`, and the `edit_detail` GET (L116) reads any
  payslip by id. A scoped session with `can_payroll` can finalize,
  reopen, delete, recalculate, read, or rewrite another company's
  payroll batch and payslip figures by id. **Highest severity of the
  three — financial data.**

The guard `org_assert_company_row()` exists (org_helper.php L74-80)
and is **never called anywhere in the repository** (grep-confirmed) —
an implemented-but-unused primitive. In the new platform this class is
structurally closed: RLS plus the per-request permission gate make an
omitted app-level check fail closed rather than fail open
(`docs/architecture/authorization-model.md` §4, F-13/F-18).

### Finding 2 — Admin password change writes plaintext into source

`dashboard/includes/account_helper.php` L19-38: the admin
`change_password` path verifies the old password with `!==` against
the `ADMIN_PASSWORD` constant (not `admin_password_valid()`/
`hash_equals`) and rewrites the live `includes/constants.php` source
file in place (`preg_replace` + `file_put_contents`) to store the new
password **in plaintext in code**, never touching
`ADMIN_PASSWORD_HASH`. Since login (`security.php` L182-189) prefers
the hash when defined, on a hash-configured deployment this flow both
validates the wrong credential and silently no-ops. Requires the web
process to have write access to its own source tree. Do not port.

### Finding 3 — Branch attendance-QR secret leaked to a third party

`org_branch_qr_image_url()` (org_helper.php L773-780), used by
`branches/_branch_qr_modal.php`: the branch check-in QR token —
validated live by `apis/api/attendance/check_in_qr.php` as a
shared secret — is rendered by sending it in cleartext as a URL
query parameter to `https://api.qrserver.com` every time an admin
opens the QR modal. Should be self-hosted in the rewrite. No
upper bound on QR expiry; no replay protection beyond expiry.

### Finding 4 — Session-scoped login lockout is bypassable

`security.php` L148-173: brute-force attempt counting lives in
`$_SESSION`, so clearing cookies resets it — not per-account or
per-IP. Corroborates the auth-hardening direction already recorded
under ADR-0005 / `hr-legacy#10`.

## Findings — Business Rules (detail in `business-rule-extraction.md`)

- **Join-request reject hard-deletes the employee row**
  (`home_service.php` L643, `dbDelete('employees', $id)`) rather than
  soft-deleting — no audit trail of a rejected applicant.
- **Password change always force-logs-out** the session for all three
  principal types (`account_helper.php`; `change_password/page.php`
  L21-24 `session_destroy()`).
- **Setting-template options are immutable/undeletable while
  referenced** (`setting_templates_helper.php` L208-248 — blocks value
  edit and delete once `companies_using > 0`).
- **A department cannot be saved with zero branches**
  (`departments/page.php` L33-36; the branch set is validated to
  belong to the company via `org_department_validate_branches_for_company`).
- **`shifts.days_off` (weekly rest days, consumed by payroll via
  `apis/helpers/schedule_helper.php`) has no dashboard UI** in
  `shifts/page.php` or `_shift_form.php` — an undocumented gap: the
  field that drives working-day/rest-day payroll math is not settable
  from the reviewed admin surface. Open question flagged below.
- **Branch QR issuance**: code = `bin2hex(random_bytes(16))` (128-bit
  CSPRNG), stored on the branch row with an admin-set `expires_at`;
  active-ness derived at read time, not stored; regeneration
  overwrites in place with no history.

## Bugs (fatal / broken, non-security)

- **`q1()` is undefined repo-wide** (grep-confirmed) yet called by
  `dashboard/includes/phone_countries_helper.php` L28,68 and
  `apis/helpers/phone_countries_helper.php` L74,89 — any add/edit in
  the admin "Countries" flow fatals with "Call to undefined function".
- **Dead redirect shims**: `content/page.php` and `app_content/page.php`
  are 302-only stubs superseded by `settings.php?tab=app_content`.

## Open Questions (need a human answer, not inferred)

- Where is `shifts.days_off` actually maintained in production, given
  no dashboard UI sets it? (Direct DB/import/seed, or another unread
  surface?) Payroll correctness depends on it.
- `DASHBOARD_LOGIN_SHOW_TYPE_TABS` (login/page.php L14-28) gates
  whether company/HR dashboard login is even reachable; its value
  lives in the git-ignored `constants.php`, unread. Confirm before
  scoping the login-page migration.

## Consequences

PMR-01's evidence bar is met: every dashboard file is read, and the
security/business-rule findings are cited into the canonical docs. The
three confirmed IDOR instances sharpen hr-legacy#6 from a blanket
claim into named acceptance criteria (each equivalent new module's
F-18 negative test already covers this class — the payroll group's
`PayrollModuleFlowTest` in particular). No finding changes an accepted
ADR; ADR-0002/0003 can be accepted with full-confidence dashboard
coverage behind them.

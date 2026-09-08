# ADR-0018: One Administrator, One Password

## Metadata

| Field | Value |
|---|---|
| ADR ID | ADR-0018 |
| Title | One Administrator, One Password |
| Status | Accepted |
| Date | 2026-09-08 |
| Owners | Solution Architect |
| Deciders | Repository owner, 2026-09-07/08 — "I need to log in to the web admin like PHP but with Java guard", and, offered the three readings of that, chose **password only, like PHP** over password-plus-TOTP and over keeping individual accounts. Recorded in `docs/bootstrap/decision-log-wave12r.md` D-205 |
| Related Issues | hr-legacy#11 (the shared admin password) |
| Supersedes | ADR-0015's authentication model — individual administrators, TOTP with seed custody, step-up approvals, the bearer API. ADR-0015's surface, session, CSRF, cookie, revalidation and audit decisions stand. |
| Superseded By | None |

## Context

The PHP dashboard's administrator login is one shared password held in a
configuration constant (`doAdminLogin()` → `admin_password_valid()`), no phone,
no second factor; the company and HR tabs exist in the markup but production
switches them off (`DASHBOARD_LOGIN_SHOW_TYPE_TABS = false`). ADR-0015 replaced
that with individual administrators identified by phone, a TOTP second factor
with seed custody and an enrolment ceremony, step-up approvals bound to each
privileged action, and a bearer API for a BFF that ADR-0015 itself later
removed.

The owner's instruction is that the Java dashboard must sign in the way the
PHP one does. Asked which of three things that meant, the owner chose the
first: PHP's flow exactly, with Java's protections behind the form.

## Decision

The dashboard has **one administrator and one password**, and no other way in.

- The password is `APP_PLATFORM_ADMIN_PASSWORD`, deployment configuration as
  PHP's constant is. The application keeps a **bcrypt hash** of it in the one
  `platform_admins` row (identifier `admin`), created on first start and
  re-encoded on any start where the configured value no longer matches — so a
  rotation is a change and a restart, and the plaintext is never stored.
- The login page is PHP's page, class for class, in its admin-only shape: one
  password field, the show/hide toggle, the "remember me" box PHP ignores, the
  language pill, the hero. `login.css` and `login.js` are the copied files.
- Behind the form, what PHP does not do: a hash comparison rather than
  `hash_equals` against a constant; a miss budget of eight in fifteen minutes
  spent **per client address** — a per-account budget with one account would
  let anyone lock the administrator out from anywhere, and PHP's per-session
  lock is walked around by a new session; session rotation on login; the
  `Secure`/`HttpOnly`/`SameSite=Lax` cookie; a CSRF token on every state
  change; per-request revalidation of the row's `active` flag; 30-minute idle
  and 8-hour absolute limits; an audit row for every login, miss and logout.
- **Removed**, not disabled: the TOTP second factor and its enrolment
  ceremony, the seed cipher and its key, step-up approvals and the three-step
  company action, the bearer API with its JWTs and refresh-token families,
  and every `factorBound` gate — 76 call sites across the services and 24
  templates that refused writes to a session without a bound factor. The four
  tables that served them leave `phase1_extensions.sql`.
- Company lifecycle actions are one CSRF-protected POST each, like every
  other page's actions, still behind the surface flag (ADR-0015 prerequisite
  7) and still audited in the same transaction.

## Alternatives Considered

The owner was offered three readings of "log in like PHP, with Java's guard":

- **Password, then a TOTP code** (recommended at the time): PHP's page and
  PHP's single password, with the second factor kept behind it. Rejected by the
  owner -- PHP has no second factor, and the instruction was PHP's flow.
- **Password only, like PHP** -- chosen.
- **Keep individual administrators and restyle the page**: ADR-0015's model
  under PHP's look. Rejected by the owner -- a phone field is not PHP's login.

## Consequences

- **The audit trail records the role, not the person.** Every row is attributed
  to the one administrator. Who typed the password is not knowable from the
  application, exactly as it is not knowable from PHP. This is the cost the
  owner accepted in choosing the model; **R-069** records it.
- **Prerequisite 7 matters more, not less.** While the PHP dashboard is
  reachable, the same password opens both doors, and the Java side's guards do
  nothing for the PHP side's. The actions flag ships off until PHP is gone.
- The `/admin/sessions` page still lists and revokes the administrator's
  browser sessions; it always worked on Spring Session, not on the removed
  refresh tokens.
- `scripts/verify-platform-admin-flow.sh` exercised the bearer API and is
  removed with it. The deployment E2E suite (PR 175) signs in with a phone and a
  code and needs a one-helper change when it lands on this line.

## Risks

- **One password, one identity: the audit names the role, not the person.**
  Accepted by decision; **R-069** records it and the trigger for reopening
  ADR-0015's individual-administrator model (a second person needing their own
  accountability).
- **The same password opens the PHP dashboard while it is reachable**, and the
  Java guards do nothing for that door. Mitigated by ADR-0015 prerequisite 7:
  the actions flag stays off until PHP is gone (**R-049**).
- **A per-client miss budget can be spread across addresses.** Eight misses per
  address per fifteen minutes still bounds a distributed guesser to a rate a
  bcrypt hash of a real password withstands; the login-attempt table records
  every miss with its address for an operator to see (`monitoring-and-alerting.md`).

## Rollback

`git revert` of the carrying commit restores every removed class, template and
table definition. The `platform_admins` row created under this ADR is a valid
row under ADR-0015's model too (its phone column holds `admin`), so nothing in
the database needs undoing.

## Validation Evidence

Owner's instruction and choice, 2026-09-07/08 (D-205). `hr-legacy/dashboard/pages/login/page.php`,
`includes/auth.php` (`doAdminLogin`), `includes/security.php`
(`admin_password_valid`, the session lock), `includes/constants.php`
(`DASHBOARD_LOGIN_SHOW_TYPE_TABS`). Full backend suite on the merged line —
recorded in D-205.

## Open Questions

- When a second administrator is needed, does the owner want ADR-0015's
  per-person model back, or PHP's answer (share the password)? Not decided;
  R-069 names it as the trigger.

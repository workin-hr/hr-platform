# OTP Hardening — Design (2026-08-08)

## Purpose And Authority

`hr-legacy` commit `d113204` ("feat(legacy): attendance calendar/session
overhaul, payroll calc updates, weekly rest credit, OTP/WhatsApp/export
improvements") landed a 206-insertion/16-deletion rewrite of
`apis/helpers/otp_helper.php` on the same day as this document, adding
rate limiting, request auditing, and purpose tagging that did not exist
before. This document catalogs that hardened behavior in full and
proposes what a corresponding OTP module in hr-platform would need —
**this is planning output only** (hr-platform `CLAUDE.md`: Claude's role
here is planning/analysis/review, not implementation). No repository-owner
decision event authorized a specific hr-platform build sequence for this
module; this document surfaces the design and its open questions rather
than asserting one.

**Confirmed: hr-platform has no OTP module today.** A repo-wide search for
`otp`/`whatsapp`/`sms`/`twilio`/`sendgrid`/`nexmo` (case-insensitive,
`.java`/`.yml`/`.properties`) returned two matches, both incidental
(`ExceptionType.java`, `PermissionKeys.java` — neither OTP-related). No
entity, repository, service, controller, migration, or test exists. This
is new-module design, not a port of a partially built feature.

**Supersession notice for `docs/legacy/business-rule-extraction.md`:**
lines 78–91 ("OTP is single-active-per-phone... issuing a new OTP first
deletes all existing rows") describe the **pre-hardening** delete
mechanism and are now **stale** — `d113204` replaced the delete with a
soft-invalidate (below). Lines 458–480 ("OTP verification has no
attempt/rate limiting; only the resend has a cooldown") were re-checked
against the hardened file and are **confirmed still accurate**: the diff
added `is_used` exclusion to the verify query but added no attempt
counter, lockout, or delay to verification itself. Whoever picks this up
next should treat :78-91 as superseded by this document and :458-480 as
still-live.

Evidence: full read of `hr-legacy/apis/helpers/otp_helper.php` (359
lines, current `main` @ `d113204`); `git show d113204 -- apis/helpers/otp_helper.php`
for the exact diff; `hr-legacy/mysql_workin.schema.sql:657-664,1191-1192,1457-1458`
(`otp_codes` DDL — identical in `mysql_workin.sql`'s full dump);
`hr-legacy/apis/config/tables.php:39-40` (`Table::OTP_CODES`,
`Table::OTP_REQUEST_LOGS` constants). hr-platform conventions confirmed
via `backend/src/main/java/com/workin/backend/authorization/PermissionKeys.java`,
`.../organization/Shift.java`/`ShiftService.java`, `.../identity/Identity.java`,
`.../identity/AuthController.java`, `.../authorization/PublicUseCase.java`.

## Scope

**In — legacy behavior catalog (all read from the current helper file):**

- **Soft-invalidate, not delete** (`otp_clear_for_phone`, lines 77-89):

  ```sql
  UPDATE otp_codes SET is_used = 1
  WHERE phone = ? AND COALESCE(is_used, 0) = 0
  ```

  replacing the prior version's `DELETE FROM otp_codes WHERE phone = ?`.
  Rows are kept for audit and as a rate-limit fallback source. Verification
  (`otp_verify_latest_for_phone`, lines 244-263) gained `AND COALESCE(is_used, 0) = 0`
  in the same commit, so a soft-invalidated row can never verify even if
  unexpired — behaviorally equivalent to delete for "old code stops
  working," different only in that the row persists.
- **Rate limiting** (`otp_assert_can_send`, lines 157-177), three checks
  in sequence, first violation wins:
  - Cooldown, **cross-purpose per phone** (the count query passes
    `purpose=''`, i.e. any purpose counts): 90 seconds if
    `purpose === 'password_reset'`, else 60 seconds for every other
    purpose.
  - Hourly cap, **purpose-specific per phone**: 5/hour for
    `password_reset`, 10/hour for every other purpose.
  - Hourly cap, **cross-purpose per IP**: 20/hour regardless of purpose.
  - Violations return `LangKey::PLEASE_WAIT_BEFORE_RESENDING` (cooldown)
    or `LangKey::OTP_TOO_MANY_REQUESTS` (either hourly cap), both HTTP 429.
  - `otp_assert_can_send` is called only from `otp_issue_and_send_whatsapp`
    (line 351) in the file itself. **Not confirmed in this pass**: whether
    every OTP-issuing call site in the wider codebase routes through that
    one function, or whether some caller invokes `otp_issue_for_phone`
    directly and bypasses rate limiting — this reading covered the helper
    file only, not its callers. Flagging as an open item for whoever scopes
    the hr-platform port: don't assume full caller coverage without an
    audit of `apis/api/**` call sites.
- **Purpose tagging** — exactly four values
  (`otp_purpose_from_message_key`, lines 330-336): `password_reset`,
  `resend`, `verify`, and `generic` (default/fallback for any unmapped
  WhatsApp template key). Purpose drives the stricter `password_reset`
  thresholds above and is written to `otp_codes.purpose`/
  `otp_request_logs.purpose` when those columns exist. Purpose is derived
  from which WhatsApp message template the caller intends to send, though
  `otp_issue_and_send_whatsapp`'s optional `$purpose` parameter lets a
  caller override the derived value.
- **`otp_request_logs` has no DDL anywhere in the repo.** A search across
  every `.sql` file and the full codebase found only the `Table::OTP_REQUEST_LOGS`
  constant and the runtime existence check (`otp_request_logs_ready`,
  lines 26-38, `SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES ...`). The
  helper is written to degrade gracefully when the table is absent,
  falling back to counting off `otp_codes`' own history
  (`otp_count_recent_sends`, lines 110-152). **There is no legacy DDL to
  port for this table** — any schema proposed for it is inferred from the
  four columns the `INSERT` actually writes (`phone`, `purpose`,
  `ip_address`, `user_agent`, line 101) plus an implied `created_at`
  (used in every windowed count query), not copied from an authoritative
  source.
- **`otp_codes`'s checked-in DDL lacks `ip_address`/`user_agent`/`purpose`.**
  `mysql_workin.schema.sql:657-664` (confirmed identical in the full
  `mysql_workin.sql` dump) shows only `id`, `phone`, `code`, `is_used`,
  `expires_at`, `created_at` — no `ip_address`, `user_agent`, or `purpose`
  columns, despite `otp_issue_for_phone` (lines 182-228) writing to them
  conditionally via `otp_table_has_column()` (an `INFORMATION_SCHEMA.COLUMNS`
  check, lines 11-24). **Fact:** the schema dump and the hardened code
  disagree. **Hypothesis, not confirmed:** either the live production
  database has these columns via an un-dumped `ALTER TABLE`, or the code
  is forward-compatible ahead of a migration that hasn't landed yet.
  Either way, hr-platform's design below defines these columns outright
  rather than reproducing the feature-detection.
- **Delivery is fused with issuance.** `otp_issue_and_send_whatsapp`
  (lines 342-359): rate-limit check → DB insert → audit log → WhatsApp
  send; delivery failure (`OTP_DELIVERY_FAILED`, 503) happens *after* the
  insert and log, with no visible rollback of either. A failed-delivery
  attempt still consumes a rate-limit slot and starts the cooldown even
  though the user received nothing — an inherited UX edge case, not to
  be silently changed without the owner's sign-off.

**In — proposed hr-platform schema** (new design, not a direct port,
since one side has no DDL and the other's checked-in DDL is incomplete):
`otp_codes` (`id`, `phone VARCHAR(20) NOT NULL`, `code VARCHAR(10) NOT NULL`,
`purpose VARCHAR(30) NOT NULL DEFAULT 'generic'`, `ip_address VARCHAR(45)`,
`user_agent VARCHAR(512)`, `is_used BOOLEAN NOT NULL DEFAULT FALSE`,
`expires_at TIMESTAMPTZ NOT NULL`, `created_at TIMESTAMPTZ NOT NULL DEFAULT now()`,
index on `(phone, created_at DESC)`) and `otp_request_logs` (`id`, `phone
VARCHAR(20) NOT NULL`, `purpose VARCHAR(30) NOT NULL`, `ip_address
VARCHAR(45)`, `user_agent VARCHAR(512)`, `created_at TIMESTAMPTZ NOT NULL
DEFAULT now()`, indexes on `(phone, purpose, created_at)` and
`(ip_address, created_at)`). No `company_id` on either table — see Design.

**Out (tracked, blockers named):**

- Actual SMS/WhatsApp delivery — see the open question below.
- The password-reset and phone-verification *flows* that would consume
  this primitive: `identity/AuthController.java` currently exposes only
  login/register/refresh/revoke (lines 34, 42, 49, 59, each
  `@PublicUseCase`), no reset-password endpoint. This spec covers the OTP
  primitive only.
- Verification-side hardening (attempt counters, lockout) beyond what
  legacy does — `business-rule-extraction.md:458-480`'s finding is
  confirmed still live; whether to close that gap in hr-platform is a
  security/product decision for the owner, not assumed here.
- `otp_resolve_country_code_for_phone`'s companies→employees fallback —
  read but not scoped in, since hr-platform's equivalent phone-lookup
  paths need their own confirmation pass first.

## Design

**Open question, not decided here — the one that matters most:** hr-platform
has no phone-based OTP delivery mechanism (no SMS/WhatsApp sender of any
kind, confirmed above). Building `otp_assert_can_send`'s rate-limiting
logic, `otp_codes`/`otp_request_logs`, and a verify endpoint is
straightforward in isolation, but an OTP module with no delivery path
behind it can only be exercised by tests that inject a code directly —
it has no real caller until something can text/WhatsApp a user. The
repository owner needs to decide sequencing: is delivery infrastructure
being built alongside this module (in which case this spec's schema and
rate limits are the right next step), or is this OTP verification logic
meant to exist ahead of any delivery mechanism (in which case building
the rate-limit/audit machinery now may be premature — a stub delivery
interface plus the core issue/verify functions might be the right-sized
first slice instead)? Not inferable from either codebase; flagging for
an explicit answer before an implementation plan is written.

New package `com.workin.backend.otp` (not folded into `identity`) — a
weaker open question than the one above: OTP is a reusable primitive
multiple future consumers (password reset, phone verification, possibly
login-OTP) would call, closer to the schedule module's "new bounded
concept, own package" precedent
(`docs/superpowers/specs/2026-08-08-employee-schedule-foundation-design.md`)
than to extending `identity`. `OtpCode`/`OtpRequestLog` entities carry no
`companyId` field, mirroring `identity/Identity.java`'s explicit
"intentionally carries no company_id, no role" design (`Identity.java`
lines 10-16, citing ADR-0010 Dimension 1) — a phone number requesting a
code is a pre-tenant concept, same shape as `Identity` itself. No RLS
migration follows; these are not tenant data, unlike every table the
organization-structure and schedule specs added.

Service/controller shape otherwise follows the existing quartet template
(`ShiftService`/`ShiftController` read for comparison) — `Optional`/plain
return, `DataIntegrityViolationException` → `ResponseStatusException(CONFLICT)`
where relevant — but **not** gated by `AuthorizationContext`/
`tenantSessionVariable.apply(...)` the way every other module's service
is, since issuance and verification happen pre-authentication. Controller
endpoints need `@PublicUseCase(reason = "...")` (the pattern already on
`AuthController`'s login/register/refresh/revoke handlers,
`authorization/PublicUseCase.java`), not a `PermissionKeys` constant —
worth stating so an implementer doesn't reflexively add
`OTP_READ`/`OTP_MANAGE`-style catalog keys to a surface that is
structurally pre-auth, unlike every module the prior two specs added.

Pure logic to port 1:1 once sequencing is decided: `otp_generate_code`'s
4-digit zero-padded generation, the three thresholds in
`otp_assert_can_send` exactly as enumerated above, and
`otp_purpose_from_message_key`'s four-way match (adapted to whatever
message/template naming hr-platform adopts once delivery exists). The
`otp_table_has_column`/`otp_request_logs_ready` `INFORMATION_SCHEMA`
feature-detection has no hr-platform analogue — Flyway migrations are
unconditional everywhere else in this codebase — and should not be
ported; one migration creates both tables with their full column sets
from the start.

## Testing

Deferred until the sequencing open question is resolved, since a test
plan depends on whether a delivery interface exists to stub. If the
primitive-only path is chosen: cooldown enforcement (issue, immediate
re-issue same purpose → 429, wait past 90s/60s → succeeds); per-phone
hourly cap distinguishes `password_reset` (5) from other purposes (10);
per-IP hourly cap (20) trips independent of purpose or phone; soft-invalidate
— issuing a second code for a phone leaves the first row present with
`is_used = true` and unverifiable, not deleted; verify accepts only the
latest unused, unexpired, matching code; expired or already-used code →
rejected; purpose defaults to `generic` when unspecified. Cross-tenant
concerns do not apply (no `company_id`). Every case above needs to run
without an `AuthorizationContext` fixture, unlike every other module's
`AbstractIntegrationTest`-based flow test, since these endpoints are
`@PublicUseCase`.

## Consequences

Until the delivery-sequencing question is answered, this module unblocks
nothing downstream — there is no password-reset or phone-verification
endpoint yet to consume it, and no way to deliver a code even if one
were issued. Building the rate-limit/audit machinery ahead of a delivery
mechanism risks being effort spent on infrastructure for a feature that
cannot be exercised end-to-end; building delivery without this hardening
risks reproducing the pre-`d113204` unlimited-OTP behavior that
`business-rule-extraction.md:78-91` (now-superseded) and :458-480
(still-live) both flagged as a real account-takeover surface. Whichever
order the owner picks, `docs/legacy/business-rule-extraction.md`'s two
OTP entries should be annotated or corrected in place: :78-91 is stale as
of this document, :458-480 remains accurate.

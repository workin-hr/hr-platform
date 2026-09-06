# Production Smoke And Post-Deployment Validation

Use this document to define the checks that confirm a release is safe
immediately after deployment and the follow-up validations that confirm the
system is behaving correctly after it has been live long enough to observe
real traffic or scheduled processing.

Production smoke tests are fast release-safety checks. Post-deployment
validation is broader and may include delayed or manual confirmation. Neither
should rely on assumed behavior; each check needs a pass condition, an owner,
and evidence.

## The Phase 1 Cutover Smoke Checklist

*Filled 2026-09-04, replacing the template that stood here. Run at step 7
of `docs/operations/release-cutover-and-rollback.md` against the jar
directly, and again at step 9 through the real routing path. The same
list both times — a check that passes directly and fails through the
router has told you the routing is wrong, which is worth knowing
separately.*

### Two constraints that shape every check

**This runs against production, with real customer data.** Three legacy
routes are destructive and must never be used as smoke checks on a real
account:

| Route | What it actually does |
|---|---|
| `profile/logout` | Deletes push tokens, **deactivates the employee**, and notifies the company that they left |
| Password change | Replaces the credential — the user can no longer log in |
| `reset_password` | Same, and commits the new password *before* the session revocation that may fail |

Legacy's "logout" is an account deactivation. Running it against a live
employee to test a table would disable that person and send their
employer a departure notice.

**Use a dedicated account** — a real employee row in a real company,
created for this purpose and known to be nobody's. `spike/parity-harness/seed-two.sh`
documents the shape; do not reuse its ids, which are harness-only.

**Every check needs a negative control.** The lesson of R-023 is a check
that passes for the wrong reason: login succeeds whether or not
`legacy_refresh_tokens` exists, so a login-only smoke test certifies a
deployment whose logout and password reset are broken.

### The checks

| # | Check | Pass condition | Negative control — why it can't pass for the wrong reason |
|---|---|---|---|
| 1 | Startup log | `Phase 1 schema check: all 10 owned tables are present`, the fingerprint line, and `WhatsApp OTP delivery is configured` | These are three distinct lines. A missing one is a real gap, not a logging quirk |
| 2 | Health endpoint | 200 | Confirms the process is up and nothing more — never treat it as a release check |
| 3 | Login as the test account | 200, the same envelope shape, a usable token | Repeat with a **wrong password**: must be the same 401 PHP gives, not a 500 |
| 4 | Authenticated read (`requests/list`) | 200, paginated shape | Repeat with **no token**: must be 401. A route that answers 200 unauthenticated is a filter-chain failure |
| 5 | **Token refresh** | 200, a new token, and the old one no longer accepted | This is the only check that writes to `legacy_refresh_tokens`. Without it R-023 is untested — see the destructive-routes table for why logout is not the way to test it |
| 6 | Tenant scoping | A read scoped to the test account's company returns only that company's rows | Request a **known id from another company**: must be 404/403, not that row. Proves the scope filter is live, not that the query happened to return the right thing |
| 7 | `/admin/login` renders | 200, the login form | Request `/admin/companies` **unauthenticated**: must redirect to login, not render |
| 8 | An OTP route | An OTP actually arrives | Not `otp_delivery_failed`. See the R-015 section below |

Checks 1–4 and 7 are non-destructive and can run against production
unchanged. Check 5 writes refresh-token rows for the test account only.
Check 6 reads another company's id but must not return it — that is the
point.

### After the traffic move

Re-run all of it (step 9). Then watch, rather than check, for the first
hours:

- error rate and latency against the PHP baseline
- any `otp_delivery_failed`
- any missing-table error
- 401 rate — a rise means the signing secrets differ after all

The rollback triggers in `release-cutover-and-rollback.md` are written
against exactly these signals.

### Not covered here

Push notifications (FCM) do not work in legacy either (**F-08**,
`hr-platform#22`), so there is nothing to smoke and nothing lost. Device
attendance ingestion is not in this cutover.

## WhatsApp OTP Delivery (R-015) — Required Before Auth Cutover

The legacy auth and profile routes issue every OTP through the Whats360
gateway. Its credentials are **not** in this repository and the properties
default to empty, so an unconfigured deployment answers **503
`otp_delivery_failed`** on every OTP-issuing route:
`auth/resend_otp`, `auth/forgot_password`, `auth/register_company`, the
verify-first branch of **both** `auth/login_company` and
`auth/login_desktop` (`login_as=company`), and
`profile/request_phone_change`. Nobody can register, reset a password, or
verify a phone — on mobile **or** desktop.

### Configuration

Supply these from the deployment's secret store. **Never commit a value** —
`app.legacy-whatsapp.api-token` is a credential, and it travels in the request
URL, which is why `LegacyWhatsAppHttpSender` logs only an exception's class
name and never its message.

| Property | Environment variable | Required? |
|---|---|---|
| `app.legacy-whatsapp.api-token` | `LEGACY_WHATSAPP_API_TOKEN` | **Yes** |
| `app.legacy-whatsapp.instance-id` | `LEGACY_WHATSAPP_INSTANCE_ID` | **Yes** — at least one instance |
| `app.legacy-whatsapp.instance-id-fallback` | `LEGACY_WHATSAPP_INSTANCE_ID_FALLBACK` | No — used when the primary reports "not connected" |
| `app.legacy-whatsapp.api-base` | `LEGACY_WHATSAPP_API_BASE` | No — defaults to legacy's endpoint |

Use the same Whats360 account the frozen stack uses. A token or instance id
left at its committed placeholder counts as unconfigured.

### Pre-cutover validation

1. **Confirm it is configured at all.** Start the service and read the startup
   line from `LegacyWhatsAppHttpSender`: either
   `WhatsApp OTP delivery is configured: N instance(s)` or
   `WhatsApp is not configured at startup`. One of the two is always emitted, so
   absence of the error means the check did not run — not that it passed.

   > Until this line existed, this step said to grep for
   > `WhatsApp is not configured`, which is logged only inside `sendText()`. An
   > operator running the grep *before* making an OTP request always found
   > nothing, including when every credential was empty, and read that as a
   > pass. Do not go back to inferring configuration from an absent log line.
2. **Send one real code.** `POST /apis/api/auth/resend_otp` with a phone
   the operator controls. Expect **200** and an actual WhatsApp message. A
   **503** means delivery failed — check the log for
   `WhatsApp delivery failed on every instance` (transport or credentials)
   versus `no LID found` (the number is not on WhatsApp, which is not an
   outage).
3. **Confirm the code is not in the response.** The body's `data` must be `[]`.
   An OTP appearing there is the PMR-05 / `hr-legacy#4` disclosure and is a
   release blocker, not a smoke-test note.
4. **Exercise the fallback**, if a fallback instance is configured: an instance
   that answers "not connected" is skipped for fifteen minutes, so a second
   send within that window should still succeed.

### What an operator sees when it fails

| Symptom | Log line | Meaning |
|---|---|---|
| Every OTP route 503s immediately | `WhatsApp is not configured` (ERROR, every attempt) | No credentials supplied |
| OTP routes 503 intermittently | `WhatsApp instance disconnected` then `will prefer fallback briefly` | The primary instance dropped; the fallback is being preferred for 15 minutes |
| One number always 503s | `WhatsApp number unreachable ... no LID found` | That number is not on WhatsApp — not an outage |
| Every OTP route 503s after working | `WhatsApp delivery failed on every instance` | Gateway outage, expired token, or network egress blocked |

**A 503 here is indistinguishable to a user from the platform being down**, so
this path is worth an alert rather than only a dashboard.

### Rate limiting interacts with this — R-014

The OTP limiter's per-IP cap currently behaves as a **platform-wide 20-per-hour
cap** against the frozen schema. During validation, twenty sends in an hour
will start refusing *unrelated* callers with 429
`otp_too_many_requests`. Do not read that as a WhatsApp failure, and do not run
a high-volume smoke test on this path without reading R-014 first.

## Open Questions

- Which smoke tests can be automated safely for every production deployment?
- Which validations require manual business confirmation after release?
- What observation window is required before a high-risk release is considered
  stable?
- Which checks must also run after rollback, not only after forward release?
- Which client, migration, or integration paths are critical enough to always
  appear in this checklist?

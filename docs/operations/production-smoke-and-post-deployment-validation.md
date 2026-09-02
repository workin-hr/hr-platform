# Production Smoke And Post-Deployment Validation

Use this document to define the checks that confirm a release is safe
immediately after deployment and the follow-up validations that confirm the
system is behaving correctly after it has been live long enough to observe
real traffic or scheduled processing.

Production smoke tests are fast release-safety checks. Post-deployment
validation is broader and may include delayed or manual confirmation. Neither
should rely on assumed behavior; each check needs a pass condition, an owner,
and evidence.

## Check

Describe the exact behavior being verified. Prefer checks tied to observable
system behavior rather than internal hope statements.

Examples:

- application health endpoint returns expected status
- authentication path works for an approved test account
- a critical HR workflow can be completed end to end
- a migration validation query shows expected row counts or invariants
- alerts remain quiet for expected conditions after release
- a scheduled integration or background process completes successfully

## Type (production smoke test / post-deployment validation)

Classify each item as one of:

- `production smoke test`: a fast check run immediately after deployment or
  cutover to detect obvious release failure
- `post-deployment validation`: a follow-up check that may require time,
  real traffic, scheduled execution, or manual confirmation

If a check serves both purposes, record when it runs in each mode rather than
blurring the distinction.

## Trigger (on every deploy, scheduled, manual)

State exactly when the check runs and what causes it to run.

Typical triggers:

- on every production deploy
- after maintenance-window exit
- after a migration step completes
- after feature-flag enablement
- scheduled after a defined observation period
- manual confirmation by an operator or business owner
- on rollback completion

## Pass Criteria

The pass condition must be observable and reviewable.

Good pass criteria examples:

- HTTP endpoint responds with the expected status and payload shape
- no blocking errors appear in release-critical monitoring within the agreed
  observation window
- a validation query returns expected invariants with zero critical mismatch
- a critical workflow completes without customer-visible failure
- no compatibility regression is observed in the monitored client path

Avoid vague criteria such as `looks normal` or `seems healthy`.

## Owner

Record the human role responsible for running or reviewing the check, such as:

- release owner
- operations owner
- QA or test owner
- engineering owner
- migration owner
- customer-support or product owner for business-facing confirmation

Agents may help prepare the checklist, but humans own production validation.

## Evidence

Link the proof that the check ran and passed or failed. Evidence may include:

- deployment log or release record
- smoke-test output
- dashboard screenshot or metric link
- alert history
- migration validation result
- manual test note with timestamp and owner
- customer-support confirmation for externally visible behavior

If evidence is not retained automatically, define how it will be recorded.

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

### Attendance-device receiver (D-156)

| Property | Environment variable | Required? |
|---|---|---|
| `app.devices.ingest.enabled` | `APP_DEVICES_INGEST_ENABLED` | No — defaults to `false`; `true` maps the unauthenticated `/iclock/**` receiver and its permit-all chain |
| `app.devices.ingest.max-body-bytes` | — | No — defaults to 1 MiB |

Smoke check without side effects: `GET /iclock/getrequest` **with no `SN`**
answers `400` with the plain-text body `ERROR: missing SN` when the receiver
is enabled, and is not mapped (`404`) when it is not. Never send a made-up
serial as a check — it is recorded as an unclaimed-device sighting.

**Turning this flag on in production has three preconditions (D-157)**, all
gates rather than checklist items: the §4.3 hardware validation has been
executed on a real customer terminal and recorded in `../devices/`; the five
device tables have an approved provisioning mechanism (**R-023**, see
`release-cutover-and-rollback.md` step 1); and device ownership is
established by platform staff with an audited unclaim/transfer/replace path
(**R-041**), rather than claimed by a tenant admin from a serial number. TLS
at the edge for the device hostname is required throughout. Operator steps:
`../devices/zkteco-adms-receiver-setup.md`.

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

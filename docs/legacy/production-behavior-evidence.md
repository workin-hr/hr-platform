# Production Behavior Evidence

Source: `workin-hr/hr-legacy` commit `83c326e40f68dd0d560595a6c4e465eb681f2ce8`
(source code) and, where noted, `apis/logs/php_errors.log` (real runtime
log — kept local-only, never committed; see that repository's sanitized
import for why). Confidence labels follow this document's own convention:
source-code reading is labeled "observed directly" (the code is the
ground truth for what the system does, not a report about it), separate
from genuinely runtime-observed evidence (the log file).

---

## Behavior: WhatsApp OTP delivery has a two-instance failover, and it fails over in real production traffic

**Source Of Evidence:** `apis/logs/php_errors.log` (real runtime log, ~224
lines, kept local-only) shows a repeated, consistent pattern: a primary
WhatsApp instance returning a 400 "instance not connected" error,
immediately followed by a fallback attempt that succeeds. This happened
repeatedly across many distinct recipient numbers over the log's time
span, not as a one-off incident.

**Confidence:** Observed directly (this is a real runtime log, not
inferred from source alone) for the *fact* that failover happens
routinely in production. Source-code confirmed for the *mechanism*:
`apis/helpers/whatsapp_helper.php` and `AppConfig::WHATSAPP_INSTANCE_ID`
/ `WHATSAPP_INSTANCE_ID_FALLBACK` in `apis/config/constants.php`.

**Notes:** The primary WhatsApp instance failing "not connected" is not a
rare edge case in this log sample — it is the routine path, with the
fallback instance carrying real delivery traffic. A migration that treats
the primary/fallback split as a rarely-exercised safety net, rather than
infrastructure that is regularly relied upon, would be underestimating
its importance. Whoever owns the WhatsApp integration account should be
asked directly why the primary instance disconnects this often, rather
than treating it as already understood — this document does not know why,
only that it happens.

---

## Behavior: `DEBUG`/`APP_DEBUG` are `true` in the committed configuration

**Source Of Evidence:** `apis/config/constants.php` (`AppConfig::DEBUG =
true`) and `dashboard/includes/constants.php` (`define('APP_DEBUG',
true)`), both with an adjacent comment stating debug mode should be
`false` in production.

**Confidence:** Observed directly for the setting's value in the
committed file. Plausible but incomplete for whether the *actual*
production deployment overrides this — this file is what ships in the
repository, not necessarily proof of what's live on the production host,
since environment-specific overrides outside this repository are
possible and not visible here.

**Notes:** If this value is genuinely live in production, `dashboard/includes/db.php`'s
failure path renders `DB_HOST`/`DB_NAME`/`DB_USER` (not the password)
directly into an HTML error page on a DB connection failure — a real
information-disclosure exposure if triggered in front of an
unauthenticated user. Worth a direct question to whoever manages the live
deployment rather than assuming either way.

---

## Behavior: The dashboard and the API are two independent codebases, not a shared application

**Source Of Evidence:** Directory structure and direct code comparison:
`apis/config/constants.php` vs. `dashboard/includes/constants.php` (two
separate files, both defining the same `DB_HOST`/`DB_NAME`/`DB_USER`/
`DB_PASS` values independently rather than one shared source);
`apis/config/pdo.php` vs. `dashboard/includes/db.php` (two separate PDO
connection functions); `apis/config/auth.php` (JWT payload key constants)
vs. `dashboard/includes/auth.php` (session-based login functions) — no
shared authentication code between the two at all.

**Confidence:** Observed directly.

**Notes:** Changing a credential, a business rule, or a validation
constraint in one codebase does not automatically apply to the other —
confirmed by reading both `constants.php` files and finding the database
credentials duplicated verbatim rather than referenced from one place.
This has direct migration-scope implications: "the legacy PHP backend"
is really two backends sharing one database, and each needs its own
Discovery/compatibility pass, not one combined pass that risks missing
dashboard-only behavior.

---

## Behavior: SMS and push-notification delivery are configured but not functional

**Source Of Evidence:** `apis/config/constants.php`:
`FCM_SERVER_KEY = 'YOUR_FCM_SERVER_KEY_HERE'`, `SMS_API_KEY =
'YOUR_SMS_API_KEY_HERE'` — literal placeholder strings, not redacted real
values (compare to `WHATSAPP_API_TOKEN`, which held a real value before
sanitization for the `hr-legacy` import).

**Confidence:** Observed directly for the constants' placeholder values.
Plausible but incomplete for whether any code path actually attempts to
use them at runtime (not traced beyond the constant definitions
themselves in this pass).

**Notes:** Do not assume SMS or push notifications are live production
channels when scoping compatibility work — WhatsApp is confirmed live
(see above), these are not confirmed live.

## Evidence

Files as cited per behavior above. Runtime log evidence
(`apis/logs/php_errors.log`) exists only in the local `hr-legacy`
checkout — it is deliberately not committed anywhere (real customer
phone numbers), so it cannot be re-verified from a fresh clone of either
repository. Anyone needing to re-confirm the WhatsApp-failover finding
independently would need direct access to that log or to the live
WhatsApp account dashboard, not just these two git repositories.

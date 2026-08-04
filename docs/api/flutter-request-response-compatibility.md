# Flutter Request And Response Compatibility

## Source

Two real Flutter client repositories were made available locally for
read-only Discovery on 2026-08-04: `workin_mobile` (employee-facing
mobile app) and `workin_desktop` (company-admin/HR-facing desktop app —
see the "Desktop/Mobile Divergence" finding below, this is not a mirror
of the mobile app). Both are excluded from git via `.gitignore` and were
never committed to this repository — see
`docs/security/pre-migration-flutter-credential-inventory.md` for the
credential-handling record. All entries below are labeled by confidence:
**Confirmed** (read directly in client source) is now possible for the
first time in this Discovery effort, superseding the "Inferred" labels
throughout `docs/api/existing-endpoint-inventory.md`.

## Capability Or Endpoint: Authentication — Employee Login And Registration

**Current Flutter Expectation** (Confirmed, `workin_mobile`): The mobile
app calls `auth/login_employee` and `auth/join_company` —
**`auth/register_employee.php` is never referenced anywhere in the mobile
client's `api_constants.dart` or call sites.** This directly resolves the
open question in `workin-hr/hr-legacy#19`: `join_company` is the real,
live registration path; `register_employee` appears to be dead or
used by some other, undiscovered caller. `_isPublicAuthEndpoint()` in
`http_helper.dart` treats `login_employee`, `join_company`,
`forgot_password`, `reset_password`, `verify_otp`, `resend_otp`, and
`lookup_company` as pre-authentication endpoints that must not trigger a
forced logout on a 401.

**Compatibility Risk**: Low for this specific finding — it *reduces* risk
by resolving an ambiguity, rather than introducing one. Workflow failure
risk remains if a Java rewrite ports `register_employee`'s behavior
instead of `join_company`'s.

**Proposed Handling**: Update `workin-hr/hr-legacy#19` and the migration
plan to treat `join_company` as the canonical registration flow to port;
`register_employee` should be confirmed dead (not just unused by this
client) before being dropped.

**Evidence**: `workin_mobile/lib/core/network/api_constants.dart`
(endpoint declarations); `workin_mobile/lib/core/helper/http_helper.dart`
(`_isPublicAuthEndpoint`).

## Capability Or Endpoint: Session/Token Lifecycle

**Current Flutter Expectation** (Confirmed, both apps): A JWT is cached
client-side after login and sent as `Authorization: Bearer <token>` on
every subsequent request. **There is no token-refresh mechanism anywhere
in the client** — the app has no code path for renewing an expiring
token; it only reacts to an outright `401`. When a `401` is received on a
non-public-auth endpoint, `_forceLogoutOnReplacedSession()` fires,
showing the user a "your session was replaced" message and logging them
out — this is a deliberate, graceful UI response to `hr-legacy`'s
single-active-session model (`employee_issue_session_token()` bumping
`token_version` on a new login elsewhere), not an accidental side effect.
**The token itself is stored in plain `SharedPreferences`** (unencrypted
on both Android and iOS by default — no `flutter_secure_storage` or
platform Keychain/Keystore usage found anywhere in either client).

**Compatibility Risk**: High, compounding an existing finding. This is
new evidence for `workin-hr/hr-legacy#7` (10-year JWT, no revocation): the
client has (a) no capability to handle a shortened token lifetime
gracefully — implementing the recommended fix (short-lived JWT + refresh
tokens) requires **client-side changes**, not just a backend one, and (b)
stores the long-lived token in unencrypted local storage, meaning a
device-level compromise (rooted/jailbroken device, backup extraction, or
another app with storage access on older Android versions) can extract a
credential valid for up to 10 years.

**Proposed Handling**: Document as a required client-side workstream, not
just backend — any Java rewrite that shortens token lifetime needs a
corresponding Flutter refresh-token implementation shipped before or
alongside it, or existing users will be logged out unexpectedly on
every deploy. Recommend `flutter_secure_storage` (or equivalent) for the
new client's token storage regardless of token lifetime chosen.

**Evidence**: `workin_mobile/lib/core/helper/http_helper.dart` (full
file); `workin_mobile/lib/core/helper/cache_helper.dart` (full file,
plain `SharedPreferences` wrapper, `tokenKey` stored via `setData`/`getData`
with no encryption layer).

## Capability Or Endpoint: Check-In Request Contract

**Current Flutter Expectation** (Confirmed, `workin_mobile`): `CheckInParameters`
sends exactly `{latitude, longitude, method: 'app'}` — no `employee_id`
field is ever sent by the mobile client, confirming self-check-in only
(the HR-on-behalf-of `employee_id` override documented in
`docs/api/existing-endpoint-inventory.md` is not exercised by this
client). `method` is hardcoded to the literal string `'app'` — the mobile
client never sends `'qr'` or `'excel'`.

**Compatibility Risk**: Low — this is an exact match to the already-documented
server contract (`apis/api/attendance/check_in.php`), with no surprises.

**Proposed Handling**: Preserve exact behavior; this is a clean,
low-risk contract to port.

**Evidence**: `workin_mobile/lib/domain/usecases/attendance/check_in_usecase.dart`
(full file).

## Capability Or Endpoint: QR Check-In

**Current Flutter Expectation** (Confirmed, both apps): **Neither the
mobile nor the desktop client references `attendance/check_in_qr`
anywhere** — not in `api_constants.dart`, not in any data-source or
repository file (checked via full-tree search, not just the constants
file). The desktop app *can* generate a branch's QR code
(`branches/generate_qr` is called), but no client discovered so far can
*consume* one to actually check in.

**Compatibility Risk**: Low compatibility risk (nothing currently depends
on it from these two clients), but a real open question: either this
feature has a third, undiscovered client (e.g. a kiosk/tablet app, or a
manual scan-and-submit flow inside the dashboard not yet checked), or it
is dead/abandoned functionality server-side. This affects the severity
assessment of `workin-hr/hr-legacy#16` (QR check-in skips the 2-hour gap
rule) — that finding remains valid as written (the endpoint exists and is
reachable), but its real-world exploitability depends on whether anything
actually calls it in production.

**Proposed Handling**: Document as an open question for whoever owns the
QR check-in feature — confirm whether a third client exists before
deciding whether to port this endpoint's behavior at all.

**Evidence**: Full-tree search of `workin_mobile/lib` and
`workin_desktop/lib` for `check_in_qr`/`checkInQr` — zero matches beyond
the unrelated `generateBranchQrEndpoint`.

## Capability Or Endpoint: Company/HR Attendance-Method Endpoint (Client References A Nonexistent Server Endpoint)

**Current Flutter Expectation** (Confirmed, `workin_desktop`):
`api_constants.dart` declares
`setEmployeeAttendanceMethodEndpoint = 'attendance/set_employee_attendance_method'`,
but **no `apis/api/attendance/set_employee_attendance_method.php` exists
in `hr-legacy`** (confirmed against the full file listing of that
directory), and **the constant itself is never referenced anywhere else
in the desktop client's source** — it is dead client-side code pointing
at a server endpoint that was never built (or was renamed/removed).

**Compatibility Risk**: None currently (nothing calls it), but worth
flagging so a future implementer doesn't assume it needs porting, or
conversely doesn't miss that this was clearly an intended-but-never-shipped
feature (per-employee attendance-method configuration, presumably
related to `employees.is_mobile_attendance_enabled`/`can_check_in_any_branch`,
which *are* implemented server-side via `employees/update.php`).

**Proposed Handling**: Document as an open question, not an action item —
confirm with whoever owns this feature whether it was abandoned or is
still planned, before deciding whether the Java rewrite needs an
equivalent endpoint.

**Evidence**: `workin_desktop/lib/core/network/api_constants.dart` line
107 (declaration); full-tree search for `setEmployeeAttendanceMethodEndpoint`
— one match, the declaration itself; `hr-legacy` `apis/api/attendance/`
directory listing (no matching file).

## Capability Or Endpoint: Offline Behavior

**Current Flutter Expectation** (Confirmed, `workin_mobile`): There is no
offline data persistence, write queue, or background sync anywhere in the
client. Every repository call checks connectivity first
(`internet_connection_checker`'s `hasConnection`); if offline, the call
fails immediately with a local error — it is never queued for retry when
connectivity returns. No local database package (`hive`, `sqflite`, or
similar) is a dependency of either app. The only "local data" concept in
the repository layer is an optional pre-supplied `localData` parameter
some callers pass in for already-cached display data — not a general
offline-write capability.

**Compatibility Risk**: Low compatibility risk (nothing to preserve —
there's no offline behavior to break), but a real scoping input for
migration: if a genuinely offline-capable client is ever wanted (e.g. for
attendance check-in in poor-connectivity environments), that is new
functionality to design, not existing behavior to port.

**Proposed Handling**: Document as confirmed absent; treat any future
offline requirement as a new feature decision, not a migration-parity
concern.

**Evidence**: `workin_mobile/lib/core/network/network_info.dart` (full
file); `workin_mobile/lib/data/repository/repository.dart` lines ~78–105
(`_repositoryImpl`, the shared request-wrapping logic every repository
method goes through).

## Capability Or Endpoint: Environment Configuration

**Current Flutter Expectation** (Confirmed, both apps): There is no
dev/staging/prod environment-switching mechanism (no Flutter flavor
configuration, no `.env` file, no build-time environment variable
found anywhere in either client). `ApiConstants.baseUrl` is a single
hardcoded literal, `https://workin.company/apis/api/`, identical in both
`workin_mobile` and `workin_desktop`.

**Compatibility Risk**: Low direct compatibility risk, but a real
migration-testing constraint: there is no existing client-side mechanism
to point either app at a staging/new backend for cutover testing without
modifying and rebuilding the app from source.

**Proposed Handling**: If staged rollout/cutover testing against a new
Java backend is wanted, environment configuration needs to be added to
the client as part of migration work — it does not exist today to reuse.

**Evidence**: Full-tree search of both clients for flavor/environment/`.env`
file patterns — zero matches; `api_constants.dart`'s single hardcoded
`baseUrl` in both apps.

## Capability Or Endpoint: Firebase Services In Actual Use

**Current Flutter Expectation** (Confirmed, both apps): Only
`firebase_core` and `firebase_messaging` are dependencies of either app —
**no Firestore, Firebase Auth, Firebase Analytics, Crashlytics, or
Firebase Storage.** Firebase's role in this system is narrowly push
notification delivery (FCM) plus local notification display
(`awesome_notifications`, a separate, non-Firebase package). This is
consistent with `hr-legacy`'s own `push_tokens` table and
`profile/register_push_token` endpoint (desktop only — not called by the
mobile client's `api_constants.dart`, worth a follow-up check on how
mobile registers its push token, since it clearly has `firebase_messaging`
as a dependency).

**Compatibility Risk**: Low — a narrow, well-understood integration
surface to replicate (FCM token registration and receipt), not a broad
Firebase-platform dependency.

**Proposed Handling**: Preserve FCM as the push-delivery mechanism; no
need to plan for migrating away from other Firebase services since none
are in use.

**Evidence**: `workin_mobile/pubspec.yaml` and `workin_desktop/pubspec.yaml`
(full dependency lists, diffed against each other).

## Capability Or Endpoint: Hardcoded URLs And Forced-Update Assumptions

**Current Flutter Expectation** (Confirmed, `workin_desktop`): Beyond the
API `baseUrl`, `constants_manager.dart` hardcodes several other URLs
directly in Dart source: Play Store/App Store listing links, the
marketing website (`https://workin.company`), a download-landing page,
and **direct binary download links** for the desktop installers
(`https://workin.company/downloads/WorkIn-Windows.exe`,
`.../WorkIn-Mac.dmg`) used by a mandatory-update screen. `ApiConstants`
also defines per-platform minimum-build-number and
under-maintenance keys (`minAndroidBuildNumberKey`, `minIosBuildNumberKey`,
`minMacBuildNumberKey`, `minWindowsBuildNumberKey`,
`androidAppUnderMaintenanceKey`, etc.) — confirming the app has a
remote-config-driven forced-update/maintenance-mode mechanism, almost
certainly served via `configs/get`.

**Compatibility Risk**: Medium — the desktop app's forced-update flow
depends on the backend continuing to serve these version-gate fields
through whatever endpoint replaces `configs/get`, and the two direct
`.exe`/`.dmg` download URLs are a real coupling to the current hosting
setup that a migration needs to either preserve or explicitly redirect.

**Proposed Handling**: Document this remote version-gating capability as
a real feature to preserve, not just static marketing links — losing it
silently would remove the mechanism currently used to force desktop users
onto compatible app versions during a coordinated backend cutover, which
is exactly the kind of control migration cutover planning benefits from
keeping.

**Evidence**: `workin_desktop/lib/core/resources/constants_manager.dart`
lines 1–20 (URLs); `workin_desktop/lib/core/network/api_constants.dart`
(the `min*BuildNumberKey`/`*UnderMaintenanceKey` response-field constants).

## Desktop/Mobile Divergence (Architecture-Level Finding, Not Just A Contract Detail)

**Current Flutter Expectation** (Confirmed): `workin_desktop` is **not** a
platform variant of the mobile app — it is a full company-admin/HR
management client, authenticating via `auth/login_company`/`auth/login_desktop`
(not `login_employee`), and its `api_constants.dart` declares endpoints
for nearly the entire `hr-legacy` admin surface: employees, branches,
departments, shifts, job titles, attendance administration (including
`delete_range`), payroll batches (including `calculate`/`finalize`/`reopen`),
payslips (full CRUD), penalties, advances (including `approve`/`reject`/`pay`
— the exact endpoints found to have the cross-tenant IDOR in
`workin-hr/hr-legacy#5`), workforce planning, company settings, HR
employee/permission management, and company account
management/deletion (`company/update`, `profile/delete_account`). The
mobile app's endpoint surface, by contrast, is entirely employee
self-service: read-only or own-record-only access to attendance,
payslips, penalties, leave balances, requests, and documents.

**Compatibility Risk**: High-level architecture risk, not a single
endpoint's contract: this confirms **three real frontends** exist against
the same `hr-legacy` backend today — the PHP session-based dashboard,
this native desktop app, and the mobile app — not two as previously
documented in `docs/legacy/existing-php-module-inventory.md`. Whether the
desktop app is replacing the PHP dashboard, coexisting with it
permanently, or something in between was not determined in this pass and
is a real open question with direct implications for ADR-0002 (does the
new system need to serve a native desktop client and a web admin panel,
or just one).

**Proposed Handling**: Document as a corrected system-shape fact (see the
`existing-php-module-inventory.md` update in this same commit) and
surface the "does the desktop app replace the dashboard" question as a
new open item for human product/architecture decision — do not assume
either answer.

**Evidence**: `workin_desktop/lib/core/network/api_constants.dart` (full
file, 674 lines) versus `workin_mobile/lib/core/network/api_constants.dart`
(full file, 326 lines) — diffed directly, not sampled.

## Evidence

All entries above are drawn from direct reads of `workin_mobile` and
`workin_desktop` (local-only, git-ignored, never committed — see
`docs/security/pre-migration-flutter-credential-inventory.md`), commit
state as checked out locally on 2026-08-04, cross-referenced against
`workin-hr/hr-legacy` commit `83c326e40f68dd0d560595a6c4e465eb681f2ce8`
and the existing Discovery documents in this repository.

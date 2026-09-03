# Mobile client runtime verification

The real Android application, built from the pinned client submodule with no
source change, installed on an emulator and driven through its own UI against
the local Java backend on its own hardcoded URL.

This is the runtime layer. The contract layer, which executes nothing, is
`spike/client-contract/MOBILE-CONTRACT-REPORT.md`.

## What was executed

`flutter-integration/workin_mobile` at `3d20855c`, byte-identical to the pinned
submodule (`diff -rq` over `lib/` is empty). `flutter build apk --debug`,
installed with `adb install`, launched with `am start`. Base URL untouched:
`https://workin.company/apis/api/`.

Two build inputs had to be supplied locally, neither of which is client source:

| input | why | what was done |
|---|---|---|
| JDK | Gradle 8.14 rejects Java 25 | pointed Flutter at JDK 21 (`flutter config --jdk-dir`); upgrading the project's Gradle would have edited client source |
| `android/key.properties` | gitignored, absent, and read unconditionally so even a debug build NPEs without it | generated a throwaway keystore in the work copy only |

## How the device reaches Java without touching the client

| layer | mechanism |
|---|---|
| name | `127.0.0.1 workin.company` appended to the emulator's `/system/etc/hosts` |
| trust | the local test CA installed as `/system/etc/security/cacerts/e6559632.0` (a system CA -- API 36 does not trust user CAs) |
| port | `adb reverse tcp:443 tcp:8443`, so the device's `:443` forwards over adb to the host's proxy |
| TLS | `tls-proxy.py` on `127.0.0.1:8443`, certificate for `workin.company`, forwarding plaintext to Java on `127.0.0.1:18081` |

`-writable-system` plus `adb root`/`adb remount` (and one reboot, which the
first remount requires) make the system partition writable. All of it is
emulator-local and disposable with the AVD.

## Network containment

Proven by removing the path and watching the app fail, not by inspection:

| state | app behaviour |
|---|---|
| proxy up, reverse set | `auth/login_employee` -> 200 |
| proxy stopped | `HandshakeException: Connection terminated during handshake` |
| reverse also removed | `SocketException: Connection refused ... address = workin.company` |
| both restored | `auth/login_employee` -> 200 again |

The refusal is the point: `workin.company` resolves to `127.0.0.1` **on the
device**, so when the local listener is gone there is nothing else for the app
to reach. It never falls back to a real host. No production traffic occurred.

## Flows verified through the UI

| flow | request the app produced | result |
|---|---|---|
| startup | app launches, notification permission prompt | login screen renders |
| login | `POST auth/login_employee` | 200, token stored, home renders with the employee's name |
| navigation | the four bottom tabs | home / requests / notifications / account all render |
| attendance read | `attendance/employee_monthly_attendance`, `attendance/stats` | 200; month summary renders |
| check-in | `POST attendance/check_in {latitude, longitude, method: app}` | 200, `attendance_id` 999024; row written with the injected coordinates |
| check-out | `POST attendance/check_out` | 200, `duration_minutes: 3`; `check_out` written |
| requests read | `requests/list`, `request_types/list` | 200; cards render with type, dates, amount and status chip |
| request create | `POST requests/create` | 201, id 999015, row written, list refreshes with it |
| photo upload | `POST_FORM_DATA employees/upload_photo?id=999005` via the real Android photo picker | 200, file written, byte-identical to the source |
| payslips | `payslips/list` | 200 `[]`, empty state renders |
| notifications | `notifications/list`, `notifications/unread_count` | 200 `[]`, empty state renders |
| logout | `POST profile/logout` | 200; `is_active` 1 -> 0, `token_version` bumped, `employee_left_company` notification written, app returns to a cleared login screen |
| session invalidation | a reseed bumped `token_version` mid-session | 401 `تم تسجيل الدخول من جهاز آخر`, client cleared the token and forced logout |

GPS was injected with `cmd location providers set-test-provider-location`;
`adb emu geo fix` reports `OK` but never lands (`last location=null`), which is
why the first check-in attempts failed.

## Findings

### `time/now` is a 404 on both stacks

The app calls `GET time/now` five times per session and gets
`404 الوحدة 'time' غير موجودة`. **PHP returns the identical 404.**
`apis/api/time/now.php` exists on disk, but `time` is absent from
`ApiModule::allowedList()` in `apis/config/http_api.php`, and the legacy router
resolves the first path segment against that list *before* it looks for an
action file. Java ports the list literally, so the two agree byte for byte.

Classification: **existing client defect against legacy behaviour, preserved
correctly by the port.** The endpoint has never been reachable in PHP either.
The client tolerates it -- no crash, no visible degradation.

### `requests/create` refuses a `company_admin` on both stacks

403 `غير مسموح — صلاحية غير كافية`, identical on PHP and Java, because
`requests/create.php` is `requireAuth([UserRoleEnum::EMPLOYEE])`. Classification:
**matching behaviour**; the success path was then verified with a plain
`employee`, which is why the seed now carries one.

### `profile/logout` deactivates the account

Not a defect: `logout.php` sets `is_active = 0` and sends
`notification_employee_left_company_to_company`. Java does exactly the same,
and the confirmation dialog warns about it accurately. Recorded because the
endpoint's name does not suggest it.

### The photo upload confirms D-154 on mobile too

The picked file is a JPEG named `.png`. Java stored it as `.jpg` -- the
content-sniffed extension of **D-154**, the accepted divergence already recorded
for `company/upload_logo` on desktop. Same behaviour, second client.

### A client-side navigation assertion

`routes.dart: Failed assertion: 'scope != null'`, four times, each while a
dialog was being dismissed at the same moment another route was popping.
Classification: **existing client defect**, but triggered by scripted input
faster than a person taps; it is not established that a human hits it. No API
call is involved and nothing was lost.

### The error dialog shows raw exception text

With the backend unreachable the app rendered
`ClientException with SocketException: Connection refused ... uri=https://...`
verbatim in a user-facing dialog. Cosmetic, client-side, no backend involvement.

### The harness employee fixtures could not drive a client at all

`+201999000002` is rejected by the client's own validator before any request is
sent: the clients validate the local part against the `phone_countries` row for
the dial code, and Egypt there is 11 digits starting `010/011/012/015`.
`seed-two.sh` now also seeds 999004 (`company_admin`) and 999005 (`employee`)
with client-valid numbers, stored exactly as the client sends them -- no dial
code and no leading zero, because the phone field strips it and
`login_employee.php` matches the trimmed value with no normalisation.

## Coverage

| | count |
|---|---|
| Distinct endpoints exercised at runtime | 16 |
| Responses observed | 45 × 200, 1 × 201, 3 × 401, 1 × 403, 5 × 404 |
| Java parity defects found | 0 |
| Accepted divergences confirmed | 1 (D-154) |
| Behaviours matching PHP that look like bugs | 3 (`time/now` 404, `requests/create` 403, `profile/logout` deactivation) |

## Device-dependent gaps

Stated rather than manufactured:

- **FCM push delivery.** Firebase initialises in-process, but delivering a push
  needs a real Firebase project and outbound network, which containment forbids.
  Not exercised. (`register_push_token` is separately unfixable -- R-013.)
- **Camera capture.** `التقاط صورة` was not exercised; the gallery path was, and
  it is the same upload code past the picker.
- **Biometrics.** The client declares no biometric dependency, so there is
  nothing to exercise.
- **Real-device GPS.** Location was injected through the test provider. The
  geofence decision is server-side and was exercised with real coordinates.

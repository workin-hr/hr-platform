# Mobile client contract conformance

**This is a contract layer, not a runtime one.** It proves what the mobile
client's own parsers would do with each stack's bytes. It verifies **nothing**
about rendering, navigation, the file picker, downloads to disk, OS
integration, or any other runtime UI behaviour. The real application was not
executed -- see "Why runtime is zero" below.

## Coverage, with the denominators kept apart

| | count |
|---|---|
| API endpoint constants declared by the client | 39 |
| Referenced from client source (reachable) | 39 |
| Distinct paths the data source actually calls | 39 |
| Contracts statically derived from client source | 39 |
| Client parsers extracted | 58 |
| **Contracts replayed against PHP and Java** | **32** |
| **Contracts replayed by THIS layer** | **32** |

This layer replays contracts; it executes nothing. The runtime verdict for
this client is a separate measurement with its own evidence in
`spike/client-runtime` — see the runtime status line below.

The 39 declared constants are **not** the denominator.
Every one of them is referenced from client source.

## Verdicts

| verdict | count |
|---|---|
| compatible | 31 |
| refusal (not coverage) | 1 |

## endpoint -> parser -> PHP -> Java -> client verdict

| endpoint | method | client parser | PHP | Java | verdict |
|---|---|---|---|---|---|
| `administrative_decisions/list` | GET | `AdministrativeDecisionsResponse` | 200 parses | 200 parses | compatible |
| `app_content/one` | GET | `AppContentResponse` | 200 parses | 200 parses | compatible |
| `assets/list` | GET | `AssetsResponse` | 200 parses | 200 parses | compatible |
| `attendance/check_in` | POST | `CheckInOutResponse` | 200 parses | 200 parses | compatible |
| `attendance/check_out` | POST | `CheckInOutResponse` | 200 parses | 200 parses | compatible |
| `attendance/employee_monthly_attendance` | GET | `AttendanceRecordsResponse` | 200 parses | 200 parses | compatible |
| `attendance/stats` | GET | `AttendanceStatsResponse` | 200 parses | 200 parses | compatible |
| `auth/forgot_password` | POST | `ForgetPasswordResponse` | 200 parses | 200 parses | compatible |
| `auth/lookup_company` | POST | `CompanyLookupResponse` | 200 parses | 200 parses | compatible |
| `auth/resend_otp` | POST | `ResendOtpResponse` | 200 parses | 200 parses | compatible |
| `banners/list` | GET | `BannersResponse` | 200 parses | 200 parses | compatible |
| `company_official_holidays/list` | GET | `OfficialHolidaysResponse` | 200 parses | 200 parses | compatible |
| `complaints/create` | POST | `BaseResponse` | 200 parses | 200 parses | compatible |
| `configs/get` | GET | `ConfigsResponse` | 200 parses | 200 parses | compatible |
| `employee_docs/list` | GET | `EmployeeDocumentsResponse` | 200 parses | 200 parses | compatible |
| `faqs/list` | GET | `FaqsResponse` | 200 parses | 200 parses | compatible |
| `leave_balances/list` | GET | `LeaveBalancesResponse` | 200 parses | 200 parses | compatible |
| `notifications/delete` | DELETE | `BaseResponse` | 200 parses | 200 parses | compatible |
| `notifications/list` | GET | `NotificationsResponse` | 200 parses | 200 parses | compatible |
| `notifications/mark_read` | PUT | `BaseResponse` | 200 parses | 200 parses | compatible |
| `notifications/unread_count` | GET | `GetUnreadNotificationsCountResponse` | 200 parses | 200 parses | compatible |
| `payslips/list` | GET | `PayslipsResponse` | 200 parses | 200 parses | compatible |
| `payslips/one` | GET | `PayslipResponse` | 200 parses | 200 parses | compatible |
| `penalties/list` | GET | `PenaltiesResponse` | 200 parses | 200 parses | compatible |
| `phone_countries/list` | GET | `PhoneCountriesResponse` | 200 parses | 200 parses | compatible |
| `profile/change_password` | POST | `BaseResponse` | 200 parses | 200 parses | compatible |
| `profile/employee` | GET | `EmployeeResponse` | 200 parses | 200 parses | compatible |
| `request_types/list` | GET | `RequestTypesResponse` | 200 parses | 200 parses | compatible |
| `requests/create` | POST | `BaseResponse` | 201 parses | 201 parses | compatible |
| `requests/list` | GET | `RequestsResponse` | 200 parses | 200 parses | compatible |
| `schedules/employee_monthly_schedule` | GET | `SchedulesResponse` | 200 parses | 200 parses | compatible |
| `time/now` | GET | `ServerTimeResponse` | 404 ServerException | 404 ServerException | refusal (not coverage) |

## Findings

### 1. No Java parity defect was found by this layer

Across every replayed contract the client verdict is the same on both stacks:
same status, same parse outcome, and the same set of fields that end up blank
or defaulted. Nothing here asks for a Java change.

### 2. `time/now` does not exist -- a client defect, not a parity defect

The mobile client calls `time/now`. There is no `time` module in the frozen
PHP tree, and both stacks answer the same 404:

```text
php  404 Module 'time' not found
java 404 Module 'time' not found
```

Pre-existing, identical on both, and not a migration decision.

### 3. `attendance/check_in` -- the enum the client sends, and the one it does not

`AttendanceMethodEnum` is `{app, excel, qr}` and `check_in_usecase` hard-codes
`app`. All three behave identically on both stacks, storing the same value:

```text
method=app    php 200 stored app     java 200 stored app
method=qr     php 200 stored qr      java 200 stored qr
method=excel  php 200 stored excel   java 200 stored excel
method=gps    php 200 stored ""      java 500
```

The last row is **outside the contract** -- no client sends it -- and is
recorded rather than raised: PHP silently writes an empty method where Java
refuses. It is a legacy data-integrity quirk (MySQL coercing an invalid ENUM)
and a Java error-handling one (500 where 400 would be right), reachable only
by a caller that is not either of these clients.

This row is also why the case sends `app`: an earlier run of this check used
`gps`, reported "check_in is broken in Java", and was wrong -- the value came
from the harness, not from the client.

## Runtime status for this client

**See `spike/client-runtime`** for the mobile runtime verdict and its
remaining device-dependent gaps. This layer does not execute the app.

The clients are pinned read-only submodules and were **not** modified for
either layer.

## What this does not cover

- rendering, widget state, navigation between screens
- the file picker, downloads written to disk, OS integration, auto-update
- endpoints whose request shape needs state this harness does not seed
- anything about the desktop client, which is a separate pass

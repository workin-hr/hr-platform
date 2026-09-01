# Mutating endpoints NOT covered by sweep-mutations.sh

Generated from the inventory, not hand-maintained: regenerate with

```sh
grep -vE '/(list|one|get|stats|export|template_excel|options|preview|now|fiscal_period)$' \
  client-endpoints.txt | sort > /tmp/mutating.txt
grep -oP 'run_case "[^"]+"\s+\w+\s+"\K[^"?]+' sweep-mutations.sh | sort -u > /tmp/covered.txt
comm -23 /tmp/mutating.txt /tmp/covered.txt
```

**45 of 127** mutating endpoints are uncovered. They are listed here
because a sweep that silently omits an endpoint is indistinguishable from one
that covers it — which is the same failure mode as counting two matching
error responses as parity.

An endpoint counts as covered only when both stacks receive a **valid**
request, the response and the affected rows are compared, and the fixture is
reset between them. Sending a request that the endpoint rejects identically on
both sides is not coverage.

## `attendance` — multipart spreadsheet upload, and two GETs

analyze_excel and import_excel take a file part; set_employee_attendance_method is a 501 stub in PHP (it has no file); employee_monthly_attendance and overall_report are GETs and belong to the authenticated read sweep.

- `attendance/analyze_excel`
- `attendance/employee_monthly_attendance`
- `attendance/import_excel`
- `attendance/overall_report`
- `attendance/set_employee_attendance_method`

## `attendance_exception_types` — needs a second exception type

create is covered; update and delete need a row create must make first, and cases cannot chain.

- `attendance_exception_types/delete`
- `attendance_exception_types/update`

## `auth` — OTP and registration flows

Each needs a live OTP value or creates a company, and two routes send WhatsApp/SMS. The harness deliberately holds placeholder integration tokens so nothing can reach a real person, which also means the send path cannot be exercised end to end. login_employee is exercised implicitly by every case in the sweep, which mints a token from BOTH stacks before each request.

- `auth/complete_company_registration`
- `auth/forgot_password`
- `auth/get_company_registration_options`
- `auth/join_company`
- `auth/login_company`
- `auth/login_desktop`
- `auth/login_employee`
- `auth/lookup_company`
- `auth/register_company`
- `auth/resend_otp`
- `auth/reset_password`
- `auth/verify_otp`

## `company` — multipart upload

upload_logo and upload_commercial_reg take a file part.

- `company/upload_commercial_reg`
- `company/upload_logo`

## `company_join_requests` — needs a pending join request

the snapshot has none for the seeded company, and creating one needs the join flow, which is in the uncovered auth group.

- `company_join_requests/accept`
- `company_join_requests/reject`

## `company_settings` — create/delete of a definition

the seeded company has its settings already created; create and delete need a definition the snapshot does not carry.

- `company_settings/create`
- `company_settings/delete`

## `employee_docs` — multipart upload

upload takes a file part; update and delete act on a row that upload must create first, and cases cannot chain because each reseeds.

- `employee_docs/delete`
- `employee_docs/update`
- `employee_docs/upload`

## `employees` — multipart and bulk import

upload_photo, analyze_excel and import_bulk take a file part; delete_preview is a GET.

- `employees/analyze_excel`
- `employees/delete_preview`
- `employees/import_bulk`
- `employees/upload_photo`

## `hr_employees` — creates a second admin identity

create makes an HR user and update_permissions rewrites hr_permissions, which the sweep itself depends on for every case.

- `hr_employees/create`
- `hr_employees/update_permissions`

## `leave_balances` — multipart spreadsheet

analyze_excel and import_bulk take a file part.

- `leave_balances/analyze_excel`
- `leave_balances/import_bulk`

## `profile` — multipart, OTP, and destructive account flows

upload_photo takes a file part; request_phone_change/confirm_phone_change need an OTP; delete_account removes the actor the sweep authenticates as, and delete_account_preview/company/employee are GETs.

- `profile/company`
- `profile/confirm_phone_change`
- `profile/delete_account`
- `profile/delete_account_preview`
- `profile/request_phone_change`

## `schedules` — a GET

employee_monthly_schedule is a read.

- `schedules/employee_monthly_schedule`

## `workforce_planning` — create/update/delete of a second row, and a GET

save_target and create are covered; the rest need a distinct fixture row, and summary is a GET.

- `workforce_planning/delete`
- `workforce_planning/summary`
- `workforce_planning/update`

## What would close the largest share

**Multipart.** Eleven of the remaining endpoints take a file part. The sweep
sends JSON only, so covering them needs a multipart request builder and a
fixture spreadsheet or image committed beside it. That is one mechanism for
eleven endpoints and is the highest-value next step.

**Chaining.** Several update/delete pairs need a row their own create makes,
and cases deliberately cannot chain because each reseeds. The fix is a seeded
fixture per pair, as already done for the penalty, payroll batch, payslip,
notification, request and administrative decision.

**OTP.** The auth group needs a way to read the OTP the server generated.
Reading it from the database is the obvious route and is what a test would do;
it should be an explicit decision, not a quiet one, because it weakens what
the flow proves.

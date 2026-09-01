# Mutation coverage: what is exercised, and what is not

**Regenerate with `./coverage-report.sh` after a full `./sweep-mutations.sh` run.**
Do not hand-edit the numbers.

Coverage requires **evidence from a run**, not a declaration. An endpoint counts
as covered only when the sweep executed a case for it, that case declared a
**2xx**, and the run recorded it as `ok` or as an explicitly accepted
divergence. A declared 2xx alone says only what the case intends: an
unreachable case, a Java 500, a response or row mismatch, or a 2xx that mutated
nothing would all have been regenerated here as covered while the sweep
reported failure.

`mutating` comes from the frozen PHP's own method guard, not from the filename.

| | count |
|---|---|
| mutating endpoints (PHP guards a non-GET method) | **116** |
| covered by a success-path case, verified by a run | **74** |
| exercised only by a refusal — *not counted* | 6 |
| no case at all | 36 |
| reads (GET or no method guard), excluded | 73 |

A request the endpoint rejects identically on both sides is not coverage.

## Exercised only through a refusal

These have a case, but only a rejecting one, so no successful mutation has been
compared. Each needs a fixture its success path can act on.

- `attendance/check_out  (declared [400])`
- `branches/delete  (declared [409])`
- `employees/delete  (declared [404, 409])`
- `profile/register_push_token  (declared [500])`
- `request_types/delete  (declared [409])`
- `requests/create  (declared [403])`

## No case at all

- `attendance/analyze_excel`
- `attendance/import_excel`
- `attendance_exception_types/delete`
- `attendance_exception_types/update`
- `auth/complete_company_registration`
- `auth/forgot_password`
- `auth/join_company`
- `auth/login_company`
- `auth/login_desktop`
- `auth/login_employee`
- `auth/lookup_company`
- `auth/register_company`
- `auth/resend_otp`
- `auth/reset_password`
- `auth/verify_otp`
- `company/upload_commercial_reg`
- `company/upload_logo`
- `company_join_requests/accept`
- `company_join_requests/reject`
- `company_settings/create`
- `company_settings/delete`
- `employee_docs/delete`
- `employee_docs/update`
- `employee_docs/upload`
- `employees/analyze_excel`
- `employees/import_bulk`
- `employees/upload_photo`
- `hr_employees/create`
- `hr_employees/update_permissions`
- `leave_balances/analyze_excel`
- `leave_balances/import_bulk`
- `profile/confirm_phone_change`
- `profile/delete_account`
- `profile/request_phone_change`
- `workforce_planning/delete`
- `workforce_planning/update`

## What closes the largest share

**Multipart — eleven endpoints, one mechanism.** `analyze_excel`,
`import_excel`, `import_bulk`, `upload_photo`, `upload_logo`,
`upload_commercial_reg` and `employee_docs/upload` all take a file part. The
sweep sends JSON only, so covering them needs a multipart request builder and
committed fixture files. Highest value per unit of harness work.

**Seeded fixtures.** The refusal-only list, plus several update/delete pairs,
need a row their own create would make — and cases deliberately cannot chain,
because each reseeds. The pattern already exists in `seed-two.sh`: an open
penalty, a draft batch, a payslip inside it, an inbox notification, an
EMPLOYEE-role actor with a pending request, a request type with both approval
side-effect flags on, and an administrative decision.

**OTP.** The auth group needs the OTP the server generated. Reading it from the
database is what a test would do, but it weakens what the flow proves, so it
should be an explicit decision rather than a quiet one.

**`attendance/set_employee_attendance_method`** has no PHP file — legacy answers
501 from the router. Listed for completeness, not as a gap the sweep can close.

# Mutation coverage: what is exercised, and what is not

**Regenerate with `./coverage-report.sh` after a full `./sweep-mutations.sh` run.**
Do not hand-edit the numbers.

Coverage requires **evidence from a run**. An endpoint counts as covered only
when the sweep executed a case for it, that case declared a **2xx**, and the run
recorded it as `ok`. An `ACCEPTED` verdict does **not** count: it documents a
deliberate divergence, which is the opposite of evidence that the two stacks
agree. `mutating` comes from the frozen PHP's own method guard, not the filename.

| | count |
|---|---|
| mutating endpoints (PHP guards a non-GET method) | **116** |
| covered by a success-path case, verified by a run | **81** |
| exercised only by a refusal — *not counted* | 7 |
| no case at all | 28 |
| reads (GET or no method guard), excluded | 73 |

A request the endpoint rejects identically on both sides is not coverage.

## Exercised only through a refusal

These have a case, but only a rejecting one. Each needs a fixture its success
path can act on.

- `attendance/check_out` — needs an OPEN check-in for the actor on the same day; the sweep reseeds per case, so it must be seeded rather than produced by the check_in case.
- `branches/delete` — 409 while the branch has employees. Needs a branch with none.
- `employees/analyze_excel` — needs a success-path fixture.
- `employees/delete` — 409 while payroll/attendance rows reference the employee. Needs a throwaway employee with no references.
- `profile/register_push_token` — **genuinely unreachable** — R-013: `register_push_token.php` INSERTs a `company_id` column `push_tokens` does not have, so it 500s for every caller and always has. The port reproduces the failure (D-058). There is no success path to cover.
- `request_types/delete` — 409 while requests reference the type. Needs an unused type.
- `requests/create` — 403 for the company-admin actor; the endpoint is for EMPLOYEE. The seeded employee actor (999003) can reach it.

## No case at all

Grouped by what each actually needs, so the list is a work item rather than an
inventory.


### a second exception type

`create` is covered; `update` and `delete` need a row that `create` would make, and cases deliberately cannot chain because each reseeds.

- `attendance_exception_types/delete`
- `attendance_exception_types/update`

### OTP and company registration

Needs the OTP the server generated. Reading it from the database is what a test would do, and it weakens what the flow proves, so it should be an explicit decision rather than a quiet one. Two routes also send WhatsApp/SMS; the harness holds placeholder tokens so nothing reaches a real person, which also means the send path cannot be exercised end to end. `auth/login_employee` is exercised implicitly by every case here, which mints a token from BOTH stacks before each request.

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

### a pending join request

The snapshot has none for the seeded companies, and creating one goes through the join flow, which is in the uncovered auth group.

- `company_join_requests/accept`
- `company_join_requests/reject`

### a setting definition the snapshot lacks

The seeded company's settings already exist, so `create` and `delete` need a definition row that is not in the snapshot.

- `company_settings/create`
- `company_settings/delete`

### not yet classified

—

- `employee_docs/delete`
- `employee_docs/update`

### bulk import from analysis output

`import_bulk` takes `rows` in the JSON body -- the output of `analyze_excel`, not a file -- so it needs a chained fixture: the analysis result captured and replayed as a body. Not multipart, despite sitting beside the analyzers.

- `employees/import_bulk`

### a second admin identity

`create` makes an HR user and `update_permissions` rewrites `hr_permissions`, which every case in this file depends on for its own actor.

- `hr_employees/create`
- `hr_employees/update_permissions`
- `leave_balances/import_bulk`

### OTP and destructive account flows

`request_phone_change`/`confirm_phone_change` need an OTP; `delete_account` removes the actor the sweep authenticates as, so it needs its own throwaway employee fixture.

- `profile/confirm_phone_change`
- `profile/delete_account`
- `profile/request_phone_change`

### a second planning row

`save_target` and `create` are covered; the rest need a distinct fixture row.

- `workforce_planning/delete`
- `workforce_planning/update`

## Genuinely blocked, not merely uncovered

- `profile/register_push_token` — no success path exists in frozen PHP (**R-013**).
- `attendance/set_employee_attendance_method` — no PHP file; legacy answers 501.
- `employees/analyze_excel` — has a case, and it passes as an **accepted divergence**
  (**R-038**): PHP answers 200 with an empty body, Java returns the analysis. Not
  counted as covered, because comparing an empty body against real output is not
  parity.

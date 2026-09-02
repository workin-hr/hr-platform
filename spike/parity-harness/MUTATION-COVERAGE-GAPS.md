# Mutation coverage: what is exercised, and what is not

**Regenerate with `./coverage-report.sh` after a full `./sweep-mutations.sh` run.**
Do not hand-edit the numbers.

Coverage requires **evidence from a run**. An endpoint counts as covered only
when the sweep executed a case for it, that case declared a **2xx**, and the run
recorded it as `ok`. An `ACCEPTED` verdict does **not** count: it documents a
deliberate divergence, which is the opposite of evidence that the stacks agree.
`mutating` comes from the frozen PHP's own method guard, not the filename.

| | count |
|---|---|
| mutating endpoints (PHP guards a non-GET method) | **116** |
| covered by a success-path case, verified by a run | **91** |
| exercised only by a refusal — *not counted* | 2 |
| no case at all | 23 |
| reads (GET or no method guard), excluded | 73 |

A request the endpoint rejects identically on both sides is not coverage.

## Genuinely blocked — no success path exists

- `profile/register_push_token` — **R-013**: `register_push_token.php` INSERTs a
  `company_id` column `push_tokens` does not have, so it 500s for every caller
  and always has. The port reproduces the failure (**D-058**). There is nothing
  to cover.
- `attendance/set_employee_attendance_method` — no PHP file; legacy answers 501
  from the router.
- `employees/analyze_excel` — has a case that passes as an **accepted divergence**
  (**R-038**): PHP answers 200 with an empty body, Java returns the analysis.
  Not counted, because comparing an empty body against real output is not parity.

## Exercised only through a refusal

- `employees/analyze_excel  (declared [200])`
- `profile/register_push_token  (declared [500])`

## No case at all

Grouped by what each needs, so the list is a work item rather than an inventory.


### OTP, and flows that create a company

Needs the OTP the server generated; reading it from the database is what a test would do and weakens what the flow proves, so it should be an explicit decision. Two routes send WhatsApp/SMS, and the harness holds placeholder tokens so nothing reaches a real person -- which also means the send path cannot be exercised end to end. `auth/login_employee` IS exercised implicitly by every case here, which mints a token from both stacks before each request.

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

- `employee_docs/update`

### the analyzer's output replayed as a body

Takes `rows` in the JSON body -- the output of `analyze_excel`, not a file. Needs the analysis captured and replayed, which is a chained fixture the per-case reseed forbids; the capture has to happen in the seed or in a generator.

- `employees/import_bulk`

### a second admin identity, carefully

`create` makes an HR user; `update_permissions` rewrites `hr_permissions`, which every case in this file depends on for its own actor. Needs an isolated target so it cannot disable the sweep's own access.

- `hr_employees/create`
- `hr_employees/update_permissions`
- `leave_balances/import_bulk`

### OTP, and flows that remove the actor

`request_phone_change`/`confirm_phone_change` need an OTP. `delete_account` removes the employee the sweep authenticates as, so it needs a throwaway actor of its own.

- `profile/confirm_phone_change`
- `profile/delete_account`
- `profile/request_phone_change`

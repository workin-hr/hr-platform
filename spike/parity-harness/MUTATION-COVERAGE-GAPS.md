# Mutation coverage: what is exercised, and what is not

**Regenerate with `./coverage-report.sh` after a full `./sweep-mutations.sh` run.**
Do not hand-edit the numbers.

Coverage requires **evidence from a run**: the sweep executed a case, that case
declared a **2xx**, and the run recorded it `ok`. An `ACCEPTED` verdict does not
count -- it documents a deliberate divergence, the opposite of evidence that the
stacks agree. `mutating` comes from the frozen PHP's own method guard.

| | count |
|---|---|
| mutating endpoints (PHP guards a non-GET method) | **116** |
| covered by a success-path case, verified by a run | **98** |
| exercised only by a refusal — *not counted* | 4 |
| no case at all | 14 |
| reads (GET or no method guard), excluded | 73 |

## Genuinely blocked — no success path exists

- `profile/register_push_token` — **R-013**: the endpoint INSERTs a `company_id`
  column `push_tokens` does not have, so it 500s for every caller and always
  has. The port reproduces the failure (**D-058**). Nothing to cover.
- `attendance/set_employee_attendance_method` — no PHP file; legacy answers 501.
- `employees/analyze_excel` — passes as an **accepted divergence** (**R-038**):
  PHP answers 200 with an empty body, Java returns the analysis.

## Exercised only through a refusal

- `auth/login_company` — needs a company account whose password the harness knows. The seeded snapshot has company rows but no known credential; seeding one is the remaining work.
- `auth/login_desktop` — same as login_company.

## No case at all

Each line says what the case needs, so the list is a work item.

- `auth/complete_company_registration` — follows register_company; needs that flow first.
- `auth/join_company` — needs a company code and an unregistered phone.
- `auth/register_company` — creates a company, so it needs a teardown the per-case reseed already gives -- but also a unique phone/company code per run, or the second run collides with the first.
- `company_join_requests/accept` — needs a pending join request, which comes from auth/join_company.
- `company_join_requests/reject` — needs a pending join request, which comes from auth/join_company.
- `company_settings/create` — needs a setting definition the seeded company does not already have a value for.
- `company_settings/delete` — same.
- `employees/import_bulk` — takes `rows` in the JSON body -- the OUTPUT of analyze_excel, not a file. Needs the analysis captured and replayed; a chained fixture the per-case reseed forbids, so the capture belongs in the generator.
- `hr_employees/create` — creates an HR identity; safe, but needs a unique phone per run.
- `hr_employees/update_permissions` — rewrites hr_permissions, which every case depends on for its own actor. Needs an isolated target so it cannot disable the sweep.
- `leave_balances/import_bulk` — same shape as employees/import_bulk.
- `profile/confirm_phone_change` — follows request_phone_change.
- `profile/delete_account` — removes the acting employee; needs a throwaway actor whose deletion cannot break later cases.
- `profile/request_phone_change` — needs an OTP for the NEW number, which the OTP runner can now supply -- the remaining work is a second phone fixture.

## How the OTP flows are covered

The OTP is a **per-stack secret**: each stack generates its own and stores it in
its own database, so `run_otp_case` reads each code from the database that stack
wrote it to and substitutes it into that stack's request. Sending one stack's
code to the other would fail for a reason unrelated to parity. This is the same
shape as `mint_token`, which already asks each stack for its own token.

Two things make it work, neither touching frozen PHP:

- `otp_codes.code` holds the code in **plaintext**, so the harness can read it.
- `whatsapp-stub.py` stands in for the send. Without it both stacks answer 503,
  the code is never written, and every OTP case compares two identical
  failures. The stub is also **safer** than the placeholder it replaces, which
  pointed at the real `pro.whats360.live` and attempted an outbound request.

`AppConfig::DEBUG` must be **false**, paired with `display_errors=Off`. With
`DEBUG=true`, `forgot_password` and `resend_otp` return the OTP **in the response
body** and `respond()` appends a stack trace to uncaught exceptions -- neither of
which production does. The harness ran that way until this work, and it made
Java look wrong for behaving like production.

# Mutation coverage: what is exercised, and what is not

**Regenerate with `./coverage-report.sh` after a full `./sweep-mutations.sh` run.**
Do not hand-edit the numbers.

Covered means: the sweep executed a case for this endpoint, **that case declared
a 2xx**, and the run recorded **that case** `ok`. The verdict is bound to the
invocation that declared the success status -- an endpoint with both a success
and a refusal case is not credited when only the refusal passes. An `ACCEPTED`
verdict never counts: it documents a deliberate divergence.

`mutating` comes from the frozen PHP's own method guard, not the filename.

| | count |
|---|---|
| mutating endpoints (PHP guards a non-GET method) | **116** |
| covered by a success-path case, verified by a run | **98** |
| exercised only by a refusal — *not counted* | 4 |
| no case at all | 14 |
| reads (GET or no method guard), excluded | 73 |

## Genuinely blocked — no success path exists

- `profile/register_push_token` — **R-013**: it INSERTs a `company_id` column
  `push_tokens` does not have, so it 500s for every caller and always has. The
  port reproduces the failure (**D-058**).
- `attendance/set_employee_attendance_method` — no PHP file; legacy answers 501.
- `employees/analyze_excel` — **R-038**: PHP answers 200 with an empty body,
  Java returns the analysis. The case passes as an accepted divergence whose
  shape is asserted on both sides, so a Java regression to an empty body fails.

## Exercised only through a refusal

- `auth/login_company` — needs a company account whose password the harness knows. The snapshot has company rows but no known credential.
- `auth/login_desktop` — needs the same as login_company.
- `employees/analyze_excel` — needs nothing -- **blocked**, R-038: PHP answers 200 with an empty body.
- `profile/register_push_token` — needs nothing -- **blocked**, R-013: the endpoint 500s for every caller and always has.

## No case at all

Each line says what it needs, so the list is a work item.

- `auth/complete_company_registration` — needs follows register_company.
- `auth/join_company` — needs a company code and an unregistered phone.
- `auth/register_company` — needs a unique phone and company code per run, so a second run does not collide with the first.
- `company_join_requests/accept` — needs a pending join request, which comes from auth/join_company.
- `company_join_requests/reject` — needs a pending join request, which comes from auth/join_company.
- `company_settings/create` — needs a setting definition the seeded company has no value for yet.
- `company_settings/delete` — needs the same.
- `employees/import_bulk` — needs the analyzer output replayed as a JSON body -- it takes `rows`, not a file. The capture has to happen in the generator, since cases cannot chain.
- `hr_employees/create` — needs a unique phone per run.
- `hr_employees/update_permissions` — needs an isolated target, so rewriting hr_permissions cannot disable the sweep’s own actor.
- `leave_balances/import_bulk` — needs the same shape as employees/import_bulk.
- `profile/confirm_phone_change` — needs follows request_phone_change.
- `profile/delete_account` — needs a throwaway actor whose deletion cannot break later cases.
- `profile/request_phone_change` — needs a second phone fixture; the OTP itself is now available through run_otp_case.

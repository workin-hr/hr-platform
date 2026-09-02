# PHP↔Java Parity Harness

Serves `hr-legacy` (PHP) and `hr-platform` (Java) from **one MariaDB**, so any
response difference can only come from the application.

Full write-up, findings and methodology:
`docs/migration/2026-08-31-php-java-parity-harness.md`

Versioned here under `spike/` so the parity results that document cites are
reproducible. `spike/` is the one top-level directory excluded from the Phase 0
file lock (`scripts/validate_phase0.py`, `SPIKE_DIR_NAME`), which is what makes
a `docker-compose.yml` legal at this path.

## Prerequisites

- `hr-legacy` checked out **beside** `hr-platform` in the same parent directory.
  Override with `WORKSPACE=/path/to/parent`, or `LEGACY=` / `PLATFORM=`
  individually.
- **`hr-legacy` checked out at the pinned oracle revision `d113204`, clean.**
  `seed.sh` verifies both and exits 5 otherwise — every parity number is only
  meaningful against a known revision, and a newer, older or locally modified
  tree gives a different answer with equal confidence.
- The Java jar is **built by `run-java.sh`**, not assumed. `build/libs` is
  git-ignored and survives a checkout, so reusing whatever is there can test an
  earlier commit and attribute the result to the reviewed code. Pass
  `SKIP_BUILD=1` to reuse deliberately.
- Docker with the compose plugin.

## First-time setup

The two PHP config files are git-ignored because they hold a JWT secret and a
dashboard admin password. Create them from the templates beside them:

```sh
cp php/constants.php.example php/constants.php
cp php/dashboard-constants.php.example php/dashboard-constants.php
# then replace every CHANGE_ME_LOCALLY. JWT_SECRET must match what run-java.sh
# passes to the Java process -- generate one locally, e.g.
#   openssl rand -base64 48 > .jwt-secret
```

Never copy a production value into either file.

## Run

```sh
docker compose up -d db php     # MariaDB :13306, PHP :18080
./seed.sh                       # schema + the legacy snapshot + a test employee
./run-java.sh &                 # Java :18081, same DB, same JWT secret
./login.sh php                  # mint a token (see the ordering note in the script)
./sweep.sh                      # reachability: status codes, unauthenticated
./sweep-auth.sh                 # parity: JSON bodies, authenticated

# wider body coverage: 26 endpoints need a parameter, and answer 400 on both
# stacks without one -- which reads as covered while nothing is compared.
./resolve-params.sh             # resolve real ids -> client-endpoints-authed.txt
ENDPOINTS=client-endpoints-authed.txt TOKEN_FILE=.php-token-214 \
  DIFFS=auth-diffs-214.txt ./sweep-auth.sh
```

`resolve-params.sh` reads ids out of the seeded snapshot rather than hardcoding
them, because hardcoded ids stop existing after a reseed and the endpoint then
404s on both stacks -- silent again, in exactly the way the script exists to
prevent. Anything it cannot resolve is written to `params-unresolved.txt` and
reported as a coverage gap rather than dropped.

Two seeded employees, deliberately:

| Employee | Company | For |
|---|---|---|
| `+201999000001` | 244 | volume -- the list endpoints |
| `+201999000002` | 214 | breadth -- 16 of 18 resource types, so the `/one` endpoints resolve |

`seed-two.sh` adds what the snapshot lacks, because a refusal is not coverage:
an open penalty, a draft payroll batch and a payslip inside it, a notification
in the caller's inbox, an EMPLOYEE-role actor with a pending request, a request
type with both approval side-effect flags set, an administrative decision, an
empty branch, an unreferenced employee, an unused request type, an open
check-in, a second exception type, a workforce-planning row, an employee
document and a push token.

Four of them exist for the auth and account endpoints, and each closes an
endpoint that could otherwise only ever answer a refusal:

| Fixture | Closes |
|---|---|
| a known password on company 214 | `auth/login_company`, `auth/login_desktop` as a company, and the company session the profile phone-change pair requires |
| a pending join request (999028) | `company_join_requests/accept` and `/reject` |
| an HR user that is not the actor (999029) | `hr_employees/update_permissions` -- rewriting the actor's own flags would strip the permissions every later case authenticates with |
| a setting definition 214 has no value for (999040) | `company_settings/create`, which answers `already_exists` for all five the snapshot ships |
| a company stopped between the two registration steps (999030) | `auth/complete_company_registration`, which needs `otp_verified=1` and `profile_completed=0` |
| company 214's onboarding notifications *removed* | `auth/login_desktop` as a company, whose `ensureCompanyOnboarding()` inserts only when the type is absent -- with the snapshot's rows present the call was a no-op and the case asserted nothing about it |
| a push token for the EMPLOYEE actor (999003) | `profile/delete_account`, whose contract includes dropping the caller's push tokens |

Three effects on this surface cannot be seen by comparing end state, and each
has its own mechanism rather than a table entry that only looks like an
assertion:

- **A write the same request deletes again.** `company_join_requests/reject`
  sends the employee a notification and then deletes that employee;
  `fk_notification_to_employee` is `ON DELETE CASCADE`, so the row is gone before
  any snapshot -- its existence *and* its contents. `seed-two.sh` installs an
  `AFTER INSERT` trigger on both databases that copies every notification into
  `parity_notification_audit`, so the row survives its own deletion and is
  compared in full: type, title, body, `reference_type` and `reference_id`.
  Counting the inserts would prove only that one happened, and **D-155** is the
  precedent for a notification whose defect was invisible everywhere except its
  own row.
- **A request that leaves the process.** The OTP endpoints store the code only
  after the send succeeds, so comparing the response and `otp_codes` proves the
  send was *attempted* and nothing about it -- a stack building the wrong
  destination or template still stores a code and still answers 200.
  `whatsapp_log_since()` reads `whatsapp-stub.log` by byte offset around each
  stack's request and compares what was sent. The generated code is the one
  thing the two stacks must differ on, so it is reduced to `<OTP>` **inside the
  message parameter only** -- the destination `jid`, also a long digit run, is
  still compared exactly. Applied to every case, so an endpoint that starts or
  stops sending is caught without anyone opting it in.
- **State the harness's own instrumentation destroys.** `mint_token()` logs the
  actor in, and `auth/login_employee` deletes that employee's push tokens -- so
  for `profile/delete_account` the seeded token was already gone before the
  request and the comparison proved nothing. `post_login_fixture()` re-applies it
  to both databases after both tokens are minted. Seeding harder does not help:
  the login sits between the seed and the case.

Each exists because some endpoint's success path needs a row the snapshot does
not provide, and cases deliberately cannot chain -- every case reseeds. That
reseed is also why the registration cases use **fixed** phone numbers rather
than per-run random ones: the previous run's row is gone before the next starts,
and a random identity would only make the case nondeterministic while hiding a
Java that stopped enforcing uniqueness.

The refusal cases are kept alongside their success cases rather than replaced:
"409 while employees remain" and "200 when none do" are both contract, and only
the second counts as coverage.

`make-fixtures.sh` also captures the two `import_bulk` bodies, because those
endpoints take `rows` in a JSON body -- the analyzer's own output, posted back
by the client -- rather than a file. They are captured from **PHP**: a body
captured from the port would let a Java analyzer defect define the input both
stacks are then judged on. What is captured differs per endpoint, because the
importers differ -- `employees` re-parses each row and wants the analyzer's raw
`data`, while `leave_balances` unwraps `payload` and takes the row objects whole.

The current numbers are in `MUTATION-COVERAGE-GAPS.md`, which
`./coverage-report.sh` **writes** from a completed run. Do not edit it by hand:
it had drifted once, claiming 98 covered while the script printed 115, because
the documented step only ever printed to the terminal.

## Mutation sweep

```sh
JAVA_DB=workin_java ./run-java.sh &   # Java on its OWN copy -- asserted, not assumed
./sweep-mutations.sh
```

Each case pins five things, which is what separates a covered endpoint from a
request that was merely sent:

| | pinned by |
|---|---|
| valid request payload | the case's body and `?query` |
| expected status semantics | `EXPECT`, asserted against PHP |
| normalised response parity | `norm()` — shapes, never drops |
| affected rows before/after | `TABLES`, hashed per row by `snapshot()` |
| reset procedure | `seed-two.sh`, re-run before every case |

`EXPECT` exists because comparing PHP against Java cannot catch both stacks
agreeing on the wrong thing. A fixture that stops resolving makes both answer
404, which compares equal and would count as a passing mutation that never
mutated anything.

Values that two *correct* implementations must disagree on — `qr_code` is
`bin2hex(random_bytes(16))` on one side and `SecureRandom` on the other — are
normalised to their shape, so a wrong length, uppercase hex or non-hex still
fails. Shape cannot distinguish a constant, so randomness is asserted where
more than one sample exists (`LegacyBranchEndToEndTest`), not here.

Divergences the repository has decided to keep are declared in
`accepted_mutation_divergence()`, keyed on the endpoint **and** the exact
status pair, and must name the risk or decision that accepted them.

## OTP flows

```sh
./whatsapp-stub.py 18099 &        # stands in for the send; nothing leaves the machine
./sweep-mutations.sh              # run_otp_case reads each stack's own code
```

The OTP is a **per-stack secret**: each stack generates its own and stores it in
its own database, so `run_otp_case` reads each code from the database that stack
wrote it to and substitutes it into that stack's request. Sending one stack's
code to the other would fail for a reason unrelated to parity. It is the same
shape as `mint_token`, which already asks each stack for its own token.

Nothing here touches frozen PHP:

- `otp_codes.code` holds the code in **plaintext**, so the harness can read it.
- `whatsapp-stub.py` returns the success shape both senders require: a
  `200` carrying a
  truthy `success`). Without it both stacks answer 503, the code is never
  written, and every OTP case compares two identical failures. The stub is also
  **safer** than the placeholder it replaces, which pointed at the real
  `pro.whats360.live` and attempted an outbound request with a dummy token.

### `DEBUG` must be false

`php/constants.php` must set `AppConfig::DEBUG = false`, and the compose file
pairs it with `display_errors=Off`. With `DEBUG=true`, `forgot_password` and
`resend_otp` return the OTP **in the response body**, and `respond()` appends a
stack trace to uncaught exceptions. Production does neither. The harness ran that
way until this was traced, and it made Java look wrong for behaving like
production.

## A trap with the bind-mounted config

`php/constants.php` is bind-mounted **as a file**, so the container holds its
inode. Editing it with anything that replaces the file rather than truncating it
-- `sed -i`, most editors' atomic-save -- orphans the mount: the host file
changes and the container keeps reading the old inode, silently. Either write in
place, or `docker restart parity-harness-php-1` afterwards to re-resolve.

## What is deliberately not wired

- **No production credentials.** The harness generates its own JWT secret and
  dashboard admin password. `hr-legacy/apis/config/constants.php` holds the real
  values and is never read or modified — the container gets a harness-local file
  mounted over that path.
- **No outbound integrations.** WhatsApp, SMS and FCM keep placeholder tokens,
  so no OTP or push can reach a real person. Two legacy OTP routes send messages;
  this is why.
- **`.jwt-secret`, `.php-token`, `.java-token`, the sweep output and the two
  real `php/*.php` configs** are git-ignored. The sweep output is excluded
  because it contains real response bodies from the snapshot, not just verdicts.

## Two traps that cost time

**Every login bumps `token_version`.** A token minted before another login is
correctly rejected. Log into the stack you intend to test *last*, or
single-active-session enforcement reads as a compatibility failure.

**Never compare `data[0]` across stacks.** Where ordering differs, positional
comparison invents catastrophic-looking value differences that are pure
misalignment — it made `basic_salary` look like `4615.44` versus `0.00`. Diff
keyed by id, and separate "same number, different JSON type" from "different
value".

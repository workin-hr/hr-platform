# PHP↔Java Parity Harness: How To Run It, And What It Found

Written 2026-08-31 against the owner's request to *"run java and php … compare
data appear in java with php … fix java to appear like php"*.

The harness exists because reading two codebases side by side cannot answer that
question. Serving both from **one database** and diffing the responses can, and
did — within an hour it found a routing defect that would have failed every
Flutter client at cutover.

---

## The headline finding

**Java is mapped on URLs no client uses.**

| URL | Production PHP | Java (before the fix) |
|---|---|---|
| `/apis/api/configs/get` — what clients call | **200** | **401** |
| `/apis/api/configs/get.php` | **500** | 200 |

Both Flutter clients build requests as `https://workin.company/apis/api/` + a
path like `auth/login_employee`. **Not one of `api_constants.dart`'s 266
endpoint constants ends in `.php`.** Every legacy controller in Java, however,
maps the *file* path — `/apis/api/auth/login_employee.php` — because the
endpoint inventory was built from the PHP source tree, where those files are
what you see.

Measured across the **190 distinct endpoint constants the clients declare**.

That denominator is declaration coverage, not call-site coverage: it was
extracted from the clients' endpoint constants, and at least one of them
(`attendance/set_employee_attendance_method`) is declared but never called —
`docs/api/three-frontend-api-usage-matrix.md` records it as a dead reference.
So the figures below measure the surface the clients *can* address, which is the
right denominator for a routing fix, and is **not** the same as the live surface
a user can reach. Where that distinction changes the reading of a result, it is
called out at that result.

| | Endpoints matching PHP's status |
|---|---|
| Client URL form, before the fix | **9 / 190** |
| Same endpoints with `.php` appended | 188 / 190 |
| Client URL form, after the fix | **188 / 190** |

The suffix was the entire difference. Java was a working port answering an
address nobody dials.

### Why the `.php` path fails in PHP too

`apis/.htaccess` rewrites to the router **only when the target does not exist**:

```apache
RewriteCond %{REQUEST_FILENAME} !-f
RewriteRule ^api/(.*)$ api/index.php [L,QSA]
```

A request for a real `.php` file is therefore served directly — and those files
open with `if ($_SERVER[ServerKey::REQUEST_METHOD] …)` assuming
`helpers/functions.php` is already loaded, which only `index.php` does. Hence
the 500. The suffix-less route is not a convenience; it is the contract.

### The fix

`LegacyPhpRouterFilter` ports `apis/api/index.php`'s router: a two-segment route
under `/apis/api/` resolves to the `.php` file that serves it. One rewrite, at
`HIGHEST_PRECEDENCE`, **outside** the Spring Security chain — the permit-list in
`LegacyPhpRoutes` is written in `.php` paths, so authorization has to evaluate
the rewritten path or it would 401 endpoints legacy serves anonymously.

The request is *wrapped*, not forwarded, so the security matcher, the
authorization rules and the dispatcher all observe one consistent path.

---

## Running it

Everything lives in **`spike/parity-harness/`**, versioned in this repository.

It began as a sibling directory outside both repos, on the reasoning that it was
throwaway tooling and that the Phase 0 lock forbids `docker-compose.yml`,
`Dockerfile` and `*.sql` outside `backend/` and `spike/`. That was wrong on the
point that matters: the moment these results are cited as the evidence closing
**R-028**, a reviewer has to be able to run them, read the normalisation logic,
and check that the harness still matches the implementation. Evidence that only
exists on one machine is not evidence. `spike/` is precisely the directory the
lock excludes for this kind of experiment (`scripts/validate_phase0.py`,
`SPIKE_DIR_NAME`), so it is legal there.

The signing secret, the minted tokens, the sweep output (which contains real
response bodies from the snapshot) and the two PHP config files are git-ignored;
`.example` templates for the configs sit beside them. See the harness README for
first-time setup.

```sh
cd spike/parity-harness

# PHP first: seed.sh runs `php -r password_hash(...)` inside the PHP container,
# so seeding fails at that step if only the database is up.
docker compose up -d db php      # MariaDB :13306 and hr-legacy :18080, loopback only
./seed.sh                        # schema, data with FK checks off, extension table
./run-java.sh &                  # Java :18081, same DB, same JWT secret
./sweep.sh                       # every client endpoint, both stacks, diffed
./sweep-auth.sh                  # authenticated bodies, one token, same data

# The MUTATION sweep needs Java on its OWN copy and cannot reconfigure a
# running JVM, so it is a separate launch. Seed synchronously -- backgrounding
# the compound would race the sweep's own reseed against a starting JVM.
./seed-two.sh
JAVA_DB=workin_java ./run-java.sh &
./sweep-mutations.sh             # proves the split with a sentinel; exits 3 if not
```

Three details that matter:

- **One database.** Both stacks read the same MariaDB, so a response difference
  can only come from the application. This is the whole design.
- **One JWT secret**, generated locally and shared by both — so a token minted
  by either is accepted by the other, which is also a live check of R-024's
  cutover precondition.
- **The seed is the real snapshot**: `hr-legacy/mysql_workin.data.sql`, 269
  companies, 2,871 employees, 36,316 attendance rows. Load it with
  `FOREIGN_KEY_CHECKS=0` — the dump is table-ordered and inserts `advances`
  before `employees`.

### What is deliberately *not* wired

- **No production credentials anywhere.** The harness generates its own JWT
  secret and its own dashboard admin password. `hr-legacy/apis/config/constants.php`
  (which holds the real values) is never read or modified — the container gets a
  harness-local file mounted over that path, so nothing on disk changes.
- **No outbound integrations.** WhatsApp, SMS and FCM keep the template's
  placeholder tokens, so no OTP or push can fire at a real person from a test
  run. Two OTP routes send messages in legacy; this is why.

---

## How to compare, in increasing order of strength

`sweep.sh` compares **status codes on unauthenticated requests**. That is a
reachability check, and it is what caught the routing defect — but it is the
weakest of the three levels, and it should not be mistaken for parity.

1. **Reachability** (done). Does the route answer at all, with the same status?
2. **Body equality on public routes** (partly done). `configs/get` returns
   **byte-identical** JSON from both stacks. Extend this to the other public
   routes — they need no token, so there is no excuse not to.
3. **Authenticated body equality** (not done — see below). Mint one token, call
   both stacks as the same employee, diff the JSON. This is the level that
   actually answers *"does the data appear the same"*, and it is where the
   remaining work is.

Level 3 needs a login that works against the seeded data — i.e. a known
plaintext password for a seeded employee. The snapshot carries bcrypt hashes, so
the practical route is to set a known hash for one throwaway employee row **in
the harness database only**, then drive both stacks with the resulting token.
That row must never be created anywhere but the container.

---

## Level 3 results: authenticated body comparison

Run with one throwaway employee (`999001`, company 244 — created in the harness
database only, never anywhere else) logging into both stacks and diffing the
JSON.

**The bidirectional token exchange R-024 asks for, performed live:**

| Direction | Result |
|---|---|
| Java-minted token → PHP | **200** |
| PHP-minted token → Java | **200** |
| Foreign-signed token → both | **401** |

One caveat learned the hard way: each login **bumps `token_version`**, so a
token minted before another login is correctly rejected. Test the direction you
care about *last*, or you will read single-active-session enforcement as a
compatibility failure. This proves the mechanism when the secret matches; it
does not prove production's secrets match.

### Two defects found, both client-affecting

**1. Whole numbers rendered as floats.** PHP casts aggregates to `(float)` and
`json_encode` writes the shortest form — `2604`, not `2604.0`. Jackson writes
`2604.0`. Same value, **different JSON type**: Dart's `json.decode` yields `int`
for one and `double` for the other, so `as int` throws on one and not the other.

Verified against the running PHP:

| PHP value | `json_encode` |
|---|---|
| `0.0` | `0` |
| `2604.0` | `2604` |
| `2604.5` | `2604.5` |

It only shows on **whole** values, which is why it survives review and appears
in production, where counts and sums usually are whole. It accounted for every
one of the 157 differing leaves in `dashboard/stats` and all four differing
endpoints. Fixed by `LegacyPhpNumberJsonConfig`, scoped to `phase1-mysql`.

**2. ~~The wrong tie-break in `payslips/list`.~~ — retracted.** This was
reported as a Java defect and was not one. `list()` already ended on `e.id`; the
line I changed was in `exportRows()`, whose PHP counterpart
(`data_export_helper.php:552`) genuinely ends on `p.id`. The two legacy queries
differ deliberately, and the change altered a correct one. Reverted after
independent review caught it.

The mistake is worth keeping visible because of *how* it survived: I measured
positional agreement improving from 0/20 to 12/20 and credited the change,
when the change could not have affected that endpoint at all. A number moved in
the right direction after an edit, and I treated coincidence as causation
without checking which method the edit was even in. The ordering residual was
always **R-029** — legacy's own non-determinism — and nothing else.

### Where it landed

> **SUPERSEDED — do not cite these figures.** They were produced by a version
> of `sweep-auth.sh` that suppressed `json.load` errors, so a non-JSON body left
> *both* comparisons empty and they compared equal. The PHP container was also
> missing the `zip` and `calendar` extensions, so `attendance/export`,
> `payslips/export` and `attendance/stats` failed in PHP and fell into the
> "not 200 on both" bucket instead of being compared.
>
> | | |
> |---|---|
> | ~~Byte-identical JSON~~ | ~~37~~ |
> | ~~Differing~~ | ~~0~~ |
> | ~~Not 200 on both~~ | ~~153~~ |

Re-measured 2026-09-01 with the corrected script and both extensions installed:

| | |
|---|---|
| Endpoints returning **equal canonicalized JSON** | **39** |
| Endpoints differing | **1** |
| **Accepted divergence** (named decision) | **1** — `company_official_holidays/list`, D-087 |
| **Not compared** — non-JSON (XLSX) body | **3** |
| Not 200 on both (POST-only, or needing params) | 146 |

**"Canonicalized", not "byte-identical", and the difference is real.** The
sweep parses both responses and re-serialises them with `sort_keys=True`, so
key order and formatting are discarded before comparison. Two responses can be
structurally equal and differ byte-for-byte. That is the right comparison for
this level — key order is not part of the contract, values are — but calling
the result byte-identical claimed more than the method can support.

The `not compared` row is the whole point of the correction: those three —
`attendance/export`, `employees/template_excel`, `leave_balances/template_excel`
— were previously counted inside the identical column, without a byte being
compared. Comparing workbooks properly needs a reader-level comparison (D-085),
not a byte diff, since a zip carries its own timestamps.

The differing one is `company_settings/options`, which emits an extra `label`
key in Java that PHP does not. It is recorded as a risk on the stacked branch
that found it; no entry for it exists here, so it is described rather than
cited by number. **R-029** (`payslips/list` tie-break ordering) is
non-deterministic and appears in some runs and not others, which is the point
of that entry.

`company_official_holidays/list` answers **403 in PHP and 200 in Java**, and is
a decided divergence rather than a finding: **D-087** deliberately removed the
`require_company_settings_access()` gate that PHP applies *only* to
COMPANY_ADMIN and HR — a gate that protects nothing, since every less
privileged role already reads the same list, while refusing the roles that
administer the module.

It surfaced only after one-sided status differences stopped being filed under
"not 200 on both". The harness now names the deciding record beside each
accepted divergence, because a bare allowlist would let a real regression be
silenced by adding a line.

(One fix in this section was later retracted — see item 2. That retraction is
about the payslip ordering claim, not these counts.)

`payslips/list` now matches on **every value and every type** when compared
keyed by id. Only its row *order* still differs, and that is legacy's own
non-determinism — two payslips of one employee tie on every ORDER BY column
(**R-029**).

### A methodology note worth keeping

Comparing `data[0]` across two stacks is unsafe when ordering differs: it made
`basic_salary` look like `4615.44` versus `0.00`, which reads as catastrophic
data corruption and was pure positional misalignment. **Diff keyed by id, and
separate "same number, different JSON type" from "different value"** — otherwise
the real defects drown in artefacts of your own comparison.

## Level 4: mutating endpoints, compared on state as well as response

The three levels above are all reads. That left **153 of 190 endpoints
unverified** — everything that writes — because with one shared database the
first stack's write changes what the second one sees, and the comparison stops
measuring the code.

`sweep-mutations.sh` fixes that with **two identical database copies**, one per
stack (`seed-two.sh`, `JAVA_DB=workin_java`). For each case it sends the same
request to both from the same starting state and compares:

- the **response** — status and canonicalised body
- the **rows each stack wrote** — a hash of every affected table, ordered by
  primary key

The second half is the one that earns its keep. An endpoint can answer
identically and still persist differently: the wrong column, a different
rounding, a missing `updated_at`, a divergent default. The response cannot see
any of that, and those are exactly the defects that surface as wrong money weeks
later.

Every case reseeds both databases first, so cases cannot contaminate each other.
That is slow on purpose — a shared, drifting state makes a failure impossible to
attribute.

**Only the audit timestamps are normalised, and only those three.**
`created_at`, `updated_at` and `deleted_at` are compared as **null-or-set** —
which catches a missing or wrong-column write — with a separate check that the
two stacks' newest value in *every* audit column agrees within a tolerance,
which catches a timezone or default that is hours out.

Everything else temporal is compared **exactly**. That distinction is the whole
point and an earlier version got it wrong twice over: it collapsed every
timestamp/date column by *type*, and the response comparison blanked every
timestamp-*shaped* string. `attendance/create`'s entire contract is `check_in`
and `check_out`, and both were reduced to `set` on one side and `<TS>` on the
other, so PHP could persist and return 09:00 while Java persisted a different
day and the case still reported identical. Business dates and times are
caller-supplied and deterministic; only the audit columns are not.

What remains invisible is a sub-tolerance difference in an otherwise-present
audit timestamp.

### First results

| | |
|---|---|
| Mutations verified identical (response **and** rows) | **3** — branches, departments, advances |
| Rejections verified identical | **6** |
| Differing | **0** |

`advances/create` is the one worth naming: it writes money, and both stacks
produced byte-identical rows.

### A trap this hit first time round

The initial run reported "8 identical" — and **seven of those were rejections**,
because the request bodies were guesses. Only one case actually wrote anything,
so the row diff barely executed. A green number that means "both stacks refused
my malformed request" is not parity coverage.

Two habits came out of it, and both are in the script:

- **Read the rejection message** before believing a case. `advances/create`
  wanted `employee_id`; `departments/create` wanted `branch_ids`;
  `requests/create` is employee-scoped and the test user is a company admin.
- **Resolve ids from the seeded data at runtime**, never hardcode them.
  Hardcoded ids turn into `400`s after a reseed, and a `400` that matches a
  `400` reports as a pass.

The rejection cases are kept deliberately rather than deleted: error envelopes
are where PHP's quirks concentrate, and a rejection that differs breaks a client
as surely as a success that differs.

### Extended results

> **SUPERSEDED — do not cite these figures.** They were produced by a version
> of the harness that collapsed **every** temporal column by type and blanked
> every timestamp-*shaped* response string, checked only `created_at` for drift,
> ordered every snapshot by a hard-coded `id` (so a composite-keyed junction
> table errored, hashed empty, and matched itself), and counted a `000`
> transport failure as agreement. `attendance/create`'s `check_in` and
> `check_out` were therefore not compared at all, which is precisely what the
> row diff exists for. The corrected harness has not yet published a
> replacement figure; this row is left here rather than quietly restated so the
> earlier claim is visibly withdrawn.
>
> | | |
> |---|---|
> | ~~Cases~~ | ~~17~~ |
> | ~~Identical (response **and** rows)~~ | ~~17~~ |
> | ~~Differing~~ | ~~0~~ |

Re-measured 2026-09-01 with the corrected harness:

| | |
|---|---|
| Cases | **17** |
| Identical (response **and** rows) | **17** |
| Differing | **0** |
| Withheld (unreachable / reseed / login / query failure) | **0** |

**The count is unchanged and its meaning is not.** Under the previous version
several of these cases were not testing what they claimed:

- `job_titles/create` omitted two fields PHP requires and was a matching **400**
  — a rejection reported as a verified write. It now returns **201**.
- `requests/create (wrong role)` was rejected for a missing field before it ever
  reached the role check it exists to test. It now returns **403**.
- `penalties/create` had the same defect, and a successful create also writes a
  notification, which was never compared.
- `departments/create` writes `department_branches`, which the snapshot could
  not read at all — a composite-keyed table against a hard-coded `ORDER BY id`.
- `attendance/create`'s `check_in` and `check_out` were normalised away on both
  sides of the comparison.

Writes covered at row level: branches, departments (with the
`department_branches` junction), job titles, advances, administrative
decisions, payroll batches, leave balances, penalties (with their
`notifications`) and attendance, with rejection cases beside each. Payslip and
payroll-calculation cases are **not** in this harness — they are added on the
stacked branch that records their own result, and are not claimed here.

### Two traps this hit, both worth keeping

**Most admin endpoints answered 403 before touching any business logic.**
`hr_session_has_permission` lets a **company** token through unconditionally,
but an **employee** token needs an `hr_permissions` row — and the test user had
none. A sweep comparing two refusals reports parity it never tested, which is
what the first run's "8 identical" mostly was. `seed-two.sh` now grants all
seventeen `can_*` columns.

**The one difference the sweep reported was the harness, not the code.**
`payroll_batches/create` differed on `created_at` by one second, because the two
stacks are called sequentially. They are **normalised, not excluded** — see
"Timestamps are normalised, not excluded" above for the reasoning. In the row
hash each timestamp/date column collapses to `null` or `set`, so a missing or
wrong-column audit write is still caught; a separate check compares the two
stacks' newest `created_at`/`updated_at` per table and fails beyond
`TS_TOLERANCE_SECONDS` (120s), so a timezone error or a wrong default is caught
too. In the response body, only the audit **keys** collapse to `<TS>` — matching
by value shape blanked `check_in` and `check_out` as well, which is the hole
this replaced. Only a
sub-tolerance difference in an otherwise-present timestamp goes unnoticed. What
is compared is what each stack *chose* to write, not when it was called.

### A finding that is not a parity failure

Both stacks accepted `month: 13` on `payroll_batches/create` — 201, with a
computed period of `2026-11-21` to `2026-12-20`, so the batch is real and covers
shifted dates. Both accepted `total_days: -5` on `leave_balances/create`,
persisting `remaining_days: -5.0`.

Java reproduces legacy exactly, so the sweep passes. The gap is legacy's, and
Phase 1 inherits it: **R-030**. Recorded rather than fixed, because adding
validation to Java alone would make a request PHP accepts into an error in Java
— which is precisely what D-058 forbids without evidence.

This is the shape of finding a parity harness is uniquely good at: it cannot
tell you the shared behaviour is *right*, only that both systems agree. Reading
what they agreed on is still a human job.

### What is still uncovered

**Roughly ninety-three of about a hundred mutating endpoints.** Seven are
verified; the rest have never been compared.

That sentence previously read "seven mutating endpoints out of roughly a
hundred" under this heading, which inverts it — seven is what is *covered*.
Anyone reading this as cutover-readiness evidence would have taken the
validation as almost complete when it is under ten percent.

The mechanism works end-to-end; the case list is the work. The highest-value
additions are the rest of the payroll batch lifecycle (calculate, finalize,
reopen, delete), the remaining attendance write paths, and request approval —
where the money and the legal obligations are.

## The two endpoints that still differ

With the suffix corrected, 188 of 190 declared constants match. The two
residuals are **not** the same kind of thing, and an earlier draft of this
section wrongly said both were uncalled:

| Endpoint | PHP | Java | Reading |
|---|---|---|---|
| `attendance/set_employee_attendance_method` | 501 | 404 | PHP routes it and answers *not implemented*; Java does not map it at all. **Not user-visible**: the desktop client declares this constant but has no call site for it (`docs/api/three-frontend-api-usage-matrix.md`, F-05), so nothing reaches either failure. It is a declaration-coverage gap, not a live parity gap, and should not be counted as the latter. |
| `time/now` | 404 | see below | **Called, and O-3's premise is wrong.** O-3 excludes this as unreachable dead surface. It is not: the mobile client calls it from the home screen — `home_provider.dart:79` → `GetServerTimeUsecase` → `repository.getServerTime()` → `remote_data_source.dart:179` → `ApiConstants.getServerTimeEndpoint` (`'time/now'`). The chain is fully wired. PHP itself 404s it (`time` is not in `app_allowed_modules()`), so the mobile home screen already absorbs a 404 today and the *status* is not the gap — the **envelope** is. See the note below the table. |

### `time/now`, measured precisely

Re-measured 2026-08-31 against the harness (Java jar built 03:07). The single
"404 vs 401" line an earlier draft carried was measuring only the
unauthenticated case and hid that there are **two** divergences:

| Request | PHP | Java |
|---|---|---|
| unauthenticated | 404, `{"success":false,"message":"Module 'time' not found. Available: …"}` | **401**, `{"code":"error.unauthorized","message":"Unauthorized"}` |
| authenticated | 404, same PHP envelope | 404, **Spring's** `{"timestamp","status","error","path"}` |

Two distinct problems, and neither is "the exclusion is already reality":

1. **Java demands authentication for a route PHP does not recognise.** PHP's
   router rejects the unknown module *before* any auth check; Java's security
   chain rejects the request before routing. Any unmapped `/apis/**` path
   behaves this way, not just this one.
2. **The 404 envelope is Spring's, not PHP's.** A client that reads `success`
   and `message` — which every client here does — cannot parse Java's shape.

Both are properties of *every* unmatched path under `/apis/api/`, so this is a
router-level gap rather than one endpoint's bug. PHP's contract for unmatched
paths is: unknown module → 404 `module_not_found` (with the allowed-module
list), known module with no action file → 501 `module_not_implemented`, both in
the standard envelope. Java reproduces none of it. Tracked as a follow-up to the
router fix rather than fixed here, because it changes the response shape on a
whole surface and deserves its own review.

**O-3 must be revisited.** It excludes `time/now` as unreachable dead surface;
the mobile client calls it from the home screen. The exclusion's premise is
false regardless of what is decided about the envelope.

---

## What this says about the port's evidence base

The endpoint inventory, the 198-endpoint count, and every wave's "delivered"
claim were all built from the **PHP source tree** — file names — rather than
from the URLs clients call. That mapping was wrong for the routing layer, and
nothing in the test suite caught it, because the tests were written against the
same file paths the controllers were.

`LegacyLoginEndToEndTest` hits `/apis/api/auth/login_employee.php` and passes.
It would have passed forever, on a backend no client could reach.

The general lesson is narrow and worth keeping: **a port verified against its
own source tree verifies the wrong contract.** The client's request is the
contract. This harness exists to keep asking that question against a running
system rather than a reading of one.

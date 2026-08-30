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

Measured across the **190 distinct endpoints the clients actually call**:

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

Everything lives in `parity-harness/`, a sibling of the two repositories.
It is deliberately **not** inside `hr-platform`: that repo's Phase 0 lock
forbids `docker-compose.yml`, `Dockerfile` and `*.sql` outside `backend/` and
`spike/`, and this is throwaway comparison tooling rather than repository
content.

```sh
cd parity-harness
docker compose up -d db          # MariaDB 11.8, port 13306
# seed: schema, then data with FK checks off, then the Phase-1 extension table
docker compose up -d php         # hr-legacy on port 18080
./run-java.sh &                  # Java on port 18081, same DB, same JWT secret
./sweep.sh                       # every client endpoint, both stacks, diffed
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

## The two endpoints that still differ

With the suffix corrected, 188 of 190 match. The rest are real gaps, not noise:

| Endpoint | PHP | Java | Reading |
|---|---|---|---|
| `attendance/set_employee_attendance_method` | 501 | 404 | PHP has the route and answers *not implemented*; Java does not map it at all. A client calling it gets a different failure. |
| `time/now` | 404 | 401 | The documented O-3 exclusion — but PHP **404s** it, so the exclusion may already be reality rather than a decision still owed. Worth re-checking O-3 against this evidence. |

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

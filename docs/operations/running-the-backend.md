# Running The Backend

One application, one database: the existing **MariaDB/MySQL**, untouched. The
jar serves `/apis/**` — the legacy PHP API the Flutter clients already call —
and the platform-admin dashboard at `/admin/**`, over that database. There used
to be a second mode over PostgreSQL behind a profile switch; it is gone
(ADR-0017), and the three profiles that remain — `local`, `integration`,
`prod` — gate environment values only, never which database or which surface.

## Running against your existing MySQL

```sh
java -jar backend-0.0.1-SNAPSHOT.jar --spring.profiles.active=prod
```

(`local` for a developer's machine, with defaults for every variable below;
`integration` for the shared test stack. `deploy/` has the compose files.)

with these in the environment:

| Variable | What it is |
|---|---|
| `LEGACY_DB_JDBC_URL` | e.g. `jdbc:mariadb://127.0.0.1:3306/workin` — your existing database, unchanged |
| `LEGACY_DB_USERNAME`, `LEGACY_DB_PASSWORD` | its credentials |
| `JWT_SECRET` | the signing secret. **It must be the same value the PHP stack used**, or every token already on a user's device stops working. See "Tokens already issued" below |
| `LEGACY_WHATSAPP_API_TOKEN`, `_INSTANCE_ID` | the OTP gateway. **Unset means every OTP route answers 503**, which is legacy's own behaviour without credentials — deliberately not the silent success legacy used in dev (D-134) |
| `SERVER_PORT` | defaults to 8080 |

No migration runs, and no frozen table is altered. The application reads and
writes the same tables the PHP application does.

**It does need its own tables added**, once, to the same MySQL database — the
platform-admin identity model and Spring Session. They are additive: nothing
that PHP owns is touched. The DDL is
`backend/src/main/resources/db/phase1-mysql/phase1_extensions.sql`, which is also
where Phase 1's `legacy_refresh_tokens` lives, and it ships inside the jar so
you can extract the copy that matches the code you deployed:

```bash
unzip -p backend.jar BOOT-INF/classes/db/phase1-mysql/phase1_extensions.sql > phase1_extensions.sql
```

**Nothing in the application creates them.** The step-by-step runbook is
`docs/operations/provisioning-phase1-tables.md`; **R-023** tracks it as an open
cutover prerequisite until it has actually been run against production. If you
skip it, the application still starts and logs one `ERROR` line per missing
table naming what it disables — so read the first seconds of the log rather than
treating a successful startup as proof.

### The admin surface

`/admin` serves the platform-administration pages ported from the PHP
dashboard (**ADR-0016**), in the dashboard's own design and both languages.
Live today: companies, dial codes, FAQs, banners and the platform
broadcast. The sidebar shows every dashboard page, with the ones still to
be ported greyed out — it reads what is actually routable rather than a
hand-kept list.

Two flags decide whether it can change anything:

| Setting | Effect |
|---|---|
| `APP_PLATFORM_ADMIN_ACTIONS_ENABLED` | Defaults to **false**. While false the pages render read-only. ADR-0015 prerequisite 7 keeps it off until the PHP admin surface is unreachable |
| A bound second factor on the signed-in administrator | Without it, every write is refused and the page says so. Bootstrap an administrator, then enrol their TOTP |

Both are enforced in the service, not the template, so a hand-crafted POST
is refused the same way a hidden button is.

Verified on 2026-09-03: the packaged jar starts in this profile against MariaDB
11.8, `POST /apis/api/auth/login_employee` returns the same envelope with a
usable token, and an authenticated `GET /apis/api/requests/list` returns the
paginated shape the clients expect.

## Forwarded headers, and the one setting that must not be wrong

`server.forward-headers-strategy` decides whether the application believes
`X-Forwarded-For` and `X-Forwarded-Proto`. It matters because the client
address is what the login's miss budget is charged to and what the legacy
rate limits count, so a caller who can set that header can spend somebody
else's budget -- or nobody's (**R-049**).

| Profile | Value | Why |
|---|---|---|
| `prod` | **`native`**, fixed | Production is only reachable through its proxy. The compose file publishes the app port to `127.0.0.1` alone |
| `integration` | `${FORWARD_HEADERS_STRATEGY:-none}`, defaulting to **`none`** | This box is reachable by more people than production's operators. `native` is correct there **only** once a proxy is the sole route to the port |
| `local` | unset (`none`) | Nothing is in front |

**Turning it on is a two-part change, and doing half of it is the hazard.**
`native` without a proxy in front means the application trusts a header any
caller can send. So: put the proxy there, close the published port to
everything but the proxy, and only then set `FORWARD_HEADERS_STRATEGY=native`
in the same change.

**What an operator sees when it is wrong.** With `native` and no proxy: login
throttling that never trips for a determined caller, because each attempt can
claim a fresh address -- visible as `platform_admin_login_attempts` rows whose
`client_key` values are varied and implausible, and as `LOGIN_FAILED` audit
rows that never lead to a lockout. With `none` behind a proxy: every request
attributed to the proxy's own address, so one caller's misses lock out
everyone -- visible as a single `client_key` carrying every attempt.

## What happens at startup, and in what order

Three checks run before anything serves traffic, and the order is deliberate:

1. **`Phase1SchemaCheck`** (`@Order(HIGHEST_PRECEDENCE)`) names every table the
   application owns and says which feature each missing one disables. It runs
   first so that a database provisioned without `phase1_extensions.sql` is
   reported as *that*, rather than as whichever runner happened to touch a
   missing table first — which is how it read before: a stack trace from an
   unrelated component, three checks later.
2. **`LegacyRowCountStartupCheck`** refuses a JDBC URL that turns off
   `useAffectedRows`, because the legacy contract depends on MySQL's
   matched-row semantics.
3. **`PlatformAdminBootstrap`** provisions or re-encodes the dashboard
   administrator's password (see below).

`StartupRunnerOrderTest` asserts the ordering by scanning the runners rather
than by listing them, so a runner added later is covered without editing it.

**No default account.** `BackendApplication` excludes Boot's
`UserDetailsServiceAutoConfiguration`; without that exclusion Boot creates a
`user` with a generated password printed to the log, and that credential
authenticates against any chain with no authentication of its own.
`NoDefaultUserTest` asserts the context has no `UserDetailsService` at all,
because the exclusion is one line in a list and silent when dropped.

## The admin dashboard

The same jar serves it; nothing extra to start. It signs in the way the PHP
dashboard does -- **one administrator, one password, no phone** (ADR-0018) --
and the password is deployment configuration:

| Variable | What it is |
|---|---|
| `APP_PLATFORM_ADMIN_PASSWORD` | The dashboard password. The application keeps a **bcrypt hash** of it in `platform_admins` and re-encodes it on a restart whenever the value changes, so rotating it is: change the variable, restart. **A rotation also ends every session opened under the old password** — they are server-side rows, and a changed hash does not invalidate one by itself, so without that step rotating after a session was believed stolen would leave the thief up to the 8-hour limit. Unset, the last password stays in force; a database that never had one cannot be signed into |
| `APP_PLATFORM_ADMIN_ACTIONS_ENABLED` | Defaults to **false**. While false the pages render read-only and say so. ADR-0015 prerequisite 7 keeps it off until the PHP admin surface -- which shares this password -- is unreachable, because while both are live the login is only as strong as the weaker door |

Behind the form, what PHP does not do: the password is compared against a hash
rather than a constant, the miss budget is spent **per client address** (eight
misses in fifteen minutes; a per-account budget with one account would let
anyone lock the administrator out from anywhere), the session id rotates on
login, the cookie is `Secure`, `HttpOnly` and `SameSite=Lax`, every state
change carries a CSRF token, and every login, miss and logout is an audit row.

Sessions idle out after 30 minutes and end after 8 hours whatever the activity
-- stricter than PHP's thirty days, and deliberately so.

The **org pages** -- branches, departments, job titles and shifts -- are behind
the same actions flag as the company actions. That is stricter than the PHP
dashboard, whose only gate is the section permission, and it is deliberate: an
administrator writing *inside a customer's company* is at least as sensitive
as editing a FAQ (**D-171**, **D-175**). The owner's decision on 2026-09-05 is
that the flag **may be enabled on the VPS** so those flows work; every write is
still recorded against the administrator's row in the same transaction.

## Tokens already issued

The JWT secret is the one piece of configuration that is not free to change.
Java validates the exact token format frozen PHP produced, so with the same
secret every token already on a phone or desktop keeps working across the
cutover and nobody is logged out. With a different secret, every client is
signed out at the moment of switchover.

## Uploads

Legacy writes uploads to a directory served by the web server. Java writes to
the same layout, so the path the application writes to must be the same
directory the web server serves — otherwise existing images resolve to 404 and
new uploads are stored but never served.

## Health

`GET /actuator/health` is permitted without authentication in both profiles.

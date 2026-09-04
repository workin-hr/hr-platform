# Running The Backend

Two very different things can be started from the same jar, and which one you
get is decided entirely by the Spring profile. Getting this wrong is the most
likely first mistake, so it comes before anything else.

| Profile | Database | What it serves | Use it for |
|---|---|---|---|
| **`phase1-mysql`** | The existing **MariaDB/MySQL**, untouched | `/apis/**` — the legacy PHP API the Flutter clients already call — **and** the platform-admin surface at `/admin/**` | **Replacing the PHP backend, and its dashboard, without changing anything else** |
| default (no profile) | **PostgreSQL**, with its own schema created by Flyway | `/api/**` and the same platform-admin surface | The new domain: tenant identity, authorization |

Under `phase1-mysql` there is no Flyway and no PostgreSQL connection. The
legacy `/apis/**` compatibility chain is not installed under the default
profile, and the PostgreSQL tenant chain is not installed under
`phase1-mysql`.

**The platform-admin surface runs under both.** Legacy has a platform admin web
of its own (`dashboard/pages/companies/`), so a deployment that stays on MySQL
needs one too. It is the same code over whichever database the profile selects;
what is *not* carried over is how legacy authenticates it — `doAdminLogin()`
checks one shared password held in a config constant (`hr-legacy#11`), and there
is no admin table in the legacy schema at all.

## Running against your existing MySQL (`phase1-mysql`)

This is the mode that replaces PHP for the existing clients.

```sh
java -jar backend-0.0.1-SNAPSHOT.jar \
  --spring.profiles.active=phase1-mysql
```

with these in the environment:

| Variable | What it is |
|---|---|
| `LEGACY_DB_JDBC_URL` | e.g. `jdbc:mariadb://127.0.0.1:3306/workin` — your existing database, unchanged |
| `LEGACY_DB_USERNAME`, `LEGACY_DB_PASSWORD` | its credentials |
| `JWT_SECRET` | the signing secret. **It must be the same value the PHP stack used**, or every token already on a user's device stops working. See "Tokens already issued" below |
| `APP_RUNTIME_DB_USERNAME`, `APP_RUNTIME_DB_PASSWORD` | required to be *set*, but unused in this profile — the properties are read eagerly, the beans that use them are not created |
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

## Running the new domain and the admin dashboard (default profile)

```sh
java -jar backend-0.0.1-SNAPSHOT.jar
```

Needs PostgreSQL. Both profiles need `APP_PLATFORM_ADMIN_MFA_ENCRYPTION_KEY`
(32 bytes, base64) and, to provision the first administrator,
`APP_PLATFORM_ADMIN_BOOTSTRAP_PHONE` / `_PASSWORD`, if the admin surface is to
be used.

Administrative actions on companies are refused unless
`APP_PLATFORM_ADMIN_ACTIONS_ENABLED=true`. They ship off: ADR-0015 prerequisite
7 requires the legacy PHP admin surface — which still authenticates with the
shared password — to be unreachable first. While both are live, MFA is only as
strong as the weaker door.

**This currently does not start from the jar** — see **R-040**.
`BackendApplication` excludes `DataSourceAutoConfiguration`, so nothing supplies
`JdbcConnectionDetails` from `spring.datasource.*`; the only implementation is
the one Testcontainers injects in tests. It fails with *"required a bean of type
JdbcConnectionDetails that could not be found"*. Until that is closed, this
profile runs only under `./gradlew bootTestRun` with the `live-verify` profile
(`docs/operations/platform-admin-runtime-verification.md`).

The `phase1-mysql` profile is **unaffected** by R-040, because it never
constructs those beans.

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

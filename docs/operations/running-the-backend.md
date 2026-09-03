# Running The Backend

Two very different things can be started from the same jar, and which one you
get is decided entirely by the Spring profile. Getting this wrong is the most
likely first mistake, so it comes before anything else.

| Profile | Database | What it serves | Use it for |
|---|---|---|---|
| **`phase1-mysql`** | The existing **MariaDB/MySQL**, untouched | `/apis/**` — the legacy PHP API, byte-compatible, which the Flutter desktop and mobile clients already call | **Replacing the PHP backend without changing anything else** |
| default (no profile) | **PostgreSQL**, with its own schema created by Flyway | `/api/**` and the platform-admin surface at `/admin/**` | The new domain: tenant identity, authorization, the platform-admin dashboard |

They do not overlap. Under `phase1-mysql` there is no Flyway, no Postgres
connection, and `/admin/**` answers **404** — the platform-admin surface does
not exist in that mode. Under the default profile the legacy `/apis/**`
compatibility chain is not installed.

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

No migration runs. No schema changes. The application reads and writes the same
tables the PHP application does.

Verified on 2026-09-03: the packaged jar starts in this profile against MariaDB
11.8, `POST /apis/api/auth/login_employee` returns the same envelope with a
usable token, and an authenticated `GET /apis/api/requests/list` returns the
paginated shape the clients expect.

## Running the new domain and the admin dashboard (default profile)

```sh
java -jar backend-0.0.1-SNAPSHOT.jar
```

Needs PostgreSQL, plus `APP_PLATFORM_ADMIN_MFA_ENCRYPTION_KEY` (32 bytes,
base64) and, to provision the first administrator,
`APP_PLATFORM_ADMIN_BOOTSTRAP_PHONE` / `_PASSWORD`.

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

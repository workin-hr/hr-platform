# Checking Against The Live Database

Running the Java backend, in Docker, against the MySQL that PHP is still
serving — so the port can be checked against real data before anything is cut
over. Every client points at it: the mobile app, the desktop app and the admin
dashboard.

> **This is production.** The application writes to it through every route a
> client calls, exactly as PHP does — and, with administrative actions on
> (**D-207**), through the dashboard as well. Nothing below is a read-only
> mode; what it is instead is *deliberate* about the three places where the
> port could do something PHP would not.

## 1. Add the fourteen tables Java owns

Java's own tables do not exist in the PHP schema, and nothing creates them at
startup: `hibernate.hbm2ddl.auto` is `none` and there is no Flyway (**ADR-0017**
removed it). One file adds them, and it only ever adds — fourteen `CREATE TABLE`
statements and their indexes, no `DROP`, no `ALTER`, no `DELETE`, nothing that
touches a table PHP knows about (**R-023**).

| Table | What stops working without it |
|---|---|
| `legacy_refresh_tokens` | password change, logout and employee-mode password reset |
| `platform_admins` | the dashboard login |
| `platform_admin_audit_events` | every administrative action (they refuse rather than proceed unrecorded) |
| `platform_admin_login_attempts` | the login's miss budget |
| `SPRING_SESSION`, `SPRING_SESSION_ATTRIBUTES` | the dashboard session — login succeeds and is immediately forgotten |
| `attendance_devices` | terminals cannot be registered, and none of their punches are accepted |
| `employee_device_identities` | device PIN to employee mapping; punches fall back to `employee_code` and otherwise go unmatched |
| `device_punches` | the punch record itself — a terminal's scans are acknowledged and then lost |
| `unclaimed_device_sightings` | terminals pointed here but not yet claimed are invisible, so device setup has nothing to show |
| `device_operation_logs` | the device operation log |
| `device_malformed_punches` | unparseable ATTLOG lines are acknowledged to the terminal and then unrecoverable |
| `legacy_runtime_offset_history` | what the legacy runtime offset WAS — pairing refuses to run rather than guess |
| `device_assignment_history` | what a device's branch and zone WERE, for a punch delivered after a reassignment |

**Verified against the live database on 2026-09-08**, read-only: MariaDB
**11.8.8** (the version the suite runs against), `utf8mb4` /
`utf8mb4_unicode_ci`, InnoDB throughout, the schema user holds `ALL
PRIVILEGES`, and **none of the fourteen tables exist yet** — so the file applies as
written. Its 44 legacy tables are also exactly the 44 in the vendored schema
the port was built and tested against, with nothing missing and nothing extra.

Check that yourself before and after, with a script that only reads —
`backend/src/main/resources/db/phase1-mysql/verify_phase1_tables.sql`, which
paste into phpMyAdmin's SQL tab or:

```sh
mysql -h "$DB_HOST" -u "$DB_USER" -p "$DB_NAME" \
  < backend/src/main/resources/db/phase1-mysql/verify_phase1_tables.sql
```

It reports the server version, the database's charset and engine, which of the
fourteen tables are present (`none`, `applied`, a database provisioned before
the device tables existed, or or a partial apply, which the verdict tells you how to resolve -- with `--force`, not a drop), the column count of each
against what the script creates — a table with the right name and the wrong
shape is the failure the non-idempotent script exists to prevent, and a name
check cannot see it — the **collation** of each, which no name or shape check
can see and which a database provisioned before 2026-09-11 gets wrong, and that
the legacy table count is unchanged.

Take a backup first, then apply it. **Run this yourself**; it changes a
production schema, which is not something to hand to an agent:

```sh
# 1. a backup you have checked, not one you assume
mysqldump -h "$DB_HOST" -P "$DB_PORT" -u "$DB_USER" -p "$DB_NAME" \
  > "workin-before-phase1-$(date +%F-%H%M).sql"
ls -lh workin-before-phase1-*.sql          # not zero bytes

# 2. the fourteen tables
mysql -h "$DB_HOST" -P "$DB_PORT" -u "$DB_USER" -p "$DB_NAME" \
  < backend/src/main/resources/db/phase1-mysql/phase1_extensions.sql

# 3. what you should see: 14
mysql -h "$DB_HOST" -P "$DB_PORT" -u "$DB_USER" -p "$DB_NAME" -N -B -e "
  SELECT COUNT(*) FROM information_schema.tables
   WHERE table_schema = DATABASE()
     AND table_name IN ('legacy_refresh_tokens','platform_admins',
       'platform_admin_audit_events','platform_admin_login_attempts',
       'SPRING_SESSION','SPRING_SESSION_ATTRIBUTES',
       'attendance_devices','employee_device_identities','device_punches',
       'unclaimed_device_sightings','device_operation_logs',
       'device_malformed_punches','legacy_runtime_offset_history',
       'device_assignment_history')"
```

`CREATE TABLE` is not `IF NOT EXISTS` here, deliberately: on a database that
already has them the script stops rather than silently continuing past a table
whose shape it did not verify. Re-running it after a partial apply means either
`mysql --force`, which creates only what is absent and reports one `ERROR 1050`
per table that already exists, or dropping the ones **this apply** created.

**Do not drop all fourteen to "start clean" on a database that already served
the six originals.** `platform_admin_audit_events` is retained evidence (D-161)
and `SPRING_SESSION` is every live administrator session; neither is recreated
with its contents. The eight device tables are the ones that are safe to drop
and re-add, because nothing has written to them yet on such a database.

**Undoing a first, complete apply** is `DROP TABLE` on all fourteen names and
nothing else — PHP references none of them — and only while none of them has
been written to. Once the platform-admin surface has been used, the audit and
session tables carry state that a drop destroys.

## 2. Point the backend at it

```sh
cd deploy
cp env.remote-db.example .env.remote-db     # git-ignored; fill it in
docker compose -f compose.remote-db.yaml -f compose.tls.yaml \
  --env-file .env.remote-db up -d --build
```

Both files. The dashboard needs the TLS one: its session cookie is `Secure`
unconditionally (**ADR-0015** prerequisite 6), so on plain HTTP a browser
accepts the login and then throws the cookie away — the symptom is a login that
appears to succeed and lands you back on the login page.

`APP_DOMAIN=localhost` makes Caddy issue from its own internal CA, so the
browser will warn once and the clients need to accept it; that is the only
difference from a real deployment.

Three things are set differently here, and they are the reason this file exists
rather than `compose.local.yaml` with a changed URL:

| | Why |
|---|---|
| `ADMIN_ACTIONS_ENABLED=true` | The dashboard does what PHP's admin panel does — approve, reject, suspend, restore. **Close the PHP panel before you open this one** (ADR-0015 prerequisite 7, **D-207**): both write the same rows and neither knows about the other. Set it to `false` to render those pages read-only while PHP is still reachable |
| `UPLOADS_URL` absolute, `UPLOADS_PATH` mounted | The URL points at wherever the files already are — given an absolute one the application registers no upload handler at all, so it cannot appear to serve files it does not have. `UPLOADS_PATH` is the directory *behind* that URL, bind-mounted in, and the two must name the same storage. Creating a company or a banner writes a file: written somewhere the PHP host cannot serve, the row holds a URL that answers 404, and nothing fails at the time — the upload succeeds and only the later GET does not. Upload one logo and open the URL the row now holds |
| WhatsApp unset | Every OTP route answers `503` instead of messaging a real person while somebody is looking around |

And `JWT_SECRET` **must equal PHP's `AppConfig::JWT_SECRET`** (**R-024**). It is
the same database, so the same devices are already carrying tokens; a different
secret rejects every one of them, which looks exactly like a broken login.
`docs/operations/verifying-the-signing-secret.md` compares the two without
printing either.

Check it came up:

```sh
curl -k https://localhost/actuator/health          # {"status":"UP"}

# The fourteen checks, against this deployment. Sourcing the environment file
# is what keeps a production password out of the shell history, and `DB_HOST`
# is what tells the script to reach the database directly -- there is no `db`
# container here for it to exec into.
set -a; . deploy/.env.remote-db; set +a
BASE_URL=https://localhost TLS_INSECURE=1 scripts/verify-admin-login.sh
```

`TLS_INSECURE=1` belongs here and nowhere else: `APP_DOMAIN=localhost` means
Caddy signed the certificate itself. Against a real deployment the certificate
is exactly what you want checked.

## 3. Run the clients against it

| | Where it points | How |
|---|---|---|
| **Web admin** | `https://localhost/admin` | A browser. Accept the certificate warning once. Sign in with `ADMIN_PASSWORD` — one password, no phone (**ADR-0018**) |
| **Mobile** | `api_constants.dart:4` | `static const String baseUrl = 'https://10.0.2.2/apis/api/';` on the Android emulator, or your machine's LAN address on a device. `flutter run` |
| **Desktop** | `api_constants.dart:4` | `static const String baseUrl = 'https://localhost/apis/api/';` then `flutter run -d linux` (or `windows`/`macos`) |

A self-signed certificate is refused by Dart's HTTP client by default, so for
this checking session either import Caddy's root — `docker compose ... exec
proxy cat /data/caddy/pki/authorities/local/root.crt` — into the device's trust
store, or run the clients against `http://localhost:8080` by bringing the stack
up **without** `compose.tls.yaml`. The API needs no cookie and no TLS; only the
dashboard does.

`docs/operations/flutter-local-integration.md` has the account list and the
response shapes, and everything in it applies here except the database it
names.

## 4. What to look at first

The pages and routes where the port had to make a judgement, because those are
where real data disagrees with a seed:

- **`/admin` overview** — the counts and the two charts. They read the same
  tables PHP's `home_service.php` reads, and PHP's own rounding (**PhpMath**)
- **`/admin/employees`** — 3,700 rows, the pager, and the `int(10) unsigned`
  column that used to 500 the page (**D-190**)
- **`/admin/payroll`** — the twenty-five payslip columns, of which the port
  writes twelve
- **`apis/api/auth/login_company`** from a real device, with a token that device
  already had — the R-024 check, from the client side
- Anything that renders an upload. A broken image means `UPLOADS_URL` is wrong,
  not that the file is gone (**R-068**)

## 5. When you are finished

```sh
docker compose -p workin-remote-db down
```

The stack owns no volume, so there is nothing of the database to remove. Two
things do survive it and are worth doing:

- **rotate the database password.** It has been in an environment file, a shell
  history and possibly a terminal you shared.
- **the rows the checking created**: sessions in `SPRING_SESSION`, one row per
  login attempt in `platform_admin_login_attempts`, and an audit row per login
  in `platform_admin_audit_events`. All in Java's own tables; none in PHP's.

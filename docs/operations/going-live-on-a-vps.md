# Going Live On A VPS

Moving this application onto a server of your own: which stack to run, every
value that changes and the file it lives in, the order to do it in, and how to
tell it worked.

Written for the cutover from the PHP deployment. It assumes the six Phase 1
tables already exist in whichever database you point at —
[checking-against-the-live-database.md](checking-against-the-live-database.md)
covers applying them.

## 1. First decide where the database lives

This is the only decision that changes which files you use.

| | Stack | The database is | Data migration |
|---|---|---|---|
| **A** | `compose.prod.yaml` + `compose.tls.yaml` | MySQL **in the stack**, on the VPS, in a Docker volume | Restore a dump onto the VPS first |
| **B** | `compose.remote-db.yaml` + `compose.tls.yaml` | Somewhere that already exists — the host PHP uses today | None |

**B is the smaller step** and the one to take first: the application moves, the
data does not, so a rollback is stopping a container rather than reconciling two
copies of a live database. Move the database afterwards, as its own change, once
the application has been serving from the VPS for a while.

Both pairs are two files, always. `compose.tls.yaml` puts Caddy in front and
**unpublishes the application's own port**, and the `prod` profile trusts
`X-Forwarded-For` — which is only safe when the proxy is the sole route in
(**R-049**, **D-206**).

## 2. Before the first start

Three things must be true, and none of them fails loudly later.

- **`APP_DOMAIN` resolves to the VPS.** Caddy obtains the certificate on first
  start; a name that does not resolve is a certificate that never issues and a
  site that never serves.
- **Port 80 and 443 are open** to the internet. The certificate authority
  reaches port 80 to validate.
- **The Phase 1 tables exist** in the database you are pointing at. The
  application creates its administrator row at every startup, so without
  `platform_admins` it does not start at all.

## 3. What changes, and where

`deploy/.env.prod` for **A**, `deploy/.env.remote-db` for **B**. Both are
git-ignored copies of the `env.*.example` beside them; neither may be committed.

| Setting | Set it to | Why it matters |
|---|---|---|
| `APP_DOMAIN` | the name clients will call | Caddy's certificate is issued for exactly this |
| `TLS_EMAIL` | a mailbox somebody reads | Where renewal failures are sent. A renewal that fails silently is a site that goes dark on a Sunday |
| `JWT_SECRET` | **PHP's `AppConfig::JWT_SECRET`, exactly** | **R-024.** Every token already on a device is signed with it. A different value logs out every user at cutover and again when their refresh token is rejected. The application logs a *fingerprint* of this at startup, never the value — compare it against PHP's before opening the window ([verifying-the-signing-secret.md](verifying-the-signing-secret.md)) |
| `DB_*` (**A**) | credentials for the stack's own MySQL | The stack creates this database on first start |
| `DB_HOST` / `DB_NAME` / `DB_USER` / `DB_PASSWORD` (**B**) | the existing server | The host must permit connections from the VPS's address — on shared hosting that is usually an allowlist you edit in the panel, and it is the failure people hit first |
| `UPLOADS_URL` | `/uploads/` after cutover | See §4 |
| `UPLOADS_PATH` | the directory behind that URL | See §4 |
| `ADMIN_PASSWORD` | the dashboard's one password | ADR-0018. Rotating it later is: change this, restart |
| `ADMIN_ACTIONS_ENABLED` | `false` until PHP is off | ADR-0015 prerequisite 7. The same password opens both dashboards, so while PHP is up the guards here protect nothing — an attacker uses the weaker surface |
| `WHATSAPP_*` (**A**) | real credentials | Unset, every OTP route answers 503, which breaks registration, password reset and phone change without an error anyone sees |

## 4. Uploads, which have two modes

`UPLOADS_URL` is where a file is **read from**; `UPLOADS_PATH` is where it is
**written**. Nothing in the application can tell whether they agree.

- **While PHP is still serving:** `UPLOADS_URL=https://the-php-host/uploads/`.
  The application registers no handler and cannot appear to serve files it does
  not have. `UPLOADS_PATH` must then be that host's own uploads directory,
  reachable from the VPS, or a logo uploaded for a new company lands where
  nothing serves it.
- **After cutover:** `UPLOADS_URL=/uploads/`. The application serves them itself
  out of `UPLOADS_PATH`, behind the same TLS, with an extension allowlist taken
  from sniffed content rather than a client-supplied filename. **This is the one
  a cutover wants** — an absolute URL at the PHP host is a dependency on the
  host you are switching off.

Going site-relative needs the existing files copied into `UPLOADS_PATH` once.
Skip it and the failure is quiet: the rows are intact, nothing errors, and only
the images are missing.

## 5. Start it

```sh
cd deploy
cp env.prod.example .env.prod          # or env.remote-db.example .env.remote-db
$EDITOR .env.prod
docker compose -f compose.prod.yaml -f compose.tls.yaml --env-file .env.prod up -d --build
```

`--build` matters: without it compose reuses whatever image is already on the
host, which on a second deployment is the previous release.

## 6. Tell whether it worked

```sh
curl https://$APP_DOMAIN/actuator/health           # {"status":"UP"}
curl -o /dev/null -w '%{http_code}\n' https://$APP_DOMAIN/apis/api/configs/get   # the clients' surface
curl -o /dev/null -w '%{http_code}\n' https://$APP_DOMAIN/admin/login            # the dashboard
curl -o /dev/null -w '%{http_code}\n' http://$APP_DOMAIN/                        # 308 to https

set -a; . .env.prod; set +a
BASE_URL=https://$APP_DOMAIN ../scripts/verify-admin-login.sh    # fourteen checks
```

Then the two that only a person can do: **sign in to `/admin`** and open a page
with real data on it, and **upload one logo and open the URL the row now
holds** — that is the only check that catches `UPLOADS_URL` and `UPLOADS_PATH`
disagreeing.

## 7. Point the clients at it

Both Flutter clients hold the base URL in one line:

- `flutter-integration/workin_mobile/lib/core/network/api_constants.dart:4`
- `flutter-integration/workin_desktop/lib/core/network/api_constants.dart:4`

```dart
static const String baseUrl = 'https://your-domain/apis/api/';
```

Changing it means a rebuild and a release to every device, so it is the slowest
step in the cutover and the one to start early. Until a device takes the new
build it keeps calling the old host — which is why `JWT_SECRET` must match: the
two deployments must accept each other's tokens while both are reachable.

## 8. Turning PHP off

Only after the clients are pointing here and the dashboard has been used
against real data:

1. Make the PHP admin surface unreachable.
2. Set `ADMIN_ACTIONS_ENABLED=true` and restart. Until this, the company pages
   render read-only and say so.
3. Switch `UPLOADS_URL` to `/uploads/` and copy the files across, if you have
   not already.

## Rolling back

Stopping the container is the rollback, and it is complete in scenario **B**:
the database is untouched by the switch, and PHP serves again the moment DNS or
the proxy points back. In **A** the VPS database has taken writes the old host
has not, so a rollback there is a data reconciliation and needs planning before
the cutover, not after.

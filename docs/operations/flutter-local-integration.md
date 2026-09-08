# Running the backend locally, for Flutter developers

Everything below was run against the stack it describes. The accounts, the
response shapes and the failure modes are observed, not assumed.

## 1. Start the backend

You need Docker and a clone of this repository. Nothing else — no VPS, no
credentials, no database to install.

```sh
cd deploy
docker compose -f compose.local.yaml up
```

First run takes a few minutes: it builds the backend and restores a 9.2 MB
seed. After that it is seconds. Leave it running; `Ctrl-C` stops it, and
`docker compose -f compose.local.yaml up -d` runs it in the background.

Check it is alive:

```sh
curl http://localhost:8080/actuator/health
# {"groups":["liveness","readiness"],"status":"UP"}
```

| | |
|---|---|
| API base | `http://localhost:8080/apis/api/` |
| MariaDB | `127.0.0.1:13306`, user `workin`, password `workin-local`, database `workin` |

## 2. Point the app at it

One line, in each app:

```text
flutter-integration/workin_mobile/lib/core/network/api_constants.dart:4
flutter-integration/workin_desktop/lib/core/network/api_constants.dart:4
```

```dart
static const String baseUrl = 'https://workin.company/apis/api/';
```

What to change it to depends on where the app runs — and this is where most of
the lost time goes:

| Running on | Use |
|---|---|
| **Android emulator** | `http://10.0.2.2:8080/apis/api/` |
| **iOS simulator** | `http://localhost:8080/apis/api/` |
| **Physical device** (same Wi-Fi) | `http://<your-machine-LAN-IP>:8080/apis/api/` |
| **Desktop (Linux/macOS/Windows)** | `http://localhost:8080/apis/api/` |

`10.0.2.2` is the Android emulator's alias for the host machine. `localhost`
inside the emulator is the emulator itself, so it will simply fail to connect.

### Cleartext HTTP is blocked, and you have to turn it on

The local stack serves plain HTTP. Neither app is currently configured to allow
that, so **the first request will fail on a real device or emulator even with
the URL correct**.

`workin_mobile` targets `targetSdk = 36`, and Android has blocked cleartext by
default since API 28. For local development only, add to
`workin_mobile/android/app/src/main/AndroidManifest.xml`, on the
`<application>` tag:

```xml
<application
    android:usesCleartextTraffic="true"
    ... >
```

For iOS, in `workin_mobile/ios/Runner/Info.plist`:

```xml
<key>NSAppTransportSecurity</key>
<dict>
    <key>NSAllowsLocalNetworking</key>
    <true/>
</dict>
```

`NSAllowsLocalNetworking` is narrower than `NSAllowsArbitraryLoads` — it permits
local addresses and nothing else, which is all this needs.

**Do not ship either change.** Both weaken transport security for every request
the app makes. Keep them on a local-only branch, or gate them behind a debug
build, and never merge them to a release configuration.

## 3. Sign in

**Every account's password is `devpassword`.**

| Who | Endpoint | Phone | `country_code` |
|---|---|---|---|
| Company owner | `auth/login_company` | `01000090001` | `+20` |
| Employee | `auth/login_employee` | `01000000002` | `+20` |
| HR | `auth/login_employee` | `01000000256` | `+20` |
| Desktop, either | `auth/login_desktop` + `"login_as": "company"` or `"employee"` | as above | `+20` |

```sh
curl -X POST http://localhost:8080/apis/api/auth/login_company \
  -H 'Content-Type: application/json' \
  -d '{"phone":"01000090001","country_code":"+20","password":"devpassword"}'
```

The token is at **`data.token`** — not `access_token`:

```json
{
  "success": true,
  "message": "Login successful",
  "data": {
    "company": { "id": 1, "company_name": "مجموعة الأفق 1", "...": "..." },
    "token": "<a JWT — three base64 segments separated by dots>"
  }
}
```

Send it as a bearer token on everything else:

```sh
curl "http://localhost:8080/apis/api/employees/list?page=1&per_page=2" \
  -H "Authorization: Bearer $TOKEN"
```

### Finding more accounts

```sh
docker exec -it workin-local-db-1 mariadb -uworkin -pworkin-local workin \
  -e "SELECT phone, country_code, company_name FROM companies WHERE status='active' LIMIT 10"

docker exec -it workin-local-db-1 mariadb -uworkin -pworkin-local workin \
  -e "SELECT phone, country_code, role, company_id FROM employees WHERE is_active=1 LIMIT 10"
```

The seed holds **386 companies**, **3,783 employees** (11 active HR), **44,756
attendance rows** and **3,193 payslips** — production's real volumes, so your
lists paginate and your payroll screens load exactly as slowly as they will in
production.

## 4. URLs

| | |
|---|---|
| API base | `http://localhost:8080/apis/api/` |
| **Swagger UI** | `http://localhost:8080/swagger-ui.html` |
| OpenAPI document (clients) | `http://localhost:8080/v3/api-docs/client-api` |
| OpenAPI document (platform admin) | `http://localhost:8080/v3/api-docs/platform-admin` |
| Health | `http://localhost:8080/actuator/health` |
| Admin UI | `http://localhost:8080/admin/login` |

Swagger UI opens on the **Client API** group: the 202 legacy routes and nothing
else. The platform-admin group is a separate entry in the dropdown, because it
is a different audience with different credentials.

Two things about that document are worth knowing before you trust it, because
both are corrections applied on purpose:

- **The verbs are exact.** Every route is mapped in Java without a method
  restriction, so the handler can answer PHP's own `405 invalid_method` in the
  legacy envelope. Generated straight, the document would claim every route
  accepts every verb. It is pruned against what each handler really checks, so
  `auth/login_company` shows `POST` alone and `profile/employee` shows `GET` and
  `PUT`. Two routes genuinely accept anything — the two `template_excel`
  downloads — and PHP does not check the method on them either.
- **The paths have no `.php` suffix**, because that is the URL you call.
  Against the PHP system this replaces, `configs/get` answers 200 and
  `configs/get.php` answers 500. Your `api_constants.dart` already uses the
  suffix-less form; this port serves both, so the suffix would work here and
  fail there.

**What the document does not tell you** is the shape inside `data`. The envelope
is `{success, message, data?, meta?}`, and `data`/`meta` are free-form objects
because the port returns PHP's own structures rather than re-typing 202
endpoints — that is what makes it a faithful port. So the document is
authoritative about *which routes exist and which verb each accepts*, and silent
about the payload. For the payload, call the endpoint, or read your own client,
which already encodes it.

The routes are also listed flat, if that is easier to grep:

```sh
grep '^/' contracts/legacy-php-routes.txt      # all 202 routes
```

Swagger is published under `local` and `integration` and **switched off under
`prod`** — an unauthenticated map of every endpoint on a live system is
reconnaissance. Do not build anything that expects it in production.

**The admin UI renders at `/admin/login` but will not keep you signed in over
plain HTTP.** Its session cookie is `Secure` unconditionally, by ADR-0015, and
that is not relaxed for local convenience. You will see the login page, submit
it, and be bounced back. To actually use it locally you need TLS in front, or a
deliberate local-only override — ask before adding one.

## 5. Watching your requests arrive

The app logs one line per request on stdout, so the `docker compose up` window
tells you whether your call reached the backend at all:

```text
172.30.0.1 POST /apis/api/auth/login_company.php HTTP/1.1 -> 200 (102ms)
```

If a request from your app does not appear here, it never arrived — check the
base URL and the cleartext settings above before looking at the backend.

Need more detail:

```sh
APP_LOG_LEVEL=DEBUG docker compose -f compose.local.yaml up
```

## 6. Things that look broken and are not

**Every OTP route answers `503 otp_delivery_failed`.** WhatsApp is deliberately
unconfigured locally, so registration, password reset and phone change cannot
complete. That is the legacy backend's own behaviour without credentials, not a
bug in the stack. If you need those flows, test them against the shared
integration environment instead.

**The phone numbers all look alike.** They are `010000NNNNN` by design:
format-valid, so login and validation work, and dialable by nobody. Names,
addresses, salaries and documents are all replaced too — the data is
production's *shape*, not its content. See `deploy/seed/README.md`.

**`/admin` will not keep you logged in over plain HTTP.** That surface's session
cookie is `Secure` unconditionally and is not relaxed for local convenience.
It does not affect `/apis/**`, which is bearer-token authenticated.

**Arabic must round-trip.** If a name you POST comes back mangled, that is a
real bug — say so. The backend decodes form and JSON bodies as UTF-8, and there
is a regression test pinning it (see R-067, which was exactly this).

## 7. Resetting

```sh
cd deploy
docker compose -f compose.local.yaml down -v   # -v drops the database volume
docker compose -f compose.local.yaml up
```

The database seeds on **first start only**, so `down -v` is how you get a clean
one — after a `git pull` that brought a new seed, for instance.

## 8. When there is a shared server

`compose.integration.yaml` runs the same image with the same seed on a shared
box, so the whole team points at one URL instead of each running Docker. It
needs secrets, so it is not something to start casually — see
[`deploy/README.md`](../../deploy/README.md).

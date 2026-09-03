# Integrating The Flutter Clients With The Java Backend

**The short version: you do not change the clients at all.**

Both Flutter applications hardcode

```dart
static const String baseUrl = 'https://workin.company/apis/api/';
```

and the Java backend, run with `--spring.profiles.active=phase1-mysql`, serves
exactly those paths with byte-compatible responses. Integration is a
**deployment** change — what `workin.company` resolves to — not a code change.
That is the whole point of the port (**D-111**: zero client change), and it is
what the parity verification was for.

## What to do

1. Run the Java backend against your existing MySQL. See
   [running-the-backend.md](../operations/running-the-backend.md) — the
   `phase1-mysql` profile.
2. **Use the same `JWT_SECRET` the PHP stack used.** Java validates the exact
   token format frozen PHP produced, so with the same secret every token already
   on a device keeps working. With a different one, every user is signed out at
   the moment you switch.
3. Point `workin.company/apis/` at the Java application instead of PHP — DNS,
   load balancer, or reverse proxy. Keep the path prefix: the clients send
   `/apis/api/...` and Java's compatibility chain matches `/apis/**`.
4. Serve the uploads directory from the same place Java writes to, or existing
   images 404.

That is the entire integration. No rebuild, no store submission, no new client
version.

## Why no client change is needed

The clients were verified against both stacks, not assumed compatible:

| Layer | What was checked | Where |
|---|---|---|
| Contract | 178 desktop and 39 mobile endpoint declarations, their parsers, and every replayed response compared field by field between PHP and Java | `spike/client-contract/` |
| Desktop runtime | The real desktop application, unmodified, driven through its own UI against both stacks — 22 endpoints, identical results | `spike/client-runtime/DESKTOP-RUNTIME-REPORT.md` |
| Mobile runtime | The real APK on an emulator against Java: login, GPS check-in/out, request creation, multipart photo upload, logout | `spike/client-runtime/MOBILE-RUNTIME-REPORT.md` |

No Java parity defect was found in any of it.

## Three things that behave the same and look like bugs

Each was reproduced against PHP before being accepted, so do not "fix" them
when you meet them:

- **`GET time/now` returns 404.** The mobile client calls it five times a
  session. `time/now.php` exists on disk, but `time` is not in
  `ApiModule::allowedList()`, and the legacy router checks that list before
  looking for a file. It has never worked. The client tolerates it.
- **`requests/create` returns 403 for a `company_admin`.** The endpoint is
  `requireAuth([EMPLOYEE])`. Only an employee-role account can create a request.
- **`profile/logout` deactivates the account** and notifies the company. That is
  what `logout.php` does; the name is misleading, the behaviour is matched.

## One accepted difference

**`company/upload_logo` and the other upload endpoints derive the stored file's
extension from the file's actual content type, not from the name the client
sent** (**D-154**). PHP took the extension from the client-supplied filename,
which is how a file whose bytes are an image but whose name ends `.php` gets
stored as `.php` in a directory the web server serves (**R-039**).

Client-visible effect: a stored file may have a different extension than under
PHP. The clients use the URL the API returns, so they follow it either way.

## The admin dashboard is served too

`/admin/**` runs under `phase1-mysql` as well, against the same MySQL database —
the replacement for `dashboard/pages/companies/`. It needs its own tables added
once (see the run guide); nothing PHP owns is altered.

It does not affect the Flutter clients. Company and HR administration is the
desktop client's job (ADR-0009 Option E); the admin surface administers Workin
itself.

## Rolling back

Point the hostname back at PHP. Both stacks read the same database and the same
token format, so a rollback is a routing change, and sessions survive it in both
directions.

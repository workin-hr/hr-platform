# Platform-Admin Surface: Runtime Verification

The dashboard's login, exercised against a **running application** rather than
only in tests:

`login -> session -> CSRF -> logout`

`PlatformAdminFullFlowTest` drives this journey on every build and is the
regression gate. This exists because "it passes in a test" and "it works in the
deployed application" are different claims, and only the second one is about a
container reading its configuration from the environment, a session cookie
crossing a proxy, and a `SPRING_SESSION` row in the database an operator
actually provisioned.

## Running it

```sh
cd deploy && docker compose -f compose.local.yaml up -d --build
BASE_URL=http://127.0.0.1:8080 ADMIN_PASSWORD=devpassword \
  DB_CONTAINER=workin-local-db-1 scripts/verify-admin-login.sh
```

Any deployment works -- point `BASE_URL`, `ADMIN_PASSWORD` and the `DB_*`
variables at it. The script reads the database to check what the application
wrote and writes nothing itself; it creates one session and ends it.

## What it checks, and why each one needs a running application

| # | Check | Why a test cannot settle it |
|---|---|---|
| 1 | `GET /admin` unauthenticated redirects to the login page | The chain that does it is assembled from configuration the container reads at boot |
| 2 | A wrong password re-renders the page with its error banner, leaves no session, and writes a `LOGIN_FAILED` row | The audit write is a different transaction from the refusal; a deployment with the table missing fails here and nowhere else |
| 3 | The right password rotates the session id, opens `/admin`, and its session is a row in `SPRING_SESSION` | Session fixation and the shared store are properties of the servlet container and the JDBC session repository, not of the controller |
| 4 | The cookie is `HttpOnly`, `SameSite=Lax` and `Secure` | ADR-0015 prerequisite 6. `Secure` is unconditional, so a deployment serving this surface over plain HTTP loses the cookie in a browser -- which is why it goes behind TLS |
| 5 | A company action and a logout without a CSRF token are both `403` | The token is bound to the session, and the binding is the deployment's `SecurityContextRepository` |
| 6 | Logout removes the `SPRING_SESSION` row, and the old cookie opens nothing | The row is what makes a revocation real; dropping the cookie alone would pass a test that only checks the browser side |

## The run of record

**2026-09-08**, against the packaged image from `deploy/compose.local.yaml`
(the `local` profile, MariaDB 11.8 with the sanitised seed, the administrator
provisioned at boot by `PlatformAdminBootstrap` from `APP_PLATFORM_ADMIN_PASSWORD`
through the real encoder):

```text
14 checks passed against http://127.0.0.1:18080
```

All six groups above passed, including the three cookie flags and both CSRF
refusals. The deployment E2E suite (`deploy/e2e/run.sh integration`) covers the
same surface in a real browser over real TLS, which is the check to run before a
cutover; this script is the one that answers in ten seconds during a deployment.

## Appendix: the ADR-0015 run (historical)

The verification below was made against ADR-0015's authentication model --
individual administrators, TOTP with seed custody, step-up approvals and the
bearer API -- on PostgreSQL, before **ADR-0017** made MySQL permanent and
**ADR-0018** replaced that model with one administrator and one password. Its
flow no longer exists, and `scripts/verify-platform-admin-flow.sh` was removed
with it. It is kept as the record of what was verified at the time.

## How the run was set up

| | |
|---|---|
| Application | The real Spring Boot application, started with `bootTestRun`, Tomcat on 18090 |
| Database | **Postgres 17 at the time of the run**, migrated by the application's own Flyway. That half is deleted (ADR-0017): the application runs against the legacy MariaDB, whose Java-owned tables `phase1_extensions.sql` provisions (`provisioning-phase1-tables.md`) |
| Administrator | Provisioned by the application's own `PlatformAdminBootstrap` from `APP_PLATFORM_ADMIN_BOOTSTRAP_PHONE`/`_PASSWORD` — the real provisioning path with the real password encoder, not a hash written into the table by hand |
| Administrative actions | **Enabled for the run** (`APP_PLATFORM_ADMIN_ACTIONS_ENABLED=true`). They ship disabled; see the deployment note below |

## What the run showed

| # | Step | Result |
|---|---|---|
| 1 | `GET /admin` unauthenticated | 302 to the login page |
| 2 | Enrolment with a **wrong** bootstrap token | refused; the password alone does not begin enrolment |
| 2 | Enrolment with the real token | seed displayed once; confirming with a code binds the factor (`bound_at` set) |
| 3 | `POST /admin/login` with the correct password | 302 to `/admin/mfa` — and `GET /admin` on that session is still refused |
| 4 | `POST /admin/mfa` with a valid code | 302; `GET /admin` now 200 |
| 5 | `GET /admin/sessions` | 200, the current session marked as such |
| 6 | Step-up, then apply | approval minted against that company and reason; the action applied; company status `active` -> `suspended` |
| 6 | Audit | one row: `COMPANY_SUSPENDED COMPANY <id> approval=<approval id>` |
| 7 | Replaying the same approval | refused |
| 8 | `POST /admin/logout` **without** a CSRF token | 403 |
| 9 | `POST /admin/logout` with one | 302; the session no longer works and its `spring_session` row is gone |
| 10 | `POST /api/platform-admin/login` with password only | 401 |
| 10 | ...with the TOTP code | 200 |

Rows 3, 8 and 10 are the ones worth reading twice: they are the three ways the
second factor could have been walked around — a password-only session reaching a
page, a state-changing route without CSRF, and the bearer API minting a token
from a password alone — and each is closed.

## Not covered by this script

- **Deactivation mid-session** and **step-up bound to a different company**:
  covered by `PlatformAdminFullFlowTest` and
  `PlatformAdminStepUpServiceTest`, which can force those states directly.
- **Concurrency.** The single-use guarantee under simultaneous requests is
  proven in `PlatformAdminStepUpServiceTest`, not here; a shell script is the
  wrong instrument for a race.
- **Multi-worker behaviour.** Sessions live in shared JDBC storage, which is
  what makes logout work across workers, but this run used one instance.

## Deployment note, and one gap this run exposed

**Administrative actions ship disabled** (`app.platform-admin.actions.enabled`
defaults to false). ADR-0015 prerequisite 7 requires the legacy PHP admin
surface — which still authenticates with the shared password — to be unreachable
first. While both are live, MFA is only as strong as the weaker door.

**Historical note.** When this verification was run the application could not
start from its jar under the PostgreSQL profile (R-040), and a test-scoped
`live-verify` data-source configuration stood in for the missing bean. That
profile, that configuration and R-040 are gone with ADR-0017; the jar starts
against MySQL as `running-the-backend.md` describes.

# Platform-Admin Surface: Runtime Verification

The complete flow of ADR-0015's platform-admin surface, exercised against a
**running application** rather than only in tests:

`login -> MFA -> session -> step-up -> admin action -> logout/revocation`

Re-run it with `scripts/verify-platform-admin-flow.sh`, which documents its own
prerequisites. The integration suite is the regression gate; this exists because
"it passes in a test" and "it works in the application" are different claims,
and the second is the one worth re-checking before a cutover.

## How the run was set up

| | |
|---|---|
| Application | The real Spring Boot application, started with `bootTestRun`, Tomcat on 18090 |
| Database | Postgres 17, migrated by the application's own Flyway on startup |
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

**The application cannot currently be started from its jar.**
`BackendApplication` excludes `DataSourceAutoConfiguration`, so nothing supplies
`JdbcConnectionDetails` from `spring.datasource.*`; the only implementation in
the repository is Testcontainers' `@ServiceConnection` in the test base class.
Running the jar against a real Postgres fails at startup with *"required a bean
of type JdbcConnectionDetails that could not be found"*.

This run worked around it with a **test-scoped** `LiveVerifyDataSourceConfig`
behind a `live-verify` profile. That is deliberately not a fix: it belongs with
the deployment work — `infrastructure/` is still an empty Phase-0 boundary — and
putting it in production code here would have hidden a real gap under a
verification task. **It has to be closed before anything deploys.**

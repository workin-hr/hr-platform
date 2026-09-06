# Monitoring And Alerting

Covers both monitoring ownership and alert routing. See
`docs/architecture/quality-attributes.md` and ADR-0008 (Observability
Baseline) for the architecture-level observability decision this operates
under.

## What This Deployment Actually Emits

*Filled 2026-09-04 against the configured application, replacing the
template that stood here. It describes what exists, not what a
well-instrumented service would have — the difference between the two is
recorded as **R-043**.*

| Signal | Where it comes from | Where it goes |
|---|---|---|
| Structured JSON logs | `logging.structured.format.console=logstash` | stdout |
| Correlation ids | `traceId`/`spanId` in every line (**ADR-0008**) | the same log line |
| Traces | Micrometer Tracing, **sampled at 1.0** | `opentelemetry-exporter-logging` — i.e. **into the log**, not to a collector |
| Health | `/actuator/health`, `permitAll` | HTTP, status only (details default to `never`) |

That is the complete list. There is **no metrics endpoint exposed, no
Prometheus, no dashboard, no log aggregation, and no alert routing**
configured anywhere in this repository.

### Two things to fix before cutover

**Trace sampling is at 100% and exports to the log.** Every request
produces span output into stdout. That is right for the local
verification it was set up for and wrong for production: it multiplies
log volume by request rate for data nobody is collecting. Either lower
`management.tracing.sampling.probability` or point the exporter at a real
collector — but decide, rather than shipping 1.0 by default.

**`/actuator/health` is `permitAll`.** That is the standard arrangement
and is safe as configured, because health details default to `never` and
the endpoint returns only `UP`/`DOWN`. It becomes an information
disclosure the moment someone sets `management.endpoint.health.show-details`,
which exposes database connectivity and component internals to
unauthenticated callers. Do not set it without also restricting the
matcher in `SecurityConfig`.

## The Signals The Rollback Depends On

`docs/operations/release-cutover-and-rollback.md` triggers a rollback on
observations like "a rise in 401s" and "error rate clearly worse than the
PHP baseline". **Nothing currently measures either.** Until that changes,
those triggers are satisfied by a human watching logs, which is a real
answer for a supervised cutover window and not an answer at all for the
days after it.

| Trigger | What would have to be watched | Available today |
|---|---|---|
| Broad authentication failure | 401 rate on `/apis/**` | No — log inspection only |
| Error rate above the PHP baseline | 5xx rate, and a baseline nobody has recorded | No, and no baseline exists |
| `otp_delivery_failed` | The literal string; logged at `ERROR` | Yes, by grep |
| Missing Phase 1 table | `Phase 1 schema check: ... MISSING` at startup | Yes, by grep, once per boot |
| Latency regression | Request duration percentiles | No |

The two that *are* available are the two that were deliberately given
loud, greppable, single-line signatures. The rest need instrumentation
that does not exist.

**The minimum honest position for the cutover window**: a human tails the
log for the string `ERROR`, and the release is supervised rather than
monitored. Say that out loud in the go/no-go rather than implying
coverage that is not there.

## Ownership, Routing And Severity

Unfilled, and blocked on the same gap: routing an alert requires an alert.
Repository owner is the de facto and only responder.

When alerting does exist, the two signals worth paging on first are the
ones with no workaround — a broad authentication failure (every user
locked out) and `otp_delivery_failed` (nobody can register or reset a
password). Everything else can wait for business hours.

## Platform-Admin Web Surface (ADR-0015)

Concrete signals for the surface added in **D-160**, recorded here because the
change introduces a runtime dependency the application did not previously have.

| Failure | What an operator sees | Where |
|---|---|---|
| Session store unreachable | Every `/admin` request bounces to the login page and login never sticks; the API surfaces are unaffected because they stay stateless | Application log: `JdbcIndexedSessionRepository` / datasource errors on the primary datasource |
| `spring_session` missing or unmigrated | Startup succeeds, first admin login fails with a SQL error | Flyway history missing `V46`; application log at first `/admin/login` POST |
| Sessions accumulating | `spring_session` row count grows without bound | Spring Session's own cleanup job deletes expired rows on a schedule; a stuck job shows as rows with `expiry_time` in the past |
| Administrator deactivated but still active | Should be impossible: the session is revalidated per request | `PlatformAdminSessionRevalidationFilter`; regression coverage in `PlatformAdminWebSessionTest` |
| Administrator locked out by throttling | They report "invalid credentials" for a password they know is right | `platform_admin_audit_events` shows the `LOGIN_FAILED` run; `platform_admin_login_attempts` holds 8 rows inside the 15-minute window for their identifier. The lockout clears itself when the window passes, or immediately on a successful login |
| Throttle table growing | `platform_admin_login_attempts` row count climbing steadily | An unauthenticated caller can add a row per attempt with a fresh identifier. `PlatformAdminLoginAttemptCleanup` deletes rows past the window every 10 minutes on every worker; growth despite that means the scheduler is not running |

The surface performs no administrative action yet, so there is no
administrative-action audit signal to watch. When one is added, ADR-0015
prerequisite 10 requires the audit row to be written in the same transaction as
the action, which makes "action without audit row" a condition that cannot
occur rather than one to alert on.

| MFA encryption key missing or wrong | Enrolment and TOTP verification fail with "not configured" or a decrypt failure; login is unaffected until the surface demands a second factor | `app.platform-admin.mfa.encryption-key` unset, or rotated without re-encrypting. Seeds are unreadable without it — **losing this key loses every enrolled factor**, so it belongs in the same backup and custody regime as the database, held separately from it |
| MFA key rotated | Rows still carry the old `seed_key_version` | Re-encrypt those rows before retiring the old key; the version column exists so this can be done incrementally rather than all at once |

| Administrative actions refused as disabled | Operators see "Administrative actions are disabled on this deployment" | `app.platform-admin.actions.enabled` is false, which is the shipped default. It is turned on only after the legacy PHP admin surface is confirmed unreachable (ADR-0015 prerequisite 7, D-152) |
| A step-up approval minted but never spent | A `STEP_UP_APPROVED` audit row with no matching action row referencing it | Normal if an operator changed their mind; a run of them is worth looking at. Approvals expire after five minutes and are purged |
| Audit rows growing | `platform_admin_audit_events` grows and is never trimmed | Intended. Retention is indefinite by decision (D-161) — this table is the evidence the shared-password model never had. The purged tables are `platform_admin_login_attempts` and `platform_admin_step_up_approvals` |

**Capacity note:** one row per live admin session, in a population of
individually provisioned platform administrators (**F-26**). This is not a
volume signal; it is a correctness one.

## Open Questions

- Which signals are mandatory for MVP versus optional for later phases?
- Which alert-routing and paging tools will actually be used in the live
  operating model?
- What severity taxonomy will be adopted across release, incident, and support
  workflows?
- Which customer-visible workflows need dedicated health indicators rather
  than only infrastructure metrics?
- Which signals are required before a high-risk release can pass the release
  gate?

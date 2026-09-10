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

### Attendance-device receiver (D-164)

Emitted by `com.workin.devices` when `app.devices.ingest.enabled=true`;
design section 9 of `docs/superpowers/specs/2026-09-02-attendance-device-ingestion-design.md`.

- Metrics (Micrometer counters, tag `vendor`): `devices.punches.stored`,
  `devices.punches.duplicate`, `devices.punches.unmatched`,
  `devices.punches.malformed`, `devices.punches.rejected`,
  `devices.unclaimed.hits`, `devices.biometric.discarded`,
  `devices.stamp.rejected`, `devices.requests.rejected`,
  `devices.uploads.oversized` (second tag `table`), and
  `devices.uploads.discarded` (second tag `table`, drawn from a closed set so
  a caller cannot grow the registry).
- Logs, all carrying the serial: WARN on unmatched PINs, malformed lines,
  template lines discarded, unknown tables; INFO on command results; ERROR
  with the cause when the receiver fails (the device retries after its
  `ErrorDelay`, so an ERROR here repeats until fixed).
- Liveness is data, not a metric yet: `attendance_devices.last_seen_at` is
  advanced by every command poll (about every 10 seconds). "Device offline"
  is that column older than a threshold for an active device; until a gauge
  exists, a scheduled query is the alert source.
- Meaningful failure modes: a claimed device silent beyond the threshold
  (site network or power); `devices.unclaimed.hits` rising (a terminal
  pointed at the receiver and not yet claimed — or a probe); a sustained
  `devices.punches.unmatched` rate (PINs nobody bound, or a badge still in
  use after the employee left); any `devices.biometric.discarded` (a firmware
  ignoring `TransFlag`; record it in the inventory); any
  `devices.punches.rejected` (a row the database refused — it is acknowledged
  rather than retried, so this counter is the only trace); and
  `devices.stamp.rejected` or `devices.requests.rejected` above a trickle,
  which means something is sending values no terminal would send; and any
  `devices.uploads.oversized`, which is either a buffered reconnect larger
  than the record cap (raise it, and record the real batch size on the
  hardware checklist) or an attempt to amplify one request into many
  statements.

### Punch to attendance pairing (D-214)

`PunchPairingService` converts stored punches into `attendance` rows.

- **Nothing triggers a pass yet.** The engine and its rules are implemented
  and tested; no scheduler, endpoint or listener calls `pairCompany`. Until
  one exists, punches accumulate in `device_punches` with
  `processing_state = 'RECEIVED'` and no attendance is written — which is
  safe (the evidence is kept, and pairing is replayable by design) but is
  not a working feature. Choosing the trigger is a separate decision: how
  often, per company or across all, and what stops two passes overlapping.
- **The query that says whether it is keeping up**, once something does run
  it — the oldest unpaired punch is the lag:

  ```sql
  SELECT company_id, COUNT(*) AS waiting, MIN(punched_at_local) AS oldest
  FROM device_punches WHERE processing_state = 'RECEIVED'
  GROUP BY company_id;
  ```

- **A punch that never leaves `RECEIVED`** is the poison-row signal. A pass
  logs at ERROR with the punch id and moves on rather than stopping, so one
  unpairable row cannot strand the rest — but it also means the row is only
  visible in that query and in the log, never as a stalled pass.
- **`devices.punches.clock_skew_seconds`** is the one per-device signal here,
  a summary tagged by `vendor` and `serial`, recording the widest
  `received_at - punched_at` in each delivery (signed: negative means the
  terminal is behind this server). A drifting terminal reports perfectly valid
  timestamps, so nothing else catches it -- the punches are accepted and
  pairing places attendance on the wrong day. Watch the per-serial value, not
  an average across devices: one terminal two days out is invisible beside a
  hundred healthy ones. Beyond two days it also logs a WARN naming the serial.
  A large positive value right after an outage is normal -- that is a buffered
  batch, not drift -- so judge it on whether it persists.
- **`review_flag` is a work queue, not an error.** `RAPID_RECHECKIN` means
  legacy would have refused the check-in; a terminal cannot be refused, so a
  human decides. `DOUBLE_READ` is a debounced second read and needs nothing.
- **What an operator sees when the enum was not widened**: one line per pass,
  at ERROR, beginning `Not pairing: attendance.method does not accept 'device'`.
  Punches stay `RECEIVED` and the pass ends immediately. The fix is
  `slice_b_attendance_method.sql` — see `provisioning-phase1-tables.md`, and
  applying it is enough: the guard re-probes, so no restart is needed.

  This previously described "data-integrity errors naming the column", from
  before the guard existed. Those cannot occur: the guard runs first and
  returns, so the INSERT that would raise them is never attempted. An operator
  waiting for that message would wait forever, and the one signal that does
  appear was not written down anywhere.

## Source System

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
| Every login attempt shares one `client_key` | `platform_admin_login_attempts` shows one address for all of them | A proxy is in front and `server.forward-headers-strategy` is `none`, so every caller looks like the proxy and one guesser can lock out everyone. Set `FORWARD_HEADERS_STRATEGY=native` -- and only with the port closed to everything but the proxy (`running-the-backend.md`) |
| `client_key` values vary implausibly | Many distinct addresses, few real callers | The reverse: `native` with no proxy in front, so `X-Forwarded-For` is attacker-controlled and the miss budget is unspendable. Set it back to `none` until a proxy is the sole route (**R-049**) |
| Throttle table growing | `platform_admin_login_attempts` row count climbing steadily | An unauthenticated caller can add a row per attempt with a fresh identifier. `PlatformAdminLoginAttemptCleanup` deletes rows past the window every 10 minutes on every worker; growth despite that means the scheduler is not running |

The surface performs no administrative action yet, so there is no
administrative-action audit signal to watch. When one is added, ADR-0015
prerequisite 10 requires the audit row to be written in the same transaction as
the action, which makes "action without audit row" a condition that cannot
occur rather than one to alert on.

| Administrative actions refused as disabled | Operators see "Administrative actions are disabled on this deployment" | `app.platform-admin.actions.enabled` is false, which is the shipped default. It is turned on only after the legacy PHP admin surface is confirmed unreachable (ADR-0015 prerequisite 7, D-152) |
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

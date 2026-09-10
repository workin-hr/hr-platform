# ADR-0019: Performance Measurement Toolchain

## Metadata

| Field | Value |
|---|---|
| ADR ID | ADR-0019 |
| Title | Performance Measurement Toolchain |
| Status | Accepted |
| Date | 2026-09-10 |
| Owners | Solution Architect |
| Deciders | Repository owner |
| Related Issues | None |
| Supersedes | None |
| Superseded By | None |

## Context

ADR-0008 set the observability baseline at structured logs, correlation ids and
a health endpoint, and **deliberately deferred** the heavier stack -- its own
words -- to "a separate, later, evidence-informed decision", on the grounds
that adopting Prometheus/Grafana/Loki/Tempo "before real load and cost data
exists could add operational burden disproportionate to MVP needs". That
reasoning still holds for a production deployment. It does not hold for
measurement, and two facts made the gap concrete:

**The application maintains meters that nothing can read.** Actuator is on the
classpath, `micrometer-tracing-bridge-otel` is wired, and the device code emits
counters and a per-device clock-skew summary. No Prometheus registry was
present and every profile exposed only `health`, so those meters were
incremented and discarded -- the cost of maintaining them paid, none of the
benefit taken.

**Trace sampling was 1.0 in every profile**, exporting through
`opentelemetry-exporter-logging` -- i.e. a span for every request, into the
log. `docs/operations/monitoring-and-alerting.md` already flagged this as
"right for the local verification it was set up for and wrong for production".

There is also a specific, known performance failure mode in this codebase
rather than a generic one. It is a PHP-to-Java parity port of a data-heavy
application, and the shape has already bitten once: `LegacyPayslipService.enrich()`
drove a per-employee, per-day lookup and turned one page into "hundreds of
avoidable round trips per HTTP request" (D-114). That is a round-trip problem,
and a CPU profiler reports it as time spent in JDBC -- true, and useless.

## Decision

Adopt a measurement toolchain, in this order, and keep production deployment of
a monitoring stack deferred.

| Tool | Question it answers | Where it runs |
|---|---|---|
| **Micrometer Prometheus registry** | What is the JVM, the pool and the app doing? | Application, local + integration profiles |
| **Prometheus + Grafana** | What was it doing *while* the load ran? | `deploy/compose.observability.yaml`, local only |
| **k6** | Does it hold up, and did this change make it worse? | Container, host network, against the published port |
| **Query-count assertions** | Is the work per row constant? | JUnit, in the normal suite |
| **JFR, then async-profiler** | Where does the time actually go? | On demand, no dependency |

**k6 over Gatling and JMeter.** A single static binary with no JVM, scenarios
in JavaScript that diff cleanly in a repository, and an image that joins the
compose network. Gatling would introduce a second JVM into a measurement of the
first. JMeter's GUI-driven XML does not belong in version control.

**The Prometheus endpoint is exposed in `local` and `integration` only.**
Production keeps `health` alone. This chain does not authenticate `/actuator`
and `deploy/Caddyfile` proxies every path on `APP_DOMAIN`, so exposing it there
would publish JVM internals, every meter and every URI template to anyone who
asks. Turning it on needs either a management port the proxy does not forward
or an authenticated matcher -- a decision with its own evidence, not a config
flip.

**Trace sampling defaults to 0.05** (`APP_TRACE_SAMPLING`), and stays at 1.0 in
local and integration, which exist to be inspected.

**Load runs are local or nightly, never per-PR.** Shared GitHub runners are
noisy neighbours, so absolute latency from a hosted runner is meaningless, and
the Actions minutes are not free -- a live constraint here (D-223).

**Query counting is a test, not a dashboard.** `QueryCounter` is a JDK proxy
over `DataSource` in test scope; datasource-proxy and p6spy both do this well
and neither earns a dependency for counting `prepareStatement`. The assertion
that matters is not a fixed budget but whether the count grows with the size of
the result, which is what catches the D-114 shape before it ships.

## Alternatives Considered

- **Hosted APM (Datadog, New Relic).** Rejected: recurring per-host cost, and
  the owner has stated subscriptions are not currently possible (D-223).
- **Deploying Prometheus/Grafana to production now.** Rejected: that is exactly
  what ADR-0008 deferred, and nothing here supplies the load and cost evidence
  it asked for yet. This ADR produces that evidence; it does not pre-empt it.
- **Hibernate statistics for query counting.** Rejected: the legacy port is
  `JdbcTemplate` throughout, so Hibernate's counters see almost none of it.
- **Micrometer `@Timed` annotations everywhere.** Rejected for now: the HTTP
  server timings the registry provides already answer "which endpoint", and
  per-method timers are a decision to take once a specific method is suspected.

## Consequences

- One new runtime dependency (`micrometer-registry-prometheus`), on a registry
  the application was already feeding.
- A load run needs the local stack up; it is not a `./gradlew` target.
- Query budgets are ratchets and will need lowering when something gets faster.
  A ratchet never tightened is decoration.

## Risks

- A threshold set from one laptop reads as authoritative later. Mitigated by
  `perf/README.md` stating plainly that these are comparative, not a service
  level.
- The observability overlay could drift toward being deployed. Mitigated by
  keeping it out of `compose.prod.yaml` and saying why in the file itself.

## Validation Evidence

- `PrometheusEndpointTest` asserts the scrape serves JVM, GC and Hikari figures
  **and** that an application meter reaches it; verified red by removing the
  registry dependency, which answered 404.
- `PairingQueryBudgetTest` measured a pairing pass at **11 statements per
  punch** (89 across 8 punches), and 4x the punches at **3.7x** the statements
  -- linear, so no N+1, but 11/punch against a 5,000-record upload cap is where
  the ingestion work now points.
- The forbidden-file exclusion for `perf/scenarios/*.js` is scoped to one
  directory and one suffix, with a test asserting `.ts`, `package.json`,
  `perf/*.js` and `.js` elsewhere all still fail.
- The stack was **run**, not just configured: `/actuator/prometheus` serves 132
  metric lines including `hikaricp_connections` and `jvm_memory_used_bytes`,
  and Prometheus reports the `workin-backend` target `up`.
- **Client API baselined**: 22,417 requests at 20 VUs, p95 107ms, avg 33ms, 0
  failures. Attendance pages 1/10/25 are flat at 47/47/44ms, so pagination does
  not degrade with depth at the seed's volume.

All three surfaces are now baselined: client API **p95 107ms**, admin dashboard
**p95 213ms**, device ingestion **p95 159ms** at 50-record batches, none with a
single failed request. Two needed a run-time override to reach, neither of them
committed:

- **Admin dashboard** needs an `https` base URL. The session cookie is
  `Secure`; browsers and `curl` treat `http://127.0.0.1` as a secure context
  and send it anyway, k6 does not, so CSRF cannot validate and sign-in is 403.
  (This also corrected `running-the-backend-for-client-developers.md`, which
  claimed the dashboard never stays signed in on the local stack. It does, on
  `localhost`; it does not by LAN IP from another machine.)
- **Device ingestion** needs `app.devices.ingest.enabled` (default false) and
  `app.devices.ingest.host`. It is also blocked on a stale seed:
  `deploy/seed/dev-seed.sql` predates the Phase-1 device tables, so the stack
  reports `8 of 14 owned tables are MISSING` and claiming a device answers 500.
  Applying `phase1_extensions.sql` to the running database unblocks a
  measurement; **regenerating the seed remains outstanding**.

**The first full run found a trap, and then the second run corrected the
conclusion drawn from it.** Prometheus recorded `hikaricp_connections_active`
at 10 with `hikaricp_connections_pending` at 10 -- real saturation, while heap
peaked at 150MB, so neither memory nor GC is the constraint. That was first
written up as "the pool is the bottleneck, and its size is the first number to
change". Testing it did not support that:

| Pool | Throughput | p95 |
|---|---|---|
| 10 | 62.2 / 42.7 / 49.3 iter/s | 153 / 307 / 202 ms |
| 24 | 48.8 iter/s | 218 ms |

The three pool=10 rows are the same configuration minutes apart. Run-to-run
variance is +/-20% on throughput and +/-50% on p95, and pool=24 lands inside
it, so the comparison is **inconclusive** -- and queueing at a pool is what a
pool is for, not on its own evidence that a larger one would help. Pool sizing
needs an otherwise idle machine; these runs shared a laptop with four other
Docker stacks.

What the investigation did find is worth more than the number would have been:
**the pool was not configurable at all.** `LegacyPersistenceConfig` builds the
datasource by hand, because ADR-0017 excludes `DataSourceAutoConfiguration`
globally, and never set a pool size -- so it ran on HikariCP's default of 10.
`spring.datasource.hikari.connection-timeout` meanwhile sat in
`application.properties` looking authoritative while the builder hardcoded the
same value: those keys did nothing, and anyone tuning them would have got no
effect and no error. Both are now `app.legacy-db.*` properties with the
previous behaviour as the default, and `PrometheusEndpointTest` asserts the
property reaches the pool -- verified red by hardcoding it again.

## What the measurement then changed

The harness paid for itself immediately. A breakdown of the 89 statements
showed seven firing once per punch, two of which asked an unchanging question:

- `legacy_runtime_offset_history` was queried per punch for the offset in force
  at that instant. That table holds one row per offset change for the life of
  the system -- roughly two a year -- so a pass now reads it once and resolves
  in memory, the same shape `DeviceAssignmentTimeline` already used. The query
  disappears from the pass entirely.
- `branchPolicy` was read per punch. The claim is ordered by employee, so a
  per-pass memo turns it into one read per employee: 8 became 4 on the fixture.

**11 statements per punch became 9** (89 -> 77 total), and the growth ratio
stayed linear at 3.6x for 4x the punches. The budget test's ratchet moved with
it, so the gain cannot quietly be given back.

Both are pass-scoped, never fields on the singleton service, and the staleness
each introduces is bounded to one pass: an offset row appearing mid-pass has
`effective_from_utc` at about "now" while the pass resolves punches that have
already happened, and a branch edited mid-pass affects a review flag rather
than a payroll figure.

**Next measured target, deliberately not taken here**: the provenance
`UPDATE device_punches SET legacy_runtime_offset_seconds ...` is still its own
statement per punch and could fold into the disposition write that always
follows it -- worth about 1 of the remaining 9. The other per-punch reads
(`expected_daily_hours`, the shift assignment) live in the shared
PHP-parity calendar code, where a cache is a parity risk rather than a
refactor, so they are left alone.

## Open Questions

- Production scrape exposure: management port or authenticated matcher.
- Whether the 11-statements-per-punch pairing cost is worth reducing, and
  against what upload volume.
- Log aggregation (Loki) and trace collection (Tempo) remain deferred under
  ADR-0008; nothing here changes that.

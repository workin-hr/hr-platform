# Load Measurement

Everything here answers one question: **does a change make this slower, and
where.** Absolute numbers from a laptop are not a service level; the value is
in comparing two runs of the same scenario against the same data.

## Why k6

A single static binary with no JVM, scenarios in JavaScript, and a container
image that runs against the published port from the host network. Gatling would
bring a second JVM into a measurement of the first one; JMeter's GUI and XML
are a poor fit for something that has to live in a repository and be diffed.
Recorded in **ADR-0019**.

## Why this data matters

These scenarios run against the **sanitised production copy** the dev stack
already seeds -- 386 companies, 3,783 employees, 44,756 attendance rows, real
in shape and fake in content. Most load tests run against a handful of
fabricated rows and prove nothing about pagination, index selectivity or list
performance. This one does not have that problem, and that is the reason to
measure here rather than in CI.

## Running

```bash
cd deploy
docker compose -f compose.local.yaml -f compose.observability.yaml up -d --wait

cd ../perf
./run.sh client-api          # or: admin-dashboard, device-ingestion, all
```

Grafana is on <http://127.0.0.1:3000> and Prometheus on
<http://127.0.0.1:9090>, both loopback-only. Watch these while a run is in
flight -- the k6 summary tells you what the client saw, the scrape tells you
what the JVM was doing while it saw it:

| Question | Query |
|---|---|
| Was it the pool? | `hikaricp_connections_pending` |
| Was it GC? | `rate(jvm_gc_pause_seconds_sum[1m])` |
| Was it heap pressure? | `jvm_memory_used_bytes{area="heap"}` |
| Which endpoint? | `topk(5, rate(http_server_requests_seconds_sum[1m]))` |

## The harness refuses a target it cannot show is disposable

`run.sh` will not generate load against something that might not be yours to
load. It proceeds only if **both** hold:

1. **The host resolves to loopback** (`127.0.0.1`, `localhost` or `::1`). This
   is a statement about the URL you typed, not about where a stack binds --
   `compose.local.yaml` and `compose.dev.yaml` both publish on all interfaces on
   purpose. What it refuses is the class of targets named by a domain, which is
   how a deployment is reached.
2. **The target publishes the API description** at `/v3/api-docs/client-api`.
   This is what refuses a *loopback* URL that is not a disposable stack:
   `deploy/compose.remote-db.yaml` publishes to `127.0.0.1:8080` -- the same
   host and port this harness defaults to -- and its own header says it points
   at the production database. It pins springdoc off, and
   `scripts/check_remote_db_pins.py` keeps that pin in place.

To override, when you are certain:

```bash
PERF_TARGET_IS_DISPOSABLE=1 ./run.sh all
```

**What neither condition catches:** an `ssh -L 8080:localhost:8080` port-forward
to a stack that does publish the description -- the shared integration
environment. That reaches loopback and answers condition 2, and no probe of a
URL can tell the socket from the stack behind it.

The harness is not what protects you there. Sign-in happens once in `setup()`
and aborts the run on failure, so a wrong credential costs one attempt rather
than the hundreds a per-iteration sign-in threw.

That matters because the miss budget is charged to `web:` + `getRemoteAddr()`,
and for the stack this guard is about that is **one shared bucket**, not one per
person: the app is containerised, so a hit on a published port arrives from the
project network's gateway (`172.17.0.1` on the default bridge; a compose project
gets its own), not from `127.0.0.1`. And `compose.remote-db.yaml` never passes
`SERVER_FORWARD_HEADERS_STRATEGY`, so it is `none` there and anything through a
proxy carries the proxy's address. Eight misses in 15 minutes closes dashboard
sign-in for everyone using that path.

`deploy/e2e/run.sh` is the exception, and deliberately: it sets
`FORWARD_HEADERS_STRATEGY=native`, so on that stack `X-Forwarded-For` is honoured
and misses are charged per real client. `docs/operations/monitoring-and-alerting.md`
prescribes `native` as the fix wherever a proxy is in front. Charging per client address rather than
per account is what stops someone locking the administrator out from anywhere;
behind a proxy it does not separate one operator from another. Point it at a
stack you can throw away.

## Not in CI

Shared GitHub runners are noisy neighbours, so absolute latency from a hosted
runner means nothing, and the Actions minutes are not free. These are for a
local run before and after a change, or a nightly run on a machine that is
otherwise idle.

## What is measured, and what is not

Run on 2026-09-10 against the local stack. Recorded because "we have load
tests" and "we have measured this" are different claims.

| Surface | State | Detail |
|---|---|---|
| **Client API** | **Baselined** | 22,417 requests at 20 VUs, **p95 107ms**, avg 33ms, 0 failures. Attendance pages 1, 10 and 25 measured flat (47/47/44ms) -- no offset-scan degradation at this volume |
| **Admin dashboard** | **Needs re-measuring** | 1,810 requests at 5 VUs, **p95 213ms**, avg 83ms, 0 failures -- but that run signed in on every iteration, so three of its five requests were the login form and the POST. The scenario now signs in once in `setup()`; the number above is not comparable to a run made after 2026-09-11 |
| **Device ingestion** | **Baselined** | 12,634 requests at 20 VUs with 50-record batches, **p95 159ms**, avg 58ms, 0 failures |

Two of them needed a run-time override to reach at all, and neither override is
committed -- they belong on the command line of a measurement, not in a profile:

- **Admin dashboard.** The session cookie is `Secure`, and k6 -- unlike
  browsers and `curl` -- does not treat `http://127.0.0.1` as a secure context,
  so the session never returns on the POST and CSRF cannot validate. Either run
  `deploy/e2e/run.sh integration` -- the profile argument matters, `local` is
  the default and puts nothing on 8443 -- which brings up the TLS proxy and
  mints its certificate, then point at `https://127.0.0.1:8443` with
  `PERF_ADMIN_PASSWORD='e2e-verify-Pass123!'` (the constant that script fixes);
  or override
  `server.servlet.session.cookie.secure=false` for the run, which measures
  application cost without TLS handshake noise.
  **Not** `docker compose -f compose.local.yaml -f e2e/compose.proxy.yaml`, as
  this said until 2026-09-11: `compose.proxy.yaml` carries
  `name: workin-integration` and a later file's project name wins the merge, so
  that command runs the local stack's definitions inside the *integration*
  project -- recreating its containers and attaching the local database to
  `workin-integration_db-data`.
- **Device ingestion.** `app.devices.ingest.enabled` defaults to false, so
  `/iclock/**` does not exist; set it, and set `app.devices.ingest.host` to the
  host k6 calls. Separately, `deploy/seed/dev-seed.sql` **predates the Phase-1
  device tables** -- the stack starts reporting `8 of 14 owned tables are
  MISSING` and claiming a device answers 500. Applying `phase1_extensions.sql`
  to the running database unblocks a measurement; regenerating the seed is the
  real fix and is still outstanding.

## What the first full run found

**The pool was not configurable at all.** `LegacyPersistenceConfig` builds the
datasource by hand -- `DataSourceAutoConfiguration` is excluded globally
(ADR-0017) -- and never set a pool size, so it ran on HikariCP's default of 10.
Worse, `spring.datasource.hikari.connection-timeout` sat in
`application.properties` looking authoritative while the builder hardcoded the
same value: **those keys did nothing**, and anyone tuning them would have seen
no effect and no error. Both are now real properties
(`app.legacy-db.maximum-pool-size`, `app.legacy-db.connection-timeout-ms`) with
the previous behaviour as the default, and a test asserts the property reaches
the pool rather than being decorative.

**Whether 10 is the right number is still unanswered, and this machine cannot
answer it.** Prometheus did record `hikaricp_connections_active` at 10 with
`hikaricp_connections_pending` at 10, which is real saturation -- but queueing
at the pool is what a pool is *for*, and it is not on its own evidence that a
bigger one would help. Measured:

| Pool | Throughput | p95 |
|---|---|---|
| 10 | 62.2 / 42.7 / 49.3 iter/s | 153 / 307 / 202 ms |
| 24 | 48.8 iter/s | 218 ms |

The three pool=10 runs are the same configuration, minutes apart. **Run-to-run
variance is +/-20% on throughput and +/-50% on p95**, and the pool=24 result
falls inside it. The comparison is inconclusive, not negative.

### Re-measured on an idle machine (2026-09-11): still don't raise it

Repeated on a machine doing nothing else -- load average 1.0 of 12 cores, every
other container under 0.3% -- sweeping the pool with `APP_DB_MAX_POOL_SIZE`, one
`client-api` run each, and **pool=10 repeated last** so the same-configuration
spread is visible beside the between-configuration one:

| Pool | req/s | p95 | avg | `connections_pending` peak |
|---|---|---|---|---|
| 10 | 244.9 | 126.6 ms | 37.6 ms | 9 |
| 20 | 208.1 | 125.1 ms | 44.2 ms | 9 |
| 40 | 214.6 | 119.0 ms | 42.9 ms | **0** |
| 10 (repeat) | 198.7 | 132.3 ms | 46.3 ms | 8 |

**The two pool=10 runs differ by 23% on throughput -- more than any pair of
different configurations differs.** Every result between 10 and 40 sits inside
that band, so the honest reading is that the pool is not the constraint at this
volume and raising it buys nothing measurable. Keep the default at 10.

The one thing that does move monotonically is `hikaricp_connections_pending`:
9 at pool=10, 0 at pool=40. So a larger pool really does remove the queueing --
it just does not make anything faster, which is the evidence the earlier note
said was missing. The bottleneck is elsewhere (CPU peaked at 293% of one core
across the run, and the database is on the same laptop).

Two caveats that matter more than the numbers:

- **The heap here is nothing like production's.** `compose.local.yaml` sets no
  container memory limit, so `MaxRAMPercentage=75` in the Dockerfile applied to
  the host's 23 GiB and the JVM ran with a **17,792 MiB** heap ceiling.
  `compose.prod.yaml` and `compose.remote-db.yaml` set `memory: 1g`, which is a
  768 MiB ceiling -- about 23x smaller. Measured here: heap used peaked at 163
  MiB, 83 collections, 0.188 s total GC pause, **0.06% GC overhead**. That
  number is not transferable; under a 1 GiB cap the same run has real GC work to
  do. Set `APP_MEMORY_LIMIT` and add a limit block to the stack you measure on
  if you want a comparable figure.
- Resource ceiling for sizing a host: 20 VUs against this seed drew **293% of
  one core at peak, 153% mean**, 38 live threads, and 596 MiB RSS for the
  application container.

This is the reason the README says these numbers are comparative and not a
service level -- and it sharpens the rule: **pool tuning needs an otherwise
idle machine.** These runs shared a laptop with four other Docker stacks.
Nothing about the pool size should be changed on this evidence.

## The thresholds are ratchets

Each scenario carries a `thresholds` block set from a measured baseline. They
exist so a regression fails the run rather than being noticed later. When a
change makes something genuinely faster, lower the threshold in the same
commit -- a ratchet that is never tightened is decoration.

# Load Measurement

Everything here answers one question: **does a change make this slower, and
where.** Absolute numbers from a laptop are not a service level; the value is
in comparing two runs of the same scenario against the same data.

## Why k6

A single static binary with no JVM, scenarios in JavaScript, and a container
image that runs in the same compose network as the application. Gatling would
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
| **Admin dashboard** | **Blocked: needs HTTPS** | The session cookie is `Secure`. Browsers and `curl` treat `http://127.0.0.1` as a secure context and send it anyway; **k6 does not**, so the session from the GET never returns on the POST, CSRF cannot validate, and every sign-in is 403. Run it against `e2e/compose.proxy.yaml` on `https://127.0.0.1:8443` |
| **Device ingestion** | **Blocked: stale seed** | `deploy/seed/dev-seed.sql` predates the Phase-1 device tables. The stack starts and reports `8 of 14 owned tables are MISSING`, and claiming a device answers 500. The seed needs regenerating from a dump taken after those tables existed |

The two blocked surfaces have scenarios written and calibrated against the real
routes; neither has a baseline, and neither threshold is evidence of anything
yet.

## The thresholds are ratchets

Each scenario carries a `thresholds` block set from a measured baseline. They
exist so a regression fails the run rather than being noticed later. When a
change makes something genuinely faster, lower the threshold in the same
commit -- a ratchet that is never tightened is decoration.

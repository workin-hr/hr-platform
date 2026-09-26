# Attendance report benchmark (D-292)

What one request to each attendance report endpoint costs -- statements, time
in the database, time everywhere else, and peak heap -- on a production-shaped
roster, before and after D-292 made their reads set-based. Unlike the k6
scenarios next door (ADR-0019), this is not load: it is **one request at a
time, one JVM per case**, because the question is how a single report scales
with its roster and its range, and a second request in the same JVM would share
its warm-up and its garbage.

## What it runs against

- **`rpt356-db`**: `mariadb:11.8` with 4 CPUs and 2 GB, `performance_schema`
  on, the vendored legacy schema plus the Phase 1 extension DDL, and
  `bench.sh`'s `seed_sql`: one company of **500 employees with a punch on every
  working day of 2025** (148,675 attendance rows), Friday as its weekly rest,
  a shift change half-way through the year for half the roster, fifteen
  holidays, three approved leaves and two approved timed requests per employee,
  missing days, open punches and exception-only markers. Rosters of 10, 100 and
  500 are the same company filtered by branch 1, department 1, or nothing.
- **The application jar**, started by [`bench.py`](bench.py) the way the
  production container runs it: `-Xmx768m -XX:+ExitOnOutOfMemoryError`
  (`APP_MEMORY_LIMIT=1g` at `MaxRAMPercentage=75`), pinned to 4 CPUs with
  `taskset` -- the owner's VPS is 4 vCPU.
- **`rpt356-toxiproxy`** (optional): the database behind 53 ms of latency in
  each direction, ~106 ms per round trip -- production's measured distance to
  its database. Checked: one client session of 21 statements took 2.9 s
  through it against 0.35 s direct, ~120 ms more per statement with the
  connection handshake amortised in.

## What it records, per case

| Column | Meaning |
|---|---|
| `statements` | Every statement MariaDB executed for the request, from `performance_schema.events_statements_summary_by_digest` -- including the `SET time_zone` and offset read `LegacySessionDataSource` issues on every connection checkout (D-099), which `QueryCounter` in the test suite does not see |
| `db_ms` | Their summed server-side execution time |
| `total_ms` | The client's wall time for the request |
| `app_ms` | `total_ms - db_ms`: everything that is not statement execution, round trips included |
| `peak_heap_mb` | Heap occupancy (live plus uncollected garbage) at its highest during the request, after a full collection just before it |

Every case gets a warm-up request first (the same endpoint, one employee, one
week) so class loading and the first JIT pass are not billed to it.

## Running

```bash
cd perf/attendance-reports
./bench.sh db                                   # ~15 s
./bench.sh proxy                                # only for the latency runs
./bench.sh run ../../backend/build/libs/backend-0.0.1-SNAPSHOT.jar after
./bench.sh run <jar> after-106ms lat            # through the proxy
./bench.sh run <jar> after "" overall_report:500x366   # one case
./bench.sh explain                              # EXPLAIN ANALYZE of the reads
./bench.sh down
```

Results append to `results/results.csv` (ignored by git), with each case's GC
log beside it. The "before" jar is the same build from `8f7505a0`'s
`backend/src/main`. Needs Docker, Java 25, `taskset`, `jcmd` and PyJWT.

## Results

Recorded in **D-292** (`docs/bootstrap/decision-log-wave12r.md`), which is
where a later run that changes them should be written too.

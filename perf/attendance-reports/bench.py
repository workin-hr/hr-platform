#!/usr/bin/env python3
"""Attendance report benchmark (D-292): one request per case, one JVM per case.

For every case this starts the application jar under production's memory
limits (-Xmx768m -XX:+ExitOnOutOfMemoryError, 4 CPUs via taskset), sends one
small warm-up request, then the measured request, and records:

  statements  every statement MariaDB executed for the request
              (performance_schema digest COUNT_STAR, SET time_zone included)
  db_ms       their summed server-side execution time (SUM_TIMER_WAIT)
  total_ms    the client's wall time for the request (curl time_total)
  app_ms      total_ms - db_ms: everything that is not statement execution,
              which includes the round trips themselves
  peak_heap   the highest heap occupancy seen during the request, after a
              full collection just before it: the largest pre-collection size
              in the GC log while it ran, or the used heap straight after it,
              whichever is larger. Occupancy, so it includes garbage not yet
              collected -- the figure that meets -Xmx first

Usage: bench.py <jar> <label> <jdbc-port> <out.csv> [case ...]

The database is the rpt356-db container seeded by bench.sh; the jdbc port is
either the database's own (no latency) or the rpt356-toxiproxy listener.
"""
import csv
import datetime
import os
import re
import signal
import subprocess
import sys
import time
import urllib.request

import jwt  # PyJWT

SECRET = "bench-only-secret-not-used-anywhere-else-0000000000"
APP_PORT = 18356
DB_CONTAINER = "rpt356-db"

# Rosters by filter: branch 1 = 10 employees, department 1 = 100, none = 500.
ROSTERS = {10: "&branch_id=1", 100: "&department_id=1", 500: ""}
RANGES = {7: ("2025-03-03", "2025-03-09"), 31: ("2025-03-01", "2025-03-31"),
          93: ("2025-01-01", "2025-04-03"), 366: ("2025-01-01", "2026-01-01")}

ENDPOINTS = {
    "list_fill_days": lambda f, t: f"/apis/api/attendance/list.php?fill_days=1&date_from={f}&date_to={t}",
    "overall_report": lambda f, t: f"/apis/api/attendance/overall_report.php?from={f}&to={t}",
    "export_fingerprints": lambda f, t: f"/apis/api/attendance/export.php?type=fingerprints&from={f}&to={t}",
    "export_overall": lambda f, t: f"/apis/api/attendance/export.php?from={f}&to={t}",
}

DEFAULT_CASES = [
    f"{endpoint}:{employees}x{days}"
    for endpoint in ENDPOINTS
    for employees, days in ((10, 31), (100, 31), (500, 31),
                            (500, 93 if endpoint == "export_fingerprints" else 366))
]


def sql(statement):
    out = subprocess.run(
        ["docker", "exec", DB_CONTAINER, "mariadb", "-uroot", "-pbench", "-N", "-B", "-e", statement],
        check=True, capture_output=True, text=True)
    return out.stdout.strip()


def token():
    now = int(time.time())
    return jwt.encode({"sub": "1", "sid": "bench", "jti": str(now), "membership_id": 1, "tenant_id": 1,
                       "role": "company_admin", "token_version": 1, "iss": "workin-backend",
                       "aud": ["workin-clients"], "iat": now, "exp": now + 3600},
                      SECRET, algorithm="HS256")


def get(path, timeout):
    request = urllib.request.Request(f"http://127.0.0.1:{APP_PORT}{path}",
                                     headers={"Authorization": f"Bearer {token()}", "Accept-Language": "en"})
    started = time.monotonic()
    with urllib.request.urlopen(request, timeout=timeout) as response:
        body = response.read()
        status = response.status
    return status, len(body), (time.monotonic() - started) * 1000


def start_app(jar, jdbc_port, gc_log):
    env = dict(os.environ,
               JWT_SECRET=SECRET,
               LEGACY_DB_JDBC_URL=f"jdbc:mariadb://127.0.0.1:{jdbc_port}/workin",
               LEGACY_DB_USERNAME="root", LEGACY_DB_PASSWORD="bench")
    command = ["taskset", "-c", "0-3", "java", "-Xmx768m", "-XX:+ExitOnOutOfMemoryError",
               f"-Xlog:gc:file={gc_log}", "-jar", jar, f"--server.port={APP_PORT}",
               "--logging.level.root=WARN", "--management.tracing.sampling.probability=0"]
    process = subprocess.Popen(command, env=env, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    deadline = time.monotonic() + 180
    while time.monotonic() < deadline:
        if process.poll() is not None:
            raise RuntimeError("application exited during start-up")
        try:
            urllib.request.urlopen(f"http://127.0.0.1:{APP_PORT}/actuator/health", timeout=2).read()
            return process
        except Exception:
            time.sleep(1)
    process.kill()
    raise RuntimeError("application did not become healthy")


def heap_used_mb(pid):
    out = subprocess.run(["jcmd", str(pid), "GC.heap_info"], capture_output=True, text=True).stdout
    match = re.search(r"garbage-first heap.*?used (\d+)K", out, re.S)
    return int(match.group(1)) / 1024 if match else None


def peak_from_gc_log(gc_log, after_line):
    peak = 0.0
    with open(gc_log) as handle:
        for index, line in enumerate(handle):
            if index < after_line:
                continue
            match = re.search(r"(\d+)M->(\d+)M\(\d+M\)", line)
            if match:
                peak = max(peak, float(match.group(1)))
    return peak


def line_count(path):
    with open(path) as handle:
        return sum(1 for _ in handle)


def measure(jar, jdbc_port, case, workdir):
    endpoint, size = case.split(":")
    employees, days = (int(value) for value in size.split("x"))
    first, last = RANGES[days]
    path = ENDPOINTS[endpoint](first, last) + ROSTERS[employees]
    gc_log = os.path.join(workdir, f"gc-{endpoint}-{size}.log")
    process = start_app(jar, jdbc_port, gc_log)
    try:
        # Warm-up: the same endpoint for one employee and one week, so class
        # loading and the first JIT pass are not billed to the case.
        warm_first, warm_last = RANGES[7]
        get(ENDPOINTS[endpoint](warm_first, warm_last) + "&employee_id=2", 600)
        # A full collection first, so the occupancy measured below is this
        # request's and not the warm-up's garbage.
        subprocess.run(["jcmd", str(process.pid), "GC.run"], capture_output=True, check=True)
        sql("TRUNCATE TABLE performance_schema.events_statements_summary_by_digest")
        gc_lines = line_count(gc_log)
        try:
            status, size_bytes, total_ms = get(path, 7200)
            outcome = str(status)
        except Exception as ex:  # noqa: BLE001 - an OOM exit is a result, not a crash of the bench
            status, size_bytes, total_ms = None, 0, None
            outcome = "exited" if process.poll() is not None else f"error: {ex}"
        used = heap_used_mb(process.pid) if process.poll() is None else None
        counts = sql("SELECT COALESCE(SUM(COUNT_STAR), 0), COALESCE(SUM(SUM_TIMER_WAIT), 0) "
                     "FROM performance_schema.events_statements_summary_by_digest "
                     "WHERE SCHEMA_NAME = 'workin'").split("\t")
        statements = int(counts[0])
        db_ms = int(counts[1]) / 1e9
        peak = max(peak_from_gc_log(gc_log, gc_lines), used or 0)
        return {
            "case": case, "endpoint": endpoint, "employees": employees, "days": days, "outcome": outcome,
            "statements": statements, "db_ms": round(db_ms, 1),
            "total_ms": None if total_ms is None else round(total_ms, 1),
            "app_ms": None if total_ms is None else round(total_ms - db_ms, 1),
            "peak_heap_mb": round(peak, 1), "bytes": size_bytes,
        }
    finally:
        if process.poll() is None:
            process.send_signal(signal.SIGTERM)
            try:
                process.wait(30)
            except subprocess.TimeoutExpired:
                process.kill()


def main():
    jar, label, jdbc_port, out_csv = sys.argv[1:5]
    cases = sys.argv[5:] or DEFAULT_CASES
    workdir = os.path.join(os.path.dirname(os.path.abspath(out_csv)), f"gc-{label}")
    os.makedirs(workdir, exist_ok=True)
    fields = ["label", "jdbc_port", "case", "endpoint", "employees", "days", "outcome", "statements", "db_ms",
              "total_ms", "app_ms", "peak_heap_mb", "bytes", "measured_at"]
    new_file = not os.path.exists(out_csv)
    with open(out_csv, "a", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=fields)
        if new_file:
            writer.writeheader()
        for case in cases:
            row = measure(jar, jdbc_port, case, workdir)
            row.update(label=label, jdbc_port=jdbc_port,
                       measured_at=datetime.datetime.now().isoformat(timespec="seconds"))
            writer.writerow(row)
            handle.flush()
            print(row, flush=True)


if __name__ == "__main__":
    main()

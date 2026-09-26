#!/usr/bin/env bash
# Attendance report benchmark (D-292). See README.md in this directory.
#
#   bench.sh db                         start rpt356-db (MariaDB 11.8, 4 CPUs) and seed it
#                                       (EMPLOYEES=3000 for the larger roster; default 500)
#   bench.sh proxy                      start rpt356-toxiproxy: ~106 ms per round trip in front of it
#   bench.sh run <jar> <label> [lat]    measure every case; lat = use the proxy
#   bench.sh explain                    EXPLAIN the report reads against the seeded data
#   bench.sh down                       remove both containers
#
# Everything it creates is named rpt356-* and bound to 127.0.0.1.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
RES="$REPO/backend/src/main/resources/db/phase1-mysql"
SCHEMA="$REPO/backend/src/test/resources/legacy/mysql_workin.schema.sql"
DB=rpt356-db
PROXY=rpt356-toxiproxy
NET=rpt356-net
DB_PORT=13356
PROXY_PORT=13357
PROXY_API=18474
OUT="${BENCH_OUT:-$HERE/results}"

db_sql() { docker exec -i "$DB" mariadb -uroot -pbench "$@"; }

# The synthetic data, as SQL. Kept here rather than in a .sql file because the
# repository's governance check admits SQL only under backend/.
seed_sql() {
  cat <<'SQL'
-- Synthetic data for the attendance report benchmark (D-292).
--
-- One active company of 500 employees with a punch on every working day of
-- 2025, the shape #356 was measured on, plus the things the per-day rules
-- branch on so the set-based reads are not measured against an empty table:
-- Friday as the company's weekly rest, a shift change half-way through the year
-- for half the roster, fifteen holidays, approved paid leave, approved timed
-- requests, missing days, open punches and exception-only markers.
--
-- Rosters of 10, 100 and 500 are asked for with filters on the same company:
-- branch 1 holds employees 1-10, department 1 holds employees 1-100, and the
-- unfiltered report is all 500.
--
-- Applied after mysql_workin.schema.sql, phase1_extensions.sql and
-- slice_b_attendance_method.sql, as the test suite applies them. Uses
-- MariaDB's Sequence engine (seq_1_to_N), which is compiled into mariadb:11.8.
-- Deterministic: every "random" choice is a function of the ids.

SET SESSION sql_mode = '';

INSERT INTO companies (id, company_name, phone, password_hash, status)
VALUES (1, 'Bench Co', '+201000000001', 'x', 'active');

INSERT INTO branches (id, company_id, name) VALUES (1, 1, 'Branch One'), (2, 1, 'Branch Two');
INSERT INTO departments (id, company_id, name) VALUES (1, 1, 'Department One'), (2, 1, 'Department Two');
INSERT INTO job_titles (id, company_id, name, work_hours) VALUES (1, 1, 'Staff', 8.00);
INSERT INTO exception_types (id, company_id, name) VALUES (1, 1, 'Sick leave');
INSERT INTO request_types (id, company_id, name, counts_as_paid_leave) VALUES (1, 1, 'Annual', 1), (2, 1, 'Mission', 0);

INSERT INTO shifts (id, company_id, name, start_time, end_time, days_off)
VALUES (1, 1, 'Day', '09:00:00', '17:00:00', ''), (2, 1, 'Late', '11:00:00', '19:00:00', 'friday');

INSERT INTO setting_definitions (id, setting_key) VALUES (1, 'weekly_off_days');
INSERT INTO setting_allowed_values (id, setting_definition_id, value, sort_order) VALUES (1, 1, 'friday', 1);
INSERT INTO company_settings (id, company_id, setting_definition_id) VALUES (1, 1, 1);
INSERT INTO company_setting_values (id, company_setting_id, setting_allowed_value_id) VALUES (1, 1, 1);

-- Employee 1 is the company admin every request authenticates as.
INSERT INTO employees (id, company_id, branch_id, department_id, job_title_id, employee_code, first_name,
    last_name, phone, role, is_active, join_request_status)
SELECT seq, 1, IF(seq <= 10, 1, 2), IF(seq <= 100, 1, 2), 1, CAST(seq AS CHAR), CONCAT('Emp', seq), 'Bench',
    CONCAT('+2011', LPAD(seq, 8, '0')), IF(seq = 1, 'company_admin', 'employee'), 1, 'accepted'
FROM seq_1_to_500;

INSERT INTO employee_shift_assignments (employee_id, shift_id, effective_from)
SELECT seq, 1, '2024-01-01' FROM seq_1_to_500;
INSERT INTO employee_shift_assignments (employee_id, shift_id, effective_from)
SELECT seq, 2, '2025-07-01' FROM seq_1_to_500 WHERE seq % 2 = 0;

INSERT INTO company_official_holidays (company_id, holiday_date, name)
SELECT 1, DATE_ADD('2025-01-07', INTERVAL seq * 23 DAY), CONCAT('Holiday ', seq) FROM seq_0_to_14;

-- A punch on every working day: ~5% of days missing, ~2% left open, ~1% an
-- exception-only marker; the rest 8h +- a few minutes.
INSERT INTO attendance (employee_id, check_in, check_out, exception_type_id, method)
SELECT e.seq,
    CASE WHEN (e.seq * 31 + d.seq * 17) % 100 = 7 THEN TIMESTAMP(DATE_ADD('2025-01-01', INTERVAL d.seq DAY))
         ELSE TIMESTAMP(DATE_ADD('2025-01-01', INTERVAL d.seq DAY), SEC_TO_TIME(32400 + ((e.seq + d.seq) % 40) * 60))
    END,
    CASE WHEN (e.seq * 31 + d.seq * 17) % 100 IN (7, 3, 5) THEN NULL
         ELSE TIMESTAMP(DATE_ADD('2025-01-01', INTERVAL d.seq DAY), SEC_TO_TIME(61200 + ((e.seq * 7 + d.seq) % 50) * 60))
    END,
    CASE WHEN (e.seq * 31 + d.seq * 17) % 100 = 7 THEN 1 ELSE NULL END,
    'app'
FROM seq_1_to_500 e
JOIN seq_0_to_364 d
WHERE DAYOFWEEK(DATE_ADD('2025-01-01', INTERVAL d.seq DAY)) <> 6
  AND (e.seq * 13 + d.seq * 29) % 100 >= 5;

-- Three approved paid leaves and two approved timed requests per employee.
INSERT INTO requests (employee_id, request_type_id, from_date, to_date, status)
SELECT e.seq, 1, DATE_ADD('2025-01-01', INTERVAL (e.seq * 7 + k.seq * 97) % 360 DAY),
    DATE_ADD('2025-01-01', INTERVAL (e.seq * 7 + k.seq * 97) % 360 + 2 DAY), 'approved'
FROM seq_1_to_500 e JOIN seq_0_to_2 k;
INSERT INTO requests (employee_id, request_type_id, from_date, to_date, from_time, to_time, status)
SELECT e.seq, 2, DATE_ADD('2025-01-01', INTERVAL (e.seq * 11 + k.seq * 131) % 360 DAY),
    DATE_ADD('2025-01-01', INTERVAL (e.seq * 11 + k.seq * 131) % 360 DAY), '10:00:00', '14:00:00', 'approved'
FROM seq_1_to_500 e JOIN seq_0_to_1 k;

ANALYZE TABLE attendance, requests, employee_shift_assignments, company_official_holidays, employees;
SQL
}

# The report reads, EXPLAINed against the seeded data.
explain_sql() {
  cat <<'SQL'
-- The report reads, EXPLAINed against the seeded data (D-292).
-- ANALYZE FORMAT=JSON runs the statement and reports rows read per table and
-- time, which is what decides whether an index would help.

-- 1. Before: one employee's attendance, the shape every per-employee read had.
ANALYZE SELECT a.id, a.check_in FROM attendance a
WHERE a.employee_id = 250 AND DATE(a.check_in) >= '2025-03-01' AND DATE(a.check_in) <= '2025-03-31'
ORDER BY a.check_in ASC;

-- 2. After, as written with DATE(): the whole roster's history is read.
ANALYZE SELECT a.employee_id, a.id, a.check_in FROM attendance a
LEFT JOIN exception_types et ON et.id = a.exception_type_id
WHERE a.employee_id IN (SELECT id FROM employees WHERE company_id = 1)
  AND DATE(a.check_in) >= '2025-02-22' AND DATE(a.check_in) <= '2025-03-31'
ORDER BY a.employee_id, a.check_in, a.id;

-- 3. After, as shipped: the range is on the column, so the index bounds it.
ANALYZE SELECT a.employee_id, a.id, a.check_in FROM attendance a
LEFT JOIN exception_types et ON et.id = a.exception_type_id
WHERE a.employee_id IN (SELECT id FROM employees WHERE company_id = 1)
  AND a.check_in >= '2025-02-22' AND a.check_in < '2025-04-01'
ORDER BY a.employee_id, a.check_in, a.id;

-- 4. Present details, grouped in SQL.
ANALYZE SELECT a.employee_id, DATE(a.check_in) AS present_date, MAX(a.exception_type_id), MAX(et.name)
FROM attendance AS a LEFT JOIN exception_types AS et ON et.id = a.exception_type_id
WHERE a.employee_id IN (SELECT id FROM employees WHERE company_id = 1)
  AND a.check_in >= '2025-03-01' AND a.check_in < '2025-04-01'
GROUP BY a.employee_id, DATE(a.check_in) ORDER BY a.employee_id, present_date;

-- 5. Shift assignments, approved leave, timed requests and leave days for the roster.
ANALYZE SELECT esa.employee_id, esa.shift_id, esa.effective_from, s.* FROM employee_shift_assignments esa
INNER JOIN shifts s ON s.id = esa.shift_id
WHERE esa.employee_id IN (SELECT id FROM employees WHERE company_id = 1) AND esa.effective_from <= '2025-04-08'
ORDER BY esa.employee_id, esa.effective_from, esa.id;

ANALYZE SELECT r.employee_id, r.from_date, r.to_date FROM requests r
INNER JOIN request_types t ON t.id = r.request_type_id
WHERE r.employee_id IN (SELECT id FROM employees WHERE company_id = 1)
  AND r.status = 'approved' AND t.counts_as_paid_leave = 1
  AND r.from_date <= '2025-03-31' AND r.to_date >= '2025-02-15';

ANALYZE SELECT id, employee_id, from_date, to_date, from_time, to_time FROM requests
WHERE employee_id IN (SELECT id FROM employees WHERE company_id = 1) AND status = 'approved'
  AND from_date <= '2025-04-08' AND to_date >= '2025-02-15'
  AND from_time IS NOT NULL AND TRIM(from_time) <> '' AND to_time IS NOT NULL AND TRIM(to_time) <> '';
SQL
}

case "${1:-}" in
db)
  docker network inspect "$NET" >/dev/null 2>&1 || docker network create "$NET" >/dev/null
  docker run -d --name "$DB" --network "$NET" --cpus=4 --memory=2g -p "127.0.0.1:$DB_PORT:3306" \
    -e MARIADB_ROOT_PASSWORD=bench -e MARIADB_DATABASE=workin mariadb:11.8 \
    --performance-schema=ON --character-set-server=utf8mb4 --collation-server=utf8mb4_unicode_ci \
    --innodb-buffer-pool-size=1G >/dev/null
  for _ in $(seq 1 60); do
    db_sql -e 'SELECT 1' >/dev/null 2>&1 && break
    sleep 1
  done
  db_sql -e 'SELECT 1' >/dev/null
  { echo "SET SESSION sql_mode='';"; cat "$SCHEMA"; } | db_sql workin
  { echo "SET SESSION sql_mode='';"; cat "$RES/phase1_extensions.sql" "$RES/slice_b_attendance_method.sql"; } | db_sql workin
  # EMPLOYEES=3000 bench.sh db seeds a larger company; the 10/100 filters are unchanged.
  seed_sql | sed "s/seq_1_to_500/seq_1_to_${EMPLOYEES:-500}/g" | db_sql workin
  db_sql workin -N -e "SELECT 'employees', COUNT(*) FROM employees UNION ALL SELECT 'attendance', COUNT(*) FROM attendance UNION ALL SELECT 'requests', COUNT(*) FROM requests"
  ;;
proxy)
  # 53 ms each way on the database's traffic: one statement's round trip is
  # ~106 ms, production's measured distance to its database.
  docker run -d --name "$PROXY" --network "$NET" -p "127.0.0.1:$PROXY_PORT:3307" -p "127.0.0.1:$PROXY_API:8474" \
    ghcr.io/shopify/toxiproxy:2.9.0 >/dev/null
  for _ in $(seq 1 30); do
    curl -fs "http://127.0.0.1:$PROXY_API/version" >/dev/null 2>&1 && break
    sleep 1
  done
  curl -fsS -X POST "http://127.0.0.1:$PROXY_API/proxies" \
    -d "{\"name\":\"db\",\"listen\":\"0.0.0.0:3307\",\"upstream\":\"$DB:3306\"}" >/dev/null
  for stream in upstream downstream; do
    curl -fsS -X POST "http://127.0.0.1:$PROXY_API/proxies/db/toxics" \
      -d "{\"name\":\"lat_$stream\",\"type\":\"latency\",\"stream\":\"$stream\",\"attributes\":{\"latency\":53}}" >/dev/null
  done
  ;;
run)
  jar="$2"; label="$3"; port=$DB_PORT
  [[ "${4:-}" == lat ]] && port=$PROXY_PORT
  shift 4 2>/dev/null || shift 3
  mkdir -p "$OUT"
  python3 "$HERE/bench.py" "$jar" "$label" "$port" "$OUT/results.csv" "$@"
  ;;
explain)
  # The reads bind the roster as a literal IN list, so the plan is taken for one.
  ids="($(seq -s, 1 500))"
  explain_sql | sed "s/(SELECT id FROM employees WHERE company_id = 1)/$ids/" | db_sql workin
  ;;
down)
  docker rm -f "$DB" "$PROXY" >/dev/null 2>&1 || true
  docker network rm "$NET" >/dev/null 2>&1 || true
  ;;
*)
  sed -n '2,10p' "$0"; exit 2 ;;
esac

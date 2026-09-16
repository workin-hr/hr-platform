#!/usr/bin/env bash
# The devices lab: a local stack with the terminal receiver and the agent
# endpoint on, lab devices allocated, and every simulator run against it.
#
#   scripts/devices-lab.sh up         build and start the lab stack, wait until healthy
#   scripts/devices-lab.sh seed       allocate lab terminals, issue a lab agent token
#   scripts/devices-lab.sh simulate   run every simulator and the agent, then summarise
#   scripts/devices-lab.sh status     punches per terminal and how they arrived
#   scripts/devices-lab.sh down       stop the lab (add --wipe to delete its database)
#
# Never touches production: the stack is the sanitised seed on its own ports and
# its own compose project, and every write below goes to that database.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PROJECT=workin-devices-lab
LAB_DIR="$ROOT/devices-agent/lab"
HTTPS_PORT="${LAB_HTTPS_PORT:-18443}"
HTTP_PORT="${LAB_HTTP_PORT:-18080}"
ZK_PORT="${LAB_ZK_PORT:-14370}"
HIK_PORT="${LAB_HIK_PORT:-18190}"
PYTHON="${PYTHON:-python3}"

export APP_DOMAIN=localhost APP_DEVICES_DOMAIN=devices.localhost TLS_EMAIL=lab@example.invalid

compose() {
  docker compose -p "$PROJECT" -f "$ROOT/deploy/compose.local.yaml" -f "$ROOT/deploy/compose.tls.yaml" \
    -f "$ROOT/deploy/compose.devices-lab.yaml" "$@"
}

sql() {
  compose exec -T db mariadb -uworkin -pworkin-local workin --batch --skip-column-names -e "$1"
}

agent() {
  # exec, so a backgrounded call's $! is the Python process itself and the trap
  # below stops the simulator rather than only the subshell that started it.
  (cd "$ROOT/devices-agent" && exec "$PYTHON" -m workin_devices "$@")
}

wait_healthy() {
  local container status deadline=$((SECONDS + 900))
  container="$(compose ps -q app)"
  while true; do
    status="$(docker inspect -f '{{.State.Health.Status}}' "$container" 2>/dev/null || echo missing)"
    [ "$status" = healthy ] && return 0
    if [ "$SECONDS" -ge "$deadline" ]; then
      echo "STOPPED: the app was not healthy after 15 minutes (last: $status); see: docker compose -p $PROJECT logs app" >&2
      return 1
    fi
    sleep 5
  done
}

cmd_up() {
  compose up -d --build
  echo "waiting for the seed to load and the app to become healthy (first start: several minutes) ..."
  wait_healthy
  echo "lab is up:"
  echo "  dashboard  https://localhost:$HTTPS_PORT/admin   (password: devpassword; accept the local certificate)"
  echo "  receiver   http://127.0.0.1:$HTTP_PORT  with Host: devices.localhost"
}

cmd_seed() {
  mkdir -p "$LAB_DIR"
  # A branch from the seed with at least five active employees. The seed's
  # employee codes are sanitised to `E000123`, which no terminal can hold, so
  # PINs 1001-1005 are bound to five of them -- the same binding HR makes for a
  # real terminal (employee_device_identities).
  local row company branch pins employees
  row="$(sql "SELECT e.company_id, e.branch_id, GROUP_CONCAT(e.id ORDER BY e.id SEPARATOR ',')
              FROM employees e JOIN branches b ON b.id = e.branch_id AND b.is_active = 1
              JOIN companies c ON c.id = e.company_id AND c.status = 'active'
              WHERE e.is_active = 1
              GROUP BY e.company_id, e.branch_id HAVING COUNT(*) >= 5
              ORDER BY COUNT(*) LIMIT 1")"
  if [ -z "$row" ]; then
    echo "STOPPED: no branch in the lab database has five active employees" >&2
    return 1
  fi
  company="$(cut -f1 <<<"$row")"
  branch="$(cut -f2 <<<"$row")"
  employees="$(cut -f3 <<<"$row" | cut -d, -f1-5)"
  pins="1001,1002,1003,1004,1005"
  local pin=1001 employee
  for employee in ${employees//,/ }; do
    sql "INSERT IGNORE INTO employee_device_identities (company_id, employee_id, pin, source, created_at, updated_at)
         VALUES ($company, $employee, '$pin', 'MANUAL', NOW(), NOW());"
    pin=$((pin + 1))
  done
  echo "lab company $company, branch $branch, PINs $pins"

  local serial vendor
  for entry in SIM-PUSH-001:zkteco SIM-ZK4370-001:zkteco SIM-HIK-001:hikvision SIM-USB-001:zkteco; do
    serial="${entry%%:*}"
    vendor="${entry##*:}"
    sql "INSERT IGNORE INTO attendance_devices (company_id, branch_id, vendor, serial_number, name, device_time_zone,
           is_active, created_at, updated_at)
         VALUES ($company, $branch, '$vendor', '$serial', 'Lab $serial', 'Africa/Cairo', 1, NOW(), NOW());
         INSERT INTO device_assignment_history (device_id, company_id, branch_id, device_time_zone, effective_from_utc, created_at)
         SELECT d.id, d.company_id, d.branch_id, d.device_time_zone, UTC_TIMESTAMP() - INTERVAL 90 DAY, NOW()
           FROM attendance_devices d
          WHERE d.serial_number = '$serial'
            AND NOT EXISTS (SELECT 1 FROM device_assignment_history h WHERE h.device_id = d.id);"
  done

  local token hash
  token="wda_$(head -c 32 /dev/urandom | base64 | tr '+/' '-_' | tr -d '=\n' | cut -c1-43)"
  hash="$(printf '%s' "$token" | sha256sum | cut -d' ' -f1)"
  sql "UPDATE device_agents SET is_active = 0 WHERE name = 'Lab agent';
       INSERT INTO device_agents (company_id, name, token_sha256, token_hint, is_active, created_at, updated_at)
       VALUES ($company, 'Lab agent', '$hash', '${token: -4}', 1, NOW(), NOW());"
  (umask 077 && printf '%s\n' "$token" > "$LAB_DIR/agent.token")
  (umask 077 && printf 'lab-hik-password\n' > "$LAB_DIR/hik.password")
  cat > "$LAB_DIR/agent.toml" <<TOML
# Generated by scripts/devices-lab.sh seed. Lab only: loopback, self-signed edge.
server_url = "https://localhost:$HTTPS_PORT"
token_file = "agent.token"
spool_path = "spool.sqlite3"
insecure_skip_tls_verify = true
poll_interval_seconds = 30
batch_size = 500

[[devices]]
serial = "SIM-ZK4370-001"
kind = "zk"
host = "127.0.0.1"
port = $ZK_PORT

[[devices]]
serial = "SIM-HIK-001"
kind = "hikvision"
host = "127.0.0.1"
port = $HIK_PORT
username = "admin"
password_file = "hik.password"
window_hours = 240
TOML
  printf '%s\n' "$pins" > "$LAB_DIR/pins"
  echo "allocated SIM-PUSH-001, SIM-ZK4370-001, SIM-HIK-001, SIM-USB-001; agent config -> $LAB_DIR/agent.toml"
}

cmd_simulate() {
  [ -f "$LAB_DIR/pins" ] || { echo "STOPPED: run seed first" >&2; return 1; }
  local pins sim_pids=()
  pins="$(cat "$LAB_DIR/pins")"
  trap 'kill "${sim_pids[@]}" 2>/dev/null || true' EXIT

  echo "== 1. a push terminal (ZKTeco ADMS), registered"
  agent sim-push --server "http://127.0.0.1:$HTTP_PORT" --host-header devices.localhost \
    --serial SIM-PUSH-001 --pins "$pins" --scenario all || echo "   (a FAIL above is a finding: read it)"

  echo "== 2. a push terminal nobody has allocated: appears in the dashboard's waiting list"
  agent sim-push --server "http://127.0.0.1:$HTTP_PORT" --host-header devices.localhost \
    --serial "SIM-PUSH-NEW-$RANDOM" --pins "$pins" --scenario unregistered || echo "   (a FAIL above is a finding: read it)"

  echo "== 3. an older terminal on 4370 and a Hikvision, read by the agent"
  agent sim-zk --port "$ZK_PORT" --serial SIM-ZK4370-001 --pins "$pins" --days 14 &
  sim_pids+=("$!")
  agent sim-hik --port "$HIK_PORT" --serial SIM-HIK-001 --password-file lab/hik.password --pins "$pins" --days 5 &
  sim_pids+=("$!")
  sleep 3
  agent doctor --config lab/agent.toml
  agent once --config lab/agent.toml
  echo "   second pass (must store nothing new):"
  agent once --config lab/agent.toml

  echo "== 4. a USB export imported through the agent"
  agent sim-usb --out lab/1_attlog.dat --pins "$pins" --days 5
  agent import-usb --config lab/agent.toml --serial SIM-USB-001 --file lab/1_attlog.dat

  cmd_status
  echo "open https://localhost:$HTTPS_PORT/admin/devices to see the same in the dashboard"
}

cmd_status() {
  echo "punches per terminal:"
  sql "SELECT d.serial_number, p.delivered_via, p.processing_state, COUNT(*)
         FROM device_punches p JOIN attendance_devices d ON d.id = p.device_id
        GROUP BY d.serial_number, p.delivered_via, p.processing_state ORDER BY 1, 2, 3" | column -t
  echo "waiting for allocation:"
  sql "SELECT serial_number, device_type, hit_count, last_seen_at FROM unclaimed_device_sightings ORDER BY last_seen_at DESC" | column -t
  echo "agents:"
  sql "SELECT name, agent_version, last_seen_at, is_active FROM device_agents ORDER BY id" | column -t
}

cmd_down() {
  if [ "${1:-}" = "--wipe" ]; then
    compose down -v
  else
    compose down
  fi
}

case "${1:-}" in
  up) cmd_up ;;
  seed) cmd_seed ;;
  simulate) cmd_simulate ;;
  status) cmd_status ;;
  down) shift; cmd_down "${1:-}" ;;
  *) sed -n '2,10p' "$0"; exit 2 ;;
esac

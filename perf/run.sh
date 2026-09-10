#!/usr/bin/env bash
# Runs a k6 scenario against the local stack, inside the compose network.
#
# In the network rather than from the host so the measurement is of the
# application, not of Docker's port forwarding -- and so `app:8080` resolves
# the same way Prometheus resolves it.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
SCENARIO="${1:-}"
NETWORK="${PERF_NETWORK:-workin-local_default}"

usage() {
  echo "usage: $0 <client-api|admin-dashboard|device-ingestion|all>" >&2
  echo >&2
  echo "The stack must be up first:" >&2
  echo "  cd deploy && docker compose -f compose.local.yaml \\" >&2
  echo "      -f compose.observability.yaml up -d --wait" >&2
  exit 2
}

[ -n "$SCENARIO" ] || usage

if ! docker network inspect "$NETWORK" >/dev/null 2>&1; then
  echo "no compose network '$NETWORK'." >&2
  echo "Start the stack first, or set PERF_NETWORK to the right name:" >&2
  docker network ls --filter name=workin --format '  {{.Name}}' >&2
  exit 1
fi

run_one() {
  local name="$1"
  local file="$HERE/scenarios/$name.js"
  [ -f "$file" ] || { echo "no scenario '$name'" >&2; usage; }

  echo "=== $name ==="
  # --quiet keeps the progress bar out of a captured log; the end-of-run
  # summary is the part worth keeping.
  docker run --rm -i \
    --network "$NETWORK" \
    -v "$HERE/scenarios:/scenarios:ro" \
    -e "BASE_URL=${BASE_URL:-http://app:8080}" \
    -e "PERF_PHONE=${PERF_PHONE:-}" \
    -e "PERF_PASSWORD=${PERF_PASSWORD:-}" \
    -e "PERF_ADMIN_USER=${PERF_ADMIN_USER:-}" \
    -e "PERF_ADMIN_PASSWORD=${PERF_ADMIN_PASSWORD:-}" \
    -e "PERF_SERIAL=${PERF_SERIAL:-}" \
    -e "PERF_BATCH=${PERF_BATCH:-}" \
    grafana/k6:0.55.0 run --quiet "/scenarios/$name.js"
}

if [ "$SCENARIO" = "all" ]; then
  # Sequentially, never together: three scenarios at once measure each other.
  for name in client-api admin-dashboard device-ingestion; do
    run_one "$name"
  done
else
  run_one "$SCENARIO"
fi

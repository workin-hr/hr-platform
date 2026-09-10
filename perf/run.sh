#!/usr/bin/env bash
# Runs a k6 scenario against a local stack, from the host network.
#
# On the HOST network, against the published port. The admin dashboard's
# session cookie is `Secure` and k6 -- unlike browsers and curl -- does not
# treat 127.0.0.1 as a secure context, so from inside the compose network that
# cookie is dropped and the scenario measures a login page. One base URL for
# all three surfaces beats one exception. The cost is Docker's port forwarding
# in the path, which is constant across runs and so does not affect a
# comparison. (`--network host` reaching host loopback is Linux-only.)
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
SCENARIO="${1:-}"
APP_PORT="${PERF_APP_PORT:-8080}"

usage() {
  echo "usage: $0 <client-api|admin-dashboard|device-ingestion|all>" >&2
  echo >&2
  echo "The stack must be up first:" >&2
  echo "  cd deploy && docker compose -f compose.local.yaml \\" >&2
  echo "      -f compose.observability.yaml up -d --wait" >&2
  exit 2
}

[ -n "$SCENARIO" ] || usage

BASE_URL="${BASE_URL:-http://127.0.0.1:${APP_PORT}}"

# The admin scenario's documented target is https://127.0.0.1:8443 with a
# self-signed certificate, so a bare `curl -fsS` fails the precheck before k6
# (which sets insecureSkipTLSVerify) ever starts -- a gate stricter than the
# thing it gates, whose only workaround trains the operator to set the
# disposability escape hatch by reflex.
# Anchored on the delimiter: an unanchored `https://localhost*` also matches
# https://localhost-staging.corp, which would disable certificate verification
# against a remote host.
case "$BASE_URL" in
  https://127.0.0.1|https://127.0.0.1[:/]*|https://localhost|https://localhost[:/]*)
    INSECURE=1 ;;
  *) INSECURE="" ;;
esac

# An explicit 200, never "curl did not error". `curl -f` only fails on >= 400,
# and the edge answers 308 on every plain-HTTP path -- so a redirect read as
# success on both probes below, and the disposability guard passed against a
# target where the API description does not exist at all. --max-redirs 0 keeps
# the answer being about THIS url.
probe_status() {  # $1=path -> HTTP status, or 000
  curl -sS -o /dev/null -w '%{http_code}' --max-redirs 0 \
    ${INSECURE:+-k} "${BASE_URL}$1" 2>/dev/null || echo 000
}

if [ "$(probe_status /actuator/health)" != "200" ]; then
  echo "nothing healthy at ${BASE_URL}." >&2
  echo "Start the stack first, or set PERF_APP_PORT / BASE_URL:" >&2
  echo "  cd deploy && docker compose -f compose.local.yaml \\" >&2
  echo "      -f compose.observability.yaml up -d --wait" >&2
  exit 1
fi

# REFUSE ANYTHING THAT IS NOT A DISPOSABLE STACK.
#
# `deploy/compose.remote-db.yaml` publishes 127.0.0.1:8080 -- the same host and
# default port this harness assumes -- and its own header says it points at the
# production database. `./run.sh all` against it would throw thousands of failed
# `admin` sign-ins at production: PlatformAdminLoginThrottle allows 8 per 15
# minutes per identifier, so the real platform administrator is locked out and
# the attempts table takes the junk. A load generator must not be one
# environment variable away from that.
#
# The marker was the published API description, borrowed from
# deployment-shape.spec.js. That was wrong for the same reason the exposure bug
# was: that marker is keyed to the PROFILE NAME (`PROFILE !== 'prod'`), and
# compose.remote-db.yaml runs profile `local`, so the guard passed on exactly
# the stack it exists to refuse. compose.remote-db.yaml now pins springdoc off
# regardless of profile, which restores the marker's meaning -- and that pin,
# not the profile, is what this relies on.
# TWO independent conditions, not one inference.
#
# Every version of this guard has been a single inference from one observable
# property of the target, and every one has had a bypass: the marker was
# profile-keyed and `remote-db` runs profile `local`; then a 308 from the edge
# read as success. A second condition that has to fail at the same time is
# worth more than a better first one.
#
# The target must be on loopback. Every disposable stack in this repository
# publishes to 127.0.0.1 and a production deployment is reached by its domain
# through the edge, so a hostname alone refuses the whole class -- whatever the
# target chooses to serve, and regardless of what the edge does to the status
# code.
case "$BASE_URL" in
  http://127.0.0.1[:/]*|http://127.0.0.1|https://127.0.0.1[:/]*|https://127.0.0.1\
  |http://localhost[:/]*|http://localhost|https://localhost[:/]*|https://localhost)
    on_loopback=1 ;;
  *) on_loopback=0 ;;
esac

if [ "${PERF_TARGET_IS_DISPOSABLE:-0}" != "1" ] && [ "$on_loopback" != "1" ]; then
  echo "refusing: ${BASE_URL} is not on loopback." >&2
  echo "  Every disposable stack here publishes to 127.0.0.1; a deployment is reached" >&2
  echo "  by its domain. A load run against one locks the platform administrator out" >&2
  echo "  and writes junk into its database." >&2
  echo "  Set PERF_TARGET_IS_DISPOSABLE=1 only if you are certain this target is." >&2
  exit 1
fi

if [ "${PERF_TARGET_IS_DISPOSABLE:-0}" != "1" ]; then
  # 200 AND an OpenAPI document. A 3xx, a portal splash page or an error page
  # that happens to return 200 must not read as "this is a disposable stack".
  description="$(curl -sS --max-redirs 0 ${INSECURE:+-k} \
    "${BASE_URL}/v3/api-docs/client-api" 2>/dev/null || true)"
  if [ "$(probe_status /v3/api-docs/client-api)" != "200" ] \
      || ! printf '%s' "$description" | grep -q '"openapi"'; then
    echo "refusing: ${BASE_URL} does not publish the API description, so it does not" >&2
    echo "  look like a local or integration stack. compose.remote-db.yaml publishes the" >&2
    echo "  same port and points at the production database; a load run there locks the" >&2
    echo "  platform administrator out and writes junk into it." >&2
    echo "  Set PERF_TARGET_IS_DISPOSABLE=1 only if you are certain this target is." >&2
    exit 1
  fi
fi

run_one() {
  local name="$1"
  local file="$HERE/scenarios/$name.js"
  [ -f "$file" ] || { echo "no scenario '$name'" >&2; usage; }

  echo "=== $name ==="
  # --quiet keeps the progress bar out of a captured log; the end-of-run
  # summary is the part worth keeping.
  # --network host, not the compose network. The admin dashboard's session
  # cookie is `Secure`, and only localhost/127.0.0.1 count as a secure context
  # over plain HTTP -- from inside the compose network that cookie is dropped
  # and the scenario measures a login page instead of a dashboard. One base URL
  # for all three surfaces beats one exception.
  docker run --rm -i \
    --network host \
    -v "$HERE/scenarios:/scenarios:ro" \
    -e "BASE_URL=$BASE_URL" \
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

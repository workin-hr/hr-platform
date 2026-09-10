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

# Resolve scheme and host ONCE, instead of pattern-matching the whole URL.
# A glob anchored on the delimiter still matches inside the authority's
# USERINFO: `https://localhost:8443@prod.corp/` matched `https://localhost[:/]*`,
# so the guard read a remote host as loopback -- and on the https form it also
# turned certificate verification off against that host. The real host is
# whatever follows the last `@`.
url_scheme() {
  case "$BASE_URL" in
    *://*) printf '%s' "${BASE_URL%%://*}" | tr '[:upper:]' '[:lower:]' ;;
    *) printf 'http' ;;
  esac
}

url_host() {
  local rest="${BASE_URL#*://}"
  rest="${rest%%[/?#]*}"   # the authority ends at the first of / ? #
  rest="${rest##*@}"       # drop userinfo
  case "$rest" in
    \[*) rest="${rest#\[}"; rest="${rest%%\]*}" ;;  # [::1]:8443
    *) rest="${rest%%:*}" ;;
  esac
  printf '%s' "$rest" | tr '[:upper:]' '[:lower:]'
}

SCHEME="$(url_scheme)"
HOST="$(url_host)"

case "$HOST" in
  127.0.0.1|localhost|::1) ON_LOOPBACK=1 ;;
  *) ON_LOOPBACK=0 ;;
esac

# The admin scenario's documented target is https://127.0.0.1:8443 with a
# self-signed certificate, so a bare `curl -fsS` fails the precheck before k6
# (which sets insecureSkipTLSVerify) ever starts -- a gate stricter than the
# thing it gates, whose only workaround trains the operator to set the
# disposability escape hatch by reflex.
# Bound to the resolved host: skipping verification against anything but
# loopback is a trust decision, not a local convenience.
if [ "$SCHEME" = "https" ] && [ "$ON_LOOPBACK" = 1 ]; then
  INSECURE=1
else
  INSECURE=""
fi

# REFUSE ANYTHING THAT IS NOT A DISPOSABLE STACK.
#
# `deploy/compose.remote-db.yaml` publishes 127.0.0.1:8080 -- the same host and
# default port this harness assumes -- and its own header says it points at the
# production database. A load run there would put read load on production and,
# before the sign-in was moved into setup(), threw thousands of failed `admin`
# attempts at it. A load generator must not be one environment variable away
# from that.
#
# TWO conditions, and they refuse different things.
#
# 1. The host must be loopback. This is a statement about the URL the operator
#    typed, NOT about where a stack binds -- compose.local.yaml and
#    compose.dev.yaml both publish on all interfaces on purpose (a phone on the
#    same Wi-Fi has to reach the dev stack). What it refuses is the whole class
#    of targets named by a domain, which is how a deployment is reached.
#
# 2. The target must publish the API description. This is what refuses a
#    LOOPBACK URL that is not a disposable stack: compose.remote-db.yaml
#    publishes to 127.0.0.1 and so satisfies condition 1 by construction, and
#    an ssh -L port-forward puts anything at all on loopback. Both remote-db
#    and a production deployment pin springdoc off, so this is the condition
#    that catches them.
#
# Neither is sufficient alone and neither subsumes the other. What NEITHER
# catches is a port-forward to a stack that does publish the description -- the
# shared integration environment. See perf/README.md; a URL cannot distinguish
# the socket from the stack behind it, and pretending otherwise is how the
# previous three versions of this guard failed.
if [ "${PERF_TARGET_IS_DISPOSABLE:-0}" != "1" ] && [ "$ON_LOOPBACK" != "1" ]; then
  echo "refusing: ${BASE_URL} resolves to host '${HOST}', which is not loopback." >&2
  echo "  A deployment is reached by its domain. A load run against one puts load on" >&2
  echo "  it and writes junk into its database." >&2
  echo "  Set PERF_TARGET_IS_DISPOSABLE=1 only if you are certain this target is." >&2
  exit 1
fi

# An explicit 200, never "curl did not error". `curl -f` only fails on >= 400,
# and the edge answers 308 on every plain-HTTP path -- so a redirect read as
# success on both probes below, and the disposability guard passed against a
# target where the API description does not exist at all. --max-redirs 0 keeps
# the answer being about THIS url.
# Timeouts because the interesting failure is not "refused" but "silent": a
# host behind a firewall that drops SYN rather than refusing hangs this script
# with no output at all.
probe_status() {  # $1=path -> HTTP status, or 000
  curl -sS -o /dev/null -w '%{http_code}' --max-redirs 0 \
    --connect-timeout 5 --max-time 15 \
    ${INSECURE:+-k} "${BASE_URL}$1" 2>/dev/null || echo 000
}

if [ "$(probe_status /actuator/health)" != "200" ]; then
  echo "nothing healthy at ${BASE_URL}." >&2
  echo "Start the stack first, or set PERF_APP_PORT / BASE_URL:" >&2
  echo "  cd deploy && docker compose -f compose.local.yaml \\" >&2
  echo "      -f compose.observability.yaml up -d --wait" >&2
  exit 1
fi


if [ "${PERF_TARGET_IS_DISPOSABLE:-0}" != "1" ]; then
  # 200 AND an OpenAPI document. A 3xx, a portal splash page or an error page
  # that happens to return 200 must not read as "this is a disposable stack".
  description="$(curl -sS --max-redirs 0 --connect-timeout 5 --max-time 15 \
    ${INSECURE:+-k} "${BASE_URL}/v3/api-docs/client-api" 2>/dev/null || true)"
  if [ "$(probe_status /v3/api-docs/client-api)" != "200" ] \
      || ! printf '%s' "$description" | grep -q '"openapi"'; then
    echo "refusing: ${BASE_URL} does not publish the API description, so it does not" >&2
    echo "  look like a local or integration stack. compose.remote-db.yaml publishes the" >&2
    echo "  same port and points at the production database; a load run there puts load" >&2
    echo "  on production. scripts/check_remote_db_pins.py keeps that pin in place." >&2
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

#!/usr/bin/env bash
# Launch the UNMODIFIED desktop client against a local backend.
#   $1 = backend port behind the TLS terminator (18081 Java, 18080 PHP)
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
. "$HERE/env.sh"
export DISPLAY="${DISPLAY:-:1}"
BACKEND_PORT="${1:-18081}"

pkill -f 'bundle/workin_desktop' 2>/dev/null || true
pkill -f 'tls-proxy.py' 2>/dev/null || true
sleep 1
nohup python3 "$HERE/tls-proxy.py" 8443 127.0.0.1 "$BACKEND_PORT" > /tmp/tls-proxy.log 2>&1 &
sleep 2

# The app's own log is the evidence: HttpHelper debugPrints every ENDPOINT,
# QUERY, BODY and Response, so the log records exactly which calls the UI made.
export LD_PRELOAD="$WORKIN_SHIM"
exec "$HERE/workin_desktop/build/linux/x64/debug/bundle/workin_desktop"

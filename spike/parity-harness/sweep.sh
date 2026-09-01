#!/usr/bin/env bash
# Sweep every endpoint the Flutter clients actually call, in the EXACT URL form
# they use (no .php), against PHP and Java on the same database. Unauthenticated
# on purpose: the question here is whether the route is reachable and answers
# the same way, not what an authorised caller sees.
set -uo pipefail
PHP_BASE=http://localhost:18080/apis/api
JAVA_BASE=http://localhost:18081/apis/api
printf '%-42s %-6s %-6s %s\n' ENDPOINT PHP JAVA VERDICT
same=0; diff=0; unreachable=0
while read -r ep; do
  [ -n "$ep" ] || continue
  p=$(curl -s -o /dev/null -m 10 -w "%{http_code}" "$PHP_BASE/$ep")
  j=$(curl -s -o /dev/null -m 10 -w "%{http_code}" "$JAVA_BASE/$ep")
  # A transport failure is curl's 000, and 000 == 000 would otherwise be
  # counted as agreement -- letting two stacks that never started certify
  # perfect parity, which is the strongest-looking possible result from a
  # validation that never ran.
  if [ "$p" = 000 ] || [ "$j" = 000 ]; then
    v=UNREACHABLE; unreachable=$((unreachable+1))
  elif [ "$p" = "$j" ]; then
    v=match; same=$((same+1))
  else
    v=DIFFERENT; diff=$((diff+1))
  fi
  printf '%-42s %-6s %-6s %s\n' "$ep" "$p" "$j" "$v"
done < client-endpoints.txt
echo
echo "matched=$same differing=$diff unreachable=$unreachable"
if [ "$unreachable" -gt 0 ]; then
  echo "REFUSING to report parity: $unreachable endpoint(s) unreachable on one or both stacks." >&2
  echo "A 000 is curl failing to connect, not an answer. Bring both stacks up and re-run." >&2
  exit 2
fi

#!/usr/bin/env bash
# Control run: identical sweep, but appending .php to the Java request only.
# If Java matches PHP here and not in the client form, the suffix is the cause.
set -uo pipefail
same=0; diff=0; unreachable=0
while read -r ep; do
  [ -n "$ep" ] || continue
  p=$(curl -s -o /dev/null -m 10 -w "%{http_code}" "http://localhost:18080/apis/api/$ep")
  j=$(curl -s -o /dev/null -m 10 -w "%{http_code}" "http://localhost:18081/apis/api/$ep.php")
  # 000 is curl failing to connect. Two of them are equal, so without this the
  # control reports all 190 matching with neither stack running -- and this
  # sweep exists to attribute the routing defect, so a false match here would
  # misattribute it.
  if [ "$p" = 000 ] || [ "$j" = 000 ]; then
    unreachable=$((unreachable+1)); printf '%-42s UNREACHABLE php=%s java=%s\n' "$ep" "$p" "$j"
  elif [ "$p" = "$j" ]; then same=$((same+1))
  else diff=$((diff+1)); printf '%-42s PHP=%s JAVA.php=%s\n' "$ep" "$p" "$j"; fi
done < client-endpoints.txt
echo "matched=$same differing=$diff unreachable=$unreachable"
if [ "$unreachable" -gt 0 ]; then
  echo "REFUSING to report a control result: $unreachable endpoint(s) unreachable." >&2
  exit 2
fi

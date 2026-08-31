#!/usr/bin/env bash
# Control run: identical sweep, but appending .php to the Java request only.
# If Java matches PHP here and not in the client form, the suffix is the cause.
set -uo pipefail
same=0; diff=0
while read -r ep; do
  [ -n "$ep" ] || continue
  p=$(curl -s -o /dev/null -m 10 -w "%{http_code}" "http://localhost:18080/apis/api/$ep")
  j=$(curl -s -o /dev/null -m 10 -w "%{http_code}" "http://localhost:18081/apis/api/$ep.php")
  if [ "$p" = "$j" ]; then same=$((same+1)); else diff=$((diff+1)); printf '%-42s PHP=%s JAVA.php=%s\n' "$ep" "$p" "$j"; fi
done < client-endpoints.txt
echo "matched=$same differing=$diff"

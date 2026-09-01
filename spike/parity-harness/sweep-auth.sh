#!/usr/bin/env bash
# Level 3: same token, same database, both stacks -- diff the JSON bodies.
# GET only: a POST would mutate the shared database and the second stack would
# then legitimately see different data, which proves nothing.
set -uo pipefail
TOKEN=$(cat .php-token)
same=0; diff=0; err=0; binary=0
: > auth-diffs.txt
while read -r ep; do
  [ -n "$ep" ] || continue
  pcode=$(curl -s -m 15 -o /tmp/p.json -w "%{http_code}" -H "Authorization: Bearer $TOKEN" "http://localhost:18080/apis/api/$ep")
  jcode=$(curl -s -m 15 -o /tmp/j.json -w "%{http_code}" -H "Authorization: Bearer $TOKEN" "http://localhost:18081/apis/api/$ep")
  if [ "$pcode" != "200" ] || [ "$jcode" != "200" ]; then err=$((err+1)); continue; fi
  # Canonicalise: key order is not part of the contract, values are.
  pn=$(python3 -c "import json,sys;print(json.dumps(json.load(open('/tmp/p.json')),sort_keys=True,ensure_ascii=False))" 2>/dev/null)
  jn=$(python3 -c "import json,sys;print(json.dumps(json.load(open('/tmp/j.json')),sort_keys=True,ensure_ascii=False))" 2>/dev/null)
  # A body that is not JSON -- attendance/export and payslips/export answer an
  # authenticated GET with an XLSX workbook -- made json.load fail for BOTH,
  # leaving two empty strings that compared equal. Those were counted as
  # identical JSON without a single byte being compared. They are their own
  # bucket now: not compared, and never counted as agreement. Comparing the
  # workbooks needs a reader-level comparison (D-085), not a byte diff, since
  # a zip carries its own timestamps.
  if [ -z "$pn" ] || [ -z "$jn" ]; then
    binary=$((binary+1))
    { echo "### $ep -- NOT COMPARED (non-JSON body: php=${#pn} java=${#jn} chars parsed)"; } >> auth-diffs.txt
    continue
  fi
  if [ "$pn" = "$jn" ]; then
    same=$((same+1))
  else
    diff=$((diff+1))
    { echo "### $ep"; echo "PHP  len=${#pn}"; echo "JAVA len=${#jn}"; } >> auth-diffs.txt
  fi
done < client-endpoints.txt
echo "identical=$same  differing=$diff  not-compared-non-json=$binary  not-200-on-both=$err"

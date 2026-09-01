#!/usr/bin/env bash
# Level 3: same token, same database, both stacks -- diff the JSON bodies.
# GET only: a POST would mutate the shared database and the second stack would
# then legitimately see different data, which proves nothing.
set -uo pipefail
# The endpoint list and token are overridable so the same comparison logic can
# run against a parameterised list (resolve-params.sh) and a different seeded
# employee, without a second copy of this script drifting from this one.
ENDPOINTS=${ENDPOINTS:-client-endpoints.txt}
TOKEN_FILE=${TOKEN_FILE:-.php-token}
DIFFS=${DIFFS:-auth-diffs.txt}
TOKEN=$(cat "$TOKEN_FILE")

# Divergences the repository has already decided to keep. Each names the
# decision that accepted it -- a bare allowlist would let a real regression be
# silenced by adding a line, so the reason is the point, not the entry.
accepted_divergence() {
  # Keyed on the endpoint AND the exact status pair. Keying on the endpoint
  # alone would accept ANY future mismatch there -- a Java regression to 500
  # would be filed under D-087 and never reported.
  case "$1:$2:$3" in
    company_official_holidays/list:403:200)
      # D-087: PHP applies require_company_settings_access() ONLY to
      # COMPANY_ADMIN/HR, so an EMPLOYEE lists holidays freely while an HR user
      # without the permission is refused the same data. Deliberately removed;
      # Java answers 200 where PHP answers 403 for those two roles.
      echo "D-087"; return 0 ;;
  esac
  return 1
}
same=0; diff=0; err=0; binary=0; accepted=0; unreachable=0
: > "$DIFFS"
while read -r ep; do
  [ -n "$ep" ] || continue
  pcode=$(curl -s -m 15 -o /tmp/p.json -w "%{http_code}" -H "Authorization: Bearer $TOKEN" "http://localhost:18080/apis/api/$ep")
  jcode=$(curl -s -m 15 -o /tmp/j.json -w "%{http_code}" -H "Authorization: Bearer $TOKEN" "http://localhost:18081/apis/api/$ep")
  # A one-sided failure is a DIFFERENCE, not an exclusion. Putting 200-vs-401
  # in the "not 200 on both" bucket hid a genuine authenticated status
  # regression among 144 entries the document describes as POST-only or
  # parameter-dependent, while the summary understated the differences.
  # 000 is curl failing to connect. Equal failures would otherwise land in
  # `err` and the run would finish "differing=0" with neither stack up -- the
  # same hole the other three sweeps had.
  if [ "$pcode" = 000 ] || [ "$jcode" = 000 ]; then
    unreachable=$((unreachable+1))
    printf '%-42s php=%s java=%s UNREACHABLE\n' "$ep" "$pcode" "$jcode"
    continue
  fi
  if [ "$pcode" != "$jcode" ]; then
    # An accepted divergence is not a finding. Without this the same known
    # difference reappears every run and people learn to ignore the number,
    # which is worse than not reporting it. Each entry must name the decision
    # that accepted it, so "expected" is auditable rather than asserted.
    if reason=$(accepted_divergence "${ep%%\?*}" "$pcode" "$jcode"); then
      accepted=$((accepted+1))
      printf '%-42s php=%s java=%s ACCEPTED (%s)\n' "$ep" "$pcode" "$jcode" "$reason"
    else
      diff=$((diff+1))
      { echo "### $ep -- STATUS DIFFERS php=$pcode java=$jcode"; } >> "$DIFFS"
      printf '%-42s php=%s java=%s STATUS-DIFFERS\n' "$ep" "$pcode" "$jcode"
    fi
    continue
  fi
  if [ "$pcode" != "200" ]; then err=$((err+1)); continue; fi
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
    # Only the known workbook routes are exempt. Treating EVERY parse failure
    # as "expected binary" would swallow a real defect: a JSON endpoint
    # returning a 200 HTML error page on one stack, or malformed bodies on
    # both, would increment `binary` and read as an accepted exclusion.
    case "${ep%%\?*}" in
      attendance/export|payslips/export|employees/template_excel|leave_balances/template_excel) ;;
      *)
        diff=$((diff+1))
        { echo "### $ep -- UNEXPECTED NON-JSON php=${#pn} java=${#jn} chars parsed"; } >> "$DIFFS"
        printf '%-42s php=%s java=%s UNEXPECTED-NON-JSON\n' "$ep" "$pcode" "$jcode"
        continue ;;
    esac
    binary=$((binary+1))
    { echo "### $ep -- NOT COMPARED (non-JSON body: php=${#pn} java=${#jn} chars parsed)"; } >> "$DIFFS"
    continue
  fi
  if [ "$pn" = "$jn" ]; then
    same=$((same+1))
  else
    diff=$((diff+1))
    { echo "### $ep"; echo "PHP  len=${#pn}"; echo "JAVA len=${#jn}"; } >> "$DIFFS"
  fi
done < "$ENDPOINTS"
echo "identical=$same  differing=$diff  accepted-divergences=$accepted  not-compared-non-json=$binary  not-200-on-both=$err  unreachable=$unreachable"
if [ "$unreachable" -gt 0 ]; then
  echo "REFUSING to report parity: $unreachable endpoint(s) unreachable." >&2
  exit 2
fi
[ "$diff" -gt 0 ] && exit 1
exit 0

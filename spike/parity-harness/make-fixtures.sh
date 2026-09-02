#!/usr/bin/env bash
# Build the multipart fixtures.
#
# Generated rather than committed: the two spreadsheets are the application's
# OWN template output, fetched from PHP's template_excel endpoints, which is the
# standard D-085 holds these readers to -- a hand-built workbook would test the
# harness's idea of the format rather than the format. The punch log is built
# here because legacy ships no template for it, and it is keyed to an
# employee_code that exists in the seeded snapshot, so it cannot be a static
# file either.
#
# Requires the stack up and seeded (docker compose up -d db php; ./seed-two.sh).
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
PHP=${PHP:-http://localhost:18080/apis/api}
DB=${DB:-parity-harness-db-1}
mkdir -p "$HERE/fixtures"

token=$(curl -s -X POST "$PHP/auth/login_employee" -H 'Content-Type: application/json' \
  -d '{"phone":"+201999000002","password":"harness-only-Pass123!"}' \
  | python3 -c "import sys,json;print(json.load(sys.stdin)['data']['token'])")
[ -n "$token" ] || { echo "FATAL: could not log in; is the stack seeded?" >&2; exit 2; }

for t in employees leave_balances; do
  curl -s -H "Authorization: Bearer $token" "$PHP/$t/template_excel" -o "$HERE/fixtures/$t-template.xlsx"
  type=$(file -b --mime-type "$HERE/fixtures/$t-template.xlsx")
  case "$type" in
    application/vnd.openxmlformats-officedocument.spreadsheetml.sheet|application/zip) ;;
    *) echo "FATAL: $t-template.xlsx is $type, not a workbook -- the endpoint returned an error page." >&2; exit 3 ;;
  esac
  echo "  fixtures/$t-template.xlsx  ($(stat -c%s "$HERE/fixtures/$t-template.xlsx") bytes, $type)"
done

python3 "$HERE/make-fixtures.py" "$HERE" "$DB"

# ---------------------------------------------------------------------------
# The import_bulk bodies: the analyzer's OWN output, replayed.
#
# Both import endpoints take `rows` in a JSON body -- not a file -- and the
# rows are what the client got back from analyze_excel and posted on. Building
# them here by hand would test this script's idea of the analyzer's output
# rather than the analyzer's output, so they are captured from a live call.
#
# Captured from PHP, deliberately: PHP is the reference, and a body captured
# from the port would let a Java analyzer defect define the input both stacks
# are then judged on. The same captured body is sent to both.
#
# What is captured differs per endpoint, because the two importers differ:
#
#   employees      re-parses each row with employee_excel_row_to_payload(), so
#                  it wants the RAW sheet row -- the analyzer's `data`.
#   leave_balances unwraps $row['payload'] when present, so it takes the
#                  analyzer's row objects whole.
#
# Sending the wrong half to either would still answer 200, with every row in
# `failed` -- a case that passes while importing nothing.
# ---------------------------------------------------------------------------
capture() {  # $1=endpoint $2=fixture file $3=jq-ish python expression $4..=curl args
  local endpoint="$1" out="$2" extract="$3"; shift 3
  local code
  code=$(curl -s -o /tmp/parity-analysis.json -w '%{http_code}' -X POST "$PHP/$endpoint" \
           -H "Authorization: Bearer $token" "$@")
  if [ "$code" != "200" ] || [ ! -s /tmp/parity-analysis.json ]; then
    echo "FATAL: $endpoint answered $code with $(stat -c%s /tmp/parity-analysis.json 2>/dev/null || echo 0) bytes." >&2
    echo "       The import fixtures are the analyzer's output; there is nothing to replay." >&2
    exit 4
  fi
  python3 -c "
import json,sys
analysis = json.load(open('/tmp/parity-analysis.json'))['data']
rows = $extract
if not rows:
    sys.exit('FATAL: the analysis carried no rows -- the sheet reached the analyzer empty.')
# At least one row the analyzer itself calls valid. Without this the fixture
# could be all-invalid, both stacks would answer 200 with everything in
# \`failed\`, and the case would pass having imported nothing -- a refusal
# wearing a success status.
valid = sum(1 for r in analysis['rows'] if r.get('status') == 'valid')
if valid == 0:
    sys.exit('FATAL: the analyzer called every row invalid: '
             + json.dumps([r.get('errors') for r in analysis['rows']], ensure_ascii=False)[:300])
json.dump({'rows': rows}, open(sys.argv[1],'w'), ensure_ascii=False)
print(f'  fixtures/{sys.argv[2]}  ({len(rows)} rows, {valid} valid, from $endpoint)')
" "$HERE/fixtures/$out" "$out"
}

capture employees/analyze_excel employees-import-rows.json \
  "[r['data'] for r in analysis['rows']]" \
  -F "file=@$HERE/fixtures/employees-filled.xlsx"
capture leave_balances/analyze_excel leave_balances-import-rows.json \
  "analysis['rows']" \
  -F "file=@$HERE/fixtures/leave_balances-template.xlsx" -F "year=2026"

echo "fixtures built."

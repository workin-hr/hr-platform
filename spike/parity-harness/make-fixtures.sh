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
echo "fixtures built."

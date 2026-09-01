#!/usr/bin/env bash
# Resolve real ids from the seeded database and emit the authenticated sweep's
# endpoint list with query strings filled in.
#
# Why this exists: sweep-auth.sh GETs every endpoint bare, so the 26 routes that
# require a parameter answer 400/404 on both stacks and land in the
# "not-200-on-both" bucket. They read as covered because the run is green, but
# nothing about their response bodies has ever been compared. Supplying a valid
# id is the difference between "PHP and Java both reject an empty request the
# same way" and "PHP and Java return the same employee".
#
# Ids are resolved from the database rather than hardcoded because the seed is a
# real legacy snapshot: hardcoded ids silently stop existing after a reseed, the
# endpoint 404s on both stacks, and the sweep goes quiet again in exactly the
# way this script exists to prevent. Every resolved id is checked non-empty and
# the script fails loudly if one is missing.
#
# Parameter NAMES are taken from the frozen PHP (`$_GET[Request::X]` and
# `apis/config/request.php`), not guessed from error messages -- app_content/one
# reports "key required" but actually reads `content_key`.
set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
DB_CONTAINER=${DB_CONTAINER:-parity-harness-db-1}
DB=${DB:-workin}
OUT=${OUT:-"$HERE/client-endpoints-authed.txt"}

# The company the harness's test employee belongs to. Scoping every id to it
# keeps the sweep inside one tenant: an id from another company would be
# refused by an authorization check and compared as a 403/403 pair, which
# proves nothing about the response body.
# 214, not 244. The bare sweep's employee (999001) lives in 244, which has
# volume for the list endpoints but is missing six of the resource types the
# /one endpoints need. 214 carries 16 of the 18 -- see the seeded employee
# 999002 -- so it is the company that maximises how many endpoints can be
# compared at all. Both employees are seeded; neither sweep disturbs the other.
COMPANY=${COMPANY:-214}

q() {
  docker exec -i "$DB_CONTAINER" mariadb -uroot -pparity -N -e "$1" 2>/dev/null | grep -v '^Warning' | head -1
}

echo "resolving ids from $DB (company_id=$COMPANY)..." >&2

# company_id directly on the table
declare -A DIRECT=(
  [employees]=employees [branches]=branches [departments]=departments
  [job_titles]=job_titles [shifts]=shifts [request_types]=request_types
  [notifications]=notifications [payroll_batches]=payroll_batches
  [workforce_planning]=workforce_planning [assets]=assets
  [company_official_holidays]=company_official_holidays
  [administrative_decisions]=administrative_decisions
)
# scoped through employees.company_id -- these tables carry employee_id, not company_id
declare -A VIAEMP=(
  [requests]=requests [advances]=advances [penalties]=penalties
  [payslips]=payslips [attendance]=attendance [leave_balance]=leave_balance
  [salary_contracts]=salary_contracts
)

declare -A ID
for k in "${!DIRECT[@]}"; do
  ID[$k]=$(q "SELECT id FROM $DB.${DIRECT[$k]} WHERE company_id=$COMPANY ORDER BY id LIMIT 1")
done
for k in "${!VIAEMP[@]}"; do
  ID[$k]=$(q "SELECT t.id FROM $DB.${VIAEMP[$k]} t JOIN $DB.employees e ON e.id=t.employee_id WHERE e.company_id=$COMPANY ORDER BY t.id LIMIT 1")
done
ID[employee_scoped]=$(q "SELECT id FROM $DB.employees WHERE company_id=$COMPANY ORDER BY id LIMIT 1")
ID[setting_definition_id]=$(q "SELECT setting_definition_id FROM $DB.company_settings WHERE company_id=$COMPANY ORDER BY id LIMIT 1")
ID[content_key]=$(q "SELECT content_key FROM $DB.app_content ORDER BY id LIMIT 1")
# A period an existing batch actually covers, so fiscal_period returns data
# rather than an empty-but-valid answer.
ID[batch_year]=$(q "SELECT year FROM $DB.payroll_batches WHERE company_id=$COMPANY ORDER BY id LIMIT 1")
ID[batch_month]=$(q "SELECT month FROM $DB.payroll_batches WHERE company_id=$COMPANY ORDER BY id LIMIT 1")

# An id that will not resolve is reported, never silently dropped. The whole
# point of this script is that an endpoint answering 404 on both stacks looks
# identical to one that was never exercised; the gap has to be visible in the
# output or it becomes invisible again.
UNRESOLVED="$HERE/params-unresolved.txt"
: > "$UNRESOLVED"
missing=0
for k in $(printf '%s\n' "${!ID[@]}" | sort); do
  if [ -z "${ID[$k]}" ] || [ "${ID[$k]}" = "NULL" ]; then
    echo "$k	no row for company_id=$COMPANY" >> "$UNRESOLVED"
    ID[$k]=""
    missing=$((missing+1))
  fi
done
if [ "$missing" -gt 0 ]; then
  echo "  $missing resource type(s) have no row in company $COMPANY:" >&2
  sed 's/^/    - /' "$UNRESOLVED" >&2
  echo "  their endpoints stay UNPARAMETERISED and remain uncompared -- listed in" >&2
  echo "  $(basename "$UNRESOLVED"). This is a coverage gap, not a pass." >&2
fi

# endpoint -> query string. Parameter names come from the frozen PHP.
declare -A PARAM=(
  [advances/one]="id=${ID[advances]}"
  [app_content/one]="content_key=${ID[content_key]}"
  [assets/one]="id=${ID[assets]}"
  [attendance/one]="id=${ID[attendance]}"
  [branches/one]="id=${ID[branches]}"
  [company_official_holidays/one]="id=${ID[company_official_holidays]}"
  [company_settings/one]="setting_definition_id=${ID[setting_definition_id]}"
  [departments/one]="id=${ID[departments]}"
  [employees/delete_preview]="id=${ID[employee_scoped]}"
  [employees/one]="id=${ID[employee_scoped]}"
  [job_titles/one]="id=${ID[job_titles]}"
  [leave_balances/one]="id=${ID[leave_balance]}"
  [notifications/one]="id=${ID[notifications]}"
  [payroll_batches/fiscal_period]="year=${ID[batch_year]}&month=${ID[batch_month]}"
  [payroll_batches/one]="id=${ID[payroll_batches]}"
  [payroll_batches/stats]="id=${ID[payroll_batches]}"
  [payslips/one]="id=${ID[payslips]}"
  [penalties/one]="id=${ID[penalties]}"
  [request_types/one]="id=${ID[request_types]}"
  [requests/one]="id=${ID[requests]}"
  [salary_contracts/list]="employee_id=${ID[employee_scoped]}"
  [salary_contracts/one]="id=${ID[salary_contracts]}"
  [shifts/one]="id=${ID[shifts]}"
  [workforce_planning/one]="id=${ID[workforce_planning]}"
)

: > "$OUT"
added=0
while read -r ep; do
  [ -n "$ep" ] || continue
  v="${PARAM[$ep]:-}"
  # A template whose id did not resolve expands to "id=" -- which the endpoint
  # rejects exactly like the bare call, so it would count as parameterised
  # while proving nothing. Drop it back to bare and record it.
  if [ -n "$v" ] && ! printf '%s' "$v" | grep -qE '=(&|$)'; then
    echo "$ep?$v" >> "$OUT"; added=$((added+1))
  else
    [ -n "$v" ] && echo "$ep	unresolved id in template" >> "$UNRESOLVED"
    echo "$ep" >> "$OUT"
  fi
done < "$HERE/client-endpoints.txt"

# Every mapping must correspond to a real endpoint. A typo here would otherwise
# be invisible: the entry is simply never matched and the endpoint stays bare.
for ep in "${!PARAM[@]}"; do
  grep -qxF "$ep" "$HERE/client-endpoints.txt" || {
    echo "FATAL: '$ep' is not in client-endpoints.txt -- typo, or the inventory changed" >&2; exit 4; }
done

echo "wrote $OUT ($(wc -l < "$OUT") endpoints, $added parameterised)" >&2

#!/usr/bin/env bash
# Compare MUTATING endpoints: same request to both stacks, from the same
# starting state, then diff the response AND the rows each one wrote.
#
# Why the row diff matters as much as the response: an endpoint can answer
# identically and still persist differently -- the wrong column, a different
# rounding, a missing updated_at, a divergent default. The response alone
# cannot see any of that, and those are exactly the defects that surface as
# wrong money weeks later.
#
# Each case runs against a freshly reseeded pair, so cases cannot contaminate
# each other. That is slow and deliberate: a shared, drifting state makes a
# failure impossible to attribute.
set -uo pipefail
DB=parity-harness-db-1
PHP_DB=workin
JAVA_DB=workin_java
PHP=http://localhost:18080/apis/api
JAVA=http://localhost:18081/apis/api

m() { docker exec -i "$DB" mariadb -uroot -pparity -N -B "$@"; }

# Row-level fingerprint of one table, ordered deterministically by primary key
# so the comparison cannot be thrown by return order.
#
# Timestamp columns are excluded. The two stacks are called sequentially, so a
# created_at/updated_at can legitimately differ by a second -- and if that
# counted, every endpoint that stamps a row would report a difference and real
# defects would drown in the noise. What is being compared is what each stack
# *chose* to write, not when the harness happened to call it.
snapshot() {  # $1=database $2=table
  # Timestamps are normalised to null/set rather than dropped.
  #
  # Dropping them was wrong: the whole argument for diffing rows is that a
  # stack can answer identically and still persist differently -- and "wrote no
  # updated_at" is exactly that defect. Excluding the columns made the one case
  # this document holds up as the reason for the row diff undetectable.
  #
  # Comparing them raw is also wrong, because the two stacks are called
  # sequentially and a second's drift is the harness, not the code. null/set
  # catches a missing or wrong-column write; the drift check below catches a
  # timezone or default that is hours out. Between them, only a
  # sub-tolerance difference in an otherwise-present timestamp goes unnoticed.
  local cols
  cols=$(m information_schema -e "
    SELECT GROUP_CONCAT(
             CASE WHEN data_type IN ('timestamp','datetime','date')
                  THEN CONCAT('CASE WHEN \`', column_name, '\` IS NULL THEN ''null'' ELSE ''set'' END')
                  ELSE CONCAT('\`', column_name, '\`') END
             ORDER BY ordinal_position)
    FROM columns WHERE table_schema='$1' AND table_name='$2'" 2>/dev/null)
  [ -n "$cols" ] && [ "$cols" != NULL ] || { echo "no-such-table:$2"; return; }
  m "$1" -e "SELECT $cols FROM \`$2\` ORDER BY id" 2>/dev/null | sha256sum | cut -d' ' -f1
}

# How far apart the two stacks' newest timestamp in a table may be. Sequential
# calls drift by a second or two; a timezone error or a wrong default is hours.
TS_TOLERANCE_SECONDS=${TS_TOLERANCE_SECONDS:-120}

timestamp_drift() {  # $1=table -> seconds between the two stacks' newest row, or empty
  local col
  col=$(m information_schema -e "
    SELECT column_name FROM columns
    WHERE table_schema='$PHP_DB' AND table_name='$1'
      AND column_name IN ('created_at','updated_at')
    ORDER BY column_name LIMIT 1" 2>/dev/null)
  [ -n "$col" ] && [ "$col" != NULL ] || return 0
  local a b
  a=$(m "$PHP_DB"  -e "SELECT UNIX_TIMESTAMP(MAX(\`$col\`)) FROM \`$1\`" 2>/dev/null)
  b=$(m "$JAVA_DB" -e "SELECT UNIX_TIMESTAMP(MAX(\`$col\`)) FROM \`$1\`" 2>/dev/null)
  case "$a$b" in *NULL*|"") return 0 ;; esac
  echo $(( a > b ? a - b : b - a ))
}

mint_token() {  # $1=base url  -> stdout
  curl -s -X POST "$1/auth/login_employee" -H 'Content-Type: application/json' \
    -d '{"phone":"+201999000001","password":"harness-only-Pass123!"}' \
    | python3 -c "import sys,json; print(json.load(sys.stdin).get('data',{}).get('token',''))"
}

pass=0; fail=0
: > mutation-diffs.txt

# Each case: NAME | METHOD | PATH | JSON BODY | TABLES TO COMPARE (comma separated)
run_case() {
  local name="$1" method="$2" path="$3" body="$4" tables="$5"

  ./seed-two.sh >/dev/null 2>&1

  # A token per stack, minted from that stack's own database. Both are valid
  # because the secret is shared and each database has the same employee row at
  # token_version 1 after reseeding.
  local pt jt
  pt=$(mint_token "$PHP"); jt=$(mint_token "$JAVA")
  if [ -z "$pt" ] || [ -z "$jt" ]; then
    printf '%-42s SKIP (login failed: php=%s java=%s)\n' "$name" "${#pt}" "${#jt}"
    return
  fi

  local pcode jcode pbody jbody
  pcode=$(curl -s -o /tmp/mp.json -w '%{http_code}' -X "$method" "$PHP/$path" \
            -H "Authorization: Bearer $pt" -H 'Content-Type: application/json' -d "$body")
  jcode=$(curl -s -o /tmp/mj.json -w '%{http_code}' -X "$method" "$JAVA/$path" \
            -H "Authorization: Bearer $jt" -H 'Content-Type: application/json' -d "$body")

  # Canonicalise, and blank any timestamp-shaped value for the same reason the
  # row snapshot drops them: sequential calls differ by a second, and that is
  # the harness talking, not the code.
  norm() { python3 -c "
import json,re,sys
def scrub(v):
    if isinstance(v,dict): return {k:scrub(x) for k,x in v.items()}
    if isinstance(v,list): return [scrub(x) for x in v]
    if isinstance(v,str) and re.fullmatch(r'\d{4}-\d{2}-\d{2}[ T]\d{2}:\d{2}:\d{2}.*', v): return '<TS>'
    return v
print(json.dumps(scrub(json.load(open(sys.argv[1]))),sort_keys=True,ensure_ascii=False))" "$1" 2>/dev/null || cat "$1"; }
  pbody=$(norm /tmp/mp.json)
  jbody=$(norm /tmp/mj.json)

  local verdict=ok detail=""
  [ "$pcode" = "$jcode" ] || { verdict=DIFF; detail="status $pcode vs $jcode"; }
  if [ "$verdict" = ok ] && [ "$pbody" != "$jbody" ]; then verdict=DIFF; detail="response body"; fi

  # State comparison runs even when the response already differed: knowing
  # whether the write also diverged is the more useful half.
  local statediff=""
  IFS=',' read -ra tl <<< "$tables"
  for t in "${tl[@]}"; do
    [ -n "$t" ] || continue
    if [ "$(snapshot "$PHP_DB" "$t")" != "$(snapshot "$JAVA_DB" "$t")" ]; then
      statediff="$statediff $t"
      continue
    fi
    drift=$(timestamp_drift "$t")
    if [ -n "$drift" ] && [ "$drift" -gt "$TS_TOLERANCE_SECONDS" ]; then
      statediff="$statediff $t(ts+${drift}s)"
    fi
  done
  if [ -n "$statediff" ]; then verdict=DIFF; detail="${detail:+$detail; }rows differ:$statediff"; fi

  if [ "$verdict" = ok ]; then
    pass=$((pass+1))
  else
    fail=$((fail+1))
    { echo "### $name  ($method $path)"
      echo "    $detail"
      echo "    PHP  $pcode $(head -c 300 <<< "$pbody")"
      echo "    JAVA $jcode $(head -c 300 <<< "$jbody")"
      echo
    } >> mutation-diffs.txt
  fi
  printf '%-42s %-4s %-4s %s %s\n' "$name" "$pcode" "$jcode" "$verdict" "$detail"
}

printf '%-42s %-4s %-4s %s\n' CASE PHP JAVA VERDICT

# Ids resolved from the seeded snapshot rather than hardcoded, so a reseed with
# different data cannot silently turn every case into a 400 that then "matches".
BRANCH=$(m "$PHP_DB" -e "SELECT id FROM branches WHERE company_id=244 LIMIT 1")
EMP=$(m "$PHP_DB" -e "SELECT id FROM employees WHERE company_id=244 AND id<>999001 LIMIT 1")

# --- cases that mutate ---------------------------------------------------
run_case "branches/create"                 POST "branches/create" \
  '{"name":"Parity Branch"}' "branches"
run_case "departments/create"              POST "departments/create" \
  "{\"name\":\"Parity Dept\",\"branch_ids\":[$BRANCH]}" "departments"
run_case "job_titles/create"               POST "job_titles/create" \
  '{"name":"Parity Title"}' "job_titles"
run_case "advances/create"                 POST "advances/create" \
  "{\"employee_id\":$EMP,\"amount\":100,\"reason\":\"parity\",\"deduction_mode\":\"single_payroll_month\",\"deduction_payroll_year\":2026,\"deduction_payroll_month\":9}" \
  "advances"

# --- cases that must be REJECTED, identically ----------------------------
# Error envelopes are where PHP's quirks concentrate, and a rejection that
# differs is as client-breaking as a success that differs.
run_case "branches/create (missing name)"  POST "branches/create" \
  '{}' "branches"
run_case "departments/create (no branches)" POST "departments/create" \
  '{"name":"Parity Dept"}' "departments"
run_case "advances/create (no employee_id)" POST "advances/create" \
  '{"amount":100}' "advances"
run_case "requests/create (wrong role)"    POST "requests/create" \
  '{"type":"leave","from_date":"2026-09-01","to_date":"2026-09-02"}' "requests"
run_case "admin_decisions/create"           POST "administrative_decisions/create" \
  '{"title":"Parity","body":"parity body"}' "administrative_decisions"

# --- the paths where the money and the legal obligations are ---------------
# Required fields taken from each endpoint's own required() call rather than
# discovered one 400 at a time, so a case that fails is failing on behaviour.
run_case "payroll_batches/create"          POST "payroll_batches/create" \
  '{"month":9,"year":2026}' "payroll_batches"
run_case "penalties/create"                POST "penalties/create" \
  "{\"employee_id\":$EMP,\"penalty_type\":\"absence\",\"penalty_date\":\"2026-09-01\"}" \
  "penalties"
run_case "leave_balances/create"           POST "leave_balances/create" \
  "{\"employee_id\":$EMP,\"year\":2026,\"total_days\":21}" "leave_balance"
run_case "attendance/create"               POST "attendance/create" \
  "{\"employee_id\":$EMP,\"check_in\":\"2026-09-01 09:00:00\",\"check_out\":\"2026-09-01 17:00:00\"}" \
  "attendance"

# Rejections on the same paths: a wrong month, an unknown employee, a bad date.
run_case "payroll_batches (month 13)"      POST "payroll_batches/create" \
  '{"month":13,"year":2026}' "payroll_batches"
run_case "penalties (unknown employee)"    POST "penalties/create" \
  '{"employee_id":99999999,"penalty_type":"absence","penalty_date":"2026-09-01"}' "penalties"
run_case "attendance (unknown employee)"   POST "attendance/create" \
  '{"employee_id":99999999,"check_in":"2026-09-01 09:00:00"}' "attendance"
run_case "leave_balances (negative days)"  POST "leave_balances/create" \
  "{\"employee_id\":$EMP,\"year\":2026,\"total_days\":-5}" "leave_balance"

echo
echo "identical=$pass  differing=$fail"
[ "$fail" -gt 0 ] && echo "details in mutation-diffs.txt"
exit 0

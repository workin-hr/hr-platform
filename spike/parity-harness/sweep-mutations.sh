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

# Java MUST be running against JAVA_DB, not the database PHP mutates.
#
# Nothing here can reconfigure an already-running JVM, and the README's read
# workflow starts Java against `workin`. If that JVM is still up, both stacks
# write to `workin` and this script then compares a twice-mutated `workin`
# against an untouched `workin_java` -- every case "differs" for a reason that
# has nothing to do with the port, or worse, appears to agree.
#
# Proved rather than assumed: a sentinel is written into JAVA_DB only, and
# Java is asked for it through configs/get, which is unauthenticated and
# returns config rows. If Java cannot see it, Java is on the wrong database.
assert_java_on_its_own_database() {
  local sentinel="parity-$$-$(date +%s)"
  m "$JAVA_DB" -e "REPLACE INTO configs (config_key, config_value) VALUES ('parity_harness_sentinel', '$sentinel')" >/dev/null 2>&1
  local seen
  seen=$(curl -s -m 10 "$JAVA/configs/get?config_key=parity_harness_sentinel" \
         | python3 -c "import sys,json;print(json.load(sys.stdin).get('data',{}).get('config_value',''))" 2>/dev/null)
  m "$JAVA_DB" -e "DELETE FROM configs WHERE config_key='parity_harness_sentinel'" >/dev/null 2>&1
  if [ "$seen" != "$sentinel" ]; then
    echo "FATAL: Java is not connected to '$JAVA_DB'." >&2
    echo "  A sentinel written only to '$JAVA_DB' was not visible through $JAVA." >&2
    echo "  Both stacks would write to the same database and this comparison would be meaningless." >&2
    echo "  Restart Java with:  JAVA_DB=$JAVA_DB ./run-java.sh" >&2
    exit 3
  fi
}

# Row-level fingerprint of one table, ordered deterministically by primary key
# so the comparison cannot be thrown by return order.
#
# Only the three audit columns are normalised. The two stacks are called sequentially, so a
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
  #
  # ONLY the audit columns are collapsed. An earlier version collapsed every
  # temporal column, which quietly gutted the cases that matter most:
  # `attendance/create` sends check_in and check_out, and reducing them to
  # `set` meant PHP could persist 09:00 while Java persisted a different day
  # entirely and the row hash still matched. Business dates and times are
  # caller-supplied and deterministic, so they are compared EXACTLY; only
  # created_at/updated_at are nondeterministic between two sequential calls.
  local cols
  cols=$(m information_schema -e "
    SELECT GROUP_CONCAT(
             CASE WHEN data_type IN ('timestamp','datetime','date')
                   AND column_name IN ('created_at','updated_at','deleted_at')
                  THEN CONCAT('CASE WHEN \`', column_name, '\` IS NULL THEN ''null'' ELSE ''set'' END')
                  -- qr_code is bin2hex(random_bytes(16)) in PHP and SecureRandom in
                  -- Java, so two CORRECT implementations must differ. Reduced to its
                  -- shape rather than dropped: a wrong length, uppercase hex, or a
                  -- constant that is not random at all still fails the comparison,
                  -- which dropping the column would hide.
                  WHEN column_name = 'qr_code'
                  THEN CONCAT('CASE WHEN \`', column_name, '\` IS NULL THEN ''null'' ',
                              'WHEN \`', column_name, '\` REGEXP ''^[0-9a-f]{32}$'' ',
                              'THEN ''random-32-lower-hex'' ',
                              'ELSE CONCAT(''UNEXPECTED-SHAPE:'', \`', column_name, '\`) END')
                  ELSE CONCAT('\`', column_name, '\`') END
             ORDER BY ordinal_position)
    FROM columns WHERE table_schema='$1' AND table_name='$2'" 2>/dev/null)
  # A failed or empty schema read must not yield something that can match.
  # `no-such-table:` is per-table text, so two failures on the SAME table
  # compared equal -- the identical hole the row query had. Both sides now
  # carry the database name, so a discovery failure can never look like
  # agreement between two databases.
  [ -n "$cols" ] && [ "$cols" != NULL ] || { echo "SCHEMA-DISCOVERY-FAILED:$1.$2"; return; }

  # Order by the table's ACTUAL primary key, derived rather than assumed.
  # This hard-coded `ORDER BY id`, which is wrong for a composite-keyed
  # junction like department_branches(department_id, branch_id) -- that query
  # errors, stderr was suppressed, and BOTH stacks then hashed empty output and
  # compared equal. A table the comparison cannot read must fail loudly, not
  # look identical.
  local pk
  pk=$(m information_schema -e "
    SELECT GROUP_CONCAT(CONCAT('\`', column_name, '\`') ORDER BY seq_in_index)
    FROM statistics WHERE table_schema='$1' AND table_name='$2' AND index_name='PRIMARY'" 2>/dev/null)
  [ -n "$pk" ] && [ "$pk" != NULL ] || pk="$cols"

  local out rc
  out=$(m "$1" -e "SELECT $cols FROM \`$2\` ORDER BY $pk"); rc=$?
  if [ $rc -ne 0 ]; then
    echo "QUERY-FAILED:$1.$2"
    return
  fi
  printf '%s' "$out" | sha256sum | cut -d' ' -f1
}

# How far apart the two stacks' newest timestamp in a table may be. Sequential
# calls drift by a second or two; a timezone error or a wrong default is hours.
TS_TOLERANCE_SECONDS=${TS_TOLERANCE_SECONDS:-120}

timestamp_drift() {  # $1=table -> worst drift across audit columns, or empty
  # EVERY audit column, not the first by name. `ORDER BY column_name LIMIT 1`
  # always picked created_at, so an update that wrote updated_at with the wrong
  # timezone or default was checked against a column the update never touched
  # -- while the row hash had already reduced both to `set`. The drift check
  # existed for exactly that defect and could not see it.
  local cols worst=""
  cols=$(m information_schema -e "
    SELECT column_name FROM columns
    WHERE table_schema='$PHP_DB' AND table_name='$1'
      AND column_name IN ('created_at','updated_at','deleted_at')" 2>/dev/null)
  [ -n "$cols" ] || return 0
  local col a b d
  while read -r col; do
    [ -n "$col" ] && [ "$col" != NULL ] || continue
    a=$(m "$PHP_DB"  -e "SELECT UNIX_TIMESTAMP(MAX(\`$col\`)) FROM \`$1\`" 2>/dev/null)
    b=$(m "$JAVA_DB" -e "SELECT UNIX_TIMESTAMP(MAX(\`$col\`)) FROM \`$1\`" 2>/dev/null)
    case "$a$b" in *NULL*|"") continue ;; esac
    d=$(( a > b ? a - b : b - a ))
    [ -z "$worst" ] || [ "$d" -gt "$worst" ] && worst=$d
  done <<< "$cols"
  [ -n "$worst" ] && echo "$worst"
}

# $1=base url  $2=phone (defaults to the company-244 employee)
#
# Which employee a case runs as is part of the case, not a global: the update
# and delete cases need a company that HAS a row of the resource type, and 244
# has none for six of them. See seed-two.sh.
mint_token() {
  curl -s -X POST "$1/auth/login_employee" -H 'Content-Type: application/json' \
    -d "{\"phone\":\"${2:-+201999000001}\",\"password\":\"harness-only-Pass123!\"}" \
    | python3 -c "import sys,json; print(json.load(sys.stdin).get('data',{}).get('token',''))"
}
PHONE_244=+201999000001
PHONE_214=+201999000002

# Divergences the repository has decided to keep, keyed on the endpoint AND the
# exact status pair -- keying on the endpoint alone would let ANY future
# mismatch there be filed under an accepted entry and never reported. Each must
# name the risk or decision that accepted it, so "expected" stays auditable.
accepted_mutation_divergence() {  # $1=path $2=php code $3=java code
  case "${1%%\?*}:$2:$3" in
    branches/update:500:404)
      # R-036: branches/update.php re-reads the row keyed on id ALONE after a
      # company-scoped UPDATE. For an id that does not exist the re-read is
      # null and public_row(null) raises a TypeError -> 500. Java returns a
      # clean 404. The same missing predicate discloses ANOTHER COMPANY's
      # branch when the id does exist elsewhere -- Java refuses that too.
      # Reproducing either behaviour would mean porting a tenant-isolation
      # defect, so the divergence is deliberate.
      echo "R-036"; return 0 ;;
  esac
  return 1
}

pass=0; fail=0; accepted=0
: > mutation-diffs.txt

# Each case pins five things, which is what makes it a covered endpoint rather
# than a request that was merely sent:
#
#   1. the valid request payload            -- BODY (and any ?query on PATH)
#   2. the expected status semantics        -- EXPECT, asserted against PHP
#   3. normalised response parity           -- norm(), audit keys and qr_code
#                                              reduced to shape, nothing dropped
#   4. the affected rows, before and after  -- TABLES, hashed by snapshot()
#   5. the reset procedure                  -- seed-two.sh, re-run per case
#
# NAME | METHOD | PATH | BODY | TABLES | WHO (244|214|-) | EXPECT
#
# WHO selects which seeded employee runs the case; 214 is the company that has
# a row of nearly every resource type, which the update and delete cases need.
# EXPECT is PHP's status for this case. It is asserted rather than inferred, so
# two stacks that agree on the WRONG status -- a fixture that stopped
# resolving, both answering 404 -- are caught instead of counted as parity.
run_case() {
  local name="$1" method="$2" path="$3" body="$4" tables="$5" who="${6:-244}" expect="${7:-}"
  case "$who" in -|"") who=244 ;; esac
  local phone; case "$who" in 214) phone=$PHONE_214 ;; *) phone=$PHONE_244 ;; esac

  # A failed reseed leaves the PREVIOUS case's mutations in place, so this case
  # would compare contaminated state and report whatever that happens to give.
  # Same class as the 000 and schema-failure holes: a step that did not run must
  # not produce a verdict.
  if ! ./seed-two.sh >/dev/null 2>&1; then
    fail=$((fail+1))
    printf '%-42s %-4s %-4s %s\n' "$name" "-" "-" "RESEED-FAILED"
    { echo "### $name  ($method $path)"; echo "    RESEED-FAILED: state is contaminated, verdict withheld"; echo; } >> mutation-diffs.txt
    return
  fi

  # A token per stack, minted from that stack's own database. Both are valid
  # because the secret is shared and each database has the same employee row at
  # token_version 1 after reseeding.
  local pt jt
  pt=$(mint_token "$PHP" "$phone"); jt=$(mint_token "$JAVA" "$phone")
  if [ -z "$pt" ] || [ -z "$jt" ]; then
    # Counted, not skipped. A SKIP was reported and tallied as neither
    # identical nor differing, so a run where every login failed printed a
    # clean-looking summary describing nothing that ran.
    fail=$((fail+1))
    printf '%-42s %-4s %-4s %s\n' "$name" "-" "-" "LOGIN-FAILED"
    { echo "### $name  ($method $path)"; echo "    LOGIN-FAILED: php-token=${#pt} java-token=${#jt} chars"; echo; } >> mutation-diffs.txt
    return
  fi

  local pcode jcode pbody jbody
  pcode=$(curl -s -o /tmp/mp.json -w '%{http_code}' -X "$method" "$PHP/$path" \
            -H "Authorization: Bearer $pt" -H 'Content-Type: application/json' -d "$body")
  jcode=$(curl -s -o /tmp/mj.json -w '%{http_code}' -X "$method" "$JAVA/$path" \
            -H "Authorization: Bearer $jt" -H 'Content-Type: application/json' -d "$body")

  # Canonicalise, and blank the audit KEYS for the same reason the
  # row snapshot drops them: sequential calls differ by a second, and that is
  # the harness talking, not the code.
  # Scrubs by KEY, not by value shape. Matching any timestamp-shaped string
  # blanked `check_in` and `check_out` in the response too, so PHP could return
  # 09:00 and Java a different time with the bodies still comparing equal --
  # the same hole the row hash had, on the other side of the comparison.
  # Only the audit keys are nondeterministic between two sequential calls.
  norm() { python3 -c "
import json,re,sys
AUDIT={'created_at','updated_at','deleted_at'}
# Same rule the row snapshot uses: a value two CORRECT implementations must
# disagree on is reduced to its shape, never dropped. A qr_code of the wrong
# length, in uppercase, or non-hex still compares unequal.
#
# What this canNOT catch, stated rather than implied: a Java constant of the
# right shape (32 lowercase hex) normalises the same as a random one. Proving
# randomness needs more than one sample, so it is asserted where samples are
# available -- LegacyBranchQrRandomnessTest -- not here.
QR=re.compile(r'^[0-9a-f]{32}$')
def scrub(v,key=None):
    if isinstance(v,dict): return {k:scrub(x,k) for k,x in v.items()}
    if isinstance(v,list): return [scrub(x,key) for x in v]
    if key in AUDIT and v is not None: return '<TS>'
    if key=='qr_code' and isinstance(v,str):
        return '<QR:random-32-lower-hex>' if QR.match(v) else '<QR:UNEXPECTED-SHAPE:'+v+'>'
    return v
print(json.dumps(scrub(json.load(open(sys.argv[1]))),sort_keys=True,ensure_ascii=False))" "$1" 2>/dev/null || cat "$1"; }
  pbody=$(norm /tmp/mp.json)
  jbody=$(norm /tmp/mj.json)

  local verdict=ok detail=""
  # A transport failure is curl's 000. Two of them compare equal, the missing
  # bodies normalize equal, and the untouched snapshots match -- so a mutation
  # that never executed would be counted as identical. Same hole sweep.sh had.
  if [ "$pcode" = 000 ] || [ "$jcode" = 000 ]; then
    fail=$((fail+1))
    printf '%-42s %-4s %-4s %s\n' "$name" "$pcode" "$jcode" "UNREACHABLE"
    { echo "### $name  ($method $path)"; echo "    UNREACHABLE: curl could not reach php=$pcode java=$jcode"; echo; } >> mutation-diffs.txt
    return
  fi
  # The expected status is pinned per case, so two stacks that agree on the
  # WRONG thing are still caught. Without this, a case whose fixture stopped
  # resolving would answer 404 on both, compare equal, and be counted as a
  # passing mutation that never mutated anything -- "matching errors read as
  # parity", the same trap the GET sweep had.
  if [ -n "$expect" ] && [ "$pcode" != "$expect" ]; then
    fail=$((fail+1))
    printf '%-42s %-4s %-4s %s\n' "$name" "$pcode" "$jcode" "UNEXPECTED-STATUS (case expects $expect)"
    { echo "### $name  ($method $path)"
      echo "    PHP answered $pcode; this case is declared to expect $expect."
      echo "    Either the fixture no longer resolves, or legacy behaviour changed."
      echo "    PHP  $pcode $(head -c 300 <<< "$pbody")"
      echo; } >> mutation-diffs.txt
    return
  fi
  if [ "$pcode" != "$jcode" ]; then
    if reason=$(accepted_mutation_divergence "$path" "$pcode" "$jcode"); then
      accepted=$((accepted+1))
      printf '%-42s %-4s %-4s %s\n' "$name" "$pcode" "$jcode" "ACCEPTED ($reason)"
      return
    fi
    verdict=DIFF; detail="status $pcode vs $jcode"
  fi
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

./seed-two.sh >/dev/null 2>&1
assert_java_on_its_own_database

printf '%-42s %-4s %-4s %s\n' CASE PHP JAVA VERDICT

# Ids resolved from the seeded snapshot rather than hardcoded, so a reseed with
# different data cannot silently turn every case into a 400 that then "matches".
BRANCH=$(m "$PHP_DB" -e "SELECT id FROM branches WHERE company_id=244 LIMIT 1")
EMP=$(m "$PHP_DB" -e "SELECT id FROM employees WHERE company_id=244 AND id<>999001 LIMIT 1")
DEPT=$(m "$PHP_DB" -e "SELECT id FROM departments WHERE company_id=244 LIMIT 1")
RTYPE=$(m "$PHP_DB" -e "SELECT id FROM request_types WHERE company_id=244 OR company_id IS NULL LIMIT 1")

# Ids for the UPDATE and DELETE cases. These name a row that must already
# exist: an id that does not resolve makes both stacks answer 404, and two
# matching 404s would be counted as a passing case that never updated or
# deleted anything. resolve_or_die refuses instead.
resolve_or_die() {  # $1=label $2=sql
  local v; v=$(m "$PHP_DB" -e "$2")
  if [ -z "$v" ] || [ "$v" = "NULL" ]; then
    echo "FATAL: could not resolve '$1' from the seed." >&2
    echo "  SQL: $2" >&2
    echo "  Refusing to run: the case would 404 on both stacks and count as identical." >&2
    exit 5
  fi
  printf '%s' "$v"
}

# company 214 -- has a row for the types 244 lacks
C214_EMP=$(resolve_or_die c214_emp   "SELECT id FROM employees WHERE company_id=214 AND id<>999002 ORDER BY id LIMIT 1")
C214_BRANCH=$(resolve_or_die c214_br "SELECT id FROM branches WHERE company_id=214 ORDER BY id LIMIT 1")
C214_DEPT=$(resolve_or_die c214_dep  "SELECT id FROM departments WHERE company_id=214 ORDER BY id LIMIT 1")
C214_TITLE=$(resolve_or_die c214_jt  "SELECT id FROM job_titles WHERE company_id=214 ORDER BY id LIMIT 1")
C214_SHIFT=$(resolve_or_die c214_sh  "SELECT id FROM shifts WHERE company_id=214 ORDER BY id LIMIT 1")
C214_RTYPE=$(resolve_or_die c214_rt  "SELECT id FROM request_types WHERE company_id=214 ORDER BY id LIMIT 1")
C214_REQ=$(resolve_or_die c214_req   "SELECT r.id FROM requests r JOIN employees e ON e.id=r.employee_id WHERE e.company_id=214 ORDER BY r.id LIMIT 1")
C214_PEN=$(resolve_or_die c214_pen   "SELECT p.id FROM penalties p JOIN employees e ON e.id=p.employee_id WHERE e.company_id=214 ORDER BY p.id LIMIT 1")
C214_ADV=$(resolve_or_die c214_adv   "SELECT a.id FROM advances a JOIN employees e ON e.id=a.employee_id WHERE e.company_id=214 ORDER BY a.id LIMIT 1")
C214_ASSET=$(resolve_or_die c214_ast "SELECT id FROM assets WHERE company_id=214 ORDER BY id LIMIT 1")
C214_HOL=$(resolve_or_die c214_hol   "SELECT id FROM company_official_holidays WHERE company_id=214 ORDER BY id LIMIT 1")
C214_PAYSLIP=$(resolve_or_die c214_ps "SELECT p.id FROM payslips p JOIN employees e ON e.id=p.employee_id WHERE e.company_id=214 ORDER BY p.id LIMIT 1")
C214_BATCH=$(resolve_or_die c214_pb  "SELECT id FROM payroll_batches WHERE company_id=214 ORDER BY id LIMIT 1")
C214_LB=$(resolve_or_die c214_lb     "SELECT l.id FROM leave_balance l JOIN employees e ON e.id=l.employee_id WHERE e.company_id=214 ORDER BY l.id LIMIT 1")
C214_SC=$(resolve_or_die c214_sc     "SELECT s.id FROM salary_contracts s JOIN employees e ON e.id=s.employee_id WHERE e.company_id=214 ORDER BY s.id LIMIT 1")
C214_ATT=$(resolve_or_die c214_att   "SELECT a.id FROM attendance a JOIN employees e ON e.id=a.employee_id WHERE e.company_id=214 ORDER BY a.id LIMIT 1")
C214_NOTIF=$(resolve_or_die c214_nt  "SELECT id FROM notifications WHERE company_id=214 ORDER BY id LIMIT 1")
C214_COMPLAINT=$(resolve_or_die c214_cp "SELECT id FROM complaints WHERE company_id=214 ORDER BY id LIMIT 1")
C214_SETTING=$(resolve_or_die c214_cs "SELECT setting_definition_id FROM company_settings WHERE company_id=214 ORDER BY id LIMIT 1")

# --- cases that mutate ---------------------------------------------------
run_case "branches/create"                 POST "branches/create" \
  '{"name":"Parity Branch"}' "branches" - 201
run_case "departments/create"              POST "departments/create" \
  "{\"name\":\"Parity Dept\",\"branch_ids\":[$BRANCH]}" "departments,department_branches" - 201
run_case "job_titles/create"               POST "job_titles/create" \
  "{\"name\":\"Parity Title\",\"department_id\":$DEPT,\"work_hours\":8}" "job_titles" - 201
run_case "advances/create"                 POST "advances/create" \
  "{\"employee_id\":$EMP,\"amount\":100,\"reason\":\"parity\",\"deduction_mode\":\"single_payroll_month\",\"deduction_payroll_year\":2026,\"deduction_payroll_month\":9}" \
  "advances" - 201

# --- cases that must be REJECTED, identically ----------------------------
# Error envelopes are where PHP's quirks concentrate, and a rejection that
# differs is as client-breaking as a success that differs.
run_case "branches/create (missing name)"  POST "branches/create" \
  '{}' "branches" - 400
run_case "departments/create (no branches)" POST "departments/create" \
  '{"name":"Parity Dept"}' "departments" - 400
run_case "advances/create (no employee_id)" POST "advances/create" \
  '{"amount":100}' "advances" - 400
run_case "requests/create (wrong role)"    POST "requests/create" \
  "{\"request_type_id\":$RTYPE,\"from_date\":\"2026-09-01\",\"to_date\":\"2026-09-02\"}" "requests" - 403
run_case "admin_decisions/create"           POST "administrative_decisions/create" \
  '{"title":"Parity","body":"parity body"}' "administrative_decisions" - 201

# --- the paths where the money and the legal obligations are ---------------
# Required fields taken from each endpoint's own required() call rather than
# discovered one 400 at a time, so a case that fails is failing on behaviour.
run_case "payroll_batches/create"          POST "payroll_batches/create" \
  '{"month":9,"year":2026}' "payroll_batches" - 201
# penalty_days omitted made this a 400 (`field_required`) on both stacks --
# another rejection dressed up as a successful write, which is exactly the trap
# recorded further down this file. A real create also notifies the employee, so
# `notifications` is compared with it.
run_case "penalties/create"                POST "penalties/create" \
  "{\"employee_id\":$EMP,\"penalty_type\":\"absence\",\"penalty_date\":\"2026-09-01\",\"penalty_days\":1}" \
  "penalties,notifications" - 201
run_case "leave_balances/create"           POST "leave_balances/create" \
  "{\"employee_id\":$EMP,\"year\":2026,\"total_days\":21}" "leave_balance" - 201
run_case "attendance/create"               POST "attendance/create" \
  "{\"employee_id\":$EMP,\"check_in\":\"2026-09-01 09:00:00\",\"check_out\":\"2026-09-01 17:00:00\"}" \
  "attendance" - 201

# Rejections on the same paths: a wrong month, an unknown employee, a bad date.
run_case "payroll_batches (month 13)"      POST "payroll_batches/create" \
  '{"month":13,"year":2026}' "payroll_batches" - 201
run_case "penalties (unknown employee)"    POST "penalties/create" \
  '{"employee_id":99999999,"penalty_type":"absence","penalty_date":"2026-09-01","penalty_days":1}' \
  "penalties,notifications" - 403
run_case "attendance (unknown employee)"   POST "attendance/create" \
  '{"employee_id":99999999,"check_in":"2026-09-01 09:00:00"}' "attendance" - 400
run_case "leave_balances (negative days)"  POST "leave_balances/create" \
  "{\"employee_id\":$EMP,\"year\":2026,\"total_days\":-5}" "leave_balance" - 201


# ===========================================================================
# Core CRUD: the update and delete halves, which had no coverage at all.
#
# Every case below runs as the company-214 employee, because these act on a
# row that must already exist and 244 has none for several of these types.
# The id is resolved from the seed (resolve_or_die), so a case that cannot
# find its row stops the run instead of comparing two 404s.
# ---------------------------------------------------------------------------
run_case "branches/update"                 PUT  "branches/update?id=$C214_BRANCH" \
  '{"name":"Parity Renamed","address":"1 Parity St","radius_meters":150}' "branches" 214 200
run_case "branches/update (unknown id)"    PUT  "branches/update?id=99999999" \
  '{"name":"Nope"}' "branches" 214 500
run_case "branches/generate_qr"            POST "branches/generate_qr?id=$C214_BRANCH" \
  '{"expires_at":"2027-01-01 00:00:00"}' "branches" 214 200
run_case "branches/generate_qr (no expiry)" POST "branches/generate_qr?id=$C214_BRANCH" \
  '{}' "branches" 214 400

run_case "departments/update"              PUT  "departments/update?id=$C214_DEPT" \
  "{\"name\":\"Parity Dept Renamed\",\"branch_ids\":[$C214_BRANCH]}" \
  "departments,department_branches" 214 200
run_case "job_titles/update"               PUT  "job_titles/update?id=$C214_TITLE" \
  "{\"name\":\"Parity Title Renamed\",\"department_id\":$C214_DEPT,\"work_hours\":7}" "job_titles" 214 200
run_case "shifts/create"                   POST "shifts/create" \
  '{"name":"Parity Shift","start_time":"09:00:00","end_time":"17:00:00"}' "shifts" 214 201
run_case "shifts/update"                   PUT  "shifts/update?id=$C214_SHIFT" \
  '{"name":"Parity Shift Renamed","start_time":"08:00:00","end_time":"16:00:00"}' "shifts" 214 200
run_case "shifts/create (no times)"        POST "shifts/create" \
  '{"name":"Parity Shift"}' "shifts" 214 400

run_case "request_types/create"            POST "request_types/create" \
  '{"name":"Parity Request Type"}' "request_types" 214 201
run_case "request_types/update"            PUT  "request_types/update?id=$C214_RTYPE" \
  '{"name":"Parity RT Renamed"}' "request_types" 214 200
run_case "request_types/create (no name)"  POST "request_types/create" \
  '{}' "request_types" 214 400

run_case "holidays/create"                 POST "company_official_holidays/create" \
  '{"name":"Parity Holiday","holiday_date":"2026-12-25"}' "company_official_holidays" 214 201
run_case "holidays/update"                 PUT  "company_official_holidays/update?id=$C214_HOL" \
  '{"name":"Parity Holiday Renamed"}' "company_official_holidays" 214 200
run_case "holidays/delete"                 DELETE "company_official_holidays/delete?id=$C214_HOL" \
  '' "company_official_holidays" 214 200
run_case "holidays/delete (unknown id)"    DELETE "company_official_holidays/delete?id=99999999" \
  '' "company_official_holidays" 214 404

run_case "assets/create"                   POST "assets/create" \
  "{\"employee_id\":$C214_EMP,\"asset_date\":\"2026-09-01\",\"asset_text\":\"Parity laptop\"}" "assets" 214 201
run_case "assets/update"                   PUT  "assets/update?id=$C214_ASSET" \
  '{"asset_text":"Parity laptop renamed","is_returned":true}' "assets" 214 200
run_case "assets/delete"                   DELETE "assets/delete?id=$C214_ASSET" '' "assets" 214 200
run_case "assets/create (no asset_text)"   POST "assets/create" \
  "{\"employee_id\":$C214_EMP,\"asset_date\":\"2026-09-01\"}" "assets" 214 400

run_case "exception_types/create"          POST "attendance_exception_types/create" \
  '{"name":"Parity Exception"}' "exception_types" 214 201
run_case "exception_types/create (no name)" POST "attendance_exception_types/create" \
  '{}' "exception_types" 214 400

echo
echo "identical=$pass  differing=$fail  accepted-divergences=$accepted"
if [ "$fail" -gt 0 ]; then
  echo "details in mutation-diffs.txt"
  # The process verdict must agree with the printed one. `exit 0` meant any
  # wrapper or CI step reading the status accepted a run whose comparisons
  # differed or never completed.
  exit 1
fi
exit 0

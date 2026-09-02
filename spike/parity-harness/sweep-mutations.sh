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
HERE="$(cd "$(dirname "$0")" && pwd)"
DB=parity-harness-db-1
PHP_DB=workin
JAVA_DB=workin_java
PHP=http://localhost:18080/apis/api
JAVA=http://localhost:18081/apis/api

# One harness run at a time. Two concurrent runs reseed the same two databases,
# so each drops the other's schema mid-load and the failures land far from the
# cause -- "Unknown database 'workin' at line 244" from a statement that was
# fine. Cost an hour to attribute, once.
# PARITY_LOCK_HELD lets a parent that already holds the lock run this without
# deadlocking against itself -- sweep-mutations.sh calls seed-two.sh per case.
if [ "${PARITY_LOCK_HELD:-0}" != "1" ]; then
exec 9> /tmp/parity-harness.lock
if ! flock -n 9; then
  echo "FATAL: another parity harness run holds the lock." >&2
  echo "  Both would reseed the same databases and neither result would mean anything." >&2
  echo "  Wait for it, or find it with:  ps -eo pid,cmd | grep -e sweep-mutations -e seed-two" >&2
  exit 7
fi
export PARITY_LOCK_HELD=1
fi

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
snapshot() {  # $1=database $2=table $3=accepted columns $4=server-stamped columns
  # Defaults matter under `set -u`: a three-argument call would otherwise abort
  # on $4, and it would abort IDENTICALLY for both databases -- so the two
  # snapshots would compare equal and a real difference would read as agreement.
  local _accepted="${3:-}" _stamped="${4:-}"
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
  # decided_at belongs here for the same reason, and was added after the
  # mutation sweep flaked on requests/approve: it is set by the SERVER with
  # NOW() (request_actions_helper.php:228), not supplied by the caller, so two
  # sequentially-called stacks legitimately land in different seconds. It is
  # collapsed to null/set like the others, so "Java never wrote decided_at"
  # still fails; only the second-level drift is forgiven, and the drift check
  # below still catches a timezone or default that is hours out.
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
                   AND (column_name IN ('created_at','updated_at','deleted_at','decided_at')
                        OR (column_name = 'uploaded_at' AND table_name = 'employee_docs')
                        OR (column_name = 'expires_at' AND table_name = 'otp_codes'))
                  THEN CONCAT('CASE WHEN \`', column_name, '\` IS NULL THEN ''null'' ELSE ''set'' END')
                  -- qr_code is bin2hex(random_bytes(16)) in PHP and SecureRandom in
                  -- Java, so two CORRECT implementations must differ. Reduced to its
                  -- shape rather than dropped: a wrong length, uppercase hex, or a
                  -- constant that is not random at all still fails the comparison,
                  -- which dropping the column would hide.
                  --
                  -- Columns a specific case is allowed to differ on, passed in
                  -- by run_case from accepted_row_columns().
                  --
                  -- Collapsed to a CONSTANT, not to null/set. The accepted
                  -- divergence these exist for is precisely that one stack
                  -- writes the column and the other leaves it null (D-100 and
                  -- approver_id), so a null/set marker would still differ and
                  -- the acceptance would do nothing.
                  --
                  -- What that gives up, stated rather than implied: this
                  -- comparison can no longer tell whether Java still writes the
                  -- column at all. That is pinned on the Java side instead, by
                  -- LegacyRequestEndToEndTest and LegacyRequestApprovalEndToEndTest,
                  -- which both assert approver_id equals the deciding employee.
                  -- An acceptance without such a test would be a blind spot.
                  -- Case-scoped server-stamped columns: null/set, like the
                  -- audit set, so a missing write still fails.
                  WHEN FIND_IN_SET(column_name, '$_stamped') > 0
                  THEN CONCAT('CASE WHEN \`', column_name, '\` IS NULL THEN ''null'' ELSE ''set'' END')
                  WHEN FIND_IN_SET(column_name, '$_accepted') > 0
                  THEN '''<accepted-divergence>'''
                  -- password_hash is bcrypt with a fresh random salt on every
                  -- write, so two CORRECT implementations must differ. Reduced
                  -- to its shape: a plaintext password, a truncated hash, or a
                  -- different cost still fails.
                  --
                  -- The prefix differs too and is deliberately allowed: PHP's
                  -- password_hash() writes $2y$ and Spring's BCrypt writes $2a$.
                  -- Both are bcrypt and BOTH STACKS VERIFY EITHER -- measured,
                  -- not assumed: PHP's password_verify() accepts the $2a$ hash
                  -- Java wrote, and Java authenticates against the $2y$ hash the
                  -- seed writes on every case in this file. So a password
                  -- changed on one stack still works on the other during a
                  -- shared-database cutover.
                  -- Stored upload URLs carry uniqid('', true), so the two
                  -- stacks must differ in the basename. Reduced to
                  -- <subdir>/<random>.<ext>, keeping the DIRECTORY and the
                  -- EXTENSION, both of which are contract -- and the extension
                  -- especially, because PHP takes it from the client filename
                  -- while Java derives it from the sniffed type.
                  --
                  -- The response body was already normalised this way; the row
                  -- was not, so every upload case reported a row difference for a
                  -- column whose difference is by design.
                  WHEN column_name IN ('logo_url','commercial_reg_url','photo_url','file_url')
                  -- No REGEXP here, deliberately. A dollar anchor inside this
                  -- double-quoted bash string begins ANSI-C quoting and
                  -- silently truncates the generated SQL, so LEFT and
                  -- SUBSTRING_INDEX express the same check without one.
                  -- (This comment spells out dollar rather than using the symbol
                  -- for exactly that reason -- the first version of it broke
                  -- the query it was explaining.)
                  THEN CONCAT('CASE WHEN \`', column_name, '\` IS NULL THEN ''null'' ',
                              'WHEN LEFT(\`', column_name, '\`, 9) = ''/uploads/'' ',
                              'AND LOCATE(''.'', \`', column_name, '\`) > 0 ',
                              'THEN CONCAT(''upload:'', SUBSTRING_INDEX(SUBSTRING(\`', column_name, '\`, 10), ''/'', 1), ',
                              '''/<random>.'', SUBSTRING_INDEX(\`', column_name, '\`, ''.'', -1)) ',
                              'ELSE CONCAT(''UNEXPECTED-SHAPE:'', \`', column_name, '\`) END')
                  WHEN column_name = 'password_hash'
                  THEN CONCAT('CASE WHEN \`', column_name, '\` IS NULL THEN ''null'' ',
                              'WHEN LENGTH(\`', column_name, '\`) = 60 ',
                              'AND LEFT(\`', column_name, '\`, 3) IN (''\$2a'', ''\$2b'', ''\$2y'') ',
                              'THEN CONCAT(''bcrypt-cost-'', SUBSTRING(\`', column_name, '\`, 5, 2)) ',
                              'ELSE CONCAT(''UNEXPECTED-SHAPE:'', LEFT(\`', column_name, '\`, 7)) END')
                  -- An OTP is generated independently by each stack, so the
                  -- two must differ. Reduced to its shape: a code of the wrong
                  -- length, or a non-numeric one, still fails.
                  WHEN column_name = 'code'
                  THEN CONCAT('CASE WHEN \`', column_name, '\` IS NULL THEN ''null'' ',
                              'WHEN \`', column_name, '\` NOT REGEXP ''[^0-9]'' AND LENGTH(\`', column_name, '\`) > 0 ',
                              'THEN CONCAT(''otp-'', LENGTH(\`', column_name, '\`), ''-digits'') ',
                              'ELSE CONCAT(''UNEXPECTED-SHAPE:'', \`', column_name, '\`) END')
                  WHEN column_name = 'qr_code'
                  -- UNHEX rather than a regex, for the dollar-anchor reason
                  -- above: 32 characters, valid hex, and already lowercase.
                  THEN CONCAT('CASE WHEN \`', column_name, '\` IS NULL THEN ''null'' ',
                              'WHEN LENGTH(\`', column_name, '\`) = 32 ',
                              'AND UNHEX(\`', column_name, '\`) IS NOT NULL ',
                              'AND BINARY \`', column_name, '\` = LOWER(\`', column_name, '\`) ',
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
  # A malformed generated query makes mariadb print its version banner or usage
  # instead of the column list, and the banner is non-empty -- so the check
  # above passes and the banner is then used AS the select list, failing every
  # row query and reporting "rows differ" for every case. Happened once, from a
  # '$' inside a double-quoted bash string being read as ANSI-C quoting.
  case "$cols" in
    *MariaDB*|*"Usage:"*|*"ERROR "*)
      echo "COLUMN-QUERY-MALFORMED:$1.$2"
      echo "FATAL: the generated column list is not SQL -- it starts:" >&2
      echo "  A double quote or a dollar-quote sequence inside the query, INCLUDING" >&2
      echo "  inside an SQL comment, splits the bash argument list and mariadb then" >&2
      echo "  prints its usage banner instead of running anything. Three occurrences" >&2
      echo "  so far, twice from a comment explaining the previous one." >&2
      printf '  %s\n' "$(printf '%s' "$cols" | head -c 120)" >&2
      exit 11 ;;
  esac

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
      AND (column_name IN ('created_at','updated_at','deleted_at','decided_at')
           OR (column_name = 'uploaded_at' AND table_name = '$1')
           OR (column_name = 'expires_at' AND table_name = 'otp_codes'))" 2>/dev/null)
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
# A COMPANY session, which is a different auth type from an employee one:
# profile/request_phone_change and confirm_phone_change both refuse anything
# whose token type is not `company` with a 403 before any logic runs, so an
# employee token cannot reach them at all. The credential is the one
# seed-two.sh stamps on company 214.
mint_company_token() {
  curl -s -X POST "$1/auth/login_company" -H 'Content-Type: application/json' \
    -d "{\"phone\":\"$COMPANY_214_PHONE\",\"password\":\"harness-only-Pass123!\"}" \
    | python3 -c "import sys,json; print(json.load(sys.stdin).get('data',{}).get('token',''))"
}
COMPANY_214_PHONE=01555781818
PHONE_244=+201999000001
PHONE_214=+201999000002
# requests/delete.php is requireAuth([EMPLOYEE]) -- a company_admin is refused
# 403 before any logic runs, so that endpoint is only reachable as its owner.
# This actor owns the seeded pending request.
PHONE_EMP=+201999000003

# Divergences the repository has decided to keep, keyed on the endpoint AND the
# exact status pair -- keying on the endpoint alone would let ANY future
# mismatch there be filed under an accepted entry and never reported. Each must
# name the risk or decision that accepted it, so "expected" stays auditable.
# Columns a case is allowed to differ on, keyed on endpoint AND table AND
# column, and each naming the decision that accepted it.
#
# This is deliberately NOT a table-level exclusion. Accepting "requests" whole
# for requests/approve would hide a divergence in status or reply -- the
# columns that actually carry the decision. Only the named column is
# collapsed; everything else in the row is still compared byte for byte.
# Tables whose ROW divergence is accepted for one case, usable ONLY where the
# status pair is itself accepted (see the guard at the call site). That coupling
# is the point: a row difference can only be waived as part of a divergence that
# is already documented, never on its own.
#
# This is for the cases whose whole purpose is to demonstrate a divergence that
# must NOT be reproduced -- where PHP writes and Java refuses, comparing the
# rows and expecting them to match is self-contradictory. What keeps it honest
# is that Java's refusal is pinned by its own test, named below.
# Cheap pre-check: does this endpoint have a row-table waiver at all? Used to
# decide whether the pre-snapshots are worth taking.
# What a registered divergence must still LOOK like. Without this the registry
# accepts any pair of bodies for a matching endpoint/status tuple, so the very
# regression the entry describes -- Java also going empty -- would pass.
divergence_shape_holds() {  # $1=reason $2=php-body-file $3=java-body-file
  case "$1" in
    R-038)
      # PHP must still answer with an EMPTY body, and Java must still answer
      # with a parseable analysis carrying the columns the endpoint is for.
      [ "$(stat -c%s "$2" 2>/dev/null || echo 1)" -eq 0 ] || return 1
      python3 -c "
import json,sys
try:
    d=json.load(open(sys.argv[1]))
except Exception:
    sys.exit(1)
data=d.get('data')
sys.exit(0 if isinstance(data,dict) and data.get('columns') else 1)" "$3" || return 1
      ;;
  esac
  return 0
}

accepted_row_tables_any() {  # $1=path
  case "${1%%\?*}" in
    advances/create|advances/approve|advances/reject|advances/pay|advances/delete) echo "advances" ;;
    branches/update) echo "branches" ;;
  esac
}

accepted_row_tables() {  # $1=path $2=php $3=java  -> stdout: table list, or empty
  case "${1%%\?*}:$2:$3" in
    advances/create:201:403)
      # R-037: PHP inserts an advance against another tenant's employee; Java
      # refuses, so `advances` legitimately differs by exactly that row. Java's
      # refusal is asserted by LegacyAdvanceServiceTest
      # #adminCreateRejectsAnEmployeeIdBelongingToAnotherCompany, so waiving the
      # table here does not leave the behaviour unpinned.
      echo "advances" ;;
    advances/approve:200:404|advances/reject:200:404|advances/pay:200:404|advances/delete:200:404)
      # R-037: PHP mutates the foreign advance, Java does not, so `advances`
      # differs by exactly that row. The delta assertion still requires
      # Java post == pre, so a Java write that returned 404 would fail.
      echo "advances" ;;
    # branches/update:200:404 is deliberately absent. R-036 is a DISCLOSURE,
    # not a write: PHP modifies nothing and merely returns the foreign row, so
    # `branches` must be UNCHANGED on both sides and the ordinary comparison is
    # the right check. Adding a waiver here would hide a PHP that started
    # writing.
  esac
}


# Columns a SPECIFIC case may differ on by a second, because that case's write
# stamps them with the server clock. Case-scoped rather than column-scoped:
# attendance.check_out is caller-supplied in attendance/create and
# attendance/update, and only attendance/check_out stamps it with NOW().
#
# Collapsed to null/set, not to a constant, so "Java never wrote check_out"
# still fails -- only the second of drift is forgiven.
timing_columns() {  # $1=path $2=table -> stdout: column list, or empty
  case "${1%%\?*}:$2" in
    attendance/check_out:attendance|attendance/check_out:) echo "check_out" ;;
  esac
}

# What each stack asked the WhatsApp sender to send.
#
# The OTP endpoints store the code only after the send succeeds, so a case that
# compares the response and `otp_codes` proves the send was ATTEMPTED and
# nothing about it. A stack that built the wrong destination, or the wrong
# message template, still stores a code and still answers 200 -- and
# forgot_password, resend_otp, register_company and request_phone_change all go
# through that path.
#
# whatsapp-stub.py logs every request for exactly this reason. The log is read
# by BYTE OFFSET rather than truncated, so a failing case leaves the evidence in
# place and concurrent reads cannot lose a line.
#
# The generated code is the one thing the two stacks must differ on: each
# generates its own, as two independent systems should. It is reduced to <OTP>
# INSIDE the message parameter only, so the destination jid -- which is also a
# long digit run -- is still compared exactly.
whatsapp_log_offset() {
  stat -c%s "$HERE/whatsapp-stub.log" 2>/dev/null || echo 0
}

whatsapp_log_since() {  # $1=byte offset -> the normalised requests made after it
  tail -c "+$(( ${1:-0} + 1 ))" "$HERE/whatsapp-stub.log" 2>/dev/null | python3 -c "
import re, sys, urllib.parse
out = []
for line in sys.stdin:
    line = line.strip()
    if not line:
        continue
    # '<timestamp> <path?query> <body>' -- the timestamp is the harness talking.
    parts = line.split(' ', 2)
    target = parts[1] if len(parts) > 1 else line
    path, _, query = target.partition('?')
    fields = urllib.parse.parse_qsl(query, keep_blank_values=True)
    rendered = []
    for key, value in fields:
        if key == 'msg':
            value = re.sub(r'[0-9]{4,6}', '<OTP>', value)
        rendered.append(f'{key}={value}')
    body = (parts[2] if len(parts) > 2 else '').strip()
    if body:
        rendered.append('body=' + re.sub(r'[0-9]{4,6}', '<OTP>', body))
    out.append(path + ' ' + ' '.join(rendered))
print('\\n'.join(out))
"
}

# State the harness's OWN instrumentation destroys, re-applied after it runs.
#
# mint_token() logs the actor in, and auth/login_employee DELETES that
# employee's push tokens. So for profile/delete_account -- whose contract is
# that it deactivates the caller and drops their push tokens -- the seeded token
# was already gone before the request, and comparing `push_tokens` proved
# nothing whether or not Java still performed the cleanup. Seeding harder does
# not help: the login is between the seed and the case.
#
# Applied identically to both databases, after both tokens are minted, so the
# two stacks still start the request from the same state.
post_login_fixture() {  # $1=path -> stdout: SQL, or empty
  case "${1%%\?*}" in
    profile/delete_account)
      echo "INSERT INTO push_tokens (id, employee_id, token, platform, updated_at)
            VALUES (999031, 999003, 'parity-delete-account-token', 'android', NOW())
            ON DUPLICATE KEY UPDATE token='parity-delete-account-token';" ;;
  esac
}

accepted_row_columns() {  # $1=path $2=table  -> stdout: column list, or empty
  case "${1%%\?*}:$2" in
    requests/approve:requests|requests/reject:requests)
      # D-100: reject.php (and approve.php in the follow-up slice) write
      # approver_id, which legacy never populates despite the column existing
      # for exactly this purpose. Nothing in legacy reads it, so there is no
      # compatibility cost; it resolves the "approver_id mapping" item the
      # wave specification lists. A deliberate correction, not a preserved bug.
      echo "approver_id" ;;
  esac
}

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
    branches/update:200:404)
      # R-036, the actual disclosure scenario -- not the 500 on a nonexistent id.
      # branches/update.php re-reads WHERE id=? with no company predicate, so a
      # foreign branch id returns THAT COMPANY'S ROW with success:true, including
      # qr_code, while modifying nothing. Java answers 404 and discloses nothing.
      echo "R-036"; return 0 ;;
    advances/approve:200:404|advances/reject:200:404|advances/pay:200:404|advances/delete:200:404)
      # R-037, the actual cross-tenant WRITE scenario. PHP mutates another
      # tenant's advance and answers 200; Java answers 404 and mutates nothing.
      echo "R-037"; return 0 ;;
    profile/register_push_token:500:500)
      # R-013: the endpoint 500s for every caller on both stacks because
      # push_tokens has no company_id column. Under the production posture
      # (DEBUG=false, display_errors=Off) PHP's 500 body is EMPTY while Java
      # answers its normal JSON envelope. The status and the absence of any
      # write are what parity means here; the body of an endpoint that cannot
      # succeed is not a contract either stack is keeping.
      echo "R-013"; return 0 ;;
    employees/analyze_excel:200:200)
      # R-038, and it is narrower than it first looked: PHP answers 200 with
      # Content-Length: 0 only when the sheet has a valid header row and NO
      # DATA ROWS. Mechanism, confirmed by probing the frozen helper rather
      # than inferred -- employee_excel_load_rows() falls into its CSV fallback
      # when the XLSX parses to zero rows (`$format === 'xlsx' && $rawRows ===
      # []`), re-reads the same workbook as text, and returns 12 rows of raw
      # ZIP bytes; that malformed UTF-8 makes respond()'s unchecked
      # json_encode() return false, so nothing is echoed.
      #
      # A sheet WITH a data row analyses correctly on both stacks, which is why
      # `employees/analyze_excel (filled)` exists and is what covers this
      # endpoint. This entry keeps the zero-row divergence visible; it does not
      # count as coverage, and divergence_shape_holds() requires PHP's body to
      # still be empty, so the filled case can never be swallowed by it.
      echo "R-038"; return 0 ;;
    advances/create:201:403)
      # R-037: create.php resolves employee_id with no company predicate, so a
      # company admin creates an advance against another tenant's employee.
      # Java refuses with 403 and writes nothing. Reproducing a cross-tenant
      # financial write is not parity worth having.
      echo "R-037"; return 0 ;;
    advances/approve:500:404|advances/reject:500:404|advances/pay:500:404)
      # R-037: the advances actions write with WHERE id=? and NO company
      # predicate, and re-read the same way. For an id that does not exist the
      # re-read is null and public_row(null) raises a TypeError -> 500. For an
      # id owned by ANOTHER COMPANY the write succeeds -- measured: approve and
      # reject changed a foreign advance's status, pay moved its remaining
      # balance, delete removed the row. Java answers 404 and mutates nothing.
      # Reproducing a cross-tenant write is not parity worth having.
      echo "R-037"; return 0 ;;
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
  local phone; case "$who" in 214) phone=$PHONE_214 ;; emp) phone=$PHONE_EMP ;; *) phone=$PHONE_244 ;; esac

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
  # Java is asked for a heartbeat before tokens are minted. The reseed drops and
  # recreates its database, and a pool that fails to recover surfaces as a login
  # failure, which reads like a credential problem and is not one.
  local jhealth
  jhealth=$(curl -s -m 5 -o /dev/null -w '%{http_code}' "$JAVA/configs/get" 2>/dev/null)
  if [ "$jhealth" != "200" ]; then
    fail=$((fail+1))
    printf '%-42s %-4s %-4s %s\n' "$name" "-" "-" "JAVA-NOT-SERVING (configs/get=$jhealth)"
    { echo "### $name  ($method $path)"
      echo "    JAVA-NOT-SERVING: heartbeat returned $jhealth after reseed."
      echo "    Verdict withheld -- this is the harness, not a parity result."
      echo; } >> mutation-diffs.txt
    return
  fi

  local pt jt
  if [ "$who" = company ]; then
    pt=$(mint_company_token "$PHP"); jt=$(mint_company_token "$JAVA")
  else
    pt=$(mint_token "$PHP" "$phone"); jt=$(mint_token "$JAVA" "$phone")
  fi
  if [ -z "$pt" ] || [ -z "$jt" ]; then
    # Counted, not skipped. A SKIP was reported and tallied as neither
    # identical nor differing, so a run where every login failed printed a
    # clean-looking summary describing nothing that ran.
    fail=$((fail+1))
    printf '%-42s %-4s %-4s %s\n' "$name" "-" "-" "LOGIN-FAILED"
    { echo "### $name  ($method $path)"; echo "    LOGIN-FAILED: php-token=${#pt} java-token=${#jt} chars"; echo; } >> mutation-diffs.txt
    return
  fi

  local post_login; post_login=$(post_login_fixture "$path")
  if [ -n "$post_login" ]; then
    if ! m "$PHP_DB" -e "$post_login" >/dev/null 2>&1 || ! m "$JAVA_DB" -e "$post_login" >/dev/null 2>&1; then
      fail=$((fail+1))
      printf '%-42s %-4s %-4s %s\n' "$name" "-" "-" "POST-LOGIN-FIXTURE-FAILED"
      return
    fi
  fi

  # Pre-call snapshots, so a case can assert a DELTA rather than only an
  # end-state match. Proving Java wrote nothing requires knowing what it looked
  # like before.
  #
  # Taken ONLY for the cases that will use them. Doing it for every case doubled
  # the harness's connection churn around the reseed -- which drops and recreates
  # the Java database -- and left Java's pool unable to recover, so a run of
  # otherwise-passing cases reported LOGIN-FAILED instead.
  declare -A pre_php pre_java
  if [ -n "$(accepted_row_tables_any "$path")" ]; then
    IFS=',' read -ra pretl <<< "$tables"
    for t in "${pretl[@]}"; do
      [ -n "$t" ] || continue
      pre_php[$t]=$(snapshot "$PHP_DB" "$t" "")
      pre_java[$t]=$(snapshot "$JAVA_DB" "$t" "")
    done
  fi

  # Captured per stack, around each request, so the two sends can be told apart.
  local wa_mark wa_php wa_java
  local pcode jcode pbody jbody
  wa_mark=$(whatsapp_log_offset)
  pcode=$(curl -s -o /tmp/mp.json -w '%{http_code}' -X "$method" "$PHP/$path" \
            -H "Authorization: Bearer $pt" -H 'Content-Type: application/json' -d "$body")
  wa_php=$(whatsapp_log_since "$wa_mark")
  wa_mark=$(whatsapp_log_offset)
  jcode=$(curl -s -o /tmp/mj.json -w '%{http_code}' -X "$method" "$JAVA/$path" \
            -H "Authorization: Bearer $jt" -H 'Content-Type: application/json' -d "$body")
  wa_java=$(whatsapp_log_since "$wa_mark")

  # Canonicalise, and blank the audit KEYS for the same reason the
  # row snapshot drops them: sequential calls differ by a second, and that is
  # the harness talking, not the code.
  # Scrubs by KEY, not by value shape. Matching any timestamp-shaped string
  # blanked `check_in` and `check_out` in the response too, so PHP could return
  # 09:00 and Java a different time with the bodies still comparing equal --
  # the same hole the row hash had, on the other side of the comparison.
  # Only the audit keys are nondeterministic between two sequential calls.
  norm() { python3 -c "
import json,os,re,sys
# expires_at is NOT here on purpose. branches/generate_qr sends a
# caller-supplied, deterministic value, so collapsing it would let Java persist
# and return the wrong expiry while the case still passed. Only otp_codes'
# expires_at is server-generated, and that is handled per-table in the row
# snapshot rather than by key here.
AUDIT={'created_at','updated_at','deleted_at','decided_at','uploaded_at'}
# Populated per case by run_case for endpoints that stamp a business column with
# the server clock -- attendance/check_out writes and echoes NOW().
TIMING_KEYS=set((os.environ.get('PARITY_TIMING_KEYS') or '').split(',')) - {''}
# Same rule the row snapshot uses: a value two CORRECT implementations must
# disagree on is reduced to its shape, never dropped. A qr_code of the wrong
# length, in uppercase, or non-hex still compares unequal.
#
# What this canNOT catch, stated rather than implied: a Java constant of the
# right shape (32 lowercase hex) normalises the same as a random one. Proving
# randomness needs more than one sample, so it is asserted where samples are
# available -- LegacyBranchQrRandomnessTest -- not here.
QR=re.compile(r'^[0-9a-f]{32}$')
URL_KEYS={'logo_url','commercial_reg_url','photo_url','file_url'}
UPLOAD_URL=re.compile(r'^/uploads/([^/]+)/[^/]+\\.([A-Za-z0-9]+)$')
def scrub(v,key=None):
    if isinstance(v,dict): return {k:scrub(x,k) for k,x in v.items()}
    if isinstance(v,list): return [scrub(x,key) for x in v]
    if key in AUDIT and v is not None: return '<TS>'
    # A JWT's exp comes from the current second, so two sequentially-called
    # stacks sign different tokens. Compared by DECODED CLAIMS instead, with exp
    # and iat reduced to present/absent -- a token missing a claim, carrying the
    # wrong subject, or not a JWT at all still fails.
    if key in TIMING_KEYS and v is not None: return '<TS>'
    if key=='token' and isinstance(v,str) and v.count('.')==2:
        try:
            import base64
            part=v.split('.')[1]
            part+='='*(-len(part)%4)
            claims=json.loads(base64.urlsafe_b64decode(part))
            for drifting in ('exp','iat','nbf'):
                if drifting in claims: claims[drifting]='<TS>'
            return {'<JWT-claims>': claims}
        except Exception:
            return '<JWT:UNDECODABLE>'
    if key=='qr_code' and isinstance(v,str):
        return '<QR:random-32-lower-hex>' if QR.match(v) else '<QR:UNEXPECTED-SHAPE:'+v+'>'
    # Stored upload URLs: uniqid('', true) on one side, random hex on the other,
    # so the basename must differ. Reduced to <subdir>/<random>.<ext> -- the
    # folder and the EXTENSION are kept, because both are contract: which
    # directory the endpoint writes to, and what it decides to call the file.
    # PHP takes the extension from the client's filename; Java derives it from
    # the sniffed type, so a mismatched upload shows up here rather than being
    # normalised away.
    if key in URL_KEYS and isinstance(v,str) and v:
        m = UPLOAD_URL.match(v)
        return f'<UPLOAD:{m.group(1)}/<random>.{m.group(2)}>' if m else '<UPLOAD:UNEXPECTED-SHAPE:'+v+'>'
    return v
print(json.dumps(scrub(json.load(open(sys.argv[1]))),sort_keys=True,ensure_ascii=False))" "$1" 2>/dev/null || cat "$1"; }
  PARITY_TIMING_KEYS=$(timing_columns "$path" "") \
    pbody=$(norm /tmp/mp.json)
  PARITY_TIMING_KEYS=$(timing_columns "$path" "") \
    jbody=$(norm /tmp/mj.json)

  local verdict=ok detail="" status_accepted=""
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
      # Accepting the STATUS pair does not accept the write. The row snapshots
      # below still run: a regression that keeps returning the approved 404 but
      # performs an unintended write would otherwise be invisible, filed under
      # the very risk that documents the status difference.
      status_accepted="$reason"
    else
      verdict=DIFF; detail="status $pcode vs $jcode"
    fi
  fi
  if [ "$verdict" = ok ] && [ "$pbody" != "$jbody" ] && [ -z "$status_accepted" ]; then
    # A registered divergence can also be body-only: R-038's status pair is
    # 200/200 and the difference is that one body is empty. Checked here so such
    # an entry is matched, not only the status-pair entries above.
    if reason=$(accepted_mutation_divergence "$path" "$pcode" "$jcode"); then
      status_accepted="$reason"
    fi
  fi
  if [ -n "$status_accepted" ] && [ "$pbody" != "$jbody" ]; then
    : # the bodies belong to a registered divergence; the acceptance covers both
  elif [ "$verdict" = ok ] && [ "$pbody" != "$jbody" ]; then
    # 5xx bodies ARE compared now. The blanket exemption existed because
    # DEBUG=true made PHP append a stack trace; with DEBUG=false and
    # display_errors=Off -- the production posture the harness is now pinned to
    # -- those bodies are stable, so exempting them would hide a real difference
    # in what a client is told when something fails.
    verdict=DIFF; detail="response body"
  fi

  # Compared for EVERY case, not a declared list: a case that sends nothing logs
  # nothing on both sides and the comparison is trivially equal, while a case
  # that starts sending -- or stops -- is caught without anyone remembering to
  # opt it in.
  if [ "$wa_php" != "$wa_java" ]; then
    verdict=DIFF
    detail="${detail:+$detail; }whatsapp request"
    { echo "### $name  ($method $path)  whatsapp"
      echo "    PHP  $wa_php"
      echo "    JAVA $wa_java"
      echo; } >> mutation-diffs.txt
  fi

  # State comparison runs even when the response already differed: knowing
  # whether the write also diverged is the more useful half.
  local statediff="" accepted_cols="" timing_cols=""
  IFS=',' read -ra tl <<< "$tables"
  for t in "${tl[@]}"; do
    [ -n "$t" ] || continue
    # A table whose divergence is accepted is NOT skipped. Skipping it passed
    # the case whether or not Java wrote, which contradicted the claim that an
    # unintended Java write would still fail. Instead both halves are asserted:
    #
    #   Java  post == pre   -- it wrote nothing, which is the behaviour we keep
    #   PHP   post != pre   -- it still writes, so the divergence is still real
    #                          and the case fails if legacy ever changes
    if [ -n "$status_accepted" ] && \
       printf '%s' ",$(accepted_row_tables "$path" "$pcode" "$jcode")," | grep -q ",$t,"; then
      local jpost ppost
      jpost=$(snapshot "$JAVA_DB" "$t" "")
      ppost=$(snapshot "$PHP_DB" "$t" "")
      if [ "$jpost" != "${pre_java[$t]}" ]; then
        statediff="$statediff $t(java-wrote-despite-refusing)"
        continue
      fi
      if [ "$ppost" = "${pre_php[$t]}" ]; then
        statediff="$statediff $t(php-no-longer-writes:divergence-gone)"
        continue
      fi
      printf '%-42s %-4s %-4s %s\n' "  ^ divergence asserted" "" "" \
        "$t: java unchanged, php wrote ($status_accepted)"
      continue
    fi
    accepted_cols=$(accepted_row_columns "$path" "$t")
    timing_cols=$(timing_columns "$path" "$t")
    if [ "$(snapshot "$PHP_DB" "$t" "$accepted_cols" "$timing_cols")" != "$(snapshot "$JAVA_DB" "$t" "$accepted_cols" "$timing_cols")" ]; then
      statediff="$statediff $t"
      continue
    fi
    if [ -n "$accepted_cols" ]; then
      # Named so an accepted column can never quietly become the reason a case
      # passes -- the run says which column was collapsed and for which table.
      printf '%-42s %-4s %-4s %s\n' "  ^ accepted column(s)" "" "" "$t.$accepted_cols"
    fi
    drift=$(timestamp_drift "$t")
    if [ -n "$drift" ] && [ "$drift" -gt "$TS_TOLERANCE_SECONDS" ]; then
      statediff="$statediff $t(ts+${drift}s)"
    fi
  done
  if [ -n "$statediff" ]; then verdict=DIFF; detail="${detail:+$detail; }rows differ:$statediff"; fi

  if [ "$verdict" = ok ] && [ -n "$status_accepted" ]; then
    accepted=$((accepted+1))
    printf '%-42s %-4s %-4s %s\n' "$name" "$pcode" "$jcode" "ACCEPTED ($status_accepted), rows verified"
    return
  fi
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


# ---------------------------------------------------------------------------
# Multipart cases.
#
# These endpoints take a file part, so the JSON path above cannot reach them.
# They also write OUTSIDE the database -- a file on disk plus a URL column --
# so a case that compares only rows would miss half the effect.
#
# What is compared, per case:
#   status                pinned from PHP, as everywhere else
#   response body         normalised; the stored URL is reduced to its SHAPE
#                         because uniqid('', true) makes the basename
#                         non-deterministic by design
#   database rows         the usual snapshot
#   files on disk         as a SET of (subdirectory, extension, sha256), so the
#                         random basename is ignored and the CONTENT is not
#
# The uploads directories are cleared before every case and PROVEN empty. A
# failed or partial case must not leave a file that the next case then counts
# as its own -- the reseed handles rows, nothing handled files until now.
# ---------------------------------------------------------------------------

# The PHP uploads directory is whatever the container actually has mounted, not
# a path assumed from the checkout layout -- the compose file has been invoked
# from more than one directory and the two disagree.
PHP_UPLOADS=$(docker inspect parity-harness-php-1 \
  --format '{{range .Mounts}}{{if eq .Destination "/var/www/html/uploads"}}{{.Source}}{{end}}{{end}}' 2>/dev/null)
JAVA_UPLOADS=${JAVA_UPLOADS:-$HERE/java-uploads}

# Fixtures are generated, not committed, so a fresh checkout has none. Build
# them rather than refusing: the documented workflow goes straight from starting
# Java to running this sweep, and an exit here would break it.
# EVERY generated fixture a case consumes, not a sample of them. A predicate
# naming only some of them skips the generator when one of the others is missing
# on its own, and the case that wanted it then reports NO-FIXTURE -- the sweep
# fails without ever testing the coverage it was extended to prove.
PARITY_FIXTURES=(parity.png parity.pdf attendance-punches.xlsx
                 employees-template.xlsx leave_balances-template.xlsx
                 employees-filled.xlsx
                 employees-import-rows.json leave_balances-import-rows.json)
fixtures_missing=0
for f in "${PARITY_FIXTURES[@]}"; do
  [ -s "$HERE/fixtures/$f" ] || fixtures_missing=1
done
if [ "$fixtures_missing" = 1 ]; then
  echo "multipart fixtures missing -- generating them..." >&2
  if ! "$HERE/make-fixtures.sh" >&2; then
    echo "FATAL: could not build the multipart fixtures." >&2
    echo "  They need the stack up and seeded: the spreadsheets are the app's own" >&2
    echo "  template output and the punch log is keyed to a seeded employee_code." >&2
    exit 10
  fi
fi

# The stub the OTP cases need. Started here so the documented workflow does not
# depend on remembering it; a stub already listening is left alone.
if ! curl -s -m 2 -o /dev/null -X POST http://127.0.0.1:18099/send-text -d '{}' 2>/dev/null; then
  echo "starting whatsapp-stub.py on 18099 (needed by the OTP cases)..." >&2
  # 9>&- closes the lock descriptor in the child. Without it the stub INHERITS
  # the flock and holds it after this run exits, so the next run refuses to
  # start with "another parity harness run holds the lock" and no process to
  # point at. Any long-lived child started from inside the lock needs this.
  nohup python3 "$HERE/whatsapp-stub.py" 18099 >/dev/null 2>&1 9>&- &
  for _ in $(seq 1 10); do
    curl -s -m 1 -o /dev/null -X POST http://127.0.0.1:18099/send-text -d '{}' 2>/dev/null && break
    sleep 1
  done
fi
# Four occurrences of quote-in-generated-SQL so far, three from comments. This
# is checked before a run rather than diagnosed after one.
if [ -x "$HERE/check-sql-quoting.sh" ] && ! "$HERE/check-sql-quoting.sh" >/dev/null 2>&1; then
  "$HERE/check-sql-quoting.sh" >&2
  echo "FATAL: SQL quoting check failed -- see above. A run would compare nothing." >&2
  exit 13
fi
assert_upload_dirs() {
  if [ -z "$PHP_UPLOADS" ] || [ ! -d "$PHP_UPLOADS" ]; then
    echo "FATAL: could not resolve the PHP uploads mount from the container." >&2
    echo "  Without it a multipart case cannot see what PHP wrote, and would" >&2
    echo "  report parity from the database alone." >&2
    exit 8
  fi
  mkdir -p "$JAVA_UPLOADS"
  if [ ! -w "$JAVA_UPLOADS" ]; then
    echo "FATAL: $JAVA_UPLOADS is not writable." >&2; exit 8
  fi

  # Apache in the PHP container runs as www-data (uid 33); the bind-mounted
  # host directory is owned by the invoking user. Without this every PHP upload
  # answers 500 file_save_failed and the multipart cases would compare two
  # failures -- the exact "matching errors read as parity" trap, on the one
  # group of endpoints whose whole point is the file side effect.
  chmod 0777 "$PHP_UPLOADS" 2>/dev/null || true
  if ! docker exec parity-harness-php-1 sh -c 'touch /var/www/html/uploads/.probe && rm -f /var/www/html/uploads/.probe' 2>/dev/null; then
    echo "FATAL: the PHP container cannot write to its uploads directory ($PHP_UPLOADS)." >&2
    echo "  Every upload would fail with 500 and the comparison would be meaningless." >&2
    exit 8
  fi
}

# Fingerprint an uploads tree as a SET of (subdir, extension, sha256).
#
# The basename is uniqid('', true) on one side and a random hex on the other, so
# comparing names would always differ; comparing CONTENT is the point. The
# subdirectory and extension are kept because both are part of the contract --
# which folder the endpoint writes to, and what it decides to call the file.
# The first table in the list that carries an upload URL column, if any.
first_url_table() {  # $1=comma-separated tables
  local t
  IFS=',' read -ra _tl <<< "$1"
  for t in "${_tl[@]}"; do
    [ -n "$t" ] || continue
    case "$t" in
      companies|employees|employee_docs) echo "$t"; return ;;
    esac
  done
}

# Raw URL values for that table, unnormalised, so a change is visible.
url_values() {  # $1=database $2=table
  local cols
  case "$2" in
    companies)     cols="logo_url, commercial_reg_url" ;;
    employees)     cols="photo_url" ;;
    employee_docs) cols="file_url" ;;
    *) return ;;
  esac
  m "$1" -N -B -e "SELECT id, $cols FROM \`$2\` ORDER BY id" 2>/dev/null | sha256sum | cut -d' ' -f1
}

upload_fingerprint() {  # $1=directory
  [ -d "$1" ] || { echo "NO-SUCH-DIR:$1"; return; }
  find "$1" -type f -printf '%P\n' 2>/dev/null | while read -r rel; do
    local sub ext
    sub=$(dirname "$rel"); [ "$sub" = "." ] && sub="(root)"
    ext="${rel##*.}"; [ "$ext" = "$rel" ] && ext="(none)"
    printf '%s\t%s\t%s\n' "$sub" "$ext" "$(sha256sum "$1/$rel" | cut -d" " -f1)"
  done | sort
}

clear_uploads() {
  # PHP's uploads are removed FROM INSIDE THE CONTAINER, as root. uploadFile()
  # creates its subdirectory as www-data, so a host-side rm cannot delete files
  # within it -- the guard below caught exactly that, refusing a verdict rather
  # than letting one case's file be counted as the next case's output.
  docker exec parity-harness-php-1 sh -c 'rm -rf /var/www/html/uploads/* /var/www/html/uploads/.[!.]* 2>/dev/null' 2>/dev/null
  rm -rf "${JAVA_UPLOADS:?}"/* 2>/dev/null
  # Proven, not assumed: a directory that could not be cleared makes every
  # later case attribute a leftover file to itself.
  local p j
  p=$(find "$PHP_UPLOADS" -type f 2>/dev/null | wc -l)
  j=$(find "$JAVA_UPLOADS" -type f 2>/dev/null | wc -l)
  if [ "$p" -ne 0 ] || [ "$j" -ne 0 ]; then
    echo "FATAL: uploads directories not empty after clearing (php=$p java=$j)." >&2
    echo "  A multipart case cannot start from a known state, so no verdict is safe." >&2
    exit 9
  fi
}


# NAME | PATH | FIELD | FIXTURE | UPLOAD-FILENAME | EXTRA | TABLES | WHO | EXPECT
#
# FIELD is the multipart part name the endpoint reads ($_FILES[<field>]).
# UPLOAD-FILENAME is sent as the part's filename and is deliberately separate
# from the fixture path, because PHP derives the stored extension from it.
# EXTRA is `k=v;k=v` for endpoints that read ordinary form fields alongside the
# file -- employee_docs/upload takes employee_id and doc_type from $_POST.
run_multipart_case() {
  local name="$1" path="$2" field="$3" fixture="$4" upname="$5" extra="$6" tables="$7" who="${8:-244}" expect="${9:-}"
  case "$who" in -|"") who=244 ;; esac
  local phone; case "$who" in 214) phone=$PHONE_214 ;; emp) phone=$PHONE_EMP ;; *) phone=$PHONE_244 ;; esac

  # Each fixture is checked below, once the `;`-separated list is split; a
  # multi-part case's FIXTURE is not a path on its own.

  if ! ./seed-two.sh >/dev/null 2>&1; then
    fail=$((fail+1)); printf '%-42s %-4s %-4s %s\n' "$name" "-" "-" "RESEED-FAILED"; return
  fi
  clear_uploads

  local jhealth
  jhealth=$(curl -s -m 5 -o /dev/null -w '%{http_code}' "$JAVA/configs/get" 2>/dev/null)
  if [ "$jhealth" != "200" ]; then
    fail=$((fail+1)); printf '%-42s %-4s %-4s %s\n' "$name" "-" "-" "JAVA-NOT-SERVING ($jhealth)"; return
  fi

  local pt jt
  pt=$(mint_token "$PHP" "$phone"); jt=$(mint_token "$JAVA" "$phone")
  if [ -z "$pt" ] || [ -z "$jt" ]; then
    fail=$((fail+1)); printf '%-42s %-4s %-4s %s\n' "$name" "-" "-" "LOGIN-FAILED"; return
  fi

  # Raw (un-normalised) URL values, captured before the request so the write can
  # be asserted as a change rather than only as a shape.
  # Only for a case that expects a successful write. A rejection case (an
  # unsupported file type) and an analyser that stores nothing both legitimately
  # leave every URL untouched, and demanding a change there fails a case that is
  # behaving correctly.
  local url_table="" url_pre_php="" url_pre_java=""
  case "$expect" in
    2*) url_table=$(first_url_table "$tables") ;;
  esac
  # analyze_excel reads a spreadsheet and stores no file, so it has no URL to
  # change even on success.
  case "${path%%\?*}" in
    *analyze_excel) url_table="" ;;
  esac
  if [ -n "$url_table" ]; then
    url_pre_php=$(url_values "$PHP_DB" "$url_table")
    url_pre_java=$(url_values "$JAVA_DB" "$url_table")
  fi

  local -a extra_args=()
  if [ -n "$extra" ]; then
    local IFS=';' kv
    for kv in $extra; do [ -n "$kv" ] && extra_args+=(-F "$kv"); done
  fi

  # FIELD, FIXTURE and UPLOAD-FILENAME may each be a `;`-separated list, for the
  # endpoints that take more than one file part -- complete_company_registration
  # requires both a logo and a commercial registration and refuses with 400 if
  # either is missing, so a single-part runner could only ever reach a refusal.
  local -a file_args=()
  local IFS=';'
  local -a fields=($field) fixtures=($fixture) upnames=($upname)
  unset IFS
  if [ "${#fields[@]}" -ne "${#fixtures[@]}" ] || [ "${#fields[@]}" -ne "${#upnames[@]}" ]; then
    fail=$((fail+1))
    printf '%-42s %-4s %-4s %s\n' "$name" "-" "-" \
      "BAD-CASE (${#fields[@]} fields, ${#fixtures[@]} fixtures, ${#upnames[@]} names)"
    return
  fi
  local i
  for i in "${!fields[@]}"; do
    if [ ! -f "${fixtures[$i]}" ]; then
      fail=$((fail+1))
      printf '%-42s %-4s %-4s %s\n' "$name" "-" "-" "NO-FIXTURE (${fixtures[$i]})"
      return
    fi
    file_args+=(-F "${fields[$i]}=@${fixtures[$i]};filename=${upnames[$i]}")
  done

  # Captured for the same reason the other runners capture: an upload endpoint
  # that starts notifying would otherwise do so unobserved.
  local wa_mark wa_php wa_java
  local pcode jcode
  wa_mark=$(whatsapp_log_offset)
  pcode=$(curl -s -o /tmp/mfp.json -w '%{http_code}' -X POST "$PHP/$path" \
            -H "Authorization: Bearer $pt" "${file_args[@]}" "${extra_args[@]}")
  wa_php=$(whatsapp_log_since "$wa_mark")
  wa_mark=$(whatsapp_log_offset)
  jcode=$(curl -s -o /tmp/mfj.json -w '%{http_code}' -X POST "$JAVA/$path" \
            -H "Authorization: Bearer $jt" "${file_args[@]}" "${extra_args[@]}")
  wa_java=$(whatsapp_log_since "$wa_mark")

  local pbody jbody
  pbody=$(norm /tmp/mfp.json); jbody=$(norm /tmp/mfj.json)

  local verdict=ok detail=""
  if [ "$pcode" = 000 ] || [ "$jcode" = 000 ]; then
    fail=$((fail+1)); printf '%-42s %-4s %-4s %s\n' "$name" "$pcode" "$jcode" "UNREACHABLE"; return
  fi
  if [ -n "$expect" ] && [ "$pcode" != "$expect" ]; then
    fail=$((fail+1))
    printf '%-42s %-4s %-4s %s\n' "$name" "$pcode" "$jcode" "UNEXPECTED-STATUS (case expects $expect)"
    { echo "### $name  (POST $path, multipart $field=$upname)"
      echo "    PHP answered $pcode; this case is declared to expect $expect."
      echo "    PHP  $pcode $(head -c 300 <<< "$pbody")"; echo; } >> mutation-diffs.txt
    return
  fi
  local mp_accepted=""
  if [ "$pcode" != "$jcode" ]; then
    if reason=$(accepted_mutation_divergence "$path" "$pcode" "$jcode"); then
      mp_accepted="$reason"
    else
      verdict=DIFF; detail="status $pcode vs $jcode"
    fi
  fi
  # A registered divergence can be body-only -- R-038's pair is 200/200 and the
  # difference is that PHP's body is empty. The same registry serves both, so a
  # multipart case does not need its own list.
  if [ "$verdict" = ok ] && [ -z "$mp_accepted" ] && [ "$pbody" != "$jbody" ]; then
    if reason=$(accepted_mutation_divergence "$path" "$pcode" "$jcode"); then
      # An accepted divergence is not a licence to accept ANY pair of bodies.
      # R-038 says PHP returns nothing and Java returns the analysis; without
      # checking both, a Java regression to an empty body would compare equal
      # and pass, and any malformed Java body would pass too.
      if ! divergence_shape_holds "$reason" /tmp/mfp.json /tmp/mfj.json; then
        verdict=DIFF
        detail="registered divergence $reason no longer holds (php=$(stat -c%s /tmp/mfp.json)B java=$(stat -c%s /tmp/mfj.json)B)"
      else
        mp_accepted="$reason"
      fi
    else
      verdict=DIFF; detail="response body"
    fi
  fi
  if [ "$wa_php" != "$wa_java" ]; then
    verdict=DIFF; detail="${detail:+$detail; }whatsapp request"
  fi

  # The stored URL must actually CHANGE on both stacks.
  #
  # Normalising every URL in the table to <subdir>/<random>.<ext> means an old
  # value and a newly written one reduce to the SAME string, so a Java that
  # never performed the update -- or updated a different row -- would still
  # compare equal. The shape check cannot see that; a before/after delta can.
  if [ -n "$url_pre_php" ]; then
    local url_post_php url_post_java
    url_post_php=$(url_values "$PHP_DB" "$url_table")
    url_post_java=$(url_values "$JAVA_DB" "$url_table")
    if [ "$url_post_php" = "$url_pre_php" ]; then
      verdict=DIFF; detail="${detail:+$detail; }php did not change any $url_table URL"
    fi
    if [ "$url_post_java" = "$url_pre_java" ]; then
      verdict=DIFF; detail="${detail:+$detail; }java did not change any $url_table URL"
    fi
  fi

  # The file side effect, which is the half a row snapshot cannot see.
  local pfp jfp
  pfp=$(upload_fingerprint "$PHP_UPLOADS"); jfp=$(upload_fingerprint "$JAVA_UPLOADS")
  if [ "$pfp" != "$jfp" ]; then
    verdict=DIFF
    detail="${detail:+$detail; }files differ"
    { echo "### $name  (POST $path, multipart $field=$upname)"
      echo "    FILES DIFFER  (subdir / extension / sha256)"
      echo "    PHP :"; printf '%s\n' "$pfp" | sed 's/^/      /'
      echo "    JAVA:"; printf '%s\n' "$jfp" | sed 's/^/      /'; echo; } >> mutation-diffs.txt
  fi

  local statediff=""
  IFS=',' read -ra tl <<< "$tables"
  for t in "${tl[@]}"; do
    [ -n "$t" ] || continue
    if [ "$(snapshot "$PHP_DB" "$t" "")" != "$(snapshot "$JAVA_DB" "$t" "")" ]; then
      statediff="$statediff $t"
    fi
  done
  [ -n "$statediff" ] && { verdict=DIFF; detail="${detail:+$detail; }rows differ:$statediff"; }

  if [ "$verdict" = ok ] && [ -n "$mp_accepted" ]; then
    accepted=$((accepted+1))
    printf '%-42s %-4s %-4s %s\n' "$name" "$pcode" "$jcode" "ACCEPTED ($mp_accepted), files+rows verified"
    return
  fi
  if [ "$verdict" = ok ]; then
    pass=$((pass+1))
  else
    fail=$((fail+1))
    { echo "### $name  (POST $path, multipart $field=$upname)"
      echo "    $detail"
      echo "    PHP  $pcode $(head -c 260 <<< "$pbody")"
      echo "    JAVA $jcode $(head -c 260 <<< "$jbody")"; echo; } >> mutation-diffs.txt
  fi
  printf '%-42s %-4s %-4s %s %s\n' "$name" "$pcode" "$jcode" "$verdict" "$detail"
}


# NAME | METHOD | PATH | FORM BODY | TABLES | WHO | EXPECT
#
# For endpoints that read $_POST rather than a JSON body. employee_docs/update
# is one: `required($_POST, [ID, DOC_TYPE])`. Sending JSON to it answers 400 on
# both stacks -- a matching rejection, which is not coverage.
run_form_case() {
  local name="$1" method="$2" path="$3" form="$4" tables="$5" who="${6:-244}" expect="${7:-}"
  case "$who" in -|"") who=244 ;; esac
  local phone; case "$who" in 214) phone=$PHONE_214 ;; emp) phone=$PHONE_EMP ;; *) phone=$PHONE_244 ;; esac

  if ! ./seed-two.sh >/dev/null 2>&1; then
    fail=$((fail+1)); printf '%-42s %-4s %-4s %s\n' "$name" "-" "-" "RESEED-FAILED"; return
  fi
  local jhealth
  jhealth=$(curl -s -m 5 -o /dev/null -w '%{http_code}' "$JAVA/configs/get" 2>/dev/null)
  if [ "$jhealth" != "200" ]; then
    fail=$((fail+1)); printf '%-42s %-4s %-4s %s\n' "$name" "-" "-" "JAVA-NOT-SERVING ($jhealth)"; return
  fi
  local pt jt
  pt=$(mint_token "$PHP" "$phone"); jt=$(mint_token "$JAVA" "$phone")
  if [ -z "$pt" ] || [ -z "$jt" ]; then
    fail=$((fail+1)); printf '%-42s %-4s %-4s %s\n' "$name" "-" "-" "LOGIN-FAILED"; return
  fi

  # Captured here too, so a form endpoint that starts sending is caught and the
  # shared comparison never reads an unset variable.
  local wa_mark wa_php wa_java
  local pcode jcode pbody jbody
  wa_mark=$(whatsapp_log_offset)
  pcode=$(curl -s -o /tmp/mfp.json -w '%{http_code}' -X "$method" "$PHP/$path" \
            -H "Authorization: Bearer $pt" -d "$form")
  wa_php=$(whatsapp_log_since "$wa_mark")
  wa_mark=$(whatsapp_log_offset)
  jcode=$(curl -s -o /tmp/mfj.json -w '%{http_code}' -X "$method" "$JAVA/$path" \
            -H "Authorization: Bearer $jt" -d "$form")
  wa_java=$(whatsapp_log_since "$wa_mark")
  pbody=$(norm /tmp/mfp.json); jbody=$(norm /tmp/mfj.json)

  local verdict=ok detail=""
  if [ "$pcode" = 000 ] || [ "$jcode" = 000 ]; then
    fail=$((fail+1)); printf '%-42s %-4s %-4s %s\n' "$name" "$pcode" "$jcode" "UNREACHABLE"; return
  fi
  if [ -n "$expect" ] && [ "$pcode" != "$expect" ]; then
    fail=$((fail+1))
    printf '%-42s %-4s %-4s %s\n' "$name" "$pcode" "$jcode" "UNEXPECTED-STATUS (case expects $expect)"
    { echo "### $name  ($method $path, form)"; echo "    PHP answered $pcode; declared $expect."
      echo "    PHP  $pcode $(head -c 260 <<< "$pbody")"; echo; } >> mutation-diffs.txt
    return
  fi
  [ "$pcode" = "$jcode" ] || { verdict=DIFF; detail="status $pcode vs $jcode"; }
  if [ "$verdict" = ok ] && [ "$pbody" != "$jbody" ]; then verdict=DIFF; detail="response body"; fi
  if [ "$wa_php" != "$wa_java" ]; then
    verdict=DIFF; detail="${detail:+$detail; }whatsapp request"
  fi

  local statediff=""
  IFS=',' read -ra tl <<< "$tables"
  for t in "${tl[@]}"; do
    [ -n "$t" ] || continue
    [ "$(snapshot "$PHP_DB" "$t" "")" != "$(snapshot "$JAVA_DB" "$t" "")" ] && statediff="$statediff $t"
  done
  [ -n "$statediff" ] && { verdict=DIFF; detail="${detail:+$detail; }rows differ:$statediff"; }

  if [ "$verdict" = ok ]; then pass=$((pass+1)); else
    fail=$((fail+1))
    { echo "### $name  ($method $path, form)"; echo "    $detail"
      echo "    PHP  $pcode $(head -c 240 <<< "$pbody")"
      echo "    JAVA $jcode $(head -c 240 <<< "$jbody")"; echo; } >> mutation-diffs.txt
  fi
  printf '%-42s %-4s %-4s %s %s\n' "$name" "$pcode" "$jcode" "$verdict" "$detail"
}


# NAME | PREP-PATH | PREP-BODY | ACT-PATH | ACT-BODY-TEMPLATE | TABLES | EXPECT
#
# The OTP flows, which cannot use the ordinary runner because the code is a
# per-stack secret: each stack generates its own and stores it in its own
# database. Sending one stack's code to the other would fail for a reason that
# has nothing to do with parity, so the runner reads each code from the database
# that stack wrote it to and substitutes it into that stack's request.
#
# This is the same shape as the token handling every other case already uses --
# mint_token asks each stack for its own token for exactly this reason.
#
# {OTP} in the act body is replaced per stack.
run_otp_case() {
  local name="$1" prep_path="$2" prep_body="$3" act_path="$4" act_tpl="$5" tables="$6" expect="${7:-}"
  # Optional 8th argument: `company` runs both halves inside a company session.
  # The auth flows below need none -- forgot_password and verify_otp are public
  # -- but the profile phone-change pair is company-only.
  local who="${8:-}"

  if ! ./seed-two.sh >/dev/null 2>&1; then
    fail=$((fail+1)); printf '%-42s %-4s %-4s %s\n' "$name" "-" "-" "RESEED-FAILED"; return
  fi
  local jhealth
  jhealth=$(curl -s -m 5 -o /dev/null -w '%{http_code}' "$JAVA/configs/get" 2>/dev/null)
  if [ "$jhealth" != "200" ]; then
    fail=$((fail+1)); printf '%-42s %-4s %-4s %s\n' "$name" "-" "-" "JAVA-NOT-SERVING ($jhealth)"; return
  fi

  # Ask each stack to issue a code. A failure here is the harness (most often
  # the WhatsApp stub being down), not a parity result, so it is reported as
  # such rather than compared.
  local pauth=() jauth=()
  if [ "$who" = company ]; then
    local pt jt
    pt=$(mint_company_token "$PHP"); jt=$(mint_company_token "$JAVA")
    if [ -z "$pt" ] || [ -z "$jt" ]; then
      fail=$((fail+1))
      printf '%-42s %-4s %-4s %s\n' "$name" "-" "-" "LOGIN-FAILED (company session)"
      return
    fi
    pauth=(-H "Authorization: Bearer $pt"); jauth=(-H "Authorization: Bearer $jt")
  fi

  local pprep jprep
  pprep=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$PHP/$prep_path" \
            "${pauth[@]}" -H 'Content-Type: application/json' -d "$prep_body")
  jprep=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$JAVA/$prep_path" \
            "${jauth[@]}" -H 'Content-Type: application/json' -d "$prep_body")
  if [ "$pprep" != "200" ] || [ "$jprep" != "200" ]; then
    fail=$((fail+1))
    printf '%-42s %-4s %-4s %s\n' "$name" "$pprep" "$jprep" "OTP-PREP-FAILED (is whatsapp-stub.py running?)"
    return
  fi

  local pcode jcode_otp
  pcode=$(m "$PHP_DB"  -N -B -e "SELECT code FROM otp_codes ORDER BY id DESC LIMIT 1")
  jcode_otp=$(m "$JAVA_DB" -N -B -e "SELECT code FROM otp_codes ORDER BY id DESC LIMIT 1")
  if [ -z "$pcode" ] || [ -z "$jcode_otp" ]; then
    fail=$((fail+1))
    printf '%-42s %-4s %-4s %s\n' "$name" "-" "-" "OTP-NOT-STORED (php=${pcode:-none} java=${jcode_otp:-none})"
    return
  fi

  local pbody_req jbody_req pcode_http jcode_http
  pbody_req=${act_tpl//\{OTP\}/$pcode}
  jbody_req=${act_tpl//\{OTP\}/$jcode_otp}
  local wa_mark wa_php wa_java
  wa_mark=$(whatsapp_log_offset)
  pcode_http=$(curl -s -o /tmp/op.json -w '%{http_code}' -X POST "$PHP/$act_path" \
                 "${pauth[@]}" -H 'Content-Type: application/json' -d "$pbody_req")
  wa_php=$(whatsapp_log_since "$wa_mark")
  wa_mark=$(whatsapp_log_offset)
  jcode_http=$(curl -s -o /tmp/oj.json -w '%{http_code}' -X POST "$JAVA/$act_path" \
                 "${jauth[@]}" -H 'Content-Type: application/json' -d "$jbody_req")
  wa_java=$(whatsapp_log_since "$wa_mark")

  local pb jb; pb=$(norm /tmp/op.json); jb=$(norm /tmp/oj.json)
  local verdict=ok detail=""
  # The ACT half's send. The PREP half is a send too, and is compared by
  # whichever case exercises that endpoint directly -- forgot_password and
  # request_phone_change both have one.
  if [ "$wa_php" != "$wa_java" ]; then
    verdict=DIFF; detail="whatsapp request"
  fi
  if [ -n "$expect" ] && [ "$pcode_http" != "$expect" ]; then
    fail=$((fail+1))
    printf '%-42s %-4s %-4s %s\n' "$name" "$pcode_http" "$jcode_http" "UNEXPECTED-STATUS (case expects $expect)"
    { echo "### $name  (POST $act_path, otp flow)"; echo "    PHP answered $pcode_http; declared $expect."
      echo "    PHP  $pcode_http $(head -c 240 <<< "$pb")"; echo; } >> mutation-diffs.txt
    return
  fi
  [ "$pcode_http" = "$jcode_http" ] || { verdict=DIFF; detail="status $pcode_http vs $jcode_http"; }
  if [ "$verdict" = ok ] && [ "$pb" != "$jb" ]; then verdict=DIFF; detail="response body"; fi

  local statediff=""
  IFS=',' read -ra tl <<< "$tables"
  for t in "${tl[@]}"; do
    [ -n "$t" ] || continue
    [ "$(snapshot "$PHP_DB" "$t" "")" != "$(snapshot "$JAVA_DB" "$t" "")" ] && statediff="$statediff $t"
  done
  [ -n "$statediff" ] && { verdict=DIFF; detail="${detail:+$detail; }rows differ:$statediff"; }

  if [ "$verdict" = ok ]; then pass=$((pass+1)); else
    fail=$((fail+1))
    { echo "### $name  (POST $act_path, otp flow)"; echo "    $detail"
      echo "    PHP  $pcode_http $(head -c 240 <<< "$pb")"
      echo "    JAVA $jcode_http $(head -c 240 <<< "$jb")"; echo; } >> mutation-diffs.txt
  fi
  printf '%-42s %-4s %-4s %s %s\n' "$name" "$pcode_http" "$jcode_http" "$verdict" "$detail"
}

./seed-two.sh >/dev/null 2>&1
assert_java_on_its_own_database
assert_upload_dirs

# The run's own result, consumed by coverage-report.sh: a case counts as
# covering an endpoint only if it actually passed here, never because it
# declared a 2xx.
: > last-run.txt
exec > >(tee -a last-run.txt)

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

# The identities the registration cases mint. Asserted, not asserted-in-a-comment:
# register_company answers 400 phone_already_registered and join_company 409 if
# the number is taken, so a snapshot that ever contains one turns a success case
# into a refusal. The case's declared 201 would catch it, but this says which
# number and why instead of leaving a status mismatch to be diagnosed.
REGISTER_COMPANY_PHONE=01099911002
JOIN_COMPANY_PHONE=01099911003
HR_CREATE_PHONE=01099911004
PHONE_CHANGE_PHONE=01099911005
# BOTH tables for every number, not the one the endpoint reads first: the
# uniqueness rules cross over. join_company refuses a phone that belongs to an
# employee (409 phone_already_used) OR to another company
# (company_phone_exists_globally), and request_phone_change refuses one held by
# any other company. Checking a single table per number would leave the case
# that fails hardest -- a cross-table collision -- unasserted.
assert_identity_free() {  # $1=label $2=phone
  local table taken
  for table in companies employees; do
    taken=$(m "$PHP_DB" -N -B -e "SELECT COUNT(*) FROM \`$table\` WHERE phone='$2'")
    if [ "${taken:-1}" != "0" ]; then
      echo "FATAL: $1 ($2) already exists in $table -- the case would answer a" >&2
      echo "  refusal, not the success path it declares. Pick another number." >&2
      exit 5
    fi
  done
}
assert_identity_free register_company "$REGISTER_COMPANY_PHONE"
assert_identity_free join_company     "$JOIN_COMPANY_PHONE"
assert_identity_free hr_create        "$HR_CREATE_PHONE"
assert_identity_free phone_change     "$PHONE_CHANGE_PHONE"

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

# Mutable fixtures seeded by seed-two.sh. The snapshot's own penalty is
# applied_to_payroll=1 and its payroll batch is finalized, so cases against
# them exercise only the refusal path -- both stacks refuse, the case passes,
# and no mutation was ever compared.
C214_PEN_OPEN=$(resolve_or_die c214_pen_open "SELECT id FROM penalties WHERE id=999010 AND applied_to_payroll=0")
C214_BATCH_DRAFT=$(resolve_or_die c214_batch_draft "SELECT id FROM payroll_batches WHERE id=999011 AND status='draft'")
C214_PAYSLIP_DRAFT=$(resolve_or_die c214_payslip_draft "SELECT id FROM payslips WHERE id=999012 AND batch_id=999011")
# notification_inbox_filter() scopes an employee token to
# to_employee_id = <caller> AND recipient_kind='employee'
# (helpers/notifications.php:26), so a company-scoped row -- all the snapshot
# has -- is not found and mark_read/delete answer 404 on both stacks.
C214_NOTIF_INBOX=$(resolve_or_die c214_notif_inbox "SELECT id FROM notifications WHERE id=999013 AND to_employee_id=999002 AND recipient_kind='employee'")
C214_DECISION=$(resolve_or_die c214_decision "SELECT id FROM administrative_decisions WHERE id=999015 AND company_id=214")
# An employee of a DIFFERENT company, for the cross-tenant probes. R-037.
FOREIGN_EMP=$(resolve_or_die foreign_emp "SELECT id FROM employees WHERE company_id NOT IN (214,244) AND is_active=1 ORDER BY id LIMIT 1")
FOREIGN_BRANCH=$(resolve_or_die foreign_branch "SELECT id FROM branches WHERE company_id NOT IN (214,244) ORDER BY id LIMIT 1")
FOREIGN_ADV=$(resolve_or_die foreign_adv "SELECT a.id FROM advances a JOIN employees e ON e.id=a.employee_id WHERE e.company_id NOT IN (214,244) AND a.status='pending' ORDER BY a.id LIMIT 1")
# Fixtures for the endpoints whose only case was a refusal, plus the update and
# delete halves that needed a row their own create would have made.
C214_BRANCH_EMPTY=$(resolve_or_die branch_empty "SELECT b.id FROM branches b WHERE b.id=999020 AND NOT EXISTS (SELECT 1 FROM employees e WHERE e.branch_id=b.id)")
C214_EMP_FREE=$(resolve_or_die emp_free "SELECT id FROM employees WHERE id=999021 AND company_id=214")
C214_RTYPE_UNUSED=$(resolve_or_die rtype_unused "SELECT t.id FROM request_types t WHERE t.id=999022 AND NOT EXISTS (SELECT 1 FROM requests r WHERE r.request_type_id=t.id)")
C214_ATT_OPEN=$(resolve_or_die att_open "SELECT id FROM attendance WHERE id=999023 AND check_out IS NULL")
C214_ATT_OPEN_EMP=$(resolve_or_die att_open_emp "SELECT employee_id FROM attendance WHERE id=999023 AND check_out IS NULL")
C214_EXCTYPE=$(resolve_or_die exctype "SELECT id FROM exception_types WHERE id=999024 AND company_id=214")
C214_WFP=$(resolve_or_die wfp "SELECT id FROM workforce_planning WHERE id=999025 AND company_id=214")
C214_DOC=$(resolve_or_die doc "SELECT id FROM employee_docs WHERE id=999026")
C214_REQ_PENDING=$(resolve_or_die c214_req_pending "SELECT r.id FROM requests r JOIN request_types t ON t.id=r.request_type_id WHERE r.id=999014 AND r.status='pending' AND t.deduct_balance=1 AND t.add_attendance_exception=1")
# A SECOND employee, so payslips/create has someone without a payslip in the
# draft batch -- the fixture above already occupies the first one, and create
# would otherwise answer "already exists" rather than creating anything.
C214_EMP2=$(resolve_or_die c214_emp2 "SELECT id FROM employees WHERE company_id=214 AND id NOT IN (999002, (SELECT employee_id FROM payslips WHERE id=999012)) ORDER BY id LIMIT 1")

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
# supplying start_time/end_time makes update() broadcast to every employee in
# the company, so the notification rows are part of this write.
run_case "shifts/update"                   PUT  "shifts/update?id=$C214_SHIFT" \
  '{"name":"Parity Shift Renamed","start_time":"08:00:00","end_time":"16:00:00"}' "shifts,notifications" 214 200
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


# ===========================================================================
# Money and legal obligations: the update, delete and state-transition halves.
#
# These are the endpoints where a divergence is not a cosmetic difference --
# an advance that pays twice, a payroll batch that finalises on one stack and
# not the other, a payslip whose deductions differ. Every case names the tables
# whose ROWS are compared, not just the response, because an endpoint can
# answer identically and still persist differently.
# ---------------------------------------------------------------------------
# R-037, fifth endpoint: advances/create.php resolves the employee without a
# company predicate, so a company admin can create a financial obligation
# against ANOTHER tenant's employee. Measured: PHP 201 and a row, Java 403 and
# none. The status pair is registered as accepted; the row snapshot still runs,
# so an unintended Java write would still fail the case.
# ===========================================================================
# The cross-tenant scenarios R-036 and R-037 actually describe.
#
# The unknown-id cases below cover the 500-vs-404 crash; these cover the part
# that matters -- an id that EXISTS and belongs to another company. Without
# them the risks were documented but not guarded, and a Java regression that
# started disclosing or mutating foreign rows would not have been detected.
# ---------------------------------------------------------------------------
# R-036: PHP returns the foreign branch row (success:true, "Branch updated",
# including qr_code) while modifying nothing. No table waiver is given -- both
# sides must leave `branches` unchanged, so a Java WRITE here still fails.
run_case "branches/update (foreign branch)" PUT  "branches/update?id=$FOREIGN_BRANCH" \
  '{"name":"parity cross-tenant probe"}' "branches" 214 200

# R-037: PHP mutates the foreign advance; Java refuses. The delta assertion
# requires Java post == pre, so a Java write returning 404 would fail.
run_case "advances/approve (foreign)"      PUT  "advances/approve?id=$FOREIGN_ADV" \
  '{}' "advances" 214 200
run_case "advances/reject (foreign)"       PUT  "advances/reject?id=$FOREIGN_ADV" \
  '{"rejection_reason":"parity cross-tenant probe"}' "advances" 214 200
run_case "advances/pay (foreign)"          PUT  "advances/pay?id=$FOREIGN_ADV" \
  '{"amount":1}' "advances" 214 200
run_case "advances/delete (foreign)"       DELETE "advances/delete?id=$FOREIGN_ADV" \
  '' "advances" 214 200

run_case "advances/create (foreign employee)" POST "advances/create" \
  "{\"employee_id\":$FOREIGN_EMP,\"amount\":100,\"reason\":\"parity cross-tenant probe\",\"deduction_mode\":\"single_payroll_month\",\"deduction_payroll_year\":2026,\"deduction_payroll_month\":9}" \
  "advances" 214 201
run_case "advances/approve"                PUT  "advances/approve?id=$C214_ADV" \
  '{}' "advances,notifications" 214 200
run_case "advances/approve (unknown id)"   PUT  "advances/approve?id=99999999" \
  '{}' "advances" 214 500
run_case "advances/reject"                 PUT  "advances/reject?id=$C214_ADV" \
  '{"rejection_reason":"parity reject"}' "advances,notifications" 214 200
run_case "advances/reject (no reason)"     PUT  "advances/reject?id=$C214_ADV" \
  '{}' "advances" 214 400
run_case "advances/pay"                    PUT  "advances/pay?id=$C214_ADV" \
  '{"amount":50}' "advances" 214 200
run_case "advances/pay (no amount)"        PUT  "advances/pay?id=$C214_ADV" \
  '{}' "advances" 214 400
run_case "advances/update"                 PUT  "advances/update?id=$C214_ADV" \
  '{"amount":75,"reason":"parity updated"}' "advances" 214 200
run_case "advances/delete"                 DELETE "advances/delete?id=$C214_ADV" \
  '' "advances" 214 200

run_case "penalties/update"                PUT  "penalties/update?id=$C214_PEN_OPEN" \
  '{"penalty_days":2}' "penalties,notifications" 214 200
run_case "penalties/update (applied)"      PUT  "penalties/update?id=$C214_PEN" \
  '{"penalty_days":2}' "penalties" 214 403
run_case "penalties/delete"                DELETE "penalties/delete?id=$C214_PEN_OPEN" \
  '' "penalties,notifications" 214 200
run_case "penalties/delete (unknown id)"   DELETE "penalties/delete?id=99999999" \
  '' "penalties" 214 400

run_case "leave_balances/update"           PUT  "leave_balances/update?id=$C214_LB" \
  '{"total_days":25}' "leave_balance" 214 200
run_case "leave_balances/delete"           DELETE "leave_balances/delete?id=$C214_LB" \
  '' "leave_balance" 214 200
run_case "leave_balances/generate"         POST "leave_balances/generate" \
  '{"year":2026,"total_days":21}' "leave_balance" 214 200

run_case "salary_contracts/create"         POST "salary_contracts/create" \
  "{\"employee_id\":$C214_EMP,\"effective_from\":\"2026-09-01\",\"basic_salary\":5000}" \
  "salary_contracts" 214 201
run_case "salary_contracts/update"         PUT  "salary_contracts/update?id=$C214_SC" \
  '{"basic_salary":5500}' "salary_contracts" 214 200
run_case "salary_contracts/delete"         DELETE "salary_contracts/delete?id=$C214_SC" \
  '' "salary_contracts" 214 200
run_case "salary_contracts/create (no from)" POST "salary_contracts/create" \
  "{\"employee_id\":$C214_EMP,\"basic_salary\":5000}" "salary_contracts" 214 400

# Payroll state machine. calculate/finalize/reopen are the transitions where a
# divergence strands a batch in a state the other stack cannot reach.
run_case "payroll_batches/update"          PUT  "payroll_batches/update?id=$C214_BATCH_DRAFT" \
  '{"month":10,"year":2026}' "payroll_batches" 214 200
run_case "payroll_batches/calculate"       POST "payroll_batches/calculate?id=$C214_BATCH_DRAFT" \
  '' "payroll_batches,payslips" 214 200
# finalize does not only flip a status: it applies advance deductions
# (updateAdvanceRemaining) and marks penalties applied_to_payroll. The seeded
# open penalty belongs to the draft payslip's employee and falls in the batch
# period, so this case definitely writes both tables -- comparing only
# payroll_batches and payslips would let the two stacks disagree on payroll
# deductions while the run stayed green.
run_case "payroll_batches/finalize"        PUT  "payroll_batches/finalize?id=$C214_BATCH_DRAFT" \
  '' "payroll_batches,payslips,penalties,advances" 214 200
# reopen against the FINALIZED snapshot batch, which is the success path. The
# draft-batch case below is the refusal, and a refusal alone is not coverage.
run_case "payroll_batches/reopen (finalized)" PUT  "payroll_batches/reopen?id=$C214_BATCH" \
  '' "payroll_batches,payslips,penalties,advances" 214 200
# reopen reverses the same side effects (restoreAdvancesAndUnmarkPenalties).
run_case "payroll_batches/reopen"          PUT  "payroll_batches/reopen?id=$C214_BATCH_DRAFT" \
  '' "payroll_batches,payslips,penalties,advances" 214 400
run_case "payroll_batches/delete"          DELETE "payroll_batches/delete?id=$C214_BATCH_DRAFT" \
  '' "payroll_batches,payslips" 214 200
run_case "payroll_batches/calc (unknown)"  POST "payroll_batches/calculate?id=99999999" \
  '' "payroll_batches,payslips" 214 404

run_case "payslips/update"                 PUT  "payslips/update?id=$C214_PAYSLIP_DRAFT" \
  '{"overtime_hours":3,"other_deductions":10}' "payslips" 214 200
run_case "payslips/delete"                 DELETE "payslips/delete?id=$C214_PAYSLIP_DRAFT" \
  '' "payslips,payroll_batches" 214 200
run_case "payslips/update (finalized)"     PUT  "payslips/update?id=$C214_PAYSLIP" \
  '{"other_deductions":10}' "payslips" 214 400
run_case "payslips/create"                 POST "payslips/create" \
  "{\"batch_id\":$C214_BATCH_DRAFT,\"employee_id\":$C214_EMP2}" "payslips" 214 201
run_case "payslips/create (no employee)"   POST "payslips/create" \
  "{\"batch_id\":$C214_BATCH_DRAFT}" "payslips" 214 400


# ===========================================================================
# Attendance, requests, people and org settings.
#
# Attendance is the highest-volume table in the system and the one whose
# writes clients make constantly, so check_in/check_out/update/delete are
# compared against the attendance rows themselves, not only the response.
# ---------------------------------------------------------------------------
run_case "attendance/update"               PUT  "attendance/update?id=$C214_ATT" \
  '{"check_in":"2026-09-01 09:00:00","check_out":"2026-09-01 17:00:00"}' "attendance" 214 200
run_case "attendance/update (unknown id)"  PUT  "attendance/update?id=99999999" \
  '{"check_in":"2026-09-01 09:00:00"}' "attendance" 214 404
run_case "attendance/delete"               DELETE "attendance/delete?id=$C214_ATT" '' "attendance" 214 200
run_case "attendance/delete (unknown id)"  DELETE "attendance/delete?id=99999999" '' "attendance" 214 404
run_case "attendance/check_in"             POST "attendance/check_in" \
  '{"latitude":30.0211667,"longitude":31.4545278,"method":"app"}' "attendance" 214 200
run_case "attendance/check_in (no coords)" POST "attendance/check_in" \
  '{"method":"app"}' "attendance" 214 400
# (The refusal case that used to live here expected 400 because no check-in was
# open. seed-two.sh now seeds one, so the success case below replaces it -- a
# case whose expectation the fixtures invalidated is worse than no case.)
run_case "attendance/delete_range"         DELETE "attendance/delete_range?employee_id=$C214_EMP&from=2026-09-01&to=2026-09-30" \
  '' "attendance" 214 200

run_case "requests/approve"                POST "requests/approve?id=$C214_REQ_PENDING" \
  '{}' "requests,notifications,leave_balance,attendance" 214 200
run_case "requests/reject"                 POST "requests/reject?id=$C214_REQ_PENDING" \
  '{}' "requests,notifications,leave_balance,attendance" 214 200
run_case "requests/approve (no id)"        POST "requests/approve" '{}' "requests" 214 400
# requests/delete.php is requireAuth([EMPLOYEE]): a company_admin is refused
# before any logic runs, so the endpoint is only reachable as its owner.
run_case "requests/delete (admin refused)" DELETE "requests/delete?id=$C214_REQ_PENDING" '' "requests" 214 403
run_case "requests/delete"                 DELETE "requests/delete?id=$C214_REQ_PENDING" '' "requests" emp 200

run_case "complaints/create"               POST "complaints/create" \
  '{"name":"Parity","phone":"+2010000000","message":"parity complaint"}' "complaints" 214 200
run_case "complaints/create (no message)"  POST "complaints/create" \
  '{"name":"Parity","phone":"+2010000000"}' "complaints" 214 400
run_case "complaints/update"               POST "complaints/update?id=$C214_COMPLAINT" \
  '{"reply":"parity reply","status":"done"}' "complaints" 214 200

run_case "notifications/mark_read"         PUT  "notifications/mark_read?id=$C214_NOTIF_INBOX" \
  '' "notifications" 214 200
run_case "notifications/delete"            DELETE "notifications/delete?id=$C214_NOTIF_INBOX" \
  '' "notifications" 214 200
run_case "notifications/send"              POST "notifications/send" \
  "{\"title\":\"Parity\",\"to_employee_id\":$C214_EMP,\"body\":\"parity body\"}" "notifications" 214 201
run_case "notifications/send (no title)"   POST "notifications/send" \
  "{\"to_employee_id\":$C214_EMP}" "notifications" 214 400

run_case "company_settings/update"         PUT  "company_settings/update?setting_definition_id=$C214_SETTING" \
  '{"values":[]}' "company_settings,company_setting_values" 214 200
run_case "workforce_planning/save_target"  POST "workforce_planning/save_target" \
  "{\"branch_id\":$C214_BRANCH,\"department_id\":$C214_DEPT,\"job_title_id\":$C214_TITLE,\"planned_count\":5}" \
  "workforce_planning" 214 200
run_case "workforce_planning (no count)"   POST "workforce_planning/save_target" \
  "{\"branch_id\":$C214_BRANCH,\"department_id\":$C214_DEPT,\"job_title_id\":$C214_TITLE}" \
  "workforce_planning" 214 400
run_case "schedules/assign"                POST "schedules/assign_employee_schedule" \
  "{\"employee_id\":$C214_EMP,\"shift_id\":$C214_SHIFT,\"dates\":[\"2026-09-10\"]}" \
  "employee_schedules,notifications" 214 200
run_case "schedules/generate"              POST "schedules/generate_employee_schedule" \
  "{\"employee_id\":$C214_EMP,\"from_date\":\"2026-09-01\",\"to_date\":\"2026-09-07\"}" \
  "employee_schedules" 214 200


# ===========================================================================
# The remaining deletes, employees, profile and company.
#
# Deletes were the largest single gap: every module had its create and update
# compared while the destructive half went untested.
# ---------------------------------------------------------------------------
# 409 is the CORRECT answer here: the seeded branch has employees assigned,
# and delete.php refuses rather than orphaning them. Pinned as the semantic,
# not worked around.
run_case "branches/delete (has employees)" DELETE "branches/delete?id=$C214_BRANCH" '' "branches,department_branches" 214 409
run_case "departments/delete"              DELETE "departments/delete?id=$C214_DEPT" '' "departments,department_branches" 214 200
run_case "job_titles/delete"               DELETE "job_titles/delete?id=$C214_TITLE" '' "job_titles" 214 200
run_case "shifts/delete"                   DELETE "shifts/delete?id=$C214_SHIFT" '' "shifts" 214 200
run_case "request_types/delete (in use)"   DELETE "request_types/delete?id=$C214_RTYPE" '' "request_types,requests" 214 409
run_case "admin_decisions/update"          PUT  "administrative_decisions/update?id=$C214_DECISION" \
  '{"title":"Parity Renamed"}' "administrative_decisions" 214 200
run_case "admin_decisions/delete"          DELETE "administrative_decisions/delete?id=$C214_DECISION" \
  '' "administrative_decisions" 214 200
run_case "workforce_planning/create"       POST "workforce_planning/create" \
  "{\"branch_id\":$C214_BRANCH,\"department_id\":$C214_DEPT,\"job_title_id\":$C214_TITLE,\"planned_count\":7}" \
  "workforce_planning" 214 201
run_case "notifications/unread_count"      GET  "notifications/unread_count" '' "notifications" 214 200

# employee_code must be digits only, and the phone must be in LOCAL format --
# create.php validates it against country_code, so "+20..." with country "+20"
# is rejected as not valid for the selected country.
# insertNewEmployee() also writes an employee_shift_assignments row (the
# payload supplies a positive shift_id) and creates or updates leave_balance
# with the 21-day default, in the same transaction. A Java regression that
# omitted the opening balance or stored a different effective date would be
# invisible against `employees` alone.
run_case "employees/create"                POST "employees/create" \
  "{\"first_name\":\"Parity\",\"last_name\":\"New\",\"country_code\":\"+20\",\"employee_code\":\"99001\",\"shift_id\":$C214_SHIFT,\"expected_daily_hours\":8,\"phone\":\"01099977701\",\"branch_id\":$C214_BRANCH,\"department_id\":$C214_DEPT,\"job_title_id\":$C214_TITLE,\"role\":\"employee\"}" \
  "employees,employee_shift_assignments,leave_balance" 214 201
run_case "employees/create (no code)"      POST "employees/create" \
  '{"first_name":"Parity","last_name":"New"}' "employees" 214 400
run_case "employees/update"                PUT  "employees/update?id=$C214_EMP" \
  '{"first_name":"Parity Renamed"}' "employees" 214 200
# deactivate() updates the employee AND unconditionally inserts an
# employee_deactivated notification -- a missing one, a wrong recipient, or a
# divergent translated body would be invisible against `employees` alone.
run_case "employees/deactivate"            DELETE "employees/deactivate?id=$C214_EMP" '' "employees,notifications" 214 200
run_case "employees/reactivate"            PUT  "employees/reactivate?id=$C214_EMP" '' "employees" 214 200
# 409: delete.php refuses while payroll/attendance rows still reference the
# employee. employees/delete_preview is the endpoint that reports what blocks it.
run_case "employees/delete (referenced)"   DELETE "employees/delete?id=$C214_EMP" '' "employees" 214 409
run_case "employees/delete (unknown id)"   DELETE "employees/delete?id=99999999" '' "employees" 214 404

run_case "profile/employee"                PUT  "profile/employee" \
  '{"first_name":"Parity Self"}' "employees" 214 200
run_case "profile/change_password"         POST "profile/change_password" \
  '{"old_password":"harness-only-Pass123!","new_password":"harness-only-Pass456!"}' "employees" 214 200
run_case "profile/change_password (wrong)" POST "profile/change_password" \
  '{"old_password":"wrong","new_password":"harness-only-Pass456!"}' "employees" 214 401
# R-013: register_push_token.php INSERTs a company_id column push_tokens does
# not have, so it 500s for every caller and always has. The port reproduces
# the failure rather than repairing the statement (D-058), so 500 on both is
# the correct parity result -- pinned, not worked around.
run_case "profile/register_push_token"     POST "profile/register_push_token" \
  '{"token":"parity-token","platform":"android"}' "push_tokens" 214 500
# logout deletes the caller's push token and, for an employee session,
# deactivates them -- which notifies. Three tables, not one.
run_case "profile/logout"                  POST "profile/logout" '' "employees,notifications,push_tokens" 214 200

run_case "company/update"                  PUT  "company/update" \
  '{"company_name":"Parity Company Renamed"}' "companies" 214 200


# ===========================================================================
# Multipart uploads and spreadsheet analysis.
#
# The spreadsheet fixtures are the application's OWN template output, fetched
# from PHP's template_excel endpoints, not hand-built workbooks -- D-085 makes
# round-tripping the self-generated template the standard these readers are
# held to, and a hand-built file would test the harness's idea of the format
# rather than the format.
# ---------------------------------------------------------------------------
run_multipart_case "company/upload_logo"          "company/upload_logo" \
  logo "$HERE/fixtures/parity.png" "logo.png" "" "companies" 214 200
run_multipart_case "company/upload_logo (pdf)"    "company/upload_logo" \
  logo "$HERE/fixtures/parity.pdf" "doc.pdf" "" "companies" 214 200
run_multipart_case "company/upload_commercial_reg" "company/upload_commercial_reg" \
  file "$HERE/fixtures/parity.pdf" "reg.pdf" "" "companies" 214 200
run_multipart_case "employees/upload_photo"       "employees/upload_photo?id=$C214_EMP" \
  photo "$HERE/fixtures/parity.png" "photo.png" "" "employees" 214 200
run_multipart_case "employee_docs/upload"         "employee_docs/upload" \
  file "$HERE/fixtures/parity.pdf" "contract.pdf" \
  "employee_id=$C214_EMP;doc_type=contract" "employee_docs" 214 201

# Rejections, which are NOT coverage of the success path but are their own
# contract: an unrecognised type must be refused identically.
run_multipart_case "upload_logo (unsupported type)" "company/upload_logo" \
  logo "$HERE/fixtures/employees-template.xlsx" "sheet.xlsx" "" "companies" 214 400

# The spreadsheet readers.
#
# Two employee cases, because the endpoint behaves differently by row count and
# only one of them is coverage. The filled sheet is the app's own template plus
# a data row keyed to the seeded company's shift, branch, department and job
# title -- see make-fixtures.py.
run_multipart_case "employees/analyze_excel (filled)" "employees/analyze_excel" \
  file "$HERE/fixtures/employees-filled.xlsx" "employees.xlsx" "" "employees" 214 200
run_multipart_case "employees/analyze_excel (empty sheet)" "employees/analyze_excel" \
  file "$HERE/fixtures/employees-template.xlsx" "employees.xlsx" "" "employees" 214 200
run_multipart_case "leave_balances/analyze_excel" "leave_balances/analyze_excel" \
  file "$HERE/fixtures/leave_balances-template.xlsx" "leave.xlsx" "" "leave_balance" 214 200
# The punch log, not the employees template: import_excel refuses the latter
# with "Cannot detect employee id", which is a rejection and not coverage. The
# column names come from the frozen PHP's own alias lists.
run_multipart_case "attendance/analyze_excel"     "attendance/analyze_excel" \
  file "$HERE/fixtures/attendance-punches.xlsx" "punches.xlsx" "" "attendance" 214 200
run_multipart_case "attendance/import_excel"      "attendance/import_excel" \
  file "$HERE/fixtures/attendance-punches.xlsx" "punches.xlsx" "" "attendance" 214 200
run_multipart_case "attendance/import (wrong sheet)" "attendance/import_excel" \
  file "$HERE/fixtures/employees-template.xlsx" "wrong.xlsx" "" "attendance" 214 400

# Registration step 2: public, multipart, and the only case that sends two file
# parts. It writes both URL columns on the companies row, so the runner's
# before/after URL assertion covers the halves the row snapshot normalises.
# The target is the half-registered company seed-two.sh provides.
#
# Three tables, not one: completing the profile also creates the company's MAIN
# BRANCH and two onboarding notifications. Measured on the seeded fixture --
# branches 376 -> 377 and notifications 3590 -> 3592 on both stacks -- so a case
# comparing only `companies` would let either side omit or duplicate those
# writes while still reporting the endpoint covered.
run_multipart_case "auth/complete_company_registration" "auth/complete_company_registration" \
  "logo;commercial_reg" "$HERE/fixtures/parity.png;$HERE/fixtures/parity.pdf" "logo.png;reg.pdf" \
  "company_id=999030;company_name=Parity Complete;main_branch_address=Cairo;company_title_id=1;company_activity_id=1;company_size_id=1;first_name=Parity;last_name=HalfRegistered" \
  "companies,branches,notifications" 214 201

# The bulk importers. These take `rows` in a JSON body -- the analyzer's output,
# posted back by the client -- so they are run_case, not run_multipart_case, and
# the bodies are the captured analyses. Cases cannot chain (every case reseeds),
# which is why the capture happens in make-fixtures.sh instead.
#
# employees writes four tables from one row: the employee, the contract, the
# opening leave balance and the shift assignment. Comparing only `employees`
# would miss three quarters of what the endpoint does.
run_case "employees/import_bulk"           POST "employees/import_bulk" \
  "$(cat "$HERE/fixtures/employees-import-rows.json")" \
  "employees,salary_contracts,leave_balance,employee_shift_assignments" 214 200
# The leave-balance sheet the template ships is pre-filled with the company's
# own employees, so this body carries both halves of the importer: rows that
# UPDATE an existing balance and rows that fail because the sheet left the
# days blank. `inserted`, `updated` and `failed` are all exercised.
run_case "leave_balances/import_bulk"      POST "leave_balances/import_bulk" \
  "$(cat "$HERE/fixtures/leave_balances-import-rows.json")" \
  "leave_balance" 214 200
# An empty row object: PHP echoes it back in `failed[].data` as `[]` because
# json_decode gives an empty array, and a Java Map would render `{}`. The
# divergence is closed by LegacyPhpEmptyArrayJsonConfig, and this case is what
# keeps it closed.
# The same four tables the success case writes, not just `employees`: every row
# fails here, so the case is also the assertion that a failed import leaves
# nothing behind in the contract, leave-balance or shift-assignment tables.
run_case "employees/import_bulk (empty row)" POST "employees/import_bulk" \
  '{"rows":[{}]}' \
  "employees,salary_contracts,leave_balance,employee_shift_assignments" 214 200


# ===========================================================================
# Success paths that previously had only a refusal, plus the update/delete
# halves that needed a seeded row.
#
# Each refusal case is KEPT alongside its success case rather than replaced:
# "409 while employees remain" and "200 when none do" are both contract, and
# only the second is coverage.
# ---------------------------------------------------------------------------
run_case "branches/delete (empty branch)"  DELETE "branches/delete?id=$C214_BRANCH_EMPTY" \
  '' "branches,department_branches" 214 200
run_case "employees/delete (unreferenced)" DELETE "employees/delete?id=$C214_EMP_FREE" \
  '' "employees" 214 200
run_case "request_types/delete (unused)"   DELETE "request_types/delete?id=$C214_RTYPE_UNUSED" \
  '' "request_types,requests" 214 200
# Names the employee whose record is open, rather than defaulting to the
# caller -- the fixture belongs to a different employee on purpose.
run_case "attendance/check_out"            POST "attendance/check_out" \
  "{\"employee_id\":$C214_ATT_OPEN_EMP,\"latitude\":30.0211667,\"longitude\":31.4545278}" "attendance" 214 200
# requests/create is requireAuth([EMPLOYEE]); the company-admin actor is refused
# 403 before any logic. The seeded employee actor can reach it.
run_case "requests/create (as employee)"   POST "requests/create" \
  "{\"request_type_id\":$C214_RTYPE_UNUSED,\"from_date\":\"2026-09-20\",\"to_date\":\"2026-09-21\"}" \
  "requests,notifications" emp 201

run_case "exception_types/update"          PUT  "attendance_exception_types/update?id=$C214_EXCTYPE" \
  '{"name":"Parity Exception Renamed"}' "exception_types" 214 200
run_case "exception_types/delete"          DELETE "attendance_exception_types/delete?id=$C214_EXCTYPE" \
  '' "exception_types,request_types,attendance" 214 200
run_case "workforce_planning/update"       PUT  "workforce_planning/update?id=$C214_WFP" \
  '{"planned_count":9}' "workforce_planning" 214 200
run_case "workforce_planning/delete"       DELETE "workforce_planning/delete?id=$C214_WFP" \
  '' "workforce_planning" 214 200
# $_POST, not a JSON body: required($_POST, [ID, DOC_TYPE]).
run_form_case "employee_docs/update"       POST "employee_docs/update" \
  "id=$C214_DOC&doc_type=id_card" "employee_docs" 214 200
run_case "employee_docs/delete"            DELETE "employee_docs/delete?id=$C214_DOC" \
  '' "employee_docs" 214 200


# ===========================================================================
# Auth and OTP.
#
# The OTP is a per-stack secret: each stack generates its own and stores it in
# its own database, so run_otp_case reads each code from the database that
# stack wrote it to. Sending one stack's code to the other would fail for a
# reason unrelated to parity. This is the same shape as the tokens every other
# case uses -- mint_token already asks each stack for its own.
#
# The codes are stored in PLAINTEXT in otp_codes.code, so no change to frozen
# PHP is needed to read them. whatsapp-stub.py stands in for the send, without
# which both stacks answer 503 and the code is never written -- two matching
# failures, which is not coverage.
# ---------------------------------------------------------------------------
run_case "auth/forgot_password"            POST "auth/forgot_password" \
  '{"phone":"+201999000002","type":"employee"}' "otp_codes" - 200
run_case "auth/forgot_password (unknown)"  POST "auth/forgot_password" \
  '{"phone":"+209999999999","type":"employee"}' "otp_codes" - 404
run_case "auth/resend_otp"                 POST "auth/resend_otp" \
  '{"phone":"+201999000002"}' "otp_codes" - 200
run_case "auth/lookup_company"             POST "auth/lookup_company" \
  '{"company_code":"EGHECH"}' "companies" - 200
run_case "auth/login_company (bad creds)"  POST "auth/login_company" \
  '{"phone":"+201999000002","password":"wrong"}' "companies" - 401
run_case "auth/login_desktop (bad creds)"  POST "auth/login_desktop" \
  '{"phone":"+201999000002","password":"wrong","login_as":"company"}' "employees,companies" - 401
# The success paths. Both authenticate against companies.password_hash, which
# seed-two.sh stamps on company 214 -- the snapshot carries a hash whose
# plaintext nobody knows, so without the fixture these two could only ever be
# exercised through the 401s above, which is a refusal and not coverage.
# The phone is the row's OWN, unchanged, and comes from the same constant
# mint_company_token uses -- a literal repeated here would go stale silently.
run_case "auth/login_company"              POST "auth/login_company" \
  "{\"phone\":\"$COMPANY_214_PHONE\",\"password\":\"harness-only-Pass123!\"}" \
  "companies,notifications" - 200
# login_desktop is the one that onboards. It calls ensureCompanyOnboarding(),
# which inserts a welcome and a pending-review notification when the company has
# none; the snapshot's 214 had both, so the call was a no-op and the case
# asserted nothing about it. seed-two.sh now removes those two rows, and the
# request inserts them: measured 3588 -> 3590 on BOTH stacks.
#
# login_company above does NOT onboard -- measured, 3588 -> 3588 -- which is why
# its `notifications` entry asserts the opposite: that this endpoint writes none.
# Two adjacent company logins with opposite notification behaviour, and the
# table list is what pins each of them.
run_case "auth/login_desktop (company)"    POST "auth/login_desktop" \
  "{\"phone\":\"$COMPANY_214_PHONE\",\"password\":\"harness-only-Pass123!\",\"login_as\":\"company\"}" \
  "employees,companies,notifications" - 200
# login bumps token_version AND deletes the employee's push tokens; a seeded
# token makes that second half observable.
run_case "auth/login_employee"             POST "auth/login_employee" \
  '{"phone":"+201999000002","password":"harness-only-Pass123!"}' "employees,push_tokens" - 200

# Registration and joining. Both mint an identity, and both use a FIXED one:
# every case reseeds from the snapshot first, so the previous run's row is gone
# before this one starts. A per-run random phone would only make the case
# nondeterministic -- and would hide a Java that stopped enforcing uniqueness.
# Neither number exists in the snapshot; make-fixtures.sh asserts the same for
# the import fixture's phone.
run_case "auth/register_company"           POST "auth/register_company" \
  "{\"first_name\":\"Parity\",\"last_name\":\"Register\",\"phone\":\"$REGISTER_COMPANY_PHONE\",\"password\":\"harness-only-Pass123!\",\"country_code\":\"+20\"}" \
  "companies,otp_codes" - 201
run_case "auth/join_company"               POST "auth/join_company" \
  "{\"first_name\":\"Parity\",\"last_name\":\"Joiner\",\"phone\":\"$JOIN_COMPANY_PHONE\",\"password\":\"harness-only-Pass123!\",\"country_code\":\"+20\",\"company_code\":\"EGHECH\"}" \
  "employees,notifications" - 201
# Both sides of the join request, against the pending fixture seed-two.sh
# provides. Accept flips the row to accepted+active; reject DELETES it, so the
# employees comparison is what proves each did the right one.
run_case "company_join_requests/accept"    POST "company_join_requests/accept?id=999028" \
  '' "employees,notifications" 214 200
# reject inserts a notification for the pending employee and then DELETES that
# employee; fk_notification_to_employee is ON DELETE CASCADE, so the row is gone
# before any end-state snapshot -- its existence AND its contents.
#
# parity_notification_audit is a harness-only copy written by an AFTER INSERT
# trigger seed-two.sh installs on both databases, so the row survives its own
# deletion and is compared in full: type, title, body, reference_type and
# reference_id included. Counting the inserts would have proved only that one
# happened -- a wrong title or a missing reference would still have passed, and
# `requests/create` (D-155) is the precedent for a notification whose defect was
# invisible everywhere except its own row.
run_case "company_join_requests/reject"    POST "company_join_requests/reject?id=999028" \
  '' "employees,notifications,parity_notification_audit" 214 200

# HR users. create writes the employee AND its seventeen permission flags in one
# transaction, so both tables are compared. update_permissions targets 999029,
# never the sweep's own actor -- rewriting 999002's flags would strip the
# permissions every later case authenticates with.
run_case "hr_employees/create"             POST "hr_employees/create" \
  "{\"first_name\":\"Parity\",\"last_name\":\"NewHr\",\"role\":\"hr\",\"branch_id\":$C214_BRANCH,\"phone\":\"$HR_CREATE_PHONE\",\"country_code\":\"+20\",\"password\":\"harness-only-Pass123!\"}" \
  "employees,hr_permissions" 214 201
run_case "hr_employees/update_permissions" PUT  "hr_employees/update_permissions?id=999029" \
  '{"permissions":{"can_dashboard":1,"can_employees":0,"can_payroll":1}}' \
  "hr_permissions" 214 200

# Company settings. create needs a definition the company has no value for --
# 214 holds all five the snapshot ships, so seed-two.sh adds 999040 with one
# allowed value. delete targets definition 5, which is is_required=0.
run_case "company_settings/create"         POST "company_settings/create" \
  '{"setting_definition_id":999040,"values":["parity-value"]}' \
  "company_settings,company_setting_values" 214 201
run_case "company_settings/delete"         DELETE "company_settings/delete?setting_definition_id=5" \
  '' "company_settings,company_setting_values" 214 200

# The company profile endpoints. Both refuse a non-company token with 403
# before any logic, so they run in a company session -- see mint_company_token.
run_case "profile/request_phone_change"    POST "profile/request_phone_change" \
  "{\"phone\":\"$PHONE_CHANGE_PHONE\",\"country_code\":\"+20\"}" "otp_codes" company 200
# The employee half of delete_account: it deactivates the caller and drops their
# push tokens. The COMPANY half is deliberately not exercised -- it hard-deletes
# the company and cascades, which would destroy the fixture set every other case
# depends on. 999003 is safe to deactivate because the next case reseeds.
#
# The token this asserts is written by post_login_fixture(), not by seed-two.sh:
# mint_token() logs 999003 in first, and that login deletes the very rows this
# case is meant to observe being deleted.
run_case "profile/delete_account"          DELETE "profile/delete_account" \
  '{"password":"harness-only-Pass123!"}' "employees,push_tokens" emp 200

run_otp_case "auth/verify_otp" \
  "auth/forgot_password" '{"phone":"+201999000002","type":"employee"}' \
  "auth/verify_otp" '{"phone":"+201999000002","otp":"{OTP}","type":"employee"}' \
  "otp_codes,employees" 200
run_otp_case "auth/reset_password" \
  "auth/forgot_password" '{"phone":"+201999000002","type":"employee"}' \
  "auth/reset_password" '{"phone":"+201999000002","password":"harness-only-Pass789!","otp":"{OTP}","type":"employee"}' \
  "otp_codes,employees" 200
# Both halves of the phone change, in one company session: request issues the
# code against the NEW number, confirm consumes it and moves companies.phone.
run_otp_case "profile/confirm_phone_change" \
  "profile/request_phone_change" "{\"phone\":\"$PHONE_CHANGE_PHONE\",\"country_code\":\"+20\"}" \
  "profile/confirm_phone_change" "{\"phone\":\"$PHONE_CHANGE_PHONE\",\"country_code\":\"+20\",\"otp\":\"{OTP}\"}" \
  "otp_codes,companies" 200 company

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

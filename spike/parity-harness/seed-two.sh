#!/usr/bin/env bash
# Seed TWO identical databases -- one per stack -- so mutating endpoints can be
# compared.
#
# With a single shared database a POST is untestable: the first stack's write
# changes what the second one sees, and every later comparison is measuring the
# harness rather than the code. Two identical copies let the same request run
# against the same starting state twice, so both the response AND the resulting
# rows can be diffed.
set -euo pipefail
# Repo locations. Both repos are siblings in the workspace; this file now
# lives at hr-platform/spike/parity-harness, so the default walks up three.
WORKSPACE=${WORKSPACE:-"$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")/../../.." && pwd)"}
LEGACY=${LEGACY:-"$WORKSPACE/hr-legacy"}
PLATFORM=${PLATFORM:-"$WORKSPACE/hr-platform"}
DB=parity-harness-db-1

# The legacy checkout IS the oracle here too. seed.sh verified it and this did
# not, so the mutation walkthrough -- which calls seed-two.sh directly -- could
# publish a 17/17 against an unknown tree. Same check, same failure mode.
LEGACY_PIN=${LEGACY_PIN:-d113204}
rev=$(git -C "$LEGACY" rev-parse --short HEAD 2>/dev/null || echo "")
dirty=$(git -C "$LEGACY" status --porcelain 2>/dev/null | head -1)
if [ -z "$rev" ]; then
  echo "FATAL: $LEGACY is not a git checkout -- the oracle revision cannot be verified" >&2
  exit 5
fi
case "$rev" in
  "$LEGACY_PIN"*) : ;;
  *) echo "FATAL: hr-legacy is at $rev, expected $LEGACY_PIN." >&2
     echo "  Parity results are only comparable against the pinned oracle." >&2
     exit 5 ;;
esac
[ -z "$dirty" ] || { echo "FATAL: $LEGACY has uncommitted changes -- the oracle must be clean" >&2; exit 5; }
echo "legacy oracle: $rev (clean)"
PHP_DB=${PHP_DB:-workin}
JAVA_DB=${JAVA_DB:-workin_java}
m() { docker exec -i "$DB" mariadb -uroot -pparity "$@"; }

echo "waiting for the database..."
until [ "$(docker inspect -f '{{.State.Health.Status}}' "$DB" 2>/dev/null)" = healthy ]; do sleep 3; done

for d in "$PHP_DB" "$JAVA_DB"; do
  echo "=== $d ==="
  m -e "DROP DATABASE IF EXISTS \`$d\`;
        CREATE DATABASE \`$d\` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
  m "$d" < "$LEGACY"/mysql_workin.schema.sql
  # FK checks off for the load only: the dump is ordered by table name, so
  # `advances` inserts before the `employees` rows it references.
  { echo "SET FOREIGN_KEY_CHECKS=0; SET UNIQUE_CHECKS=0; SET SESSION sql_mode='';"
    cat "$LEGACY"/mysql_workin.data.sql
    echo "SET FOREIGN_KEY_CHECKS=1; SET UNIQUE_CHECKS=1;"
  } | m "$d"
  m "$d" < "$PLATFORM"/backend/src/test/resources/legacy/phase1_extensions.schema.sql
done

echo "=== parity test employee (same id and hash in both) ==="
HASH=$(docker exec parity-harness-php-1 php -r 'echo password_hash("harness-only-Pass123!", PASSWORD_BCRYPT);')
for d in "$PHP_DB" "$JAVA_DB"; do
  m "$d" -e "
  SET FOREIGN_KEY_CHECKS=0;
  INSERT INTO employees
   (id, company_id, branch_id, first_name, last_name, phone, role, password_hash,
    is_active, is_mobile_attendance_enabled, can_check_in_any_branch, join_request_status, token_version, created_at)
  SELECT 999001, 244, (SELECT id FROM branches WHERE company_id=244 LIMIT 1),
   'Parity','Harness','+201999000001','company_admin','$HASH',1,1,0,'accepted',1,NOW()
  ON DUPLICATE KEY UPDATE password_hash=VALUES(password_hash), token_version=1;
  SET FOREIGN_KEY_CHECKS=1;"

  # Full HR permissions for the test employee.
  #
  # An EMPLOYEE token is gated by hr_permissions (hr_session_has_permission);
  # a COMPANY token bypasses that check entirely. Without a row here, most
  # admin endpoints answer 403 before touching any business logic, and the
  # sweep then compares two refusals and reports parity it never tested.
  m "$d" -e "
  INSERT INTO hr_permissions
   (employee_id, can_dashboard, can_recent_activities, can_branches, can_departments,
    can_job_titles, can_shifts, can_leave_balances, can_assets, can_advances,
    can_workforce_planning, can_salary_calculator, can_company_settings, can_employees,
    can_attendance, can_requests, can_payroll, can_penalties)
  VALUES (999001, 1,1,1,1, 1,1,1,1,1, 1,1,1,1, 1,1,1,1)
  ON DUPLICATE KEY UPDATE can_employees=1, can_payroll=1, can_attendance=1,
                          can_requests=1, can_penalties=1, can_leave_balances=1;"

  # A SECOND employee, in company 214, for the same reason seed.sh has one:
  # the update and delete cases need a row of each resource type to ACT ON, and
  # 244 has none for requests, penalties, assets, holidays, workforce_planning
  # or administrative_decisions. Without this, those cases send ?id= of a row
  # that does not exist, both stacks answer 404, and the case counts as
  # identical while never having exercised an update or a delete at all --
  # the same "matching errors read as parity" trap the GET sweep had.
  #
  # 214 carries 16 of the 18 resource types, more than any other company in
  # the snapshot. Cases name which employee they run as.
  m "$d" -e "
  SET FOREIGN_KEY_CHECKS=0;
  INSERT INTO employees
   (id, company_id, branch_id, first_name, last_name, phone, role, password_hash,
    is_active, is_mobile_attendance_enabled, can_check_in_any_branch, join_request_status, token_version, created_at)
  SELECT 999002, 214, (SELECT id FROM branches WHERE company_id=214 ORDER BY id LIMIT 1),
   'Parity','Breadth','+201999000002','company_admin','$HASH',1,1,0,'accepted',1,NOW()
  ON DUPLICATE KEY UPDATE password_hash=VALUES(password_hash), token_version=1, company_id=VALUES(company_id);
  SET FOREIGN_KEY_CHECKS=1;"

  m "$d" -e "
  INSERT INTO hr_permissions
   (employee_id, can_dashboard, can_recent_activities, can_branches, can_departments,
    can_job_titles, can_shifts, can_leave_balances, can_assets, can_advances,
    can_workforce_planning, can_salary_calculator, can_company_settings, can_employees,
    can_attendance, can_requests, can_payroll, can_penalties)
  VALUES (999002, 1,1,1,1, 1,1,1,1,1, 1,1,1,1, 1,1,1,1)
  ON DUPLICATE KEY UPDATE can_employees=1, can_payroll=1, can_attendance=1,
                          can_requests=1, can_penalties=1, can_leave_balances=1;"
done

# Fixtures the MUTABLE cases need, which the snapshot does not provide.
#
# Company 214 has a penalty and a payroll batch, but the penalty is already
# applied_to_payroll=1 and the batch is finalized -- so update/delete/calculate
# against them only ever exercise the REFUSAL path. Both stacks refuse
# identically, the case passes, and nothing was actually mutated. That is the
# "matching errors read as parity" trap in its most expensive form, because
# these are the payroll paths.
#
# Fixed ids in the 9990xx range so they cannot collide with snapshot rows, and
# written to BOTH databases so the two stacks start from the same state.
for d in "$PHP_DB" "$JAVA_DB"; do
  m "$d" -e "
  SET FOREIGN_KEY_CHECKS=0;
  INSERT INTO penalties (id, employee_id, penalty_type, penalty_days, reason, penalty_date, applied_to_payroll, created_at)
  SELECT 999010, (SELECT id FROM employees WHERE company_id=214 AND id<>999002 ORDER BY id LIMIT 1),
         'absence', 1, 'parity fixture', '2026-09-01', 0, NOW()
  ON DUPLICATE KEY UPDATE applied_to_payroll=0, penalty_days=1;

  INSERT INTO payroll_batches (id, company_id, month, year, status, period_from, period_to, created_at)
  SELECT 999011, 214, 9, 2026, 'draft', '2026-09-01', '2026-09-30', NOW()
  ON DUPLICATE KEY UPDATE status='draft', month=9, year=2026;

  -- A payslip INSIDE the draft batch. The snapshot's payslips all belong to
  -- the finalized batch, so payslips/update and /delete against them only ever
  -- return 'Batch already finalized' -- identical on both stacks, and no
  -- update or delete ever compared.
  INSERT INTO payslips (id, batch_id, employee_id)
  SELECT 999012, 999011, (SELECT id FROM employees WHERE company_id=214 AND id<>999002 ORDER BY id LIMIT 1)
  ON DUPLICATE KEY UPDATE batch_id=999011;
  SET FOREIGN_KEY_CHECKS=1;"
done

echo "=== verifying the two copies start identical ==="
for t in companies employees attendance payslips advances requests; do
  a=$(m -N -B "$PHP_DB"  -e "SELECT COUNT(*) FROM \`$t\`" 2>/dev/null || echo NA)
  b=$(m -N -B "$JAVA_DB" -e "SELECT COUNT(*) FROM \`$t\`" 2>/dev/null || echo NA)
  [ "$a" = "$b" ] && s=ok || s=MISMATCH
  printf '  %-12s php=%-8s java=%-8s %s\n' "$t" "$a" "$b" "$s"
done

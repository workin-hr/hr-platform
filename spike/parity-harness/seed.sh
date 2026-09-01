#!/usr/bin/env bash
# Seed the harness database from the legacy snapshot. Idempotent: drops and
# recreates, so a broken run is recovered by re-running rather than debugging.
set -euo pipefail
# Repo locations. Both repos are siblings in the workspace; this file now
# lives at hr-platform/spike/parity-harness, so the default walks up three.
WORKSPACE=${WORKSPACE:-"$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")/../../.." && pwd)"}
LEGACY=${LEGACY:-"$WORKSPACE/hr-legacy"}
PLATFORM=${PLATFORM:-"$WORKSPACE/hr-platform"}
DB=parity-harness-db-1
m() { docker exec -i "$DB" mariadb -uroot -pparity "$@"; }

# The legacy checkout IS the oracle. Every parity number is only meaningful
# against a known revision of it, and nothing here verified one -- a reviewer on
# a newer, older or locally modified tree would follow this procedure verbatim
# and get a different answer with equal confidence.
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
     echo "  Check it out, or set LEGACY_PIN=$rev deliberately." >&2
     exit 5 ;;
esac
[ -z "$dirty" ] || { echo "FATAL: $LEGACY has uncommitted changes -- the oracle must be clean" >&2; exit 5; }
echo "legacy oracle: $rev (clean)"

echo "waiting for the database..."
until [ "$(docker inspect -f '{{.State.Health.Status}}' "$DB" 2>/dev/null)" = healthy ]; do sleep 3; done

m -e "DROP DATABASE IF EXISTS workin;
      CREATE DATABASE workin CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"

echo "schema..."
m workin < "$LEGACY"/mysql_workin.schema.sql

# FK checks off for the load: the dump is ordered by table name, so `advances`
# inserts before the `employees` rows it references. This is a property of the
# dump, not of the schema -- constraints are back on immediately after.
echo "data (foreign keys deferred)..."
{ echo "SET FOREIGN_KEY_CHECKS=0; SET UNIQUE_CHECKS=0; SET SESSION sql_mode='';"
  cat "$LEGACY"/mysql_workin.data.sql
  echo "SET FOREIGN_KEY_CHECKS=1; SET UNIQUE_CHECKS=1;"
} | m workin

echo "phase 1 extension table..."
m workin < "$PLATFORM"/backend/src/test/resources/legacy/phase1_extensions.schema.sql

echo "parity test employee..."
HASH=$(docker exec "$DB" true && docker exec parity-harness-php-1 \
  php -r 'echo password_hash("harness-only-Pass123!", PASSWORD_BCRYPT);')
m workin -e "
SET FOREIGN_KEY_CHECKS=0;
INSERT INTO employees
 (id, company_id, branch_id, first_name, last_name, phone, role, password_hash,
  is_active, is_mobile_attendance_enabled, can_check_in_any_branch, join_request_status, token_version, created_at)
SELECT 999001, 244, (SELECT id FROM branches WHERE company_id=244 LIMIT 1),
 'Parity','Harness','+201999000001','company_admin','$HASH',1,1,0,'accepted',1,NOW()
ON DUPLICATE KEY UPDATE password_hash=VALUES(password_hash);
SET FOREIGN_KEY_CHECKS=1;"

# A SECOND employee, in a different company, purely for endpoint breadth.
#
# 244 has the employee volume the list endpoints want but is missing six of the
# resource types the /one endpoints need -- requests, penalties, assets,
# holidays, workforce_planning, administrative_decisions -- so those endpoints
# answer 400 "id required" on both stacks and land in the not-200-on-both
# bucket, where they read as covered while nothing has ever been compared.
#
# 214 carries 16 of the 18 resource types, more than any other company in the
# snapshot. Seeding a second employee there rather than moving the first keeps
# the volume comparison and the breadth comparison both available; neither
# sweep disturbs the other. resolve-params.sh resolves ids against this
# company; sweep-auth.sh takes the token via TOKEN_FILE.
m workin -e "
SET FOREIGN_KEY_CHECKS=0;
INSERT INTO employees
 (id, company_id, branch_id, first_name, last_name, phone, role, password_hash,
  is_active, is_mobile_attendance_enabled, can_check_in_any_branch, join_request_status, token_version, created_at)
SELECT 999002, 214, (SELECT id FROM branches WHERE company_id=214 ORDER BY id LIMIT 1),
 'Parity','Breadth','+201999000002','company_admin','$HASH',1,1,0,'accepted',1,NOW()
ON DUPLICATE KEY UPDATE password_hash=VALUES(password_hash), company_id=VALUES(company_id);
SET FOREIGN_KEY_CHECKS=1;"

m workin -e "SELECT (SELECT COUNT(*) FROM companies) companies,
                    (SELECT COUNT(*) FROM employees) employees,
                    (SELECT COUNT(*) FROM attendance) attendance;"
echo "seeded."

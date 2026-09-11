#!/usr/bin/env bash
# Generate deploy/seed/dev-seed.sql from a production dump.
#
#   scripts/build_dev_seed.sh path/to/production-dump.sql
#
# Stands up a throwaway MariaDB, restores the dump into it, applies
# deploy/seed/sanitise.sql, dumps the result, destroys the container, and then
# runs the gate over the output. The gate is the thing that decides whether the
# result may be committed -- this script's own success proves nothing.
#
# It works on a container it creates and destroys, and on no other database.
# There is no host or credential parameter, deliberately: the way this script
# damages something is by being pointed at it, so it cannot be pointed.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SANITISER="$REPO_ROOT/deploy/seed/sanitise.sql"
OUTPUT="$REPO_ROOT/deploy/seed/dev-seed.sql"
GATE="$REPO_ROOT/scripts/check_dev_seed_sanitised.py"

CONTAINER="workin-seed-build-$$"
DB_NAME="workin"
ROOT_PASSWORD="seed-build-throwaway-$$"

usage() {
  echo "usage: $0 <production-dump.sql>" >&2
  echo >&2
  echo "The dump is read only. Nothing is written to it, and no database" >&2
  echo "other than this script's own throwaway container is touched." >&2
  exit 2
}

[ $# -eq 1 ] || usage
DUMP="$1"
[ -f "$DUMP" ] || { echo "no such dump: $DUMP" >&2; exit 2; }
[ -f "$SANITISER" ] || { echo "missing $SANITISER" >&2; exit 2; }

cleanup() {
  docker rm -f "$CONTAINER" >/dev/null 2>&1 || true
}
trap cleanup EXIT

echo "==> throwaway MariaDB ($CONTAINER)"
docker run -d --name "$CONTAINER" \
  -e MARIADB_ROOT_PASSWORD="$ROOT_PASSWORD" \
  -e MARIADB_DATABASE="$DB_NAME" \
  mariadb:11.8 \
  --character-set-server=utf8mb4 \
  --collation-server=utf8mb4_unicode_ci \
  --sql-mode= >/dev/null

echo "==> waiting for it"
for _ in $(seq 1 90); do
  if docker exec "$CONTAINER" healthcheck.sh --connect --innodb_initialized >/dev/null 2>&1; then
    break
  fi
  sleep 2
done
docker exec "$CONTAINER" healthcheck.sh --connect --innodb_initialized >/dev/null 2>&1 \
  || { echo "MariaDB did not become ready" >&2; exit 1; }

run_sql() {
  docker exec -i "$CONTAINER" mariadb -uroot -p"$ROOT_PASSWORD" "$DB_NAME" 2>&1 \
    | grep -v "Using a password on the command line" || true
}

echo "==> restoring the dump (read-only on the source file)"
run_sql < "$DUMP"

# A production dump predates any Phase-1 table, and the sanitiser deletes rows
# from platform_admins, which only exists once the extensions have run.
#
# All three DDL files, in the runbook's order (provisioning-phase1-tables.md
# step 3) -- not just the tables. Phase1SchemaCheck compares table NAMES only, so
# a seed carrying the fourteen tables without the triggers and without the
# widened enum logs "all 14 owned tables are present" and then
# PunchPairingService refuses every pass: punches pile up in RECEIVED and nothing
# says why. Tables-alone is the one combination that fails silently.
PHASE1_DIR="$REPO_ROOT/backend/src/main/resources/db/phase1-mysql"
for ddl in phase1_extensions.sql slice_b_attendance_method.sql legacy_runtime_offset_hooks.sql; do
  if [ ! -f "$PHASE1_DIR/$ddl" ]; then
    echo "FATAL: $PHASE1_DIR/$ddl is missing -- the seed would be silently incomplete" >&2
    exit 1
  fi
  echo "==> applying $ddl"
  run_sql < "$PHASE1_DIR/$ddl"
done

echo "==> sanitising"
run_sql < "$SANITISER"

echo "==> dumping"
mkdir -p "$(dirname "$OUTPUT")"
# --skip-dump-date so a regenerated seed diffs only where the data changed,
# rather than on a timestamp in the footer every single time.
#
# --skip-triggers, then the hooks file appended verbatim below. mariadb-dump
# writes triggers as `/*!50003 CREATE*/ /*!50017 DEFINER=`root`@`localhost`*/
# /*!50003 TRIGGER ...`, and that DEFINER is not decoration: deploy/e2e/run.sh
# restores as the unprivileged `workin` user under E2E_SEED_PROD=1, where
# setting one needs SET USER and the restore dies mid-file with ERROR 1227 --
# a half-applied database, which is the state that script exists to refuse.
# Appending the DDL file instead keeps one shape for the triggers: the same
# text whether the seed was regenerated or hand-extended, carrying no DEFINER
# and already idempotent.
# --skip-triggers discards whatever triggers the INPUT dump carried, not just the
# three installed above. Production has none today (docs/migration/trigger-inventory.md),
# but that is an assumption about someone else's database, and a DBA adding one
# would see it vanish from every dev, integration and E2E stack with nothing
# saying so. Turn the assumption into a check.
EXPECTED_TRIGGERS="$(grep -cE '^[[:space:]]*CREATE TRIGGER' "$PHASE1_DIR/legacy_runtime_offset_hooks.sql")"
ACTUAL_TRIGGERS="$(docker exec "$CONTAINER" mariadb -uroot -p"$ROOT_PASSWORD" -N -B -e \
  "SELECT COUNT(*) FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA='$DB_NAME';" 2>/dev/null \
  | tr -d '[:space:]')"
if [ "$ACTUAL_TRIGGERS" != "$EXPECTED_TRIGGERS" ]; then
  echo "FATAL: the database carries $ACTUAL_TRIGGERS trigger(s); this script installed" >&2
  echo "$EXPECTED_TRIGGERS and dumps with --skip-triggers, so the difference would be" >&2
  echo "dropped from the seed silently. Reconcile before regenerating." >&2
  exit 1
fi

docker exec "$CONTAINER" mariadb-dump \
  -uroot -p"$ROOT_PASSWORD" \
  --single-transaction \
  --skip-dump-date \
  --skip-triggers \
  --default-character-set=utf8mb4 \
  "$DB_NAME" 2>/dev/null > "$OUTPUT"

{
  printf '\n--\n-- Phase 1 runtime-offset triggers, appended rather than dumped: see the\n'
  printf -- '-- --skip-triggers note in scripts/build_dev_seed.sh. The one authority for\n'
  printf -- '-- removing them is docs/operations/provisioning-phase1-tables.md#rollback.\n--\n\n'
  cat "$PHASE1_DIR/legacy_runtime_offset_hooks.sql"
} >> "$OUTPUT"

SIZE="$(du -h "$OUTPUT" | cut -f1)"
echo "==> wrote $OUTPUT ($SIZE)"

echo "==> gate"
if python3 "$GATE"; then
  echo
  echo "The seed passed the gate and may be committed."
else
  status=$?
  echo >&2
  echo "The gate REJECTED the generated seed. Do not commit it." >&2
  echo "Read the FAIL lines above: they name the artifact at fault, which is" >&2
  echo "deploy/seed/sanitise.sql for a value-shape or coverage finding, but the" >&2
  echo "phase1-mysql DDL for a missing table, trigger or enum value." >&2
  exit "$status"
fi

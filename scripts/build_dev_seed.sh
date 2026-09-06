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
if [ -f "$REPO_ROOT/backend/src/main/resources/db/phase1-mysql/phase1_extensions.sql" ]; then
  echo "==> applying the Phase 1 extensions"
  run_sql < "$REPO_ROOT/backend/src/main/resources/db/phase1-mysql/phase1_extensions.sql"
fi

echo "==> sanitising"
run_sql < "$SANITISER"

echo "==> dumping"
mkdir -p "$(dirname "$OUTPUT")"
# --skip-dump-date so a regenerated seed diffs only where the data changed,
# rather than on a timestamp in the footer every single time.
docker exec "$CONTAINER" mariadb-dump \
  -uroot -p"$ROOT_PASSWORD" \
  --single-transaction \
  --skip-dump-date \
  --default-character-set=utf8mb4 \
  "$DB_NAME" 2>/dev/null > "$OUTPUT"

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
  echo "Fix deploy/seed/sanitise.sql and run this script again." >&2
  exit "$status"
fi

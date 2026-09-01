#!/usr/bin/env bash
# Java backend against a legacy database (JAVA_DB, default
# workin_java), with the same JWT secret PHP uses so tokens cross freely.
#
# Its own copy, not a shared one: a mutating endpoint changes state, and with
# one database the second stack to run would see the first one's writes -- the
# comparison would then be measuring the harness rather than the code. Two
# identical copies let the same request start from the same state twice.
#
# Export JAVA_DB=workin to point both stacks at one database, which is what the
# read-only sweeps want.
set -euo pipefail
# Repo locations. Both repos are siblings in the workspace; this file now
# lives at hr-platform/spike/parity-harness, so the default walks up three.
WORKSPACE=${WORKSPACE:-"$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")/../../.." && pwd)"}
LEGACY=${LEGACY:-"$WORKSPACE/hr-legacy"}
PLATFORM=${PLATFORM:-"$WORKSPACE/hr-platform"}
HERE="$(cd "$(dirname "$0")" && pwd)"
# Defaults to `workin`, the one database seed.sh creates, so the README's
# read-comparison walkthrough works verbatim.
#
# The MUTATION sweep needs Java on its own copy and cannot reconfigure an
# already-running JVM, so it must be started as:
#
#     JAVA_DB=workin_java ./run-java.sh
#
# sweep-mutations.sh proves this before running any case -- it writes a
# sentinel into JAVA_DB only and checks Java can see it -- and exits 3 if not,
# because otherwise both stacks mutate the same database and the comparison is
# meaningless.
exec java -jar "$PLATFORM/backend/build/libs/backend-0.0.1-SNAPSHOT.jar" \
  --spring.profiles.active=phase1-mysql \
  --server.port=18081 \
  --app.legacy-db.jdbc-url="jdbc:mariadb://127.0.0.1:13306/${JAVA_DB:-workin}" \
  --app.legacy-db.username=root \
  --app.legacy-db.password=parity \
  --app.jwt.secret="$(cat "$HERE/.jwt-secret")"

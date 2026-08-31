#!/usr/bin/env bash
# Mint a token from whichever stack is named, and store it.
#
# Order matters: every login bumps employees.token_version, which invalidates
# the previously issued token. Log into the stack you intend to test LAST, or
# single-active-session enforcement reads as a compatibility failure.
set -euo pipefail
case "${1:-php}" in
  php)  PORT=18080 ;;
  java) PORT=18081 ;;
  *) echo "usage: $0 [php|java]" >&2; exit 2 ;;
esac
curl -s -X POST "http://localhost:$PORT/apis/api/auth/login_employee" \
  -H 'Content-Type: application/json' \
  -d '{"phone":"+201999000001","password":"harness-only-Pass123!"}' \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['token'])" > .php-token
echo "token minted by $1 -> .php-token"

#!/usr/bin/env bash
#
# Live verification of the platform-admin flow (ADR-0015) against a *running*
# application: login -> MFA -> session -> step-up -> admin action -> logout.
#
# The integration tests cover the same ground and are the regression gate; this
# script exists because "it works in a test" and "it works in the application"
# are different claims, and the second one is worth being able to re-check by
# hand before a cutover.
#
# Prerequisites, none of which it sets up for you:
#
#   1. A Postgres the application can reach, migrated by starting the app once.
#   2. The application running with administrative actions ENABLED
#      (APP_PLATFORM_ADMIN_ACTIONS_ENABLED=true) -- they ship disabled, see
#      ADR-0015 prerequisite 7 -- and a bootstrap administrator provisioned via
#      APP_PLATFORM_ADMIN_BOOTSTRAP_PHONE / _PASSWORD.
#   3. Those same values below, and a psql reachable in the container named by
#      DB_CONTAINER.
#
# It writes fixtures directly to the database (a company to act on, and a
# bootstrap token) because there is no API for either -- both are deliberately
# operator-provisioned.
#
# Not a substitute for the test suite: it asserts nothing, it prints what
# happened next to what was expected, and a human reads it.
set -uo pipefail
BASE=${BASE:-http://127.0.0.1:18090}
DB_CONTAINER=${DB_CONTAINER:-admin-verify-db}
J=$(mktemp)
: > "$J"
# head -1: psql prints the command tag (INSERT 0 1) after a RETURNING value.
DB() { docker exec -i "$DB_CONTAINER" psql -q -U verify -d workin_admin -tAc "$1" | head -1; }

say() { printf '\n=== %s ===\n' "$1"; }

# Fixtures, written straight to the database like any bootstrap-provisioned admin.
# The administrator is the one the application's own PlatformAdminBootstrap
# created at startup -- the real provisioning path, with the real encoder,
# rather than a password hash written into the table by hand.
PHONE='+201000000042'
PASSWORD='live-verify-Pass123!'
ADMIN_ID=$(DB "SELECT id FROM platform_admins WHERE phone='$PHONE'")
COMPANY_ID=$(DB "INSERT INTO companies (name, phone, active, status) VALUES ('Live Verify Co', '+2055$(date +%N)', true, 'active') RETURNING id")
echo "admin=$ADMIN_ID company=$COMPANY_ID phone=$PHONE"

csrf() { grep -o 'name="_csrf" value="[^"]*"' /tmp/page.html | head -1 | sed 's/.*value="//;s/"//'; }
get()  { curl -sS -b "$J" -c "$J" -o /tmp/page.html -w '%{http_code}' "$BASE$1"; }
post() { local path=$1; shift; curl -sS -b "$J" -c "$J" -o /tmp/page.html -w '%{http_code}' -X POST "$BASE$path" "$@"; }

say "1. unauthenticated /admin"
CODE=$(get /admin); echo "GET /admin -> $CODE  (expect 302)"

say "2. enrolment needs password AND bootstrap token"
TOKEN=$(python3 - "$ADMIN_ID" <<'PY'
import subprocess, sys, secrets, hashlib
raw = secrets.token_urlsafe(32)
h = hashlib.sha256(raw.encode()).hexdigest()
subprocess.run(["docker","exec","-i",__import__("os").environ.get("DB_CONTAINER","admin-verify-db"),"psql","-U","verify","-d","workin_admin","-c",
  f"INSERT INTO platform_admin_mfa_bootstrap_tokens (platform_admin_id, token_hash, issued_at, expires_at) "
  f"VALUES ({sys.argv[1]}, '{h}', now(), now() + interval '30 minutes')"], check=True, capture_output=True)
print(raw)
PY
)
CODE=$(get /admin/enrol); C=$(csrf)
CODE=$(post /admin/enrol --data-urlencode "phone=$PHONE" --data-urlencode "password=$PASSWORD" \
  --data-urlencode "bootstrapToken=wrong-token" --data-urlencode "_csrf=$C")
echo "enrol with wrong token -> $CODE $(grep -c 'were not accepted' /tmp/page.html || true) rejection(s)"

CODE=$(get /admin/enrol); C=$(csrf)
CODE=$(post /admin/enrol --data-urlencode "phone=$PHONE" --data-urlencode "password=$PASSWORD" \
  --data-urlencode "bootstrapToken=$TOKEN" --data-urlencode "_csrf=$C")
SEED=$(grep -o '<code>[A-Z2-7]*</code>' /tmp/page.html | head -1 | sed 's/<[^>]*>//g')
echo "enrol with real token -> $CODE, seed shown: ${SEED:0:8}..."

totp() { python3 - "$1" "${2:-0}" <<'PY'
import base64, hmac, hashlib, struct, sys, time
seed = base64.b32decode(sys.argv[1] + '=' * (-len(sys.argv[1]) % 8))
step = int(time.time() // 30) + int(sys.argv[2])
h = hmac.new(seed, struct.pack('>Q', step), hashlib.sha1).digest()
o = h[-1] & 0x0F
print('%06d' % ((struct.unpack('>I', h[o:o+4])[0] & 0x7fffffff) % 1000000))
PY
}

C=$(csrf)
CODE=$(post /admin/enrol/confirm --data-urlencode "code=$(totp "$SEED")" --data-urlencode "_csrf=$C")
echo "confirm enrolment -> $CODE  (expect 302)"
echo "factor bound in db: $(DB "SELECT bound_at IS NOT NULL FROM platform_admin_mfa WHERE platform_admin_id=$ADMIN_ID")"

say "3. password alone reaches only the challenge"
: > "$J"
CODE=$(get /admin/login); C=$(csrf)
LOC=$(curl -sS -b "$J" -c "$J" -o /dev/null -w '%{redirect_url}' -X POST "$BASE/admin/login" \
  --data-urlencode "phone=$PHONE" --data-urlencode "password=$PASSWORD" --data-urlencode "_csrf=$C")
echo "POST /admin/login -> $LOC"
CODE=$(get /admin); echo "GET /admin with password-only session -> $CODE  (expect 302)"

say "4. second factor completes the session"
DB "UPDATE platform_admin_mfa SET last_accepted_time_step = NULL WHERE platform_admin_id=$ADMIN_ID" >/dev/null
CODE=$(get /admin/mfa); C=$(csrf)
CODE=$(post /admin/mfa --data-urlencode "code=$(totp "$SEED")" --data-urlencode "_csrf=$C")
echo "POST /admin/mfa -> $CODE  (expect 302)"
CODE=$(get /admin); echo "GET /admin -> $CODE  (expect 200)"

say "5. sessions are listed"
CODE=$(get /admin/sessions); echo "GET /admin/sessions -> $CODE, current session marked: $(grep -c 'this one' /tmp/page.html || true)"

say "6. step-up, then the admin action"
DB "UPDATE platform_admin_mfa SET last_accepted_time_step = NULL WHERE platform_admin_id=$ADMIN_ID" >/dev/null
CODE=$(get /admin/companies); C=$(csrf)
CODE=$(post /admin/companies/confirm --data-urlencode "action=COMPANY_SUSPEND" \
  --data-urlencode "companyId=$COMPANY_ID" --data-urlencode "reason=non-payment" \
  --data-urlencode "code=$(totp "$SEED")" --data-urlencode "_csrf=$C")
APPROVAL=$(grep -o 'name="approvalId" value="[0-9a-f]*"' /tmp/page.html | sed 's/.*value="//;s/"//')
echo "step-up -> $CODE, approval ${APPROVAL:0:12}..."
C=$(csrf)
CODE=$(post /admin/companies/apply --data-urlencode "action=COMPANY_SUSPEND" \
  --data-urlencode "companyId=$COMPANY_ID" --data-urlencode "reason=non-payment" \
  --data-urlencode "approvalId=$APPROVAL" --data-urlencode "_csrf=$C")
echo "apply -> $CODE  (expect 302)"
echo "company status: $(DB "SELECT status FROM companies WHERE id=$COMPANY_ID")"
echo "audit row: $(DB "SELECT event_type||' '||target_type||' '||target_id||' approval='||COALESCE(step_up_approval_id,'-') FROM platform_admin_audit_events WHERE platform_admin_id=$ADMIN_ID AND event_type LIKE 'COMPANY%'")"

say "7. the approval is single use"
CODE=$(get /admin/companies); C=$(csrf)
CODE=$(post /admin/companies/apply --data-urlencode "action=COMPANY_SUSPEND" \
  --data-urlencode "companyId=$COMPANY_ID" --data-urlencode "reason=non-payment" \
  --data-urlencode "approvalId=$APPROVAL" --data-urlencode "_csrf=$C")
echo "replay -> $CODE, refused: $(grep -c 'was not accepted' /tmp/page.html || true)"

say "8. CSRF is enforced"
CODE=$(post /admin/logout); echo "POST /admin/logout without token -> $CODE  (expect 403)"

say "9. logout ends the session everywhere"
CODE=$(get /admin); C=$(csrf)
CODE=$(post /admin/logout --data-urlencode "_csrf=$C"); echo "POST /admin/logout -> $CODE  (expect 302)"
CODE=$(get /admin); echo "GET /admin after logout -> $CODE  (expect 302)"
echo "spring_session rows for this admin: $(DB "SELECT COUNT(*) FROM spring_session WHERE principal_name='$ADMIN_ID'")"

say "10. bearer API requires the second factor"
DB "UPDATE platform_admin_mfa SET last_accepted_time_step = NULL WHERE platform_admin_id=$ADMIN_ID" >/dev/null
echo "password only  -> $(curl -sS -o /dev/null -w '%{http_code}' -X POST "$BASE/api/platform-admin/login" -H 'Content-Type: application/json' -d "{\"phone\":\"$PHONE\",\"password\":\"$PASSWORD\"}")  (expect 401)"
echo "with TOTP code -> $(curl -sS -o /dev/null -w '%{http_code}' -X POST "$BASE/api/platform-admin/login" -H 'Content-Type: application/json' -d "{\"phone\":\"$PHONE\",\"password\":\"$PASSWORD\",\"code\":\"$(totp "$SEED")\"}")  (expect 200)"

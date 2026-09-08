#!/usr/bin/env bash
# Drive the dashboard's login against a RUNNING application, over HTTP.
#
#   deploy/compose.local.yaml up -d --build     # or any deployment
#   ADMIN_PASSWORD=... scripts/verify-admin-login.sh
#
# The integration suite already proves this journey on every build. This proves
# something the suite cannot: that the *packaged* application, wired the way a
# deployment wires it, behaves the same -- ADR-0015's prerequisites are about
# runtime behaviour (a Secure cookie, a rotated session id, a server-side
# session row), and a test that constructs its own context is not evidence
# about a container reading its configuration from the environment.
#
# It reads the database to check what the application wrote, and it writes
# nothing itself. Point it at a deployment you may sign into; it will create a
# session and end it.
set -euo pipefail

BASE="${BASE_URL:-http://127.0.0.1:8080}"
PASSWORD="${ADMIN_PASSWORD:-local-admin-password}"
DB_CONTAINER="${DB_CONTAINER:-workin-local-db-1}"
DB_USER="${DB_USER:-workin}"
DB_PASSWORD="${DB_PASSWORD:-workin-local}"
DB_NAME="${DB_NAME:-workin}"

# A deployment rehearsing behind Caddy's internal CA -- `APP_DOMAIN=localhost`
# -- serves a certificate this machine has no reason to trust. Set
# TLS_INSECURE=1 there, and nowhere else: against a real deployment the
# certificate is exactly what you want verified.
TLS=()
[ -n "${TLS_INSECURE:-}" ] && TLS=(-k)

JAR="$(mktemp -d)/cookies"
step=0
pass() { step=$((step + 1)); printf '  \033[32mok\033[0m  %s\n' "$1"; }
fail() { printf '  \033[31mFAIL\033[0m  %s\n' "$1" >&2; exit 1; }
say()  { printf '\n\033[1m%s\033[0m\n' "$1"; }

sql() {
  docker exec -i "$DB_CONTAINER" mariadb -u"$DB_USER" -p"$DB_PASSWORD" "$DB_NAME" -N -B -e "$1" \
    2>/dev/null | tr -d '\r'
}

# The CSRF token and the session cookie travel together: a token minted for one
# session is refused by another, which is the point of it.
csrf_of() {
  curl -sS "${TLS[@]}" -c "$1" -b "$1" "$BASE$2" | grep -o 'name="_csrf" value="[^"]*"' | head -1 |
    sed 's/.*value="//; s/"$//'
}
status_of() { curl -sS "${TLS[@]}" -o /dev/null -w '%{http_code}' "$@"; }

say "1. the surface is closed to an anonymous caller"
code="$(status_of "$BASE/admin")"
[ "$code" = 302 ] || fail "GET /admin answered $code, expected a redirect to the login page"
location="$(curl -sS "${TLS[@]}" -o /dev/null -D - "$BASE/admin" | grep -i '^location:' | tr -d '\r')"
case "$location" in *"/admin/login"*) pass "GET /admin -> 302 $location";; *) fail "redirected to $location";; esac

say "2. a wrong password is refused, and leaves an audit row"
before="$(sql "SELECT COUNT(*) FROM platform_admin_audit_events WHERE event_type = 'LOGIN_FAILED'")"
token="$(csrf_of "$JAR.miss" /admin/login)"
[ -n "$token" ] || fail "the login page served no CSRF token"
body="$(curl -sS "${TLS[@]}" -c "$JAR.miss" -b "$JAR.miss" -d "_csrf=$token" -d "password=not-the-password" "$BASE/admin/login")"
case "$body" in *login-alert--error*) pass "the page came back with its error banner";; *) fail "no error banner in the response";; esac
[ "$(status_of -b "$JAR.miss" "$BASE/admin")" = 302 ] || fail "a refused login left a usable session"
after="$(sql "SELECT COUNT(*) FROM platform_admin_audit_events WHERE event_type = 'LOGIN_FAILED'")"
[ "$after" -gt "$before" ] || fail "no LOGIN_FAILED row was written ($before -> $after)"
pass "LOGIN_FAILED recorded ($before -> $after)"

say "3. the password opens a session, server-side"
token="$(csrf_of "$JAR" /admin/login)"
anonymous="$(grep -i 'WORKIN_ADMIN_SESSION' "$JAR" | awk '{print $NF}')"
code="$(curl -sS "${TLS[@]}" -o /dev/null -w '%{http_code}' -c "$JAR" -b "$JAR" \
  -d "_csrf=$token" -d "password=$PASSWORD" "$BASE/admin/login")"
# A refused password re-renders the page (200); an accepted one redirects. Worth
# separating, because every assertion below would otherwise fail with a reason
# that has nothing to do with what it tests.
[ "$code" = 302 ] || fail "the login answered $code -- the password in ADMIN_PASSWORD is not this deployment's"
authenticated="$(grep -i 'WORKIN_ADMIN_SESSION' "$JAR" | awk '{print $NF}')"
[ -n "$authenticated" ] || fail "no session cookie was set"
[ "$anonymous" != "$authenticated" ] || fail "the session id did not rotate on login (fixation)"
pass "the session id rotated on login"
[ "$(status_of -b "$JAR" "$BASE/admin")" = 200 ] || fail "the dashboard did not open"
pass "GET /admin -> 200"
id="$(printf '%s' "$authenticated" | base64 -d 2>/dev/null || true)"
[ -n "$id" ] && [ "$(sql "SELECT COUNT(*) FROM SPRING_SESSION WHERE SESSION_ID = '$id'")" = 1 ] \
  || fail "the session is not in the shared store"
pass "the session is a row in SPRING_SESSION, not one worker's heap"
[ "$(sql "SELECT COUNT(*) FROM platform_admin_audit_events WHERE event_type = 'LOGIN'")" -gt 0 ] \
  || fail "no LOGIN audit row"
pass "LOGIN recorded"

say "4. the cookie is what ADR-0015 prerequisite 6 requires"
flags="$(curl -sS "${TLS[@]}" -o /dev/null -D - -c "$JAR.flags" -b "$JAR.flags" "$BASE/admin/login" |
  grep -i 'set-cookie: *WORKIN_ADMIN_SESSION' | tr -d '\r')"
case "$flags" in *HttpOnly*) pass "HttpOnly";; *) fail "the session cookie is not HttpOnly: $flags";; esac
case "$flags" in *SameSite=Lax*) pass "SameSite=Lax";; *) fail "the session cookie is not SameSite=Lax: $flags";; esac
# Secure is set unconditionally, so over plain HTTP the browser would drop it --
# which is why a deployment serves this surface behind TLS and why the E2E suite
# runs over it. Checked here, not assumed.
case "$flags" in *Secure*) pass "Secure";; *) fail "the session cookie is not Secure: $flags";; esac

say "5. a state change without its token is refused"
code="$(status_of -b "$JAR" -X POST -d "action=COMPANY_SUSPEND" -d "companyId=1" "$BASE/admin/companies/action")"
[ "$code" = 403 ] || fail "the company action answered $code without a CSRF token, expected 403"
pass "POST /admin/companies/action without a token -> 403"
code="$(status_of -b "$JAR" -X POST "$BASE/admin/logout")"
[ "$code" = 403 ] || fail "logout answered $code without a CSRF token, expected 403"
pass "POST /admin/logout without a token -> 403"

say "6. logout ends the session where it lives"
token="$(csrf_of "$JAR" /admin/sessions)"
[ -n "$token" ] || fail "the sessions page served no CSRF token"
curl -sS "${TLS[@]}" -o /dev/null -b "$JAR" -c "$JAR" -d "_csrf=$token" "$BASE/admin/logout"
[ "$(sql "SELECT COUNT(*) FROM SPRING_SESSION WHERE SESSION_ID = '$id'")" = 0 ] \
  || fail "the session row survived logout -- the cookie went, the session did not"
pass "the SPRING_SESSION row is gone, not just the cookie"
[ "$(status_of -b "$JAR" "$BASE/admin")" = 302 ] || fail "the ended session still opens the dashboard"
pass "the old cookie no longer opens anything"

say "$step checks passed against $BASE"

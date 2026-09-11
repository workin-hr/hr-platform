#!/usr/bin/env bash
# The FAILURE branches of test-review-gate-permission-live.sh, with `gh` stubbed.
#
# That script asks a live question, so its failure paths cannot be exercised by
# asking the real API nicely. Nothing exercised them, and that is precisely how
# a P0 shipped: `status="$(gh api ... | sed ...)"` under `errexit` + `pipefail`
# killed the script on the first non-2xx, making the retry loop, the 403 branch
# and the upstream-fault branch unreachable for every failure they existed for.
# A 403 exited after one call with no output at all -- worse than no retry.
#
# These cases would have caught that on the commit that introduced it.
set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
LIVE="$HERE/test-review-gate-permission-live.sh"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
fails=0

# `gh` that answers however the case asks, the way the real one does: a non-2xx
# both prints the error body AND exits non-zero.
cat > "$WORK/gh" <<'STUB'
#!/usr/bin/env bash
# Behaves like the real `gh`: a non-2xx prints a body AND exits non-zero.
# Honours both shapes its callers use -- `--include` (the live probe, which
# wants the status line) and `--jq` (the extracted gate helper, which wants the
# field). A stub that ignored --jq handed the helper a headered response, which
# it correctly called unreadable.
who=""
jqexpr=""
next_is_jq=0
for a in "$@"; do
  if [ "$next_is_jq" = 1 ]; then jqexpr="$a"; next_is_jq=0; continue; fi
  case "$a" in
    --jq) next_is_jq=1 ;;
    */collaborators/*/permission) who="${a#*/collaborators/}"; who="${who%/permission}" ;;
  esac
done
emit() {  # $1=status line, $2=body
  if [ -n "$jqexpr" ]; then printf '%s' "$2" | jq -r "$jqexpr"
  else printf '%s\r\n\r\n%s\n' "$1" "$2"; fi
}
case "${GH_MODE:-ok}" in
  ok)
    # Per login, as the real endpoint answers: a public repo reports `read` for
    # any non-collaborator, and 404 for an account that does not exist.
    case "$who" in
      octocat)          emit 'HTTP/2.0 200 OK' '{"permission":"read"}'; exit 0 ;;
      no-such-account*) emit 'HTTP/2.0 404 Not Found' '{"message":"not a user"}'
                        echo "gh: Not Found (HTTP 404)" >&2; exit 1 ;;
      *)                emit 'HTTP/2.0 200 OK' '{"permission":"admin"}'; exit 0 ;;
    esac ;;
  403) emit 'HTTP/2.0 403 Forbidden' '{"message":"Resource not accessible by integration"}'
       echo "gh: Resource not accessible by integration (HTTP 403)" >&2; exit 1 ;;
  500) emit 'HTTP/2.0 500 Internal Server Error' '{"message":"Server Error"}'
       echo "gh: HTTP 500" >&2; exit 1 ;;
  absent) exit 127 ;;
esac
STUB
chmod +x "$WORK/gh"

rc=0
run() {  # $1=GH_MODE -> writes $WORK/out, sets $rc
  rc=0
  GH_MODE="$1" PATH="$WORK:$PATH" RECORDER_LOGIN=someone REPO=owner/repo \
    SKIP_SLEEP=1 timeout 120 "$LIVE" >"$WORK/out" 2>&1 || rc=$?
}

check() {  # $1=ok $2=label
  if [ "$1" = 0 ]; then printf '  ok    %s\n' "$2"
  else printf '  FAIL  %s\n' "$2"; sed 's/^/          /' "$WORK/out"; fails=$((fails + 1)); fi
}

# 1. A 403 is an answer: report it AS a permissions regression, and do not retry
#    (retrying cannot grant a permission).
run 403 || true
if grep -q "PERMISSIONS REGRESSION" "$WORK/out"; then check 0 "403 is reported as a permissions regression"
else check 1 "403 is reported as a permissions regression"; fi
if grep -q "403 for someone" "$WORK/out"; then check 0 "403 names the account and the status"
else check 1 "403 names the account and the status"; fi

# 2. A 5xx is NOT a permissions regression. It must retry and then say so.
run 500 || true
if grep -q "likely an upstream fault" "$WORK/out"; then check 0 "500 is reported as a likely upstream fault"
else check 1 "500 is reported as a likely upstream fault"; fi
if grep -q "PERMISSIONS REGRESSION" "$WORK/out"; then
  check 1 "500 is NOT misreported as a permissions regression"
else check 0 "500 is NOT misreported as a permissions regression"; fi
if grep -q "HTTP status: '500'" "$WORK/out"; then check 0 "500 names the status it saw"
else check 1 "500 names the status it saw"; fi

# 2b. THE BIT THAT DECIDES WHETHER `validate` GOES RED. Every case above is a
#     grep, and a mutant that keeps the diagnostics but calls `check 0` passes
#     all of them while exiting 0 on a 403 -- permission unestablished, gate's
#     authorisation basis unproven, CI green. Assert the status itself.
for mode in 403 500 absent; do
  run "$mode"
  if [ "$rc" -ne 0 ]; then check 0 "mode $mode exits non-zero"
  else check 1 "mode $mode exits non-zero (it exited 0 -- validate would be GREEN)"; fi
done

# 2c. And the positive: a healthy stub must still exit 0, or the step would red
#     the repository on every run.
run ok
if [ "$rc" -eq 0 ]; then check 0 "a healthy lookup exits 0"
else check 1 "a healthy lookup exits 0"; fi

# 3. The script must never die silently. Every failure mode prints a verdict
#    line -- this is the property whose absence was the P0.
for mode in 403 500 absent; do
  run "$mode" || true
  if grep -qE "^  (ok|FAIL) " "$WORK/out"; then check 0 "mode $mode still prints a verdict line"
  else check 1 "mode $mode still prints a verdict line (silent death)"; fi
done

echo
if [ "$fails" -eq 0 ]; then echo "review-gate probe failure paths: all cases pass."
else echo "$fails case(s) failed." >&2; fi
exit "$fails"

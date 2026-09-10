#!/usr/bin/env bash
# What the D-226 zero-findings guard does on the PRODUCTION path.
#
# `check-review-dispositions.sh` has a fixture seam, REVIEW_THREADS_JSON_FILE,
# and every existing case used it. That seam also short-circuited the guard's
# comment queries, so the branch that runs on a real pull request had no
# coverage at all -- and shipped referencing two variables the script never
# assigned. Under `set -u` it aborted at expansion, before `gh` ran, on every
# real invocation: the compensating control D-226 names was inert, and ordinary
# clean pull requests failed with a shell error.
#
# So this test deliberately does NOT set REVIEW_THREADS_JSON_FILE. It stubs `gh`
# and drives the whole script, which is the only way these cases can fail if the
# live path breaks again.
#
# The jq fragments below contain `$a`, a jq variable, not a shell expansion.
# shellcheck disable=SC2016

set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
SCRIPT="$HERE/check-review-dispositions.sh"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

HEAD_FULL=af14c9c8231c653e0eac2efbb7ea1e0bcac80584

# Answers the three calls the script makes: `repo view`, the GraphQL threads
# query, and the REST comments listing. Comments come from a file each case
# writes, so a case describes only what it is about.
cat > "$WORK/gh" <<'STUB'
#!/usr/bin/env bash
if [ "${1:-}" = "repo" ]; then echo "owner/repo"; exit 0; fi
jqexpr=""
next_is_jq=0
is_graphql=0
is_comments=0
for arg in "$@"; do
  if [ "$next_is_jq" = 1 ]; then jqexpr="$arg"; next_is_jq=0; continue; fi
  case "$arg" in
    --jq) next_is_jq=1 ;;
    graphql) is_graphql=1 ;;
    */comments) is_comments=1 ;;
  esac
done
if [ "$is_graphql" = 1 ]; then
  cat "$FIXTURE_DIR/threads.json"; exit 0
fi
if [ "$is_comments" = 1 ]; then
  if [ -n "$jqexpr" ]; then
    # --paginate emits one array per page; the stub serves every page file so a
    # case can prove the caller counts across pages instead of per page.
    for f in "$FIXTURE_DIR"/comments*.json; do jq -r "$jqexpr" < "$f"; done
  else
    cat "$FIXTURE_DIR"/comments.json
  fi
  exit 0
fi
echo "[]"
STUB
chmod +x "$WORK/gh"
PATH="$WORK:$PATH"
export FIXTURE_DIR="$WORK"

# Zero reviewer-authored threads: every case below is about the total==0 guard.
printf '%s' '{"data":{"repository":{"pullRequest":{"reviewThreads":{"pageInfo":{"hasNextPage":false,"endCursor":null},"nodes":[]}}}}}' \
  > "$WORK/threads.json"

comments() { rm -f "$WORK"/comments*.json; printf '%s' "$1" > "$WORK/comments.json"; }

ROUND_BODY="independent-review-round: agent\r\nhead: $HEAD_FULL"

fails=0
expect() {  # $1=label $2=expected exit status
  local got=0
  "$SCRIPT" 194 >"$WORK/out.txt" 2>&1 || got=$?
  if [ "$got" = "$2" ]; then
    printf '  ok    %-56s exit %s\n' "$1" "$got"
  else
    printf '  FAIL  %-56s expected exit %s, got %s\n' "$1" "$2" "$got"
    sed 's/^/          /' "$WORK/out.txt"
    fails=$((fails + 1))
  fi
}

# 1. The ordinary clean pull request: nobody claimed a round, no findings.
#    This is the case that crashed with `REPO: unbound variable`.
comments '[]'
expect "clean PR, no round claimed" 0

# 2. A round claimed by a writer, with no findings and no declaration. The
#    whole point of the guard: silence is not a disposition.
comments '[{"author_association":"OWNER","created_at":"t1","updated_at":"t1","body":"'"$ROUND_BODY"'"}]'
expect "round claimed, nothing declared (must FAIL)" 1

# 3. The same round, declaring zero findings on its own line.
comments '[{"author_association":"OWNER","created_at":"t1","updated_at":"t1","body":"'"$ROUND_BODY"'\r\nfindings: none"}]'
expect "round claimed, zero findings declared" 0

# 4. The guard must not disarm itself. Its own failure text names the
#    declaration; pasting that failure into a separate comment used to satisfy
#    the condition being reported.
comments '[{"author_association":"OWNER","created_at":"t1","updated_at":"t1","body":"'"$ROUND_BODY"'"},
           {"author_association":"OWNER","created_at":"t2","updated_at":"t2","body":"the check says: no round comment declares findings: none"}]'
expect "declaration pasted outside the round comment (must FAIL)" 1

# 5. An outsider cannot claim a round, so cannot arm the guard either. On a
#    public repository this is any GitHub account.
comments '[{"author_association":"NONE","created_at":"t1","updated_at":"t1","body":"'"$ROUND_BODY"'"}]'
expect "round claimed by a non-writer is not a round" 0

# 6. CONTRIBUTOR is earned by one merged commit and implies no write access.
comments '[{"author_association":"CONTRIBUTOR","created_at":"t1","updated_at":"t1","body":"'"$ROUND_BODY"'"}]'
expect "round claimed by a drive-by CONTRIBUTOR is not a round" 0

# 7. An edited round comment is not a round: the body it is counted on is not
#    the body that was posted.
comments '[{"author_association":"OWNER","created_at":"t1","updated_at":"t2","body":"'"$ROUND_BODY"'"}]'
expect "edited round comment is not a round" 0

# 8. A comment QUOTING the marker -- a review of the gate itself -- must not
#    claim a round. Indentation is what a fenced or quoted paste looks like.
comments '[{"author_association":"OWNER","created_at":"t1","updated_at":"t1","body":"the workflow sets:\r\n    independent-review-round: agent\r\nhead: '"$HEAD_FULL"'"}]'
expect "quoted marker does not claim a round" 0

# 9. Counting across pages. `--paginate` emits one array per page, so `| length`
#    yields "1\n0" here -- which `[ ... -gt 0 ]` reports as a syntax error, and
#    an erroring test falls through to the pass path.
rm -f "$WORK"/comments*.json
printf '%s' '[{"author_association":"OWNER","created_at":"t1","updated_at":"t1","body":"'"$ROUND_BODY"'"}]' > "$WORK/comments1.json"
printf '%s' '[]' > "$WORK/comments2.json"
expect "round on page 1 of 2 still counts (must FAIL)" 1

echo
if [ "$fails" -ne 0 ]; then
  echo "$fails case(s) failed."
  exit 1
fi
echo "check-review-dispositions production path: all cases pass."

#!/usr/bin/env bash
# Proves the review gate's authorisation works against the REAL GitHub API,
# under a token restricted the way the gate's own token is (D-228).
#
# The unit suites stub `gh`, so they prove the LOGIC. They cannot prove the one
# thing that decides whether the gate functions at all: that GITHUB_TOKEN, with
# `permissions: contents: read`, may call
# `GET /repos/{owner}/{repo}/collaborators/{login}/permission`. If it may not,
# every round fails closed and the gate is permanently red -- worse than the
# author_association filter it replaced. That question is answerable only at
# runtime, so it is asked here, on every pull request, and the build fails if
# the answer changes.
#
# The function under test is EXTRACTED FROM THE WORKFLOW, so this cannot pass
# against a copy the gate no longer runs.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
WORKFLOW="$ROOT/.github/workflows/independent-review-gate.yml"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

REPO="${REPO:-${GITHUB_REPOSITORY:-workin-hr/hr-platform}}"
export REPO
# A real account that is not a collaborator here. On a PUBLIC repository this
# endpoint answers `read` for any such account, which is exactly the value the
# allowlist must refuse.
OUTSIDER="${OUTSIDER_LOGIN:-octocat}"
ABSENT="no-such-account-$(date +%s)-x9z7"

sed -n '/^ *round_author_is_trusted() {/,/^ *}$/p' "$WORKFLOW" | sed 's/^          //' \
  > "$WORK/fn.sh"
grep -q 'round_author_is_trusted() {' "$WORK/fn.sh" || {
  echo "FATAL: could not extract round_author_is_trusted() from $WORKFLOW." >&2
  exit 2
}
ROUND_AUTHOR_PERMISSIONS="$(
  sed -e 's/^[[:space:]]*#.*$//' "$WORKFLOW" \
    | sed -n "s/.*ROUND_AUTHOR_PERMISSIONS:[[:space:]]*'\([^']*\)'.*/\1/p" | head -n 1
)"
[ -n "$ROUND_AUTHOR_PERMISSIONS" ] || {
  echo "FATAL: could not read ROUND_AUTHOR_PERMISSIONS from $WORKFLOW." >&2
  exit 2
}
export ROUND_AUTHOR_PERMISSIONS
# shellcheck source=/dev/null
. "$WORK/fn.sh"

fails=0
check() {  # $1=ok $2=label
  if [ "$1" = 0 ]; then printf '  ok    %s\n' "$2"
  else printf '  FAIL  %s\n' "$2"; fails=$((fails + 1)); fi
}

echo "Repository: $REPO"
echo "Accepted permissions: $ROUND_AUTHOR_PERMISSIONS"
echo

# 1. THE TOKEN QUESTION. Not "is this account trusted" but "may this token ask".
#    A 403 here is the failure mode that would make the gate permanently red,
#    and it must be loud rather than indistinguishable from "not a writer".
actor="${RECORDER_LOGIN:-${GITHUB_ACTOR:-}}"
if [ -z "$actor" ]; then
  echo "FATAL: no RECORDER_LOGIN/GITHUB_ACTOR to probe with." >&2
  exit 2
fi
status=""
raw=""
for attempt in 1 2 3; do
  status="$(gh api "repos/$REPO/collaborators/$actor/permission" \
      --include 2>"$WORK/err" | sed -n '1s#.*[[:space:]]\([0-9][0-9][0-9]\)[[:space:]].*#\1#p' | head -n 1)"
  raw="$(gh api "repos/$REPO/collaborators/$actor/permission" --jq '.permission' 2>>"$WORK/err")" || raw=""
  case "$raw" in ""|*[!a-z]*) raw="" ;; esac
  [ -n "$raw" ] && break
  # 403 is the answer, not a blip: retrying cannot grant a permission. Anything
  # else might be a secondary rate limit or a transient 5xx, and this step sits
  # in the only required check on `main`.
  [ "$status" = "403" ] && break
  [ "$attempt" = 3 ] || sleep $((attempt * 5))
done

if [ -n "$raw" ]; then
  check 0 "the workflow token CAN read repository permissions (got '$raw' for $actor)"
elif [ "$status" = "403" ]; then
  check 1 "the workflow token CAN read repository permissions -- 403 for $actor"
  echo "        A PERMISSIONS REGRESSION, not a blip. GITHUB_TOKEN cannot call the"
  echo "        collaborators endpoint with the permissions this workflow declares,"
  echo "        so every review round would fail closed and the gate would be"
  echo "        permanently red. Check the job's \`permissions:\` block."
  sed 's/^/        /' "$WORK/err" >&2 || true
else
  check 1 "the workflow token CAN read repository permissions -- no answer for $actor after 3 tries"
  echo "        HTTP status: '${status:-none}'. This is NOT a 403, so it is more"
  echo "        likely an upstream fault -- a secondary rate limit or a 5xx -- than"
  echo "        a permissions regression. Re-run the job before treating it as one."
  sed 's/^/        /' "$WORK/err" >&2 || true
fi

# 2. The recorder's own verdict must match the permission the API reported.
case ",${ROUND_AUTHOR_PERMISSIONS}," in
  *",${raw},"*)
    if round_author_is_trusted "$actor" 2>"$WORK/why"; then
      check 0 "an authorised recorder ($actor, '$raw') is counted"
    else
      check 1 "an authorised recorder ($actor, '$raw') is counted"; sed 's/^/        /' "$WORK/why"
    fi ;;
  *)
    if round_author_is_trusted "$actor" 2>/dev/null; then
      check 1 "a non-writer ($actor, '${raw:-unreadable}') must NOT be counted"
    else
      check 0 "this actor ($actor, '${raw:-unreadable}') is not a writer and is not counted"
      echo "        (informational: run by an account without write access)"
    fi ;;
esac

# 3. A real, external, read-only account is refused.
if round_author_is_trusted "$OUTSIDER" 2>/dev/null; then
  check 1 "an external contributor ($OUTSIDER) is refused"
else
  check 0 "an external contributor ($OUTSIDER) is refused"
fi

# 4. A lookup that cannot succeed is refused, and SAYS WHY.
if round_author_is_trusted "$ABSENT" 2>"$WORK/diag"; then
  check 1 "an unresolvable account is refused"
else
  if grep -q "ignoring a round" "$WORK/diag"; then
    check 0 "an unresolvable account is refused, with a diagnostic"
    sed 's/^/        /' "$WORK/diag"
  else
    check 1 "an unresolvable account is refused WITH A DIAGNOSTIC (none printed)"
  fi
fi

echo
if [ "$fails" -eq 0 ]; then
  echo "review-gate permission lookup: live behaviour confirmed."
else
  echo "$fails live check(s) failed." >&2
fi
exit "$fails"

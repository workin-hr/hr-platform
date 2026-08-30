#!/usr/bin/env bash
# Step 7 of the Human Approval And Merge Sequence, made mechanical: every
# finding the independent reviewer raised must carry an explicit disposition
# before the pull request merges.
#
# What this checks and what it deliberately does not:
#
#   IT CHECKS that each thread opened by the named reviewer has a reply
#   declaring one of four dispositions. That is a decidable property.
#
#   IT DOES NOT judge whether the disposition is *right*. "Declined" with a bad
#   reason passes here exactly as "declined" with a good one. Automating that
#   judgement is not possible and pretending otherwise would be worse than not
#   trying -- a green check that implies review quality is a false signal, and
#   R-008 already records a merge that went wrong because a green box was read
#   as more than it said.
#
# So this closes the gap `required_conversation_resolution` leaves -- resolution
# is a state anyone with write access can set without answering, while a
# disposition is a written claim attributable to whoever wrote it -- and it
# leaves the reading of that claim to a human, which is where it belongs.
#
# The four dispositions, and what each asserts:
#   fixed                   - the code changed; the diff is the answer
#   declined-with-evidence  - the finding is wrong, and the reply says why with
#                             a reference someone else can check
#   accepted-risk           - the finding is right and is not being fixed now;
#                             the reply says who accepted it and where it is
#                             tracked
#   superseded              - a later change or finding replaced this one
#
# Usage:  scripts/check-review-dispositions.sh <pull-request-number>
#
# Requires `gh` (authenticated) and `jq`.
#
# Testability overrides (used by scripts/test_validate_phase0.py, never needed
# for a real run):
#   REVIEW_THREADS_JSON_FILE  - read the thread payload from this file instead
#     of calling `gh api graphql`.
#   INDEPENDENT_REVIEW_WORKFLOW_FILE - read the reviewer login from this file
#     instead of .github/workflows/independent-review-gate.yml.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
INDEPENDENT_REVIEW_WORKFLOW="${INDEPENDENT_REVIEW_WORKFLOW_FILE:-$SCRIPT_DIR/../.github/workflows/independent-review-gate.yml}"

DISPOSITIONS='fixed|declined-with-evidence|accepted-risk|superseded'

if [ ! -f "$INDEPENDENT_REVIEW_WORKFLOW" ]; then
  echo "Error: independent-review workflow not found: $INDEPENDENT_REVIEW_WORKFLOW" >&2
  exit 1
fi

# Read the reviewer from the workflow that queries it rather than keeping a
# second copy here that could drift (D-121). Comments are stripped first, and
# the value is taken from the assignment itself -- a login named only in a
# comment must not stand in for the one the gate actually uses. That precise
# bug has been written twice in this repository already.
REVIEWER="$(
  sed -e 's/^[[:space:]]*#.*$//' -e 's/[[:space:]]#[^"]*$//' "$INDEPENDENT_REVIEW_WORKFLOW" \
    | sed -n 's/.*REVIEWER:[[:space:]]*"\([^"]*\)".*/\1/p' | head -n 1
)"

if [ -z "$REVIEWER" ]; then
  echo "Error: could not read the reviewer login from $INDEPENDENT_REVIEW_WORKFLOW" >&2
  exit 1
fi

if [ -n "${REVIEW_THREADS_JSON_FILE:-}" ]; then
  THREADS_JSON="$(cat "$REVIEW_THREADS_JSON_FILE")"
else
  PR_NUMBER="${1:-}"
  if [ -z "$PR_NUMBER" ]; then
    echo "Usage: $0 <pull-request-number>" >&2
    exit 2
  fi
  # The $owner, $repo and $pr inside the single-quoted query below are GraphQL
  # variables bound by the -F flags, not shell expansions. They must reach the
  # server literally, so the single quotes are the point and SC2016 is noise.
  # shellcheck disable=SC2016
  # Paginated deliberately. A single `reviewThreads(first: 100)` page silently
  # truncates on a pull request with more than 100 threads, and an
  # undispositioned finding on thread 101 would then be invisible to the
  # arithmetic below -- the check would report success precisely on the
  # longest, most-reviewed pull requests, which are the ones it exists for.
  # This is not hypothetical: PR #142 in this repository carried 31 reviews,
  # and unpaginated REST queries against it produced two false "gate bypassed"
  # alarms earlier in the same wave.
  THREADS_JSON=""
  cursor="null"
  while :; do
    # shellcheck disable=SC2016
    page="$(gh api graphql -F pr="$PR_NUMBER" -F owner=:owner -F repo=:repo -F cursor="$cursor" -f query='
      query($owner: String!, $repo: String!, $pr: Int!, $cursor: String) {
        repository(owner: $owner, name: $repo) {
          pullRequest(number: $pr) {
            reviewThreads(first: 100, after: $cursor) {
              pageInfo { hasNextPage endCursor }
              nodes {
                isResolved
                path
                line
                comments(first: 100) { nodes { author { login } body } }
              }
            }
          }
        }
      }')"
    if ! echo "$page" | jq -e . >/dev/null 2>&1; then
      THREADS_JSON="$page"
      break
    fi
    if [ -z "$THREADS_JSON" ]; then
      THREADS_JSON="$page"
    else
      THREADS_JSON="$(jq -s '
        .[0] as $acc | .[1] as $next
        | $acc
        | .data.repository.pullRequest.reviewThreads.nodes =
            ($acc.data.repository.pullRequest.reviewThreads.nodes
             + $next.data.repository.pullRequest.reviewThreads.nodes)' \
        <(echo "$THREADS_JSON") <(echo "$page"))"
    fi
    has_next="$(echo "$page" | jq -r '.data.repository.pullRequest.reviewThreads.pageInfo.hasNextPage // false')"
    [ "$has_next" = "true" ] || break
    cursor="$(echo "$page" | jq -r '.data.repository.pullRequest.reviewThreads.pageInfo.endCursor')"
  done
fi

if ! echo "$THREADS_JSON" | jq -e . >/dev/null 2>&1; then
  echo "Error: review thread payload is not valid JSON:" >&2
  echo "$THREADS_JSON" >&2
  exit 1
fi

# The GraphQL API returns a bot's login WITHOUT the `[bot]` suffix that the REST
# API, the workflow declaration and the UI all show -- `chatgpt-codex-connector`
# rather than `chatgpt-codex-connector[bot]`. Comparing the two literally never
# matches, and this check would then report "no findings" on every real pull
# request and pass. A check that always passes is worse than no check, so the
# suffix is stripped from both sides before comparing.
#
# Found by running the script against a real pull request rather than by reading
# it; the original regression fixtures used the suffixed form because that is
# what the workflow declares, which is exactly the assumption that was wrong.
REVIEWER_LOGIN="${REVIEWER%\[bot\]}"

# A "finding" is a thread the reviewer opened. A thread someone else started is
# a conversation, not a finding, and step 7 does not speak to it.
findings="$(echo "$THREADS_JSON" | jq --arg reviewer "$REVIEWER_LOGIN" '
  [ .data.repository.pullRequest.reviewThreads.nodes[]
    | select((.comments.nodes | length) > 0)
    | select((.comments.nodes[0].author.login | sub("\\[bot\\]$"; "")) == $reviewer) ]')"

total="$(echo "$findings" | jq 'length')"

if [ "$total" -eq 0 ]; then
  echo "No findings from $REVIEWER on this pull request; nothing for step 7 to disposition."
  exit 0
fi

# The disposition must come from a reply, never from the finding itself: a
# reviewer quoting the word "fixed" in the text of its own finding must not
# discharge that finding.
undisposed="$(echo "$findings" | jq -r --arg d "$DISPOSITIONS" '
  .[] | select(
    ([ .comments.nodes[1:][] | select(.body | test("(?i)disposition:[[:space:]]*(" + $d + ")(?![-[:alnum:]_])")) ] | length) == 0
  ) | "  " + ((.path // "(no path)")) + ":" + ((.line // 0) | tostring)')"

missing="$(echo "$findings" | jq -r --arg d "$DISPOSITIONS" '
  [ .[] | select(
    ([ .comments.nodes[1:][] | select(.body | test("(?i)disposition:[[:space:]]*(" + $d + ")(?![-[:alnum:]_])")) ] | length) == 0
  ) ] | length')"

if [ "$missing" -gt 0 ]; then
  echo "FAIL: $missing of $total finding(s) from $REVIEWER carry no disposition:"
  echo "$undisposed"
  echo
  echo "Reply on each thread with one of:"
  echo "  Disposition: fixed                   -- the code changed; say which commit"
  echo "  Disposition: declined-with-evidence  -- say why, with a reference that can be checked"
  echo "  Disposition: accepted-risk           -- say who accepted it and where it is tracked"
  echo "  Disposition: superseded              -- say what replaced it"
  echo
  echo "This checks that a disposition was written, never that it is a good one."
  exit 1
fi

echo "All $total finding(s) from $REVIEWER carry an explicit disposition."
echo "That says each was answered in writing -- not that the answer is correct, which stays a human judgement (R-008)."

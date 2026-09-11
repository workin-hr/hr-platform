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

# Same rule, same reason, for the agent-round constants: the gate decides what a
# round IS, and this script must ask the same question. A second copy here drifts
# the moment the marker is tightened in the workflow -- and it drifts SILENTLY,
# because a marker that matches nothing makes the vacuity guard below turn itself
# off rather than fail.
AGENT_ROUND_RE="$(
  sed -e 's/^[[:space:]]*#.*$//' -e "s/[[:space:]]#[^\"']*$//" "$INDEPENDENT_REVIEW_WORKFLOW" \
    | sed -n "s/.*AGENT_ROUND_RE:[[:space:]]*'\([^']*\)'.*/\1/p" | head -n 1
)"
ROUND_AUTHOR_PERMISSIONS="$(
  sed -e 's/^[[:space:]]*#.*$//' -e "s/[[:space:]]#[^\"']*$//" "$INDEPENDENT_REVIEW_WORKFLOW" \
    | sed -n "s/.*ROUND_AUTHOR_PERMISSIONS:[[:space:]]*'\([^']*\)'.*/\1/p" | head -n 1
)"
export AGENT_ROUND_RE ROUND_AUTHOR_PERMISSIONS

if [ -z "$AGENT_ROUND_RE" ] || [ -z "$ROUND_AUTHOR_PERMISSIONS" ]; then
  echo "Error: could not read the agent-round marker or its permission allowlist from" >&2
  echo "       $INDEPENDENT_REVIEW_WORKFLOW -- the zero-findings guard cannot run" >&2
  echo "       without them, and running it with a marker that matches nothing" >&2
  echo "       would silently pass every claimed round (D-226)." >&2
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
                id
                isResolved
                path
                line
                  # 100 comments per thread, and the disposition is looked for in
                # comments[1:]. A thread that runs past 100 replies would hide a
                # valid disposition and block a merge that had actually answered
                # the finding -- the opposite failure to the outer truncation,
                # and the reason this is capped rather than silently sliced:
                # `totalCount` lets the check say so instead of guessing.
                comments(first: 100) {
                  totalCount
                  pageInfo { hasNextPage endCursor }
                  nodes { author { login } body }
                }
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
#
# D-226 breaks the author test, and silently: an agent reviewer is read-only, so
# the IMPLEMENTER posts its findings, and every such thread is authored by the
# implementer. Matching on the reviewer login alone found zero threads and exited
# 0 -- "nothing to disposition" -- for the entire agent review path. A check that
# always passes is worse than no check, which is what the comments in this file
# already say, so the marker below is what carries attribution instead.
#
# It is an ATTESTATION, not proof: the implementer writes it. What it does buy is
# that the claim is explicit and countable, so an agent round with no finding
# threads and no explicit zero-findings declaration fails rather than passing
# quietly.
FINDING_MARKER='finding-of: independent-review-agent'

findings="$(echo "$THREADS_JSON" | jq --arg reviewer "$REVIEWER_LOGIN" --arg marker "$FINDING_MARKER" '
  [ .data.repository.pullRequest.reviewThreads.nodes[]
    | select((.comments.nodes | length) > 0)
    | select(((.comments.nodes[0].author.login | sub("\\[bot\\]$"; "")) == $reviewer)
             or (.comments.nodes[0].body | test($marker))) ]')"

# A thread longer than one comment page needs its remaining comments fetched
# before the disposition can be judged. An earlier version refused instead --
# but refusing is not a recoverable state: answering or resolving the thread by
# hand does not reduce totalCount, so that thread would fail every subsequent
# run forever. Fetch the rest.
if [ -z "${REVIEW_THREADS_JSON_FILE:-}" ]; then
  overlong_ids="$(echo "$findings" | jq -r '
    .[] | select(.comments.totalCount > (.comments.nodes | length)) | .id')"
  for thread_id in $overlong_ids; do
    comment_cursor="$(echo "$findings" | jq -r --arg id "$thread_id" '
      .[] | select(.id == $id) | .comments.pageInfo.endCursor')"
    while [ -n "$comment_cursor" ] && [ "$comment_cursor" != "null" ]; do
      # shellcheck disable=SC2016
      comment_page="$(gh api graphql -F id="$thread_id" -F cursor="$comment_cursor" -f query='
        query($id: ID!, $cursor: String) {
          node(id: $id) {
            ... on PullRequestReviewThread {
              comments(first: 100, after: $cursor) {
                pageInfo { hasNextPage endCursor }
                nodes { author { login } body }
              }
            }
          }
        }')"
      findings="$(jq -s --arg id "$thread_id" '
        .[0] as $acc | .[1] as $page
        | $acc
        | map(if .id == $id
              then .comments.nodes += $page.data.node.comments.nodes
              else . end)' \
        <(echo "$findings") <(echo "$comment_page"))"
      if [ "$(echo "$comment_page" | jq -r '.data.node.comments.pageInfo.hasNextPage // false')" != "true" ]; then
        break
      fi
      comment_cursor="$(echo "$comment_page" | jq -r '.data.node.comments.pageInfo.endCursor')"
    done
  done
fi

# Whatever the source, refuse to judge a thread whose comments are still short
# of totalCount -- that can only happen if the fetch above was skipped (a
# fixture) or failed, and guessing in either direction is worse than saying so.
still_short="$(echo "$findings" | jq -r '
  .[] | select(.comments.totalCount > (.comments.nodes | length))
  | "  " + ((.path // "(no path)")) + ":" + ((.line // 0) | tostring)')"
if [ -n "$still_short" ]; then
  echo "Error: these threads could not be fully fetched, so a disposition may be unreadable:" >&2
  echo "$still_short" >&2
  exit 1
fi

total="$(echo "$findings" | jq 'length')"

if [ "$total" -eq 0 ]; then
  # Zero findings is only a pass when nobody CLAIMED a round. If a D-226 agent
  # round is recorded on this pull request, zero marked finding threads means
  # either the reviewer found nothing -- which must be declared, not inferred --
  # or the findings were never posted. Both look identical from here, so this
  # refuses rather than guessing, which is the vacuity the agent path introduced.
  # The counts come from the API in a real run, and from the environment in
  # fixture mode -- the same seam REVIEW_THREADS_JSON_FILE already uses, so the
  # refusal below is reachable by a regression test without a live pull request.
  if [ -n "${REVIEW_THREADS_JSON_FILE:-}" ]; then
    agent_round="${AGENT_ROUND_COUNT:-0}"
    declared_none="${DECLARED_NONE_COUNT:-0}"
  else
    # `$REPO` and `$PR` are the names the WORKFLOW uses, where they arrive as
    # `env:`. This script never set them, so under `set -u` the queries below
    # aborted at expansion before `gh` ran -- on every real invocation. Resolved
    # here rather than beside PR_NUMBER so the threads fetch above costs no
    # extra API call.
    PR="$PR_NUMBER"
    REPO="$(gh repo view --json nameWithOwner -q .nameWithOwner)"
    export PR REPO

    # A round comment is one the GATE would count: written by somebody with
    # write access, never edited since, and carrying the marker on its own line.
    # Anything weaker counts comments the gate does not, so the guard would be
    # answering a different question than the one that turns the status green.
    #
    # "Write access" is the repository PERMISSION, read per author below, not
    # author_association -- D-228, and the same authority the gate now uses.
    # shellcheck disable=SC2016
    round_comments='.[]
      | select(.created_at == .updated_at)
      | select(.body | test(env.AGENT_ROUND_RE))'

    # THREE outcomes, not two. The gate can collapse "not a writer" and "cannot
    # tell" into one, because there both mean "not counted" and not counted
    # means RED. Here they are opposites: a round that is not counted makes this
    # guard exit 0, so treating an unreadable permission as "not a writer" would
    # disarm the compensating control exactly when the API is unreliable --
    # failing OPEN while claiming to fail closed.
    #   0 = may record   1 = definitely may not   2 = could not determine
    author_may_record() {  # $1=login
      local permission
      case "$1" in ""|*[!A-Za-z0-9-]*) return 1 ;; esac
      if ! permission="$(gh api "repos/$REPO/collaborators/$1/permission" \
          --jq '.permission' 2>/dev/null)"; then
        permission=""
      fi
      # One lowercase word, or nothing. `gh` prints the error body on stdout for
      # a 404, which would otherwise read as a permission level.
      case "$permission" in ""|*[!a-z]*) permission="" ;; esac
      [ -n "$permission" ] || return 2
      case ",${ROUND_AUTHOR_PERMISSIONS}," in *",${permission},"*) return 0 ;; esac
      return 1
    }
    # Prints "<counted> <indeterminate>". Command substitution runs this in a
    # subshell, so the indeterminate tally travels in the output rather than in
    # a variable the caller would never see.
    count_rounds() {  # $1=extra jq filter ("" for none)
      local extra="$1" login n=0 unknown=0 listing listed=1
      # The LISTING's exit status matters as much as each lookup's. Discarding
      # it made a 502 or a secondary rate limit read as "no rounds", which this
      # guard treats as "nothing to disposition" and exits 0 -- the same
      # fail-open the tri-state below exists to close, one call earlier.
      # `--paginate` also stops at the first failed page, so a round on page 4
      # is invisible if page 3 fails.
      if ! listing="$(gh api "repos/$REPO/issues/$PR/comments" --paginate \
          --jq "$round_comments ${extra} | .user.login" 2>/dev/null)"; then
        listed=0
        listing=""
      fi
      while read -r login; do
        [ -n "$login" ] || continue
        # `if`, not `case $?`: a bare call would abort the loop under
        # `set -e` if inherit_errexit were ever enabled.
        if author_may_record "$login"; then
          n=$((n + 1))
        elif [ "$?" = 2 ]; then
          unknown=$((unknown + 1))
          echo "  could not read the repository permission of '$login'" >&2
        fi
      done <<EOF
$listing
EOF
      printf '%s %s %s' "$n" "$unknown" "$listed"
    }
    # Count LINES, never `| length`. `--paginate` emits one JSON array per page,
    # so `length` yields one count per page -- "0\n0" past 100 comments, which
    # `[ ... -gt 0 ]` reports as a syntax error, and an erroring test falls
    # through to the pass path. The gate's own counting documents this defect;
    # writing it a third time here would be the third time in this repository.
    read -r agent_round agent_unknown agent_listed <<EOF
$(count_rounds "")
EOF
    # The declaration must live IN a round comment, on its own line -- not
    # anywhere on the pull request. Matching it loosely made this guard disarm
    # itself: its own failure text below named the literal, so pasting that
    # failure into a comment satisfied the condition it was reporting.
    read -r declared_none _ _ <<EOF
$(count_rounds "| select(.body | test(\"(^|\\\\n)findings: none\"))")
EOF
  fi
  # An indeterminate lookup is not "no round". If the API could not tell us
  # whether a comment author may record one, this guard cannot answer the
  # question it exists to answer, and must say so rather than pass.
  if [ "${agent_listed:-1}" != "1" ]; then
    echo "FAIL: could not list this pull request's comments, so whether an agent round"
    echo "      was recorded is unknown. That is a failed lookup, not an absent round:"
    echo "      re-run once the GitHub API is answering, and do not merge on this result."
    exit 1
  fi
  if [ "${agent_unknown:-0}" -gt 0 ]; then
    echo "FAIL: could not determine the repository permission of ${agent_unknown} comment"
    echo "      author(s) on this pull request, so whether an agent round was recorded is"
    echo "      unknown -- and this guard cannot check the dispositions of a round it"
    echo "      cannot see. This is a failed lookup, not an absent round: re-run once the"
    echo "      GitHub API is answering, and do not merge on this result."
    exit 1
  fi
  if [ "${agent_round:-0}" -gt 0 ] && [ "${declared_none:-0}" -eq 0 ]; then
    echo "FAIL: an agent review round is recorded on this pull request, but no thread carries"
    echo "      '$FINDING_MARKER' and no round comment declares zero findings."
    echo "      A round that produced findings must post them; a round that produced none must"
    echo "      say so, in the round comment, on its own line. Silence is not a disposition."
    echo "      The declaration's exact form is in .agents/skills/independent-review/SKILL.md."
    exit 1
  fi
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

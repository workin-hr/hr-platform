#!/usr/bin/env bash
# What the independent-review gate counts as a completed round.
#
# The gate publishes the `independent-review` status that stands between a pull
# request and a merge, and its whole decision is one question: has the named
# reviewer completed a round on THIS head? Getting that wrong is expensive in
# both directions -- red on a reviewed head stalls every merge, green on an
# unreviewed one defeats the gate -- so the counting is pinned here.
#
# The function under test is EXTRACTED FROM THE WORKFLOW rather than copied, so
# this cannot pass against a stale duplicate of logic the workflow no longer has.
#
# `gh` is stubbed. The fixtures are the shapes this repository has actually
# seen, named in each case.
# The fixtures contain literal backticks: the reviewer writes the SHA as
# markdown code and the marker the gate matches includes them, so they are data
# rather than command substitution. File-scope because every fixture has them.
# shellcheck disable=SC2016

set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
WORKFLOW="$ROOT/.github/workflows/independent-review-gate.yml"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# The shipped function, dedented out of the `run: |` block.
sed -n '/^ *rounds_on_head() {/,/^ *}$/p' "$WORKFLOW" | sed 's/^          //' > "$WORK/rounds_on_head.sh"
if ! grep -q 'rounds_on_head() {' "$WORK/rounds_on_head.sh"; then
  echo "FATAL: could not extract rounds_on_head() from $WORKFLOW." >&2
  echo "  The gate's counting is unpinned until this test can find it again." >&2
  exit 2
fi

MARKER='**Reviewed commit:**'
HEAD_FULL=c9cc119482b48922e0c65a6ec1c2ed0d3f03923f

# The stub answers whichever endpoint it is asked for, from files the case wrote.
cat > "$WORK/gh" <<'STUB'
#!/usr/bin/env bash
for arg in "$@"; do
  case "$arg" in
    */reviews) cat "$FIXTURE_DIR/reviews.json"; exit 0 ;;
    */comments) cat "$FIXTURE_DIR/comments.json"; exit 0 ;;
  esac
done
echo "[]"
STUB
chmod +x "$WORK/gh"
PATH="$WORK:$PATH"
export REPO=owner/repo REVIEWER="chatgpt-codex-connector[bot]" FIXTURE_DIR="$WORK"

# The agent half of the gate, read from the workflow for the same reason the
# function is extracted from it: hardcoding these here would let the test pass
# against a marker the gate no longer uses. Unset, `test(null)` errors and the
# error is swallowed by the function's own `2>/dev/null`, so every agent case
# would silently count zero and still look green.
strip_comments() { sed -e 's/^[[:space:]]*#.*$//' -e "s/[[:space:]]#[^\"']*$//" "$WORKFLOW"; }
AGENT_ROUND_RE="$(strip_comments | sed -n "s/.*AGENT_ROUND_RE:[[:space:]]*'\([^']*\)'.*/\1/p" | head -n 1)"
ROUND_AUTHOR_ASSOC="$(strip_comments | sed -n "s/.*ROUND_AUTHOR_ASSOC:[[:space:]]*'\([^']*\)'.*/\1/p" | head -n 1)"
if [ -z "$AGENT_ROUND_RE" ] || [ -z "$ROUND_AUTHOR_ASSOC" ]; then
  echo "FATAL: could not read AGENT_ROUND_RE / ROUND_AUTHOR_ASSOC from $WORKFLOW." >&2
  echo "  The agent half of the gate is unpinned until this test can find them again." >&2
  exit 2
fi
export AGENT_ROUND_RE ROUND_AUTHOR_ASSOC

# gh's --jq is jq over the response; the stub prints the response, so the real
# jq has to do the filtering the workflow asks for.
cat > "$WORK/gh" <<'STUB'
#!/usr/bin/env bash
body=""
jqexpr=""
next_is_jq=0
for arg in "$@"; do
  if [ "$next_is_jq" = 1 ]; then jqexpr="$arg"; next_is_jq=0; continue; fi
  case "$arg" in
    --jq) next_is_jq=1 ;;
    */reviews) body="$FIXTURE_DIR/reviews.json" ;;
    */comments) body="$FIXTURE_DIR/comments.json" ;;
  esac
done
[ -n "$body" ] || { echo "[]"; exit 0; }
if [ -n "$jqexpr" ]; then jq -r "$jqexpr" < "$body"; else cat "$body"; fi
STUB
chmod +x "$WORK/gh"

# shellcheck source=/dev/null
. "$WORK/rounds_on_head.sh"

fails=0
expect() {  # $1=label $2=expected count
  local got
  got="$(HEAD_SHA="$HEAD_SHA" rounds_on_head 1 | wc -l | tr -d '[:space:]')"
  if [ "$got" = "$2" ]; then
    printf '  ok    %-52s %s round(s)\n' "$1" "$got"
  else
    printf '  FAIL  %-52s expected %s, got %s\n' "$1" "$2" "$got"
    fails=$((fails + 1))
  fi
}

reviews() { printf '%s' "$1" > "$WORK/reviews.json"; }
comments() { printf '%s' "$1" > "$WORK/comments.json"; }


export HEAD_SHA="$HEAD_FULL"

# 1. A round WITH findings: a review object on the head. Always counted.
reviews '[{"user":{"login":"chatgpt-codex-connector[bot]"},"commit_id":"'"$HEAD_FULL"'","state":"COMMENTED","submitted_at":"2026-09-02T14:29:19Z"}]'
comments '[]'
expect "review object on this head" 1

# 2. A CLEAN round: an issue comment carrying the marker, no review object.
#    This is the case the gate used to miss -- #163.
reviews '[]'
comments '[{"user":{"login":"chatgpt-codex-connector[bot]"},"created_at":"2026-09-02T16:01:35Z","body":"Codex Review: Didn'"'"'t find any major issues. :tada:\n\n'"$MARKER"' `c9cc119482`"}]'
expect "clean round, comment only (the #163 defect)" 1

# 3. The sign-off varies and must not be matched on.
comments '[{"user":{"login":"chatgpt-codex-connector[bot]"},"created_at":"2026-09-02T16:01:35Z","body":"Codex Review: Didn'"'"'t find any major issues. Breezy!\n\n'"$MARKER"' `c9cc119482`"}]'
expect "clean round with a different sign-off" 1

# 4. QUOTA EXHAUSTION must never satisfy the gate: R-009 makes the reviewer
#    unavailable, not waived. The message carries no marker.
comments '[{"user":{"login":"chatgpt-codex-connector[bot]"},"created_at":"2026-09-02T16:01:35Z","body":"You have reached your Codex usage limits for code reviews. You can see your limits in the Codex usage settings."}]'
expect "quota message (must NOT count)" 0

# 5. A clean round on an EARLIER commit -- #155 and #156. The head moved after
#    the round, which is exactly what D-121 refuses to merge.
comments '[{"user":{"login":"chatgpt-codex-connector[bot]"},"created_at":"2026-08-31T10:00:00Z","body":"Codex Review: Didn'"'"'t find any major issues.\n\n'"$MARKER"' `8478781bf8`"}]'
expect "marker naming an earlier commit (must NOT count)" 0

# 6. Somebody else cannot post a round on the reviewer's behalf.
comments '[{"user":{"login":"someone-else"},"created_at":"2026-09-02T16:01:35Z","body":"'"$MARKER"' `c9cc119482`"}]'
expect "marker from a different author (must NOT count)" 0

# 7. A truncated marker must not match everything.
comments '[{"user":{"login":"chatgpt-codex-connector[bot]"},"created_at":"2026-09-02T16:01:35Z","body":"'"$MARKER"' `c9c`"}]'
expect "marker too short to identify a commit (must NOT count)" 0

# 8. Both forms present -- a round with findings and a later clean one.
reviews '[{"user":{"login":"chatgpt-codex-connector[bot]"},"commit_id":"'"$HEAD_FULL"'","state":"COMMENTED","submitted_at":"2026-09-02T14:29:19Z"}]'
comments '[{"user":{"login":"chatgpt-codex-connector[bot]"},"created_at":"2026-09-02T16:01:35Z","body":"'"$MARKER"' `c9cc119482`"}]'
expect "both a review object and a marker comment" 2

# 9. A DISMISSED review is not a round.
reviews '[{"user":{"login":"chatgpt-codex-connector[bot]"},"commit_id":"'"$HEAD_FULL"'","state":"DISMISSED","submitted_at":"2026-09-02T14:29:19Z"}]'
comments '[]'
expect "dismissed review (must NOT count)" 0

# --- D-226 agent rounds -------------------------------------------------------
# The gate counts these too, and until now no case exercised them at all.

AGENT_BODY="independent-review-round: agent\r\nhead: $HEAD_FULL"
agent() {  # $1=association $2=created $3=updated $4=body
  comments '[{"author_association":"'"$1"'","created_at":"'"$2"'","updated_at":"'"$3"'","body":"'"$4"'"}]'
}
reviews '[]'

# 10. The round the skill tells the reviewer to produce.
agent OWNER t1 t1 "$AGENT_BODY"
expect "agent round by a writer, naming this head" 1

# 11. Naming a different head. Same rule as the Codex path: a round does not
#     carry forward to a commit the reviewer never saw.
agent OWNER t1 t1 "independent-review-round: agent\r\nhead: 8478781bf88478781bf88478781bf88478781bf8"
expect "agent round naming another head (must NOT count)" 0

# 12. No `head:` line at all -- a claim about nothing in particular.
agent OWNER t1 t1 "independent-review-round: agent\r\nlooks fine to me"
expect "agent round with no head SHA (must NOT count)" 0

# 13. The self-approval bypass this gate shipped with: the marker was matched on
#     body text alone, so on a PUBLIC repository any GitHub account could green
#     the gate on anyone's pull request with a single comment.
agent NONE t1 t1 "$AGENT_BODY"
expect "agent round from outside the repository (must NOT count)" 0

# 14. CONTRIBUTOR is earned by one merged commit and grants no write access.
agent CONTRIBUTOR t1 t1 "$AGENT_BODY"
expect "agent round from a drive-by contributor (must NOT count)" 0

# 15. Post a round naming head A, push commits, then EDIT the comment to name
#     head B. `edited` is one of this workflow's triggers, so without this the
#     SHA binding buys nothing.
agent OWNER t1 t2 "$AGENT_BODY"
expect "edited agent round (must NOT count)" 0

# 16. A comment QUOTING the marker -- reviewing this workflow on a pull request
#     is exactly that -- must not claim a round on the workflow it quotes.
agent OWNER t1 t1 "the gate sets:\r\n    AGENT_ROUND_RE: independent-review-round: agent\r\nhead: $HEAD_FULL"
expect "quoted marker in a review of the gate (must NOT count)" 0

# 17. Both mechanisms on the same head count separately.
reviews '[{"user":{"login":"chatgpt-codex-connector[bot]"},"commit_id":"'"$HEAD_FULL"'","state":"COMMENTED","submitted_at":"2026-09-02T14:29:19Z"}]'
agent OWNER t1 t1 "$AGENT_BODY"
expect "a Codex round and an agent round on one head" 2

# 18. The OUTPUT CONTRACT, not just the count. The retarget path sorts these
#     lines and compares the result against a bare marker timestamp, so the
#     timestamp must lead. A leading mechanism tag sorts by mechanism and makes
#     that comparison always false -- silently clearing a base-change
#     invalidation that must hold the gate red. Counting cases cannot see this.
reviews '[]'
agent OWNER t1 t1 "$AGENT_BODY"
line="$(HEAD_SHA="$HEAD_FULL" rounds_on_head 1 | head -n 1)"
if printf '%s' "$line" | grep -Eq '^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9:]+Z? (codex|agent)$' \
   || printf '%s' "$line" | grep -Eq '^t[0-9]+ (codex|agent)$'; then
  printf '  ok    %-52s %s\n' "round line leads with its timestamp" "$line"
else
  printf '  FAIL  %-52s got %s\n' "round line leads with its timestamp" "$line"
  fails=$((fails + 1))
fi

echo
if [ "$fails" -ne 0 ]; then
  echo "$fails case(s) failed."
  exit 1
fi
echo "review-gate counting: all cases pass."

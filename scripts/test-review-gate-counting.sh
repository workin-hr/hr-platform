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

echo
if [ "$fails" -ne 0 ]; then
  echo "$fails case(s) failed."
  exit 1
fi
echo "review-gate counting: all cases pass."

#!/usr/bin/env bash
# Mechanically verifies GitHub branch protection on `main` instead of a
# human eyeballing the GitHub UI — closes GH-1 in the Engineering
# Enablement Plan. Turns execution-checklist.md H1's "Done when: main is
# protected" into something checkable rather than assumed.
#
# Requires `gh`, authenticated with access to the target GitHub
# organization/repository, plus `jq`. This cannot run at all until H1
# (GitHub organization and branch-protection setup) has actually been
# done, and cannot run in this environment regardless — it needs real
# GitHub organization access that a bootstrap/planning session does not
# have. Built and unit-tested now so it is ready the moment both exist.
#
# Testability overrides (used by scripts/test_validate_phase0.py, never
# needed for a real run):
#   BRANCH_PROTECTION_JSON_FILE - read protection JSON from this file
#     instead of calling `gh api`.
#   PHASE0_WORKFLOW_FILE - read the required job id from this file
#     instead of .github/workflows/phase0-validate.yml.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WORKFLOW_FILE="${PHASE0_WORKFLOW_FILE:-$SCRIPT_DIR/../.github/workflows/phase0-validate.yml}"

if [ ! -f "$WORKFLOW_FILE" ]; then
  echo "Error: workflow file not found: $WORKFLOW_FILE" >&2
  exit 1
fi

# Extract the first job id under a top-level `jobs:` key
# (`  <job-id>:` at exactly 2-space indent), so this stays bound to
# whatever the workflow actually calls its job rather than a hardcoded
# guess that could drift from it.
REQUIRED_JOB_NAME="$(awk '
  /^jobs:[[:space:]]*$/ { injobs=1; next }
  injobs && /^  [A-Za-z0-9_-]+:[[:space:]]*$/ {
    line=$0
    sub(/^  /, "", line)
    sub(/:.*/, "", line)
    print line
    exit
  }
' "$WORKFLOW_FILE")"

if [ -z "$REQUIRED_JOB_NAME" ]; then
  echo "Error: could not parse a job id out of $WORKFLOW_FILE" >&2
  exit 1
fi

if ! command -v jq >/dev/null 2>&1; then
  echo "Error: jq is required and not on PATH." >&2
  exit 1
fi

if [ -n "${BRANCH_PROTECTION_JSON_FILE:-}" ]; then
  if [ ! -f "$BRANCH_PROTECTION_JSON_FILE" ]; then
    echo "Error: BRANCH_PROTECTION_JSON_FILE set but not found: $BRANCH_PROTECTION_JSON_FILE" >&2
    exit 1
  fi
  PROTECTION_JSON="$(cat "$BRANCH_PROTECTION_JSON_FILE")"
else
  if ! command -v gh >/dev/null 2>&1; then
    echo "Error: gh (GitHub CLI) is required and not on PATH." >&2
    exit 1
  fi
  if ! PROTECTION_JSON="$(gh api repos/:owner/:repo/branches/main/protection 2>&1)"; then
    echo "Error: could not fetch branch protection for main via" >&2
    echo "  gh api repos/:owner/:repo/branches/main/protection" >&2
    echo "This requires gh to be authenticated with access to the target GitHub organization/repository." >&2
    echo "$PROTECTION_JSON" >&2
    exit 1
  fi
fi

if ! echo "$PROTECTION_JSON" | jq -e . >/dev/null 2>&1; then
  echo "Error: branch protection response is not valid JSON:" >&2
  echo "$PROTECTION_JSON" >&2
  exit 1
fi

failures=0

required_review_count="$(echo "$PROTECTION_JSON" | jq -r '.required_pull_request_reviews.required_approving_review_count // 0')"
if [ "$required_review_count" -lt 1 ]; then
  echo "FAIL: required_pull_request_reviews.required_approving_review_count is $required_review_count (need >= 1)"
  failures=$((failures + 1))
fi

enforce_admins="$(echo "$PROTECTION_JSON" | jq -r '.enforce_admins.enabled // false')"
if [ "$enforce_admins" != "true" ]; then
  echo "FAIL: enforce_admins.enabled is $enforce_admins (need true)"
  failures=$((failures + 1))
fi

allow_force_pushes="$(echo "$PROTECTION_JSON" | jq -r '.allow_force_pushes.enabled // false')"
if [ "$allow_force_pushes" != "false" ]; then
  echo "FAIL: allow_force_pushes.enabled is $allow_force_pushes (need false)"
  failures=$((failures + 1))
fi

# A required approving review does NOT gate on the named independent reviewer
# (D-121): that reviewer is read-only and cannot approve, so a human approval
# satisfies the count while a review round is still in flight -- which is how
# PR #126 merged ten seconds after its round posted (R-008, third instance).
# required_conversation_resolution is the setting that actually blocks that
# merge, because unaddressed findings are unresolved threads.
conversation_resolution="$(echo "$PROTECTION_JSON" | jq -r '.required_conversation_resolution.enabled // false')"
if [ "$conversation_resolution" != "true" ]; then
  echo "FAIL: required_conversation_resolution.enabled is $conversation_resolution (need true, so a merge cannot outrun unresolved review findings -- see R-008)"
  failures=$((failures + 1))
fi

contexts="$(echo "$PROTECTION_JSON" | jq -r '(.required_status_checks.contexts // []) | join(",")')"
if ! echo ",$contexts," | grep -q ",$REQUIRED_JOB_NAME,"; then
  echo "FAIL: required_status_checks.contexts ($contexts) does not include '$REQUIRED_JOB_NAME' (the job id in $(basename "$WORKFLOW_FILE"))"
  failures=$((failures + 1))
fi

if [ "$failures" -gt 0 ]; then
  echo
  echo "$failures branch-protection requirement(s) not met for main."
  exit 1
fi

echo "Branch protection on main meets all requirements (required review >= 1, enforce_admins, no force pushes, conversation resolution, required check '$REQUIRED_JOB_NAME')."

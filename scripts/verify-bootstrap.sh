#!/usr/bin/env sh
set -eu

# Two modes, controlled by BOOTSTRAP_STRICT:
#
#   - Default (BOOTSTRAP_STRICT unset or not "true"): developer-friendly
#     LOCAL convenience check. Runs the mandatory validator and regression
#     tests (always required, never skipped), then runs each external
#     linter/scanner only if it happens to be on PATH, printing install
#     guidance and skipping (not failing) any that are missing. Do not
#     treat a clean default-mode run as equivalent to CI passing — a tool
#     missing locally is silently skipped here.
#
#   - BOOTSTRAP_STRICT=true: strict mode, used by
#     .github/workflows/phase0-validate.yml. Every external tool below
#     must already be resolvable on PATH — CI installs pinned,
#     checksum-verified (see docs/bootstrap/audit-remediation.md, P2-01,
#     for exactly which tools are checksum-verified versus only
#     version-pinned) copies into a job-local tools directory and adds it
#     to PATH *before* this script runs, so the tool invocations below
#     resolve to the exact same binaries CI's own dedicated steps already
#     exercised — this script does not download anything itself. A
#     missing required tool is a hard failure in this mode, never a skip.

ROOT="$(CDPATH="" cd -- "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

STRICT="${BOOTSTRAP_STRICT:-false}"

echo "[1/3] Running repository bootstrap validator"
python3 scripts/validate_phase0.py

echo "[2/3] Running deterministic regression test suites (always required)"
python3 scripts/test_git_guard.py
python3 scripts/test_adr_validation.py

echo "[3/3] Running external tool checks (BOOTSTRAP_STRICT=$STRICT)"

run_tool() {
  tool="$1"
  shift
  if command -v "$tool" >/dev/null 2>&1; then
    echo "Running $tool $*"
    "$tool" "$@"
    return $?
  fi
  if [ "$STRICT" = "true" ]; then
    echo "REQUIRED TOOL MISSING: $tool is not on PATH." >&2
    echo "In BOOTSTRAP_STRICT=true mode this is a hard failure, not a skip." >&2
    echo "CI installs a pinned copy into a job-local tools directory added to PATH before this step runs." >&2
    return 1
  fi
  echo "Skipping $tool: not installed locally. Run scripts/check-bootstrap-prerequisites.sh for install guidance, or rely on CI (which never skips)."
  return 0
}

status=0
run_tool markdownlint-cli2 "**/*.md" || status=1
run_tool yamllint -s . || status=1
run_tool shellcheck scripts/*.sh .agents/skills/*/scripts/*.sh || status=1
run_tool actionlint || status=1
run_tool gitleaks detect --no-git --source . --redact --exit-code 1 || status=1
run_tool lychee "**/*.md" || status=1

exit "$status"

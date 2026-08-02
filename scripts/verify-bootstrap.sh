#!/usr/bin/env sh
set -eu

# This is a developer-friendly LOCAL convenience check: it runs the
# mandatory structural validator, then runs each linter/scanner only if it
# happens to be installed, skipping (not failing) when a tool is missing.
# It is intentionally lenient so it's cheap to run before committing.
#
# It is NOT the enforcement mechanism. The authoritative, non-skippable
# checks run in .github/workflows/phase0-validate.yml, which installs a
# pinned, checksum-verified version of every one of these tools and fails
# the build if any of them is missing or reports a problem. Do not treat a
# clean local run of this script as equivalent to CI passing.

ROOT="$(CDPATH="" cd -- "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

echo "[1/2] Running repository bootstrap validator"
python3 scripts/validate_phase0.py

echo "[2/2] Running optional local tools when available"

run_optional() {
  tool="$1"
  shift
  if command -v "$tool" >/dev/null 2>&1; then
    echo "Running $tool $*"
    "$tool" "$@"
  else
    echo "Skipping $tool: not installed locally (this is fine here; CI installs a pinned copy and does not skip)"
  fi
}

run_optional markdownlint-cli2 "**/*.md"
run_optional yamllint .
run_optional shellcheck scripts/*.sh .agents/skills/*/scripts/*.sh
run_optional actionlint
run_optional gitleaks detect --no-git --source . --redact
run_optional lychee "**/*.md"

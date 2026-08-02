#!/usr/bin/env sh
set -eu

ROOT="$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)"
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
    echo "Skipping $tool: not installed locally"
  fi
}

run_optional markdownlint-cli2 "**/*.md"
run_optional yamllint .
run_optional shellcheck scripts/*.sh .agents/skills/*/scripts/*.sh
run_optional actionlint
run_optional gitleaks detect --no-git --source .
run_optional lychee --offline --no-progress "**/*.md"

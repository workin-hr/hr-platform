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
python3 scripts/test_validate_phase0.py
python3 scripts/test_edit_audit_log.py
python3 scripts/test_check_flyway_versions.py
python3 scripts/test_check_rls_migration_safety.py
python3 scripts/test_migration_diff.py
python3 scripts/test_check_legacy_schema_drift.py

echo "[3/3] Running external tool checks (BOOTSTRAP_STRICT=$STRICT)"

# CI-2: BOOTSTRAP_STRICT=true (every CI run) hard-fails on a missing tool —
# it can never reach the "skip" branch below, so a skip can only happen in
# a local, non-strict run. A skip there is easy to miss buried in the
# middle of otherwise-normal output, and a clean local run is not proof CI
# will pass. Track skips and print an unmissable summary at the end
# instead — a warning naming what was skipped locally, or a positive
# confirmation that all 6 tools actually ran.
skipped=""
skipped_count=0
total_tools=6

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
  skipped="$skipped $tool"
  skipped_count=$((skipped_count + 1))
  return 0
}

# Git-ignored paths are not repository content -- the same boundary
# scripts/validate_phase0.py applies, and for the same reason. Installing a
# third-party agent skill (`npx skills add ...` writes to .agents/skills/)
# drops that author's shell scripts under a glob this script expands, so
# without the filter their ShellCheck conventions become this repository's
# aggregate verification failure, on the machine that installed them and
# nowhere else.
#
# Deliberately NOT applied to gitleaks below: narrowing a secret scan by
# gitignore is backwards. A credential sitting in an ignored third-party
# tree is exactly the one worth knowing about, and the scan is cheap.
not_ignored() {
  for candidate in "$@"; do
    [ -e "$candidate" ] || continue
    git check-ignore -q "$candidate" 2>/dev/null && continue
    printf '%s\n' "$candidate"
  done
}

# shellcheck disable=SC2046 # deliberate word splitting: one argument per path
set -- $(not_ignored scripts/*.sh .agents/skills/*/scripts/*.sh)
shell_scripts="$*"

# lychee and yamllint both take paths rather than a config-level gitignore
# switch (unlike markdownlint-cli2, which has "gitignore": true), so the same
# boundary is applied by handing them explicit file lists.
#
# --cached --others --exclude-standard, not plain `git ls-files`: the default
# lists tracked files only, which would silently drop a new, not-yet-staged
# document from the link check. The exemption is meant to exclude ignored
# third-party tooling, not every file a developer has just written.
tracked_and_new() {
  git ls-files --cached --others --exclude-standard -- "$1" | tr '\n' ' '
}
markdown_files="$(tracked_and_new '*.md')"
yaml_files="$(tracked_and_new '*.yml')$(tracked_and_new '*.yaml')"

status=0
run_tool markdownlint-cli2 "**/*.md" || status=1
# shellcheck disable=SC2086 # deliberate word splitting: one argument per path
run_tool yamllint -s $yaml_files || status=1
# shellcheck disable=SC2086 # same
run_tool shellcheck $shell_scripts || status=1
run_tool actionlint || status=1
run_tool gitleaks detect --no-git --source . --redact --exit-code 1 || status=1
# shellcheck disable=SC2086 # deliberate word splitting: one argument per path
run_tool lychee $markdown_files || status=1

echo
echo "=================================================================="
if [ "$skipped_count" -gt 0 ]; then
  echo "SUMMARY: $skipped_count of $total_tools external tool(s) skipped locally (not on PATH):$skipped"
  echo "Every one of these runs in CI (BOOTSTRAP_STRICT=true) regardless — a"
  echo "clean local run with skips is NOT equivalent to CI passing."
  echo "Run scripts/check-bootstrap-prerequisites.sh for install guidance."
else
  echo "SUMMARY: all $total_tools external tool checks ran (none skipped)."
fi
echo "=================================================================="

exit "$status"

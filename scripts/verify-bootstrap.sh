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

# run_tool's sibling for the tools that take a file list.
#
# The list arrives NUL-delimited on stdin **by redirection, never by pipe**: a
# pipeline runs its right-hand side in a subshell, so the `skipped` /
# `skipped_count` updates below would be discarded and the SUMMARY -- which
# exists precisely so a local skip cannot go unnoticed -- would under-report.
# Caught by the shim regression test after exactly that happened to lychee.
#
# The list is handed over with xargs -0,
# because filenames may legally contain spaces, tabs, glob characters or
# newlines -- all of which a space-joined, unquoted expansion would split or
# glob into different arguments, so the linter would inspect the wrong paths or
# fail on a valid one. POSIX sh has no arrays, so xargs is the portable way to
# keep argument boundaries intact.
run_tool_on_list() {
  tool="$1"
  shift
  if command -v "$tool" >/dev/null 2>&1; then
    echo "Running $tool on the filtered file list"
    xargs -0 "$tool" "$@"
    return $?
  fi
  cat >/dev/null   # drain the list so the producer does not see EPIPE
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
not_ignored_z() {
  for candidate in "$@"; do
    [ -e "$candidate" ] || continue
    git check-ignore -q "$candidate" 2>/dev/null && continue
    printf '%s\0' "$candidate"
  done
}

# lychee and yamllint both take paths rather than a config-level gitignore
# switch (unlike markdownlint-cli2, which has "gitignore": true), so the same
# boundary is applied by handing them explicit file lists.
#
# --cached --others --exclude-standard, not plain `git ls-files`: the default
# lists tracked files only, which would silently drop a new, not-yet-staged
# document from the link check. The exemption is meant to exclude ignored
# third-party tooling, not every file a developer has just written.
#
# The existence filter drops the other direction: --cached still emits a file
# the developer has deleted but not yet staged, and handing a linter a path
# that is not there fails the whole run before it reaches the files that do
# exist.
existing_files() {
  git ls-files -z --cached --others --exclude-standard -- "$@" |
    xargs -0 sh -c 'for f do
      if [ -e "$f" ]; then printf "%s\0" "$f"; fi
    done' _
}

# A temp file rather than a pipe, so run_tool_on_list stays in this shell and
# its skip accounting survives. Removed on exit, including on failure.
file_list="$(mktemp)"
trap 'rm -f "$file_list"' EXIT

status=0
run_tool markdownlint-cli2 "**/*.md" || status=1
existing_files '*.yml' '*.yaml' > "$file_list"
run_tool_on_list yamllint -s < "$file_list" || status=1
not_ignored_z scripts/*.sh .agents/skills/*/scripts/*.sh > "$file_list"
run_tool_on_list shellcheck < "$file_list" || status=1
run_tool actionlint || status=1
run_tool gitleaks detect --no-git --source . --redact --exit-code 1 || status=1
existing_files '*.md' > "$file_list"
run_tool_on_list lychee < "$file_list" || status=1

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

#!/usr/bin/env sh
set -eu

# Bootstrap prerequisite checker. Reports the status of every tool this
# repository's Phase 0 tooling depends on. Installs nothing.
#
# Modes:
#   - Default (BOOTSTRAP_STRICT unset or not "true"): informational. A
#     missing operator-environment tool is reported as an actionable
#     warning, not a failure.
#   - BOOTSTRAP_STRICT=true: tools this repository's own validation
#     genuinely requires (git, python3) fail if missing. Tools that are
#     operator-environment-dependent by design (specify, Claude/Codex
#     CLIs, and the local lint/scan tools) remain warnings even in strict
#     mode here, because this script's job is to report status, not to
#     gate CI — CI enforces its own required tools directly in
#     .github/workflows/phase0-validate.yml by installing pinned copies
#     itself, not through this script. See
#     docs/bootstrap/audit-remediation.md (P2-03) for why `specify`
#     specifically is never a strict-mode failure here: Spec Kit's
#     repository integration artifacts are static files already committed
#     (see .specify/README.md); Phase 0 CI does not re-run `specify init`
#     and therefore does not require the CLI to be present to validate
#     what's committed.

STRICT="${BOOTSTRAP_STRICT:-false}"
overall_status=0

report_required() {
  name="$1"
  shift
  if command -v "$name" >/dev/null 2>&1; then
    path="$(command -v "$name")"
    version="$("$@" 2>&1 | head -n1 || true)"
    echo "[OK]      $name -> $path ($version)"
  else
    echo "[MISSING] $name not found on PATH."
    if [ "$STRICT" = "true" ]; then
      echo "          BOOTSTRAP_STRICT=true: $name is required and missing. Failing."
      overall_status=1
    else
      echo "          Install $name before running Phase 0 validation."
    fi
  fi
}

report_operator_dependent() {
  name="$1"
  expected_range="$2"
  guidance="$3"
  shift 3
  if command -v "$name" >/dev/null 2>&1; then
    path="$(command -v "$name")"
    version="$("$@" 2>&1 | head -n1 || true)"
    echo "[OK]      $name -> $path ($version) [expected: $expected_range]"
  else
    echo "[ABSENT]  $name not found on PATH — this is operator-environment"
    echo "          dependent, not a repository defect. CLI availability"
    echo "          genuinely varies between environments (confirmed"
    echo "          directly: one audit environment had it, another did"
    echo "          not — see docs/bootstrap/audit-remediation.md, P2-03)."
    echo "          Expected version range: $expected_range"
    echo "          Install: $guidance"
  fi
}

echo "=== Bootstrap prerequisite check (BOOTSTRAP_STRICT=$STRICT) ==="
echo

report_required git git --version
report_required python3 python3 --version

echo
echo "--- Spec Kit ---"
report_operator_dependent specify ">=0.8.0 (last verified against 0.8.15)" \
  "see .specify/install-plan.md for the approved installation mechanism (uv tool install specify-cli)" \
  specify --version

echo
echo "--- Agent CLIs (used to run/verify this repository's agent definitions) ---"
report_operator_dependent claude "any (Claude Code CLI)" \
  "see https://claude.com/claude-code for installation" \
  claude --version
report_operator_dependent codex "any (Codex CLI)" \
  "see the Codex CLI distribution channel your organization uses" \
  codex --version

echo
echo "--- Local validation tools (CI installs pinned copies of these itself;"
echo "    this section is diagnostic only and never fails, even in strict mode)"
report_operator_dependent markdownlint-cli2 "0.23.2 (version pinned in CI)" \
  "npm install --global markdownlint-cli2@0.23.2" \
  markdownlint-cli2 --version
report_operator_dependent yamllint "1.38.0 (version pinned in CI)" \
  "pip install yamllint==1.38.0" \
  yamllint --version
report_operator_dependent shellcheck "0.11.0 (pinned + checksum-verified in CI)" \
  "https://github.com/koalaman/shellcheck/releases/tag/v0.11.0" \
  shellcheck --version
report_operator_dependent actionlint "1.7.12 (pinned + checksum-verified in CI)" \
  "https://github.com/rhysd/actionlint/releases/tag/v1.7.12" \
  actionlint -version
report_operator_dependent gitleaks "8.30.1 (pinned + checksum-verified in CI)" \
  "https://github.com/gitleaks/gitleaks/releases/tag/v8.30.1" \
  gitleaks version
report_operator_dependent lychee "0.24.2 (pinned + checksum-verified in CI)" \
  "https://github.com/lycheeverse/lychee/releases/tag/lychee-v0.24.2" \
  lychee --version

echo
if [ "$overall_status" -ne 0 ]; then
  echo "=== Result: FAIL (a required tool is missing under BOOTSTRAP_STRICT=true) ==="
else
  echo "=== Result: OK (all required tools present; see above for operator-dependent tool status) ==="
fi
exit "$overall_status"

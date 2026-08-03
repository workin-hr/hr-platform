#!/usr/bin/env sh
set -eu

# This used to be a second, hand-maintained reimplementation of ADR
# structural validation, separate from scripts/validate_phase0.py. It
# drifted out of sync once already (the "## Decision" vs "## Proposed
# Direction" mismatch that a prior audit found — see
# docs/bootstrap/audit-remediation.md, P1-1) and had a hardcoded ADR list
# problem of its own kind. Rather than maintain two divergent validators,
# this now delegates to the single authoritative implementation in
# scripts/validate_phase0.py (see docs/bootstrap/audit-remediation.md,
# P2-02).

file="${1:?usage: validate-adr.sh <file>}"
SCRIPT_DIR="$(CDPATH="" cd -- "$(dirname "$0")" && pwd)"
VALIDATOR="$SCRIPT_DIR/../../../../scripts/validate_phase0.py"

exec python3 "$VALIDATOR" --validate-adr "$file"

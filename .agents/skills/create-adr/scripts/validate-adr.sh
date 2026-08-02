#!/usr/bin/env sh
set -eu

file="${1:?usage: validate-adr.sh <file>}"
base="$(basename "$file")"

# File naming: the shared template is exempt; every real ADR must match
# ADR-NNNN-slug.md, matching docs/adr/README.md and scripts/validate_phase0.py.
case "$base" in
  ADR-0000-template.md) ;;
  ADR-[0-9][0-9][0-9][0-9]-*.md) ;;
  *)
    echo "Invalid ADR file name (expected ADR-NNNN-slug.md): $base"
    exit 1
    ;;
esac

for section in "## Metadata" "## Context" "## Decision" "## Alternatives Considered" "## Consequences" "## Risks" "## Validation Evidence" "## Open Questions"; do
  if ! grep -Fq "$section" "$file"; then
    echo "Missing section: $section"
    exit 1
  fi
done

for field in "ADR ID" "Title" "Status" "Date" "Owners" "Deciders" "Related Issues" "Supersedes" "Superseded By"; do
  if ! grep -Eq "^\| *${field} *\|" "$file"; then
    echo "Missing metadata field: $field"
    exit 1
  fi
done

status_line="$(grep -E '^\| *Status *\|' "$file" | head -n1)"
case "$status_line" in
  *Proposed*|*Accepted*|*Rejected*|*Superseded*|*Deferred*) ;;
  *)
    echo "Invalid or missing Status value: $status_line"
    exit 1
    ;;
esac

if printf '%s' "$status_line" | grep -q "Proposed"; then
  if ! grep -Fq "Approval status: Proposed" "$file"; then
    echo "Proposed ADR must visibly state its Decision has not been approved (missing 'Approval status: Proposed' marker under ## Decision)"
    exit 1
  fi
fi

# Coarse non-empty-content check: every required section must have at least
# one non-blank, non-header line before the next '## ' heading or EOF. This
# is intentionally a lighter check than the authoritative one performed by
# scripts/validate_phase0.py::validate_adrs(), which checks each section
# individually; this script exists as a fast local/pre-commit check.
awk '
  /^## / { if (section != "") { if (!has_content) { print "Empty section: " section; err = 1 } }
           section = $0; has_content = 0; next }
  { if (section != "" && $0 !~ /^[[:space:]]*$/) has_content = 1 }
  END { if (section != "" && !has_content) { print "Empty section: " section; err = 1 }
        exit err }
' "$file"

echo "ADR structure looks valid: $file"

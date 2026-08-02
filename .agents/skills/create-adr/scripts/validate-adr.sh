#!/usr/bin/env sh
set -eu

file="${1:?usage: validate-adr.sh <file>}"

for section in "## Status" "## Context" "## Decision" "## Consequences" "## Alternatives Considered"; do
  if ! grep -Fq "$section" "$file"; then
    echo "Missing section: $section"
    exit 1
  fi
done

echo "ADR structure looks valid: $file"

#!/usr/bin/env bash
# Prints the exact Codex CLI invocation flags for a given agent role.
#
# docs/agents/operating-model.md (enforcement layer 2) is explicit that
# Codex's sandboxing "only holds if a human actually applies it" — Codex has
# no equivalent of Claude Code's per-agent tool scoping, and reads
# configuration only from the operator's own ~/.codex/config.toml plus CLI
# flags, never from a project-local file (see .codex/config.toml). This
# script exists so the human operator does not have to recall the right
# flags from memory before every invocation.
#
# It extracts the "Recommended invocation for this role" bash block directly
# from the target .codex/agents/<name>.md file, rather than duplicating the
# flags here, so it cannot drift from that file being the source of truth.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
AGENTS_DIR="$SCRIPT_DIR/../.codex/agents"

usage() {
  echo "Usage: $0 <agent-name>" >&2
  echo >&2
  echo "Available agent names:" >&2
  if [ -d "$AGENTS_DIR" ]; then
    for f in "$AGENTS_DIR"/*.md; do
      [ -f "$f" ] || continue
      echo "  - $(basename "$f" .md)" >&2
    done
  fi
}

if [ "$#" -ne 1 ]; then
  usage
  exit 1
fi

AGENT_NAME="$1"
AGENT_FILE="$AGENTS_DIR/${AGENT_NAME}.md"

if [ ! -f "$AGENT_FILE" ]; then
  echo "Error: no agent definition at .codex/agents/${AGENT_NAME}.md" >&2
  usage
  exit 1
fi

# Extract the single ```bash ... ``` fenced block that immediately follows
# "Recommended invocation for this role:" in the agent file.
COMMAND="$(awk '
  /Recommended invocation for this role:/ { found=1; next }
  found && /^```bash/ { incode=1; next }
  found && incode && /^```/ { exit }
  found && incode { print }
' "$AGENT_FILE")"

if [ -z "$COMMAND" ]; then
  echo "Error: no 'Recommended invocation for this role:' bash block found in $AGENT_FILE" >&2
  exit 1
fi

echo "$COMMAND"

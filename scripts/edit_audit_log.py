#!/usr/bin/env python3
"""PostToolUse hook: appends a one-line audit-trail entry for every
Edit/Write/NotebookEdit tool call to evidence/agent-file-changes.log.

The "read-only unless a human explicitly assigns documentation work"
boundary for planning/review sessions in this repository is a procedural
control (docs/agents/operating-model.md, enforcement layer 5) — nothing
technically stops a top-level Claude Code session from calling Edit or
Write, unlike the six `.claude/agents/*.md` subagents, which are tool-scoped
for real (layer 1). This hook does not change that: it never blocks the
underlying call. It only strengthens decision traceability (the review
standard named in CLAUDE.md) with a cheap, local, append-only record of
what file-modifying tool touched what path and when, independent of `git
diff` — useful when a change needs to be reconstructed or questioned later.

Log path can be overridden with the AGENT_AUDIT_LOG_PATH environment
variable (used by scripts/test_edit_audit_log.py so tests never write into
the real evidence/agent-file-changes.log). *.log is already gitignored —
this is a local artifact, not a repository one.

Wired into: .claude/settings.json (hooks.PostToolUse, matcher
"Edit|Write|NotebookEdit"), scripts/validate_phase0.py.
"""

from __future__ import annotations

import json
import os
import sys
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

TRACKED_TOOLS = {"Edit", "Write", "NotebookEdit"}


def log_path() -> Path:
    override = os.environ.get("AGENT_AUDIT_LOG_PATH")
    if override:
        return Path(override)
    return ROOT / "evidence/agent-file-changes.log"


def extract_file_path(tool_input: dict) -> str | None:
    # Edit and Write use "file_path"; NotebookEdit uses "notebook_path".
    for key in ("file_path", "notebook_path"):
        value = tool_input.get(key)
        if isinstance(value, str) and value:
            return value
    return None


def build_log_line(payload: dict, now: datetime | None = None) -> str | None:
    """Returns the log line to append, or None if this payload is not a
    tracked Edit/Write/NotebookEdit call with a determinable file path.
    Pure function — no I/O — so it can be tested deterministically without
    a real clock or a real filesystem."""
    tool_name = payload.get("tool_name")
    if tool_name not in TRACKED_TOOLS:
        return None
    tool_input = payload.get("tool_input")
    if not isinstance(tool_input, dict):
        return None
    file_path = extract_file_path(tool_input)
    if file_path is None:
        return None
    timestamp = (now or datetime.now(timezone.utc)).isoformat(timespec="seconds")
    return f"{timestamp}\t{tool_name}\t{file_path}\n"


def main() -> int:
    try:
        raw_stdin = sys.stdin.read()
    except Exception:  # pragma: no cover - defensive; stdin should be a pipe
        raw_stdin = ""

    try:
        payload = json.loads(raw_stdin) if raw_stdin.strip() else None
    except json.JSONDecodeError:
        payload = None

    if isinstance(payload, dict):
        line = build_log_line(payload)
        if line is not None:
            try:
                path = log_path()
                path.parent.mkdir(parents=True, exist_ok=True)
                with path.open("a", encoding="utf-8") as handle:
                    handle.write(line)
            except OSError:
                # This hook only observes; it must never fail the
                # underlying tool call over a logging problem.
                pass

    # Always allow. This hook has no permissionDecision output at all —
    # unlike scripts/git_guard.py, it never denies anything.
    return 0


if __name__ == "__main__":
    sys.exit(main())

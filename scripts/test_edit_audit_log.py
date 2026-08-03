#!/usr/bin/env python3
"""Deterministic regression tests for scripts/edit_audit_log.py.

Run directly: `python3 scripts/test_edit_audit_log.py`. Uses a temporary
file (via the AGENT_AUDIT_LOG_PATH override) for every subprocess case —
never the real evidence/agent-file-changes.log.

Wired into: scripts/verify-bootstrap.sh (required, not skippable),
scripts/validate_phase0.py (invoked as a subprocess check via
validate_script_test_siblings' requirement), and
.github/workflows/phase0-validate.yml.
"""

from __future__ import annotations

import json
import os
import subprocess
import sys
import tempfile
from datetime import datetime, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import edit_audit_log as m  # noqa: E402

SCRIPT = Path(__file__).resolve().parent / "edit_audit_log.py"

FIXED_NOW = datetime(2026, 8, 3, 12, 0, 0, tzinfo=timezone.utc)

CASES_RUN: list[tuple[bool, str]] = []


def check(condition: bool, description: str) -> None:
    CASES_RUN.append((condition, description))
    print(f"{'OK ' if condition else 'FAIL'} {description}")


# ---------------------------------------------------------------------------
# build_log_line: pure-function cases, no I/O, no real clock.
# ---------------------------------------------------------------------------


def test_edit_payload_produces_a_log_line() -> None:
    line = m.build_log_line(
        {"tool_name": "Edit", "tool_input": {"file_path": "docs/foo.md", "old_string": "a", "new_string": "b"}},
        now=FIXED_NOW,
    )
    check(
        line == "2026-08-03T12:00:00+00:00\tEdit\tdocs/foo.md\n",
        f"an Edit payload produces the expected tab-separated log line (line={line!r})",
    )


def test_write_payload_produces_a_log_line() -> None:
    line = m.build_log_line(
        {"tool_name": "Write", "tool_input": {"file_path": "scripts/new.py", "content": "x"}},
        now=FIXED_NOW,
    )
    check(
        line == "2026-08-03T12:00:00+00:00\tWrite\tscripts/new.py\n",
        f"a Write payload produces the expected log line (line={line!r})",
    )


def test_notebookedit_payload_uses_notebook_path() -> None:
    line = m.build_log_line(
        {"tool_name": "NotebookEdit", "tool_input": {"notebook_path": "analysis.ipynb"}},
        now=FIXED_NOW,
    )
    check(
        line == "2026-08-03T12:00:00+00:00\tNotebookEdit\tanalysis.ipynb\n",
        f"a NotebookEdit payload reads notebook_path, not file_path (line={line!r})",
    )


def test_unrelated_tool_is_ignored() -> None:
    line = m.build_log_line({"tool_name": "Bash", "tool_input": {"command": "ls"}}, now=FIXED_NOW)
    check(line is None, f"a Bash payload (not Edit/Write/NotebookEdit) produces no log line (line={line!r})")


def test_missing_file_path_is_ignored() -> None:
    line = m.build_log_line({"tool_name": "Edit", "tool_input": {"old_string": "a"}}, now=FIXED_NOW)
    check(line is None, f"an Edit payload missing file_path produces no log line (line={line!r})")


def test_malformed_payload_shapes_are_ignored_not_raised() -> None:
    results = [
        m.build_log_line({}, now=FIXED_NOW),
        m.build_log_line({"tool_name": "Edit"}, now=FIXED_NOW),
        m.build_log_line({"tool_name": "Edit", "tool_input": "not-a-dict"}, now=FIXED_NOW),
    ]
    check(
        all(r is None for r in results),
        f"malformed payload shapes return None rather than raising (results={results!r})",
    )


# ---------------------------------------------------------------------------
# Hook subprocess end-to-end, isolated to a temp log file.
# ---------------------------------------------------------------------------


def run_hook(payload: object, log_file: Path) -> subprocess.CompletedProcess:
    env = dict(os.environ)
    env["AGENT_AUDIT_LOG_PATH"] = str(log_file)
    stdin = payload if isinstance(payload, str) else json.dumps(payload)
    return subprocess.run(
        [sys.executable, str(SCRIPT)],
        input=stdin,
        capture_output=True,
        text=True,
        timeout=10,
        env=env,
    )


def test_hook_appends_to_overridden_log_path() -> None:
    with tempfile.TemporaryDirectory(prefix="edit-audit-log-test-") as tmp:
        log_file = Path(tmp) / "audit.log"
        proc = run_hook(
            {"tool_name": "Edit", "tool_input": {"file_path": "docs/x.md", "old_string": "a", "new_string": "b"}},
            log_file,
        )
        contents = log_file.read_text(encoding="utf-8") if log_file.is_file() else ""
        check(
            proc.returncode == 0 and contents.endswith("\tEdit\tdocs/x.md\n"),
            f"the hook subprocess exits 0 and appends one line to the overridden log path "
            f"(exit={proc.returncode}, contents={contents!r})",
        )


def test_hook_never_blocks_even_for_tracked_tools() -> None:
    with tempfile.TemporaryDirectory(prefix="edit-audit-log-test-") as tmp:
        log_file = Path(tmp) / "audit.log"
        proc = run_hook({"tool_name": "Write", "tool_input": {"file_path": "a.txt", "content": "x"}}, log_file)
        check(
            proc.returncode == 0 and proc.stdout == "" and proc.stderr == "",
            f"the hook never denies (no hookSpecificOutput, exit 0, silent) (exit={proc.returncode}, "
            f"stdout={proc.stdout!r}, stderr={proc.stderr!r})",
        )


def test_hook_survives_malformed_stdin() -> None:
    with tempfile.TemporaryDirectory(prefix="edit-audit-log-test-") as tmp:
        log_file = Path(tmp) / "audit.log"
        proc = run_hook("not json at all {{{", log_file)
        check(
            proc.returncode == 0 and not log_file.exists(),
            f"malformed stdin exits 0 and writes nothing (exit={proc.returncode}, log exists={log_file.exists()})",
        )


def test_hook_ignores_unrelated_tool_and_writes_nothing() -> None:
    with tempfile.TemporaryDirectory(prefix="edit-audit-log-test-") as tmp:
        log_file = Path(tmp) / "audit.log"
        proc = run_hook({"tool_name": "Bash", "tool_input": {"command": "git status"}}, log_file)
        check(
            proc.returncode == 0 and not log_file.exists(),
            f"a Bash payload exits 0 and never creates the log file (exit={proc.returncode}, "
            f"log exists={log_file.exists()})",
        )


def main() -> int:
    test_edit_payload_produces_a_log_line()
    test_write_payload_produces_a_log_line()
    test_notebookedit_payload_uses_notebook_path()
    test_unrelated_tool_is_ignored()
    test_missing_file_path_is_ignored()
    test_malformed_payload_shapes_are_ignored_not_raised()
    test_hook_appends_to_overridden_log_path()
    test_hook_never_blocks_even_for_tracked_tools()
    test_hook_survives_malformed_stdin()
    test_hook_ignores_unrelated_tool_and_writes_nothing()

    passed = sum(1 for ok, _ in CASES_RUN if ok)
    total = len(CASES_RUN)
    print(f"\n{passed}/{total} edit_audit_log regression cases passed.")
    if passed != total:
        print(f"{total - passed} FAILURE(S)")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())

#!/usr/bin/env python3
"""What the compose parity check catches, and what it must not fail on.

The check exists because prose alone kept the two stacks in step, and prose had
already failed once. So the cases that matter are the silent ones: a value
changed on one side, a setting added to one side, and the intended differences
themselves disappearing.
"""
from __future__ import annotations

import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

SCRIPT = Path(__file__).resolve().parent / "check_compose_dev_parity.py"
CASES: list[tuple[bool, str]] = []

LOCAL = """\
name: workin-local
services:
  db:
    image: mariadb:11.8.8
    healthcheck:
      interval: 5s
      retries: 60
  app:
    build:
      context: ..
      dockerfile: deploy/Dockerfile
    restart: unless-stopped
"""

DEV = """\
name: workin-dev
services:
  db:
    image: mariadb:11.8.8
    healthcheck:
      interval: 5s
      retries: 60
  app:
    image: ghcr.io/workin-hr/hr-platform/backend:${BACKEND_TAG:-main}
    restart: unless-stopped
"""


def run(local: str, dev: str) -> subprocess.CompletedProcess:
    root = Path(tempfile.mkdtemp(prefix="compose-parity-"))
    try:
        (root / "deploy").mkdir()
        (root / "scripts").mkdir()
        (root / "deploy/compose.local.yaml").write_text(local, encoding="utf-8")
        (root / "deploy/compose.dev.yaml").write_text(dev, encoding="utf-8")
        copy = root / "scripts" / SCRIPT.name
        copy.write_text(SCRIPT.read_text(encoding="utf-8"), encoding="utf-8")
        return subprocess.run([sys.executable, str(copy)],
                              capture_output=True, text=True, timeout=30)
    finally:
        shutil.rmtree(root)


def check(ok: bool, label: str) -> None:
    CASES.append((ok, label))
    print(("  ok    " if ok else "  FAIL  ") + label)


def main() -> int:
    proc = run(LOCAL, DEV)
    check(proc.returncode == 0,
          f"the two intended differences pass (exit={proc.returncode}, err={proc.stderr[:120]!r})")

    # Comments and blank lines are how each file explains itself; they carry no
    # behaviour and must not count as drift.
    noisy = DEV.replace("services:", "# a comment the other file lacks\n\nservices:")
    proc = run(LOCAL, noisy)
    check(proc.returncode == 0,
          f"comments and blank lines are not drift (exit={proc.returncode})")

    # A changed value on one side only -- the silent case.
    proc = run(LOCAL, DEV.replace("interval: 5s", "interval: 30s"))
    check(proc.returncode == 1 and "interval: 30s" in proc.stderr,
          f"a changed health interval fails and is named (exit={proc.returncode})")

    # A setting added to one side only.
    proc = run(LOCAL, DEV.replace("    restart: unless-stopped",
                                  "    restart: unless-stopped\n    mem_limit: 512m"))
    check(proc.returncode == 1 and "mem_limit" in proc.stderr,
          f"a setting present on one side only fails (exit={proc.returncode})")

    # The same, in the other direction: the local stack gains something.
    proc = run(LOCAL.replace("    restart: unless-stopped",
                             "    restart: unless-stopped\n    mem_limit: 512m"), DEV)
    check(proc.returncode == 1 and "mem_limit" in proc.stderr,
          f"drift is caught in both directions (exit={proc.returncode})")

    # The intended difference disappearing must also fail: if the dev stack
    # starts BUILDING the image it is no longer testing the published one, and
    # a silent pass would hide that.
    converged = DEV.replace(
        "    image: ghcr.io/workin-hr/hr-platform/backend:${BACKEND_TAG:-main}",
        "    build:\n      context: ..\n      dockerfile: deploy/Dockerfile")
    proc = run(LOCAL.replace("name: workin-local", "name: workin-dev"), converged)
    check(proc.returncode == 1 and "GONE" in proc.stderr,
          f"an intended difference vanishing fails too (exit={proc.returncode})")

    # A missing file is a failure, not a silent skip: this check protects a
    # file a developer could delete while splitting the stacks apart.
    root = Path(tempfile.mkdtemp(prefix="compose-parity-"))
    try:
        (root / "deploy").mkdir()
        (root / "scripts").mkdir()
        (root / "deploy/compose.local.yaml").write_text(LOCAL, encoding="utf-8")
        copy = root / "scripts" / SCRIPT.name
        copy.write_text(SCRIPT.read_text(encoding="utf-8"), encoding="utf-8")
        proc = subprocess.run([sys.executable, str(copy)],
                              capture_output=True, text=True, timeout=30)
    finally:
        shutil.rmtree(root)
    check(proc.returncode == 1 and "is missing" in proc.stderr,
          f"a missing compose file fails rather than skipping (exit={proc.returncode})")

    passed = sum(1 for ok, _ in CASES if ok)
    print(f"\n{passed}/{len(CASES)} compose parity cases passed.")
    return 0 if passed == len(CASES) else 1


if __name__ == "__main__":
    sys.exit(main())

#!/usr/bin/env python3
"""What the remote-db pin check catches, and what it must not fail on.

The check exists because the answer to "what test fails if these lines are
deleted?" was none. So the case that matters is each pin removed on its own --
and the near-miss forms that a checker matching loosely would let through: the
line commented out, the value flipped, the quotes dropped, the port widened.
"""
from __future__ import annotations

import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

SCRIPT = Path(__file__).resolve().parent / "check_remote_db_pins.py"
CASES: list[tuple[bool, str]] = []

COMPOSE = """\
name: workin-remote-db
services:
  app:
    environment:
      APP_TRACE_SAMPLING: ${APP_TRACE_SAMPLING:-1.0}
      # Actuator stays shut: this process holds production credentials.
      MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE: health
      SPRINGDOC_API_DOCS_ENABLED: "false"
      SPRINGDOC_SWAGGER_UI_ENABLED: "false"
    ports:
      - "127.0.0.1:${APP_PUBLISHED_PORT:-8080}:8080"
"""


def run(compose: str | None) -> subprocess.CompletedProcess:
    root = Path(tempfile.mkdtemp(prefix="remote-db-pins-"))
    try:
        (root / "deploy").mkdir()
        (root / "scripts").mkdir()
        if compose is not None:
            (root / "deploy/compose.remote-db.yaml").write_text(compose, encoding="utf-8")
        copy = root / "scripts" / SCRIPT.name
        copy.write_text(SCRIPT.read_text(encoding="utf-8"), encoding="utf-8")
        return subprocess.run([sys.executable, str(copy)],
                              capture_output=True, text=True, timeout=30)
    finally:
        shutil.rmtree(root)


def check(ok: bool, label: str) -> None:
    CASES.append((ok, label))
    print(("  ok    " if ok else "  FAIL  ") + label)


PINS = (
    ('      SPRINGDOC_API_DOCS_ENABLED: "false"\n', "SPRINGDOC_API_DOCS_ENABLED"),
    ('      SPRINGDOC_SWAGGER_UI_ENABLED: "false"\n', "SPRINGDOC_SWAGGER_UI_ENABLED"),
    ("      MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE: health\n",
     "MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE"),
    ('      - "127.0.0.1:${APP_PUBLISHED_PORT:-8080}:8080"\n', "127.0.0.1"),
)


def main() -> int:
    proc = run(COMPOSE)
    check(proc.returncode == 0,
          f"the pinned file passes (exit={proc.returncode}, err={proc.stderr[:120]!r})")

    # The case the check was written for: each pin deleted, one at a time. A
    # checker that passed here would be decorative.
    for line, name in PINS:
        assert line in COMPOSE, line
        proc = run(COMPOSE.replace(line, ""))
        check(proc.returncode == 1 and name in proc.stderr,
              f"deleting {name} fails and is named (exit={proc.returncode})")

    # Commented out is deleted. A checker that searched the raw text would find
    # the pin in its own explanation and pass.
    for line, name in PINS:
        proc = run(COMPOSE.replace(line, "  #" + line.lstrip()))
        check(proc.returncode == 1 and name in proc.stderr,
              f"commenting {name} out fails (exit={proc.returncode})")

    # Flipped values, not absent ones -- the form an edit actually takes.
    proc = run(COMPOSE.replace('SPRINGDOC_API_DOCS_ENABLED: "false"',
                               'SPRINGDOC_API_DOCS_ENABLED: "true"'))
    check(proc.returncode == 1 and "SPRINGDOC_API_DOCS_ENABLED" in proc.stderr,
          f"flipping api-docs to true fails (exit={proc.returncode})")

    # Unquoted `false` is YAML boolean false, which Compose rejects for an
    # environment value; pinning the quoted literal is deliberate.
    proc = run(COMPOSE.replace('SPRINGDOC_API_DOCS_ENABLED: "false"',
                               "SPRINGDOC_API_DOCS_ENABLED: false"))
    check(proc.returncode == 1,
          f"dropping the quotes fails (exit={proc.returncode})")

    # Re-enabling docs through .env instead of the literal.
    proc = run(COMPOSE.replace('SPRINGDOC_API_DOCS_ENABLED: "false"',
                               "SPRINGDOC_API_DOCS_ENABLED: ${SPRINGDOC_API_DOCS_ENABLED:-false}"))
    check(proc.returncode == 1 and "SPRINGDOC_API_DOCS_ENABLED" in proc.stderr,
          f"making the pin .env-overridable fails (exit={proc.returncode})")

    # Widening the publish to every interface.
    proc = run(COMPOSE.replace('- "127.0.0.1:${APP_PUBLISHED_PORT:-8080}:8080"',
                               '- "${APP_PUBLISHED_PORT:-8080}:8080"'))
    check(proc.returncode == 1 and "127.0.0.1" in proc.stderr,
          f"publishing on all interfaces fails (exit={proc.returncode})")

    # Widening actuator exposure.
    proc = run(COMPOSE.replace("MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE: health",
                               "MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE: health,env"))
    check(proc.returncode == 1 and "MANAGEMENT_ENDPOINTS" in proc.stderr,
          f"widening actuator exposure fails (exit={proc.returncode})")

    # Comments and blank lines elsewhere are how the file explains itself.
    noisy = COMPOSE.replace("services:", "\n# why this stack exists\n\nservices:")
    proc = run(noisy)
    check(proc.returncode == 0,
          f"comments and blank lines are not drift (exit={proc.returncode})")

    # Re-ordering the environment block is not drift.
    reordered = COMPOSE.replace(
        '      SPRINGDOC_API_DOCS_ENABLED: "false"\n'
        '      SPRINGDOC_SWAGGER_UI_ENABLED: "false"\n',
        '      SPRINGDOC_SWAGGER_UI_ENABLED: "false"\n'
        '      SPRINGDOC_API_DOCS_ENABLED: "false"\n')
    proc = run(reordered)
    check(proc.returncode == 0, f"re-ordering the pins is not drift (exit={proc.returncode})")

    # A missing file fails rather than skipping.
    proc = run(None)
    check(proc.returncode == 1 and "is missing" in proc.stderr,
          f"a missing compose file fails rather than skipping (exit={proc.returncode})")

    passed = sum(1 for ok, _ in CASES if ok)
    print(f"\n{passed}/{len(CASES)} remote-db pin cases passed.")
    return 0 if passed == len(CASES) else 1


if __name__ == "__main__":
    sys.exit(main())

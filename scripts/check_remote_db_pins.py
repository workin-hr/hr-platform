#!/usr/bin/env python3
"""deploy/compose.remote-db.yaml must keep the pins that make it safe to point
at production.

This is the one compose file in the repository whose own header says it may
point at the production database, and the only stack here that publishes the
same host and port the load harness defaults to. Three settings are what keep
that from being dangerous, and every one of them is a single line that reads
like a preference:

  SPRINGDOC_API_DOCS_ENABLED / SPRINGDOC_SWAGGER_UI_ENABLED
      perf/run.sh refuses a target that does not publish the API description.
      Because this stack publishes to 127.0.0.1 it satisfies the harness's
      loopback condition BY CONSTRUCTION, so this pin is the only condition
      left that refuses it. Re-enable docs for a cutover rehearsal and
      `./run.sh all` against the default BASE_URL starts generating load
      against production, with every other gate in the repository still green.

  MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE
      Anything beyond `health` exposes actuator -- heapdump, env, mappings --
      on a process holding production credentials.

  ports:
      Loopback-only. Widening it to `${APP_PUBLISHED_PORT}:8080` puts that
      process on every interface of whatever machine runs it.

They were pinned as literals so that `.env` cannot override them, and nothing
checked that they stayed. A review found the gap by asking what test would fail
if the lines were deleted, and the answer was none: this stack is in no test
matrix, and `deploy/e2e/run.sh` only ever drives local, integration and prod.
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
COMPOSE = "deploy/compose.remote-db.yaml"

# (regex, what it pins, why it matters if it goes)
REQUIRED: tuple[tuple[str, str, str], ...] = (
    (
        r'^\s*SPRINGDOC_API_DOCS_ENABLED:\s*"false"\s*$',
        'SPRINGDOC_API_DOCS_ENABLED: "false"',
        "perf/run.sh's only remaining refusal for this stack; without it a load "
        "run against the default BASE_URL reaches the production database",
    ),
    (
        r'^\s*SPRINGDOC_SWAGGER_UI_ENABLED:\s*"false"\s*$',
        'SPRINGDOC_SWAGGER_UI_ENABLED: "false"',
        "serves an interactive API console against production data",
    ),
    (
        r"^\s*MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE:\s*health\s*$",
        "MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE: health",
        "anything wider exposes actuator on a process holding production "
        "credentials",
    ),
    (
        r'^\s*-\s*"127\.0\.0\.1:\$\{APP_PUBLISHED_PORT:-8080\}:8080"\s*$',
        '- "127.0.0.1:${APP_PUBLISHED_PORT:-8080}:8080"',
        "publishing on all interfaces exposes that process to the network",
    ),
)


def main() -> int:
    path = ROOT / COMPOSE
    if not path.is_file():
        print(f"FAIL: {COMPOSE} is missing", file=sys.stderr)
        return 1

    # Comments are dropped first: a pin that has been commented out is gone,
    # and a checker that matched the explanation above the line would not
    # notice.
    lines = [
        raw
        for raw in path.read_text(encoding="utf-8").splitlines()
        if raw.strip() and not raw.strip().startswith("#")
    ]

    missing = [
        (literal, why)
        for pattern, literal, why in REQUIRED
        if not any(re.match(pattern, line) for line in lines)
    ]
    if missing:
        print(f"FAIL: {COMPOSE} has lost a pin that keeps it safe.\n", file=sys.stderr)
        for literal, why in missing:
            print(f"  missing: {literal}\n    {why}\n", file=sys.stderr)
        print(
            "These are literals on purpose, so that .env cannot override them.\n"
            "If one genuinely has to change, change it here too and say why.",
            file=sys.stderr,
        )
        return 1

    print(f"{COMPOSE} keeps all {len(REQUIRED)} pins.")
    return 0


if __name__ == "__main__":
    sys.exit(main())

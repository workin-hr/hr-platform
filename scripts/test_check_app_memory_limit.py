#!/usr/bin/env python3
"""What the app memory-limit check catches, and what it must not fail on.

The failure it exists for is one of D-295's three copies of the limit edited
alone. So the cases are each copy changed on its own, a limit that ignores the
variable, a limit moved where only rendering can see it, and each way the
check could skip instead of fail: no compose, no limit, an unreadable example.
The last case runs against the real deploy/ so the gate follows the files.
"""
from __future__ import annotations

import os
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

SCRIPT = Path(__file__).resolve().parent / "check_app_memory_limit.py"
REPO = SCRIPT.parents[1]
CASES: list[tuple[bool, str]] = []

PROD = """\
services:
  app:
    image: app
    environment:
      JWT_SECRET: ${JWT_SECRET}
    deploy:
      resources:
        limits:
          memory: ${APP_MEMORY_LIMIT:-2g}
"""

REMOTE = """\
services:
  app:
    image: app
    environment:
      LEGACY_DB_JDBC_URL: jdbc:mariadb://${DB_HOST}:${DB_PORT:-3306}/${DB_NAME:?set DB_NAME}
    deploy:
      resources:
        limits:
          memory: ${APP_MEMORY_LIMIT:-2g} # D-295
"""

EXAMPLE = "JWT_SECRET=\n# the heap is 75% of this\nAPP_MEMORY_LIMIT=2g\n"
REMOTE_EXAMPLE = "DB_HOST=\n"


def run(prod: str | None = PROD, remote: str | None = REMOTE, example: str | None = EXAMPLE,
        remote_example: str | None = REMOTE_EXAMPLE, dotenv: str | None = None,
        path: str | None = None) -> subprocess.CompletedProcess:
    root = Path(tempfile.mkdtemp(prefix="app-memory-limit-"))
    try:
        deploy = root / "deploy"
        deploy.mkdir()
        for name, text in (("compose.prod.yaml", prod), ("compose.remote-db.yaml", remote),
                           ("env.prod.example", example), ("env.remote-db.example", remote_example),
                           (".env", dotenv)):
            if text is not None:
                (deploy / name).write_text(text, encoding="utf-8")
        env = dict(os.environ)
        env.pop("APP_MEMORY_LIMIT", None)
        if path is not None:
            env["PATH"] = path
        return subprocess.run([sys.executable, str(SCRIPT), "--root", str(root)],
                              capture_output=True, text=True, timeout=120, env=env)
    finally:
        shutil.rmtree(root, ignore_errors=True)


def check(ok: bool, label: str) -> None:
    CASES.append((ok, label))
    print(f"  {'ok  ' if ok else 'FAIL'}  {label}")


def refused(proc: subprocess.CompletedProcess, needle: str, label: str) -> None:
    check(proc.returncode == 1 and needle in proc.stderr,
          f"{label} (exit={proc.returncode}, err={proc.stderr.strip()[:160]!r})")


def main() -> int:
    if shutil.which("docker") is None:
        print("docker is not on PATH; this check renders compose files and cannot run without it", file=sys.stderr)
        return 1

    # The green control: without it every refusal below could be the fixture.
    proc = run()
    check(proc.returncode == 0, f"matching fixtures pass (exit={proc.returncode}, err={proc.stderr[:160]!r})")

    refused(run(prod=PROD.replace(":-2g", ":-1g")), "compose.prod.yaml: with APP_MEMORY_LIMIT unset",
            "the prod default changed alone is refused")
    refused(run(remote=REMOTE.replace(":-2g", ":-1g")), "compose.remote-db.yaml: with APP_MEMORY_LIMIT unset",
            "the remote-db default changed alone is refused")
    proc = run(example=EXAMPLE.replace("=2g", "=1g"))
    check(proc.returncode == 1 and proc.stderr.count("with APP_MEMORY_LIMIT unset") == 2,
          f"the example changed alone is refused for both files (exit={proc.returncode})")
    refused(run(remote=REMOTE.replace("${APP_MEMORY_LIMIT:-2g}", "2g")), "the variable does not decide it",
            "a hard-coded limit that ignores the variable is refused")
    refused(run(prod=PROD.replace("${APP_MEMORY_LIMIT:-2g}", "${APP_MEMORY_LIMIT:-2048m}").replace("2048m", "2047m")),
            "compose.prod.yaml: with APP_MEMORY_LIMIT unset", "a default one MiB off is refused")

    # Rendering, not text: equal sizes in other units pass, and an anchor that
    # carries the limit is resolved rather than missed.
    proc = run(prod=PROD.replace(":-2g", ":-2048m"))
    check(proc.returncode == 0, f"2048m is the same limit as 2g (exit={proc.returncode}, err={proc.stderr[:160]!r})")
    anchored = ("x-limits: &limits\n  resources:\n    limits:\n      memory: ${APP_MEMORY_LIMIT:-1g}\n"
                + PROD.replace("    deploy:\n      resources:\n        limits:\n"
                               "          memory: ${APP_MEMORY_LIMIT:-2g}\n", "    deploy: *limits\n"))
    refused(run(prod=anchored), "compose.prod.yaml: with APP_MEMORY_LIMIT unset",
            "a wrong default behind an anchor is refused")
    proc = run(prod=anchored.replace(":-1g", ":-2g"))
    check(proc.returncode == 0, f"a right default behind an anchor passes (exit={proc.returncode}, "
                                f"err={proc.stderr[:160]!r})")

    # A developer's deploy/.env must not decide the answer.
    proc = run(dotenv="APP_MEMORY_LIMIT=1g\n")
    check(proc.returncode == 0, f"a local deploy/.env is ignored (exit={proc.returncode}, err={proc.stderr[:160]!r})")

    refused(run(remote_example=REMOTE_EXAMPLE + "APP_MEMORY_LIMIT=1g\n"), "env.remote-db.example sets APP_MEMORY_LIMIT=1g",
            "the remote-db example setting another value is refused")
    proc = run(remote_example=REMOTE_EXAMPLE + "APP_MEMORY_LIMIT=2048m\n")
    check(proc.returncode == 0, f"the remote-db example setting the same value passes (exit={proc.returncode})")

    # Each way to skip is a failure instead.
    refused(run(example=EXAMPLE + "APP_MEMORY_LIMIT=2g\n"), "sets APP_MEMORY_LIMIT 2 times",
            "two lines in the example are refused")
    refused(run(example=EXAMPLE.replace("APP_MEMORY_LIMIT=2g", "# APP_MEMORY_LIMIT=2g")),
            "does not set APP_MEMORY_LIMIT", "a commented-out example line is refused")
    refused(run(example=EXAMPLE.replace("=2g", "=two gigs")), "cannot read", "an unreadable size is refused")
    refused(run(example=None), "env.prod.example is missing", "a missing example is refused")
    refused(run(remote=None), "compose.remote-db.yaml is missing", "a missing compose file is refused")
    refused(run(prod=PROD.replace("    deploy:\n      resources:\n        limits:\n"
                                  "          memory: ${APP_MEMORY_LIMIT:-2g}\n", "")),
            "no resolved memory limit", "a stack with no app limit is refused")
    refused(run(prod=PROD.replace("services:", "services: [")), "docker compose config failed",
            "a compose file that does not render is refused")
    empty = tempfile.mkdtemp(prefix="no-docker-")
    try:
        refused(run(path=empty), "cannot run docker compose", "no docker on PATH is a failure, not a skip")
    finally:
        shutil.rmtree(empty, ignore_errors=True)

    # The real files.
    env = dict(os.environ)
    env.pop("APP_MEMORY_LIMIT", None)
    proc = subprocess.run([sys.executable, str(SCRIPT), "--root", str(REPO)],
                          capture_output=True, text=True, timeout=120, env=env)
    check(proc.returncode == 0, f"the repository's deploy/ passes (exit={proc.returncode}, err={proc.stderr[:200]!r})")

    passed = sum(1 for ok, _ in CASES if ok)
    print(f"\n{passed}/{len(CASES)} app memory-limit cases passed.")
    return 0 if passed == len(CASES) else 1


if __name__ == "__main__":
    sys.exit(main())

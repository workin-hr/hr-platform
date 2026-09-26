#!/usr/bin/env python3
"""The app container's memory limit must be the same on every production path.

D-295 sets it in three places: the `${APP_MEMORY_LIMIT:-...}` default in
deploy/compose.prod.yaml, the same default in deploy/compose.remote-db.yaml,
and the `APP_MEMORY_LIMIT=` line in deploy/env.prod.example that a new
`.env.prod` is copied from. The heap is 75% of that limit (the Dockerfile's
MaxRAMPercentage), so a one-file edit that makes them disagree gives one
deployment path a different heap with every other gate green.

Each stack is RENDERED with `docker compose config`, not read as text: the
limit that matters is the one compose resolves, and a text match cannot see an
overlay, an anchor or a hard-coded value that ignores the variable. Twice per
file:

  - with APP_MEMORY_LIMIT unset, the app service's limit must equal the
    example's value;
  - with APP_MEMORY_LIMIT set to a probe value, it must equal the probe, so the
    variable is actually what decides it.

Anything this cannot resolve -- no docker compose, a render that fails, no app
limit, an example line it cannot read -- is a failure, never a skip.

Usage: check_app_memory_limit.py [--root DIR]   (DIR holds deploy/; default: the repo)
"""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
from pathlib import Path

VARIABLE = "APP_MEMORY_LIMIT"
COMPOSE_FILES = ("compose.prod.yaml", "compose.remote-db.yaml")
EXAMPLE = "env.prod.example"
OTHER_EXAMPLES = ("env.remote-db.example",)
PROBE = "3g"

_UNITS = {"": 1, "b": 1, "k": 1024, "kb": 1024, "m": 1024**2, "mb": 1024**2, "g": 1024**3, "gb": 1024**3}
# A variable with no default: compose refuses to render without it.
_REQUIRED = re.compile(r"\$\{([A-Za-z_][A-Za-z0-9_]*)(?=\}|:?\?)")


class Refused(Exception):
    pass


def to_bytes(value: str) -> int:
    match = re.fullmatch(r"(\d+)([a-z]*)", value.strip().lower())
    if not match or match.group(2) not in _UNITS:
        raise Refused(f"cannot read {value!r} as a memory size")
    return int(match.group(1)) * _UNITS[match.group(2)]


def example_value(path: Path, required: bool) -> str | None:
    if not path.is_file():
        raise Refused(f"{path} is missing")
    values = []
    for line in path.read_text(encoding="utf-8").splitlines():
        stripped = line.strip()
        if stripped.startswith("#") or "=" not in stripped:
            continue
        key, _, value = stripped.partition("=")
        if key.strip() == VARIABLE:
            values.append(value.strip())
    if len(values) > 1:
        raise Refused(f"{path} sets {VARIABLE} {len(values)} times")
    if not values:
        if required:
            raise Refused(f"{path} does not set {VARIABLE}")
        return None
    return values[0]


def rendered_limit(compose: Path, memory: str | None) -> int:
    if not compose.is_file():
        raise Refused(f"{compose} is missing")
    env = {key: os.environ[key] for key in ("PATH", "HOME", "DOCKER_CONFIG", "DOCKER_HOST") if key in os.environ}
    for name in set(_REQUIRED.findall(compose.read_text(encoding="utf-8"))) - {VARIABLE}:
        env[name] = "placeholder"
    if memory is not None:
        env[VARIABLE] = memory
    # --env-file /dev/null: a developer's deploy/.env must not decide the answer.
    command = ["docker", "compose", "--env-file", os.devnull, "-f", str(compose), "config", "--format", "json"]
    try:
        result = subprocess.run(command, env=env, capture_output=True, text=True, timeout=60, check=False)
    except (OSError, subprocess.TimeoutExpired) as ex:
        raise Refused(f"cannot run docker compose: {ex}") from ex
    if result.returncode != 0:
        raise Refused(f"docker compose config failed for {compose.name}: {result.stderr.strip()[:300]}")
    try:
        limit = json.loads(result.stdout)["services"]["app"]["deploy"]["resources"]["limits"]["memory"]
    except (ValueError, KeyError, TypeError) as ex:
        raise Refused(f"{compose.name}: the app service has no resolved memory limit") from ex
    return to_bytes(str(limit))


def check(root: Path) -> list[str]:
    deploy = root / "deploy"
    errors = []
    try:
        expected_text = example_value(deploy / EXAMPLE, required=True)
        expected = to_bytes(expected_text)
    except Refused as ex:
        return [str(ex)]
    for name in OTHER_EXAMPLES:
        try:
            other = example_value(deploy / name, required=False)
            if other is not None and to_bytes(other) != expected:
                errors.append(f"{name} sets {VARIABLE}={other}, but {EXAMPLE} sets {expected_text}")
        except Refused as ex:
            errors.append(str(ex))
    for name in COMPOSE_FILES:
        try:
            default = rendered_limit(deploy / name, None)
            if default != expected:
                errors.append(f"{name}: with {VARIABLE} unset the app limit is {default} bytes, "
                              f"but {EXAMPLE} sets {expected_text} ({expected} bytes)")
            probed = rendered_limit(deploy / name, PROBE)
            if probed != to_bytes(PROBE):
                errors.append(f"{name}: with {VARIABLE}={PROBE} the app limit is {probed} bytes; "
                              f"the variable does not decide it")
        except Refused as ex:
            errors.append(str(ex))
    return errors


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parent.parent)
    args = parser.parse_args()
    errors = check(args.root)
    if errors:
        for error in errors:
            print(f"FAIL {error}", file=sys.stderr)
        return 1
    value = example_value(args.root / "deploy" / EXAMPLE, required=True)
    print(f"{VARIABLE}: {', '.join(COMPOSE_FILES)} default to {value}, as {EXAMPLE} sets it, "
          f"and both follow an explicit value.")
    return 0


if __name__ == "__main__":
    sys.exit(main())

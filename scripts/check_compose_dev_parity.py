#!/usr/bin/env python3
"""deploy/compose.dev.yaml must differ from compose.local.yaml in two ways only.

compose.dev.yaml is a copy of compose.local.yaml that PULLS the backend image
instead of building it. Everything else -- database image, health check,
credentials, volumes, ports, restart policy, dependency conditions -- has to
stay identical, because a client developer running the dev stack is supposed to
be running the same thing a backend developer runs locally.

Prose said so; nothing checked it. The first operational fix to the local stack
would silently leave client developers on different behaviour, and that had
already happened: compose.dev.yaml's reseed instructions still named
compose.local.yaml, whose project is `workin-local`, so following them
destroyed the wrong stack's volume.

The comparison ignores comments and blank lines -- the two files explain
themselves differently on purpose -- and permits exactly the differences listed
in ALLOWED. Any other divergence fails, in either direction.
"""
from __future__ import annotations

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
LOCAL = "deploy/compose.local.yaml"
DEV = "deploy/compose.dev.yaml"

# (local lines, dev lines) that this pair is allowed to differ by, in order.
# Each entry is the exact normalised text on each side; `()` means "absent".
ALLOWED: tuple[tuple[tuple[str, ...], tuple[str, ...]], ...] = (
    # The compose project name. They must differ: sharing it would make the two
    # stacks fight over the same containers and volumes.
    (("name: workin-local",), ("name: workin-dev",)),
    # The one intended behavioural difference: build locally vs pull published.
    # Indentation included, because the comparison keeps it.
    (
        ("    build:", "      context: ..", "      dockerfile: deploy/Dockerfile"),
        ("    image: ghcr.io/workin-hr/hr-platform/backend:${BACKEND_TAG:-main}",),
    ),
)


def normalise(path: Path) -> list[str]:
    """Significant lines only, INDENTATION INTACT.

    Comments and blank lines carry no behaviour, so they are dropped. Leading
    whitespace is not: in YAML it is the nesting. Stripping it made the
    comparison blind to structural drift -- moving `restart: unless-stopped`
    from under `app` to under `services` keeps the same text in the same order
    and would have compared equal, while producing a different stack.
    """
    out = []
    for raw in path.read_text(encoding="utf-8").splitlines():
        if not raw.strip() or raw.strip().startswith("#"):
            continue
        out.append(raw.rstrip())
    return out


def main() -> int:
    import difflib

    local_path, dev_path = ROOT / LOCAL, ROOT / DEV
    for path in (local_path, dev_path):
        if not path.is_file():
            print(f"FAIL: {path.relative_to(ROOT)} is missing", file=sys.stderr)
            return 1

    local, dev = normalise(local_path), normalise(dev_path)
    remaining = list(ALLOWED)
    problems: list[str] = []

    matcher = difflib.SequenceMatcher(a=local, b=dev, autojunk=False)
    for tag, i1, i2, j1, j2 in matcher.get_opcodes():
        if tag == "equal":
            continue
        change = (tuple(local[i1:i2]), tuple(dev[j1:j2]))
        if change in remaining:
            remaining.remove(change)
            continue
        problems.append(
            f"  unexpected difference\n"
            f"    {LOCAL}: {list(change[0]) or '(absent)'}\n"
            f"    {DEV}:   {list(change[1]) or '(absent)'}"
        )

    for missing in remaining:
        problems.append(
            f"  an intended difference is GONE -- the two stacks converged where\n"
            f"  they must not, or the checker is now describing something stale:\n"
            f"    {LOCAL}: {list(missing[0]) or '(absent)'}\n"
            f"    {DEV}:   {list(missing[1]) or '(absent)'}"
        )

    if problems:
        print(f"FAIL: {DEV} and {LOCAL} have drifted.\n", file=sys.stderr)
        print("\n".join(problems), file=sys.stderr)
        print(
            f"\nEvery setting but the project name and build-vs-image must match, or a\n"
            f"client developer is running something a backend developer never sees.\n"
            f"Fix the copy, or -- if the difference is deliberate -- add it to ALLOWED\n"
            f"in scripts/{Path(__file__).name} with a reason.",
            file=sys.stderr,
        )
        return 1

    print(f"{DEV} matches {LOCAL} except the {len(ALLOWED)} intended differences.")
    return 0


if __name__ == "__main__":
    sys.exit(main())

#!/usr/bin/env python3
"""Drift detector for spike/parity-harness/client-endpoints.txt.

Every sweep in the harness treats that file as the complete client-addressable
surface. If a client adds, renames or removes an endpoint constant, the sweeps
keep iterating the same lines and report full coverage while silently omitting
the changed route -- a coverage claim that cannot notice it is stale.

The authoritative source is each client's `api_constants.dart`. Those are git
submodules and are not always checked out (CI does not initialise them), which
is the same constraint check_legacy_modules_drift.py handles: the real
comparison needs the source, `--self-test` needs nothing.

    python3 scripts/check_client_endpoints_drift.py --clients flutter-integration
    python3 scripts/check_client_endpoints_drift.py --clients flutter-integration --write
    python3 scripts/check_client_endpoints_drift.py --self-test
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
VENDORED = ROOT / "spike/parity-harness/client-endpoints.txt"

# `static const String fooEndpoint = 'auth/login_employee';`
CONST = re.compile(r"""=\s*(['"])([A-Za-z0-9_]+(?:/[A-Za-z0-9_]+)+)\1""")


def strip_comments(dart: str) -> str:
    """Removes `//` and `/* */` comments, preserving string literals.

    A client retires a constant by commenting it out. Reading raw source kept
    those as live endpoints, so the drift check accepted a stale vendored route
    and every sweep kept probing it -- coverage counted against a URL no client
    calls any more.
    """
    out, i, n = [], 0, len(dart)
    while i < n:
        c = dart[i]
        if c in "\"'":
            quote, j = c, i + 1
            while j < n:
                if dart[j] == "\\":
                    j += 2
                    continue
                if dart[j] == quote:
                    j += 1
                    break
                j += 1
            out.append(dart[i:j]); i = j
        elif dart.startswith("//", i):
            j = dart.find("\n", i)
            i = n if j < 0 else j
        elif dart.startswith("/*", i):
            j = dart.find("*/", i + 2)
            i = n if j < 0 else j + 2
        else:
            out.append(c); i += 1
    return "".join(out)


def extract(dart: str) -> set[str]:
    """Endpoint-shaped constant values: at least one `/`, no scheme, no leading slash."""
    dart = strip_comments(dart)
    out = set()
    for _, value in CONST.findall(dart):
        if value.startswith("/") or "://" in value:
            continue
        out.add(value)
    return out


def extract_all(clients_dir: Path) -> set[str]:
    found = set()
    files = list(clients_dir.rglob("api_constants.dart"))
    if not files:
        raise FileNotFoundError(
            f"no api_constants.dart under {clients_dir} -- submodules not initialised?")
    for f in files:
        found |= extract(f.read_text(encoding="utf-8", errors="ignore"))
    return found


def read_vendored() -> set[str]:
    return {ln.strip() for ln in VENDORED.read_text(encoding="utf-8").splitlines()
            if ln.strip() and not ln.startswith("#")}


def report(actual: set[str], expected: set[str]) -> int:
    if actual == expected:
        print(f"pass: {len(expected)} endpoints, list matches the client constants")
        return 0
    only_client = sorted(expected - actual)
    only_list = sorted(actual - expected)
    print("DRIFT between client-endpoints.txt and the client constants", file=sys.stderr)
    if only_client:
        print(f"  declared by a client, missing from the list ({len(only_client)}): "
              f"{only_client[:10]}", file=sys.stderr)
    if only_list:
        print(f"  in the list, no longer declared ({len(only_list)}): "
              f"{only_list[:10]}", file=sys.stderr)
    return 1


def self_test() -> int:
    dart = """
      class ApiConstants {
        static const String base = 'https://workin.company/apis/api/';
        static const String loginEndpoint = 'auth/login_employee';
        static const String listEndpoint  = "employees/list";
        static const String slashy        = '/leading/slash';
        static const String notAnEndpoint = 'plainword';
      }"""
    got = extract(dart)
    assert got == {"auth/login_employee", "employees/list"}, got
    assert report(got, got) == 0
    assert report(got, got | {"extra/one"}) == 1, "a new client constant must fail"
    assert report(got | {"stale/one"}, got) == 1, "a removed constant must fail"
    if VENDORED.is_file():
        v = read_vendored()
        assert len(v) > 100, f"vendored list looks truncated: {len(v)}"
        assert all("/" in e for e in v), "every entry must be module/action"
    print("self-test passed")
    return 0


def main() -> int:
    argv = sys.argv[1:]
    if "--clients" not in argv:
        print("pass --clients PATH, or --self-test which needs no checkout", file=sys.stderr)
        return 2
    clients = Path(argv[argv.index("--clients") + 1]).resolve()
    try:
        expected = extract_all(clients)
    except FileNotFoundError as ex:
        print(ex, file=sys.stderr)
        return 2
    if "--write" in argv:
        head = "\n".join(l for l in VENDORED.read_text(encoding="utf-8").splitlines()
                         if l.startswith("#")) if VENDORED.is_file() else ""
        VENDORED.write_text((head + "\n" if head else "") + "\n".join(sorted(expected)) + "\n",
                            encoding="utf-8")
        print(f"wrote {len(expected)} endpoints to {VENDORED.relative_to(ROOT)}")
        return 0
    return report(read_vendored(), expected)


if __name__ == "__main__":
    sys.exit(self_test() if "--self-test" in sys.argv else main())

#!/usr/bin/env python3
"""Fail when hr-legacy serves a /apis/** route this application does not.

The Java side already has a bidirectional inventory
(LegacyPhpRouteInventoryTest): every mapped route is in a literal list, and
every entry in the list is mapped. That guard compares Java against Java.
Nothing compared either of them against the PHP the port exists to
reproduce, so when hr-legacy grew three endpoints -- guide_videos/list,
employees/update_bulk, employees/analyze_excel_update -- every check stayed
green and the clients calling them would have got a 404 from Java.

This closes that direction: the PHP tree on disk is the source, the Java
inventory is the copy, and a route present in the first and absent from the
second fails the build.

Deliberately one-directional. A route Java serves that PHP does not is a
different problem with a different owner (D-074's test-scoped shims live in
src/test and never reach this list), and folding both into one check would
make a legitimate Java-only route look like legacy drift.

Three-way, so it works with or without a sibling hr-legacy checkout:

    committed inventory  contracts/legacy-php-routes.txt
    Java inventory       LegacyPhpRouteInventoryTest's literal list
    hr-legacy on disk    apis/api/**/*.php, when present

CI has no hr-legacy beside it -- it is a separate repository, not a submodule
-- so the gate CI *can* enforce is Java against the committed inventory. That
is the same arrangement check_client_contract_drift.py uses for the client
submodules, and it is what makes this a permanent gate rather than one that
only runs on a developer machine.

When hr-legacy *is* present, the committed inventory is checked against it
too, so a stale inventory fails locally before it can hide a new route from
CI.

Usage:
    python3 scripts/check_legacy_route_drift.py            # check
    python3 scripts/check_legacy_route_drift.py --refresh  # rewrite the inventory from hr-legacy
    python3 scripts/check_legacy_route_drift.py --list     # print PHP's routes
"""
from __future__ import annotations

import argparse
import os
import re
import sys

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
LEGACY_API = os.path.join(REPO_ROOT, "..", "hr-legacy", "apis", "api")
INVENTORY = os.path.join(
    REPO_ROOT, "backend", "src", "test", "java", "com", "workin", "legacy",
    "LegacyPhpRouteInventoryTest.java",
)
COMMITTED = os.path.join(REPO_ROOT, "contracts", "legacy-php-routes.txt")

COMMITTED_HEADER = """\
# Every /apis/** route hr-legacy serves, one per line, sorted.
#
# GENERATED -- refresh with:
#   python3 scripts/check_legacy_route_drift.py --refresh
#
# Committed so the drift gate can run where hr-legacy is not checked out,
# which includes CI. Without it the check would silently pass there, and a
# route added to PHP would reach a client as a 404 from Java with every
# check green.
"""

ROUTE_IN_INVENTORY = re.compile(r'"(/apis/api/[a-z0-9_]+/[a-z0-9_]+\.php)"')

# Endpoint files that exist but serve nothing a client can reach. Each needs a
# reason, so the list cannot quietly become a place to hide unported routes.
EXEMPT: dict[str, str] = {}


def php_routes(api_dir: str) -> set[str]:
    """Every `<resource>/<action>.php` under hr-legacy's apis/api."""
    found = set()
    if not os.path.isdir(api_dir):
        return found
    for resource in sorted(os.listdir(api_dir)):
        resource_dir = os.path.join(api_dir, resource)
        if not os.path.isdir(resource_dir):
            continue
        for entry in sorted(os.listdir(resource_dir)):
            if entry.endswith(".php"):
                found.add(f"/apis/api/{resource}/{entry}")
    return found


def java_routes(inventory_path: str) -> set[str]:
    with open(inventory_path, encoding="utf-8") as handle:
        return set(ROUTE_IN_INVENTORY.findall(handle.read()))


def committed_routes(path: str) -> set[str]:
    if not os.path.exists(path):
        return set()
    with open(path, encoding="utf-8") as handle:
        return {
            line.strip() for line in handle
            if line.strip() and not line.startswith("#")
        }


def write_committed(path: str, routes: set[str]) -> None:
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as handle:
        handle.write(COMMITTED_HEADER)
        for route in sorted(routes):
            handle.write(route + "\n")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--legacy-api", default=LEGACY_API)
    parser.add_argument("--inventory", default=INVENTORY)
    parser.add_argument("--committed", default=COMMITTED)
    parser.add_argument("--list", action="store_true", help="print the PHP routes and exit")
    parser.add_argument("--refresh", action="store_true",
                        help="rewrite the committed inventory from hr-legacy")
    args = parser.parse_args()

    php = php_routes(args.legacy_api)

    if args.list:
        for route in sorted(php):
            print(route)
        return 0

    if args.refresh:
        if not php:
            print(f"FATAL: no PHP routes under {args.legacy_api} -- nothing to refresh from.",
                  file=sys.stderr)
            return 2
        write_committed(args.committed, php)
        print(f"wrote {len(php)} routes to {args.committed}")
        return 0

    committed = committed_routes(args.committed)
    if not committed:
        print(f"FATAL: {args.committed} is missing or empty. Run --refresh beside an "
              "hr-legacy checkout; without it this gate proves nothing.", file=sys.stderr)
        return 2

    java = java_routes(args.inventory)
    status = 0

    # The half CI can run: Java against the committed inventory.
    missing = sorted(committed - java - set(EXEMPT))
    print(f"committed legacy routes: {len(committed)}   java inventory: {len(java)}")
    if missing:
        print(f"\nFAIL: {len(missing)} route(s) hr-legacy serves and this application does not:",
              file=sys.stderr)
        for route in missing:
            print(f"  {route}", file=sys.stderr)
        print("\nPort them, or add each to EXEMPT with the reason it is unreachable.",
              file=sys.stderr)
        status = 1

    # And, when hr-legacy is beside us, that the committed inventory is current.
    if php:
        added = sorted(php - committed)
        removed = sorted(committed - php)
        if added or removed:
            print("\nFAIL: the committed inventory is stale against hr-legacy.", file=sys.stderr)
            for route in added:
                print(f"  + {route} (in hr-legacy, not committed)", file=sys.stderr)
            for route in removed:
                print(f"  - {route} (committed, not in hr-legacy)", file=sys.stderr)
            print("\nRefresh it: python3 scripts/check_legacy_route_drift.py --refresh",
                  file=sys.stderr)
            status = 1
        else:
            print(f"hr-legacy present: its {len(php)} routes match the committed inventory.")
    else:
        print("hr-legacy not checked out: comparing against the committed inventory only.")

    if status == 0:
        print("OK: every legacy route is in the Java inventory.")
    return status


if __name__ == "__main__":
    raise SystemExit(main())

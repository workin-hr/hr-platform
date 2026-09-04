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

Usage:
    python3 scripts/check_legacy_route_drift.py            # check
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


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--legacy-api", default=LEGACY_API)
    parser.add_argument("--inventory", default=INVENTORY)
    parser.add_argument("--list", action="store_true", help="print PHP's routes and exit")
    args = parser.parse_args()

    php = php_routes(args.legacy_api)
    if args.list:
        for route in sorted(php):
            print(route)
        return 0

    if not php:
        print(
            f"FATAL: no PHP routes found under {args.legacy_api} -- without a sibling "
            "hr-legacy checkout this check proves nothing, so it fails rather than passes.",
            file=sys.stderr,
        )
        return 2

    java = java_routes(args.inventory)
    missing = sorted(php - java - set(EXEMPT))

    print(f"legacy routes: {len(php)}   java inventory: {len(java)}")
    if missing:
        print(f"\nFAIL: {len(missing)} route(s) hr-legacy serves and this application does not:", file=sys.stderr)
        for route in missing:
            print(f"  {route}", file=sys.stderr)
        print(
            "\nPort them, or add each to EXEMPT with the reason it is unreachable.",
            file=sys.stderr,
        )
        return 1

    print("OK: every legacy route is in the Java inventory.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

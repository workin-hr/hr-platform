#!/usr/bin/env python3
"""Keep the OpenAPI method inventory honest against the controllers.

The legacy surface maps every route with a bare `@RequestMapping(path)` and no
`method =`, because the port reproduces PHP's own 405: the handler checks the
method itself and answers `invalid_method` in the legacy envelope. Adding
`method = POST` to the annotation would make Spring answer 405 first, in its
own shape, and that is a client-visible change (D-111).

The cost is that springdoc sees a mapping with no method restriction and
documents EVERY verb. Left alone, the published spec says
`GET /apis/api/auth/login_company.php` is valid. It is not -- it answers 405 --
and a specification that lies is worse than no specification.

So the true method is extracted from the handler bodies into
`backend/src/main/resources/legacy/route-methods.txt`, which `OpenApiConfig`
reads to prune the generated document. This script generates that file and, in
its default mode, fails when it no longer matches the sources.

Four guard idioms exist in the controllers, all of them greppable:

    requireMethod(request, "POST");                        -> POST
    if (!"GET".equals(request.getMethod())) { throw ... }  -> GET
    requireGet(request);                                   -> GET
    if ("GET".equals(...)) ... if ("PUT".equals(...)) ...  -> GET, PUT

A handler with none of them accepts any method, which is also faithful:
`employees/template_excel.php` has no method check in PHP either. Those routes
are recorded as `ANY` and keep every verb in the spec.

Usage:
    python3 scripts/check_openapi_route_methods_drift.py            # check
    python3 scripts/check_openapi_route_methods_drift.py --refresh  # rewrite
"""
from __future__ import annotations

import argparse
import os
import re
import sys

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
LEGACY_SOURCES = os.path.join(REPO_ROOT, "backend", "src", "main", "java", "com", "workin", "legacy")
INVENTORY = os.path.join(REPO_ROOT, "contracts", "legacy-php-routes.txt")
OUTPUT = os.path.join(
    REPO_ROOT, "backend", "src", "main", "resources", "legacy", "route-methods.txt"
)

CLASS_MAPPING = re.compile(
    r'@RequestMapping\s*\(\s*"([^"]*)"\s*\)\s*(?:@\w+[^\n]*\s*)*public\s+(?:final\s+)?class'
)
METHOD_MAPPING = re.compile(
    r'@(?:Request|Get|Post|Put|Delete|Patch)Mapping\s*\(\s*(\{[^}]*\}|"[^"]*")\s*\)'
)

# Order matters. A negative test states THE method; a positive test states one
# of possibly several, so it is collected only when no definitive guard is
# present.
DEFINITIVE_GUARDS = (
    re.compile(r'requireMethod\(\s*request\s*,\s*"([A-Z]+)"'),
    re.compile(r'!\s*"([A-Z]+)"\.equals\(\s*request\.getMethod\(\)\s*\)'),
    re.compile(r'require(Get|Post|Put|Delete|Patch)\(\s*request\s*\)'),
)
BRANCH_GUARD = re.compile(r'(?<!!)"([A-Z]+)"\.equals\(\s*request\.getMethod\(\)\s*\)')

ANY = "ANY"

HEADER = """# The HTTP method each legacy route actually accepts, one route per line.
#
# GENERATED -- refresh with:
#   python3 scripts/check_openapi_route_methods_drift.py --refresh
#
# Spring maps these routes with no method restriction, on purpose: the handler
# answers PHP's own 405 `invalid_method` in the legacy envelope, and letting
# Spring answer first would change what a client sees (D-111). springdoc
# therefore documents every verb unless told otherwise, and OpenApiConfig uses
# this file to tell it otherwise.
#
# ANY means the handler has no method check, which is faithful -- those routes
# have none in PHP either.
"""


def fail(message: str, failures: list[str]) -> None:
    failures.append(message)


def extract_routes() -> dict[str, list[str]]:
    routes: dict[str, list[str]] = {}
    for directory, _, filenames in os.walk(LEGACY_SOURCES):
        for filename in sorted(filenames):
            if not filename.endswith(".java"):
                continue
            with open(os.path.join(directory, filename), encoding="utf-8") as handle:
                source = handle.read()

            class_match = CLASS_MAPPING.search(source)
            prefix = class_match.group(1) if class_match else ""
            start = class_match.end() if class_match else 0

            hits = [
                (m.start(), m.end(), m.group(1))
                for m in METHOD_MAPPING.finditer(source, start)
            ]
            for index, (_, annotation_end, raw_paths) in enumerate(hits):
                body_end = hits[index + 1][0] if index + 1 < len(hits) else len(source)
                body = source[annotation_end:body_end]

                methods: list[str] = []
                for guard in DEFINITIVE_GUARDS:
                    found = guard.search(body)
                    if found:
                        methods = [found.group(1).upper()]
                        break
                if not methods:
                    methods = sorted(set(BRANCH_GUARD.findall(body)))

                for path in re.findall(r'"([^"]*)"', raw_paths):
                    full = path if path.startswith("/apis") else prefix + path
                    if full.startswith("/apis"):
                        routes[full] = methods or [ANY]
    return routes


def render(routes: dict[str, list[str]]) -> str:
    lines = [HEADER]
    for path in sorted(routes):
        lines.append(f"{path} {','.join(routes[path])}")
    return "\n".join(lines) + "\n"


def committed_inventory() -> list[str]:
    if not os.path.isfile(INVENTORY):
        return []
    with open(INVENTORY, encoding="utf-8") as handle:
        return [line.strip() for line in handle if line.startswith("/")]


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--refresh", action="store_true")
    arguments = parser.parse_args()

    if not os.path.isdir(LEGACY_SOURCES):
        print(f"missing {LEGACY_SOURCES}", file=sys.stderr)
        return 2

    routes = extract_routes()
    failures: list[str] = []

    # Cross-check against the route inventory. The two are derived from
    # different things -- that one from hr-legacy's PHP files, this one from the
    # Java controllers -- so agreement is evidence and disagreement is a real
    # finding rather than a formatting difference.
    inventory = committed_inventory()
    if inventory:
        extracted = set(routes)
        for path in inventory:
            if path not in extracted:
                fail(
                    f"{path} is in contracts/legacy-php-routes.txt but no controller mapping "
                    f"was found for it; the extractor or the port is out of date",
                    failures,
                )
        for path in sorted(extracted):
            if path not in inventory:
                fail(
                    f"{path} is mapped by a controller but absent from "
                    f"contracts/legacy-php-routes.txt",
                    failures,
                )

    rendered = render(routes)

    if arguments.refresh:
        os.makedirs(os.path.dirname(OUTPUT), exist_ok=True)
        with open(OUTPUT, "w", encoding="utf-8") as handle:
            handle.write(rendered)
        specific = sum(1 for m in routes.values() if m != [ANY])
        print(
            f"wrote {os.path.relpath(OUTPUT, REPO_ROOT)}: {len(routes)} routes, "
            f"{specific} with a specific method, {len(routes) - specific} ANY"
        )
        if failures:
            for failure in failures:
                print(f"FAIL: {failure}", file=sys.stderr)
            return 1
        return 0

    if not os.path.isfile(OUTPUT):
        print(
            f"missing {os.path.relpath(OUTPUT, REPO_ROOT)}; generate it with --refresh",
            file=sys.stderr,
        )
        return 2

    with open(OUTPUT, encoding="utf-8") as handle:
        current = handle.read()
    if current != rendered:
        fail(
            f"{os.path.relpath(OUTPUT, REPO_ROOT)} is stale: a controller's method guard "
            f"changed and the published OpenAPI document would still show the old verb. "
            f"Refresh it with --refresh",
            failures,
        )

    if failures:
        for failure in failures:
            print(f"FAIL: {failure}", file=sys.stderr)
        return 1

    specific = sum(1 for m in routes.values() if m != [ANY])
    print(
        f"OpenAPI route methods are current: {len(routes)} routes, "
        f"{specific} with a specific method, {len(routes) - specific} ANY."
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

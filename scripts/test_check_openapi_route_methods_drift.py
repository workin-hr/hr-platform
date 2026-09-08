#!/usr/bin/env python3
"""Fixture-based regression tests for check_openapi_route_methods_drift.py.

The gate's output is read by OpenApiConfig and becomes the published API
description: if the extractor misreads a handler's method guard, the document
tells a client developer that a route accepts a verb it answers 405 to. So
these cases are mostly about the extractor being *wrong* in ways that would
still look plausible -- a guard leaking across a method boundary, a negative
test read as a positive one, a class prefix applied twice.

    python3 scripts/test_check_openapi_route_methods_drift.py
"""
from __future__ import annotations

import importlib.util
import os
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))

spec = importlib.util.spec_from_file_location(
    "check_openapi_route_methods_drift",
    os.path.join(HERE, "check_openapi_route_methods_drift.py"),
)
gate = importlib.util.module_from_spec(spec)
assert spec.loader is not None
spec.loader.exec_module(gate)

FAILURES: list[str] = []


def check(condition: bool, message: str) -> None:
    print(("OK  " if condition else "FAIL ") + message)
    if not condition:
        FAILURES.append(message)


def extract(source: str, filename: str = "Fixture.java") -> dict[str, list[str]]:
    """Run the real extractor over one synthetic controller."""
    with tempfile.TemporaryDirectory() as directory:
        with open(os.path.join(directory, filename), "w", encoding="utf-8") as handle:
            handle.write(source)
        original = gate.LEGACY_SOURCES
        gate.LEGACY_SOURCES = directory
        try:
            return gate.extract_routes()
        finally:
            gate.LEGACY_SOURCES = original


CLASS_HEADER = """package com.workin.legacy.fixture;

@RestController
@RequestMapping("/apis/api/fixture")
public class FixtureController {
"""


def test_each_guard_idiom_is_read() -> None:
    routes = extract(CLASS_HEADER + """
    @PostMapping("/explicit")
    public Object explicit(HttpServletRequest request) {
        requireMethod(request, "POST");
        return null;
    }

    @RequestMapping("/negated")
    public Object negated(HttpServletRequest request) {
        if (!"GET".equals(request.getMethod())) {
            throw invalidMethod();
        }
        return null;
    }

    @RequestMapping("/helper")
    public Object helper(HttpServletRequest request) {
        requireGet(request);
        return null;
    }

    @RequestMapping("/branching")
    public Object branching(HttpServletRequest request) {
        if ("GET".equals(request.getMethod())) {
            return read();
        }
        if ("PUT".equals(request.getMethod())) {
            return write();
        }
        throw invalidMethod();
    }
}
""")
    check(routes.get("/apis/api/fixture/explicit") == ["POST"],
          "requireMethod(request, \"POST\") reads as POST")
    check(routes.get("/apis/api/fixture/negated") == ["GET"],
          "a negated equals() guard reads as the method it requires, not one it rejects")
    check(routes.get("/apis/api/fixture/helper") == ["GET"],
          "requireGet(request) reads as GET")
    check(routes.get("/apis/api/fixture/branching") == ["GET", "PUT"],
          "a two-branch handler reads as both verbs")


def test_an_unguarded_handler_is_ANY_not_a_neighbours_verb() -> None:
    """The falsification that matters most.

    Bodies are delimited by the next mapping annotation. If that bound were
    wrong, the POST guard below would be found while scanning the handler above
    it, and an unguarded route -- which really does accept anything, exactly as
    PHP does -- would be published as POST-only."""
    routes = extract(CLASS_HEADER + """
    @RequestMapping("/template_excel")
    public void template(HttpServletResponse response) {
        stream(response);
    }

    @PostMapping("/guarded")
    public Object guarded(HttpServletRequest request) {
        requireMethod(request, "POST");
        return null;
    }
}
""")
    check(routes.get("/apis/api/fixture/template_excel") == [gate.ANY],
          "an unguarded handler is ANY and does not inherit the next handler's guard")
    check(routes.get("/apis/api/fixture/guarded") == ["POST"],
          "the following handler still reads its own guard")


def test_a_definitive_guard_beats_a_branch_mention() -> None:
    """`!"GET".equals(...)` states THE method; a bare `"POST".equals(...)`
    further down cannot widen it, because that branch is unreachable."""
    routes = extract(CLASS_HEADER + """
    @RequestMapping("/mixed")
    public Object mixed(HttpServletRequest request) {
        if (!"GET".equals(request.getMethod())) {
            throw invalidMethod();
        }
        if ("POST".equals(request.getMethod())) {
            return unreachable();
        }
        return null;
    }
}
""")
    check(routes.get("/apis/api/fixture/mixed") == ["GET"],
          "an unreachable branch does not widen a definitive guard")


def test_paths_compose_and_absolute_paths_are_not_prefixed_twice() -> None:
    routes = extract("""package com.workin.legacy.fixture;

@RestController
@RequestMapping("/apis/api/prefixed")
public class PrefixedController {

    @GetMapping("/relative")
    public Object relative(HttpServletRequest request) {
        requireGet(request);
        return null;
    }

    @RequestMapping("/apis/api/elsewhere/absolute")
    public Object absolute(HttpServletRequest request) {
        requireGet(request);
        return null;
    }

    @RequestMapping({"/first", "/second"})
    public Object twoPaths(HttpServletRequest request) {
        requireMethod(request, "DELETE");
        return null;
    }
}
""")
    check("/apis/api/prefixed/relative" in routes,
          "a relative path is composed with the class prefix")
    check("/apis/api/elsewhere/absolute" in routes
          and "/apis/api/prefixed/apis/api/elsewhere/absolute" not in routes,
          "a path that is already absolute is not prefixed a second time")
    check(routes.get("/apis/api/prefixed/first") == ["DELETE"]
          and routes.get("/apis/api/prefixed/second") == ["DELETE"],
          "a multi-path mapping records every path it answers")


def test_non_legacy_mappings_are_ignored() -> None:
    routes = extract("""package com.workin.backend.fixture;

@RestController
@RequestMapping("/api/platform-admin/things")
public class AdminController {

    @GetMapping("/list")
    public Object list() {
        return null;
    }
}
""")
    check(routes == {},
          "a controller outside /apis is not part of the client inventory")


def test_rendered_lines_are_what_the_java_loader_parses() -> None:
    """OpenApiConfig splits on the LAST space and on commas. Pin that shape
    here rather than discovering a mismatch as an unpruned published document."""
    rendered = gate.render({"/apis/api/x/y.php": ["GET", "PUT"], "/apis/api/z.php": [gate.ANY]})
    body = [line for line in rendered.splitlines() if line and not line.startswith("#")]
    check(body == ["/apis/api/x/y.php GET,PUT", "/apis/api/z.php ANY"],
          f"render() emits `<path> <verbs>` sorted by path (got {body})")
    path, _, verbs = body[0].rpartition(" ")
    check(path == "/apis/api/x/y.php" and verbs.split(",") == ["GET", "PUT"],
          "the Java loader's split-on-last-space parse recovers the path and verbs")


def test_the_committed_inventory_matches_the_committed_routes() -> None:
    """Not a fixture: the real check, so a stale route-methods.txt or a
    controller missing from contracts/legacy-php-routes.txt fails here too and
    not only in the gate's own run."""
    routes = gate.extract_routes()
    inventory = set(gate.committed_inventory())
    check(bool(inventory), "contracts/legacy-php-routes.txt is present and non-empty")
    check(set(routes) == inventory,
          f"every controller mapping is in the route inventory and vice versa "
          f"(extra={sorted(set(routes) - inventory)}, missing={sorted(inventory - set(routes))})")
    with open(gate.OUTPUT, encoding="utf-8") as handle:
        check(handle.read() == gate.render(routes),
              "backend/src/main/resources/legacy/route-methods.txt is current")


def main() -> int:
    for name, function in sorted(globals().items()):
        if name.startswith("test_") and callable(function):
            function()
    print()
    if FAILURES:
        print(f"{len(FAILURES)} FAILURE(S)")
        return 1
    print("all OpenAPI route-method gate regression cases passed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

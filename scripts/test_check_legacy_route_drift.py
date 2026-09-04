#!/usr/bin/env python3
"""Deterministic regression tests for scripts/check_legacy_route_drift.py.

Run directly: `python3 scripts/test_check_legacy_route_drift.py`.

Fixture-based and hermetic: no sibling hr-legacy checkout. Every case builds
a small PHP tree and a stub inventory in a temporary directory and drives the
real functions over them.

The case that matters most is the empty-tree one. This check runs in an
environment that may not have hr-legacy beside it, and a check that silently
passes when its input is missing is worse than no check -- it reports "no
drift" for "nothing to compare". It exits 2 instead.

Wired into: scripts/validate_phase0.py's script/test-sibling rule.
"""

from __future__ import annotations

import pathlib
import sys
import tempfile

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))

import check_legacy_route_drift as drift  # noqa: E402

FAILURES: list[str] = []


def check(name: str, condition: bool, detail: str = "") -> None:
    print(f"{'OK ' if condition else 'FAIL'} {name}" + (f" ({detail})" if detail and not condition else ""))
    if not condition:
        FAILURES.append(name)


def build_php(root: pathlib.Path, routes: list[str]) -> pathlib.Path:
    api = root / "apis" / "api"
    for route in routes:
        resource, action = route.split("/")
        (api / resource).mkdir(parents=True, exist_ok=True)
        (api / resource / action).write_text("<?php\n", encoding="utf-8")
    return api


def build_inventory(root: pathlib.Path, routes: list[str]) -> pathlib.Path:
    body = "\n".join(f'\t\t\t"/apis/api/{r}",' for r in routes)
    path = root / "Inventory.java"
    path.write_text(
        "class LegacyPhpRouteInventoryTest {\n"
        "\tprivate static final List<String> EXPECTED_ROUTES = List.of(\n"
        + body
        + "\n\t);\n}\n",
        encoding="utf-8",
    )
    return path


def run(php_routes: list[str], java_routes: list[str]) -> int:
    with tempfile.TemporaryDirectory() as tmp:
        root = pathlib.Path(tmp)
        api = build_php(root, php_routes)
        inventory = build_inventory(root, java_routes)
        argv = sys.argv
        sys.argv = ["check", "--legacy-api", str(api), "--inventory", str(inventory)]
        try:
            return drift.main()
        finally:
            sys.argv = argv


def test_matching_sets_pass() -> None:
    check("identical route sets pass", run(["a/list.php", "b/one.php"], ["a/list.php", "b/one.php"]) == 0)


def test_php_only_route_fails() -> None:
    check("a route PHP serves and Java does not fails",
          run(["a/list.php", "b/new.php"], ["a/list.php"]) == 1)


def test_java_only_route_is_not_a_failure() -> None:
    """One-directional by design -- a Java-only route has a different owner."""
    check("a route only Java has does not fail",
          run(["a/list.php"], ["a/list.php", "z/extra.php"]) == 0)


def test_missing_legacy_tree_fails_rather_than_passes() -> None:
    with tempfile.TemporaryDirectory() as tmp:
        root = pathlib.Path(tmp)
        inventory = build_inventory(root, ["a/list.php"])
        argv = sys.argv
        sys.argv = ["check", "--legacy-api", str(root / "nope"), "--inventory", str(inventory)]
        try:
            code = drift.main()
        finally:
            sys.argv = argv
    check("an absent hr-legacy checkout exits 2, not 0", code == 2, str(code))


def test_non_php_files_and_stray_dirs_are_ignored() -> None:
    with tempfile.TemporaryDirectory() as tmp:
        root = pathlib.Path(tmp)
        api = build_php(root, ["a/list.php"])
        (api / "a" / "notes.md").write_text("x", encoding="utf-8")
        (api / "a" / "nested").mkdir()
        found = drift.php_routes(str(api))
    check("only .php files count as routes", found == {"/apis/api/a/list.php"}, str(found))


def test_inventory_parsing_ignores_unrelated_strings() -> None:
    with tempfile.TemporaryDirectory() as tmp:
        path = pathlib.Path(tmp) / "Inventory.java"
        path.write_text(
            'String other = "/admin/companies";\n'
            'String route = "/apis/api/a/list.php";\n'
            'String comment = "/apis/api/b/one.php";\n',
            encoding="utf-8",
        )
        found = drift.java_routes(str(path))
    check("only /apis/api/*.php literals are read from the inventory",
          found == {"/apis/api/a/list.php", "/apis/api/b/one.php"}, str(found))


def main() -> int:
    test_matching_sets_pass()
    test_php_only_route_fails()
    test_java_only_route_is_not_a_failure()
    test_missing_legacy_tree_fails_rather_than_passes()
    test_non_php_files_and_stray_dirs_are_ignored()
    test_inventory_parsing_ignores_unrelated_strings()
    print()
    if FAILURES:
        print(f"{len(FAILURES)} FAILURE(S): {FAILURES}")
        return 1
    print("all check_legacy_route_drift cases passed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

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


def build_committed(root: pathlib.Path, routes: list[str]) -> pathlib.Path:
    path = root / "legacy-php-routes.txt"
    path.write_text("# generated\n" + "".join(f"/apis/api/{r}\n" for r in routes), encoding="utf-8")
    return path


def run(php_routes: list[str], java_routes: list[str],
        committed_routes: list[str] | None = None) -> int:
    """committed defaults to mirroring PHP, which is the state after --refresh."""
    with tempfile.TemporaryDirectory() as tmp:
        root = pathlib.Path(tmp)
        api = build_php(root, php_routes)
        inventory = build_inventory(root, java_routes)
        committed = build_committed(
            root, php_routes if committed_routes is None else committed_routes)
        argv = sys.argv
        sys.argv = ["check", "--legacy-api", str(api), "--inventory", str(inventory),
                    "--committed", str(committed)]
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


def test_missing_legacy_tree_still_checks_against_the_committed_inventory() -> None:
    """CI has no hr-legacy. The gate must still catch an unported route there."""
    with tempfile.TemporaryDirectory() as tmp:
        root = pathlib.Path(tmp)
        inventory = build_inventory(root, ["a/list.php"])
        committed = build_committed(root, ["a/list.php", "b/new.php"])
        argv = sys.argv
        sys.argv = ["check", "--legacy-api", str(root / "nope"),
                    "--inventory", str(inventory), "--committed", str(committed)]
        try:
            code = drift.main()
        finally:
            sys.argv = argv
    check("without hr-legacy, a route missing from Java still fails", code == 1, str(code))

    with tempfile.TemporaryDirectory() as tmp:
        root = pathlib.Path(tmp)
        inventory = build_inventory(root, ["a/list.php"])
        committed = build_committed(root, ["a/list.php"])
        argv = sys.argv
        sys.argv = ["check", "--legacy-api", str(root / "nope"),
                    "--inventory", str(inventory), "--committed", str(committed)]
        try:
            code = drift.main()
        finally:
            sys.argv = argv
    check("without hr-legacy, a matching inventory passes", code == 0, str(code))


def test_missing_committed_inventory_fails_rather_than_passes() -> None:
    """A gate that passes when its input is missing is worse than no gate."""
    with tempfile.TemporaryDirectory() as tmp:
        root = pathlib.Path(tmp)
        api = build_php(root, ["a/list.php"])
        inventory = build_inventory(root, ["a/list.php"])
        argv = sys.argv
        sys.argv = ["check", "--legacy-api", str(api), "--inventory", str(inventory),
                    "--committed", str(root / "absent.txt")]
        try:
            code = drift.main()
        finally:
            sys.argv = argv
    check("an absent committed inventory exits 2, not 0", code == 2, str(code))


def test_a_stale_committed_inventory_fails_when_hr_legacy_is_present() -> None:
    check("hr-legacy having a route the committed list lacks fails",
          run(["a/list.php", "b/new.php"], ["a/list.php", "b/new.php"],
              committed_routes=["a/list.php"]) == 1)
    check("the committed list having a route hr-legacy dropped fails",
          run(["a/list.php"], ["a/list.php", "b/gone.php"],
              committed_routes=["a/list.php", "b/gone.php"]) == 1)


def test_refresh_rewrites_the_committed_inventory() -> None:
    with tempfile.TemporaryDirectory() as tmp:
        root = pathlib.Path(tmp)
        api = build_php(root, ["a/list.php", "b/new.php"])
        committed = build_committed(root, ["a/list.php"])
        argv = sys.argv
        sys.argv = ["check", "--legacy-api", str(api), "--committed", str(committed), "--refresh"]
        try:
            code = drift.main()
        finally:
            sys.argv = argv
        written = drift.committed_routes(str(committed))
    check("--refresh exits 0", code == 0, str(code))
    check("--refresh writes every PHP route",
          written == {"/apis/api/a/list.php", "/apis/api/b/new.php"}, str(written))


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
    test_missing_legacy_tree_still_checks_against_the_committed_inventory()
    test_missing_committed_inventory_fails_rather_than_passes()
    test_a_stale_committed_inventory_fails_when_hr_legacy_is_present()
    test_refresh_rewrites_the_committed_inventory()
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

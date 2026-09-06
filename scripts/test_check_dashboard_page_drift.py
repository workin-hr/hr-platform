#!/usr/bin/env python3
"""Deterministic regression tests for scripts/check_dashboard_page_drift.py.

Run directly: `python3 scripts/test_check_dashboard_page_drift.py`.

Fixture-based and hermetic: no sibling hr-legacy checkout. Each case builds a
small dashboard tree and a stub manifest in a temporary directory and drives
the real functions over them.

Three cases carry the weight. A directory under pages/ with no page.php is not
a route -- three of the real ones are asset directories, and counting
directories invents pages that do not exist. index.php is the home page and
lives outside pages/. And a rewrite in .htaccess makes a detail.php reachable
under a name of its own, which is how employee_detail and company_detail are
served.

The fourth is the one the sibling script family exists for: an empty manifest
exits 2 rather than reporting no drift, because a gate that passes when its
baseline is missing is worse than no gate.

Wired into: scripts/validate_phase0.py's script/test-sibling rule.
"""

from __future__ import annotations

import importlib.util
import io
import pathlib
import subprocess
import sys
import tempfile
from contextlib import redirect_stderr, redirect_stdout

MODULE = pathlib.Path(__file__).with_name("check_dashboard_page_drift.py")


def load(dashboard: pathlib.Path, committed: pathlib.Path):
    spec = importlib.util.spec_from_file_location("dashboard_page_drift", MODULE)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    module.DASHBOARD = str(dashboard)
    module.COMMITTED = str(committed)
    return module


def build(root: pathlib.Path, pages, index=True, htaccess=None, assets_only=(),
          uncommitted=()):
    """A fixture hr-legacy whose pages live in a commit.

    The script reads HEAD rather than the filesystem (R-063), so the fixture
    is a real repository -- which also lets these tests cover the case that
    caused the risk: a page directory present on disk and in no commit.
    """
    repo = root / "hr-legacy"
    dashboard = repo / "dashboard"
    (dashboard / "pages").mkdir(parents=True)
    if index:
        (dashboard / "index.php").write_text("<?php\n", encoding="utf-8")
    for name in pages:
        directory = dashboard / "pages" / name
        directory.mkdir()
        (directory / "page.php").write_text("<?php\n", encoding="utf-8")
    for name in assets_only:
        directory = dashboard / "pages" / name
        directory.mkdir()
        (directory / "assets").mkdir()
        (directory / "assets" / "style.css").write_text("/* */\n", encoding="utf-8")
    if htaccess is not None:
        (dashboard / ".htaccess").write_text(htaccess, encoding="utf-8")
    subprocess.run(["git", "init", "-q", str(repo)], check=True)
    subprocess.run(["git", "-C", str(repo), "config", "user.email", "t@t"], check=True)
    subprocess.run(["git", "-C", str(repo), "config", "user.name", "t"], check=True)
    subprocess.run(["git", "-C", str(repo), "add", "-A"], check=True)
    subprocess.run(["git", "-C", str(repo), "commit", "-qm", "fixture"], check=True)
    for name in uncommitted:
        directory = dashboard / "pages" / name
        directory.mkdir(parents=True, exist_ok=True)
        (directory / "page.php").write_text("<?php\n", encoding="utf-8")
    return dashboard


CASES = []


def case(name):
    def register(fn):
        CASES.append((name, fn))
        return fn
    return register


@case("a directory with no page.php is not a page")
def _(root):
    dashboard = build(root, ["employees"], assets_only=["org", "reports"])
    module = load(dashboard, root / "manifest.txt")
    return module.legacy_pages() == {"index", "employees"}


@case("index.php is the home page and lives outside pages/")
def _(root):
    dashboard = build(root, ["employees"])
    module = load(dashboard, root / "manifest.txt")
    assert "index" in module.legacy_pages()
    (root / "b").mkdir()
    dashboard2 = build(root / "b", ["employees"], index=False)
    module2 = load(dashboard2, root / "manifest.txt")
    return "index" not in module2.legacy_pages()


@case("an .htaccess rewrite adds a route of its own")
def _(root):
    rewrite = (
        "RewriteEngine On\n"
        "RewriteRule ^company_detail\\.php$ pages/companies/detail.php [L]\n"
        "RewriteRule ^employee_detail\\.php$ pages/employees/detail.php [L]\n"
        "RewriteRule ^([a-z][a-z0-9_]*)\\.php$ pages/$1/page.php [L]\n"
    )
    dashboard = build(root, ["companies", "employees"], htaccess=rewrite)
    module = load(dashboard, root / "manifest.txt")
    pages = module.legacy_pages()
    # The catch-all rule's $1 is not a literal page name and must not become one.
    return pages == {"index", "companies", "employees", "company_detail", "employee_detail"}


@case("an empty manifest exits 2 rather than reporting no drift")
def _(root):
    dashboard = build(root, ["employees"])
    manifest = root / "manifest.txt"
    manifest.write_text("# only a comment\n", encoding="utf-8")
    module = load(dashboard, manifest)
    sys.argv = ["check_dashboard_page_drift.py"]
    with redirect_stdout(io.StringIO()), redirect_stderr(io.StringIO()):
        return module.main() == 2


@case("drift in either direction fails")
def _(root):
    dashboard = build(root, ["employees", "shifts"])
    manifest = root / "manifest.txt"
    manifest.write_text("index\nemployees\nbanners\n", encoding="utf-8")
    module = load(dashboard, manifest)
    sys.argv = ["check_dashboard_page_drift.py"]
    out = io.StringIO()
    with redirect_stdout(out), redirect_stderr(out):
        status = module.main()
    text = out.getvalue()
    return status == 1 and "shifts" in text and "banners" in text


@case("a manifest that matches passes")
def _(root):
    dashboard = build(root, ["employees", "shifts"])
    manifest = root / "manifest.txt"
    manifest.write_text("index\nemployees\nshifts\n", encoding="utf-8")
    module = load(dashboard, manifest)
    sys.argv = ["check_dashboard_page_drift.py"]
    with redirect_stdout(io.StringIO()), redirect_stderr(io.StringIO()):
        return module.main() == 0


@case("a page directory on disk and in no commit is not a page (R-063)")
def _(root):
    dashboard = build(root, ["employees"], uncommitted=["guide_videos"])
    module = load(dashboard, root / "manifest.txt")
    pages = module.legacy_pages()
    return "employees" in pages and "guide_videos" not in pages


def main() -> int:
    failures = 0
    for name, fn in CASES:
        with tempfile.TemporaryDirectory() as tmp:
            try:
                ok = bool(fn(pathlib.Path(tmp)))
            except Exception as exc:  # noqa: BLE001
                ok, name = False, f"{name} (raised {exc!r})"
        print(f"  {'OK  ' if ok else 'FAIL'} {name}")
        failures += 0 if ok else 1
    print(f"\n{len(CASES) - failures}/{len(CASES)} dashboard page-drift cases passed.")
    return 0 if failures == 0 else 1


if __name__ == "__main__":
    sys.exit(main())

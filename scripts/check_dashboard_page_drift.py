#!/usr/bin/env python3
"""Every page hr-legacy's dashboard serves, against the committed manifest.

The sibling of check_legacy_route_drift.py, for the other surface. It exists
because the dashboard's routable set is not the directory listing: three of
the directories under dashboard/pages/ carry no page.php and are not routes
at all, index.php is the home page rather than a pages/ entry, and two routes
reach a detail.php only through a rewrite in dashboard/.htaccess.

Counting directories -- which is the obvious thing to do, and which an earlier
inventory did -- overstates the surface and invents pages that do not exist.

    contracts/legacy-dashboard-pages.txt   the committed manifest
    scripts/check_dashboard_page_drift.py  refresh with --write

The Java side is not checked here. AdminDashboardPageInventoryTest enumerates
the real RequestMappingHandlerMapping and compares against this file, because
a grep over source is not an inventory -- it misses class-level prefixes and
array-valued mappings, and it has been wrong in both directions.
"""

import argparse
import os
import re
import sys

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DASHBOARD = os.path.join(REPO_ROOT, "..", "hr-legacy", "dashboard")
COMMITTED = os.path.join(REPO_ROOT, "contracts", "legacy-dashboard-pages.txt")

HEADER = """\
# Every page hr-legacy's dashboard serves, one per line, sorted.
#
# GENERATED -- refresh with:
#   python3 scripts/check_dashboard_page_drift.py --write
#
# A page is a routable URL under /dashboard/, not a directory:
#   - index                     dashboard/index.php, the home page
#   - <name>                    dashboard/pages/<name>/page.php, via the
#                               .htaccess rewrite ^([a-z][a-z0-9_]*)\\.php$
#   - company_detail            rewritten to pages/companies/detail.php
#   - employee_detail           rewritten to pages/employees/detail.php
#
# Directories under pages/ that carry no page.php (home, org, reports) are
# asset or helper directories and are deliberately absent.
"""

REWRITE = re.compile(r'^RewriteRule\s+\^([a-z][a-z0-9_]*)\\?\.php\$\s+pages/', re.M)


def legacy_pages() -> set[str]:
    if not os.path.isdir(DASHBOARD):
        return set()
    pages = set()
    if os.path.isfile(os.path.join(DASHBOARD, "index.php")):
        pages.add("index")
    pages_dir = os.path.join(DASHBOARD, "pages")
    for name in os.listdir(pages_dir):
        if os.path.isfile(os.path.join(pages_dir, name, "page.php")):
            pages.add(name)
    htaccess = os.path.join(DASHBOARD, ".htaccess")
    if os.path.isfile(htaccess):
        with open(htaccess, encoding="utf-8") as handle:
            for name in REWRITE.findall(handle.read()):
                pages.add(name)
    return pages


def committed_pages() -> set[str]:
    if not os.path.isfile(COMMITTED):
        return set()
    with open(COMMITTED, encoding="utf-8") as handle:
        return {line.strip() for line in handle
                if line.strip() and not line.startswith("#")}


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--write", action="store_true",
                        help="rewrite the manifest from hr-legacy")
    args = parser.parse_args()

    committed = committed_pages()
    legacy = legacy_pages()

    if args.write:
        if not legacy:
            print("hr-legacy is not present; refusing to write an empty manifest.")
            return 1
        with open(COMMITTED, "w", encoding="utf-8") as handle:
            handle.write(HEADER)
            for page in sorted(legacy):
                handle.write(page + "\n")
        print(f"wrote {len(legacy)} pages to contracts/legacy-dashboard-pages.txt")
        return 0

    if not committed:
        # The same rule check_legacy_route_drift.py follows: a gate whose own
        # baseline is missing proves nothing, and saying so loudly beats
        # reporting "no drift" for "nothing to compare".
        print(f"FATAL: {COMMITTED} is missing or empty. Run --write beside an "
              "hr-legacy checkout; without it this gate proves nothing.",
              file=sys.stderr)
        return 2

    print(f"committed dashboard pages: {len(committed)}")
    if not legacy:
        # The Java half needs no sibling checkout and is enforced separately by
        # AdminDashboardPageInventoryTest, which reads this manifest.
        print("hr-legacy is not checked out here; the Java half is covered by "
              "AdminDashboardPageInventoryTest.")
        return 0

    missing = sorted(legacy - committed)
    extra = sorted(committed - legacy)
    if missing:
        print(f"MISSING from the manifest ({len(missing)}): {', '.join(missing)}")
    if extra:
        print(f"NOT SERVED by hr-legacy ({len(extra)}): {', '.join(extra)}")
    if missing or extra:
        print("refresh with: python3 scripts/check_dashboard_page_drift.py --write")
        return 1
    print(f"hr-legacy present: its {len(legacy)} pages match the committed manifest.")
    return 0


if __name__ == "__main__":
    sys.exit(main())

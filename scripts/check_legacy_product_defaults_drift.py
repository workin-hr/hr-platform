#!/usr/bin/env python3
"""Pin this application's copies of hr-legacy's product defaults to the source.

Some of `AppConfig` is deployment configuration -- a database password, a JWT
secret, an SMS key -- and this port has no business reproducing any of it.
A few members are not configuration at all but product decisions that the
application must reproduce exactly, and those live beside the credentials in a
git-ignored `constants.php`.

`DEFAULT_ANNUAL_LEAVE_DAYS` is the case that motivated this file. It decides
the entitlement every new `leave_balance` row opens with, it replaced a
per-company setting in hr-legacy 505004f, and until a2dd5d7 it existed only in
a git-ignored file on one machine. There was nothing to port from and nothing
to check a port against -- exactly the R-063 failure, in a value rather than a
surface.

So the number is read back out of the tracked template, at HEAD, and compared
with the Java constant. Copying it by hand would have been a line of work; the
point is that it stays copied correctly after somebody changes it.

    hr-legacy apis/config/constants.example.php   the tracked contract
    LegacyLeavePolicy.java                        this port's copy

The example file rather than `constants.php` because the example is the only
one committed -- that is the whole reason this check can exist. It carries the
real value for product defaults (as it already does for DEFAULT_LIMIT and
MAX_LIMIT) and a placeholder for anything secret.
"""

from __future__ import annotations

import os
import re
import subprocess
import sys

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
LEGACY_REPO = os.path.join(REPO_ROOT, "..", "hr-legacy")
TEMPLATE = "apis/config/constants.example.php"

# One entry per product default this port reproduces: the PHP constant, the
# Java file that mirrors it, and the Java constant. Deployment configuration
# is deliberately absent and must stay absent -- a DB password has no business
# being compared against anything here.
PINNED = (
    (
        "DEFAULT_ANNUAL_LEAVE_DAYS",
        os.path.join("backend", "src", "main", "java", "com", "workin", "legacy",
                     "LegacyLeavePolicy.java"),
        "DEFAULT_ANNUAL_LEAVE_DAYS",
    ),
)


def php_constant(source: str, name: str) -> str | None:
    """`public const NAME = <value>;` -- the literal, normalised."""
    found = re.search(
        r"public\s+const\s+" + re.escape(name) + r"\s*=\s*([^;]+);", source)
    return normalise(found.group(1)) if found else None


def java_constant(source: str, name: str) -> str | None:
    """`... NAME = <value>;` -- the literal, normalised."""
    found = re.search(
        r"\b" + re.escape(name) + r"\s*=\s*([^;]+);", source)
    return normalise(found.group(1)) if found else None


def normalise(literal: str) -> str:
    """Compare values, not spellings.

    PHP writes `15.0` and Java may write `15.0d`; both mean the same number and
    a check that failed on the suffix would be noise. Numbers are compared as
    numbers, anything else as its stripped text.
    """
    text = literal.strip().rstrip("dDfFlL") or literal.strip()
    try:
        return repr(float(text))
    except ValueError:
        return literal.strip()


def template_source() -> str | None:
    """The template as HEAD has it, or None when hr-legacy is not beside us."""
    if not os.path.isdir(os.path.join(LEGACY_REPO, ".git")):
        return None
    blob = subprocess.run(
        ["git", "-C", LEGACY_REPO, "show", f"HEAD:{TEMPLATE}"],
        capture_output=True, text=True, check=False)
    return blob.stdout if blob.returncode == 0 else None


def main() -> int:
    template = template_source()
    if template is None:
        # Unlike the inventory gates there is no committed manifest to fall
        # back to, so this check simply does not run in CI. Say so rather than
        # printing a pass it did not earn.
        print("hr-legacy not checked out: product defaults not compared.")
        return 0

    status = 0
    for php_name, java_path, java_name in PINNED:
        expected = php_constant(template, php_name)
        java_file = os.path.join(REPO_ROOT, java_path)
        if expected is None:
            print(f"FATAL: {TEMPLATE} at HEAD does not define {php_name}. It is a product "
                  "default this port reproduces; commit it there rather than dropping "
                  "this check.", file=sys.stderr)
            status = 2
            continue
        if not os.path.isfile(java_file):
            print(f"FATAL: {java_path} is missing.", file=sys.stderr)
            status = 2
            continue
        with open(java_file, encoding="utf-8") as handle:
            actual = java_constant(handle.read(), java_name)
        if actual is None:
            print(f"FAIL: {java_path} does not define {java_name}.", file=sys.stderr)
            status = max(status, 1)
        elif actual != expected:
            print(f"FAIL: {php_name} drifted.\n"
                  f"  hr-legacy {TEMPLATE}: {expected}\n"
                  f"  {java_path}: {actual}", file=sys.stderr)
            status = max(status, 1)
        else:
            print(f"OK: {php_name} = {expected} in both.")

    if status == 0:
        print(f"OK: {len(PINNED)} product default(s) match hr-legacy at HEAD.")
    return status


if __name__ == "__main__":
    raise SystemExit(main())

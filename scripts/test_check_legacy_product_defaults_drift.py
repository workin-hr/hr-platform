#!/usr/bin/env python3
"""Deterministic regression tests for check_legacy_product_defaults_drift.py.

Run directly: `python3 scripts/test_check_legacy_product_defaults_drift.py`.

Fixture-based and hermetic: no sibling hr-legacy checkout. Each case builds a
real git repository holding a template and a stub Java file, and drives the
real functions over them.

Three cases carry the weight:

* the drifted value. Without it this file is decoration -- the whole point is
  that the number stays copied correctly after somebody changes it.
* `15.0` against `15.0d`. Java writes a double suffix and PHP does not; a
  check that failed on the spelling would be noise nobody would keep.
* the value on disk and in no commit. That is R-063, which is why this gate
  reads HEAD rather than the working tree.

Wired into: scripts/validate_phase0.py's script/test-sibling rule.
"""

from __future__ import annotations

import pathlib
import subprocess
import sys
import tempfile

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))

import check_legacy_product_defaults_drift as drift  # noqa: E402

FAILURES: list[str] = []


def check(name: str, condition: bool, detail: str = "") -> None:
    print(f"{'OK ' if condition else 'FAIL'} {name}"
          + (f" ({detail})" if detail and not condition else ""))
    if not condition:
        FAILURES.append(name)


def build_legacy(root: pathlib.Path, value: str, uncommitted: str | None = None) -> pathlib.Path:
    """A fixture hr-legacy whose template lives in a commit."""
    repo = root / "hr-legacy"
    template = repo / "apis" / "config" / "constants.example.php"
    template.parent.mkdir(parents=True, exist_ok=True)
    template.write_text(
        "<?php\nfinal class AppConfig {\n"
        "    public const DB_PASS = 'your_database_password';\n"
        f"    public const DEFAULT_ANNUAL_LEAVE_DAYS = {value};\n"
        "}\n", encoding="utf-8")
    subprocess.run(["git", "init", "-q", str(repo)], check=True)
    subprocess.run(["git", "-C", str(repo), "config", "user.email", "t@t"], check=True)
    subprocess.run(["git", "-C", str(repo), "config", "user.name", "t"], check=True)
    subprocess.run(["git", "-C", str(repo), "add", "-A"], check=True)
    subprocess.run(["git", "-C", str(repo), "commit", "-qm", "fixture"], check=True)
    if uncommitted is not None:
        template.write_text(
            "<?php\nfinal class AppConfig {\n"
            f"    public const DEFAULT_ANNUAL_LEAVE_DAYS = {uncommitted};\n"
            "}\n", encoding="utf-8")
    return repo


def build_java(root: pathlib.Path, body: str) -> str:
    rel = pathlib.Path("backend") / "Policy.java"
    path = root / rel
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(body, encoding="utf-8")
    return str(rel)


def run(root: pathlib.Path, legacy: pathlib.Path, java_rel: str,
        java_name: str = "DEFAULT_ANNUAL_LEAVE_DAYS",
        php_name: str = "DEFAULT_ANNUAL_LEAVE_DAYS") -> int:
    real = (drift.REPO_ROOT, drift.PINNED)
    drift.REPO_ROOT = str(root)
    drift.PINNED = ((php_name, java_rel, java_name),)
    try:
        # Drive the real --legacy argument rather than patching a module
        # global: the checkout the caller selects is now part of the
        # detector's contract, so the tests have to exercise that path.
        return drift.main(["--legacy", str(legacy)])
    finally:
        drift.REPO_ROOT, drift.PINNED = real


POLICY = "class Policy { public static final double DEFAULT_ANNUAL_LEAVE_DAYS = %s; }\n"


def test_matching_values_pass() -> None:
    with tempfile.TemporaryDirectory() as tmp:
        root = pathlib.Path(tmp)
        legacy = build_legacy(root, "15.0")
        java = build_java(root, POLICY % "15.0d")
        check("a Java constant equal to the template passes", run(root, legacy, java) == 0)


def test_a_drifted_value_fails() -> None:
    with tempfile.TemporaryDirectory() as tmp:
        root = pathlib.Path(tmp)
        legacy = build_legacy(root, "15.0")
        java = build_java(root, POLICY % "21.0d")
        check("a drifted value fails", run(root, legacy, java) == 1)


def test_the_double_suffix_is_not_drift() -> None:
    with tempfile.TemporaryDirectory() as tmp:
        root = pathlib.Path(tmp)
        legacy = build_legacy(root, "15.0")
        for suffix in ("15.0d", "15.0D", "15.0f", "15.0", "15"):
            java = build_java(root, POLICY % suffix)
            code = run(root, legacy, java)
            check(f"{suffix} compares equal to PHP's 15.0", code == 0, f"exit {code}")


def test_a_value_on_disk_and_in_no_commit_is_not_the_contract() -> None:
    """R-063: the gate reads HEAD, so an uncommitted edit is not the contract."""
    with tempfile.TemporaryDirectory() as tmp:
        root = pathlib.Path(tmp)
        legacy = build_legacy(root, "15.0", uncommitted="30.0")
        java = build_java(root, POLICY % "15.0d")
        check("an uncommitted template edit does not become the contract",
              run(root, legacy, java) == 0)


def test_a_missing_java_constant_fails() -> None:
    with tempfile.TemporaryDirectory() as tmp:
        root = pathlib.Path(tmp)
        legacy = build_legacy(root, "15.0")
        java = build_java(root, "class Policy { }\n")
        check("a Java file that defines nothing fails", run(root, legacy, java) == 1)


def test_a_missing_php_constant_is_fatal() -> None:
    with tempfile.TemporaryDirectory() as tmp:
        root = pathlib.Path(tmp)
        legacy = build_legacy(root, "15.0")
        java = build_java(root, POLICY % "15.0d")
        # Pin a name the template does not carry: a product default that
        # vanished upstream is a fatal contract problem, not a value mismatch.
        code = run(root, legacy, java, php_name="NO_SUCH_DEFAULT")
        check("a product default missing from the template is fatal", code == 2, f"exit {code}")


def test_no_hr_legacy_does_not_claim_a_pass() -> None:
    with tempfile.TemporaryDirectory() as tmp:
        root = pathlib.Path(tmp)
        java = build_java(root, POLICY % "15.0d")
        check("with no hr-legacy the check exits 0 without comparing",
              run(root, root / "absent", java) == 0)


def test_a_linked_worktree_is_a_real_checkout() -> None:
    """A linked worktree's `.git` is a FILE, not a directory.

    The presence test used to be os.path.isdir(legacy/".git"), which rejects
    one -- so pointing --legacy at a worktree reported "not checked out" and
    skipped the comparison while exiting 0. This repository is worked entirely
    through linked worktrees, so that is the normal case, not an exotic one.
    """
    with tempfile.TemporaryDirectory() as tmp:
        root = pathlib.Path(tmp)
        legacy = build_legacy(root, "21")
        linked = root / "linked-worktree"
        subprocess.run(["git", "-C", str(legacy), "worktree", "add", "-q",
                        str(linked), "HEAD"], check=True,
                       capture_output=True)
        check("a linked worktree's .git is a file, not a directory",
              (linked / ".git").is_file() and not (linked / ".git").is_dir())
        check("the presence test accepts a linked worktree",
              drift.is_git_checkout(str(linked)))

        java = build_java(root, POLICY % "21.0")
        # The decisive assertion: identical verdict from the worktree and the
        # clone. Before the fix this returned 0 *without comparing anything*,
        # which is indistinguishable from a pass by exit code alone.
        check("a linked worktree yields the same verdict as its clone",
              run(root, linked, java) == run(root, legacy, java) == 0)

        drifted = build_java(root, POLICY % "22.0")
        check("and it still detects drift through the worktree",
              run(root, linked, drifted) == 1)


def main() -> int:
    test_matching_values_pass()
    test_a_drifted_value_fails()
    test_the_double_suffix_is_not_drift()
    test_a_value_on_disk_and_in_no_commit_is_not_the_contract()
    test_a_missing_java_constant_fails()
    test_a_missing_php_constant_is_fatal()
    test_no_hr_legacy_does_not_claim_a_pass()
    test_a_linked_worktree_is_a_real_checkout()
    print()
    if FAILURES:
        print(f"{len(FAILURES)} FAILURE(S): {FAILURES}")
        return 1
    print("all check_legacy_product_defaults_drift cases passed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

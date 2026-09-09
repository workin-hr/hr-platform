#!/usr/bin/env python3
"""Deterministic regression tests for scripts/check_all_legacy_drift.sh.

Run directly: `python3 scripts/test_check_all_legacy_drift.py`.

Fixture-based and hermetic: every case copies the real wrapper into a
temporary tree beside nine *stub* detectors, so no hr-legacy checkout and no
real detector runs. The wrapper resolves detectors as `scripts/<name>.py`
relative to the working directory and never consults `$0`, which is what makes
stubbing possible.

Why this guard matters, and why a shell script needed it at all: the wrapper's
entire purpose is to catch two silent failures -- a detector invoked with the
wrong flag (argparse accepts an unambiguous *prefix*, so `--legacy` was
silently taken as `--legacy-lang`), and a detector that cannot find hr-legacy,
says so, and still exits 0. Both are invisible in a green run. The wrapper
therefore has to be tested on the exact properties it exists to assert, or the
same false-green class returns unnoticed.

It also closes a real coverage hole: validate_phase0.py's script/test-sibling
rule (validate_script_test_siblings) globs `scripts/*.py` only, so a shell
guard can ship with zero coverage without the validator noticing.

Wired into: .github/workflows/phase0-validate.yml.
"""
from __future__ import annotations

import json
import os
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
WRAPPER = REPO_ROOT / "scripts" / "check_all_legacy_drift.sh"

# name -> (detector filename, expected argv given legacy root L)
EXPECTED: dict[str, tuple[str, list[str]]] = {
    "schema":            ("check_legacy_schema_drift.py",             ["--legacy", "{L}"]),
    "modules":           ("check_legacy_modules_drift.py",            ["--legacy", "{L}"]),
    "lang":              ("check_legacy_lang_drift.py",               ["--legacy", "{L}"]),
    "spreadsheet":       ("check_legacy_spreadsheet_columns_drift.py", ["--legacy", "{L}"]),
    "sensitive-keys":    ("check_legacy_sensitive_keys_drift.py",     ["--legacy", "{L}"]),
    "excel-error-codes": ("check_legacy_excel_error_codes_drift.py",  ["--legacy", "{L}"]),
    # The three that do NOT take a bare --legacy. A wrong flag here is silent,
    # which is the whole reason these are asserted individually.
    "routes":            ("check_legacy_route_drift.py",              ["--legacy-api", "{L}/apis/api"]),
    "messages":          ("check_legacy_message_drift.py",            ["--legacy-lang", "{L}/apis/lang"]),
    "product-defaults":  ("check_legacy_product_defaults_drift.py",   ["--legacy", "{L}"]),
}

STUB = '''#!/usr/bin/env python3
import json, os, sys
name = os.path.basename(sys.argv[0])
with open(os.path.join(os.environ["ARGV_DIR"], name + ".argv"), "w") as fh:
    json.dump(sys.argv[1:], fh)
plan = json.load(open(os.environ["PLAN"]))
spec = plan.get(name, {})
sys.stdout.write(spec.get("out", "OK: fine\\n"))
sys.exit(spec.get("exit", 0))
'''

failures: list[str] = []


def check(condition: bool, message: str) -> None:
    if not condition:
        failures.append(message)


def run_wrapper(plan: dict, *, legacy_exists: bool = True, legacy_arg: str | None = None):
    """Drive the real wrapper over stub detectors. Returns (exit, stdout, argv)."""
    tmp = Path(tempfile.mkdtemp())
    try:
        (tmp / "scripts").mkdir()
        shutil.copy(WRAPPER, tmp / "scripts" / WRAPPER.name)
        for _, (filename, _args) in EXPECTED.items():
            stub = tmp / "scripts" / filename
            stub.write_text(STUB)
            stub.chmod(0o755)

        legacy = tmp / "hr-legacy"
        if legacy_exists:
            legacy.mkdir()
        argv_dir = tmp / "argv"; argv_dir.mkdir()
        plan_file = tmp / "plan.json"; plan_file.write_text(json.dumps(plan))

        proc = subprocess.run(
            ["bash", f"scripts/{WRAPPER.name}", legacy_arg if legacy_arg is not None else str(legacy)],
            cwd=tmp, capture_output=True, text=True,
            env={**os.environ, "ARGV_DIR": str(argv_dir), "PLAN": str(plan_file)})
        seen = {p.name.replace(".argv", ""): json.loads(p.read_text())
                for p in argv_dir.glob("*.argv")}
        return proc.returncode, proc.stdout + proc.stderr, seen, str(legacy)
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


# --- 1. every detector receives exactly the flag it actually takes ----------
code, out, seen, legacy = run_wrapper({})
check(code == 0, f"all-clean run should exit 0, got {code}\n{out}")
check(len(seen) == 9, f"expected 9 detectors invoked, saw {len(seen)}: {sorted(seen)}")
for label, (filename, argtpl) in EXPECTED.items():
    want = [a.replace("{L}", legacy) for a in argtpl]
    got = seen.get(filename)
    check(got == want, f"{label}: expected argv {want}, got {got}")

# --- 2. final status line names the checkout actually compared --------------
check("all 9 detectors read" in out, f"clean run should announce all 9 read the checkout:\n{out}")
check(legacy in out, "final status must name the selected checkout, not a hard-coded one")

# --- 3. a detector that exits 0 but never read hr-legacy is NOT a pass ------
code, out, _seen, _l = run_wrapper(
    {"check_legacy_route_drift.py": {"exit": 0, "out": "hr-legacy not checked out: routes not compared.\n"}})
check(code == 1, f"degraded detector must fail the wrapper, got exit {code}\n{out}")
check("DEGRADED" in out, f"degraded detector must be reported as DEGRADED:\n{out}")
check("1 degraded" in out, f"degraded count must reach the summary:\n{out}")
check("all 9 detectors read" not in out, "a degraded run must NOT claim all nine read the checkout")

# --- 4. a non-zero detector is reported and aggregated ----------------------
code, out, _seen, _l = run_wrapper(
    {"check_legacy_schema_drift.py": {"exit": 2, "out": "FAIL: schema drifted\n"}})
check(code == 1, f"failing detector must fail the wrapper, got exit {code}\n{out}")
check("FAIL" in out and "exit 2" in out, f"failure must report the detector's exit code:\n{out}")
check("1 failed" in out, f"failure count must reach the summary:\n{out}")

# --- 5. failures and degradations aggregate together -----------------------
code, out, _seen, _l = run_wrapper({
    "check_legacy_schema_drift.py": {"exit": 2, "out": "FAIL\n"},
    "check_legacy_modules_drift.py": {"exit": 1, "out": "FAIL\n"},
    "check_legacy_message_drift.py": {"exit": 0, "out": "hr-legacy not checked out: skipped.\n"},
})
check(code == 1, f"mixed failures must exit 1, got {code}")
check("2 failed, 1 degraded" in out, f"counts must aggregate exactly:\n{out}")

# --- 6. a missing checkout is fatal, not a silent pass ----------------------
code, out, seen, _l = run_wrapper({}, legacy_exists=False, legacy_arg="/nonexistent-checkout")
check(code == 2, f"missing checkout must exit 2, got {code}\n{out}")
check(not seen, f"no detector may run without a checkout, but these did: {sorted(seen)}")

if failures:
    print(f"FAIL: {len(failures)} assertion(s) failed", file=sys.stderr)
    for f in failures:
        print(f"  - {f}", file=sys.stderr)
    sys.exit(1)
print("OK: check_all_legacy_drift.sh regression tests passed (6 cases)")

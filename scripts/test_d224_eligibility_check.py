#!/usr/bin/env python3
"""Deterministic regression tests for D-224's eligibility command.

Run directly: `python3 scripts/test_d224_eligibility_check.py`.

The command lives in a fenced block inside docs/bootstrap/decision-log-wave12r.md
rather than in a script file, because it is the *evidence procedure* D-224
requires and belongs beside the criteria it evidences. That makes it easy to
edit into something that no longer works, so these tests extract the committed
block and exercise it -- against a stub `gh` on PATH, so nothing touches the
real repository.

The two failure modes pinned here both shipped once and were caught in review:

  1. Target-head resolution failed OPEN. `gh pr view` failing left SHA empty,
     `[ "" -eq 0 ]` errored, the `if` fell through to its else branch, and the
     command printed "the gate is SATISFIED" -- a confident verdict about a
     gate it never examined -- and exited 0.

  2. The repository-wide feeds read only page one. The two feeds are capped
     independently and churn at different rates, so a recovery posted as a
     review comment could fall off page one of the fast feed while a stale
     usage-limit notice survived on the slow one, and the merge reported an
     exhaustion that had already ended.
"""
from __future__ import annotations

import os
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DOC = ROOT / "docs/bootstrap/decision-log-wave12r.md"

failures: list[str] = []


def check(condition: bool, message: str) -> None:
    if not condition:
        failures.append(message)


def extract_block() -> str:
    text = DOC.read_text(encoding="utf-8")
    m = re.search(r"```bash\n(set -o pipefail\nPR=<number>.*?)```", text, re.S)
    if not m:
        raise SystemExit("FAIL: D-224's eligibility block is no longer in the decision log")
    return m.group(1)


BLOCK = extract_block()

# --- structural properties the block must keep ------------------------------
check("--paginate" in BLOCK,
      "the repository-wide feeds must use --paginate; page one alone hides a recovery")
check(BLOCK.count("--paginate") >= 2,
      "BOTH feeds must paginate -- capping either one independently is the defect")
check(".[][]" in BLOCK,
      "--paginate emits one array per page, so the filter must flatten with .[][]")
check(re.search(r"headRefOid.*\|\|\s*die", BLOCK) is not None,
      "target-head resolution must fail closed (|| die) before any criterion runs")
check("${#SHA} -eq 40" in BLOCK,
      "the resolved head must be validated as a full sha before use")


def run_block(pr: str, gh_stub: str) -> tuple[int, str]:
    """Run the committed block with a stub `gh` first on PATH."""
    tmp = Path(tempfile.mkdtemp())
    try:
        bindir = tmp / "bin"
        bindir.mkdir()
        stub = bindir / "gh"
        stub.write_text(gh_stub)
        stub.chmod(0o755)
        script = tmp / "check.sh"
        script.write_text(BLOCK.replace("PR=<number>", f"PR={pr}"))
        env = {**os.environ, "PATH": f"{bindir}:{os.environ['PATH']}"}
        proc = subprocess.run(["bash", str(script)], capture_output=True, text=True, env=env)
        return proc.returncode, proc.stdout + proc.stderr
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


# --- 1. head resolution fails -> abort before evaluating anything -----------
FAILING_GH = '#!/usr/bin/env bash\nexit 1\n'
code, out = run_block("190", FAILING_GH)
check(code == 3, f"a failed head resolution must exit 3, got {code}\n{out}")
check("evidence incomplete" in out, f"it must say the evidence is incomplete:\n{out}")
check("SATISFIED" not in out and "PASS" not in out,
      f"it must print NO verdict about the gate when the head could not be resolved:\n{out}")

# --- 2. head resolves to something that is not a sha ------------------------
BAD_SHA_GH = '''#!/usr/bin/env bash
[ "$1" = "pr" ] && { echo "not-a-sha"; exit 0; }
echo "[]"
'''
code, out = run_block("190", BAD_SHA_GH)
check(code == 3, f"a non-sha head must exit 3, got {code}\n{out}")
check("not a" in out.lower(), f"it must say the head is not a sha:\n{out}")

# --- 3. the feeds are read through --paginate, not one page -----------------
COUNTING_GH = '''#!/usr/bin/env bash
if [ "$1" = "pr" ]; then
  case "${*}" in
    *headRefOid*) echo "eaae1c42dec3de7e142e2c2ea808e8e0e6a86767"; exit 0 ;;
    *) echo '{"reviews":[],"comments":[]}'; exit 0 ;;
  esac
fi
if [ "$1" = "api" ]; then
  for a in "$@"; do [ "$a" = "--paginate" ] && echo "PAGINATED" >> "$PAGINATE_LOG"; done
  echo "[]"
fi
'''
tmpdir = Path(tempfile.mkdtemp())
log = tmpdir / "paginate.log"
log.write_text("")
os.environ["PAGINATE_LOG"] = str(log)
code, out = run_block("190", COUNTING_GH)
seen = log.read_text().count("PAGINATED")
check(seen >= 2, f"both feeds must be fetched with --paginate; saw {seen} paginated call(s)")
shutil.rmtree(tmpdir, ignore_errors=True)

if failures:
    print(f"FAIL: {len(failures)} assertion(s) failed", file=sys.stderr)
    for f in failures:
        print(f"  - {f}", file=sys.stderr)
    sys.exit(1)
print("OK: D-224 eligibility check regression tests passed")

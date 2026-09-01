#!/usr/bin/env bash
# Mutation coverage, derived from the FROZEN PHP's own method guard and from
# cases that actually succeed -- not from filenames and not from the mere
# presence of a run_case line.
#
# The earlier name-based version was wrong in both directions, and said so in
# its own output while contradicting it:
#   - it counted GETs as mutating, because attendance/overall_report does not
#     look like a read;
#   - it counted an endpoint as covered when the only case for it expected a
#     REFUSAL (branches/delete 409, request_types/delete 409,
#     attendance/check_out 400), which the same document calls not-coverage.
#
# Mutating = the endpoint's PHP file guards on a method other than GET.
# Covered  = at least one run_case for it declares a 2xx expected status, so a
#            success path actually ran and its rows were compared.
set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
WORKSPACE=${WORKSPACE:-"$(cd "$HERE/../../.." && pwd)"}
LEGACY=${LEGACY:-"$WORKSPACE/hr-legacy"}
[ -d "$LEGACY/apis/api" ] || { echo "FATAL: no PHP tree at $LEGACY/apis/api" >&2; exit 2; }

python3 - "$HERE" "$LEGACY" <<'PY'
import re, sys, os
here, legacy = sys.argv[1], sys.argv[2]

inventory = [l.strip() for l in open(f"{here}/client-endpoints.txt") if l.strip()]

mutating, reads, missing = [], [], []
for ep in inventory:
    path = f"{legacy}/apis/api/{ep}.php"
    if not os.path.exists(path):
        missing.append(ep); continue
    src = open(path, encoding="utf-8", errors="replace").read()
    m = re.search(r"REQUEST_METHOD\]\s*!==\s*HttpMethod::(\w+)", src)
    method = m.group(1) if m else None
    (reads if method in (None, "GET") else mutating).append(ep)

sweep_lines = open(f"{here}/sweep-mutations.sh").read().split("\n")

# Join each run_case invocation with its backslash-continued lines, so the
# trailing EXPECT is read from the invocation itself rather than from whatever
# line happens to precede the next one.
invocations = []
i = 0
while i < len(sweep_lines):
    line = sweep_lines[i]
    if line.startswith("run_case "):
        parts = [line]
        while parts[-1].rstrip().endswith("\\"):
            i += 1
            parts.append(sweep_lines[i])
        invocations.append(" ".join(p.rstrip().rstrip("\\").strip() for p in parts))
    i += 1

declared = {}
for inv in invocations:
    m = re.match(r'run_case\s+"[^"]*"\s+\w+\s+"([^"?]+)', inv)
    if not m:
        continue
    tail = re.search(r'(\d{3})\s*$', inv)
    if tail:
        declared.setdefault(m.group(1), set()).add(int(tail.group(1)))

assert invocations, "no run_case invocations parsed -- the parser is broken, not the sweep"
undeclared = [i for i in invocations if not re.search(r'\d{3}\s*$', i)]
if undeclared:
    print(f"WARNING: {len(undeclared)} case(s) declare no expected status:")
    for u in undeclared[:5]:
        print("   ", u[:100])
    print()

def covered(ep):
    return any(200 <= c < 300 for c in declared.get(ep, ()))

ok  = [e for e in mutating if covered(e)]
ref = [e for e in mutating if e in declared and not covered(e)]
non = [e for e in mutating if e not in declared]

print(f"mutating endpoints (PHP guards a non-GET method) : {len(mutating)}")
print(f"  covered by a SUCCESS-path case                 : {len(ok)}")
print(f"  exercised only by a REFUSAL case               : {len(ref)}")
print(f"  no case at all                                 : {len(non)}")
print(f"reads (GET or no method guard), excluded         : {len(reads)}")
if missing:
    print(f"inventory entries with no PHP file               : {len(missing)}  {missing}")
print()
if ref:
    print("Exercised ONLY through a refusal -- NOT counted as covered:")
    for e in sorted(ref): print(f"  {e}  (declared {sorted(declared[e])})")
    print()
print("No case at all:")
for e in sorted(non): print(f"  {e}")
PY

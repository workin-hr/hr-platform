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

python3 - "$HERE" "$LEGACY" "${SWEEP_RESULT:-$HERE/last-run.txt}" <<'PY'
import re, sys, os
here, legacy, result_path = sys.argv[1], sys.argv[2], sys.argv[3]

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
    if any(line.startswith(prefix) for prefix in
           ("run_case ", "run_multipart_case ", "run_form_case ", "run_otp_case ")):
        parts = [line]
        while parts[-1].rstrip().endswith("\\"):
            i += 1
            parts.append(sweep_lines[i])
        invocations.append(" ".join(p.rstrip().rstrip("\\").strip() for p in parts))
    i += 1

# The actor argument's closed vocabulary. It has to be closed: run_case's
# signature ends TABLES WHO EXPECT with WHO optional, so a bare trailing `214`
# is ambiguous between "actor 214, no expected status" and "expects 214" unless
# the actors are known. No endpoint on this surface answers 214 or 244.
CASE_ACTORS = {"214", "244", "emp", "company", "-"}


def declared_status(invocation):
    """The EXPECT argument, which is not always the last token.

    run_otp_case takes an optional actor AFTER it (`... 200 company`), so a
    `\\d{3}\\s*$` search stopped matching and the case was reported as
    declaring nothing -- and therefore as uncovered. Searching more loosely is
    worse: on `... "employees" 214 200` the leftmost three-digit match is the
    ACTOR. Tokens from the end, with CASE_ACTORS telling the two apart.
    """
    tokens = invocation.split()
    if not tokens:
        return None
    if re.fullmatch(r"\d{3}", tokens[-1]) and tokens[-1] not in CASE_ACTORS:
        return int(tokens[-1])
    if (tokens[-1] in CASE_ACTORS and len(tokens) >= 2
            and re.fullmatch(r"\d{3}", tokens[-2]) and tokens[-2] not in CASE_ACTORS):
        return int(tokens[-2])
    return None


# name -> the status that invocation declared, and endpoint -> its case names.
# Keeping these per-INVOCATION is the point: an endpoint with both a success and
# a refusal case would otherwise be credited when only the refusal passed.
declared = {}
case_status = {}
for inv in invocations:
    # run_case:           NAME METHOD "PATH" ...
    # run_multipart_case: NAME "PATH" FIELD ...
    # run_case / run_form_case: NAME METHOD "PATH" ...
    # run_multipart_case:        NAME "PATH" FIELD ...
    # run_otp_case:              NAME "PREP" 'PREPBODY' "ACT" ... -- the ACT path
    #                            is the endpoint under test; the prep path has
    #                            its own case.
    m = None
    for pattern in (
            r'run_(?:case|form_case)\s+"[^"]*"\s+\w+\s+"([^"?]+)',
            r'run_multipart_case\s+"[^"]*"\s+"([^"?]+)',
            r'run_otp_case\s+"[^"]*"\s+"[^"]*"\s+\S.*?\s+"([^"?]+)'):
        m = re.match(pattern, inv)
        if m:
            break
    if not m:
        continue
    status = declared_status(inv)
    name_m = re.match(r'run_\w*case\s+"([^"]*)"', inv)
    if status is not None:
        declared.setdefault(m.group(1), set()).add(status)
        if name_m:
            case_status[name_m.group(1)] = status

assert invocations, "no run_case invocations parsed -- the parser is broken, not the sweep"
undeclared = [i for i in invocations if declared_status(i) is None]
if undeclared:
    print(f"WARNING: {len(undeclared)} case(s) declare no expected status:")
    for u in undeclared[:5]:
        print("   ", u[:100])
    print()

# Coverage requires EVIDENCE FROM A RUN, not a declaration.
#
# A declared 2xx says only what the case intends. An unreachable case, a Java
# 500, a response or row mismatch, or a 2xx that mutated nothing would all have
# been regenerated into the gaps document as "covered" while the sweep reported
# failure. Coverage now means: the sweep ran this case, it declared a 2xx, and
# the run recorded it as ok (or as an explicitly accepted divergence).
import os
run_ok, run_seen = set(), set()
if os.path.exists(result_path):
    for line in open(result_path):
        m = re.match(r'^(\S.*?)\s{2,}(\d{3}|-)\s+(\d{3}|-)\s+(ok|DIFF|ACCEPTED|UNEXPECTED-STATUS|RESEED-FAILED|LOGIN-FAILED|JAVA-NOT-SERVING|UNREACHABLE)',
                     line.rstrip())
        if not m:
            continue
        name, verdict = m.group(1).strip(), m.group(4)
        run_seen.add(name)
        # Only "ok" counts. An ACCEPTED case documents a DIVERGENCE -- the two
        # stacks deliberately disagree there -- which is the opposite of
        # evidence that the endpoint behaves identically. Where an endpoint has
        # both (advances/approve has a normal case and a cross-tenant guard),
        # the normal one carries the coverage; where it has only an accepted
        # case (employees/analyze_excel, R-038), it is correctly uncovered.
        if verdict == "ok":
            run_ok.add(name)
else:
    print(f"NO RUN EVIDENCE at {result_path}.")
    print("Run ./sweep-mutations.sh (it writes last-run.txt) before trusting these numbers.")
    print()

# map endpoint -> the case names that target it
case_names = {}
for inv in invocations:
    m = None
    for pattern in (
            r'run_(?:case|form_case)\s+"([^"]*)"\s+\w+\s+"([^"?]+)',
            r'run_multipart_case\s+"([^"]*)"\s+"([^"?]+)',
            r'run_otp_case\s+"([^"]*)"\s+"[^"]*"\s+\S.*?\s+"([^"?]+)'):
        m = re.match(pattern, inv)
        if m:
            break
    if m:
        case_names.setdefault(m.group(2), []).append(m.group(1))

def covered(ep):
    """Covered = a case that DECLARED a 2xx for this endpoint actually passed.

    Checking the endpoint's declared statuses and its passing case names
    separately was wrong: `branches/delete` has a 200 success case and a 409
    refusal case, so a broken success path was still counted as covered because
    the refusal passed. The verdict has to belong to the invocation that
    declared the 2xx.
    """
    if not run_seen:
        return False              # no evidence at all -> nothing is covered
    for name in case_names.get(ep, []):
        status = case_status.get(name)
        if status is not None and 200 <= status < 300 and name in run_ok:
            return True
    return False

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

# The document is WRITTEN here, not transcribed by hand from the numbers above.
# It had drifted once already -- it claimed 98 covered while this script printed
# 115 -- because "regenerate with ./coverage-report.sh" described a step that
# only ever printed. A document that has to be copied is a document that rots.
BLOCKED = {
    "profile/register_push_token":
        "**R-013**: it INSERTs a `company_id` column `push_tokens` does not have, "
        "so it 500s for every caller and always has. The port reproduces the "
        "failure (**D-058**).",
    "attendance/set_employee_attendance_method":
        "no PHP file; legacy answers 501.",
}

lines = []
lines.append("# Mutation coverage: what is exercised, and what is not")
lines.append("")
lines.append("**Generated by `./coverage-report.sh` after a full `./sweep-mutations.sh` run.**")
lines.append("Do not hand-edit: the script overwrites this file.")
lines.append("")
lines.append("Covered means: the sweep executed a case for this endpoint, **that case declared")
lines.append("a 2xx**, and the run recorded **that case** `ok`. The verdict is bound to the")
lines.append("invocation that declared the success status -- an endpoint with both a success")
lines.append("and a refusal case is not credited when only the refusal passes. An `ACCEPTED`")
lines.append("verdict never counts: it documents a deliberate divergence.")
lines.append("")
lines.append("`mutating` comes from the frozen PHP's own method guard, not the filename.")
lines.append("")
lines.append("| | count |")
lines.append("|---|---|")
lines.append(f"| mutating endpoints (PHP guards a non-GET method) | **{len(mutating)}** |")
lines.append(f"| covered by a success-path case, verified by a run | **{len(ok)}** |")
lines.append(f"| exercised only by a refusal -- *not counted* | {len(ref)} |")
lines.append(f"| no case at all | {len(non)} |")
lines.append(f"| reads (GET or no method guard), excluded | {len(reads)} |")
lines.append("")
lines.append("## Genuinely blocked -- no success path exists")
lines.append("")
for endpoint, why in sorted(BLOCKED.items()):
    lines.append(f"- `{endpoint}` -- {why}")
lines.append("")
lines.append("## Exercised only through a refusal")
lines.append("")
if ref:
    for e in sorted(ref):
        note = BLOCKED.get(e, "no success-path case yet.")
        lines.append(f"- `{e}` (declared {sorted(declared[e])}) -- {note}")
else:
    lines.append("None: every endpoint with a case has a passing success path.")
lines.append("")
lines.append("## No case at all")
lines.append("")
if non:
    for e in sorted(non):
        lines.append(f"- `{e}`")
else:
    lines.append("None.")
lines.append("")
open(f"{here}/MUTATION-COVERAGE-GAPS.md", "w").write("\n".join(lines))
print()
print(f"wrote {here}/MUTATION-COVERAGE-GAPS.md")
PY

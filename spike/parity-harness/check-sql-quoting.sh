#!/usr/bin/env bash
# Refuse double quotes and dollar-quote sequences in SQL COMMENT lines.
#
# Why comments specifically: the SQL in this harness is passed as `-e "..."`, so
# a double quote closes the surrounding bash string and splits the argument list
# -- mariadb then prints its usage banner and runs nothing -- while `$'` begins
# ANSI-C quoting and truncates the statement. Neither is a shell syntax error.
#
# This has happened FOUR times here, THREE of them in a comment, and twice in a
# comment explaining the previous occurrence. Comments are where it happens
# because they are the part that reads like prose, so quoting a phrase or naming
# a variable feels natural.
#
# Scanning comment lines is deliberate rather than parsing the whole string: a
# scanner that walks to the closing quote has the same blind spot bash does --
# it cannot tell an injected quote from the real terminator, which is exactly
# how the first version of this checker passed a file that was broken.
set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"

python3 - "$HERE" <<'PY'
import re, sys
from pathlib import Path

here = Path(sys.argv[1])
status = 0
for name in ("seed.sh", "seed-two.sh", "sweep-mutations.sh", "sweep-auth.sh", "resolve-params.sh"):
    path = here / name
    if not path.exists():
        continue
    bad = []
    for n, line in enumerate(path.read_text().splitlines(), 1):
        stripped = line.strip()
        # An SQL comment is `--` followed by whitespace. A shell long option
        # (`--format`, `--quiet`) is not, and flagging those would be noise --
        # docker inspect's --format legitimately carries quotes.
        if not re.match(r"^--(\s|$)", stripped):
            continue
        if '"' in stripped:
            bad.append((n, "double quote", stripped[:78]))
        if "$'" in stripped:
            bad.append((n, "dollar-quote", stripped[:78]))
    if bad:
        status = 1
        print(f"FAIL {name}")
        for n, what, text in bad:
            print(f"  line {n}: {what} in an SQL comment")
            print(f"    {text}")
    else:
        print(f"ok   {name}")
sys.exit(status)
PY

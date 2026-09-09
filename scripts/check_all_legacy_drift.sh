#!/usr/bin/env bash
# Run every hr-legacy drift detector, with the flag each one actually takes,
# and refuse to report success unless each proves it read hr-legacy.
#
# Two things went wrong that this exists to prevent, both found by review
# rather than by us (R-071, D-221):
#
#   1. The flags differ per script, and three of them are not `--legacy`.
#      A guessed flag is not rejected: Python's argparse accepts an
#      unambiguous PREFIX of a long option, so `--legacy` on the message
#      check was silently taken as `--legacy-lang` and pointed the script at
#      the wrong directory.
#
#   2. Three detectors DEGRADE rather than fail when they cannot find
#      hr-legacy. They compare the port against its own committed inventory,
#      print "not checked out", and exit 0 with OK on the last line. A green
#      exit is therefore not evidence that anything was compared.
#
# So this asserts the positive: every detector must exit 0 AND must not have
# fallen back. Run it from the repository root with hr-legacy as a sibling.
set -uo pipefail

LEGACY="${1:-../hr-legacy}"
if [ ! -d "$LEGACY" ]; then
    echo "FATAL: no hr-legacy checkout at $LEGACY -- nothing can be compared." >&2
    exit 2
fi

failed=0
degraded=0

run() {
    local name="$1"; shift
    local output status
    output="$("$@" 2>&1)"
    status=$?

    if [ "$status" -ne 0 ]; then
        printf 'FAIL      %-26s exit %s\n' "$name" "$status"
        printf '%s\n' "$output" | sed 's/^/            /'
        failed=$((failed + 1))
        return
    fi

    # Exit 0 is not enough. A detector that could not find hr-legacy says so
    # and still exits 0; treating that as a pass is the whole failure mode.
    if printf '%s' "$output" | grep -qi "not checked out"; then
        printf 'DEGRADED  %-26s exit 0, but it never read hr-legacy\n' "$name"
        printf '%s\n' "$output" | grep -i "not checked out" | sed 's/^/            /'
        degraded=$((degraded + 1))
        return
    fi

    printf 'ok        %-26s %s\n' "$name" "$(printf '%s' "$output" | tail -1 | cut -c1-72)"
}

echo "Comparing against $LEGACY"
echo

run schema            python3 scripts/check_legacy_schema_drift.py             --legacy "$LEGACY"
run modules           python3 scripts/check_legacy_modules_drift.py            --legacy "$LEGACY"
run lang              python3 scripts/check_legacy_lang_drift.py               --legacy "$LEGACY"
run spreadsheet       python3 scripts/check_legacy_spreadsheet_columns_drift.py --legacy "$LEGACY"
run sensitive-keys    python3 scripts/check_legacy_sensitive_keys_drift.py     --legacy "$LEGACY"
run excel-error-codes python3 scripts/check_legacy_excel_error_codes_drift.py  --legacy "$LEGACY"

# These three take a different flag, or none. Spelled out rather than
# generated, because a wrong flag here is silent.
run routes            python3 scripts/check_legacy_route_drift.py   --legacy-api  "$LEGACY/apis/api"
run messages          python3 scripts/check_legacy_message_drift.py --legacy-lang "$LEGACY/apis/lang"
run product-defaults  python3 scripts/check_legacy_product_defaults_drift.py

echo
if [ "$failed" -gt 0 ] || [ "$degraded" -gt 0 ]; then
    echo "$failed failed, $degraded degraded -- the baseline is NOT verified."
    exit 1
fi
echo "all 9 detectors read $LEGACY and agree with it."

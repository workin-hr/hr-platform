#!/usr/bin/env python3
"""Does the committed client contract still match the client source?

`spike/client-contract/*-contracts.json` is the extractor's output and the input
to the committed coverage reports. It is derived purely from the pinned client
checkouts, so it is deterministic and can drift silently when the extractor
changes, when a client submodule moves, or when someone edits a report by hand.
This re-extracts and compares.

What it deliberately does NOT check: the *-results.json files and the PHP/Java
columns of the reports. Those need both stacks running and a seeded database, so
they cannot be reproduced in CI -- the reports say which run produced them, and
regenerating them is a documented manual step.

Skips, loudly, when a client checkout is absent: the submodules are not
initialised in every environment, and a check that silently passes on a missing
input is worse than one that says why it did nothing.
"""
import json
import subprocess
import sys
from pathlib import Path

DEFAULT_ROOT = Path(__file__).resolve().parents[1]
CLIENT_DIRS = {
    'desktop': 'flutter-integration/workin_desktop',
    'mobile': 'flutter-integration/workin_mobile',
}


def check(root: Path | None = None) -> tuple[list[str], int]:
    """Returns (failures, clients_compared). Root is injectable so the sibling
    test can build a fixture tree rather than depend on the real submodules."""
    root = root if root is not None else DEFAULT_ROOT
    extractor = root / 'spike/client-contract/extract_client_contracts.py'
    if not extractor.is_file():
        extractor = DEFAULT_ROOT / 'spike/client-contract/extract_client_contracts.py'
    failures = []
    checked = 0
    for name, relative in CLIENT_DIRS.items():
        path = root / relative
        committed = root / f'spike/client-contract/{name}-contracts.json'
        if not (path / 'lib/core/network/api_constants.dart').is_file():
            print(f'  {name}: client checkout absent -- skipped (submodule not initialised)')
            continue
        if not committed.is_file():
            failures.append(f'{committed.relative_to(root)} is missing but {name} is checked out')
            continue
        fresh = subprocess.run([sys.executable, str(extractor), str(path)],
                               capture_output=True, text=True)
        if fresh.returncode != 0:
            failures.append(f'{name}: the extractor failed: {fresh.stderr.strip()[:200]}')
            continue
        got = json.loads(fresh.stdout)
        want = json.loads(committed.read_text())
        checked += 1
        for field in ('endpoints_declared', 'endpoints_referenced', 'declared_not_referenced'):
            if got[field] != want[field]:
                failures.append(f'{name}.{field}: committed {want[field]!r}, client source gives {got[field]!r}')
        got_calls = {(x['path'], x['http_method'], x['model']) for x in got['calls']}
        want_calls = {(x['path'], x['http_method'], x['model']) for x in want['calls']}
        for extra in sorted(got_calls - want_calls):
            failures.append(f'{name}: client calls {extra} which the committed contract does not have')
        for gone in sorted(want_calls - got_calls):
            failures.append(f'{name}: committed contract has {gone} which the client no longer calls')
        if set(got['models']) != set(want['models']):
            added = sorted(set(got['models']) - set(want['models']))
            removed = sorted(set(want['models']) - set(got['models']))
            failures.append(f'{name}: parsers changed (added {added[:5]}, removed {removed[:5]})')
        else:
            print(f'  {name}: {len(got["calls"])} calls, {len(got["models"])} parsers -- matches')

    return failures, checked


def main() -> int:
    failures, checked = check()
    if failures:
        print()
        print('client contract drift:')
        for line in failures:
            print(f'  - {line}')
        print()
        print('Regenerate with:')
        print('  cd spike/client-contract && python3 extract_client_contracts.py \\')
        print('      ../../flutter-integration/workin_desktop > desktop-contracts.json')
        print('and re-run the report builder; the PHP/Java columns need both stacks up.')
        return 1
    if checked == 0:
        print('no client checkout present; nothing compared')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())

#!/usr/bin/env python3
"""Does the committed client contract still match the client source, and do the
committed reports still match that contract?

Two halves, because they need different things and fail in different ways:

  contract vs CLIENT SOURCE   needs the client checkout. The Flutter clients are
                              submodules on SSH URLs outside this organisation,
                              so a workflow token cannot fetch them and CI
                              normally runs without them.

  reports  vs CONTRACT        needs nothing but this repository, so it runs
                              everywhere, including CI.

That split is the point. An earlier version only did the first half and
therefore did nothing at all in CI -- it skipped both clients and exited 0,
which is worse than not running, because the required check looked satisfied.
The second half is what gives CI something real to enforce: the coverage
tables, the unused-endpoint list and the download-endpoint footer are all
derived from the committed contract, so a regenerated contract with a stale
report is caught without any client present.

What neither half checks, stated rather than implied: the PHP/Java columns of
the reports. Those need both stacks running and a seeded database, which CI has
not; the reports name the run that produced them and regenerating them is a
documented manual step.
"""
import json
import re
import subprocess
import sys
from pathlib import Path

DEFAULT_ROOT = Path(__file__).resolve().parents[1]
CLIENT_DIRS = {
    'desktop': 'flutter-integration/workin_desktop',
    'mobile': 'flutter-integration/workin_mobile',
}


def _normalised_calls(doc):
    """Every deterministic field of every call, keyed by path+method.

    Projecting to (path, method, model) was not enough: a client can change the
    body or query keys it sends, or become multipart, or change its response
    type, while keeping the same path, method and model name -- and those are
    exactly the request semantics this inventory exists to track.
    """
    out = {}
    for call in doc['calls']:
        key = f"{call['http_method']} {call['path']}"
        out[key] = {
            'model': call.get('model'),
            'multipart': call.get('multipart'),
            'response_type': call.get('response_type'),
            'body_keys': sorted(call.get('body_keys') or []),
            'query_keys': sorted(call.get('query_keys') or []),
            'parameters_class': call.get('parameters_class'),
        }
    return out


def _normalised_models(doc):
    """Every field a parser reads, with the semantics that decide the verdict.

    A parser can keep its name and change the key it reads, the accessor, the
    default, the nesting or the branch guard -- each of which changes what the
    client does with a response.
    """
    out = {}
    for name, spec in doc['models'].items():
        out[name] = [
            {
                'field': f.get('field'),
                'accessor': f.get('accessor'),
                'key': f.get('key'),
                'bang': f.get('bang'),
                'default': f.get('default'),
                'nested': f.get('nested'),
                'guard': f.get('guard'),
                'receiver': f.get('receiver'),
            }
            for f in spec['fields']
        ]
    return out


def _compare(name, got, want, failures):
    for field in ('endpoints_declared', 'endpoints_referenced', 'declared_not_referenced'):
        if got[field] != want[field]:
            failures.append(f'{name}.{field}: committed {want[field]!r}, client source gives {got[field]!r}')

    got_calls, want_calls = _normalised_calls(got), _normalised_calls(want)
    for key in sorted(set(got_calls) - set(want_calls)):
        failures.append(f'{name}: client calls {key!r}, which the committed contract does not have')
    for key in sorted(set(want_calls) - set(got_calls)):
        failures.append(f'{name}: committed contract has {key!r}, which the client no longer calls')
    for key in sorted(set(got_calls) & set(want_calls)):
        if got_calls[key] != want_calls[key]:
            changed = [f for f in got_calls[key] if got_calls[key][f] != want_calls[key][f]]
            failures.append(f'{name}: {key!r} changed ({", ".join(changed)})')

    got_models, want_models = _normalised_models(got), _normalised_models(want)
    for parser in sorted(set(got_models) ^ set(want_models)):
        side = 'client source' if parser in got_models else 'committed contract'
        failures.append(f'{name}: parser {parser!r} exists only in the {side}')
    for parser in sorted(set(got_models) & set(want_models)):
        if got_models[parser] != want_models[parser]:
            failures.append(f'{name}: parser {parser!r} reads different fields than the committed contract')


REPORT_ROWS = re.compile(r'^\| API endpoint constants declared by the client \| (\d+) \|', re.M)


def _check_report(name, contract, root, failures):
    """The report's own numbers, against the contract it was generated from."""
    report = root / f'spike/client-contract/{name.upper()}-CONTRACT-REPORT.md'
    if not report.is_file():
        failures.append(f'{report.relative_to(root)} is missing but {name}-contracts.json is committed')
        return
    text = report.read_text()
    expectations = {
        'API endpoint constants declared by the client': contract['endpoints_declared'],
        'Referenced from client source (reachable)': contract['endpoints_referenced'],
        'Distinct paths the data source actually calls': len({c['path'] for c in contract['calls']}),
        'Contracts statically derived from client source': len(contract['calls']),
        'Client parsers extracted': len(contract['models']),
    }
    for label, expected in expectations.items():
        found = re.search(r'^\| ' + re.escape(label) + r' \| \**(\d+)\** \|', text, re.M)
        if not found:
            failures.append(f'{report.name}: no row for {label!r}')
        elif int(found.group(1)) != expected:
            failures.append(f'{report.name}: {label!r} says {found.group(1)}, contract gives {expected}')
    for endpoint in contract['declared_not_referenced']:
        if f'`{endpoint}`' not in text:
            failures.append(f'{report.name}: does not list the unreferenced endpoint {endpoint}')
    bytes_calls = [c for c in contract['calls'] if c.get('response_type') == 'bytes']
    if bytes_calls and f'the {len(bytes_calls)} `ResponseType.bytes`' not in text:
        failures.append(f'{report.name}: the ResponseType.bytes count does not match the contract '
                        f'({len(bytes_calls)} such calls)')


def check(root: Path | None = None) -> tuple[list[str], int]:
    """Returns (failures, clients_compared_against_source). Root is injectable so
    the sibling test can build a fixture tree rather than depend on submodules."""
    root = root if root is not None else DEFAULT_ROOT
    extractor = root / 'spike/client-contract/extract_client_contracts.py'
    if not extractor.is_file():
        extractor = DEFAULT_ROOT / 'spike/client-contract/extract_client_contracts.py'
    failures: list[str] = []
    compared = 0
    for name, relative in CLIENT_DIRS.items():
        path = root / relative
        committed = root / f'spike/client-contract/{name}-contracts.json'
        client_present = (path / 'lib/core/network/api_constants.dart').is_file()
        if not committed.is_file():
            if client_present:
                failures.append(f'{committed.relative_to(root)} is missing but {name} is checked out')
            continue

        want = json.loads(committed.read_text())
        _check_report(name, want, root, failures)

        if not client_present:
            print(f'  {name}: report matches the committed contract; client checkout absent, '
                  f'so the contract itself was not re-derived')
            continue
        fresh = subprocess.run([sys.executable, str(extractor), str(path)],
                               capture_output=True, text=True)
        if fresh.returncode != 0:
            failures.append(f'{name}: the extractor failed: {fresh.stderr.strip()[:200]}')
            continue
        _compare(name, json.loads(fresh.stdout), want, failures)
        compared += 1
        print(f'  {name}: {len(want["calls"])} calls, {len(want["models"])} parsers, '
              f'report and client source both match')
    return failures, compared


def main() -> int:
    failures, compared = check()
    if failures:
        print()
        print('client contract drift:')
        for line in failures:
            print(f'  - {line}')
        print()
        print('Regenerate with:')
        print('  cd spike/client-contract && python3 extract_client_contracts.py \\')
        print('      ../../flutter-integration/workin_desktop > desktop-contracts.json')
        print('  python3 build_report.py desktop')
        print('The PHP/Java columns need both stacks up; see that directory\'s README.')
        return 1
    if compared == 0:
        print('client checkouts absent; the contracts were checked against the reports only')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())

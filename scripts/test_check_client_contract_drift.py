#!/usr/bin/env python3
"""Fixture-based regression tests for check_client_contract_drift.py.

The checker guards committed evidence, so the cases that matter are the ones
where it must FAIL and the one where it must decline to judge: a silently
passing drift check is worse than none, and so is one that reports success
against a client checkout that is not there.
"""
import json
import subprocess
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from check_client_contract_drift import check  # noqa: E402

ROOT = Path(__file__).resolve().parents[1]
EXTRACTOR = ROOT / 'spike/client-contract/extract_client_contracts.py'

CONSTANTS = """
class ApiConstants {
  static const String baseUrl = 'https://example.invalid/apis/api/';
  static const String listEndpoint = 'things/list';
  static const String deadEndpoint = 'things/unused';
  static const String dataKey = "data";
  static const String idKey = "id";
}
"""
DATA_SOURCE = """
class RemoteDataSource {
  Future<ThingsResponse> getThings() async {
    var response = await HttpHelper.sendRequest(
      requestMethod: RequestMethod.get,
      endPoint: ApiConstants.listEndpoint,
    );
    return ThingsResponse.fromJson(response);
  }
}
"""
MODEL = """
class ThingsResponse {
  late final int id;
  ThingsResponse.fromJson(Map<String, dynamic> json) {
    id = json.getInt(ApiConstants.idKey);
  }
}
"""


def build_client(root: Path, name: str, data_source: str = DATA_SOURCE) -> None:
    base = root / 'flutter-integration' / f'workin_{name}'
    (base / 'lib/core/network').mkdir(parents=True, exist_ok=True)
    (base / 'lib/data/data_source/remote').mkdir(parents=True, exist_ok=True)
    (base / 'lib/data/response').mkdir(parents=True, exist_ok=True)
    (base / 'lib/domain').mkdir(parents=True, exist_ok=True)
    (base / 'lib/core/network/api_constants.dart').write_text(CONSTANTS)
    (base / 'lib/data/data_source/remote/remote_data_source.dart').write_text(data_source)
    (base / 'lib/data/response/things_response.dart').write_text(MODEL)


REPORT = """# {title} client contract conformance

| | count |
|---|---|
| API endpoint constants declared by the client | {declared} |
| Referenced from client source (reachable) | {referenced} |
| Distinct paths the data source actually calls | {paths} |
| Contracts statically derived from client source | {calls} |
| Client parsers extracted | {parsers} |
| **Contracts replayed against PHP and Java** | **0** |
{unused}
"""


def commit_report(root: Path, name: str, **overrides) -> None:
    """The generated report, as build_report.py would write its number rows."""
    contract = json.loads((root / f'spike/client-contract/{name}-contracts.json').read_text())
    values = {
        'title': name.capitalize(),
        'declared': contract['endpoints_declared'],
        'referenced': contract['endpoints_referenced'],
        'paths': len({c['path'] for c in contract['calls']}),
        'calls': len(contract['calls']),
        'parsers': len(contract['models']),
        'unused': '\n'.join(f'- `{e}`' for e in contract['declared_not_referenced']),
    }
    values.update(overrides)
    (root / f'spike/client-contract/{name.upper()}-CONTRACT-REPORT.md').write_text(
        REPORT.format(**values))


def commit_contract(root: Path, name: str) -> None:
    out = subprocess.run([sys.executable, str(EXTRACTOR),
                          str(root / 'flutter-integration' / f'workin_{name}')],
                         capture_output=True, text=True)
    assert out.returncode == 0, out.stderr
    target = root / 'spike/client-contract'
    target.mkdir(parents=True, exist_ok=True)
    (target / f'{name}-contracts.json').write_text(out.stdout)


def baseline(root: Path) -> None:
    """Both clients committed, so a case exercises the field under test rather
    than the unrelated 'the other contract is missing' rule."""
    for name in ('desktop', 'mobile'):
        build_client(root, name)
        commit_contract(root, name)
        commit_report(root, name)


def case(label, root, expect_failure_containing=None, expect_checked=None):
    failures, checked = check(root)
    if expect_failure_containing is None:
        ok = not failures
        detail = f'expected no failures, got {failures[:2]}'
    else:
        ok = any(expect_failure_containing in f for f in failures)
        detail = f'expected a failure containing {expect_failure_containing!r}, got {failures[:3]}'
    if ok and expect_checked is not None and checked != expect_checked:
        ok, detail = False, f'expected {expect_checked} client(s) compared, got {checked}'
    print(('  ok    ' if ok else '  FAIL  ') + label + ('' if ok else f'\n          {detail}'))
    return ok


def main() -> int:
    results = []
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        baseline(root)
        results.append(case('a contract matching its client source passes', root, None, 2))

        # A client that gained a call the committed contract does not have.
        drifted = DATA_SOURCE.replace('}\n', """
  Future<ThingsResponse> getOne() async {
    var response = await HttpHelper.sendRequest(
      requestMethod: RequestMethod.get,
      endPoint: ApiConstants.deadEndpoint,
    );
    return ThingsResponse.fromJson(response);
  }
}
""", 1)
        build_client(root, 'desktop', drifted)
        results.append(case('a new client call is reported', root, 'which the committed contract does not have'))

        # And the reverse: the committed contract keeps a call the client dropped.
        commit_contract(root, 'desktop')
        commit_report(root, 'desktop')
        build_client(root, 'desktop', DATA_SOURCE)
        results.append(case('a removed client call is reported', root, 'no longer calls'))

    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        baseline(root)
        (root / 'flutter-integration/workin_desktop/lib/data/response/things_response.dart').write_text(
            MODEL.replace('class ThingsResponse', 'class RenamedResponse')
                 .replace('ThingsResponse.fromJson', 'RenamedResponse.fromJson'))
        results.append(case('a renamed parser is reported', root, "exists only in the"))

    with tempfile.TemporaryDirectory() as tmp:
        # A field the projection used to ignore: same path, method and model.
        root = Path(tmp)
        baseline(root)
        changed = DATA_SOURCE.replace('requestMethod: RequestMethod.get,',
                                      'requestMethod: RequestMethod.get,\n      query: {ApiConstants.idKey: 1},')
        build_client(root, 'desktop', changed)
        results.append(case('a changed request parameter is reported', root, 'changed (query_keys)'))

    with tempfile.TemporaryDirectory() as tmp:
        # And a parser that keeps its name while reading something else.
        root = Path(tmp)
        baseline(root)
        (root / 'flutter-integration/workin_desktop/lib/data/response/things_response.dart').write_text(
            MODEL.replace('json.getInt(ApiConstants.idKey)', 'json.getIntOrNull(ApiConstants.idKey)'))
        results.append(case('a parser reading with a different accessor is reported',
                            root, 'reads different fields'))

    with tempfile.TemporaryDirectory() as tmp:
        # The half that has to work with no client checkout at all.
        root = Path(tmp)
        baseline(root)
        commit_report(root, 'desktop', calls=99)
        import shutil
        shutil.rmtree(root / 'flutter-integration')
        results.append(case('a stale report is reported even with no client checkout',
                            root, 'says 99'))

    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        baseline(root)
        (root / 'spike/client-contract/DESKTOP-CONTRACT-REPORT.md').unlink()
        import shutil
        shutil.rmtree(root / 'flutter-integration')
        results.append(case('a missing report is reported even with no client checkout',
                            root, 'is missing but'))

    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        build_client(root, 'desktop')
        results.append(case('a checked-out client with no committed contract fails',
                            root, 'the committed contract is required'))

    with tempfile.TemporaryDirectory() as tmp:
        # The shape CI actually runs: contracts and reports committed, no
        # submodules. The contract half is skipped and reported as such; the
        # report half still runs.
        root = Path(tmp)
        baseline(root)
        import shutil
        shutil.rmtree(root / 'flutter-integration')
        results.append(case('with no client checkout, the report half still runs and passes',
                            root, None, 0))

    with tempfile.TemporaryDirectory() as tmp:
        # And a repository with no contracts at all is broken, not "nothing to do".
        results.append(case('a repository missing both contracts fails',
                            Path(tmp), 'the committed contract is required'))

    print()
    if not all(results):
        print(f'{results.count(False)} case(s) failed.')
        return 1
    print('client-contract drift check: all cases pass.')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())

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


def commit_contract(root: Path, name: str) -> None:
    out = subprocess.run([sys.executable, str(EXTRACTOR),
                          str(root / 'flutter-integration' / f'workin_{name}')],
                         capture_output=True, text=True)
    assert out.returncode == 0, out.stderr
    target = root / 'spike/client-contract'
    target.mkdir(parents=True, exist_ok=True)
    (target / f'{name}-contracts.json').write_text(out.stdout)


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
        build_client(root, 'desktop')
        commit_contract(root, 'desktop')
        results.append(case('a contract matching its client source passes', root, None, 1))

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
        build_client(root, 'desktop', DATA_SOURCE)
        results.append(case('a removed client call is reported', root, 'no longer calls'))

    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        build_client(root, 'desktop')
        commit_contract(root, 'desktop')
        (root / 'flutter-integration/workin_desktop/lib/data/response/things_response.dart').write_text(
            MODEL.replace('class ThingsResponse', 'class RenamedResponse')
                 .replace('ThingsResponse.fromJson', 'RenamedResponse.fromJson'))
        results.append(case('a renamed parser is reported', root, 'parsers changed'))

    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        build_client(root, 'desktop')
        results.append(case('a checked-out client with no committed contract fails',
                            root, 'is missing but'))

    with tempfile.TemporaryDirectory() as tmp:
        # No client checkout at all: the submodules are not always initialised.
        results.append(case('an absent client checkout is skipped, not passed',
                            Path(tmp), None, 0))

    print()
    if not all(results):
        print(f'{results.count(False)} case(s) failed.')
        return 1
    print('client-contract drift check: all cases pass.')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())

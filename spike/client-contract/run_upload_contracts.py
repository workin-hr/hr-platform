#!/usr/bin/env python3
"""The client's multipart request shapes, replayed against both stacks.

The part NAMES come from the client's own parameter classes, because that is
what reaches the server -- `HttpHelper.multipartRequest` uses each body-map key
as the multipart field name. Substituting the name the server reads would test
a request the client never sends, and would have hidden the
`company/upload_commercial_reg` mismatch this file records.
"""
from __future__ import annotations

import json
import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from client_parser import Evaluator                              # noqa: E402
from run_contract_check import PHP, JAVA, decode, request, sql   # noqa: E402

HARNESS = Path(__file__).resolve().parents[1] / 'parity-harness'


def reseed() -> bool:
    """A failed reseed leaves the previous case's writes in place, so the next
    case would compare contaminated state and report whatever that happens to
    give -- a reproducible-looking parity row from stale data. Same guard the
    mutation sweep and run_mutation_contracts.py already apply."""
    return subprocess.run(['./seed-two.sh'], cwd=HARNESS, capture_output=True).returncode == 0
FIXTURES = HARNESS / 'fixtures'


def curl(base, path, token, parts, fields):
    args = ['curl', '-s', '-o', '/tmp/upload-contract.json', '-w', '%{http_code}',
            '-X', 'POST', f'{base}/{path}', '-H', f'Authorization: Bearer {token}',
            '-H', 'Accept-Language: en']
    for name, (fixture, filename) in parts.items():
        args += ['-F', f'{name}=@{FIXTURES / fixture};filename={filename}']
    for name, value in fields.items():
        args += ['-F', f'{name}={value}']
    status = subprocess.run(args, capture_output=True, text=True).stdout.strip()
    body = Path('/tmp/upload-contract.json').read_text(errors='replace')
    return int(status or 0), body


def main() -> None:
    contracts = json.loads(Path('desktop-contracts.json').read_text())
    evaluator = Evaluator(contracts['models'])
    by_path = {c['path']: c for c in contracts['calls']}

    if not reseed():
        raise SystemExit('FATAL: could not seed both databases; nothing below would mean anything.')
    employee = sql('workin', 'SELECT id FROM workin.employees WHERE company_id=214 ORDER BY id LIMIT 1')

    cases = [
        ('company/upload_logo', {'logo': ('parity.png', 'logo.png')}, {}, 200),
        # The client sends `logo` here; the endpoint reads `file`. Kept as the
        # client sends it -- see the verdict.
        ('company/upload_commercial_reg', {'logo': ('parity.pdf', 'reg.pdf')}, {}, 200),
        ('employees/upload_photo', {'photo': ('parity.png', 'photo.png')}, {}, 200),
        ('employees/analyze_excel', {'file': ('employees-filled.xlsx', 'employees.xlsx')}, {}, 200),
        ('attendance/analyze_excel', {'file': ('attendance-punches.xlsx', 'punches.xlsx')}, {}, 200),
        ('leave_balances/analyze_excel', {'file': ('leave_balances-template.xlsx', 'leave.xlsx')},
         {'year': 2026}, 200),
        ('employee_docs/upload', {'file': ('parity.pdf', 'contract.pdf')},
         {'employee_id': employee, 'doc_type': 'contract'}, 201),
    ]

    results = []
    for path, parts, fields, expect in cases:
        call = by_path.get(path)
        if not reseed():
            results.append({'path': path,
                            'skipped': 'reseed failed; state contaminated, verdict withheld'})
            print(f'  {path:34} RESEED-FAILED (verdict withheld)')
            continue
        tokens = {}
        for label, base in (('php', PHP), ('java', JAVA)):
            status, text = request(base, 'POST', 'auth/login_desktop', None, body={
                'phone': sql('workin', 'SELECT phone FROM workin.companies WHERE id=214'),
                'password': 'harness-only-Pass123!', 'login_as': 'company', 'country_code': '+20'})
            tokens[label] = ((decode(status, text) or {}).get('data') or {}).get('token')
        query = {'id': employee} if 'id' in (call['query_keys'] if call else []) else {}
        row = {'path': path, 'client_parts': list(parts), 'model': call['model'] if call else None,
               'expect': expect}
        for label, base in (('php', PHP), ('java', JAVA)):
            url = base + ('' if not query else '')
            target = path + (f'?id={employee}' if query else '')
            status, text = curl(base, target, tokens[label], parts, fields)
            payload = decode(status, text)
            if isinstance(payload, dict) and payload.get('client_action') == 'ServerException':
                row[label] = {'status': status, 'verdict': 'ServerException',
                              'raw': payload['raw'][:160]}
            else:
                row[label] = {'status': status, **evaluator.parse(row['model'], payload)}
        row['status_as_expected'] = row['php']['status'] == expect
        same = row['php']['verdict'] == row['java']['verdict'] and row['php']['status'] == row['java']['status']
        note = '' if row['status_as_expected'] else '  <-- not the declared status'
        print(f"  {path:34} parts={list(parts)} php={row['php']['status']}/{row['php']['verdict']:16} "
              f"java={row['java']['status']}/{row['java']['verdict']:16} same={same}{note}")
        results.append(row)
    Path('desktop-upload-results.json').write_text(json.dumps(results, indent=1))


if __name__ == '__main__':
    main()

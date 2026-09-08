#!/usr/bin/env python3
"""The mobile client's mutation shapes, replayed against both stacks.

Mobile mutations are employee-scoped, and the role matters: requests/create is
requireAuth([EMPLOYEE]), so the company-admin fixture is refused 403 before any
logic. Each case therefore names the actor it needs.

`attendance/check_out` needs the caller to be checked in, and the seeded open
record belongs to someone else on purpose, so that case performs its own
check-in as SETUP on both stacks first -- otherwise it answers "not checked in"
identically on both and verifies nothing.
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
ADMIN = '+201999000002'
EMPLOYEE = '+201999000003'


def login(base: str, phone: str):
    status, text = request(base, 'POST', 'auth/login_employee', None,
                           body={'phone': phone, 'password': 'harness-only-Pass123!'})
    payload = decode(status, text)
    return ((payload or {}).get('data') or {}).get('token') if isinstance(payload, dict) else None


def main() -> None:
    contracts = json.loads(Path('mobile-contracts.json').read_text())
    evaluator = Evaluator(contracts['models'])
    by_path = {c['path']: c for c in contracts['calls']}

    subprocess.run(['./seed-two.sh'], cwd=HARNESS, capture_output=True)
    # 'app' is what the client sends -- AttendanceMethodEnum is {app, excel, qr}
    # and check_in_usecase hard-codes 'app'. An invented value would test a
    # request no client makes: 'gps' answers 200 on PHP (which stores an empty
    # method) and 500 on Java, a divergence that exists outside the contract and
    # is not reachable from either client.
    coords = {'latitude': 30.0211667, 'longitude': 31.4545278, 'method': 'app'}
    request_type = sql('workin', 'SELECT id FROM workin.request_types WHERE company_id=214 ORDER BY id LIMIT 1')
    notification = sql('workin', "SELECT id FROM workin.notifications WHERE to_employee_id=999002 ORDER BY id LIMIT 1")

    cases = [
        ('attendance/check_in', ADMIN, {}, dict(coords), 200, None),
        ('attendance/check_out', ADMIN, {}, dict(coords), 200, ('attendance/check_in', dict(coords))),
        ('requests/create', EMPLOYEE, {}, {'request_type_id': int(request_type),
                                           'from_date': '2026-09-20', 'to_date': '2026-09-21'}, 201, None),
        ('complaints/create', ADMIN, {}, {'message': 'contract check', 'name': 'Parity',
                                          'phone': '01099911009', 'email': 'p@example.com'}, 200, None),
        ('notifications/mark_read', ADMIN, {'id': notification}, None, 200, None),
        ('notifications/delete', ADMIN, {'id': notification}, None, 200, None),
        ('profile/change_password', ADMIN, {}, {'old_password': 'harness-only-Pass123!',
                                                'new_password': 'harness-only-Pass456!'}, 200, None),
        ('auth/lookup_company', None, {}, {'company_code': 'EGHECH'}, 200, None),
        ('auth/forgot_password', None, {}, {'phone': ADMIN, 'type': 'employee'}, 200, None),
        ('auth/resend_otp', None, {}, {'phone': ADMIN}, 200, None),
    ]

    results = []
    for path, actor, query, body, expect, setup in cases:
        call = by_path.get(path)
        if not reseed():
            results.append({'path': path,
                            'skipped': 'reseed failed; state contaminated, verdict withheld'})
            print(f'  {path:34} RESEED-FAILED (verdict withheld)')
            continue
        row = {'path': path, 'method': call['http_method'], 'model': call['model'],
               'actor': actor, 'expect': expect, 'query': query}
        for label, base in (('php', PHP), ('java', JAVA)):
            token = login(base, actor) if actor else None
            if setup:
                request(base, 'POST', setup[0], token, body=setup[1])
            status, text = request(base, call['http_method'], path, token,
                                   query=query, body=body)
            payload = decode(status, text)
            if isinstance(payload, dict) and payload.get('client_action') == 'ServerException':
                row[label] = {'status': status, 'verdict': 'ServerException',
                              'raw': payload['raw'][:160]}
            else:
                row[label] = {'status': status, **evaluator.parse(call['model'], payload)}
        row['status_as_expected'] = row['php']['status'] == expect
        note = '' if row['status_as_expected'] else '  <-- not the declared status'
        print(f"  {path:28} php={row['php']['status']}/{row['php']['verdict']:16} "
              f"java={row['java']['status']}/{row['java']['verdict']:16}"
              f" same={row['php']['verdict']==row['java']['verdict'] and row['php']['status']==row['java']['status']}{note}")
        results.append(row)
    Path('mobile-mutation-results.json').write_text(json.dumps(results, indent=1))


if __name__ == '__main__':
    main()

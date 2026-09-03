#!/usr/bin/env python3
"""Replay the client's own request shapes against PHP and Java, and judge the
answers with the client's own parser.

This is a CONTRACT layer, not a runtime one. It proves what the app's parsers
would do with each stack's bytes. It proves nothing about rendering,
navigation, file pickers, downloads to disk or OS integration -- see README.md.

Authentication uses the request the desktop client itself issues:
`auth/login_desktop` with `login_as: company`, built from the client's own
parameter class.
"""
from __future__ import annotations

import json
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from client_parser import Evaluator  # noqa: E402

PHP = 'http://localhost:18080/apis/api'
JAVA = 'http://localhost:18081/apis/api'
DB = 'parity-harness-db-1'


def sql(database: str, statement: str) -> str:
    out = subprocess.run(
        ['docker', 'exec', '-i', DB, 'mariadb', '-uroot', '-pparity', '-N', '-B',
         '-e', statement],
        capture_output=True, text=True).stdout
    rows = [r for r in out.strip().splitlines() if r and not r.startswith('Warning')]
    return rows[0] if rows else ''


def request(base: str, method: str, path: str, token: str | None,
            query: dict | None = None, body: dict | None = None):
    url = f'{base}/{path}'
    if query:
        url += '?' + urllib.parse.urlencode(query)
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header('Content-Type', 'application/json')
    req.add_header('Accept-Language', 'en')
    if token:
        req.add_header('Authorization', f'Bearer {token}')
    try:
        with urllib.request.urlopen(req, timeout=30) as response:
            return response.status, response.read().decode('utf-8', 'replace')
    except urllib.error.HTTPError as error:
        return error.code, error.read().decode('utf-8', 'replace')
    except Exception as error:                      # noqa: BLE001
        return 0, f'<transport: {error}>'


def decode(status: int, text: str):
    """HttpHelper._handleResponse, as the client applies it."""
    trimmed = text.strip()
    if not 200 <= status < 300:
        return {'client_action': 'ServerException', 'status': status,
                'logs_out': status == 401, 'raw': trimmed}
    if trimmed == '':
        return {}                                   # the client substitutes {}
    if trimmed.startswith('{') and trimmed.endswith('}'):
        return json.loads(trimmed)
    if trimmed.startswith('[') and trimmed.endswith(']'):
        return {'results': json.loads(trimmed)}     # a bare list is wrapped
    return trimmed


def main() -> None:
    contracts = json.loads(Path(sys.argv[1]).read_text())
    evaluator = Evaluator(contracts['models'])
    out_path = sys.argv[2] if len(sys.argv) > 2 else 'desktop-read-results.json'

    # Each client authenticates the way it actually does: the desktop app opens
    # a COMPANY session through auth/login_desktop, the mobile app an EMPLOYEE
    # session through auth/login_employee. Using one client's session for the
    # other would test a request neither of them sends.
    mobile = any(c['path'] == 'auth/login_employee' for c in contracts['calls']) and \
        not any(c['path'] == 'auth/login_desktop' for c in contracts['calls'])
    if mobile:
        auth_path, auth_model = 'auth/login_employee', 'EmployeeAuthResponse'
        credentials = {'phone': '+201999000002', 'password': 'harness-only-Pass123!'}
    else:
        auth_path, auth_model = 'auth/login_desktop', 'CompanyAuthResponse'
        credentials = {'phone': sql('workin', "SELECT phone FROM workin.companies WHERE id=214"),
                       'password': 'harness-only-Pass123!',
                       'login_as': 'company', 'country_code': '+20'}
    if auth_model not in contracts['models']:
        auth_model = next((c['model'] for c in contracts['calls']
                           if c['path'] == auth_path and c['model']), None)

    tokens = {}
    for label, base in (('php', PHP), ('java', JAVA)):
        status, text = request(base, 'POST', auth_path, None, body=credentials)
        payload = decode(status, text)
        verdict = evaluator.parse(auth_model, payload) if auth_model else {'verdict': 'no model'}
        token = (payload.get('data') or {}).get('token') if isinstance(payload, dict) else None
        print(f'  {auth_path:20} {label:4} status={status} '
              f'client-parse={verdict["verdict"]} token={"yes" if token else "NO"}')
        if not token:
            raise SystemExit(f'FATAL: {label} did not return a token; '
                             f'the client could not authenticate. Body: {text[:200]}')
        tokens[label] = token

    # A REAL id per endpoint, resolved from the seeded snapshot.
    #
    # Sending one branch id to every `/one` turned 22 endpoints into matching
    # 404s. Two stacks agreeing on "not found" says nothing about whether the
    # client can read the record, which is the whole question here -- the same
    # rule the mutation sweep applies: a matching refusal is not coverage.
    company = 214
    def one(table, where='company_id=214', column='id'):
        return sql('workin', f"SELECT {column} FROM workin.{table} WHERE {where} ORDER BY {column} LIMIT 1")

    employee = one('employees')
    branch = one('branches')
    per_path_id = {
        'branches/one': branch,
        'departments/one': one('departments'),
        'shifts/one': one('shifts'),
        'job_titles/one': one('job_titles'),
        'employees/one': employee,
        'employees/delete_preview': employee,
        'attendance/one': one('attendance', f'employee_id IN (SELECT id FROM workin.employees WHERE company_id={company})'),
        'attendance/employee_monthly_attendance': employee,
        'assets/one': one('assets'),
        'workforce_planning/one': one('workforce_planning'),
        'request_types/one': one('request_types'),
        'requests/one': one('requests', f'employee_id IN (SELECT id FROM workin.employees WHERE company_id={company})'),
        'payroll_batches/one': one('payroll_batches'),
        'payroll_batches/stats': one('payroll_batches'),
        'payslips/one': one('payslips', f'batch_id IN (SELECT id FROM workin.payroll_batches WHERE company_id={company})'),
        'penalties/one': one('penalties', f'employee_id IN (SELECT id FROM workin.employees WHERE company_id={company})'),
        'advances/one': one('advances', f'employee_id IN (SELECT id FROM workin.employees WHERE company_id={company})'),
        'leave_balances/one': one('leave_balance', f'employee_id IN (SELECT id FROM workin.employees WHERE company_id={company})'),
        'salary_contracts/one': one('salary_contracts', f'employee_id IN (SELECT id FROM workin.employees WHERE company_id={company})'),
        # Scoped to the caller's inbox, so it must be a COMPANY-recipient row --
        # the seeded employee-recipient notification answers "not found" here.
        'notifications/one': one('notifications', f"company_id={company} AND recipient_kind='company'"),
        'company_official_holidays/one': one('company_official_holidays'),
    }
    per_path_extra = {
        'company_settings/one': {'setting_definition_id': '1'},
        'app_content/one': {'content_key': sql('workin', "SELECT content_key FROM workin.app_content ORDER BY id LIMIT 1")},
    }
    # profile/employee is employee-scoped: a company session is refused 401,
    # and a 401 makes the client log the user out. The desktop client reaches it
    # in its HR/employee session, so that is the session used for it.
    #
    # Minted ONCE, and reused when the primary session is already this employee.
    # auth/login_employee bumps token_version, so logging in a second time as
    # the same employee invalidates the first token -- the mobile pass then
    # answered "Signed in from another device" on 18 of 22 endpoints, which
    # looked like a parity result and was this harness invalidating itself.
    if mobile:
        employee_tokens = dict(tokens)
    else:
        employee_tokens = {}
        employee_credentials = {'phone': '+201999000002', 'password': 'harness-only-Pass123!'}
        for label, base in (('php', PHP), ('java', JAVA)):
            status, text = request(base, 'POST', 'auth/login_employee', None,
                                   body=employee_credentials)
            payload = decode(status, text)
            if isinstance(payload, dict):
                employee_tokens[label] = (payload.get('data') or {}).get('token')
    EMPLOYEE_SESSION = {'profile/employee'}

    ids = {
        'employee_id': employee,
        'branch_id': branch,
        'year': '2026',
        'month': '9',
    }

    results = []
    for call in contracts['calls']:
        if call['http_method'] != 'GET' or call['response_type'] != 'json':
            continue
        query = {k: ids[k] for k in call['query_keys'] if k in ids and ids[k]}
        if 'id' in call['query_keys'] and per_path_id.get(call['path']):
            query['id'] = per_path_id[call['path']]
        query.update({k: v for k, v in per_path_extra.get(call['path'], {}).items() if v})
        row = {'path': call['path'], 'model': call['model'],
               'method_name': call['method_name'], 'query': query}
        for label, base in (('php', PHP), ('java', JAVA)):
            session = (employee_tokens if call['path'] in EMPLOYEE_SESSION else tokens)[label]
            status, text = request(base, 'GET', call['path'], session, query=query)
            payload = decode(status, text)
            if isinstance(payload, dict) and payload.get('client_action') == 'ServerException':
                row[label] = {'status': status, 'verdict': 'ServerException',
                              'logs_out': payload['logs_out'], 'raw': payload['raw'][:200]}
            else:
                verdict = evaluator.parse(call['model'], payload)
                row[label] = {'status': status, **verdict, 'body': text[:4000]}
        results.append(row)
    Path(out_path).write_text(json.dumps(results, indent=1))
    print(f'\n{len(results)} GET contracts replayed -> {out_path}')


if __name__ == '__main__':
    main()

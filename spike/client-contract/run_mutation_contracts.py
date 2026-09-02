#!/usr/bin/env python3
"""The client's mutation request shapes, replayed against both stacks.

Same rules as the read pass: the client's parameter class supplies the body
keys, the client's parser judges the answer, and a matching refusal is not
treated as verification -- every case here declares the status it expects and is
reported as unverified if it does not get it.

Each case reseeds both databases first, exactly as the mutation sweep does: a
case that runs against the previous case's writes is measuring the harness.
"""
from __future__ import annotations

import json
import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from client_parser import Evaluator                      # noqa: E402
from run_contract_check import PHP, JAVA, decode, request, sql   # noqa: E402

HARNESS = Path(__file__).resolve().parents[1] / 'parity-harness'


def reseed() -> bool:
    return subprocess.run(['./seed-two.sh'], cwd=HARNESS, capture_output=True).returncode == 0


def main() -> None:
    contracts = json.loads(Path('desktop-contracts.json').read_text())
    evaluator = Evaluator(contracts['models'])
    by_path = {c['path']: c for c in contracts['calls'] if c['http_method'] != 'GET'}

    reseed()
    company = 214
    def one(table, where=f'company_id={company}', column='id'):
        return sql('workin', f'SELECT {column} FROM workin.{table} WHERE {where} ORDER BY {column} LIMIT 1')

    employee = one('employees')
    branch = one('branches')
    department = one('departments')
    shift = one('shifts')
    job_title = one('job_titles')
    emp_scope = f'employee_id IN (SELECT id FROM workin.employees WHERE company_id={company})'
    advance = one('advances', emp_scope)
    penalty = one('penalties', emp_scope)
    # The seeded DRAFT batch and OPEN penalty, not simply the first row: the
    # snapshot's first batch is finalized and its first penalty applied, so
    # calculate/finalize/update answered 400/403 and verified nothing.
    batch = one('payroll_batches', f"company_id={company} AND status='draft'") or one('payroll_batches')
    # `applied_to_payroll=0`: penalties/update answers 403 "already applied to
    # payroll" otherwise. The column is applied_to_payroll, not status.
    open_penalty = one('penalties', f'{emp_scope} AND applied_to_payroll=0')
    request_id = sql('workin', f'SELECT id FROM workin.requests WHERE {emp_scope} AND status IN ("pending") ORDER BY id LIMIT 1') or one('requests', emp_scope)
    attendance = one('attendance', emp_scope)

    # path, query, body, expected status, session
    cases = [
        ('attendance/create', {}, {'employee_id': int(employee), 'check_in': '2026-09-15 08:00:00',
                                   'check_out': '2026-09-15 17:00:00'}, 201, 'company'),
        ('attendance/update', {'id': attendance}, {'check_in': '2026-09-15 09:00:00'}, 200, 'company'),
        ('attendance/delete', {'id': attendance}, None, 200, 'company'),
        ('employees/create', {}, {'first_name': 'Contract', 'last_name': 'Check',
                                  'employee_code': '990500', 'branch_id': int(branch),
                                  'department_id': int(department), 'job_title_id': int(job_title),
                                  'shift_id': int(shift), 'expected_daily_hours': 8,
                                  'is_mobile_attendance_enabled': 1, 'basic': 5000}, 201, 'company'),
        ('employees/update', {'id': employee}, {'address': 'Contract check'}, 200, 'company'),
        ('employees/deactivate', {'id': employee}, None, 200, 'company'),
        ('employees/reactivate', {'id': employee}, None, 200, 'company'),
        ('requests/approve', {'id': request_id}, {'status': 'approved'}, 200, 'company'),
        ('requests/reject', {'id': request_id}, {'reply': 'contract check'}, 200, 'company'),
        ('advances/create', {}, {'employee_id': int(employee), 'amount': 500,
                                 'deduction_mode': 'single'}, 201, 'company'),
        ('advances/update', {'id': advance}, {'amount': 600}, 200, 'company'),
        ('advances/approve', {'id': advance}, None, 200, 'company'),
        ('advances/reject', {'id': advance}, {'rejection_reason': 'contract check'}, 200, 'company'),
        ('payroll_batches/create', {}, {'month': 11, 'year': 2026}, 201, 'company'),
        ('payroll_batches/calculate', {'id': batch}, None, 200, 'company'),
        ('payroll_batches/finalize', {'id': batch}, None, 200, 'company'),
        ('penalties/update', {'id': open_penalty or penalty}, {'reason': 'contract check'}, 200, 'company'),
    ]

    results = []
    for path, query, body, expect, session in cases:
        call = by_path.get(path)
        if call is None:
            results.append({'path': path, 'skipped': 'not called by this client'})
            continue
        if not reseed():
            results.append({'path': path, 'skipped': 'reseed failed'})
            continue
        tokens = {}
        for label, base in (('php', PHP), ('java', JAVA)):
            status, text = request(base, 'POST', 'auth/login_desktop', None, body={
                'phone': sql('workin', 'SELECT phone FROM workin.companies WHERE id=214'),
                'password': 'harness-only-Pass123!', 'login_as': 'company', 'country_code': '+20'})
            tokens[label] = ((decode(status, text) or {}).get('data') or {}).get('token')
        row = {'path': path, 'method': call['http_method'], 'model': call['model'],
               'expect': expect, 'query': query}
        for label, base in (('php', PHP), ('java', JAVA)):
            status, text = request(base, call['http_method'], path, tokens[label],
                                   query=query, body=body)
            payload = decode(status, text)
            if isinstance(payload, dict) and payload.get('client_action') == 'ServerException':
                row[label] = {'status': status, 'verdict': 'ServerException',
                              'raw': payload['raw'][:200]}
            else:
                row[label] = {'status': status, **evaluator.parse(call['model'], payload)}
        row['status_as_expected'] = row['php']['status'] == expect
        results.append(row)
        flag = '' if row['status_as_expected'] else '  <-- NOT the declared status; not verification'
        print(f"  {path:30} php={row['php']['status']}/{row['php']['verdict']:16} "
              f"java={row['java']['status']}/{row['java']['verdict']}{flag}")
    Path('desktop-mutation-results.json').write_text(json.dumps(results, indent=1))


if __name__ == '__main__':
    main()

#!/usr/bin/env python3
"""Write the client-contract coverage report from the recorded runs."""
import json
from pathlib import Path

c = json.loads(Path('desktop-contracts.json').read_text())
reads = json.loads(Path('desktop-read-results.json').read_text())
muts = json.loads(Path('desktop-mutation-results.json').read_text())
ups = json.loads(Path('desktop-upload-results.json').read_text())

rows = []
for r in reads:
    rows.append((r['path'], 'GET', r['model'], r['php'], r['java'], True))
for r in muts:
    if 'php' in r:
        rows.append((r['path'], r['method'], r['model'], r['php'], r['java'], r.get('status_as_expected')))
for r in ups:
    rows.append((r['path'], 'POST (multipart)', r['model'], r['php'], r['java'], r.get('status_as_expected')))

def verdict(php, java, declared_ok):
    if php['verdict'] != java['verdict'] or php['status'] != java['status']:
        return 'DIVERGES'
    if not declared_ok:
        return 'not verified'
    if php['verdict'] == 'ServerException':
        return 'refusal (not coverage)'
    if php.get('blank_fields') != java.get('blank_fields'):
        return 'DIVERGES (blank fields)'
    return 'compatible'

replayed = {r[0] for r in rows if r[5]}
callpaths = {x['path'] for x in c['calls']}
lines = []
w = lines.append
w('# Desktop client contract conformance')
w('')
w('**This is a contract layer, not a runtime one.** It proves what the desktop')
w("client's own parsers would do with each stack's bytes. It verifies **nothing**")
w('about rendering, navigation, the file picker, downloads to disk, OS')
w('integration, or any other runtime UI behaviour. The real application was not')
w('executed -- see "Why runtime is zero" below.')
w('')
w('## Coverage, with the denominators kept apart')
w('')
w('| | count |')
w('|---|---|')
w(f"| API endpoint constants declared by the client | {c['endpoints_declared']} |")
w(f"| Referenced from client source (reachable) | {c['endpoints_referenced']} |")
w(f"| Distinct paths the data source actually calls | {len(callpaths)} |")
w(f"| Contracts statically derived from client source | {len(c['calls'])} |")
w(f"| Client parsers extracted | {len(c['models'])} |")
w(f"| **Contracts replayed against PHP and Java** | **{len(replayed)}** |")
w('| **Runtime client verified** | **0** — blocked, see below |')
w('')
w(f"The {c['endpoints_declared']} declared constants are **not** the denominator.")
w(f"{c['endpoints_declared'] - c['endpoints_referenced']} are declared and never referenced:")
w('')
for name in c['declared_not_referenced']:
    w(f"- `{name}`")
w('')
w('## Verdicts')
w('')
w('| verdict | count |')
w('|---|---|')
from collections import Counter
counts = Counter(verdict(p, j, ok) for _, _, _, p, j, ok in rows)
for k, v in counts.most_common():
    w(f'| {k} | {v} |')
w('')
w('## endpoint -> parser -> PHP -> Java -> client verdict')
w('')
w('| endpoint | method | client parser | PHP | Java | verdict |')
w('|---|---|---|---|---|---|')
for path, method, model, php, java, ok in sorted(rows):
    w(f"| `{path}` | {method} | `{model}` | {php['status']} {php['verdict']} "
      f"| {java['status']} {java['verdict']} | {verdict(php, java, ok)} |")
w('')
w('## Findings')
w('')
w('### 1. No Java parity defect was found by this layer')
w('')
w('Across every replayed contract the client verdict is the same on both stacks:')
w('same status, same parse outcome, and the same set of fields that end up blank')
w('or defaulted. Nothing here asks for a Java change.')
w('')
w('### 2. `company/upload_commercial_reg` -- a client defect, not a parity defect')
w('')
w('The client sends the multipart part named **`logo`**')
w('(`EditCompanyCommercialRegParameters.toBodyMap` -> `ApiConstants.logoKey`,')
w('and `HttpHelper.multipartRequest` uses each body-map key as the field name).')
w('The endpoint reads `UploadMultipart::FILE`, i.e. **`file`**.')
w('')
w('Measured with the request the client actually sends:')
w('')
w('```text')
w('part `logo`   php 400 "No file uploaded"   java 400 "No file uploaded"')
w('part `file`   php 200 uploaded             java 200 uploaded')
w('```')
w('')
w('**Both stacks behave identically**, so the migration neither causes nor fixes')
w('it. Recorded as a pre-existing client/server mismatch. The client is left')
w('unchanged: PHP and Java do not differ here, so nothing about this is a')
w('migration decision.')
w('')
w('### 3. Client-visible behaviours worth knowing, identical on both stacks')
w('')
w('- **A 401 logs the user out.** `HttpHelper._handleResponse` calls')
w('  `changeCurrentCompany(null)` on any 401, so an endpoint that answers 401')
w('  where the other stack answers 403 would eject the user. None does.')
w('- **An empty 2xx body becomes `{}`**, not a parse error -- so a response like')
w('  R-038\'s empty body reaches the model as an absent `data`, which is a blank')
w('  screen for most models and a **throw** for the three that write')
w('  `getModel(...)!`.')
w('- **`attendance/list` picks its parser from `meta.full_month`.** The client')
w('  parses `data` as calendar days when it is true and as attendance records')
w('  otherwise. Both stacks agree on the flag, so both take the same branch.')
w('- **`payslips/one` answers the raw key `payslip_not_found`** as its message on')
w('  both stacks -- legacy behaviour, faithfully reproduced, shown to the user as')
w('  written.')
w('')
w('### 4. The three `getModel(...)!` sites are safe')
w('')
w('`dashboard/stats`, `employees/stats` and `configs/get` throw in the client if')
w('`data` is not a JSON object. Checked explicitly because **D-156** changed how')
w('Java renders an empty structure: `data` is an object on both stacks, and')
w("dashboard's eight `(object)[]` keys stay objects on both while")
w('`workforce_planning_stats` stays a list on both.')
w('')
w('## Why runtime is zero')
w('')
w('The real application was not executed, and could not be on this machine:')
w('')
w('| requirement | state |')
w('|---|---|')
w('| Flutter/Dart SDK | not installed |')
w('| Linux desktop toolchain | GTK3 headers, cmake, ninja, clang absent; no root |')
w('| prebuilt desktop binary | none in the checkout |')
w('| redirect `workin.company` to a local Java | `/etc/hosts` read-only; user namespaces denied; `bwrap` denied |')
w('| build-time URL override | none -- `ApiConstants.baseUrl` is a hardcoded `const` |')
w('')
w('The clients are pinned read-only submodules and were **not** modified. Runtime')
w('verification belongs on a machine with the Flutter toolchain and control of the')
w('test hostname.')
w('')
w('## What this does not cover')
w('')
w('- rendering, widget state, navigation between screens')
w('- the file picker, downloads written to disk, OS integration, auto-update')
w('- the four `ResponseType.bytes` endpoints (template/report downloads): the')
w('  client treats them as raw bytes, so there is no parser to check here')
w('- endpoints whose request shape needs state this harness does not seed')
w('- anything about the mobile client, which is a separate pass')
w('')
text = '\n'.join(lines).rstrip('\n') + '\n'
while '\n\n\n' in text:
    text = text.replace('\n\n\n', '\n\n')
Path('DESKTOP-CONTRACT-REPORT.md').write_text(text)
print(f'{len(rows)} contract rows written')
print(counts)

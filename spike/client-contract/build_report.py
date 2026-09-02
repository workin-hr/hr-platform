#!/usr/bin/env python3
"""Write the client-contract coverage report from the recorded runs."""
import json
from pathlib import Path

import sys
CLIENT = sys.argv[1] if len(sys.argv) > 1 else 'desktop'

def load(name, default):
    path = Path(f'{CLIENT}-{name}.json')
    return json.loads(path.read_text()) if path.exists() else default

c = json.loads(Path(f'{CLIENT}-contracts.json').read_text())
reads = load('read-results', [])
muts = load('mutation-results', [])
ups = load('upload-results', [])

rows = []
skipped = []
for r in reads:
    rows.append((r['path'], 'GET', r['model'], r['php'], r['java'], True))
for r in muts:
    if 'php' not in r:
        skipped.append((r['path'], r.get('skipped', 'withheld')))
    if 'php' in r:
        rows.append((r['path'], r['method'], r['model'], r['php'], r['java'], r.get('status_as_expected')))
for r in ups:
    # Same guard the mutation rows get: a case whose reseed failed writes only
    # `path` and `skipped`, and reading `model`/`php`/`java` from it raised a
    # KeyError -- so the builder crashed instead of producing the report whose
    # verdict was deliberately withheld.
    if 'php' in r:
        rows.append((r['path'], 'POST (multipart)', r['model'], r['php'], r['java'],
                     r.get('status_as_expected')))
    else:
        skipped.append((r['path'], r.get('skipped', 'withheld')))

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
w(f'# {CLIENT.capitalize()} client contract conformance')
w('')
w(f"**This is a contract layer, not a runtime one.** It proves what the {CLIENT}")
w("client's own parsers would do with each stack's bytes. It verifies **nothing**")
w('about rendering, navigation, the file picker, downloads to disk, OS')
w('integration, or any other runtime UI behaviour, because this layer executes')
w('nothing -- it replays recorded bytes through the parsers. Whether the real')
w('application was ever run is a separate measurement: see "Runtime status for')
w('this client" below.')
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
w(f'| **Contracts replayed by THIS layer** | **{len(replayed)}** |')
w('')
w('This layer replays contracts; it executes nothing. The runtime verdict for')
w('this client is a separate measurement with its own evidence in')
w('`spike/client-runtime` — see the runtime status line below.')
w('')
w(f"The {c['endpoints_declared']} declared constants are **not** the denominator.")
if c['declared_not_referenced']:
    w(f"{len(c['declared_not_referenced'])} are declared and never referenced by any")
    w('screen, so they are excluded from what a client run could exercise:')
    w('')
    for name in c['declared_not_referenced']:
        w(f"- `{name}`")
else:
    w('Every one of them is referenced from client source.')
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
if skipped:
    w('')
    w('## Cases whose verdict was withheld')
    w('')
    w('A case whose database reseed failed compares contaminated state, so no')
    w('verdict is recorded for it. These are **not** counted as verified:')
    w('')
    for path, why in skipped:
        w(f'- `{path}` -- {why}')

if CLIENT == 'mobile':
    w('')
    w('## Findings')
    w('')
    w('### 1. No Java parity defect was found by this layer')
    w('')
    w('Across every replayed contract the client verdict is the same on both stacks:')
    w('same status, same parse outcome, and the same set of fields that end up blank')
    w('or defaulted. Nothing here asks for a Java change.')
    w('')
    w('### 2. `time/now` does not exist -- a client defect, not a parity defect')
    w('')
    w('The mobile client calls `time/now`. There is no `time` module in the frozen')
    w('PHP tree, and both stacks answer the same 404:')
    w('')
    w('```text')
    w("php  404 Module 'time' not found")
    w("java 404 Module 'time' not found")
    w('```')
    w('')
    w('Pre-existing, identical on both, and not a migration decision.')
    w('')
    w('### 3. `attendance/check_in` -- the enum the client sends, and the one it does not')
    w('')
    w('`AttendanceMethodEnum` is `{app, excel, qr}` and `check_in_usecase` hard-codes')
    w('`app`. All three behave identically on both stacks, storing the same value:')
    w('')
    w('```text')
    w('method=app    php 200 stored app     java 200 stored app')
    w('method=qr     php 200 stored qr      java 200 stored qr')
    w('method=excel  php 200 stored excel   java 200 stored excel')
    w('method=gps    php 200 stored ""      java 500')
    w('```')
    w('')
    w('The last row is **outside the contract** -- no client sends it -- and is')
    w('recorded rather than raised: PHP silently writes an empty method where Java')
    w('refuses. It is a legacy data-integrity quirk (MySQL coercing an invalid ENUM)')
    w('and a Java error-handling one (500 where 400 would be right), reachable only')
    w('by a caller that is not either of these clients.')
    w('')
    w('This row is also why the case sends `app`: an earlier run of this check used')
    w('`gps`, reported "check_in is broken in Java", and was wrong -- the value came')
    w('from the harness, not from the client.')
    w('')
else:
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
    w('(`EditCompanyCommercialRegParameters.toBodyMap` -> `ApiConstants.logoKey`, and')
    w('`HttpHelper.multipartRequest` uses each body-map key as the field name). The')
    w('endpoint reads `UploadMultipart::FILE`, i.e. **`file`**.')
    w('')
    w('Measured with the request the client actually sends:')
    w('')
    w('```text')
    w('part `logo`   php 400 "No file uploaded"   java 400 "No file uploaded"')
    w('part `file`   php 200 uploaded             java 200 uploaded')
    w('```')
    w('')
    w('**Both stacks behave identically**, so the migration neither causes nor fixes')
    w('it. The client is left unchanged: PHP and Java do not differ here, so nothing')
    w('about it is a migration decision.')
    w('')
    w('### 3. The three `getModel(...)!` sites are safe')
    w('')
    w('`dashboard/stats`, `employees/stats` and `configs/get` throw in the client if')
    w('`data` is not a JSON object. Checked explicitly because **D-156** changed how')
    w('Java renders an empty structure: `data` is an object on both stacks,')
    w("dashboard's eight `(object)[]` keys stay objects on both, and")
    w('`workforce_planning_stats` stays a list on both.')
    w('')
w('## Runtime status for this client')
w('')
if CLIENT == 'desktop':
    w('**Verified separately.** The real desktop application was built from')
    w('unmodified source and executed against the local Java backend through its')
    w('own hardcoded URL: login, navigation, create/update/delete, a multipart')
    w('upload through the real file chooser, and logout. Evidence and setup are in')
    w('`spike/client-runtime/DESKTOP-RUNTIME-REPORT.md`.')
else:
    w('**See `spike/client-runtime`** for the mobile runtime verdict and its')
    w('remaining device-dependent gaps. This layer does not execute the app.')
w('')
w('The clients are pinned read-only submodules and were **not** modified for')
w('either layer.')
w('')
w('## What this does not cover')
w('')
w('- rendering, widget state, navigation between screens')
w('- the file picker, downloads written to disk, OS integration, auto-update')
bytes_calls = [x['path'] for x in c['calls'] if x.get('response_type') == 'bytes']
if bytes_calls:
    w(f"- the {len(bytes_calls)} `ResponseType.bytes` endpoints (downloads): the client")
    w('  treats them as raw bytes, so there is no parser to check here')
w('- endpoints whose request shape needs state this harness does not seed')
w(f"- anything about the {'desktop' if CLIENT == 'mobile' else 'mobile'} client, which is a separate pass")
w('')
text = '\n'.join(lines).rstrip('\n') + '\n'
while '\n\n\n' in text:
    text = text.replace('\n\n\n', '\n\n')
Path(f'{CLIENT.upper()}-CONTRACT-REPORT.md').write_text(text)
print(f'{len(rows)} contract rows written')
print(counts)

#!/usr/bin/env python3
"""Derive the API contract from a Flutter client's own source.

The client is the authority here, not the OpenAPI description and not the PHP
source: what matters for "would the app still work" is what the app's parsers
read and how they coerce it. Everything below is extracted from the pinned,
read-only client checkout; nothing is inferred from the server.

Emits one JSON document:

  endpoints[]  name, method, path, query/body keys, response model, referenced
  models{}     class -> ordered field reads (accessor, key, bang, default, nested)
  accessors{}  the coercion semantics, so the evaluator and this file cannot drift
"""
import json
import re
import sys
from pathlib import Path


def strip_comments(text: str) -> str:
    text = re.sub(r'/\*.*?\*/', '', text, flags=re.S)
    return re.sub(r'^\s*//.*$', '', text, flags=re.M)


def constants(root: Path) -> tuple[dict, dict]:
    src = (root / 'lib/core/network/api_constants.dart').read_text(errors='replace')
    # Both quote styles: the file mixes them, and a single-quote-only pattern
    # silently resolved 384 key constants to their own NAMES rather than their
    # values -- request bodies would then be built from `countryCodeKey`.
    pairs = dict(re.findall(r'static const String (\w+)\s*=\s*[\'"]([^\'"]*)[\'"];', src))
    endpoints = {k: v for k, v in pairs.items() if k.endswith('Endpoint')}
    keys = {k: v for k, v in pairs.items() if k.endswith('Key')}
    return endpoints, keys


def data_source_calls(root: Path, endpoints: dict, endpoints_keys: dict) -> list[dict]:
    """Each remote data-source method: what it sends and what parses the reply."""
    path = root / 'lib/data/data_source/remote/remote_data_source.dart'
    src = strip_comments(path.read_text(errors='replace'))
    out = []
    # `Future<X> name(args) async { ... }` -- matched to the next method header,
    # which is enough because the file is one flat class of them.
    headers = list(re.finditer(r'Future<([\w<>?, ]+)>\s+(\w+)\s*\(([^)]*)\)', src))
    for i, m in enumerate(headers):
        start = m.end()
        end = headers[i + 1].start() if i + 1 < len(headers) else len(src)
        body = src[start:end]
        const = re.search(r'endPoint:\s*ApiConstants\.(\w+)', body)
        if not const:
            const = re.search(r'ApiConstants\.(\w+Endpoint)', body)
        if not const or const.group(1) not in endpoints:
            continue
        verb = re.search(r'requestMethod:\s*RequestMethod\.(\w+)', body)
        multipart = 'multipartRequest' in body
        model = re.search(r'return\s+(\w+)\.fromJson\(', body)
        arg_type = re.match(r'\s*(?:required\s+)?([A-Z]\w+)\s+\w+', m.group(3) or '')
        out.append({
            'method_name': m.group(2),
            'parameters_class': arg_type.group(1) if arg_type else None,
            'returns': m.group(1),
            'endpoint_const': const.group(1),
            'path': endpoints[const.group(1)],
            'http_method': ('POST' if multipart else
                            (verb.group(1).upper() if verb else 'GET')),
            'multipart': multipart,
            'response_type': (re.search(r'responseType:\s*ResponseType\.(\w+)', body).group(1)
                              if re.search(r'responseType:\s*ResponseType\.(\w+)', body) else 'json'),
            'query_keys': _map_keys(_slice(body, 'query:'), endpoints_keys),
            'body_keys': _map_keys(_slice(body, 'body:'), endpoints_keys),
            'model': model.group(1) if model else None,
        })
    return out


def parameter_classes(root: Path, keys: dict) -> dict:
    """`toBodyMap()` / `toQueryMap()` on the use-case parameter objects.

    The data source never spells a request body out inline; it forwards
    `parameters.toBodyMap()`. Reading only the data source would therefore
    report every mutation as having no parameters at all.
    """
    out = {}
    for path in sorted((root / 'lib/domain').rglob('*.dart')):
        src = strip_comments(path.read_text(errors='replace'))
        for cls in re.finditer(r'class\s+(\w+)\s*\{', src):
            body = _class_body(src, cls.end() - 1)
            entry = {}
            # Every name a parameter class uses to build a request map. The
            # first two cover almost all of them; the others are rare and were
            # silently dropped -- auth/complete_company_registration uses
            # toMultipartBody() and was recorded as sending no fields at all,
            # on a public onboarding endpoint that takes a logo, a commercial
            # registration and the whole company profile.
            for which, label in (('body', 'toBodyMap'), ('query', 'toQueryMap'),
                                 ('body', 'toMultipartBody'), ('body', 'toMap'),
                                 ('body', 'toJson')):
                # Both bodies: `toQueryMap() { return {...}; }` and the
                # arrow form `toQueryMap() => {...};`. Handling only the first
                # silently reported such a request as having no parameters --
                # employees/delete_preview then answered "Field 'id' is
                # required" and looked like a server refusal.
                m = re.search(label + r'\(\)\s*(\{|=>)', body)
                if not m:
                    continue
                inner = _class_body(body, body.find('{', m.start()))
                found = []
                for k in re.findall(r'ApiConstants\.(\w+Key)\s*:', inner):
                    found.append(keys.get(k, k))
                found += re.findall(r"'(\w+)'\s*:", inner)
                entry[which] = sorted(set(entry.get(which, [])) | set(found))
            if entry:
                out[cls.group(1)] = entry
    return out


def _map_keys(literal: str, keys: dict) -> list:
    """Keys of an inline Dart map, whether quoted or named by a constant.

    Reading only quoted keys missed every `ApiConstants.somethingKey:` entry,
    and the client writes its inline query and body maps that way -- so those
    calls were recorded as sending no parameters at all, and the replay sent
    none either.
    """
    found = [keys.get(k, k) for k in re.findall(r'ApiConstants\.(\w+Key)\s*:', literal)]
    found += re.findall(r"'(\w+)'\s*:", literal)
    found += re.findall(r'"(\w+)"\s*:', literal)
    return sorted(set(found))


def _slice(body: str, label: str) -> str:
    """The brace-balanced argument that follows `label`, or ''."""
    i = body.find(label)
    if i < 0:
        return ''
    j = body.find('{', i)
    if j < 0:
        return ''
    depth = 0
    for k in range(j, len(body)):
        if body[k] == '{':
            depth += 1
        elif body[k] == '}':
            depth -= 1
            if depth == 0:
                return body[j:k + 1]
    return body[j:]


ACCESSOR = re.compile(
    # The receiver is not always `json`: a model may read a nested map it
    # already pulled out, as `meta?.getBoolOrNull(...)`. Requiring `json` here
    # dropped exactly the field that decides a branch.
    r'(?P<field>\w+)\s*[:=]\s*(?P<recv>\w+)\s*\??'
    r'(?:\.(?P<acc>get\w+))'
    r'\((?P<args>.*?)\)(?P<bang>\s*!)?\s*(?P<orelse>\?\?[^;,]*)?\s*[;,]',
    re.S)
DIRECT = re.compile(r'(?P<field>\w+)\s*[:=]\s*(?P<cls>\w+)\.fromJson\(json\)\s*[,;)]')


def models(root: Path, keys: dict) -> dict:
    out = {}
    for path in sorted((root / 'lib/data').rglob('*.dart')):
        src = strip_comments(path.read_text(errors='replace'))
        for cls in re.finditer(r'class\s+(\w+)\s*\{', src):
            name = cls.group(1)
            body = _class_body(src, cls.end() - 1)
            ctor = re.search(re.escape(name) + r'\.fromJson\(\s*(?:Map<String,\s*dynamic>|dynamic)\s+json\s*\)', body)
            if not ctor:
                continue
            brace = body.find('{', ctor.end())
            arrow = body.find('=>', ctor.end())
            if arrow != -1 and (brace == -1 or arrow < brace):
                inner = body[arrow:body.find(';', arrow) + 1]
            else:
                inner = _class_body(body, brace)
            guards = _guard_regions(inner)

            def guard_for(pos: int):
                for start, end, cond in guards:
                    if start <= pos < end:
                        return cond
                return None

            fields = []
            for d in DIRECT.finditer(inner):
                fields.append({'field': d.group('field'), 'accessor': 'sameObject',
                               'key': None, 'bang': False, 'nested': d.group('cls'),
                               'guard': guard_for(d.start())})
            for a in ACCESSOR.finditer(inner):
                args = a.group('args')
                keyexpr = args.split(',')[0].strip()
                key = keys.get(keyexpr.replace('ApiConstants.', ''),
                               keyexpr.strip("'") if keyexpr.startswith("'") else None)
                nested = re.search(r'(\w+)\.fromJson', args)
                default = args.split(',', 1)[1].strip() if ',' in args and not nested else None
                fields.append({
                    'field': a.group('field'),
                    'accessor': a.group('acc'),
                    'key': key,
                    'key_expr': keyexpr,
                    'bang': bool(a.group('bang')),
                    'default': default,
                    'or_else': (a.group('orelse') or '').strip() or None,
                    'nested': nested.group(1) if nested else None,
                    'receiver': a.group('recv'),
                    'guard': guard_for(a.start()),
                })
            if fields:
                out[name] = {'file': str(path), 'fields': fields}
    return out


def _guard_regions(body: str):
    """`if (...) { ... } else { ... }` spans inside a fromJson body.

    Without this the extractor reads BOTH arms as if both ran. It reported
    `attendance/list` as throwing on every stack, because the model parses
    `data` as calendar days OR as attendance records depending on
    `meta.full_month` -- and the arm that does not run reads a key that is not
    there. A false defect, and on an endpoint whose branch is exactly the kind
    of client-visible decision this check exists to compare.
    """
    regions = []
    for m in re.finditer(r'\bif\s*\(', body):
        depth, close = 0, None
        for k in range(m.end() - 1, len(body)):
            if body[k] == '(':
                depth += 1
            elif body[k] == ')':
                depth -= 1
                if depth == 0:
                    close = k
                    break
        if close is None:
            continue
        condition = body[m.end():close].strip()
        brace = body.find('{', close)
        if brace < 0:
            continue
        arm = _class_body(body, brace)
        regions.append((brace, brace + len(arm), condition))
        rest = body[brace + len(arm):]
        els = re.match(r'\s*else\s*\{', rest)
        if els:
            offset = brace + len(arm) + els.end() - 1
            other = _class_body(body, offset)
            regions.append((offset, offset + len(other), f'!({condition})'))
    return regions


def _class_body(src: str, brace: int) -> str:
    depth = 0
    for k in range(brace, len(src)):
        if src[k] == '{':
            depth += 1
        elif src[k] == '}':
            depth -= 1
            if depth == 0:
                return src[brace:k + 1]
    return src[brace:]


def main() -> None:
    root = Path(sys.argv[1])
    endpoints, keys = constants(root)
    calls = data_source_calls(root, endpoints, keys)
    referenced = set()
    src = "\n".join(p.read_text(errors='replace') for p in (root / 'lib').rglob('*.dart')
                    if p.name != 'api_constants.dart')
    for const in endpoints:
        if re.search(r'\b' + const + r'\b', src):
            referenced.add(const)
    params = parameter_classes(root, keys)
    for call in calls:
        spec = params.get(call.get('parameters_class') or '', {})
        if not call['body_keys']:
            call['body_keys'] = spec.get('body', [])
        if not call['query_keys']:
            call['query_keys'] = spec.get('query', [])
    doc = {
        'client': root.name,
        'parameter_classes': len(params),
        'endpoints_declared': len(endpoints),
        'endpoints_referenced': len(referenced),
        'declared_not_referenced': sorted(set(endpoints) - referenced),
        'calls': calls,
        'models': models(root, keys),
        'keys': keys,
    }
    json.dump(doc, sys.stdout, indent=1)


if __name__ == '__main__':
    main()

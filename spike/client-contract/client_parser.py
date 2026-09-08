#!/usr/bin/env python3
"""The client's own JSON coercion, reimplemented exactly.

Every rule here is transcribed from
`lib/core/utilities/extensions.dart` in the pinned client checkout -- that file
is the single place the app turns JSON into typed fields, so it is the authority
for "would the app accept this response". Deviating from it to be tidier would
make this whole check meaningless, so the awkward parts are kept:

  * `getIntOrNull` accepts a JSON double and TRUNCATES it. A server rendering
    2604.0 where another renders 2604 is therefore NOT a client-visible defect
    for an int field -- the client coerces both to 2604. It IS one for a
    *string* "2604.0", because Dart's int.tryParse rejects decimals.
  * `getStringOrNull` maps the literal text "null" and the empty string to null,
    so a field can be present and still read as absent.
  * `getBoolOrNull` accepts only true/false/1/0 (in any of bool/int/string
    form) and answers null for everything else -- which `getBool` then throws on.
  * `getModel` answers null when the value is not a JSON OBJECT. An empty JSON
    array where an object was expected is silently "no model", and the three
    call sites that append `!` turn that into a throw.
"""
from __future__ import annotations

import math
import re


class ParseThrow(Exception):
    """What the client would surface as a FormatException or a null-check error."""


def _to_string(value) -> str:
    """Dart's `value.toString()` for JSON scalars."""
    if isinstance(value, bool):
        return 'true' if value else 'false'
    if isinstance(value, float):
        # Dart prints a whole double as "2604.0", not "2604".
        if math.isfinite(value) and value == int(value):
            return f'{int(value)}.0'
        return repr(value)
    if value is None:
        return 'null'
    return str(value)


_INT_RE = re.compile(r'^[+-]?\d+$')
_NUM_RE = re.compile(r'^[+-]?(\d+\.?\d*|\.\d+)([eE][+-]?\d+)?$')


def get_int_or_null(json_map, key):
    value = json_map.get(key)
    if value is None:
        return None
    if isinstance(value, bool):          # bool is not int in Dart
        return None if not _INT_RE.match(_to_string(value)) else None
    if isinstance(value, int):
        return value
    if isinstance(value, float):
        return int(value)                # Dart's double.toInt() truncates
    text = _to_string(value)
    return int(text) if _INT_RE.match(text) else None


def get_double_or_null(json_map, key):
    value = json_map.get(key)
    if value is None:
        return None
    if isinstance(value, bool):
        return None
    if isinstance(value, float):
        return value
    if isinstance(value, int):
        return float(value)
    text = _to_string(value)
    return float(text) if _NUM_RE.match(text) else None


def get_num_or_null(json_map, key):
    value = json_map.get(key)
    if value is None:
        return None
    text = _to_string(value)
    if not _NUM_RE.match(text):
        return None
    return float(text) if ('.' in text or 'e' in text.lower()) else int(text)


def get_string_or_null(json_map, key):
    value = json_map.get(key)
    if value is None:
        return None
    text = _to_string(value).strip()
    if text.lower() == 'null' or text == '':
        return None
    return text


def get_bool_or_null(json_map, key):
    value = json_map.get(key)
    if value is None:
        return None
    if isinstance(value, bool):
        return value
    text = _to_string(value).lower()
    if text in ('1', 'true'):
        return True
    if text in ('0', 'false'):
        return False
    return None


_DATE_RE = re.compile(
    r'^\d{4}-\d{2}-\d{2}([ T]\d{2}:\d{2}(:\d{2}(\.\d+)?)?(Z|[+-]\d{2}:?\d{2})?)?$')


def get_date_or_null(json_map, key):
    value = json_map.get(key)
    if value is None:
        return None
    return _to_string(value) if _DATE_RE.match(_to_string(value).strip()) else None


def get_time_or_null(json_map, key):
    value = json_map.get(key)
    if value is None:
        return None
    parts = _to_string(value).split(':')
    if len(parts) < 2:
        return None
    if not (_INT_RE.match(parts[0].strip()) and _INT_RE.match(parts[1].strip())):
        return None
    return _to_string(value)


NULLABLE = {
    'getIntOrNull': get_int_or_null,
    'getDoubleOrNull': get_double_or_null,
    'getNumOrNull': get_num_or_null,
    'getStringOrNull': get_string_or_null,
    'getBoolOrNull': get_bool_or_null,
    'getDateOrNull': get_date_or_null,
    'getTimeOrNull': get_time_or_null,
}
STRICT = {
    'getInt': get_int_or_null,
    'getDouble': get_double_or_null,
    'getNum': get_num_or_null,
    'getString': get_string_or_null,
    'getBool': get_bool_or_null,
    'getDate': get_date_or_null,
    'getTime': get_time_or_null,
}
DEFAULTED = {
    'getIntOrDefault': get_int_or_null,
    'getDoubleOrDefault': get_double_or_null,
    'getNumOrDefault': get_num_or_null,
    'getStringOrDefault': get_string_or_null,
    'getBoolOrDefault': get_bool_or_null,
    'getDateOrDefault': get_date_or_null,
    'getTimeOrDefault': get_time_or_null,
}


class Evaluator:
    """Runs a model tree over a decoded response, the way the client would."""

    def __init__(self, models: dict):
        self.models = models

    def parse(self, model_name: str, payload) -> dict:
        self.notes: list[str] = []
        self.blanks: list[str] = []
        self.branch_unresolved: list[str] = []
        try:
            if not isinstance(payload, dict):
                # HttpHelper wraps a bare JSON list as {'results': [...]} and an
                # empty body as {}; anything else reaching a model is a Map.
                raise ParseThrow(f'response is {type(payload).__name__}, not a JSON object')
            self._model(model_name, payload, model_name)
            return {'verdict': 'parses', 'blank_fields': self.blanks,
                    'notes': self.notes, 'branch_unresolved': self.branch_unresolved}
        except ParseThrow as error:
            return {'verdict': 'throws', 'reason': str(error),
                    'blank_fields': self.blanks, 'notes': self.notes,
                    'branch_unresolved': self.branch_unresolved}

    def _model(self, name: str, payload: dict, path: str) -> None:
        spec = self.models.get(name)
        if spec is None:
            self.notes.append(f'{path}: model {name} not extracted; fields unchecked')
            return
        resolved: dict = {}
        for field in spec['fields']:
            guard = field.get('guard')
            taken = self._guard_holds(guard, resolved)
            if taken is False:
                continue                       # the arm the client does not run
            if taken is None and guard is not None:
                self.branch_unresolved.append(f'{path}.{field["field"]} (guard: {guard})')
                continue                       # never claim a throw we cannot prove
            self._field(name, field, payload, path, resolved)

    def _guard_holds(self, guard, resolved):
        """True / False / None when the condition cannot be decided here."""
        if guard is None:
            return True
        negated = False
        text = guard.strip()
        while text.startswith('!'):
            negated = not negated
            text = text[1:].strip()
            if text.startswith('(') and text.endswith(')'):
                text = text[1:-1].strip()
        if not text.isidentifier() or text not in resolved:
            return None
        value = resolved[text]
        if not isinstance(value, bool):
            return None
        return (not value) if negated else value

    def _field(self, owner: str, field: dict, payload: dict, path: str,
               resolved: dict | None = None) -> None:
        resolved = {} if resolved is None else resolved
        acc, key = field['accessor'], field['key']
        where = f'{path}.{field["field"]}'

        # A field may read a map an earlier field already pulled out.
        receiver = field.get('receiver')
        if receiver and receiver not in ('json', None):
            source = resolved.get(receiver)
            if not isinstance(source, dict):
                self.blanks.append(f'{where} (receiver {receiver} is not a map)')
                resolved[field['field']] = None
                return
            payload = source

        if acc == 'sameObject':
            self._model(field['nested'], payload, where)
            return
        if key is None:
            self.notes.append(f'{where}: key expression {field.get("key_expr")} unresolved')
            return

        if acc == 'getModel':
            value = payload.get(key)
            missing = key not in payload
            model = None if (missing or not isinstance(value, dict)) else value
            if model is None:
                if field['bang']:
                    raise ParseThrow(
                        f'{where}: getModel({key!r}) returned null and the call site '
                        f'asserts non-null (!) -- value is '
                        f'{"absent" if missing else type(value).__name__}')
                self.blanks.append(f'{where} (no {key!r} object)')
                return
            if field['nested']:
                self._model(field['nested'], model, where)
            return

        if acc == 'getList':
            value = payload.get(key)
            if not isinstance(value, list):
                self.blanks.append(f'{where} (empty list: {key!r} is '
                                   f'{"absent" if key not in payload else type(value).__name__})')
                return
            if field['nested']:
                # EVERY item, not a sample. The client parses the whole list, so
                # a malformed fourth element throws in the app while a sampled
                # check reports the endpoint compatible -- the check would be
                # least trustworthy exactly where a response is largest.
                for index, item in enumerate(value):
                    if not isinstance(item, dict):
                        raise ParseThrow(f'{where}[{index}]: list item is '
                                         f'{type(item).__name__}, not an object')
                    self._model(field['nested'], item, f'{where}[{index}]')
            return

        if acc == 'getMap':
            value = payload.get(key)
            if not isinstance(value, dict):
                self.blanks.append(f'{where} (no {key!r} map)')
                resolved[field['field']] = None
                return
            resolved[field['field']] = value
            return

        if acc in STRICT:
            if STRICT[acc](payload, key) is None:
                raise ParseThrow(
                    f'{where}: {acc}({key!r}) found '
                    f'{"no key" if key not in payload else repr(payload.get(key))} '
                    f'-- the client throws FormatException here')
            return

        if acc in DEFAULTED:
            value = DEFAULTED[acc](payload, key)
            resolved[field['field']] = value
            if value is None:
                self.blanks.append(f'{where} (default for {key!r})')
            return

        if acc in NULLABLE:
            value = NULLABLE[acc](payload, key)
            resolved[field['field']] = value
            if value is None:
                self.blanks.append(f'{where} (null for {key!r})')
            if field['bang'] and value is None:
                raise ParseThrow(f'{where}: {acc}({key!r}) is null under a `!`')
            return

        if acc == 'getIntIdList':
            return
        self.notes.append(f'{where}: accessor {acc} not modelled')

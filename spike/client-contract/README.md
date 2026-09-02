# Client contract conformance

A **contract** verification layer for the Flutter clients, separate from the
runtime one. It answers a narrower question than "does the app work", and it is
worth being blunt about which:

> Given the bytes each stack returns for the request the client actually sends,
> what would the client's own parsers do with them?

It does **not** verify rendering, navigation, the file picker, downloads written
to disk, OS integration, or any other runtime behaviour, and it is not evidence
that the real desktop or mobile application has been executed successfully.

## Why the client is the authority

`lib/core/utilities/extensions.dart` is the single place the app turns JSON into
typed fields. `client_parser.py` reimplements it rule for rule, including the
awkward parts, because a tidier reimplementation would measure the wrong thing:

- `getIntOrNull` accepts a JSON double and truncates it, so `2604` and `2604.0`
  are the same value to this client for an `int` field. That difference is
  therefore **not** a client-visible defect here — but it is for a *string*
  `"2604.0"`, because Dart's `int.tryParse` rejects decimals.
- `getStringOrNull` maps the literal text `"null"` and `""` to null.
- `getModel` answers null when the value is not a JSON **object**, and three
  call sites append `!`, which turns that into a throw. An empty JSON array
  where an object was expected is exactly that case.

## Layout

| file | what it does |
|---|---|
| `extract_client_contracts.py` | derives the contract from the client source: endpoint, method, request keys, response parser, per-field accessor, nullability, guards |
| `client_parser.py` | the client's coercion rules, reimplemented |
| `run_contract_check.py` | replays the read contracts against both stacks and judges each answer with the client's parser |
| `run_mutation_contracts.py` | the same for mutations, reseeding both databases per case |
| `run_upload_contracts.py` | the same for multipart, using the client's own part names |
| `build_report.py` | writes `DESKTOP-CONTRACT-REPORT.md` |

## Running it

Needs the parity harness up: `db` and `php` from `spike/parity-harness`, Java on
18081, and a seeded snapshot (`./seed-two.sh`).

```sh
python3 extract_client_contracts.py ../../flutter-integration/workin_desktop > desktop-contracts.json
python3 run_contract_check.py desktop-contracts.json
python3 run_mutation_contracts.py
python3 run_upload_contracts.py
python3 build_report.py
```

## Rules this layer keeps

- **A matching refusal is not verification.** Every mutation and upload case
  declares the status it expects and is reported as *not verified* otherwise —
  the same rule the mutation sweep applies.
- **The declared endpoint count is not the denominator.** Declared, referenced,
  statically derived, replayed and runtime-verified are five different numbers
  and the report keeps them apart.
- **Part names come from the client**, not from what the server reads. Using the
  server's name would test a request the client never sends, and would have
  hidden the `company/upload_commercial_reg` mismatch.
- **The clients are never modified.** They are pinned read-only submodules.

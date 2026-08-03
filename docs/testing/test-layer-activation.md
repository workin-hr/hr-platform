# Test Layer Activation Triggers

`docs/testing/test-strategy.md`'s Planned Test Layers list names 28 test
types with no stated condition for when each one turns on. This maps every
one of them to a concrete, structural activation trigger — a real file,
directory, or document state that can be checked — so "planned" has a
defined path to "running" instead of staying aspirational indefinitely.

## Status

Proposed. This is a planning document, not an enforcement mechanism: no
trigger below is wired into `scripts/validate_phase0.py` unless explicitly
noted. Building automated enforcement for a trigger implies committing to
a specific tool or framework choice ahead of the ADR that should make that
choice (see each layer's "Depends On" column) — this document intentionally
stops at specifying the trigger, not automating detection of it.

## Already Active

| Test Layer | Where |
|---|---|
| secrets scanning | Gitleaks, every push and pull request — `.github/workflows/phase0-validate.yml` |
| static analysis (of this repository's own governance scripts) | ShellCheck (`scripts/*.sh`, `.agents/skills/*/scripts/*.sh`), every push and pull request |
| dependency scanning | inert until a package manifest exists, then enforced by `validate_dependabot_ecosystem_coverage` (GH-3) — see `scripts/validate_phase0.py` |

## Code-Boundary Triggered

Activates the first time a real (non-README) tracked file lands in the
named boundary — the same "boundary README.md only" signal
`validate_codeowners_component_coverage` (GH-2) already checks for.

| Test Layer | Activation Trigger | Depends On |
|---|---|---|
| unit tests | first source file under `backend/`, `admin-web/`, or `edge-gateway/` | ADR-0002 (component boundaries) |
| component tests | first source file under any of the same three boundaries | ADR-0002 |
| module integration tests | first source file under `backend/` | ADR-0002 |
| accessibility tests | first source file under `admin-web/` | — |
| static analysis (of product code) | first source file in a language-specific boundary | ADR-0002, ADR-0006 (which language(s) actually land) |
| container scanning | first real container manifest — blocked entirely today: `Dockerfile`/`docker-compose.yml` are in `FORBIDDEN_FILE_NAMES` (`scripts/validate_phase0.py`) until Phase 0 ends | — |

## Contract/Evidence Triggered

Activates once a specific document or artifact stops being a template and
starts holding real, cited content.

| Test Layer | Activation Trigger | Depends On |
|---|---|---|
| API contract tests | first file under `contracts/openapi/` or `contracts/schemas/` | — |
| consumer compatibility tests | `docs/api/flutter-request-response-compatibility.md` holds real cited evidence (A1) | — |
| Flutter compatibility tests | `flutter-integration/` holds real content, or `hr-flutter` repository question (H2) is resolved | ADR-0001 |
| migration tests | `docs/migration/database-schema-inventory.md` and `docs/migration/migration-validation-queries.md` hold real cited evidence (A1) | ADR-0004 |
| differential PHP-versus-Java tests | `docs/legacy/production-behavior-evidence.md` holds real evidence **and** `backend/` has real Java code | None — no ADR currently designates Java as the backend implementation language; `backend/README.md`'s "Java and Spring Boot" framing is boundary placeholder text only, not ADR-backed (ADR-0002 sets modular-monolith architecture style, not language) |
| golden-master tests | `docs/legacy/production-behavior-evidence.md` holds real captured legacy behavior to serve as the baseline | — |
| backup and restore tests | `docs/operations/backup-and-restore.md`'s Backup Method section is no longer `Not yet discovered` **and** a real data store exists | — |
| recovery tests | `docs/operations/recovery-objectives.md` has real (non-placeholder) RTO/RPO values **and** a target environment exists | — |
| device simulator tests | `docs/devices/vendor-capability-matrix.md` holds real vendor evidence (A1) **and** a device-simulation harness exists | ADR-0006 |
| gateway offline and reconnect tests | ADR-0006 moves toward Accepted **and** `edge-gateway/` has real implementation | ADR-0006 |

## Environment Triggered

Activates once a real (not "candidate") environment exists per
`docs/operations/environment-and-deployment-strategy.md`.

| Test Layer | Activation Trigger | Depends On |
|---|---|---|
| end-to-end tests | at least two component boundaries have real implementation | ADR-0002 |
| security tests | `backend/` or `edge-gateway/` has real endpoint/auth code | ADR-0005 |
| dynamic application security testing | a real deployed/running environment exists | environment-and-deployment-strategy.md |
| performance smoke tests | `backend/` or `edge-gateway/` has a real running service endpoint | — |
| load tests | a real environment exists and performance smoke tests already pass | — |
| stress tests | same as load tests, run after load tests are stable | — |
| spike tests | same as load tests | — |
| soak tests | same as load tests | — |
| failure-injection tests | a real deployed environment plus monitoring/alerting signals to observe against | ADR-0008 |
| database integration tests | a real schema/migration artifact exists (`infrastructure/` or `backend/` persistence code) | ADR-0004 |

## Open Questions

- Which of these triggers should eventually become a real
  `scripts/validate_phase0.py` check (dormant today, mirroring GH-2/GH-3),
  versus staying a human review-checklist item?
- Which test layers are mandatory before the *first* production release
  versus acceptable to phase in afterward?
- Does every "Depends On" ADR need to be fully Accepted before its
  dependent test layers may begin, or only Proposed with enough Discovery
  evidence to write a meaningful test?

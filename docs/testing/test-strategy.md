# Test Strategy

## Principles

- Match test cost to change risk.
- Keep compatibility and migration risks visible.
- Use differential and evidence-driven testing where behavior parity matters.

## Every Commit

Enforced by `.github/workflows/phase0-validate.yml`, which installs a
pinned, checksum-verified copy of every tool below — nothing here is
conditional on a tool happening to be present:

- repository validation (`scripts/validate_phase0.py`)
- markdown lint (markdownlint-cli2) and link checks (lychee)
- YAML lint (yamllint), shell lint (ShellCheck, scoped to this repository's
  own scripts), and GitHub Actions lint (actionlint)
- secret detection (Gitleaks, authoritative; the validator's five regex
  patterns are an additional fast check — see
  `docs/security/security-boundaries.md`)
- agent and skill structure validation

## Every Pull Request

- all commit checks
- expanded repository validation
- ADR and documentation integrity checks
- independent review evidence

## Nightly

- deeper compatibility checks
- broader contract validation
- selected performance smoke and resilience checks once tooling exists

## Pre-Release

- end-to-end tests
- migration tests
- differential PHP-versus-Java checks
- security testing
- performance, load, and recovery validation

## Production Smoke Tests

- health and readiness checks
- critical-path synthetic verification

## Planned Test Layers

- unit tests
- component tests
- module integration tests
- database integration tests
- API contract tests
- consumer compatibility tests
- Flutter compatibility tests
- migration tests
- differential PHP-versus-Java tests
- golden-master tests
- end-to-end tests
- accessibility tests
- security tests
- static analysis
- dependency scanning
- secrets scanning
- container scanning
- dynamic application security testing
- performance smoke tests
- load tests
- stress tests
- spike tests
- soak tests
- recovery tests
- failure-injection tests
- backup and restore tests
- device simulator tests
- gateway offline and reconnect tests

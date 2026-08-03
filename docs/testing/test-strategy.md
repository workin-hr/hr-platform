# Test Strategy

## Principles

- Match test cost to change risk.
- Keep compatibility and migration risks visible.
- Use differential and evidence-driven testing where behavior parity matters.

## Every Commit

Enforced by `.github/workflows/phase0-validate.yml` in `BOOTSTRAP_STRICT`
mode — nothing here is conditional on a tool happening to be present; a
missing required tool fails the build (`scripts/verify-bootstrap.sh` fails
the same way locally when run with `BOOTSTRAP_STRICT=true`). The tools
themselves carry different, precisely-stated guarantees — see
`docs/bootstrap/audit-remediation.md` (P2-01) for why these are not all
described the same way:

- repository validation (`scripts/validate_phase0.py`), which also runs
  the Git command-guard, dynamic-ADR-discovery, governance-check, and
  audit-hook regression test suites (`scripts/test_git_guard.py`,
  `scripts/test_adr_validation.py`, `scripts/test_validate_phase0.py`,
  `scripts/test_edit_audit_log.py`)
- ShellCheck, actionlint, Gitleaks, and Lychee: exact version **pinned and
  checksum-verified** before use (SHA-256, checked in CI before the binary
  ever runs)
- markdownlint-cli2 and yamllint: exact version **pinned only** (installed
  from the npm/PyPI registries; not checksum-verified)
- actions/checkout, actions/setup-node, actions/setup-python: **immutable
  action references** (pinned to a commit SHA, not a floating tag)
- the runner itself: a **fixed runner generation** (`ubuntu-24.04`), not a
  fully reproducible, byte-identical OS image
- markdown lint (markdownlint-cli2) and link checks (Lychee — checks
  repository-local links only; it does not prove external URLs are
  reachable, since `lychee.toml` runs it `offline`)
- YAML lint (yamllint), shell lint (ShellCheck, scoped to this repository's
  own scripts), and GitHub Actions lint (actionlint)
- secret detection (Gitleaks, authoritative; the validator's five regex
  patterns are an additional fast check — see
  `docs/security/security-boundaries.md`)
- agent and skill structure validation
- the dedicated ADR validator (`.agents/skills/create-adr/scripts/validate-adr.sh`,
  which delegates to `scripts/validate_phase0.py`) run individually against
  every real ADR
- a bootstrap/Spec Kit prerequisite report (`scripts/check-bootstrap-prerequisites.sh`)
  — informational only; it never fails the build over an operator-dependent
  tool such as `specify`, since Phase 0 CI does not itself depend on that
  CLI being installed (see `docs/bootstrap/audit-remediation.md`, P2-03)

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

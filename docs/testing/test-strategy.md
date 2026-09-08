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

## The Suite's Database

The backend suite runs against a **real MariaDB**, because the legacy schema is
an external contract and an in-memory stand-in cannot hold it (D-037: production
is MariaDB 11.8, and the schema uses syntax MySQL 8 only warns on).

**One container for the whole JVM.** `LegacyMariaDb` starts it once and hands
out databases inside it — `freshDatabase()` with the legacy schema and the
Phase 1 extensions applied, `emptyDatabase()` for the few classes that create
their own tables. Before this, eighty-four classes each started their own
container: about three seconds of startup apiece, serialized, for a database
that is identical every time.

**The unit of isolation is the handle, not the class.** Most classes hold their
own handle and so cannot see another class's rows. Two shared harnesses
deliberately do not: `AbstractLegacyMySqlTest` shares one database across its
eighteen subclasses, and `AdminPayrollTestSupport` across its three. That is
their long-standing contract, and it is why those hierarchies use distinct ids
per test or reset in `@BeforeEach` the tables they touch. A test that joins one
of them inherits that discipline; a test that takes its own handle does not
need it.

Two things keep it that way, and both are tests rather than conventions:

- `LegacyMariaDbSingletonTest` reads the test sources and fails if any class
  other than `LegacyMariaDb` constructs a container. A stray one is invisible
  otherwise — the suite still passes, only slower and with a second database
  process beside the shared one.
- the `test` task declares `outputs.cacheIf { false }` (`backend/build.gradle`),
  so Gradle never restores it from the build cache. Docker's state and the
  floating `mariadb:11.8` tag are not task inputs, so a cached "pass" could be
  a run that never started a database at all.

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

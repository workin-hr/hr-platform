# Security Boundaries

## Secrets Handling

- store no real secrets in the repository
- use placeholders that cannot be mistaken for live credentials

### Secret Scanning

**Gitleaks (default ruleset, no custom config, no exclusions) is the
authoritative secret scanner**, run in CI on every push and pull request
(`.github/workflows/phase0-validate.yml`) with `--exit-code 1`, so a detected
secret fails the build. The five custom regular expressions in
`scripts/validate_phase0.py::validate_secrets()` (GitHub token prefixes, AWS
access-key-ID prefix, PEM private-key header) are an additional fast, narrow
check that runs as part of the structural validator — they are not, and must
not be described as, comprehensive secret scanning on their own.

- **Local execution**: `gitleaks detect --no-git --source .` (install from
  <https://github.com/gitleaks/gitleaks/releases>, or let
  `scripts/verify-bootstrap.sh` run it automatically if it is already on
  `PATH`).
- **Baseline handling**: there is no `.gitleaks.toml` allowlist and none
  should be added speculatively — a real allowlist entry may only be added
  for a confirmed, reviewed false positive (see below), never to silence an
  unreviewed finding, and never as a broad path exclusion.
- **False-positive review procedure**: if gitleaks flags a string that is
  not a real credential (e.g. an example/placeholder value in
  documentation), a human must confirm it is not real before merging. If it
  recurs, add the narrowest possible rule exception in a dedicated
  `.gitleaks.toml` with a comment explaining why, rather than disabling the
  scan or excluding a whole path.
- If gitleaks ever finds a real secret, treat it as a security incident per
  "Incident Escalation" in `docs/security/logging-and-privacy.md`, not as a
  normal lint failure to silence.

## Agent Credential Boundaries

- no unrestricted organization tokens
- no production database access
- no private key access

## Data Restrictions

- no PII exports into repository evidence unless explicitly sanitized
- no biometric raw data for agents

## Platform Security

- attendance terminals are an untrusted external boundary (D-164, R-041):
  a device is identified by a serial number the platform resolves against
  its own registry, never by anything the payload asserts; nothing is
  ingested for an unclaimed or deactivated serial; biometric template
  records are discarded before storage; device commands are a closed
  allow-list — see
  `docs/superpowers/specs/2026-09-02-attendance-device-ingestion-design.md` §8
- least privilege
- dependency and supply-chain review
- security review in pull requests
- incident escalation to human owners

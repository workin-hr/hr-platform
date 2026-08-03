# Security Policy

## Phase 0 Security Boundaries

This repository must not contain:

- production credentials
- production data
- database dumps
- biometric records
- private keys
- unrestricted organization tokens

## Agent Credential Rules

- Agents use least-privilege credentials only.
- Read-only agents must remain read-only.
- Agents must not access production systems, databases, or customer biometric data.

## Reporting

Report sensitive findings through a private maintainer-controlled channel, not a public issue, when exposure would create additional risk.

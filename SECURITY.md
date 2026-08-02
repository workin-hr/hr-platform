# Security Policy

## Phase 0 Boundaries

This repository must not contain:

- production credentials
- database dumps
- customer biometric data
- private keys
- unrestricted organization tokens

## Reporting

Do not report vulnerabilities through public issues if they expose sensitive details.

Use a private maintainer channel or GitHub private vulnerability reporting once enabled.

## Agent Limits

- Agents must use least-privilege tokens.
- Read-only agents must not be upgraded silently to write access.
- No agent may access production systems during Phase 0.

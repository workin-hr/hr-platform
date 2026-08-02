# Security Boundaries

## Secrets Handling

- store no real secrets in the repository
- use placeholders that cannot be mistaken for live credentials

## Agent Credential Boundaries

- no unrestricted organization tokens
- no production database access
- no private key access

## Data Restrictions

- no PII exports into repository evidence unless explicitly sanitized
- no biometric raw data for agents

## Platform Security

- least privilege
- dependency and supply-chain review
- security review in pull requests
- incident escalation to human owners

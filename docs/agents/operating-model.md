# Agent Operating Model

## Roles

- Humans own final product, architecture, security, and production decisions.
- Claude focuses on planning, analysis, and independent review.
- Codex focuses on controlled implementation within approved scope.

## Mandatory Rules

- planning agents are read-only unless assigned documentation work
- review agents are always read-only
- implementers cannot approve their own work
- no agent can merge its own pull request
- no agent may access production data or unrestricted credentials

## Workflow

Issue -> Specification -> Clarification -> Architecture and test impact -> Human approval -> Isolated implementation branch -> Automated verification -> Independent review -> Human merge

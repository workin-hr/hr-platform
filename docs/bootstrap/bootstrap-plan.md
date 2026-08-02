# Bootstrap Plan

## Goal

Prepare `hr-platform` to support safe, auditable, repository-driven collaboration among humans, Claude agents, and Codex agents before product discovery and implementation begin.

## Ordered Workstreams

1. Repository structure and boundary documentation
2. Governance documents and manual GitHub setup instructions
3. Agent operating model and scoped agent definitions
4. Reusable skills and supporting validation assets
5. Architecture, discovery, testing, security, and tooling document system
6. Lightweight Phase 0 validation and CI
7. Independent review readiness

## Expected Outputs

- required root files
- required repository tree
- bootstrap, architecture, discovery, testing, security, and tooling documents
- ADR template and proposed ADR placeholders
- issue forms, labels, dependabot, PR template, and validation workflow
- agent definitions and skills with explicit guardrails
- repository validation scripts and wrapper command

## Execution Rules

- no product implementation
- no production access
- no silent resolution of open questions
- all checks must emit actionable failures

## Exit Condition

Phase 0 is ready for human review when the repository satisfies the definition of done and an independent reviewer can audit both scope and evidence without relying on chat history.

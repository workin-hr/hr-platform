---
name: create-adr
description: Use when creating or updating an architecture decision record with explicit context, decision, consequences, alternatives, and validation.
---

# Create ADR

## Trigger

Use when a decision materially affects architecture, governance, migration, testing, or security boundaries.

## Inputs

- decision context
- evidence and constraints
- related open questions

## Workflow

1. Start from `assets/adr-template.md`.
2. Record context and decision evidence.
3. Describe consequences and alternatives.
4. Run `scripts/validate-adr.sh` on the draft.

## Required Evidence

- links to source documents
- explicit alternatives considered

## Validation Checklist

- status is present
- context and decision are separate
- consequences are concrete

## Failure And Escalation

Escalate if the decision lacks evidence or closes an unresolved architecture question silently.

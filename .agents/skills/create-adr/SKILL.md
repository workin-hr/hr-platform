---
name: create-adr
description: Use when creating or updating an architecture decision record with explicit context, status, alternatives, and open questions.
---

# Create ADR

## Description And Trigger

Use when a decision materially affects architecture, governance, migration, testing, compatibility, or security boundaries.

## Inputs

- decision context
- evidence and constraints
- related open questions

## Preconditions

- there is a real decision or decision candidate worth tracking

## Ordered Workflow

1. Start from `assets/adr-template.md`.
2. Record context and evidence.
3. Describe the proposed direction, consequences, alternatives, and open questions.
4. Run `scripts/validate-adr.sh` on the draft.

## Required Outputs

- ADR draft or update
- ADR validation result

## Evidence

- links to source documents
- explicit alternatives considered

## Validation Checklist

- status is present
- context and decision are separate
- consequences are concrete

## Failure Conditions

- an ADR closes a decision without evidence

## Escalation Conditions

Escalate if the decision lacks evidence or closes an unresolved architecture question silently.

## Forbidden Behavior

- fabricating final decisions
- omitting tradeoffs

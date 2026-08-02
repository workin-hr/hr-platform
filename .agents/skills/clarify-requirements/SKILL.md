---
name: clarify-requirements
description: Use when requirements, assumptions, or acceptance criteria need clarification before planning or implementation continues.
---

# Clarify Requirements

## Description And Trigger

Use when repository work is blocked by ambiguity, conflicting assumptions, or missing acceptance criteria.

## Inputs

- source issue or specification
- related ADRs and bootstrap documents
- current open questions

## Preconditions

- the ambiguity materially affects scope, design, or validation

## Ordered Workflow

1. Identify the ambiguous statement or missing decision.
2. Separate confirmed facts from assumptions.
3. Draft the clarification questions and impact areas.
4. Record unresolved items in repository documents or PR notes.

## Required Outputs

- clarification question set
- impact summary
- updated open question references

## Evidence

- links to affected files or issues
- explanation of why clarification is required

## Validation Checklist

- ambiguity is stated precisely
- affected areas are explicit
- no hidden decisions were made

## Failure Conditions

- ambiguity remains but work proceeds as if resolved

## Escalation Conditions

- human decision ownership is required
- multiple architecture options remain plausible

## Forbidden Behavior

- silently inventing requirements
- treating assumptions as approved decisions

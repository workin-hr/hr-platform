---
name: create-specification
description: Use when creating or refining a repository-backed specification with acceptance criteria, evidence, and unresolved questions.
---

# Create Specification

## Canonical Instructions

Read and follow repository-root `AGENTS.md`; this skill narrows that contract and never overrides it.

## Description And Trigger

Use when a specification under `specs/` or related planning documents needs to be created or updated.

## Inputs

- discovery evidence
- related ADRs
- approved scope

## Preconditions

- enough discovery exists to describe the problem and acceptance criteria honestly

## Ordered Workflow

1. Define problem, actors, constraints, and acceptance criteria.
2. Separate facts, assumptions, and open questions.
3. Link the specification to evidence and related ADRs.

## Required Outputs

- specification draft or update
- linked evidence and dependencies

## Evidence

- supporting documents
- linked risks and dependencies

## Validation Checklist

- acceptance criteria are explicit
- assumptions are isolated
- dependencies are visible

## Failure Conditions

- the specification hides unresolved questions

## Escalation Conditions

Escalate if the specification depends on unresolved product or architecture decisions.

## Forbidden Behavior

- writing fake certainty
- bypassing human approval gates

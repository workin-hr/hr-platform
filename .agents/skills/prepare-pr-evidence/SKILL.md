---
name: prepare-pr-evidence
description: Use when assembling pull-request evidence, validation output, assumptions, decisions, and unresolved items for human review.
---

# Prepare PR Evidence

## Canonical Instructions

Read and follow repository-root `AGENTS.md`; this skill narrows that contract and never overrides it.

## Description And Trigger

Use when a branch is ready for review and the PR description or evidence package needs to be assembled.

## Inputs

- changed files
- command log
- validation results
- related issue or bootstrap documents

## Preconditions

- the work is complete enough for review

## Ordered Workflow

1. Summarize the scope and linked reference.
2. List changed files and key decisions.
3. Record commands executed and validation results.
4. Record assumptions, unresolved items, and rollback notes.

## Required Outputs

- PR summary
- validation evidence
- unresolved item list

## Evidence

- command output summaries
- file references
- check results

## Validation Checklist

- linked reference is present
- validation results are explicit
- unresolved items are not hidden

## Failure Conditions

- PR evidence omits validation or key assumptions

## Escalation Conditions

Escalate if required review evidence is missing.

## Forbidden Behavior

- overstating confidence
- claiming checks passed when they were not run

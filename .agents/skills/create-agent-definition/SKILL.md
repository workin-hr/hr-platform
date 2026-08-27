---
name: create-agent-definition
description: Use when creating or updating Claude or Codex agent definitions with explicit scope, permissions, and evidence rules.
---

# Create Agent Definition

## Canonical Instructions

Read and follow repository-root `AGENTS.md`; this skill narrows that contract and never overrides it.

## Description And Trigger

Use when adding or updating agent definitions under `.claude/agents/` or `.codex/agents/`.

## Inputs

- approved role list
- agent responsibilities
- repository governance rules

## Preconditions

- the role is approved or clearly justified by the current repository workflow

## Ordered Workflow

1. Define role and purpose.
2. Record trigger conditions, inputs, outputs, and tools.
3. Record permissions, scope, PR authority, approval authority, escalation rules, and evidence requirements.
4. Validate that the agent cannot approve its own work or exceed Phase 0 scope.

## Required Outputs

- agent definition
- permission and scope summary

## Evidence

- mapping to bootstrap documents
- explicit permission boundaries

## Validation Checklist

- all mandatory sections exist
- permissions are least-privilege
- review agents are read-only

## Failure Conditions

- agent permissions exceed role needs

## Escalation Conditions

Escalate if the requested agent duplicates another role or requires broader permissions than approved.

## Forbidden Behavior

- granting self-approval
- leaving repository scope implicit

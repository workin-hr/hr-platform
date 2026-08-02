---
name: create-agent-definition
description: Use when creating or updating project-scoped Claude or Codex agent definitions with explicit permissions, tools, and completion criteria.
---

# Create Agent Definition

## Trigger

Use when adding or updating agent definitions under `.claude/agents/` or `.codex/agents/`.

## Inputs

- approved role list
- agent responsibilities
- repository governance rules

## Workflow

1. Define role and purpose.
2. Record inputs, outputs, allowed tools, and forbidden actions.
3. Record read/write permissions, escalation rules, and completion criteria.
4. Validate that the agent cannot approve its own work or exceed Phase 0 scope.

## Required Evidence

- mapping to approved bootstrap plan
- explicit permission boundaries

## Validation Checklist

- all mandatory sections exist
- permissions are least-privilege
- review agents are read-only

## Failure And Escalation

Escalate if the requested agent duplicates another role or requires broader permissions than approved.

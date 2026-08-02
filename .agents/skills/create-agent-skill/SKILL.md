---
name: create-agent-skill
description: Use when creating a reusable repository skill with a concrete workflow, evidence requirements, validation, and escalation rules.
---

# Create Agent Skill

## Trigger

Use when adding or revising a reusable skill under `.agents/skills/`.

## Inputs

- concrete repeatable task
- repository constraints
- required artifacts or scripts

## Workflow

1. Define the trigger and inputs.
2. Write an ordered workflow.
3. Specify required evidence, validation, and escalation.
4. Add only minimal supporting assets, references, or scripts.

## Required Evidence

- repeatable procedure
- validation approach

## Validation Checklist

- frontmatter contains `name` and `description`
- workflow is concrete, not a vague prompt
- failure path is explicit

## Failure And Escalation

Escalate if the task is too broad for a reusable skill or depends on unstable hidden context.

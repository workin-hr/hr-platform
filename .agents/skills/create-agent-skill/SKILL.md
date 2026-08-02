---
name: create-agent-skill
description: Use when creating a reusable repository skill with concrete workflow, evidence, validation, and escalation rules.
---

# Create Agent Skill

## Description And Trigger

Use when adding or revising a reusable skill under `.agents/skills/`.

## Inputs

- concrete repeatable task
- repository constraints
- required artifacts or scripts

## Preconditions

- the task is repeatable and repository-driven

## Ordered Workflow

1. Define the trigger and inputs.
2. Write an ordered workflow.
3. Specify required outputs, evidence, validation, failure, and escalation.
4. Add only minimal supporting assets, references, or scripts.

## Required Outputs

- skill definition
- optional supporting assets or scripts

## Evidence

- repeatable procedure
- validation approach

## Validation Checklist

- frontmatter contains `name` and `description`
- workflow is concrete, not a vague prompt
- failure path is explicit

## Failure Conditions

- the skill is only a prompt with no procedure

## Escalation Conditions

Escalate if the task is too broad for a reusable skill or depends on unstable hidden context.

## Forbidden Behavior

- overloading the skill with unnecessary context
- omitting escalation paths

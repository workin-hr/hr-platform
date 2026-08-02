---
name: create-github-backlog
description: Use when defining backlog taxonomy, epics, issue forms, labels, and manual GitHub Project setup instructions.
---

# Create GitHub Backlog

## Description And Trigger

Use when defining backlog structure, epics, issue types, workflow states, labels, or project setup instructions.

## Inputs

- bootstrap backlog documents
- manual setup instructions

## Preconditions

- bootstrap governance direction exists

## Ordered Workflow

1. Confirm repository-file versus GitHub-UI responsibilities.
2. Capture issue taxonomy, labels, and workflow states.
3. Record what must be applied manually in GitHub.

## Required Outputs

- backlog setup document or update
- manual action list for GitHub UI

## Evidence

- issue type list
- project field list
- epic and spike seed

## Validation Checklist

- issue taxonomy matches the approved plan
- project-only settings are documented explicitly

## Failure Conditions

- GitHub-only settings are implied but undocumented

## Escalation Conditions

Escalate if requested backlog changes alter scope or workflow semantics without approval.

## Forbidden Behavior

- assuming organization admin access
- hiding manual setup steps

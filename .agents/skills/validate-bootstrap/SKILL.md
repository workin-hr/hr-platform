---
name: validate-bootstrap
description: Use when running deterministic checks for required Phase 0 files, forbidden application files, agent definitions, skills, links, and secret exposure.
---

# Validate Bootstrap

## Trigger

Use when validating repository readiness before review or merge.

## Inputs

- repository tree
- validation script path

## Workflow

1. Run `python3 scripts/validate_phase0.py`.
2. Capture failures and map them to files.
3. Confirm forbidden application files are absent.

## Required Evidence

- validation output
- list of unresolved failures

## Validation Checklist

- required structure exists
- secret scan passes
- agent and skill structure checks pass

## Failure And Escalation

Escalate if validation reveals forbidden files, secrets, or missing governance artifacts.

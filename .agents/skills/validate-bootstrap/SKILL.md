---
name: validate-bootstrap
description: Use when running deterministic checks for required Phase 0 files, forbidden application files, agents, skills, ADRs, links, and secrets.
---

# Validate Bootstrap

## Canonical Instructions

Read and follow repository-root `AGENTS.md`; this skill narrows that contract and never overrides it.

## Description And Trigger

Use when validating repository readiness before review or merge.

## Inputs

- repository tree
- validation script path

## Preconditions

- repository files are present locally

## Ordered Workflow

1. Run `python3 scripts/validate_phase0.py`.
2. Capture failures and map them to files.
3. Confirm forbidden application files are absent.

## Required Outputs

- validation result summary
- failure list if applicable

## Evidence

- validation output
- list of unresolved failures

## Validation Checklist

- required structure exists
- secret scan passes
- agent and skill structure checks pass

## Failure Conditions

- failures are ignored or hidden

## Escalation Conditions

Escalate if validation reveals forbidden files, secrets, or missing governance artifacts.

## Forbidden Behavior

- claiming success without running the checks

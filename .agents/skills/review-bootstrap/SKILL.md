---
name: review-bootstrap
description: Use when auditing Phase 0 bootstrap work for scope compliance, governance, security boundaries, skill quality, and maintainability.
---

# Review Bootstrap

## Canonical Instructions

Read and follow repository-root `AGENTS.md`; this skill narrows that contract and never overrides it.

## Description And Trigger

Use when independently reviewing Phase 0 pull requests or repository changes.

## Inputs

- bootstrap documents
- changed files
- validation results

## Preconditions

- there is a concrete change set or repository snapshot to review

## Ordered Workflow

1. Check scope compliance first.
2. Inspect governance, agent permissions, and secret boundaries.
3. Inspect documentation completeness and validation coverage.
4. Report findings by severity with exact file references.

## Required Outputs

- severity-ranked findings
- verdict and remediation set

## Evidence

- exact file and section references
- severity and remediation

## Validation Checklist

- no application implementation exists
- review agents remain read-only
- bootstrap plan and actual files match

## Failure Conditions

- review lacks exact evidence

## Escalation Conditions

Escalate P0 and P1 findings immediately and do not approve based on document volume alone.

## Forbidden Behavior

- approving without evidence
- rewriting files while reviewing

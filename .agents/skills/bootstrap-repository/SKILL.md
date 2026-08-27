---
name: bootstrap-repository
description: Use when establishing or extending the Phase 0 bootstrap structure for hr-platform without adding application code.
---

# Bootstrap Repository

## Canonical Instructions

Read and follow repository-root `AGENTS.md`; this skill narrows that contract and never overrides it.

## Description And Trigger

Use when creating or validating Phase 0 repository structure, governance files, templates, and empty component boundaries.

## Inputs

- bootstrap documents
- current repository tree

## Preconditions

- the requested work is Phase 0 bootstrap only

## Ordered Workflow

1. Read the bootstrap documents.
2. Confirm the work stays within Phase 0 scope.
3. Create or update repository structure, governance files, templates, and validation assets only.
4. Verify that no forbidden application files were introduced.

## Required Outputs

- repository structure changes
- updated bootstrap artifacts

## Evidence

- file tree changes
- validation output
- explicit confirmation that no application implementation was added

## Validation Checklist

- required directories exist
- forbidden files are absent
- root governance files exist

## Failure Conditions

- product implementation is introduced
- required bootstrap artifacts are missing

## Escalation Conditions

Escalate if the requested change requires application scaffolding, unresolved architecture decisions, or repository settings that cannot be encoded safely in files.

## Forbidden Behavior

- adding product code
- hiding scope expansion

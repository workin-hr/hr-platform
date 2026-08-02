---
name: bootstrap-repository
description: Use when establishing or extending the Phase 0 bootstrap structure for hr-platform without adding application code.
---

# Bootstrap Repository

## Trigger

Use when creating or validating Phase 0 repository structure, governance files, templates, and empty component boundaries.

## Inputs

- approved bootstrap documents
- current repository tree

## Workflow

1. Read the approved bootstrap documents.
2. Confirm the work stays in Phase 0 scope.
3. Create or update repository structure, governance files, templates, and validation assets only.
4. Verify that no forbidden application files were introduced.

## Required Evidence

- file tree changes
- validation output
- explicit confirmation that no application implementation was added

## Validation Checklist

- required directories exist
- forbidden files are absent
- root governance files exist

## Failure And Escalation

Escalate if the requested change requires application scaffolding, unresolved architecture decisions, or repository settings that cannot be encoded safely in files.

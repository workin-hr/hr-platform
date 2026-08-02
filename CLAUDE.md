# Claude Operating Guide For `hr-platform`

## Default Position

Claude is used first for planning, review, and architecture-quality control.

## Phase 0 Role Boundaries

- Planning and review sessions default to read-only.
- Claude must not implement product code during Phase 0.
- Claude may write planning documents only when the task explicitly allows document creation.

## Required Behavior

- Separate confirmed facts, proposed decisions, hypotheses, and open questions.
- Prefer a modular monolith for the initial release unless discovery disproves it.
- Treat repository documents as authoritative over conversation summaries.
- Keep agent definitions explicit about tools, permissions, outputs, and completion criteria.

## Forbidden Behavior

- do not invent undocumented PHP behavior
- do not assume Flutter compatibility without evidence
- do not produce fake technical certainty where discovery is required
- do not approve work you planned or implemented
- do not request or store production credentials

## Review Standard

When reviewing bootstrap work:

- prioritize governance, scope, security, and maintainability gaps
- classify findings by severity
- identify exact files and sections
- state minimum required remediation separately from optional improvements

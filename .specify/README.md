# Spec Kit Integration

Status: **Operational.** The official `specify-cli` was installed and run
against this repository during Phase 0 remediation. This is a real Spec Kit
project, not a placeholder.

## What was executed

```bash
specify --version                                   # 0.8.15 (Python 3.12.3)
specify init --here --integration claude --no-git --force
specify integration install codex --force
```

`--no-git` was used because this repository already has its own Git history;
Spec Kit was not asked to reinitialize it. `--force` skipped the
non-empty-directory confirmation prompt (this is a pre-existing repository,
not an empty scaffold) — every file it touched was verified beforehand
(see `docs/bootstrap/audit-remediation.md`, finding P1-3) to confirm it only
appends within `<!-- SPECKIT START -->` / `<!-- SPECKIT END -->` markers in
existing files and otherwise adds new files, never overwriting unrelated
content.

## What was installed

- `.specify/memory/constitution.md` — the canonical constitution, populated
  with this project's existing 15 principles (see that file for governance
  and ratification status; nothing has been auto-ratified).
- `.specify/templates/` — real `constitution-template.md`, `spec-template.md`,
  `plan-template.md`, `tasks-template.md`, `checklist-template.md`.
- `.specify/scripts/bash/` — real Spec Kit workflow scripts
  (`check-prerequisites.sh`, `common.sh`, `create-new-feature.sh`,
  `setup-plan.sh`, `setup-tasks.sh`).
- `.specify/workflows/` — the bundled `speckit` workflow definition.
- `.specify/integrations/`, `.specify/integration.json`,
  `.specify/init-options.json` — Spec Kit's own integration state, not
  hand-authored.
- `.claude/skills/speckit-*/SKILL.md` — Claude-facing Spec Kit skills
  (`speckit-constitution`, `speckit-specify`, `speckit-clarify`,
  `speckit-plan`, `speckit-tasks`, `speckit-analyze`, `speckit-checklist`,
  `speckit-implement`, `speckit-taskstoissues`).
- `.agents/skills/speckit-*/SKILL.md` — the same set, installed for the Codex
  integration. These are vendor-provided skill definitions and are validated
  only for presence and valid frontmatter by `scripts/validate_phase0.py`,
  not against this repository's custom skill-procedure schema (see
  `docs/agents/skill-catalog.md` for the distinction between vendor-provided
  Spec Kit skills and this repository's own authored governance skills).
- A short marker block appended to `CLAUDE.md` and `AGENTS.md` (between
  `<!-- SPECKIT START -->` / `<!-- SPECKIT END -->`), which Spec Kit itself
  manages. All pre-existing content in both files was preserved verbatim.

## What was not installed

Nothing was run beyond `init` and `integration install`. No `/speckit-*`
skill was invoked to generate a real specification, plan, task list, or
implementation. `docs/product/`, `docs/architecture/`, `docs/adr/`, and every
other Phase 0 governance document remain the actual source of truth for this
repository; Spec Kit provides the tooling to eventually run
Specify -> Clarify -> Plan -> Tasks -> Analyze, it does not yet contain any
product content.

## Phase 0 rule (unchanged)

During Phase 0, only specification and planning commands may be used once
Discovery begins. `/speckit-implement` must not be used to generate or run
product code during Phase 0 — see `.specify/memory/constitution.md` for the
explicit rule and `workflow.md` for the phase sequence.

## Related files

- `.specify/memory/constitution.md` — the constitution (canonical Spec Kit
  location).
- `workflow.md` — the phase sequence and Phase 0 rule.

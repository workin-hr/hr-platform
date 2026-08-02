# Spec Kit Integration

This distinguishes three separate, easily-conflated things. Do not collapse
them into a single "operational" or "not operational" claim — a Codex
re-audit could not run `specify --version` in its own environment while
this repository's real templates and scripts were, and remain, present the
whole time. Neither environment was wrong; CLI availability genuinely
varies by environment, and only the first line below is a repository fact.

```text
Repository integration:      Installed
CLI availability:            Operator-environment dependent
Last verified CLI version:   0.8.15 (verified in the environment that ran
                              this remediation on 2026-08-02; not
                              guaranteed present in every environment —
                              see below)
Constitution status:         Proposed and unratified
```

- **Repository integration: Installed.** `.specify/templates/`,
  `.specify/scripts/bash/`, `.specify/workflows/`, `.specify/memory/constitution.md`,
  and the `speckit-*` skills under `.claude/skills/` and `.agents/skills/`
  are real, committed files. This is true regardless of whether `specify`
  is installed in whatever environment is reading this — verify with
  `ls .specify/templates/` or `scripts/check-bootstrap-prerequisites.sh`,
  which checks repository artifacts and CLI availability separately.
- **CLI availability: Operator-environment dependent.** Whether the
  `specify` command itself is on `PATH` depends entirely on the machine or
  container running it, not on this repository. Run
  `scripts/check-bootstrap-prerequisites.sh` to check the current
  environment; it reports `specify` as present-with-version or
  absent-with-install-guidance, and never fails Phase 0 CI over it (Phase
  0 CI does not install or depend on `specify` itself — see
  `docs/bootstrap/audit-remediation.md`, P2-03).
- **Last verified CLI version: 0.8.15**, confirmed by directly running
  `specify --version` and `specify check` (a non-destructive command that
  only inspects installed coding-agent tools) in the environment that
  performed this remediation — see "What was executed" below for the exact
  output. This is a point-in-time fact about one environment, not a claim
  that every environment has this CLI.
- **Constitution status: Proposed and unratified** — see
  `.specify/memory/constitution.md`'s Governance section; this has not
  changed and is restated here so this file doesn't imply more certainty
  than that one does.

## What was executed

```bash
specify --version                                   # 0.8.15 (Python 3.12.3)
specify init --here --integration claude --no-git --force
specify integration install codex --force
```

### Evidence from this remediation (2026-08-02)

Re-ran `specify --version` and one additional non-destructive verification
command supported by 0.8.15, `specify check` (inspects which coding-agent
CLIs are on `PATH`; writes nothing), in the environment performing this
remediation:

```text
$ specify --version
specify 0.8.15

$ specify check
...
Specify CLI is ready to use!
Tip: Run 'specify self check' to verify you have the latest CLI version
```

Full output is recorded in `docs/bootstrap/audit-remediation.md` (Codex
re-audit remediation, P2-03).

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
- `scripts/check-bootstrap-prerequisites.sh` — run this to check whether
  `specify` (and every other Phase 0 tool) is available in the current
  environment; it reports status without failing Phase 0 CI over CLI
  tools this repository's validation doesn't itself require.

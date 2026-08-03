# Spec Kit Workflow

`Constitution -> Specify -> Clarify -> Plan -> Tasks -> Analyze -> Human approval -> Implementation`

This is now backed by a real, installed Spec Kit toolchain (see `README.md`),
not just a named sequence. Each phase maps to an installed skill:

| Phase | Skill command | Required? |
|---|---|---|
| Constitution | `/speckit-constitution` | Already populated; re-run only for amendments |
| Specify | `/speckit-specify` | Required |
| Clarify | `/speckit-clarify` | Optional, recommended before Plan |
| Plan | `/speckit-plan` | Required |
| Tasks | `/speckit-tasks` | Required |
| Analyze | `/speckit-analyze` | Optional, recommended before Implement |
| — | `/speckit-checklist` | Optional quality gate after Plan |
| Implementation | `/speckit-implement` | Forbidden during Phase 0 (see below) |
| — | `/speckit-taskstoissues` | Optional, converts tasks to GitHub issues |

## Phase 0 Rule

Product implementation remains disabled during Phase 0. `/speckit-implement`
must not be invoked to generate or execute product code until: Discovery
evidence exists for the relevant area, a backing ADR is approved (not merely
Proposed), and a human has explicitly authorized implementation for that
specific piece of work. This mirrors `.specify/memory/constitution.md`'s
"Phase 0 Scope Constraint" and Principle XIII (Evidence-Gated Implementation).

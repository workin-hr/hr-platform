# Skill Catalog

Shared procedural skills live under `.agents/skills/` and are intended for Codex directly and Claude through repository-backed or Spec Kit-backed integration.

## Repository-Authored Skills

These 14 skills follow this repository's own procedure schema (see
`SKILL_SECTIONS` in `scripts/validate_phase0.py`) and are validated against it.

- bootstrap-repository
- create-project-charter
- create-specification
- clarify-requirements
- create-adr
- create-agent-definition
- create-agent-skill
- create-github-backlog
- analyze-legacy-system
- analyze-api-compatibility
- create-test-strategy
- review-bootstrap
- validate-bootstrap
- prepare-pr-evidence

## Vendor-Provided (Spec Kit)

Installed by `specify init` / `specify integration install` and not authored by
this repository. These use upstream GitHub Spec Kit's own frontmatter and
section conventions, not this repository's schema — see
`scripts/validate_phase0.py`'s `validate_skill_files` for how they are
validated differently from the repository-authored skills above.

- speckit-analyze
- speckit-checklist
- speckit-clarify
- speckit-constitution
- speckit-implement
- speckit-plan
- speckit-specify
- speckit-tasks
- speckit-taskstoissues

This list is checked against `.agents/skills/*/SKILL.md` on every build
(`validate_skill_catalog_consistency` in `scripts/validate_phase0.py`): a
skill directory not named somewhere on this page fails validation, so this
catalog cannot silently drift from what actually exists on disk.

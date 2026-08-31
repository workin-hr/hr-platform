# Skill Catalog

Shared procedural skills live under `.agents/skills/` and are intended for Codex directly and Claude through repository-backed or Spec Kit-backed integration.

## Repository-Authored Skills

These 15 skills follow this repository's own procedure schema (see
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
- propagate-change

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

### What the check covers, and what it deliberately does not

**Scope: repository content.** A skill directory that **git ignores** is exempt
from this catalog, from the `SKILL_SECTIONS` structure check, from the
broken-link scan and from the Phase 0 forbidden-file scan.

The reason is that an ignored path is, by definition, not repository content.
Tooling such as `npx skills add ...` installs third-party skills into
`.agents/skills/` and symlinks them into `.claude/skills/`; they exist only on
the machine that installed them, never reach CI or another clone, and use their
authors' own schema rather than this repository's. Requiring them here would
mean listing third-party tool names in a governance document to satisfy a check
that nobody else can reproduce or fix — and would break `validate_phase0.py`
locally for anyone who installs one.

**Untracked but *not* ignored skills are still checked.** That is the case the
drift protection exists for: a genuinely new repository skill is untracked right
up until it is committed, and exempting it would remove the guard exactly when it
matters.

The exemption **fails closed**. If git is unavailable, the tree is not a
repository, or `git check-ignore` returns anything unexpected, every path is
checked. Git being unable to answer must never silently exempt something from a
governance control.

Regression cases covering all four scanners, both directions of the exemption,
and the outside-a-repository fallback are in `scripts/test_validate_phase0.py`.

Every repository-authored skill inherits the root `AGENTS.md` contract through
its mandatory `Canonical Instructions` section. `propagate-change` applies
that contract's synchronized-artifact check before handoff.

## Why The 15 Repository-Authored Skills Are Not Also Under `.claude/skills/`

Considered and declined for now (see `docs/bootstrap/decision-log.md`
D-010), not an oversight. The `speckit-*` skills exist in both
`.agents/skills/` and `.claude/skills/` because `specify-cli` installs
near-full duplicate copies (Claude-specific frontmatter keys added, body
otherwise identical) so they are directly invocable as `/speckit-*` — see
`scripts/validate_phase0.py`'s `validate_skill_files` for how those two
copies are validated differently from the schema below.

Doing the same for these 15 would mean maintaining two copies of every
skill body with no automated content-parity check between them — a real
drift risk, worse than the catalog-listing gap this file's own history
just fixed, since content can drift silently in ways a name-presence check
cannot catch. A thin `.claude/skills/<name>/SKILL.md` that only pointed at
the canonical `.agents/skills/<name>/SKILL.md` would avoid duplication, but
`validate_skill_files` currently requires every non-`speckit-*` skill file
(regardless of which base directory it is under) to carry the full
`SKILL_SECTIONS` structure, so a thin pointer would need its own validator
carve-out first.

Revisit if these skills need direct `/name` invocation inside Claude Code
sessions badly enough to justify building and validating that carve-out —
until then, they remain reachable through repository-backed or Spec
Kit-backed integration, as stated above.

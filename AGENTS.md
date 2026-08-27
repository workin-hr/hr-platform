# Repository Engineering Instructions

## Canonical Source Of Truth

This `AGENTS.md` is the single canonical instruction file for every human and
AI coding tool working in this repository. Tool-specific files must import or
point to this file and may add only runtime-specific mechanics; they must not
repeat or redefine repository policy.

Instruction precedence is:

1. the current explicit user request;
2. this `AGENTS.md`;
3. the authoritative domain artifacts linked below;
4. tool-specific agent or skill instructions.

If a lower-level artifact conflicts with this file, stop and resolve the drift
in the same change. `CLAUDE.md` imports this file with `@AGENTS.md`. Repository
agent definitions under `.claude/agents/` and `.codex/agents/`, and
repository-authored skills under `.agents/skills/`, must declare that they
inherit this contract.

## Read First

- Bootstrap plan: [docs/bootstrap/bootstrap-plan.md](docs/bootstrap/bootstrap-plan.md)
- Execution checklist: [docs/bootstrap/execution-checklist.md](docs/bootstrap/execution-checklist.md)
- Project charter: [docs/bootstrap/project-charter.md](docs/bootstrap/project-charter.md)
- Open questions: [docs/bootstrap/open-questions.md](docs/bootstrap/open-questions.md)
- Risk register: [docs/bootstrap/risk-register.md](docs/bootstrap/risk-register.md)

## Domain Maps

- Product specifications: [specs](specs)
- Architecture documentation: [docs/architecture](docs/architecture)
- ADRs: [docs/adr](docs/adr)
- Security rules: [docs/security](docs/security)
- Testing strategy: [docs/testing](docs/testing)
- Legacy evidence: [docs/legacy](docs/legacy)
- API contracts and compatibility: [contracts](contracts), [docs/api](docs/api)
- Migration documents: [docs/migration](docs/migration)
- Device compatibility: [docs/devices](docs/devices)
- Operational runbooks: [docs/operations](docs/operations)

## Mandatory Workflow

`Issue -> Specification -> Clarification -> Architecture and test impact -> Human approval -> Isolated implementation branch -> Automated verification -> Independent review -> Human merge`

## Mandatory Change Propagation

No implementation, configuration, schema, contract, automation, agent, or
skill change is complete when only its primary file changed. Before editing,
identify the affected artifact set; before handoff, update and validate all of
it in the same branch.

At minimum:

| Changed concern | Required synchronized artifacts |
| --- | --- |
| Runtime behavior or bug fix | regression tests plus the owning module, migration, decision, risk, or operations document |
| API route, request, response, or authentication contract | contract examples/OpenAPI where applicable, route or endpoint inventories, compatibility evidence, and client-facing documentation |
| Database schema, persistence, or ETL | migrations/schema contracts, schema and entity inventories, ETL manifests/loaders/reconciliation, deployment order, rollback, and data-integrity tests |
| Configuration, build, CI, or deployment | configuration precedence docs, examples/templates, validation scripts, deployment/runbook docs, and monitoring implications |
| Agent role or permission | this file when policy changes, affected `.claude/agents/` and `.codex/agents/`, `docs/agents/responsibility-matrix.md`, enforcement configuration, and validation tests |
| Skill or workflow | canonical `.agents/skills/` source, `docs/agents/skill-catalog.md`, any generated/vendor integration copy that actually owns the skill, and skill/validator tests |
| Inventory or collection-backed behavior | the authoritative inventory/collection, its producer or validator, and a drift test proving code and collection agree |
| Architecture, product, security, or operational decision | the owning ADR/spec/plan/runbook plus `docs/bootstrap/decision-log.md`, open questions, and risk register when affected |

Every behavior-changing code/configuration/schema change must update at least
one durable explanatory document. Do not satisfy this rule with comments alone.
Purely mechanical changes with no behavior or workflow impact must record the
no-documentation-impact reason in the delivery evidence.

The implementation handoff and PR evidence must list:

- documents, contracts, inventories/collections, skills, and agents updated;
- affected categories reviewed and found not applicable, with reasons;
- exact validation proving the synchronized artifacts do not drift.

## Global Rules

- Repository files are sources of truth. Chat history is not.
- Keep policy in this file and details in their owning artifacts; link rather than copy.
- No direct writes to `main`.
- Planning agents are read-only unless explicitly assigned a documentation task.
- Review agents are always read-only.
- Implementers cannot approve or merge their own work.
- Agents may access a production database only when the user explicitly authorizes a specific read-only evidence or compatibility check.
- Production database access is strictly read-only: enforce a read-only transaction and use only non-mutating queries such as `SELECT` or `SHOW`. Never insert, update, delete, replace, repair, migrate, or otherwise change production data, schema, routines, permissions, or configuration. A session setting used solely to enforce read-only transaction mode is allowed.
- Never print, log, commit, or otherwise expose production credentials. Access to biometric data, private keys, and unrestricted organization tokens remains prohibited.
- No agent may silently resolve unclear requirements or make irreversible architecture decisions.

## Artifact Ownership Map

- Repository policy and workflow: this `AGENTS.md`.
- Claude runtime entrypoint: `CLAUDE.md`, which imports this file.
- Agent roles and permissions: `.claude/agents/`, `.codex/agents/`, and
  `docs/agents/responsibility-matrix.md`.
- Reusable procedures: `.agents/skills/`; the complete checked catalog is
  `docs/agents/skill-catalog.md`. `.claude/skills/speckit-*` are upstream
  integration copies, not a second repository-authored source.
- Spec Kit principles: `.specify/memory/constitution.md`, subordinate to this
  repository-wide contract for instruction precedence.
- Accepted architectural/product decisions: `docs/adr/` and
  `docs/bootstrap/decision-log.md`.
- Current implementation sequencing: the approved plan/specification for the
  active issue or wave.
- API/schema compatibility: `contracts/`, `docs/api/`, migration inventories,
  and executable drift/inventory tests.

<!-- SPECKIT START -->
For additional context about technologies to be used, project structure,
shell commands, and other important information, read the current plan
<!-- SPECKIT END -->

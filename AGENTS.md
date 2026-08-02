# Agent Rules For `hr-platform`

## Repository Mode

This repository is in Phase 0 bootstrap mode.

- Bootstrap documentation, governance, and validation work is allowed.
- Application implementation is forbidden.
- Empty component boundaries with `README.md` files are allowed.

## Sources Of Truth

- Repository files are authoritative.
- Approved bootstrap documents under `docs/bootstrap/` are authoritative for Phase 0 execution.
- Chat history is not a source of truth once information is written into repository files.

## Global Agent Boundaries

- No agent may write directly to `main`.
- No agent may approve its own work.
- Reviewing agents must remain read-only.
- Planning agents must not silently become implementation agents.
- No agent may access production systems or production data.
- No agent may add secrets, credentials, or unrestricted organization tokens to the repository.

## Allowed Bootstrap Work

- repository structure and documentation
- agent and skill definitions
- GitHub issue and pull request templates
- validation scripts and lightweight CI
- ADR, architecture, testing, and discovery templates
- GitHub setup instructions for settings that cannot be stored as files

## Forbidden Work

- Spring Boot, Java, Maven, or Gradle application scaffolding
- Next.js, Node, or TypeScript application scaffolding
- Flutter application scaffolding
- .NET gateway scaffolding
- SQL migrations or schema definitions
- Dockerized application stacks
- Kubernetes manifests
- placeholder business logic or fake production architecture

## Required Read Order For Implementation Agents

1. `docs/bootstrap/approved-bootstrap-plan.md`
2. `docs/bootstrap/decisions.md`
3. `docs/bootstrap/open-questions.md`
4. `docs/bootstrap/risks.md`

## Escalation

Escalate when:

- a task requires changing approved Phase 0 scope
- a new tool needs installation
- a repository or organization setting cannot be represented safely in files
- legacy behavior is assumed without evidence
- an agent needs broader permissions than currently documented

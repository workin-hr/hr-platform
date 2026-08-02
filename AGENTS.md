# AGENTS Navigation

## Read First

- Bootstrap plan: [docs/bootstrap/bootstrap-plan.md](docs/bootstrap/bootstrap-plan.md)
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

## Global Rules

- Repository files are sources of truth. Chat history is not.
- No direct writes to `main`.
- Planning agents are read-only unless explicitly assigned a documentation task.
- Review agents are always read-only.
- Implementers cannot approve or merge their own work.
- No agent may access production databases, biometric data, private keys, or unrestricted organization tokens.
- No agent may silently resolve unclear requirements or make irreversible architecture decisions.

<!-- SPECKIT START -->
For additional context about technologies to be used, project structure,
shell commands, and other important information, read the current plan
<!-- SPECKIT END -->

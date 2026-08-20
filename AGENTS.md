# AGENTS Navigation

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

## Global Rules

- Repository files are sources of truth. Chat history is not.
- No direct writes to `main`.
- Planning agents are read-only unless explicitly assigned a documentation task.
- Review agents are always read-only.
- Implementers cannot approve or merge their own work.
- Agents may access a production database only when the user explicitly authorizes a specific read-only evidence or compatibility check.
- Production database access is strictly read-only: enforce a read-only transaction and use only non-mutating queries such as `SELECT` or `SHOW`. Never insert, update, delete, replace, repair, migrate, or otherwise change production data, schema, routines, permissions, or configuration. A session setting used solely to enforce read-only transaction mode is allowed.
- Never print, log, commit, or otherwise expose production credentials. Access to biometric data, private keys, and unrestricted organization tokens remains prohibited.
- No agent may silently resolve unclear requirements or make irreversible architecture decisions.

<!-- SPECKIT START -->
For additional context about technologies to be used, project structure,
shell commands, and other important information, read the current plan
<!-- SPECKIT END -->

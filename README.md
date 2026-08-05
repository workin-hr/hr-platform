# HR Platform

`hr-platform` is the repository for the HR platform modernization effort.
As of Wednesday, August 5, 2026, it contains both:

- the Phase 0 bootstrap/governance/documentation system for the overall program
- the first explicitly authorized Phase 1 implementation slice under `backend/`

## Current Purpose

This repository is the source of truth for:

- bootstrap governance
- planning and architecture documentation
- the accepted backend implementation baseline under `backend/`
- agent operating rules
- reusable procedural skills
- specification workflow
- backlog and GitHub governance guidance
- validation and quality controls

## Current Boundaries

Repository-wide, Phase 0 still governs most component boundaries.
`backend/` is the only directory whose implementation lock has been
lifted so far, per `docs/bootstrap/decision-log.md` D-028.

Still allowed everywhere:

- repository structure
- documentation
- specifications
- ADR placeholders
- issue and pull-request templates
- validation scripts and CI
- empty future component boundaries with `README.md`

Still forbidden outside explicitly unlocked directories:

- Spring Boot code outside `backend/`
- Next.js code
- Flutter application code committed into this repository
- .NET gateway code
- SQL migrations outside `backend/`
- production configuration
- business-domain implementation outside `backend/`

Currently unlocked:

- `backend/`: real Spring Boot 4.1 / Java 25 code, SQL migrations, and
  core backend business-domain implementation are allowed there only

## Working Model

Use the repository workflow defined in [AGENTS.md](AGENTS.md) and the bootstrap instructions under [docs/bootstrap](docs/bootstrap).

For the current owner-by-owner follow-through order, use [docs/bootstrap/execution-checklist.md](docs/bootstrap/execution-checklist.md).

Human approval is required before work moves from planning into implementation.

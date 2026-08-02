# ADR Index

Use ADRs for decisions that materially affect repository strategy, architecture, migration, compatibility, security, observability, or testing obligations.

All ADRs use one authoritative format: a `## Metadata` table (ADR ID, Title,
Status, Date, Owners, Deciders, Related Issues, Supersedes, Superseded By),
followed by `## Context`, `## Decision`, `## Alternatives Considered`,
`## Consequences`, `## Risks`, `## Validation Evidence`, `## Open Questions`.
See `ADR-0000-template.md`. Valid `Status` values are `Proposed`, `Accepted`,
`Rejected`, `Superseded`, `Deferred`. Every ADR below is currently `Proposed`
— the `## Decision` section states a candidate direction, not an approved one.
`scripts/validate_phase0.py` and `.agents/skills/create-adr/scripts/validate-adr.sh`
both enforce this structure; see `docs/bootstrap/audit-remediation.md` (P1-1).

## Template

- `ADR-0000-template.md`

## Proposed ADRs

- `ADR-0001-repository-strategy.md`
- `ADR-0002-modular-monolith-baseline.md`
- `ADR-0003-api-versioning-and-flutter-compatibility.md`
- `ADR-0004-mysql-to-postgresql-migration-approach.md`
- `ADR-0005-authentication-and-authorization-direction.md`
- `ADR-0006-attendance-edge-gateway-direction.md`
- `ADR-0007-testing-and-quality-gate-strategy.md`
- `ADR-0008-observability-baseline.md`

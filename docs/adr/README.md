# ADR Index

Use ADRs for decisions that materially affect repository strategy, architecture, migration, compatibility, security, observability, or testing obligations.

All ADRs use one authoritative format: a `## Metadata` table (ADR ID, Title,
Status, Date, Owners, Deciders, Related Issues, Supersedes, Superseded By),
followed by `## Context`, `## Decision`, `## Alternatives Considered`,
`## Consequences`, `## Risks`, `## Validation Evidence`, `## Open Questions`.
See `ADR-0000-template.md`. Valid `Status` values are `Proposed`, `Accepted`,
`Rejected`, `Superseded`, `Deferred`. **Update 2026-08-04**: ADR-0002
(Part A only — see that ADR's Metadata Date field for the
per-part-status caveat; Part B remains genuinely `Proposed`) and
ADR-0005 are now `Accepted`, per `docs/bootstrap/decision-log.md`
D-016/D-017. Every other ADR below remains `Proposed` — the
`## Decision` section states a candidate direction, not an approved
one.

`scripts/validate_phase0.py::validate_adrs()` discovers real ADR files
dynamically (`docs/adr/ADR-[0-9][0-9][0-9][0-9]-*.md`, excluding the
template) — a new ADR added to this directory is picked up and validated
automatically, with no change needed to the validator itself. It also
detects duplicate ADR numbers, invalid file names, ADRs missing from this
index, and index entries pointing at files that don't exist.
`.agents/skills/create-adr/scripts/validate-adr.sh` delegates to the same
implementation (`validate_phase0.py --validate-adr <file>`) rather than
maintaining a second, divergent copy of these rules — see
`docs/bootstrap/audit-remediation.md` (P1-1, P2-02).

## Template

- `ADR-0000-template.md`

## Accepted ADRs

- `ADR-0002-modular-monolith-baseline.md` — **Part A only** (strategic
  direction); Part B (tenant-isolation pattern) remains `Proposed`
- `ADR-0005-authentication-direction.md`

## Proposed ADRs

- `ADR-0001-repository-strategy.md`
- `ADR-0003-api-versioning-and-flutter-compatibility.md`
- `ADR-0004-mysql-to-postgresql-migration-approach.md`
- `ADR-0006-attendance-edge-gateway-direction.md`
- `ADR-0007-testing-and-quality-gate-strategy.md`
- `ADR-0008-observability-baseline.md`
- `ADR-0009-dashboard-vs-desktop-admin-client.md`
- `ADR-0010-authorization-model.md`

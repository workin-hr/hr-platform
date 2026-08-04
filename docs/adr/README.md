# ADR Index

Use ADRs for decisions that materially affect repository strategy, architecture, migration, compatibility, security, observability, or testing obligations.

All ADRs use one authoritative format: a `## Metadata` table (ADR ID, Title,
Status, Date, Owners, Deciders, Related Issues, Supersedes, Superseded By),
followed by `## Context`, `## Decision`, `## Alternatives Considered`,
`## Consequences`, `## Risks`, `## Validation Evidence`, `## Open Questions`.
See `ADR-0000-template.md`. Valid `Status` values are `Proposed`, `Accepted`,
`Rejected`, `Superseded`, `Deferred`. **Update 2026-08-05**: 9 of 10 ADRs
are now `Accepted` — ADR-0001, ADR-0002 (both Part A and Part B),
ADR-0003, ADR-0004, ADR-0005, ADR-0006 (Part A only; Part B remains
`Proposed`, blocked on PMR-04), ADR-0007, ADR-0008, and ADR-0009 — per
`docs/bootstrap/decision-log.md` D-016 through D-025. Only ADR-0010
remains `Proposed`, deliberately, fully open on all six of its
dimensions, per its own Decision section.

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

- `ADR-0001-repository-strategy.md`
- `ADR-0002-modular-monolith-baseline.md` — **Both Part A and Part B**
  (tenant-isolation pattern: RLS, accepted 2026-08-05)
- `ADR-0003-api-versioning-and-flutter-compatibility.md`
- `ADR-0004-mysql-to-postgresql-migration-approach.md`
- `ADR-0005-authentication-direction.md`
- `ADR-0006-attendance-edge-gateway-direction.md` — **Part A only**
  (adapter/SPI architectural pattern); Part B (vendor-specific
  gateway-or-not decisions) remains `Proposed`, blocked on PMR-04
- `ADR-0007-testing-and-quality-gate-strategy.md`
- `ADR-0008-observability-baseline.md`
- `ADR-0009-dashboard-vs-desktop-admin-client.md` — Option E, role-based
  split; all Validation Evidence items resolved 2026-08-05

## Proposed ADRs

- `ADR-0010-authorization-model.md` — deliberately, fully open on all
  six dimensions; not a candidate direction awaiting sign-off

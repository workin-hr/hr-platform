# ADR Index

Use ADRs for decisions that materially affect repository strategy, architecture, migration, compatibility, security, observability, or testing obligations.

All ADRs use one authoritative format: a `## Metadata` table (ADR ID, Title,
Status, Date, Owners, Deciders, Related Issues, Supersedes, Superseded By),
followed by `## Context`, `## Decision`, `## Alternatives Considered`,
`## Consequences`, `## Risks`, `## Validation Evidence`, `## Open Questions`.
See `ADR-0000-template.md`. Valid `Status` values are `Proposed`, `Accepted`,
`Rejected`, `Superseded`, `Deferred`. **Update 2026-08-05**: all 10 ADRs
now have a recorded decision — 9 are fully `Accepted` (ADR-0001, ADR-0002
both parts, ADR-0003, ADR-0004, ADR-0005, ADR-0006 Part A, ADR-0007,
ADR-0008, ADR-0009) per `docs/bootstrap/decision-log.md` D-016 through
D-025, and ADR-0010 (authorization model, all six dimensions) is
Accepted per D-026. Only ADR-0006's Part B (final vendor
protocol/connectivity detail) remains open, blocked on PMR-04.

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
- `ADR-0010-authorization-model.md` — all six dimensions decided
  2026-08-05; detailed reference: `docs/architecture/authorization-model.md`.
  **Dimension 2's identity/membership sequence and Dimension 7's RLS step
  describe a model Phase 1 does not have** — see ADR-0011, ADR-0012
- `ADR-0011-phase-sequencing.md` — implementation, then storage, then
  modernization; strict legacy API contract parity and full 38-module
  replacement in Phase 1 (accepted 2026-08-16)
- `ADR-0012-phase-1-tenant-isolation.md` — tenant isolation without
  row-level security while Phase 1 runs on MySQL, with its compensating
  controls and fail-closed obligations (accepted 2026-08-16)
- `ADR-0013-phase1-mysql-profile-bootstrap.md` — the `phase1-mysql`
  Spring profile that points the application at legacy MySQL, inactive
  by default and guarded by an ArchUnit profile-coverage test, becoming
  the normal runtime only at the single Phase 1 cutover (accepted
  2026-08-17 with four owner-required amendments, `docs/bootstrap/decision-log.md` D-043)

## Proposed ADRs

- `ADR-0014-platform-admin-web-authentication.md` — how the Next.js
  platform-admin surface authenticates: ADR-0005's rotating-refresh model
  carried over `HttpOnly`/`Secure`/`SameSite` cookies rather than
  browser-stored bearer tokens, with MFA and step-up on destructive
  actions (proposed 2026-08-30, **not approved**)

ADR-0006's Part B (final vendor protocol/connectivity detail) is a further
open ADR sub-item, tracked within `ADR-0006-attendance-edge-gateway-direction.md`
itself rather than as a separately `Proposed` ADR.

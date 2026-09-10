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
Accepted per D-026. ADR-0006's Part B (final vendor
protocol/connectivity detail) was the last open item until the repository
owner accepted it on 2026-09-02 (D-164, hardware checklist recorded as a
condition).

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
- `ADR-0006-attendance-edge-gateway-direction.md` — **Part A** (adapter/SPI
  architectural pattern, D-023) **and Part B** (ZKTeco terminals push over
  ADMS, edge gateway as fallback — D-164, accepted 2026-09-02 with the
  hardware checklist in
  `../superpowers/specs/2026-09-02-attendance-device-ingestion-design.md`
  §4.3 as a recorded condition)
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
  replacement in Phase 1 (accepted 2026-08-16). **Phase 2, the storage
  migration, is superseded by ADR-0017**; Phase 1's contract stands
- `ADR-0012-phase-1-tenant-isolation.md` — tenant isolation without
  row-level security while Phase 1 runs on MySQL, with its compensating
  controls and fail-closed obligations (accepted 2026-08-16)
- `ADR-0015-platform-admin-jte-authentication.md` — the platform-admin web
  surface is **server-rendered JTE inside the existing Spring
  application**, authenticated by a server-side session; no token is
  issued to or held by the browser because there is no separate frontend.
  Carries forward MFA/TOTP with seed custody, bounded step-up, throttling,
  per-request authorization, session invalidation and auditability, and
  adds CSRF and session-cookie hardening, which the in-process model makes
  first-class (accepted 2026-09-01). **Its authentication model is superseded
  by ADR-0018**; the surface, session, CSRF and audit decisions stand
- `ADR-0016-full-dashboard-port-to-jte.md` — the **whole** PHP dashboard is
  reproduced in JTE inside the backend: the same pages, the same design
  (its stylesheets copied verbatim, its 772 labels converted), and all
  three login audiences. Supersedes ADR-0009 Option E in scope, after the
  owner's decision to run the VPS on Java and MySQL with no PHP turned
  every unported capability into a permanent loss — four admin pages
  write data no API endpoint can (accepted 2026-09-04)
- `ADR-0013-phase1-mysql-profile-bootstrap.md` — the `phase1-mysql`
  Spring profile that points the application at legacy MySQL, inactive
  by default and guarded by an ArchUnit profile-coverage test, becoming
  the normal runtime only at the single Phase 1 cutover (accepted
  2026-08-17 with four owner-required amendments, `docs/bootstrap/decision-log.md` D-043).
  **Amended by ADR-0017**: the profile split is gone and this configuration
  is the application's only persistence
- `ADR-0019-performance-measurement-toolchain.md` — a Micrometer Prometheus
  registry reads the meters the application already maintained, k6 drives the
  three surfaces from the compose network, query-count assertions catch the
  round-trip growth a profiler reports as "time in JDBC", and JFR answers the
  rest. The scrape is exposed in `local`/`integration` only and load runs stay
  off per-PR CI. Answers part of ADR-0008's deferred question; the deployed
  monitoring stack stays deferred (accepted 2026-09-10)
- `ADR-0018-one-administrator-one-password.md` — the dashboard signs in the
  way PHP's does: one administrator, one password, no phone and no second
  factor, with Java's guards behind the form -- a bcrypt hash, a per-client
  miss budget, session rotation, CSRF, audit. TOTP, step-up approvals and the
  bearer API are removed (accepted 2026-09-08, owner's choice)
- `ADR-0017-mysql-is-the-production-database.md` — MySQL is the production
  database and stays so; the PostgreSQL domain, Flyway, the ETL and the
  profile split are removed rather than left dormant. Supersedes ADR-0004
  and ADR-0011's Phase 2 (accepted 2026-09-08)

## Superseded ADRs

- `ADR-0004-mysql-to-postgresql-migration-approach.md` — superseded by
  ADR-0017: there is no migration to PostgreSQL

Listed here so index-driven readers and tooling do not treat them as
active. The document is retained, not deleted: the reasoning behind the
requirements that survived the supersession is recorded in it.

- `ADR-0014-platform-admin-web-authentication.md` — **superseded by
  ADR-0015 on 2026-09-01** (**D-151**). Designed the platform-admin
  surface as a Next.js app with a server-side BFF holding the token pair.
  The premise was corrected by the repository owner: the admin web is JTE
  inside the existing Spring application. Do not implement from this ADR —
  read `ADR-0015` instead

## Proposed ADRs

None. ADR-0006's Part B (final vendor protocol/connectivity detail) is a further
open ADR sub-item, tracked within `ADR-0006-attendance-edge-gateway-direction.md`
itself rather than as a separately `Proposed` ADR.

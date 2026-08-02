# System Context

## Current Context

- PHP backend shared by admin and employee applications
- PHP admin frontend
- Flutter employee applications
- MySQL database
- attendance devices and vendor ecosystems

## Target Context (Intended Direction — Subject To Discovery And ADR Approval)

None of the items below are decided. Each is a candidate direction backed
by a specific ADR, and every one of those ADRs is currently `Proposed`, not
`Accepted` — see `docs/adr/README.md`. This section must not be read as
more settled than the ADRs it summarizes.

- `hr-platform` as the planning and future implementation repository —
  proposed direction, see ADR-0001 (Repository Strategy)
- Java and Spring Boot backend — proposed direction, see ADR-0002 (Modular
  Monolith Baseline); requires an approved ADR before implementation begins
- Next.js admin portal — proposed direction; no dedicated ADR yet, requires
  one before implementation begins
- Flutter compatibility retained where required by validated client
  behavior — proposed direction, see ADR-0003 (API Versioning And Flutter
  Compatibility)
- PostgreSQL as the target database, unless an approved ADR changes the
  direction — proposed direction, see ADR-0004 (MySQL-To-PostgreSQL
  Migration Approach)
- local .NET edge gateway for attendance integration scenarios where
  needed — explicitly a candidate direction pending vendor and device
  discovery, see ADR-0006 (Attendance Edge-Gateway Direction)

## Open Context Questions

- exact external integrations
- identity and authorization boundaries
- operational environments and deployment topology

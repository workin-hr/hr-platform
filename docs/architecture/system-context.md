# System Context

## Current Context

- PHP backend shared by admin and employee applications
- PHP admin frontend
- Flutter employee applications
- MySQL database
- attendance devices and vendor ecosystems

## Target Context (Intended Direction — Subject To Discovery And ADR Approval)

**Mixed status — read each bullet, not this heading.** This section was written
when every ADR below was `Proposed`; most have since been accepted
(ADR-0001 through ADR-0005, and ADR-0009 through ADR-0013), while a few remain
candidate directions. Each bullet carries its own status, and
`docs/adr/README.md` is authoritative. This section must not be read as more
settled — or less settled — than the ADRs it summarizes.

- `hr-platform` as the planning and future implementation repository —
  **accepted**, see ADR-0001 (Repository Strategy)
- Java and Spring Boot backend — **accepted**, see ADR-0002 (Modular
  Monolith Baseline)
- Next.js admin portal — direction confirmed for the narrowed
  platform-admin surface, see ADR-0009 §"Technology For The Platform-Admin
  Web Surface" (accepted 2026-08-05, `docs/bootstrap/decision-log.md`
  D-025). Its authentication is **not** yet decided — see ADR-0014
  (Proposed), which must be accepted before implementation begins
- Flutter compatibility retained where required by validated client
  behavior — **accepted**, see ADR-0003 (API Versioning And Flutter
  Compatibility)
- PostgreSQL as the target database — **accepted**, see ADR-0004 (MySQL-To-PostgreSQL
  Migration Approach)
- local .NET edge gateway for attendance integration scenarios where
  needed — explicitly a candidate direction pending vendor and device
  discovery, see ADR-0006 (Attendance Edge-Gateway Direction)

## Open Context Questions

- exact external integrations
- identity and authorization boundaries
- operational environments and deployment topology

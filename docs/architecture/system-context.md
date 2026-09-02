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
- **JTE admin portal, inside the existing Spring application** — one
  deployment, server-side rendered, on the application's existing
  authentication and session model. See **ADR-0015** (accepted
  2026-09-01, **D-151**), which **supersedes ADR-0014**. An earlier
  direction of *Next.js with a server-side BFF* was recorded here and in
  ADR-0009; the repository owner corrected the premise on 2026-09-01, and
  the BFF's security surface — a second credential store, rotation-result
  custody, browser-token enforcement, cookie topology — is removed rather
  than deferred, because none of it exists without a separate frontend.
  What remains outstanding are implementation prerequisites: MFA/TOTP with
  seed custody, step-up bounds, throttling, session bounds, and — new to
  the in-process model — CSRF and session-cookie hardening
- Flutter compatibility retained where required by validated client
  behavior — **accepted**, see ADR-0003 (API Versioning And Flutter
  Compatibility)
- PostgreSQL as the target database — **accepted as the target**, see ADR-0004
  (MySQL-To-PostgreSQL Migration Approach). **The migration itself is Phase 2,
  out of scope now, and not to be advanced (D-151)**; the current phase ports
  PHP to Java against the existing MySQL schema
- local .NET edge gateway for attendance integration scenarios where
  needed — explicitly a candidate direction pending vendor and device
  discovery, see ADR-0006 (Attendance Edge-Gateway Direction) — **update
  2026-09-02**: D-158 (accepted) makes device-initiated ADMS push the
  primary ZKTeco path and the gateway a fallback; see
  `docs/superpowers/specs/2026-09-02-attendance-device-ingestion-design.md`

## Open Context Questions

- exact external integrations
- identity and authorization boundaries
- operational environments and deployment topology

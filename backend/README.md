# Backend Boundary

Real Spring Boot 4.1 / Java 25 backend implementation. Phase 0's
blanket "no application scaffolding" prohibition was lifted for this
directory specifically on 2026-08-05
(`docs/bootstrap/decision-log.md` D-028), after every architecture ADR
this depends on reached `Accepted` and the Migration-Readiness Gate's
minimum conditions were satisfied. No other component directory
(`admin-web/`, `edge-gateway/`, `infrastructure/`, `contracts/`,
`specs/`) is unlocked by this — each needs its own separate, explicit
transition decision.

Architecture references: `docs/architecture/module-boundaries.md`
(candidate module diagram), `docs/adr/ADR-0002-modular-monolith-baseline.md`
(modular monolith + RLS tenant isolation), `docs/adr/ADR-0005-authentication-direction.md`
(auth direction), `docs/adr/ADR-0010-authorization-model.md` and
`docs/architecture/authorization-model.md` (authorization model).

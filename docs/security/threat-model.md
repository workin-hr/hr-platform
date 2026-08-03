# Threat Model

**Status: Not started — pending Discovery.** This file defines the template
and method the actual system threat model will use. It contains no real
findings yet, because Discovery (legacy PHP, Flutter compatibility, MySQL
migration, attendance-device) has not produced the evidence a real threat
model requires. Do not fill in specific threats, mitigations, or residual
risk ratings based on assumption — every row must cite evidence.

## Method

STRIDE (Spoofing, Tampering, Repudiation, Information Disclosure, Denial of
Service, Elevation of Privilege), applied per trust boundary once the
boundaries below are actually known. This is an explicit choice, not a
placeholder — if a future architecture decision picks a different method,
record that as an ADR and update this line.

## Scope (to be completed during Discovery)

- **Assets**: what data and capabilities need protecting (employee/customer
  PII, attendance/biometric data, authentication credentials, tenant data
  isolation, source code, CI/CD credentials)
- **Actors**: who interacts with the system (employees, admins, customers,
  attendance devices, vendor APIs, internal agents/automation)
- **Trust boundaries**: where control or privilege changes (client <-> API,
  API <-> database, tenant <-> tenant, edge gateway <-> device, edge gateway
  <-> cloud, agent <-> repository)
- **Data flows**: how data moves across each trust boundary above

## Threat Register (empty — populate only with evidenced findings)

| Boundary | STRIDE Category | Threat | Likelihood | Impact | Mitigation | Residual Risk | Owner | Evidence |
| -------- | ---------------- | ------ | ---------- | ------ | ---------- | -------------- | ----- | -------- |
| _(none yet — populate only once Discovery produces real evidence)_ | | | | | | | | |

## Specific Areas Requiring Coverage Once Discovery Exists

- Authentication and authorization (see ADR-0005, currently Proposed)
- Multi-tenant isolation (see `.specify/memory/constitution.md` Principle IV)
- PII handling (see `docs/security/logging-and-privacy.md`)
- Biometric data from attendance devices (see `docs/devices/`)
- Device-gateway and vendor-integration trust boundaries (see ADR-0006,
  currently Proposed)
- Database migration exposure window (see `docs/migration/`)
- Agent access boundaries (see `docs/agents/operating-model.md` Enforcement
  Layers — this is itself a threat surface: what happens if an agent's
  tool scope is misconfigured or a human misapplies Codex sandbox settings)
- Supply-chain risk (dependencies, CI actions, MCP servers — see
  `docs/tools/tool-catalog.md`)
- Abuse cases (e.g. attendance spoofing, tenant data leakage via
  misconfigured API scoping, credential stuffing)

## Evidence

None yet.

## Open Questions

- Who is the accountable owner for maintaining this threat model once
  Discovery starts?
- Does any compliance requirement (e.g. regional data-protection law
  applicable to the customers in scope) mandate a specific threat-modeling
  cadence or method beyond STRIDE?

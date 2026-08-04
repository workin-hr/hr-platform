# Threat Model

**Status: Discovery in progress.** This file defines the template and
method the actual system threat model will use. It contains one real,
evidenced finding below (from the `hr-legacy` `advances` module deep-dive);
everything else remains pending further Discovery passes. Do not fill in
specific threats, mitigations, or residual risk ratings based on
assumption — every row must cite evidence.

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

## Threat Register (populate only with evidenced findings)

| Boundary | STRIDE Category | Threat | Likelihood | Impact | Mitigation | Residual Risk | Owner | Evidence |
| -------- | ---------------- | ------ | ---------- | ------ | ---------- | -------------- | ----- | -------- |
| Tenant ↔ tenant, within `apis/api/advances/` | Tampering, Elevation of Privilege, Information Disclosure | A `COMPANY_ADMIN`/`HR` user authenticated as *any* tenant can approve, reject, record a payment against, or delete a salary-advance record belonging to a *different* tenant, and can create an advance for an `employee_id` belonging to a different tenant, purely by supplying its numeric ID/employee_id. `approve.php`, `reject.php`, and `pay.php` run their `UPDATE`/`SELECT` with no `company_id` (or joined `employees.company_id`) filter at all. `delete.php` checks ownership only for the `EMPLOYEE` role, not for `COMPANY_ADMIN`/`HR`. `create.php` never validates that a company/HR-supplied `employee_id` belongs to the caller's own `company_id`. This is confirmed **inconsistent within the same module**: `update.php`, `one.php`, and `list.php` in this exact directory all correctly scope every query through a join on `employees.company_id`, proving the isolation pattern is known and simply omitted on these five endpoints. | Not independently assessed (would require confirming how guessable/enumerable advance IDs are in practice, and whether any WAF/rate-limit sits in front of the API — neither confirmed in this pass). Structurally, no special access is required beyond a valid Admin/HR login for *any* tenant plus a guessed or enumerated numeric ID. | High — real financial state (advance approval, remaining-balance payments, deletion) and employee PII (name, via the joined response) can be read or mutated across tenant boundaries with no tenant-isolation check on 5 of the module's 8 endpoints. | None found in code as of this Discovery pass. | **Open — unmitigated, present in the live production system.** | Unassigned — needs a human owner and a decision on whether to patch immediately (independent of migration timeline) or accept as a documented pre-migration risk. | `workin-hr/hr-legacy` commit `83c326e40f68dd0d560595a6c4e465eb681f2ce8`: `apis/api/advances/{create,approve,reject,pay,delete}.php` (missing checks), contrasted with `apis/api/advances/{update,one,list}.php` in the same commit (correct checks present). |

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

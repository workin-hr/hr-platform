# Bootstrap Decisions

## Confirmed Decisions

### BD-001 Repository Strategy

Use `workin-hr/hr-platform` as the dedicated Phase 0 repository for the new system.

### BD-002 Legacy Boundary

`hr-legacy` remains outside `hr-platform` and should be treated as discovery input, not as code to migrate inside this repository.

### BD-003 Flutter Boundary

Flutter remains outside `hr-platform` during Phase 0 until API compatibility and release-process discovery are completed.

### BD-004 Bootstrap Scope

Create only repository harness, documents, agents, skills, templates, and validation.

### BD-005 Governance Model

No direct writes to `main`, no agent self-approval, no repository administration by agents, and no production secret access by agents.

### BD-006 Architecture Starting Assumption

Prefer a modular monolith for the first release unless discovery proves otherwise.

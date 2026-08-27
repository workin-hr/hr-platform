# hr-platform Constitution

This is the canonical constitution for Spec Kit principles in this repository
(installed by `specify init`, `specify-cli` 0.8.15). Repository-wide
instruction precedence, authority, workflow, and change propagation remain
canonical only in root `AGENTS.md`; this file is subordinate to that contract
and must not redefine it. It carries forward, unchanged in
substance, the 15 principles originally adopted during Phase 0 bootstrap. It
supersedes the pre-installation copy formerly kept at the non-standard path
`.specify/constitution.md`, which has been removed to avoid two sources of
truth for the same content.

## Core Principles

### I. Repository As Source Of Truth

Repository files are sources of truth.

### II. Fact/Assumption/Question Separation

Specifications must separate facts, assumptions, and open questions.

### III. API Compatibility

Preserve API compatibility where required by validated client behavior.

### IV. Multi-Tenant Isolation

Multi-tenant isolation is a first-class design constraint.

### V. Risk-Based Testing

Test obligations must be explicit and risk-based.

### VI. Evidence-Driven, Reversible Migration

Database migration work must be evidence-driven and reversible where possible.

### VII. Security And Least Privilege

Security, least privilege, and sensitive-data protection are mandatory.

### VIII. Performance And Observability Budgets

Performance budgets and observability expectations must be documented before
implementation decisions depend on them.

### IX. Idempotent Ingestion

External ingestion should be idempotent where repeat delivery is possible.

### X. Immutable Attendance Events

Attendance events should be treated as immutable facts.

### XI. Role Separation

Planning, implementation, and review roles must remain distinct.

### XII. No Self-Approval Or Self-Merge

No agent may approve or merge its own work.

### XIII. Evidence-Gated Implementation

No product implementation begins before approved discovery and architecture
decisions justify it.

### XIV. Agent Data And Credential Boundaries

Sensitive production data and unrestricted credentials are never exposed to
agents.

### XV. Mandatory Human Approval

Human approval is mandatory before implementation and merge.

## Phase 0 Scope Constraint

Product implementation remains disabled during Phase 0. The Spec Kit workflow
this constitution governs is:

`Constitution -> Specify -> Clarify -> Plan -> Tasks -> Analyze -> Human approval -> Implementation`

The `/speckit-implement` command installed with this integration must not be
invoked to generate or execute product code during Phase 0. It becomes usable
only after Discovery evidence exists, an architecture decision is approved,
and a human has authorized implementation for that specific piece of work.

## Development Workflow

The mandatory contribution workflow is defined once, in `AGENTS.md` and
`docs/agents/operating-model.md`, to avoid duplicating it here and letting the
two drift. Summary: Issue -> Specification -> Clarification -> Architecture
and testing impact -> Human approval -> Isolated implementation branch ->
Automated verification -> Independent review -> Human merge.

## Governance

- This constitution supersedes ad hoc practice for any work governed by the
  Spec Kit workflow.
- Amendments require a written proposal (an ADR or a `docs/bootstrap/decision-log.md`
  entry), a stated rationale, and explicit human approval before the change
  takes effect.
- Every principle above is currently in **Proposed** status: it has been
  followed in practice throughout Phase 0 bootstrap, but has not yet been
  formally ratified by a human owner. Formal ratification is expected to
  happen as part of the Phase 0 human-approval gate described in
  `docs/bootstrap/manual-setup-checklist.md`. This file will be updated with a
  real ratification date at that time — do not treat the version line below as
  evidence that ratification has already occurred.
- Complexity or deviation from these principles must be justified in writing
  (PR description or ADR) and is subject to independent review.

**Version**: 0.1.0 (Draft) | **Ratified**: Not yet ratified — pending human approval | **Last Amended**: 2026-08-02

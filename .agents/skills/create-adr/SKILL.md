---
name: create-adr
description: Use when creating or updating an architecture decision record with explicit context, status, alternatives, and open questions.
---

# Create ADR

## Description And Trigger

Use when a decision materially affects architecture, governance, migration, testing, compatibility, or security boundaries.

## Inputs

- decision context
- evidence and constraints
- related open questions

## Preconditions

- there is a real decision or decision candidate worth tracking

## Ordered Workflow

1. Start from `docs/adr/ADR-0000-template.md` (the single authoritative
   template; do not copy the old `assets/adr-template.md`, which now only
   redirects here).
2. Fill in the `## Metadata` table completely (ADR ID, Title, Status, Date,
   Owners, Deciders, Related Issues, Supersedes, Superseded By). New ADRs
   start `Status: Proposed`.
3. Record context and evidence in `## Context`.
4. Write `## Decision` as a candidate direction. If `Status` is `Proposed`,
   the section must include the literal marker text `Approval status:
   Proposed` making clear it is not yet approved.
5. Fill in `## Alternatives Considered`, `## Consequences`, `## Risks`,
   `## Validation Evidence` (state "None yet — pending Discovery" if no
   evidence exists), and `## Open Questions`.
6. Run `.agents/skills/create-adr/scripts/validate-adr.sh <path-to-adr>` on
   the draft and fix any failures before treating the ADR as ready for
   review. This delegates to `scripts/validate_phase0.py --validate-adr`
   — there is one authoritative ADR-structure implementation, not two.
7. Run `python3 scripts/validate_phase0.py` to confirm the full Phase 0
   validation (which also dynamically discovers and validates every ADR,
   including this new one, with no hardcoded list to update) passes.

## Required Outputs

- ADR draft or update, in the format `docs/adr/ADR-NNNN-slug.md`
- ADR validation result (`validate-adr.sh` and the full
  `scripts/validate_phase0.py` run — both exercise the same underlying
  validation logic)

## Evidence

- links to source documents
- explicit alternatives considered
- for `Proposed` ADRs, an explicit statement of what evidence is still
  missing before the decision could become `Accepted`

## Validation Checklist

- file name matches `ADR-NNNN-slug.md`
- `## Metadata` table is complete and `Status` is one of `Proposed`,
  `Accepted`, `Rejected`, `Superseded`, `Deferred`
- all required sections are present and non-empty
- context and decision are separate
- consequences are concrete
- if `Status` is `Proposed`, `## Decision` visibly says so and is not written
  as if it were already approved

## Failure Conditions

- an ADR closes a decision without evidence

## Escalation Conditions

Escalate if the decision lacks evidence or closes an unresolved architecture question silently.

## Forbidden Behavior

- fabricating final decisions
- omitting tradeoffs

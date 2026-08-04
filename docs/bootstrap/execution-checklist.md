# Phase 0 Execution Checklist

This checklist turns the current Phase 0 gap summary into an execution order
with explicit owners.

**Where this checklist conflicts with a later, Accepted decision-log entry,
the decision-log entry wins.** This document was written before D-013
(branch-protection deferral), D-014 (first human-approved merge), and D-015
(Phase 0 complete, Discovery authorized) were recorded, and has not been
kept in lockstep with them line-by-line — see `docs/bootstrap/decision-log.md`
for the canonical, current record. The specific corrections below (H1's
completion criterion, the H2/Discovery sequencing, and A2's stub claim) were
found to actively contradict those decisions and are fixed here; treat any
other apparent staleness the same way — check the decision log first.

## Current Checkpoint

As of August 3, 2026:

- `python3 scripts/validate_phase0.py` passes.
- `bash scripts/verify-bootstrap.sh` passes with optional external tools
  skipped when not installed locally.
- The repository is ready for bootstrap follow-through, but human-owned
  GitHub setup, open decisions, and discovery evidence are still incomplete.

## Priority Order

1. Human GitHub organization and repository setup
2. Human resolution of named open questions and ownership
3. Public placeholder replacement before publication
4. Discovery evidence capture into the existing document set
5. Operations and release-readiness document completion
6. Optional bootstrap tool installation after explicit approval

## Human-Owned Actions

### H1. Apply GitHub Organization Setup

Owner: Human organization owner or maintainer

Do:

- Create and publish the organization `.github` repository.
- Apply the organization profile, verified domain, contact email, and website.
- Create the required teams and assign real human owners.
- Apply branch protection, rulesets, and repository security defaults —
  **except on `hr-platform` itself, where this is explicitly Deferred, not
  achievable, under D-013 (`docs/bootstrap/decision-log.md`): the
  organization is on GitHub Free and the repository is private, and the
  repository owner has decided neither an org-plan upgrade nor making the
  repository public is in scope.** Do not spend time attempting this on
  `hr-platform` until D-013 is revisited; other repositories in the
  organization are not necessarily under the same constraint.
- Create the GitHub Project, milestones, and label set.

Primary references:

- `../bootstrap/manual-setup-checklist.md`

Related external context, if this repository is being reviewed inside the full
multi-repository `workin-hr-workspace` bootstrap workspace:

- the sibling organization-setup checklist in the separate `.github`
  repository checkout

Done when:

- The GitHub settings described in both checklists exist in the live
  organization, other than `hr-platform` branch protection/rulesets, which
  are Deferred under D-013 and are not a completion requirement for this
  item.
- Required teams, labels, milestones, and project fields exist.

### H2. Resolve Open Bootstrap Decisions

Owner: Human engineering lead or repository owner

Decide:

- Which GitHub plan features are actually available.
- Which humans own `platform-owners`, `backend`, `frontend`, `mobile`,
  `gateway`, `qa`, `agents-readonly`, and `agents-write`.
- Whether `hr-flutter` is a new repository, a renamed repository, or a
  transferred repository.
- Whether `specify-cli` and GitHub MCP are approved for use in discovery.

Primary reference:

- `open-questions.md`

Done when:

- Each question has a recorded answer or explicit deferral in
  `decision-log.md`.
- Team ownership and repository-boundary decisions stop being ambiguous.

### H3. Replace Public Placeholders

Owner: Human maintainer with correct public-contact information

Do:

- Replace placeholder support, website, and security-contact values before
  public use.
- Update template-level security-policy details where a real project requires
  them.

Primary references:

- `../../README.md`

Related external context, if this repository is being reviewed inside the full
multi-repository `workin-hr-workspace` bootstrap workspace:

- the workspace-root `README.md`
- the sibling `repo-template` repository's `SECURITY.md`

Done when:

- No public-facing bootstrap document depends on fake ownership or contact
  details.

## Agent-Eligible Actions

### A1. Capture Discovery Evidence

Owner: Discovery analyst, architect, or documentation agent operating under
human direction

Fill with cited evidence:

- `../api/existing-endpoint-inventory.md`
- `../api/flutter-request-response-compatibility.md`
- `../legacy/production-behavior-evidence.md`
- `../migration/database-schema-inventory.md`
- `../devices/vendor-capability-matrix.md`
- `../product/mvp-scope-prioritization.md`

Done when:

- Each file contains concrete entries tied to evidence, not just headings.
- Claims about legacy behavior, Flutter contracts, migration risk, device
  support, and MVP priority are attributable to source material.

### A2. Complete Operations Release Criteria

**Status: Done.** `../operations/release-readiness.md` is no longer a stub
— it already defines the release gate, minimum gate expectations, and
cross-references the rest of the operations document set. This entry is
kept for historical traceability of what A2 required, not as an open task.

Owner: Operations planner, test architect, or documentation agent under human
review

Do (completed):

- Replace the stub structure in `../operations/release-readiness.md` with real
  release gates, required evidence, sign-off owners, go/no-go criteria, and
  open questions.
- Keep unknown values explicitly marked as unknown rather than guessed.

Done when:

- `../operations/release-readiness.md` can be used as a real review artifact
  — confirmed true as of this correction.

### A3. Install Optional Local Bootstrap Tools After Approval

Owner: Human operator for approval; agent may execute once approved

Install only if explicitly approved:

- `specify-cli`
- `markdownlint-cli2`
- `yamllint`
- `shellcheck`
- `actionlint`
- `gitleaks`
- `lychee`

Primary reference:

- `../tools/local-bootstrap-tools.md`

Done when:

- The approved tools are installed locally or in CI.
- The team no longer relies on skipped local checks for those tools.

## Suggested Execution Sequence

**Superseded for A1 by D-015 (`docs/bootstrap/decision-log.md`):** D-015
explicitly declared Phase 0 complete and authorized Discovery (A1) to begin
*while H2 remains unresolved* — H2's open questions and Discovery evidence
are both listed there as "explicitly outside the Phase 0 completion gate,"
not sequential blockers on each other. Step 2 below (H2 before Discovery)
reflected this checklist's original, no-longer-current assumption; A1 does
not wait on H2 and has in fact already substantially proceeded (see
`docs/api/existing-endpoint-inventory.md`, `docs/legacy/business-rule-extraction.md`,
`docs/security/threat-model.md`, and the rest of `docs/migration/`) without
H2 being resolved first. H2 still needs a human answer — it just isn't a
gate on A1.

1. Complete H1's non-deferred items (teams, labels, project, org profile) —
   it is still the external governance blocker for those, though branch
   protection specifically is Deferred per D-013 (see H1 above) and does
   not block anything.
2. Resolve H2 in parallel with Discovery, not before it — ownership and
   repository-boundary ambiguity should still be closed out, but per D-015
   it is not a precondition for A1 to continue.
3. Complete H3 before publishing or reusing the template outside the local
   workspace.
4. Continue A1 and A2 as ongoing work; A1 is already in progress and
   should keep incorporating new evidence as it's found.
5. Run A3 only after explicit human approval for network-installed tools.

## Notes

- Empty future component boundaries under `backend/`, `admin-web/`,
  `edge-gateway/`, `infrastructure/`, `contracts/`, and `specs/` are
  intentional in Phase 0 and are not missing application work.
- Do not treat undocumented assumptions as completed checklist items.

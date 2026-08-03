# Phase 0 Execution Checklist

This checklist turns the current Phase 0 gap summary into an execution order
with explicit owners.

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
- Apply branch protection, rulesets, and repository security defaults.
- Create the GitHub Project, milestones, and label set.

Primary references:

- `../bootstrap/manual-setup-checklist.md`

Related external context, if this repository is being reviewed inside the full
multi-repository `workin-hr-workspace` bootstrap workspace:

- the sibling organization-setup checklist in the separate `.github`
  repository checkout

Done when:

- The GitHub settings described in both checklists exist in the live
  organization.
- `main` is protected.
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

Owner: Operations planner, test architect, or documentation agent under human
review

Do:

- Replace the stub structure in `../operations/release-readiness.md` with real
  release gates, required evidence, sign-off owners, go/no-go criteria, and
  open questions.
- Keep unknown values explicitly marked as unknown rather than guessed.

Done when:

- `../operations/release-readiness.md` can be used as a real review artifact.

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

1. Complete H1 because it is the external governance blocker.
2. Resolve H2 before opening substantial discovery work, so ownership and
   repository boundaries are not ambiguous.
3. Complete H3 before publishing or reusing the template outside the local
   workspace.
4. Run A1 and A2 in parallel once humans have defined the decision owners and
   approved the discovery direction.
5. Run A3 only after explicit human approval for network-installed tools.

## Notes

- Empty future component boundaries under `backend/`, `admin-web/`,
  `edge-gateway/`, `infrastructure/`, `contracts/`, and `specs/` are
  intentional in Phase 0 and are not missing application work.
- Do not treat undocumented assumptions as completed checklist items.

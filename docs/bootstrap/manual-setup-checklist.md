# Manual GitHub Setup Checklist

This checklist documents settings that must be applied in GitHub because they cannot be safely represented as repository files alone.

## Organization

1. Confirm the organization name is `workin-hr`.
2. Confirm repository visibility and access policies for `hr-platform`, `hr-legacy`, and `hr-flutter`.
3. Limit repository administration to trusted human owners only.

## Teams

Create these teams:

- `platform-owners`
- `backend`
- `frontend`
- `mobile`
- `gateway`
- `qa`
- `agents-readonly`
- `agents-write`

## Repository Permissions

- Grant human maintainers the minimum permissions required by role.
- Keep planning and review agents read-only.
- Limit Codex bootstrap implementation agents to branch-scoped write access only.
- Prevent any agent from repository administration, production secret access, or direct `main` writes.

## GitHub Project

Create a project named `HR Platform Delivery`.

Configure issue types:

- Epic
- Feature
- User Story
- Task
- Bug
- Spike
- ADR
- Risk
- Technical Debt

Configure custom fields:

- Status
- Priority
- Area
- Iteration
- Size
- Risk
- Target Release
- Owner Role
- API Impact
- Database Impact
- Legacy Impact
- Flutter Impact
- Security Impact
- Performance Impact
- Migration Impact
- Device Vendor

Configure status values:

- Inbox
- Discovery
- Ready for Specification
- Specification in Progress
- Architecture Review
- Ready for Execution
- In Progress
- Review
- Validation
- Blocked
- Done

Configure views:

- Executive Roadmap
- Phase 0 Bootstrap
- Discovery Backlog
- Architecture Decisions
- Legacy Analysis
- Database Migration
- Flutter Compatibility
- Attendance Integration
- Testing and Quality
- Risks and Blockers
- Current Iteration

## Milestones

Create at minimum:

- `Phase 0 Engineering Bootstrap`
- `Discovery Readiness`
- `MVP Delivery`

## Labels

Apply the label set defined in `.github/labels.yml`.

## Branch Protection And Rulesets

**Status: Deferred, not merely pending — see `docs/bootstrap/decision-log.md`
D-013.** `workin-hr` is a GitHub Free organization and `hr-platform` is
private; both the classic branch-protection API and the Rulesets API
return `403: Upgrade to GitHub Pro or make this repository public` on this
repo. The repository owner has explicitly decided neither an organization
plan upgrade nor making the repository public is in scope. The rules below
remain the target configuration if this is ever revisited; none of them
are currently applied or applicable.

Protect `main` with rules that:

- require pull requests
- require one human approval
- require resolved conversations
- block direct pushes
- block force pushes
- block branch deletion
- require successful status checks

Required status checks should include the bootstrap validation workflow once merged to `main`.

## Pull Request Policy

- Agents cannot approve their own work.
- Agents cannot merge their own pull requests.
- Human review is mandatory before merge.

## Human Approval And Merge Sequence

**Status: Pending human acceptance gate.** This is a human-controlled
GitHub process. `main` now exists and `bootstrap/engineering-foundation`
has been pushed with an open pull request into it, but no step below has
been evidenced as complete by a human — no step in this section may be
marked complete by an agent; only a human owner can attest to it, with the
evidence listed in step 9.

1. Branch protection on `main` is Deferred, not applied — see "Branch
   Protection And Rulesets" above and D-013 in
   `docs/bootstrap/decision-log.md`. This step cannot be completed as
   originally written under the accepted GitHub Free plan limitation; the
   temporary mitigation in R-008 (`docs/bootstrap/risk-register.md`)
   substitutes for it.
2. A human (or an agent, on the existing `bootstrap/engineering-foundation`
   branch, with no force push) pushes the branch to `origin`.
3. A human opens a real GitHub pull request from
   `bootstrap/engineering-foundation` into `main`.
4. Required status checks run (`.github/workflows/phase0-validate.yml` and
   any checks added by this remediation — see
   `docs/bootstrap/audit-remediation.md`).
5. An independent, read-only audit is obtained (Claude `bootstrap-auditor`
   and/or Codex `independent-verification-reviewer`; a prior such audit
   returned `REQUEST CHANGES` and is what this remediation responds to).
6. At least one human reviewer approves the pull request.
7. Any remaining required findings from step 5 are resolved (re-run the
   independent audit if findings required changes).
8. A human merges the pull request. No agent merges it, and no agent
   approves its own or another agent's work.
9. The human records, in `docs/bootstrap/decision-log.md`: the pull-request
   URL, the approving human's identity, the merge commit SHA, and a link to
   the validation evidence used.
10. A human runs `python3 scripts/validate_phase0.py` and
    `bash scripts/verify-bootstrap.sh` against `main` post-merge to confirm
    Phase 0 validation still passes after merge.

Until all ten steps are complete, this section must remain classified as
**Pending human acceptance gate** — never rewritten to imply completion
based on an agent's own statement that the work is "ready."

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

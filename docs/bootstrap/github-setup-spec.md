# GitHub Setup Specification

## Organization Repositories

- `hr-platform`: private, default branch `main`, bootstrap and future target system repository
- `hr-legacy`: private, legacy PHP repository, treated as read-only for agents
- `hr-flutter`: separate repository, not moved into `hr-platform` during Phase 0

## Organization Project

Create an organization project named `HR Platform Delivery`.

### Issue Types

- Epic
- Feature
- User Story
- Task
- Bug
- Spike
- ADR
- Risk
- Technical Debt

### Project Fields

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
- Device Vendor

### Required Status Values

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

### Required Views

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

## Repository Ruleset For `main`

Apply a ruleset that:

- requires pull requests
- requires one human approval
- requires resolved conversations
- blocks force pushes
- blocks branch deletion
- requires successful status checks
- disallows direct pushes

Agent approvals do not count as human approvals.

## Initial Agent Permissions

- Claude planning and review agents: read-only
- Codex bootstrap agent: write branch only
- no agent: direct `main` write
- no agent: repository administration
- no agent: production secrets access

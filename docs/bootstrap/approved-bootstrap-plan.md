# Approved Phase 0 Bootstrap Plan

## Purpose

This document records the approved Phase 0 bootstrap baseline for `hr-platform` based on human direction on August 2, 2026.

## Confirmed Facts

- `workin-hr` is the target GitHub organization.
- `hr-platform` is the new private main repository for the modernization effort.
- `hr-legacy` remains separate and should be treated as read-only for discovery, except critical bug work by humans.
- Flutter remains separate for now and is not moved into the monorepo during Phase 0.
- The current target stack direction is Java 25, Spring Boot 4.x, Next.js 16, Flutter retained, PostgreSQL, and a local .NET edge gateway.
- Existing production data and customers exist, so migration and compatibility work must be evidence-driven.

## Approved Decisions

- Phase 0 is bootstrap only. No product implementation is allowed.
- Repository strategy is a single `hr-platform` repository with documentation, governance, agent definitions, skills, validation, and empty future component boundaries only.
- Planning starts from a modular monolith assumption unless discovery disproves it.
- GitHub organization and project governance are part of Phase 0 even when some settings must be applied manually in GitHub UI.
- Agent responsibilities must remain separated across planning, implementation, and review.
- Spec-driven workflow is required. Repository files are sources of truth.

## Phase 0 Deliverables

- repository structure for documents, specs, contracts, evidence, and future component boundaries
- `AGENTS.md` and `CLAUDE.md`
- initial Claude and Codex agent definitions
- initial reusable skills
- GitHub issue and PR templates
- ADR, architecture, testing, security, and discovery templates
- deterministic repository validation
- GitHub setup specification for organization project, rulesets, teams, and protected branch behavior

## Explicitly Forbidden In Phase 0

- Spring Boot application code
- Next.js application code
- Flutter application code
- .NET gateway code
- database migrations
- infrastructure deployment stacks
- business-domain placeholder implementations

## Required Agent Set

- Program Bootstrap Architect
- Product Discovery Analyst
- Legacy PHP Analyst
- Solution Architect
- Test Architect
- Bootstrap Engineer
- Bootstrap Auditor
- Independent Verification Reviewer

## Required Skill Set

- bootstrap-repository
- create-project-charter
- create-specification
- create-adr
- create-agent-definition
- create-agent-skill
- create-github-backlog
- analyze-legacy-system
- create-test-strategy
- review-bootstrap
- validate-bootstrap

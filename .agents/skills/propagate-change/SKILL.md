---
name: propagate-change
description: Use when completing a repository change so all affected documentation, contracts, inventories, collections, agent definitions, skills, and validators remain synchronized with the implementation.
---

# Propagate Change

## Canonical Instructions

Read and follow the repository-root `AGENTS.md`. Its Mandatory Change
Propagation matrix defines the authoritative artifact categories and completion
contract; this skill applies that contract without redefining it.

## Description And Trigger

Use before handoff for any implementation, configuration, schema, contract,
automation, agent, or skill change.

## Inputs

- current diff and owning issue/specification
- affected runtime path and contracts
- repository inventories, collections, docs, agent definitions, and skills
- validation and PR evidence requirements

## Preconditions

- the primary change and its real callers/consumers are understood
- unrelated worktree changes have been identified and preserved

## Ordered Workflow

1. Classify every changed file using `AGENTS.md`'s propagation matrix.
2. Trace callers, consumers, contracts, persistence, configuration, tests, and deployment impact.
3. Update every affected authoritative artifact in the same branch.
4. For each category not updated, record why it is not applicable.
5. Run drift checks, targeted regression tests, repository validation, and final diff inspection.
6. List the synchronized artifacts and exact evidence in the handoff or PR description.

## Required Outputs

- updated durable documentation for behavior-changing work
- synchronized affected contracts, inventories/collections, agent definitions, and skills
- regression/drift validation evidence
- explicit not-applicable decisions for reviewed categories not changed

## Evidence

- exact changed-file list
- exact validation commands and outcomes
- mapping from implementation changes to synchronized artifacts

## Validation Checklist

- `AGENTS.md` remains the only repository policy source
- `CLAUDE.md` still imports `@AGENTS.md`
- affected inventories/collections agree with executable behavior
- affected agent and skill catalogs agree with files on disk
- behavior-changing work updates at least one durable explanatory document
- delivery evidence names both updated and reviewed-not-applicable categories

## Failure Conditions

- code or configuration behavior changed without durable documentation
- a contract, inventory, collection, agent, or skill changed without its catalog/validator
- duplicated policy was added to a tool-specific file
- validation or not-applicable evidence is missing

## Escalation Conditions

Escalate when artifact ownership is ambiguous, synchronized updates would
materially broaden scope, or authoritative sources conflict.

## Forbidden Behavior

- treating comments as the only documentation update
- copying repository policy into multiple tool-specific files
- marking categories not applicable without inspecting their actual consumers
- weakening drift checks to make synchronization pass

---
name: create-test-strategy
description: Use when producing or updating a layered test strategy covering correctness, compatibility, migration, security, performance, and resilience.
---

# Create Test Strategy

## Canonical Instructions

Read and follow repository-root `AGENTS.md`; this skill narrows that contract and never overrides it.

## Description And Trigger

Use when defining or refining repository-level testing strategy documents.

## Inputs

- bootstrap documents
- architecture assumptions
- known system risks

## Preconditions

- relevant system risks or quality attributes are known

## Ordered Workflow

1. Map test layers to system risks.
2. Distinguish what is required now from what is deferred.
3. Define evidence expected for each layer.

## Required Outputs

- strategy document or update
- test-layer-to-risk mapping

## Evidence

- risk mapping
- layer definitions
- deferred decision list

## Validation Checklist

- unit, integration, contract, migration, E2E, security, performance, and resilience layers are addressed
- assumptions are explicit

## Failure Conditions

- the strategy ignores a major risk class

## Escalation Conditions

Escalate when strategy depends on unresolved architecture or delivery decisions.

## Forbidden Behavior

- claiming every expensive test runs on every commit
- hiding deferred quality decisions

---
name: create-test-strategy
description: Use when producing or updating a layered test strategy covering correctness, compatibility, migration, security, performance, and resilience.
---

# Create Test Strategy

## Trigger

Use when defining or refining repository-level testing strategy documents.

## Inputs

- bootstrap documents
- architecture assumptions
- known system risks

## Workflow

1. Map test layers to system risks.
2. Distinguish what is required now from what is deferred.
3. Define evidence expected for each layer.

## Required Evidence

- risk mapping
- layer definitions
- deferred decision list

## Validation Checklist

- unit, integration, contract, migration, E2E, security, performance, and resilience layers are addressed
- assumptions are explicit

## Failure And Escalation

Escalate when strategy depends on unresolved architecture or delivery decisions.

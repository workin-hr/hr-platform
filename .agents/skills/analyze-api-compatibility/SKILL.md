---
name: analyze-api-compatibility
description: Use when inventorying or evaluating PHP-to-target API behavior and Flutter request-response compatibility without inventing undocumented client assumptions.
---

# Analyze API Compatibility

## Description And Trigger

Use when endpoint behavior, request and response contracts, or Flutter compatibility risks need structured analysis.

## Inputs

- endpoint inventory
- Flutter compatibility notes
- legacy evidence

## Preconditions

- at least one concrete API surface or client behavior is under review

## Ordered Workflow

1. Inventory the current endpoint or contract.
2. Capture known request, response, and error behavior.
3. Identify Flutter compatibility assumptions and unknowns.
4. Record compatibility risks and open questions.

## Required Outputs

- compatibility analysis entry
- risk summary
- evidence references

## Evidence

- endpoint references
- client behavior evidence
- exact unknowns

## Validation Checklist

- request and response behavior are both covered
- compatibility risk is explicit
- undocumented client behavior is not treated as fact

## Failure Conditions

- compatibility claims are made without evidence

## Escalation Conditions

Escalate when production client behavior cannot be inferred safely.

## Forbidden Behavior

- inventing Flutter assumptions
- approving breaking changes silently

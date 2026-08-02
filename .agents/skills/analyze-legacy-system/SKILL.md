---
name: analyze-legacy-system
description: Use when planning or performing evidence-based analysis of the PHP legacy system without modifying its code or inventing undocumented behavior.
---

# Analyze Legacy System

## Trigger

Use when inventorying repositories, runtime behavior, integrations, data coupling, or undocumented assumptions in the legacy PHP system.

## Inputs

- legacy repository paths
- deployment notes
- discovery templates

## Workflow

1. Inventory repositories and entry points.
2. Record observed behavior with exact evidence.
3. Separate confirmed behavior from hypotheses and open questions.
4. Capture migration and compatibility risks.

## Required Evidence

- file references
- command output summaries
- explicit uncertainty notes

## Validation Checklist

- no code changes were made
- findings cite evidence
- undocumented behavior is not treated as fact

## Failure And Escalation

Escalate when production behavior cannot be determined safely from repository evidence.

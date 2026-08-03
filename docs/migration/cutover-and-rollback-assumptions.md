# Cutover And Rollback Assumptions

This template tracks assumptions about the migration cutover window and
rollback path. Do not fill in real RTO/RPO figures, dates, or production
topology here until Discovery produces that evidence — record only what is
actually known, and mark everything else as an open question.

## Assumption

Record the assumption in testable language. If it cannot be challenged or
validated, it is probably too vague.

## Category (cutover window, rollback trigger, data-freeze scope, communication)

Classify the assumption so reviewers can see whether it affects timing, data
correctness, operational coordination, or customer communication.

## Confidence (Assumed / Evidenced)

Use `Assumed` unless there is actual evidence supporting the statement.

## Risk If Wrong

Describe the migration or customer consequence if the assumption fails.

## Evidence

Link the discovery artifact, measurement, rehearsal, or decision record that
supports the assumption. Leave blank if the assumption is still unsupported.

## Open Questions

Record what must still be learned before the assumption can become part of a
real cutover plan.

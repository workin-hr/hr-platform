# Production Smoke And Post-Deployment Validation

Use this document to define the checks that confirm a release is safe
immediately after deployment and the follow-up validations that confirm the
system is behaving correctly after it has been live long enough to observe
real traffic or scheduled processing.

Production smoke tests are fast release-safety checks. Post-deployment
validation is broader and may include delayed or manual confirmation. Neither
should rely on assumed behavior; each check needs a pass condition, an owner,
and evidence.

## Check

Describe the exact behavior being verified. Prefer checks tied to observable
system behavior rather than internal hope statements.

Examples:

- application health endpoint returns expected status
- authentication path works for an approved test account
- a critical HR workflow can be completed end to end
- a migration validation query shows expected row counts or invariants
- alerts remain quiet for expected conditions after release
- a scheduled integration or background process completes successfully

## Type (production smoke test / post-deployment validation)

Classify each item as one of:

- `production smoke test`: a fast check run immediately after deployment or
  cutover to detect obvious release failure
- `post-deployment validation`: a follow-up check that may require time,
  real traffic, scheduled execution, or manual confirmation

If a check serves both purposes, record when it runs in each mode rather than
blurring the distinction.

## Trigger (on every deploy, scheduled, manual)

State exactly when the check runs and what causes it to run.

Typical triggers:

- on every production deploy
- after maintenance-window exit
- after a migration step completes
- after feature-flag enablement
- scheduled after a defined observation period
- manual confirmation by an operator or business owner
- on rollback completion

## Pass Criteria

The pass condition must be observable and reviewable.

Good pass criteria examples:

- HTTP endpoint responds with the expected status and payload shape
- no blocking errors appear in release-critical monitoring within the agreed
  observation window
- a validation query returns expected invariants with zero critical mismatch
- a critical workflow completes without customer-visible failure
- no compatibility regression is observed in the monitored client path

Avoid vague criteria such as `looks normal` or `seems healthy`.

## Owner

Record the human role responsible for running or reviewing the check, such as:

- release owner
- operations owner
- QA or test owner
- engineering owner
- migration owner
- customer-support or product owner for business-facing confirmation

Agents may help prepare the checklist, but humans own production validation.

## Evidence

Link the proof that the check ran and passed or failed. Evidence may include:

- deployment log or release record
- smoke-test output
- dashboard screenshot or metric link
- alert history
- migration validation result
- manual test note with timestamp and owner
- customer-support confirmation for externally visible behavior

If evidence is not retained automatically, define how it will be recorded.

## Open Questions

- Which smoke tests can be automated safely for every production deployment?
- Which validations require manual business confirmation after release?
- What observation window is required before a high-risk release is considered
  stable?
- Which checks must also run after rollback, not only after forward release?
- Which client, migration, or integration paths are critical enough to always
  appear in this checklist?

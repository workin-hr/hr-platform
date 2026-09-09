---
name: degraded-independent-review
description: Use when D-121's named reviewer is unavailable and D-224's degraded-review procedure has been authorised, to obtain and record a substitute independent review round.
---

# Degraded Independent Review

## Canonical Instructions

Read and follow repository-root `AGENTS.md`; this skill narrows that contract and never overrides it.

This skill does not grant permission. It executes D-224 once the repository owner has already authorised it, and it produces the record D-224 requires. If any precondition below is unmet, the skill stops — a merge waits rather than proceeding on a weaker gate that was never actually established.

## Description And Trigger

Use when **all** of these hold:

- `chatgpt-codex-connector[bot]` cannot produce a round on the pull request's exact head — it has posted a usage-limit comment, or it has been silent under D-224's stated conditions;
- the repository owner has **explicitly** declined or deferred the documented remedy (restoring or funding the reviewer), in their own words, in this session or on the pull request;
- the owner has authorised D-224 for this pull request.

Do **not** use it because a review feels slow, because a branch is ready, or because an agent judges the change small. D-222 governs merging past the gate for active harm; this skill is the different thing — merging under a **weaker** gate, with that weakness written down.

## Inputs

- The pull request number.
- Its **exact head SHA**, frozen: nothing may be pushed between the review and the merge.
- The owner's authorisation and their decline of the remedy, quotable.
- The diff under review, as GitHub reports it against the pull request's current base.

## Preconditions

- The head is frozen and matches what will merge.
- `validate` (and `test` where the path filter runs it) is green on that exact head.
- Zero unresolved review threads from earlier rounds, or each carries a recorded disposition.
- The substitute reviewer has **no authorship, implementation, generation, or repository-write involvement** in this change, on any branch. An agent that wrote the diff cannot review it by later running read-only; that is the same actor twice.
- D-224 is canonical on `main` and any owner-stated sequencing condition is either satisfied or explicitly waived by the owner, with the waiver recorded.

## Ordered Workflow

1. Establish and record the reviewer's unavailability using D-224's Criterion 1 command; paste its output verbatim.
2. Record the owner's decline of the documented remedy, quoted.
3. Freeze the head. Re-read it from GitHub rather than trusting a local value.
4. Dispatch a **read-only** reviewer with no write tools and no prior involvement in the change. Give it the diff and the repository's standards; do not tell it what you believe is correct.
5. Post every finding it returns **verbatim** to the pull request, including findings you dispute — state the dispute as a reply, never by omission.
6. Reproduce each finding independently before acting on it. Fix what reproduces; answer what does not, with the evidence that disproves it.
7. Add a discriminating regression test for each behavioural fix, and prove it discriminates by reverting the fix and observing the test fail.
8. Re-run the focused suites, and the full suite where the blast radius warrants it.
9. Post the evidence block from **Required Outputs** as a single comment.
10. Hand the pull request to the owner for merge. Never merge it yourself.

## Required Outputs

One comment on the pull request containing:

- the exact head SHA;
- which unavailability path was established, with the command output;
- the owner's decline of the remedy, quoted, and who gave it;
- the substitute reviewer's identity and confirmation it holds no write access to the branch and did not author the change;
- every finding and its disposition — fixed with a commit SHA, or answered with evidence;
- **what remains unverified relative to a real round**, stated plainly;
- a link to D-224.

## Evidence

- The Criterion 1 command output, unedited.
- Commit SHAs for each fix, and the revert-proof that each new test discriminates.
- Focused suite results on the final head.
- The pull request's gate states at the moment of handoff: `validate`, `test` where applicable, `independent-review`, and the unresolved-thread count read from the API rather than assumed.

## Validation Checklist

- [ ] The head that was reviewed is the head that will merge.
- [ ] The substitute reviewer wrote none of the change under review.
- [ ] Every finding is on the pull request verbatim, disputes included.
- [ ] Every behavioural fix has a test proven to fail without it.
- [ ] The evidence comment names what is unverified, not only what passed.
- [ ] The unresolved count was re-read after resolving, not inferred from the mutation result.
- [ ] The merge is left to the owner.

## Failure Conditions

Stop and report rather than continuing when:

- the head moves after the review — the round is now about a different commit;
- the only available reviewer had a hand in writing the change;
- a finding reproduces and cannot be fixed within the pull request's scope;
- `validate` or `test` is not green on the exact head;
- D-224's own eligibility conditions cannot be established from evidence.

## Escalation Conditions

Escalate to the repository owner, and do not decide alone, when:

- a finding implies a schema, contract, or governance change beyond the pull request;
- the substitute review disagrees with an earlier round from the named reviewer;
- D-224's time box has lapsed, or the named reviewer has recovered — in which case the normal gate resumes immediately and this skill stops applying;
- following this procedure would require merging your own work.

## Forbidden Behavior

- Reviewing a change you authored, or dispatching a reviewer that authored it.
- Summarising findings instead of posting them verbatim.
- Resolving a thread without a recorded disposition.
- Merging the pull request.
- Using this skill without the owner's explicit authorisation, or inferring that authorisation from impatience, from a prior approval of different work, or from a slow queue.
- Recording the substitute round as though it were a round from the named reviewer. It is weaker, and the record must say so.

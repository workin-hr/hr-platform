---
name: independent-review
description: Use to obtain and record the independent review round AGENTS.md's Mandatory Workflow requires, performed by a read-only reviewer that did not write the change.
---

# Independent Review

## Canonical Instructions

Read and follow repository-root `AGENTS.md`; this skill narrows that contract and never overrides it.

This skill performs the gate D-226 defines. It does not weaken it and does not grant permission to skip it: if any precondition below is unmet, the skill stops, and the merge waits.

## Description And Trigger

Use for **every** pull request that needs its independent review round, unless a round from `chatgpt-codex-connector[bot]` already covers the exact head — those still count (D-226).

Do **not** use it to review your own work. The reviewer this skill dispatches must have had no hand in the change; if no such reviewer can be obtained, the gate is unavailable and the merge waits. D-222 governs merging past the gate for active harm and is unaffected by this skill.

## Inputs

- The pull request number.
- Its **exact head SHA**, frozen: nothing may be pushed between the review and the merge.
- The diff under review, as GitHub reports it against the pull request's current base.

## Preconditions

- The head is frozen and matches what will merge.
- `validate` (and `test` where the path filter runs it) is green on that exact head.
- Zero unresolved review threads from earlier rounds, or each carries a recorded disposition.
- The independent reviewer has **no authorship, implementation, generation, or repository-write involvement** in this change, on any branch. An agent that wrote the diff cannot review it by later running read-only; that is the same actor twice.
- The reviewer is read-only: no write tools, no ability to push, comment, or merge.

## Ordered Workflow

1. Freeze the head. Re-read it from GitHub rather than trusting a local value.
2. Dispatch a **read-only** reviewer with no prior involvement in the change. Give it the **pull request number and the frozen head SHA**, not a diff you selected -- it fetches the diff itself and echoes the SHA and file count it actually read, so a partial or wrong-commit diff cannot pass unnoticed. Do not tell it what you believe is correct.
3. Post every finding it returns **verbatim** to the pull request, including findings you dispute — state the dispute as a reply, never by omission.
4. Reproduce each finding independently before acting on it. Fix what reproduces; answer what does not, with the evidence that disproves it.
5. Add a discriminating regression test for each behavioural fix, and prove it discriminates by reverting the fix and observing the test fail.
6. Re-run the focused suites, and the full suite where the blast radius warrants it.
7. Post the evidence block from **Required Outputs** as a single comment.
8. Hand the pull request to the owner for merge. Never merge it yourself.

## Required Outputs

One comment on the pull request containing:

- the exact head SHA;
- the independent reviewer's identity and confirmation it holds no write access to the branch and did not author the change;
- the reviewer's **complete unedited output**, as a fenced block, separate from your dispositions -- "posted verbatim" is otherwise unverifiable and an omission is undetectable;
- every finding and its disposition — fixed with a commit SHA, or answered with evidence;
- **what remains unverified relative to a real round**, stated plainly;
- a link to D-226.

The comment must open with this block, exactly. It is not decoration: the gate
and the disposition check match these literals, and a comment without them
records no round at all -- `independent-review` stays red on a head that was
reviewed, and the operator is left reverse-engineering the marker from the
workflow.

```text
independent-review-round: agent
head: <the full 40-character head SHA>
```

Both lines start at column 1. An indented or fenced copy does not count, so
that a comment *quoting* this protocol -- a review of the gate itself -- cannot
claim a round on the pull request it is quoting.

The round must be recorded by someone with write access to the repository
(`OWNER`, `MEMBER` or `COLLABORATOR`), because the reviewer is read-only and
cannot post. Do not edit the comment afterwards: an edited comment is not
counted, since the body it would be counted on is not the body that was posted.
If the head moves, post a new round.

Each finding goes in its own review thread whose first comment carries:

```text
finding-of: independent-review-agent
```

A round that found nothing declares it, on its own line, in the round comment:

```text
findings: none
```

Silence is not a disposition: a claimed round carrying neither a marked finding
nor this declaration fails `scripts/check-review-dispositions.sh` rather than
passing quietly.

## Evidence

- Commit SHAs for each fix, and the revert-proof that each new test discriminates.
- Focused suite results on the final head.
- The pull request's gate states at the moment of handoff: `validate`, `test` where applicable, `independent-review`, and the unresolved-thread count read from the API rather than assumed.

## Validation Checklist

- [ ] The head that was reviewed is the head that will merge.
- [ ] The independent reviewer wrote none of the change under review.
- [ ] Every finding is on the pull request verbatim, disputes included.
- [ ] Every behavioural fix has a test proven to fail without it.
- [ ] The evidence comment names what is unverified, not only what passed.
- [ ] The unresolved count was re-read after resolving, not inferred from the mutation result.
- [ ] The round comment opens with `independent-review-round: agent` and `head: <40-hex>`, both at column 1.
- [ ] Every finding thread's first comment carries `finding-of: independent-review-agent`.
- [ ] A clean round declares `findings: none` on its own line in the round comment.
- [ ] The round comment has not been edited since it was posted.
- [ ] The merge is left to the owner.

## Failure Conditions

Stop and report rather than continuing when:

- the head moves after the review — the round is now about a different commit;
- the only available reviewer had a hand in writing the change;
- a finding reproduces and cannot be fixed within the pull request's scope;
- `validate` or `test` is not green on the exact head;
- the reviewer's independence cannot be established from evidence.

## Escalation Conditions

Escalate to the repository owner, and do not decide alone, when:

- a finding implies a schema, contract, or governance change beyond the pull request;
- the substitute review disagrees with an earlier round from the named reviewer;
- following this procedure would require merging your own work.

## Forbidden Behavior

- Reviewing a change you authored, or dispatching a reviewer that authored it.
- Summarising findings instead of posting them verbatim.
- Resolving a thread without a recorded disposition.
- Merging the pull request.
- Presenting this round as equivalent to one from a fully independent external service. D-226 records why it is weaker; the evidence comment must not imply otherwise.

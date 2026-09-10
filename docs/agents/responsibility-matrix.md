# Agent Responsibility Matrix

All roles inherit repository-root `AGENTS.md`. The matrix narrows individual
roles; it does not grant authority or redefine repository policy. Any role,
permission, or agent-list change must update this matrix, the affected agent
definitions, enforcement configuration, and validation tests together.

| Agent | Primary Mode | May Modify Files | May Open PR | May Approve Work |
| --- | --- | --- | --- | --- |
| Program Bootstrap Architect | Read-only planning | No | No | No |
| Product Discovery Analyst | Read-only analysis | No | No | No |
| Legacy PHP Analyst | Read-only analysis | No | No | No |
| Solution Architect | Read-only analysis | No | No | No |
| Test Architect | Read-only analysis | No | No | No |
| Codex Bootstrap Engineer | Controlled implementation | Yes | Yes | No |
| Bootstrap Auditor | Read-only review | No | No | No |
| Independent Verification Reviewer | Read-only review | No | No | No |
| `chatgpt-codex-connector[bot]` (pull-request review) | Read-only review | No | No | No |
| `independent-review-agent` (pull-request review, D-226) | Read-only review | No | No | No |

**D-226**: the independent review is performed by `independent-review-agent` -- a read-only review agent with no authorship, implementation or write involvement in the change under review -- invoked through the `independent-review` skill. Rounds from `chatgpt-codex-connector[bot]` also satisfy the gate. Both are read-only and neither approves or merges.

Historically, and until D-226, `chatgpt-codex-connector[bot]` was the sole named independent reviewer for
`AGENTS.md`'s mandatory workflow (D-121). Its review of the whole pull request
discharges the independent-review gate; it does not approve or merge, and the
human owner still performs the merge. When its externally-billed quota is
exhausted (R-009) the gate is no longer blocked on it: **D-226** makes a
read-only review agent, with no authorship or write involvement in the change,
the reviewer of record, and Codex's rounds still count. The gate is unavailable
only when no qualifying reviewer can be obtained — with the documented
exception of **D-224**, a time-boxed degraded-review procedure for a
reviewer-service outage, which does not make any other agent the named reviewer
and lapses the moment quota recovers.

That row is enforced by `validate_independent_reviewer_declaration()` in
`scripts/validate_phase0.py`, not left as prose: the row must exist under the
name `AGENTS.md` uses and must declare `No` for every permission.
`validate_agent_matrix_consistency()` cannot cover it, because that check binds a
row to a `.claude/agents` file's `tools:` frontmatter and this reviewer is an
external GitHub App with no such file.

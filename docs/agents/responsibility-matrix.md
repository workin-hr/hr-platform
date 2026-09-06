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
| Claude Code Action (`@claude` on issues and pull requests) | On-demand implementation | Yes | Yes | No |

`chatgpt-codex-connector[bot]` is the named independent reviewer for
`AGENTS.md`'s mandatory workflow (D-121). Its review of the whole pull request
discharges the independent-review gate; it does not approve or merge, and the
human owner still performs the merge. When its externally-billed quota is
exhausted (R-009) the gate is unavailable, not waived.

The Claude Code Action row is the GitHub Actions counterpart of a terminal
Claude session, not a second reviewer. `.github/workflows/claude-assistant.yml`
runs it only when a human writes the trigger phrase or assigns an issue, and it
holds `contents`, `pull-requests` and `issues` write scopes. It has no
`statuses: write`, so it cannot publish `independent-review` even by accident,
and `May Approve Work = No` is the same rule every implementer is under:
AGENTS.md forbids approving or merging your own work, and D-121 reserves the
review round for `chatgpt-codex-connector[bot]`.

Like the reviewer row above, it is not bound by
`validate_agent_matrix_consistency()`. That check binds a row to a
`.claude/agents` file's `tools:` frontmatter, and this role has no such file:
its permissions are the workflow's `permissions:` block and the deny-list in
`.claude/settings.json`, which the workflow passes to the action so the CI run
and terminal sessions cannot drift apart.

That row is enforced by `validate_independent_reviewer_declaration()` in
`scripts/validate_phase0.py`, not left as prose: the row must exist under the
name `AGENTS.md` uses and must declare `No` for every permission.
`validate_agent_matrix_consistency()` cannot cover it, because that check binds a
row to a `.claude/agents` file's `tools:` frontmatter and this reviewer is an
external GitHub App with no such file.

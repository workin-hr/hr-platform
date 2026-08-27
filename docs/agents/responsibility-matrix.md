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

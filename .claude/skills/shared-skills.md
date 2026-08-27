# Shared Skills For Claude

Claude must first follow the canonical repository contract imported by
`CLAUDE.md` from `AGENTS.md`, then use repository-backed shared procedures
under `.agents/skills/` when their trigger applies.

The complete, CI-checked inventory is `docs/agents/skill-catalog.md`; do not
hand-maintain a second list here. `propagate-change` is mandatory before
handoff for any implementation, configuration, schema, contract, automation,
agent, or skill change.

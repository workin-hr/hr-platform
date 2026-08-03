# Open Questions

## GitHub Governance

- Which organization-level GitHub Project, issue type, and ruleset features are available on the current plan?
- Which human maintainers will own `platform-owners`, `backend`, `frontend`, `mobile`, `gateway`, `qa`, `agents-readonly`, and `agents-write`?
- Should `hr-flutter` be created as a new organization repository or should an existing repository be renamed or transferred?

## Legacy Discovery

- Which repositories and branches accurately represent current production behavior?
- Are there deployment-specific PHP behaviors not represented clearly in version control?
- Which stored procedures, triggers, or cron-driven jobs are business-critical?

## Flutter Compatibility

- What request and response contracts are relied on by current mobile and desktop releases?
- Is there any existing client generation process, or are contracts hand-maintained?

## Tooling

- Will `specify-cli` be installed during Phase 0 or deferred until human review approves it?
- Will GitHub MCP be enabled read-only during discovery or deferred entirely?

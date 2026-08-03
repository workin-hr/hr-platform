# Operations Readiness

Use this area for rollout planning, cutover strategy, rollback plans, backup
and restore, incident response, monitoring and alerting ownership, runbook
standards, smoke and post-deployment validation, edge-gateway operational
support, customer communication, and recovery-objective discovery.

These are planning templates, not a description of an existing production
environment — nothing here exists yet. Do not record real production
topology, infrastructure names, or RTO/RPO values until Discovery and an
approved ADR establish them; where a number is genuinely unknown, write
"Not yet discovered" rather than a placeholder-looking number.

## Templates

- `environment-and-deployment-strategy.md`
- `release-readiness.md`
- `release-cutover-and-rollback.md` — the overall system rollout cutover/rollback plan (distinct from `docs/migration/cutover-and-rollback-assumptions.md`, which covers the database migration cutover specifically)
- `backup-and-restore.md`
- `incident-response.md`
- `monitoring-and-alerting.md`
- `logging-conventions.md` — structured-logging field contract, proposed ahead of implementation
- `runbook-standards.md`
- `production-smoke-and-post-deployment-validation.md`
- `gateway-operational-support.md`
- `customer-communication.md`
- `recovery-objectives.md`

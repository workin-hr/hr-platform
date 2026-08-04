# Spike — Disposable, Not Production Code

**This directory is explicitly outside the governed component boundaries**
(`backend/`, `admin-web/`, `edge-gateway/`, etc. — reserved for real
implementation per `docs/bootstrap/execution-checklist.md`'s Notes
section). It exists only to execute
`docs/migration/technical-spike-plan.md`'s H2 experiment (tenant
isolation: PostgreSQL Row-Level Security vs. a repository-layer guard
pattern).

**Per the spike plan's Rollback / Discard Strategy**: this code must not
be able to accidentally become production code. It is torn down (deleted
from the branch, not merged to `main` as "done") once the spike report
is written. Only the **written report**
(`docs/migration/technical-spike-plan.md`'s findings section, or a
dedicated spike-report document) and any explicitly graduated, deliberately
chosen reusable outputs (e.g. a validated Flyway migration-naming
convention) are intentionally promoted into the real repository —
as a separate, deliberate decision after the spike, never by default.

`scripts/validate_phase0.py`'s CODEOWNERS component-coverage check
excludes this directory explicitly (see that script's `EXCLUDED
components` handling) — this exclusion was added deliberately as part of
executing this spike, not silently.

## What's Here

`tenant-isolation-spike/` — a real Spring Boot 4.1 / Java 25 / Spring
Modulith 2.1 project (generated via the live Spring Initializr,
`start.spring.io`), implementing the same tiny vertical slice the spike
plan specifies: tenant/company identity plus one reference-data entity
(`branches`), with **both** candidate tenant-isolation mechanisms
implemented side by side for direct comparison, switched via Spring
profile (`isolation-rls` vs `isolation-guard`) rather than two separate
codebases, so the comparison is apples-to-apples against identical
business logic.

See `tenant-isolation-spike/SPIKE-NOTES.md` for the running findings log.

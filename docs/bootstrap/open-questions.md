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

## Payroll Migration

Surfaced by `docs/migration/payroll-module-execution-plan.md`
(2026-08-07, updated after reconciliation against `main`) — none of
these are decided by that document, and none should be guessed at
implementation time:

- Is `salary_contracts.housing_allowance` a normal settable contract
  field going forward, or intentionally payslip-only (`hr-legacy#14`)?
- Preserve the legacy fixed-30-day payroll divisor, or move to real
  calendar days for day-rate/absence calculations?
- Where does per-company fiscal-period configuration
  (`month_start_day`/`month_end_day`) live until the `company_settings`
  module is built?
- What should the real advance-deduction scheduling mechanism be at
  payroll finalize? The current implementation uses an explicit,
  documented v1 heuristic (oldest APPROVED advance first, FIFO) because
  `advances`' `deduction_mode`/`deduction_amount_per_month`/
  `deduction_payroll_year`/`deduction_payroll_month` columns (V12)
  remain unmapped and unused, per that module's own Javadoc parking the
  legacy `deduction_type` redundancy as a product decision. Revisit
  once that decision is made.

## Tooling

- Will `specify-cli` be installed during Phase 0 or deferred until human review approves it?
- Will GitHub MCP be enabled read-only during discovery or deferred entirely?

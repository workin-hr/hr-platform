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
  module is built? **Resolved 2026-08-07**: the typed
  `company_settings` module exists (V27,
  `docs/superpowers/specs/2026-08-07-company-settings-first-slice-design.md`)
  and `PayrollBatchService.create` computes batch periods with the
  ported `payroll_fiscal_period_bounds` algorithm; unset settings
  reproduce calendar months exactly.
- What should the real advance-deduction scheduling mechanism be at
  payroll finalize? The current implementation uses an explicit,
  documented v1 heuristic (oldest APPROVED advance first, FIFO) because
  `advances`' `deduction_mode`/`deduction_amount_per_month`/
  `deduction_payroll_year`/`deduction_payroll_month` columns (V12)
  remain unmapped and unused, per that module's own Javadoc parking the
  legacy `deduction_type` redundancy as a product decision. Revisit
  once that decision is made.

## Requests/Leave Migration

Surfaced by `docs/superpowers/specs/2026-08-07-requests-leave-balances-first-slice-design.md` —
neither is decided by that document:

- Legacy's `exception_type_resolve_for_company()` (a company-default
  fallback when a request type has `add_attendance_exception` but no
  `exception_type_id`) was not read this pass — the new approve
  conservatively skips the side effect in that case. Read the resolver
  and decide whether to port its fallback.
- The auto-created leave balance uses the constant 21.0-day fallback;
  legacy consults the `MONTHLY_LEAVE_ACCRUAL` company setting first.
  Revisit when the `company_settings` module exists (same family as
  the payroll fiscal-period question above). **Resolved 2026-08-07**:
  `RequestService` now reads `monthly_leave_accrual` via
  `CompanySettingsService.effective` with 21.0 as the unset fallback.

## Tooling

- Will `specify-cli` be installed during Phase 0 or deferred until human review approves it?
- Will GitHub MCP be enabled read-only during discovery or deferred entirely?

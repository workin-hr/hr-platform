# Wave 12 completion audit

Working audit for the remaining Phase 1 Wave 12 scope after Wave 12.7.

Authoritative source: frozen `workin-hr/hr-legacy` API tree at `d113204c8a2cf83b997c5e65c6c86e4f59b3f8f6` plus the Java delivered-route inventory.

## Remaining endpoint scope after Wave 12.7

- Deferred Wave 12.6 attendance: 5 routes — `list`, `stats`, `employee_monthly_attendance`, `overall_report`, `export`.
- Wave 12.8: 20 routes — `salary_contracts` 5, `advances` 8, `penalties` 7.
- Wave 12.9: 16 routes — `payroll_batches` 10, `payslips` 6.
- Wave 12.10: 3 routes — `company/update`, `company/upload_logo`, `company/upload_commercial_reg`.
- Wave 12.R: retrofit already-implemented routes that are not yet exact legacy-path/envelope compatible, then run bidirectional route coverage.

That is 44 newly delivered routes before the compatibility retrofit. With the 62 routes delivered through Wave 12.7, the route inventory should reach 106 before Wave 12.R changes compatibility classification.

## Execution state

- `salary_contracts`: **implemented and slice-reviewed (5/5)** — production controller/service/store, route guard, inventory and focused regressions are present.
- `advances`: **implemented and adversarially slice-reviewed (8/8)** — all frozen routes (`create`, `list`, `one`, `update`, `approve`, `reject`, `pay`, `delete`) are mapped; focused tests lock role/ownership behavior, null-coalescing, deduction normalization, overpayment, pending-only edits and the legacy id-only action quirks. The delivered-route inventory is now **75**.
- `penalties`: next unfinished Wave 12.8 slice (0/7).
- Deferred attendance, Wave 12.9, Wave 12.10 and Wave 12.R remain pending.

### Salary-contract adversarial review notes

- Preserved PHP method → auth role list → active-company guard order.
- Reads permit `company_admin`, `hr`, `manager`; writes permit only `company_admin`, `hr`.
- `daily` mode zeros `basic_salary`, transport/food/risk allowances and incentives, while preserving `daily_wage` and deductions.
- Invalid update salary modes fall back to `monthly`; create treats every non-`daily` value as `monthly`.
- `daily_wage` update uses `array_key_exists` semantics so explicit null/empty clears it.
- Employee existence and contract ownership remain company-derived through `employees.company_id`.
- Post-write re-read remains id-only, matching PHP rather than adding a new scoped read.

### Advance adversarial review notes

- Preserved method → auth role list → active-company ordering for all eight routes.
- `list`, `one` and the `update` preflight retain company scoping; `approve`, `reject`, `pay` and `delete` deliberately retain the frozen source's id-only lookup/write behavior rather than adding new tenant filtering.
- Employee create ignores caller-supplied `employee_id`/`status`, uses the authenticated employee and forces `pending`; admin/HR create keeps PHP's unvalidated initial status behavior.
- Employee update can only alter amount/reason and resets `remaining` to the chosen amount, exactly as PHP does.
- PHP `??` semantics are preserved on update: an explicit JSON null for amount/reason/status falls back to the stored value. Adversarial review found and fixed an initial `containsKey` implementation that would have written null instead.
- `array_key_exists` semantics remain distinct for deduction payroll year/month and installments JSON, so explicit null/empty clears those values.
- Invalid deduction mode/type values normalize to the same legacy defaults.
- Missing rows after id-only approve/reject post-write re-read remain unexpected failures rather than being modernized to 404.
- JDBC row mapping uses `LegacyJdbcValues.read(..., sqlType)` so DECIMAL and temporal values keep PDO-compatible wire representation. Compile-focused review found and fixed the initial missing SQL-type argument.

## CI infrastructure blocker

GitHub-hosted runner provisioning is still failing before the first job step for this private organization/repository. On head `0aaab1bc901fd0ba7d24e9c2bf96216e308de3f0`, `Backend Validate` completed with `steps=[]`, `runner_id=0` and no runner name. That is an organization Actions provisioning/entitlement failure, not an application test result. The newer advance-store review fix has the same organization-level validation dependency; no affected head may be called CI-green until a runner is actually assigned and executes steps.

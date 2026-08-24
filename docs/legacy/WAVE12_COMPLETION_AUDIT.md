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

- `salary_contracts`: **implemented and slice-reviewed** — all 5 routes have production controller/service/store code, exact route-guard registration, bidirectional inventory coverage, focused service regression tests, and automatic coverage by the global mapped-route unauthenticated guard-order E2E. The inventory is now 67 delivered routes. Hosted CI execution is still blocked by runner provisioning, so this means implementation/review complete, not CI-green.
- `advances`: source behavior discovery complete; implementation is the next unfinished Wave 12.8 slice.
- Deferred attendance, `penalties`, Wave 12.9, Wave 12.10 and Wave 12.R remain pending.

### Salary-contract adversarial review notes

- Preserved PHP method → auth role list → active-company guard order.
- Reads permit `company_admin`, `hr`, `manager`; writes permit only `company_admin`, `hr`.
- `daily` mode zeros `basic_salary`, transport/food/risk allowances and incentives, while preserving `daily_wage` and deductions.
- Invalid update salary modes fall back to `monthly`; create treats every non-`daily` value as `monthly`.
- `daily_wage` update uses `array_key_exists` semantics so explicit null/empty clears it.
- Employee existence and contract ownership remain company-derived through `employees.company_id`.
- Post-write re-read remains id-only, matching PHP rather than adding a new scoped read.
- Self-review removed an accidental diff expansion: the existing security rationale in `LegacyPhpRoutes` was restored and the slice now adds only the required route entry.

## CI infrastructure blocker

GitHub-hosted runner provisioning is currently failing before the first job step for this private organization/repository. The latest `Backend Validate` on this branch again completed with an empty step list and no runner assignment, matching the earlier temporary `ubuntu-latest` echo-only diagnostic. This excludes application code, Gradle and runner-image execution from that failure. Code/test review must not treat the infrastructure failure as a test failure, but Wave 12 cannot be called CI-validated until the organization Actions entitlement/provisioning issue is restored.

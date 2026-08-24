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

- `salary_contracts`: production controller/service/store implemented on this branch; exact route/test inventory still needs to be closed before marking the slice complete.
- Remaining modules: not yet marked implemented.

## CI infrastructure blocker

GitHub-hosted runner provisioning is currently failing before the first job step for this private organization/repository. A temporary `ubuntu-latest` echo-only diagnostic reproduced `steps=null`, excluding application code, Gradle and runner-image selection. Code/test review must not treat that infrastructure failure as a test failure, but Wave 12 cannot be called CI-validated until the organization Actions entitlement/provisioning issue is restored.

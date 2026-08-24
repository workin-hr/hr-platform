# Wave 12 completion audit

Working audit for the remaining Phase 1 Wave 12 scope after Wave 12.7.

Authoritative source: frozen `workin-hr/hr-legacy` API tree at `d113204c8a2cf83b997c5e65c6c86e4f59b3f8f6` plus the Java delivered-route inventory.

Remaining execution order:

1. Close the five request-dependent attendance routes deferred from Wave 12.6.
2. Wave 12.8: `salary_contracts`, `advances`, `penalties`.
3. Wave 12.9: `payroll_batches`, `payslips`.
4. Wave 12.10: `company`.
5. Wave 12.R: retrofit already-implemented routes that are not yet exact legacy-path/envelope compatible, then run bidirectional route coverage.

CI note: GitHub-hosted runner provisioning is currently failing before the first job step for this private organization/repository. A temporary `ubuntu-latest` echo-only diagnostic reproduced `steps=null`, excluding application code, Gradle and runner-image selection. Code/test review must not treat that infrastructure failure as a test failure, but Wave 12 cannot be called CI-validated until the organization Actions entitlement/provisioning issue is restored.

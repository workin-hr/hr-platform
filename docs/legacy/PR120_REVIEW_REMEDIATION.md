# PR #120 review remediation

This document records the final review-driven corrections applied to Phase 1 Wave 12 on pull request #120. The frozen PHP source remains the compatibility authority; the changes below correct Java behavior or runtime cost without changing the literal route inventory.

## Closed findings

### Payslip `penalty_days: null`

`/apis/api/payslips/update.php` now normalizes an explicit JSON `null` for `penalty_days` to the same shape as an omitted key before entering `LegacyPayslipService`. This reproduces PHP null-coalescing (`??`): both forms fall back to the employee's unapplied penalty-day total, while a non-null explicit value remains an override.

Regression coverage: `LegacyPayslipControllerNullCoalescingTest`.

### Department list N+1 wire reads

`/apis/api/departments/list.php` still returns the same lexical MariaDB-backed row shape, but it now loads the company's raw department rows in one company-scoped query and joins them to the already-filtered service views in memory. `one.php`, `create.php`, and `update.php` retain their single-row lexical reads.

Regression coverage: `LegacyDepartmentPhpControllerBatchReadTest`, which verifies one `JdbcTemplate.query` call for a multi-row list and no per-row JDBC calls.

### Payroll batch update/finalize race

`payroll_batches/update.php` and `finalize.php` now repeat their authoritative batch read under the same `SELECT ... FOR UPDATE` lifecycle lock. The period update, fiscal-setting reads, finalization transition, and finalization side effects run on the transaction-bound legacy connection. This prevents both race directions:

- finalization wins first: update observes `finalized` under the lock and aborts;
- update wins first: finalization re-reads the updated month/year under the lock before resolving fiscal bounds and applying side effects.

A Spring `DataSourceTransactionManager`/`TransactionTemplate` is used for these paths so `LegacyPayrollBatchStore` and `LegacyPayrollFiscalSettings` reuse the same physical legacy connection. The calculation path keeps its existing precompute-then-short-locked-write design and therefore does not reintroduce the connection-pool exhaustion finding from the prior review round.

Regression coverage: `LegacyPayrollBatchServiceTest#updateRechecksFinalizedStateAfterTakingTheLifecycleLock` and `#finalizeUsesThePeriodVisibleAfterTakingTheLifecycleLock`.

## Deliberately unchanged findings

D-117 remains authoritative for the two finalize-time advance/penalty findings. Frozen PHP reads those rows live at finalize time; changing that behavior in Phase 1 would alter compatibility and can overwrite manual payslip edits. Those findings are documented compatibility decisions, not untracked defects.

## Propagation review

- API route inventory: not changed; paths, verbs, request keys, and response envelopes are unchanged.
- OpenAPI/client examples: not applicable; these are literal frozen-PHP compatibility routes and no wire contract changed.
- Database schema/migrations: not changed.
- Agent/skill definitions: not changed.
- Security model: not changed.
- Durable documentation: this file records the runtime behavior and concurrency correction.
- Tests: targeted regression coverage added for all three corrected findings.

## Required validation

The final PR head must pass:

- `Backend Validate` (`./gradlew clean test compilePhase2TestJava` via CI), including the new regression tests;
- `Phase 0 Bootstrap Validate`, including repository propagation/structure checks;
- a fresh Codex review of the final head before human merge.

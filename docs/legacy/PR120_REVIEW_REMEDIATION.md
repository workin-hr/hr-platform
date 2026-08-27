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

## Closed findings -- concurrency round

A further review round closed four write races that the earlier round did not cover. All four
keep the frozen PHP wire contract: no route, verb, request key, or response envelope changed,
and each race resolves to an error code the endpoint could already return.

### Employee advance edit vs. concurrent approval

`advances/update.php` checked `status = 'pending'` on a preflight read and then wrote
amount/remaining/reason on a later statement. An administrator approving the advance in between
was silently overwritten by the stale employee edit. `LegacyAdvanceStore.updateEmployee` now
folds the predicate into the write (`UPDATE ... WHERE id=? AND status='pending'`) and returns the
affected-row count; `LegacyAdvanceService.update` maps a zero count to the same
`400 cannot_edit_non_pending_advance` a normal non-pending edit already returns.

Regression coverage: `LegacyAdvanceServiceTest#employeeUpdateRejectsWhenApprovalWinsAfterThePendingPreflight`.

### Penalty mutation vs. batch finalization

`penalties/update.php` and `delete.php` validated mutability from a preflight read, so a payroll
batch finalizing in between could mark the penalty applied and still have the mutation land --
leaving a finalized payslip whose deduction no longer matches the surviving penalty row.
`LegacyPenaltyStore.updateFields` and `deleteById` now carry `AND applied_to_payroll=0` in the
statement itself and return the affected-row count; `LegacyPenaltyService` maps a zero count to
the same `403 forbidden` the immutability check already returns.

Regression coverage: `LegacyPenaltyServiceTest` finalization-race cases.

### Payslip mutation vs. batch finalization

`payslips/create.php`, `update.php`, and `delete.php` each rejected a finalized batch, but read
that status on one statement and mutated the payslip on a later one, so a concurrent
`payroll_batches/finalize.php` could commit in the gap. `LegacyPayslipWriteCoordinator` now takes
the same batch-row `SELECT ... FOR UPDATE` lifecycle lock that calculate/update/delete/finalize
use, then delegates to the unchanged service. Because the delegate's stores share the legacy
DataSource, their reads and writes reuse the transaction-bound physical connection while the lock
is held. Paths that cannot mutate -- a create with no `batch_id`, or a payslip that does not
resolve -- still short-circuit to the service so validation and error ordering are preserved.

Regression coverage: `LegacyPayslipWriteCoordinatorTest`.

### Duplicate payroll batch for one period

The frozen schema has no unique key on `(company_id, month, year)`, so
`payroll_batches/create.php` was a check-then-insert that two concurrent requests could both
pass, creating two batches for the same period. `LegacyPayrollBatchCreateLock` locks the owning
`companies` row `FOR UPDATE` and `LegacyPayrollBatchCreateCoordinator` runs the existing service
create inside that transaction, so the second caller only proceeds once the first has committed
and its `existsForPeriod` check sees the new batch. This serializes per tenant without altering
the frozen schema. Missing `month`/`year` short-circuits to the service so required-field
validation ordering is unchanged.

Regression coverage: `LegacyPayrollBatchServiceTest` create-serialization cases.

### Operational dependency: affected-row-count semantics

The advance and penalty guards above decide "the write lost the race" from a JDBC affected-row
count of zero. That inference is only correct while the legacy connection reports *matched* rows
rather than *changed* rows -- that is, while `CLIENT_FOUND_ROWS` is in effect. MariaDB
Connector/J controls this with the `useAffectedRows` connection option.

`LEGACY_DB_JDBC_URL` must therefore not enable `useAffectedRows`. If it were enabled, an
otherwise legal edit that submits values identical to the stored row would change no rows, and
the guard would misread that no-op as a lost race -- returning
`400 cannot_edit_non_pending_advance` or `403 forbidden` for a request that should succeed. An
operator would see those two codes rising on `advances/update.php` and `penalties/update.php`
immediately after a connection-string change, with no corresponding approval or finalization
traffic.

Mock-based tests cannot observe this, because the row count they stub is the very thing in
question. Both affected paths are therefore pinned against real MariaDB by an edit that resubmits
the stored values: such an edit changes no columns, so it succeeds only under matched-row
semantics.

| Path | Regression test | Symptom under changed-row semantics |
| --- | --- | --- |
| `advances/update.php` | `LegacyAdvancePayEndToEndTest#employeeEditResubmittingStoredValuesSucceedsInsteadOfLookingLikeALostRace` | `400 Cannot edit non-pending advance` |
| `penalties/update.php` | `LegacyPayrollBatchCalculateEndToEndTest#penaltyEditResubmittingStoredValuesSucceedsInsteadOfLookingLikeALostRace` | `403 Forbidden` |

Both were verified by falsification, not assumed. With MariaDB Connector/J defaults they pass,
confirming matched-row semantics are in effect. Appending `?useAffectedRows=true` to the test
container's JDBC URL makes each fail with exactly the production symptom in the table above. The
guards therefore turn a silent production regression into a build failure.

`penalties/delete.php` shares the same `applied_to_payroll=0` guard as `penalties/update.php` and
the same row-count dependency; the update test covers the semantics for both, since a delete
always changes the matched row and so cannot exhibit the no-op case.

## Deliberately unchanged findings

D-117 remains authoritative for the two finalize-time advance/penalty findings. Frozen PHP reads those rows live at finalize time; changing that behavior in Phase 1 would alter compatibility and can overwrite manual payslip edits. Those findings are documented compatibility decisions, not untracked defects.

## Propagation review

- API route inventory: not changed; paths, verbs, request keys, and response envelopes are unchanged.
- OpenAPI/client examples: not applicable; these are literal frozen-PHP compatibility routes and no wire contract changed.
- Database schema/migrations: not changed.
- Agent/skill definitions: not changed.
- Security model: not changed.
- Durable documentation: this file records the runtime behavior and concurrency correction.
- Tests: targeted regression coverage added for all corrected findings in both rounds.
- Test premise: `LegacyAdvanceNullCoalescingTest` stubs the new `updateEmployee` row count.
  Its mock previously relied on Mockito's default `0`, which the conditional-write guard now
  reads as a lost race; the stub restores the null-coalescing behavior the test actually asserts.

## Required validation

The final PR head must pass:

- `Backend Validate` (`./gradlew clean test compilePhase2TestJava` via CI), including the new regression tests;
- `Phase 0 Bootstrap Validate`, including repository propagation/structure checks;
- a fresh Codex review of the final head before human merge.

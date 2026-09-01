package com.workin.legacy.payroll;

import java.util.Map;
import java.util.Objects;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.workin.legacy.LegacyValues;
import com.workin.legacy.wire.LegacyApiException;

/**
 * Serializes mutable payslip endpoints with the payroll-batch lifecycle.
 *
 * <p>{@code payslips/create.php}, {@code update.php}, and {@code delete.php} all reject a
 * finalized batch, but their original Java port checked the status on one statement and
 * mutated the payslip later. A concurrent {@code payroll_batches/finalize.php} could commit
 * between those statements. This coordinator takes the same batch-row {@code FOR UPDATE}
 * lifecycle lock used by calculate/update/delete/finalize before delegating to the existing
 * service. Because the delegate's stores use the same legacy DataSource/JdbcTemplate, their
 * reads and writes reuse the transaction-bound physical connection while this lock is held.
 */
@Service
public class LegacyPayslipWriteCoordinator {

	private static final String FINALIZED = "finalized";

	private final LegacyPayslipService service;
	private final LegacyPayslipStore payslipStore;
	private final LegacyPayrollBatchStore batchStore;
	private final TransactionTemplate transactionTemplate;

	public LegacyPayslipWriteCoordinator(
			LegacyPayslipService service,
			LegacyPayslipStore payslipStore,
			LegacyPayrollBatchStore batchStore,
			@Qualifier("legacyDataSource") DataSource legacyDataSource) {
		this.service = service;
		this.payslipStore = payslipStore;
		this.batchStore = batchStore;
		this.transactionTemplate = new TransactionTemplate(new DataSourceTransactionManager(legacyDataSource));
	}

	public Map<String, Object> create(long companyId, Map<String, Object> body) {
		// EVERY required field is checked before the lock, not just batch_id.
		//
		// create.php runs required($body, [BATCH_ID, EMPLOYEE_ID]) before it
		// looks at the batch at all, so a request missing employee_id answers
		// "Field 'employee_id' is required" even when the batch is finalized.
		// Locking first inverted that: lockMutableBatch() raised
		// "Batch already finalized" and the missing field was never reported.
		// An earlier version guarded only batch_id, which left the second
		// required field behind the lock. Found by the parity harness's
		// mutation sweep.
		//
		// Delegating is safe for exactly the reason the original comment gave:
		// a request that cannot pass required() cannot mutate anything, so it
		// needs no transaction.
		if (missingRequired(body, "batch_id") || missingRequired(body, "employee_id")) {
			return service.create(companyId, body);
		}
		Object rawBatchId = body.get("batch_id");
		long batchId = LegacyValues.toPhpLong(rawBatchId);
		return requiredResult(transactionTemplate.execute(ignored -> {
			lockMutableBatch(companyId, batchId);
			return service.create(companyId, body);
		}));
	}

	public Map<String, Object> update(
			long companyId, long payslipId, Map<String, Object> body,
			String presentLabel, String weeklyRestLabel, String officialHolidayFallbackLabel) {
		Map<String, Object> target = payslipStore.withBatchStatus(payslipId, companyId);
		if (target == null) {
			return service.update(
					companyId, payslipId, body, presentLabel, weeklyRestLabel, officialHolidayFallbackLabel);
		}
		long batchId = LegacyValues.toPhpLong(target.get("batch_id"));
		return requiredResult(transactionTemplate.execute(ignored -> {
			lockMutableBatch(companyId, batchId);
			return service.update(
					companyId, payslipId, body, presentLabel, weeklyRestLabel, officialHolidayFallbackLabel);
		}));
	}

	public void delete(long companyId, long payslipId) {
		Map<String, Object> target = payslipStore.withBatchStatus(payslipId, companyId);
		if (target == null) {
			service.delete(companyId, payslipId);
			return;
		}
		long batchId = LegacyValues.toPhpLong(target.get("batch_id"));
		transactionTemplate.executeWithoutResult(ignored -> {
			lockMutableBatch(companyId, batchId);
			service.delete(companyId, payslipId);
		});
	}

	/** {@code required()} treats null and the empty string alike; so does this. */
	private static boolean missingRequired(Map<String, Object> body, String key) {
		Object value = body.get(key);
		return value == null || "".equals(value);
	}

	private void lockMutableBatch(long companyId, long batchId) {
		Map<String, Object> batch = batchStore.scopedForUpdate(batchId, companyId);
		if (batch == null) {
			throw new LegacyApiException(404, "batch_not_found");
		}
		if (FINALIZED.equals(batch.get("status"))) {
			throw new LegacyApiException(400, "batch_already_finalized");
		}
	}

	private static Map<String, Object> requiredResult(Map<String, Object> result) {
		return Objects.requireNonNull(result, "transactional payslip write returned null");
	}
}

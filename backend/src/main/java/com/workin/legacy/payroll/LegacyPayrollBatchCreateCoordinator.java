package com.workin.legacy.payroll;

import java.util.Map;
import java.util.Objects;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Serializes payroll-batch creation per company so one period cannot be inserted twice. */
@Service
public class LegacyPayrollBatchCreateCoordinator {

	private final LegacyPayrollBatchService service;
	private final LegacyPayrollBatchCreateLock createLock;
	private final TransactionTemplate transactionTemplate;

	public LegacyPayrollBatchCreateCoordinator(
			LegacyPayrollBatchService service,
			LegacyPayrollBatchCreateLock createLock,
			@Qualifier("legacyDataSource") DataSource legacyDataSource) {
		this.service = service;
		this.createLock = createLock;
		this.transactionTemplate = new TransactionTemplate(new DataSourceTransactionManager(legacyDataSource));
	}

	public Map<String, Object> create(long companyId, Map<String, Object> body) {
		if (missingRequired(body, "month") || missingRequired(body, "year")) {
			// Preserve required-field validation ordering without locking when no insert can occur.
			return service.create(companyId, body);
		}
		return Objects.requireNonNull(transactionTemplate.execute(ignored -> {
			createLock.lockCompany(companyId);
			// The existing service re-runs existsForPeriod after the lock. A concurrent
			// creator for this tenant must commit and release the company row before this
			// transaction proceeds, so the second check sees and rejects the first batch.
			return service.create(companyId, body);
		}), "transactional payroll batch create returned null");
	}

	private static boolean missingRequired(Map<String, Object> body, String key) {
		Object value = body.get(key);
		return value == null || "".equals(value);
	}
}

package com.workin.legacy.payroll;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Cross-instance mutex for {@code payroll_batches/create.php}.
 *
 * <p>The frozen schema has no unique key on {@code (company_id, month, year)}. Locking the
 * owning company row serializes the legacy check-then-insert sequence for one tenant without
 * changing that schema or the wire contract. The caller must invoke this inside a transaction.
 */
@Repository
public class LegacyPayrollBatchCreateLock {

	private final JdbcTemplate jdbc;

	public LegacyPayrollBatchCreateLock(@Qualifier("legacyDataSource") DataSource legacyDataSource) {
		this.jdbc = new JdbcTemplate(legacyDataSource);
	}

	public void lockCompany(long companyId) {
		jdbc.queryForList("SELECT id FROM companies WHERE id=? FOR UPDATE", Long.class, companyId);
	}
}

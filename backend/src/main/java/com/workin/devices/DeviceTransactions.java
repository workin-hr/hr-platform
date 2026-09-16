package com.workin.devices;

import javax.sql.DataSource;

import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The module's transactions: its own when none is open, the caller's when one is.
 *
 * <p>A plain {@code TransactionTemplate(new DataSourceTransactionManager(...))} does not join a
 * transaction the application's JPA transaction manager opened. That manager exposes its connection
 * to JDBC but never marks it transaction-active, so the DataSource manager starts a "new"
 * transaction on the same connection and COMMITS it when its callback returns -- in the middle of
 * the caller's transaction. A platform-administrator write that allocates a terminal and then
 * records its audit row therefore committed the allocation first, and a failure writing the audit
 * row left the change without its record (ADR-0015 prerequisite 10).
 *
 * <p>When any transaction is already active the work runs inside it, and an exception rolls the
 * whole of it back. The callbacks here never set rollback-only; they throw.
 */
public final class DeviceTransactions extends TransactionTemplate {

	private static final long serialVersionUID = 1L;

	private DeviceTransactions(DataSource dataSource) {
		super(new DataSourceTransactionManager(dataSource));
	}

	public static DeviceTransactions over(DataSource dataSource) {
		return new DeviceTransactions(dataSource);
	}

	@Override
	public <T> T execute(TransactionCallback<T> action) {
		if (TransactionSynchronizationManager.isActualTransactionActive()) {
			return action.doInTransaction(new SimpleTransactionStatus(false));
		}
		return super.execute(action);
	}
}

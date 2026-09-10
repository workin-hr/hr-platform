package com.workin.devices.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.Statement;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import com.workin.devices.assignment.DeviceAssignmentHistoryStore;
import com.workin.legacy.AbstractLegacyMySqlTest;
import com.workin.legacy.LegacyClock;

/**
 * Claiming a device onto a branch that is being deleted underneath it.
 *
 * <p>{@code device_punches.branch_id} has no foreign key, so nothing at the
 * schema level stops a claim landing on a branch that no longer exists. The
 * ownership check runs before the claim's transaction opens, so a delete in
 * that window left an active device -- and then punches -- carrying a branch
 * id that resolves to nothing.
 *
 * <p>The narrow question is whether the in-transaction re-read actually HOLDS
 * the row. A re-read that did not lock would only shrink the window, and a
 * test that simply deleted the branch first would pass either way. So this
 * holds the lock on one connection and proves a second cannot delete the
 * branch while it is held.
 */
class BranchClaimLockTest extends AbstractLegacyMySqlTest {

	private static final long COMPANY = 9940;
	private static final long BRANCH = 994001;

	private DataSource dataSource;

	private AttendanceDeviceStore store() {
		return new AttendanceDeviceStore(dataSource,
				new DeviceAssignmentHistoryStore(dataSource), new LegacyClock(dataSource));
	}

	private static void exec(String sql) throws Exception {
		try (Connection c = connect(); Statement s = c.createStatement()) {
			s.execute(sql);
		}
	}

	@BeforeEach
	void seed() throws Exception {
		exec("DELETE FROM branches WHERE id = " + BRANCH);
		exec("DELETE FROM companies WHERE id = " + COMPANY);
		// branches.company_id has a foreign key; device_punches.branch_id has
		// none, which is exactly why a device can outlive its branch and why
		// this lock has to do the work the schema does not.
		seedAsLegacyWould(
				"INSERT INTO companies (id, company_name, phone, password_hash, status,"
						+ " otp_verified, profile_completed, created_at) VALUES ("
						+ COMPANY + ", 'Lock Co', '+201100249940', 'x', 'active', 1, 1,"
						+ " '2025-01-01 09:00:00')",
				"INSERT INTO branches (id, company_id, name, is_active, created_at) VALUES ("
						+ BRANCH + ", " + COMPANY + ", 'Locked', 1, '2025-01-01 09:00:00')");
		this.dataSource = new DriverManagerDataSource(
				MARIADB.getJdbcUrl(), MARIADB.getUsername(), MARIADB.getPassword());
	}

	@Test
	void aDeletedBranchIsNotFoundByTheLockingLookup() throws Exception {
		AttendanceDeviceStore store = store();
		assertThat(store.branchCompanyIdForUpdate(BRANCH)).isEqualTo(COMPANY);

		exec("DELETE FROM branches WHERE id = " + BRANCH);

		assertThat(store.branchCompanyIdForUpdate(BRANCH))
				.as("a branch deleted before the claim must not be claimable")
				.isNull();
	}

	@Test
	void theLookupHoldsTheBranchRowSoAConcurrentDeleteCannotWin() {
		AttendanceDeviceStore store = store();
		TransactionTemplate transactions =
				new TransactionTemplate(new DataSourceTransactionManager(dataSource));

		transactions.execute(status -> {
			// Inside the claim's transaction, exactly as claim() now does.
			assertThat(store.branchCompanyIdForUpdate(BRANCH)).isEqualTo(COMPANY);

			// A concurrent delete on its own connection, with a short wait so
			// the test states an outcome instead of hanging.
			assertThatThrownBy(() -> {
				try (Connection other = connect(); Statement s = other.createStatement()) {
					s.execute("SET SESSION innodb_lock_wait_timeout = 1");
					s.execute("DELETE FROM branches WHERE id = " + BRANCH);
				}
			})
					.as("the branch row must be held for the life of the claim")
					.isInstanceOf(Exception.class);
			return null;
		});
	}
}

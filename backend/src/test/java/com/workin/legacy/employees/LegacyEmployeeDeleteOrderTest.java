package com.workin.legacy.employees;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import com.workin.legacy.AbstractLegacyMySqlTest;

/**
 * The order of the two statements on {@code delete.php}'s direct path.
 *
 * <p>That path is deliberately not transactional (D-077), so the two deletes
 * commit independently and their ORDER is the only thing deciding which
 * partial state is reachable. Removing the device identity first left a
 * reachable state where the employee survives with their PIN binding already
 * gone -- their terminal punches stop resolving, silently, with nothing to say
 * why.
 */
class LegacyEmployeeDeleteOrderTest extends AbstractLegacyMySqlTest {

	private static final long COMPANY = 9950;
	private static final long BRANCH = 995001;
	private static final long EMPLOYEE = 995010;
	private static final long OTHER_COMPANY = 9951;

	private LegacyEmployeeStore store;

	private static void exec(String sql) throws Exception {
		try (Connection c = connect(); Statement s = c.createStatement()) {
			s.execute(sql);
		}
	}

	private static long count(String sql) throws Exception {
		try (Connection c = connect(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
			return rs.next() ? rs.getLong(1) : 0L;
		}
	}

	@BeforeEach
	void seed() throws Exception {
		exec("DELETE FROM employee_device_identities WHERE employee_id = " + EMPLOYEE);
		exec("DELETE FROM employees WHERE id = " + EMPLOYEE);
		exec("DELETE FROM branches WHERE id = " + BRANCH);
		exec("DELETE FROM companies WHERE id IN (" + COMPANY + ", " + OTHER_COMPANY + ")");
		seedAsLegacyWould(
				"INSERT INTO companies (id, company_name, phone, password_hash, status,"
						+ " otp_verified, profile_completed, created_at) VALUES ("
						+ COMPANY + ", 'Order Co', '+201100249950', 'x', 'active', 1, 1,"
						+ " '2025-01-01 09:00:00')",
				"INSERT INTO companies (id, company_name, phone, password_hash, status,"
						+ " otp_verified, profile_completed, created_at) VALUES ("
						+ OTHER_COMPANY + ", 'Other Co', '+201100249951', 'x', 'active', 1, 1,"
						+ " '2025-01-01 09:00:00')",
				"INSERT INTO branches (id, company_id, name, is_active, created_at) VALUES ("
						+ BRANCH + ", " + COMPANY + ", 'HQ', 1, '2025-01-01 09:00:00')",
				"INSERT INTO employees (id, company_id, branch_id, employee_code, first_name,"
						+ " last_name, phone, role, is_active, is_mobile_attendance_enabled,"
						+ " can_check_in_any_branch, join_request_status, token_version, created_at)"
						+ " VALUES (" + EMPLOYEE + ", " + COMPANY + ", " + BRANCH + ", '9950',"
						+ " 'Order', 'Test', '+201100249952', 'employee', 1, 1, 0, 'accepted', 1,"
						+ " '2025-01-01 09:00:00')",
				"INSERT INTO employee_device_identities (company_id, employee_id, pin, source,"
						+ " created_at, updated_at) VALUES (" + COMPANY + ", " + EMPLOYEE
						+ ", '9950', 'MANUAL', '2025-01-01 09:00:00', '2025-01-01 09:00:00')");
		this.store = new LegacyEmployeeStore(new DriverManagerDataSource(
				MARIADB.getJdbcUrl(), MARIADB.getUsername(), MARIADB.getPassword()));
	}

	@Test
	void deletingTheEmployeeAlsoRemovesTheirDeviceIdentity() throws Exception {
		store.deleteEmployeeUnscopedOfAnyTransaction(EMPLOYEE, COMPANY);

		assertThat(count("SELECT COUNT(*) FROM employees WHERE id = " + EMPLOYEE)).isZero();
		assertThat(count("SELECT COUNT(*) FROM employee_device_identities WHERE employee_id = "
				+ EMPLOYEE)).isZero();
	}

	@Test
	void aFailingEmployeeDeleteLeavesTheBindingIntact() throws Exception {
		// The reviewer's case is a related row whose foreign key refuses the
		// delete; this schema has no such constraint, so the same failure is
		// produced the way production actually produces it -- another
		// transaction holding the row until this one times out. Deadlock and
		// connection loss land in the same place.
		try (Connection holder = connect(); Statement lock = holder.createStatement()) {
			holder.setAutoCommit(false);
			lock.execute("SELECT id FROM employees WHERE id = " + EMPLOYEE + " FOR UPDATE");

			LegacyEmployeeStore impatient = new LegacyEmployeeStore(new DriverManagerDataSource(
					MARIADB.getJdbcUrl() + "?sessionVariables=innodb_lock_wait_timeout=1",
					MARIADB.getUsername(), MARIADB.getPassword()));

			assertThat(catchThrowable(() ->
					impatient.deleteEmployeeUnscopedOfAnyTransaction(EMPLOYEE, COMPANY)))
					.as("the employee delete must fail while the row is held")
					.isNotNull();

			holder.rollback();
		}

		assertThat(count("SELECT COUNT(*) FROM employees WHERE id = " + EMPLOYEE))
				.as("the employee survived, because its delete failed").isOne();
		assertThat(count("SELECT COUNT(*) FROM employee_device_identities WHERE employee_id = "
				+ EMPLOYEE))
				.as("so their PIN binding must survive too -- otherwise the employee is "
						+ "present with their terminal punches silently resolving to nobody")
				.isOne();
	}
}

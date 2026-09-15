package com.workin.legacy.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import com.workin.legacy.AbstractLegacyMySqlTest;
import com.workin.legacy.profile.LegacyCompanyDelete.ClearedTable;
import com.workin.legacy.wire.LegacyMessages;

/**
 * What the company cascade deletes, against what the platform administrator's
 * delete page is told it will, and how a lock failure inside it ends.
 */
class LegacyCompanyDeleteCascadeTest extends AbstractLegacyMySqlTest {

	private static final long COMPANY = 9920;

	/** The table a {@code DELETE} removes rows from, with or without an alias before {@code FROM}. */
	private static final Pattern DELETE_TARGET = Pattern.compile("(?is)^\\s*DELETE\\s+(?:\\w+\\s+)?FROM\\s+(\\w+)");

	@AfterEach
	void cleanUp() throws Exception {
		exec("DROP TRIGGER IF EXISTS cascade_test_branch_deadlock");
		exec("DELETE FROM attendance_devices WHERE company_id = " + COMPANY);
		exec("DELETE FROM department_branches WHERE branch_id = 992000");
		exec("DELETE FROM departments WHERE company_id = " + COMPANY);
		exec("DELETE FROM branches WHERE company_id = " + COMPANY);
		exec("DELETE FROM companies WHERE id = " + COMPANY);
	}

	@Test
	void everyTableTheCascadeDeletesFromIsCountedInItsOrder() throws Exception {
		seedCompany();
		List<String> statements = new ArrayList<>();

		new LegacyCompanyDelete(recording(statements), new LegacyMessages()).cascadeDelete(COMPANY, "en");

		Set<String> deletedFrom = new LinkedHashSet<>();
		for (String sql : statements) {
			Matcher target = DELETE_TARGET.matcher(sql);
			if (target.find()) {
				deletedFrom.add(target.group(1));
			}
		}
		deletedFrom.remove("companies");
		assertThat(deletedFrom).as("the recording saw the cascade run")
				.contains("notifications", "employees", "attendance_devices", "branches");

		List<String> counted = LegacyCompanyDelete.countedTables();
		assertThat(counted.stream().filter(deletedFrom::contains).toList())
				.as("a table the cascade deletes from but the page does not count is destroyed unannounced")
				.containsExactlyElementsOf(deletedFrom);

		Set<String> countedOnly = new HashSet<>(counted);
		countedOnly.removeAll(deletedFrom);
		countedOnly.retainAll(deployedTables());
		assertThat(countedOnly)
				.as("nothing is counted that the cascade does not delete (an absent optional table is skipped by both)")
				.isEmpty();
	}

	@Test
	void theCountsIncludeDeviceTablesAndRowsReachedThroughAParent() throws Exception {
		seedCompany();

		assertThat(service().clearedTables(COMPANY)).containsExactly(
				new ClearedTable("attendance_devices", 1),
				new ClearedTable("department_branches", 1),
				new ClearedTable("departments", 1),
				new ClearedTable("branches", 1));
	}

	@Test
	void aLockFailureInAToleratedDeleteAbortsTheCascade() throws Exception {
		seedCompany();
		// Deleting branches is a failure legacy tolerates. SQLSTATE 40001 with error
		// 1213 is what InnoDB reports for a deadlock, after rolling the whole
		// transaction back; swallowed, the statements after it would commit in a new one.
		exec("CREATE TRIGGER cascade_test_branch_deadlock BEFORE DELETE ON branches FOR EACH ROW"
				+ " SIGNAL SQLSTATE '40001' SET MESSAGE_TEXT = 'forced deadlock', MYSQL_ERRNO = 1213");

		assertThatThrownBy(() -> service().cascadeDelete(COMPANY, "en"))
				.isInstanceOf(PessimisticLockingFailureException.class);
		assertThat(count("SELECT COUNT(*) FROM companies WHERE id = " + COMPANY))
				.as("the company is still there")
				.isOne();
	}

	private static void seedCompany() throws Exception {
		exec("INSERT INTO companies (id, company_name, phone, password_hash)"
				+ " VALUES (" + COMPANY + ", 'cascade co', '01000000992', 'x')");
		exec("INSERT INTO branches (id, company_id, name) VALUES (992000, " + COMPANY + ", 'cascade branch')");
		exec("INSERT INTO departments (id, company_id, name) VALUES (992010, " + COMPANY + ", 'cascade department')");
		exec("INSERT INTO department_branches (department_id, branch_id) VALUES (992010, 992000)");
		exec("INSERT INTO attendance_devices"
				+ " (id, company_id, branch_id, vendor, serial_number, name, device_time_zone, is_active,"
				+ " created_at, updated_at)"
				+ " VALUES (992001, " + COMPANY + ", 992000, 'zkteco', 'CASCADE-SERIAL-1',"
				+ " 'cascade device', 'Africa/Cairo', 1, NOW(), NOW())");
	}

	private static LegacyCompanyDelete service() {
		return new LegacyCompanyDelete(new DriverManagerDataSource(
				MARIADB.getJdbcUrl(), MARIADB.getUsername(), MARIADB.getPassword()), new LegacyMessages());
	}

	/** The legacy database, recording the text of every statement prepared on it. */
	private static DataSource recording(List<String> statements) {
		return new DelegatingDataSource(new DriverManagerDataSource(
				MARIADB.getJdbcUrl(), MARIADB.getUsername(), MARIADB.getPassword())) {
			@Override
			public Connection getConnection() throws SQLException {
				Connection connection = super.getConnection();
				return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
						new Class<?>[] {Connection.class}, (proxy, method, args) -> {
							if (method.getName().startsWith("prepare") && args != null
									&& args.length > 0 && args[0] instanceof String sql) {
								statements.add(sql);
							}
							try {
								return method.invoke(connection, args);
							} catch (InvocationTargetException e) {
								throw e.getCause();
							}
						});
			}
		};
	}

	private static Set<String> deployedTables() throws Exception {
		Set<String> tables = new HashSet<>();
		try (Connection c = connect(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(
				"SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE()")) {
			while (rs.next()) {
				tables.add(rs.getString(1));
			}
		}
		return tables;
	}

	private static void exec(String sql) throws Exception {
		try (Connection c = connect(); Statement s = c.createStatement()) {
			s.execute(sql);
		}
	}

	private static int count(String sql) throws Exception {
		try (Connection c = connect(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
			return rs.next() ? rs.getInt(1) : -1;
		}
	}
}

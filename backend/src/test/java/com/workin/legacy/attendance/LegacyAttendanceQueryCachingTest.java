package com.workin.legacy.attendance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import com.workin.legacy.AbstractLegacyMySqlTest;
import com.workin.legacy.attendance.calendar.LegacyAttendanceCalendar;

/**
 * Regression for the N+1 query pattern {@code payslips/list.php}'s
 * enrichment loop drives through {@link LegacyWeeklyOffDays#forCompany},
 * {@link LegacyAttendanceCalendar#shiftForEmployeeOnDate}, and {@link
 * LegacyAttendanceCalendar#holidaysByDate} -- all three now request-scoped
 * and memoized (see each class's javadoc; the third was added after a PR
 * #120 review found it missing from the first two's fix). PHP ran one
 * process per request against a short-lived connection; this holds a
 * pooled connection and a servlet thread for the whole enrichment loop, so
 * a handful of concurrent {@code list.php} calls without this cache could
 * exhaust the pool.
 *
 * <p>Proven with a connection budget rather than a call counter: each
 * instance is wrapped around a {@link DataSource} that allows only as many
 * real connections as there are genuinely distinct cache keys, then wired
 * directly (bypassing Spring's request scope entirely -- the memoization is
 * plain instance state, and {@code new LegacyWeeklyOffDays(dataSource)} is
 * the same direct-construction style {@code LegacyPhoneCountriesMariaDbTest}
 * already uses for the same reason). A repeated key that is not actually
 * cached exhausts the budget and throws; a genuinely new key is expected to
 * consume one.
 */
class LegacyAttendanceQueryCachingTest extends AbstractLegacyMySqlTest {

	private static final long COMPANY_A = 20901L;
	private static final long COMPANY_B = 20902L;
	private static final long BRANCH = 20911L;
	private static final long EMPLOYEE = 209011L;
	private static final long SHIFT = 209021L;
	private static final String SHIFT_EFFECTIVE_FROM = "2025-06-01";

	private static DataSource dataSource;

	@BeforeAll
	static void prepare() throws Exception {
		dataSource = dataSourceFor(MARIADB.getDatabaseName());
		seed();
	}

	@Test
	void forCompanyIsMemoizedPerCompanyNotGloballyOrPerCall() throws Exception {
		// Budget of 2: exactly the number of genuinely distinct companies queried below.
		LegacyWeeklyOffDays weeklyOffDays = new LegacyWeeklyOffDays(new FailAfterNConnectionsDataSource(dataSource, 2));

		assertThat(weeklyOffDays.forCompany(COMPANY_A)).containsExactly("friday");
		// Two more calls for the SAME company: served from cache, no new connection.
		assertThat(weeklyOffDays.forCompany(COMPANY_A)).containsExactly("friday");
		assertThat(weeklyOffDays.forCompany(COMPANY_A)).containsExactly("friday");

		// A different company is a genuine cache miss -- consumes the second and last allowed connection.
		assertThat(weeklyOffDays.forCompany(COMPANY_B)).containsExactly("saturday");
		assertThat(weeklyOffDays.forCompany(COMPANY_B)).containsExactly("saturday");
		// Company A's entry must still be cached after a different key was queried in between.
		assertThat(weeklyOffDays.forCompany(COMPANY_A)).containsExactly("friday");

		// A third distinct key would need a third connection, which the budget refuses --
		// confirms the budget is actually being enforced, not vacuously permissive.
		LegacyWeeklyOffDays exhausted = new LegacyWeeklyOffDays(new FailAfterNConnectionsDataSource(dataSource, 1));
		exhausted.forCompany(COMPANY_A);
		assertThatThrownBy(() -> exhausted.forCompany(COMPANY_B)).isInstanceOf(RuntimeException.class);
	}

	@Test
	void shiftForEmployeeOnDateIsMemoizedPerEmployeeAndDateNotJustPerEmployee() throws Exception {
		String dateOne = "2025-06-15";
		String dateTwo = "2025-07-01";
		// Budget of 2: two distinct (employee, date) keys, even though both resolve to the same shift row.
		LegacyAttendanceCalendar calendar =
				new LegacyAttendanceCalendar(new FailAfterNConnectionsDataSource(dataSource, 2), new LegacyWeeklyOffDays(dataSource));

		Map<String, Object> first = calendar.shiftForEmployeeOnDate(EMPLOYEE, dateOne);
		assertThat(first).containsEntry("name", "Cache Shift");
		// Repeated calls for the SAME (employee, date): cached, no new connection.
		assertThat(calendar.shiftForEmployeeOnDate(EMPLOYEE, dateOne)).containsEntry("name", "Cache Shift");
		assertThat(calendar.shiftForEmployeeOnDate(EMPLOYEE, dateOne)).containsEntry("name", "Cache Shift");

		// A different date is a genuinely different cache key -- consumes the second and last connection,
		// even though the query resolves to the same underlying shift assignment.
		assertThat(calendar.shiftForEmployeeOnDate(EMPLOYEE, dateTwo)).containsEntry("name", "Cache Shift");
		assertThat(calendar.shiftForEmployeeOnDate(EMPLOYEE, dateTwo)).containsEntry("name", "Cache Shift");
		assertThat(calendar.shiftForEmployeeOnDate(EMPLOYEE, dateOne)).containsEntry("name", "Cache Shift");
	}

	/**
	 * The gap Codex's PR #120 review found after D-113 shipped: {@code holidaysByDate} was not
	 * memoized, so {@code expectedForDay}'s single-day {@code (companyId, date, date)} call --
	 * reached once per day per payslip in {@code payslips/list.php}'s enrichment loop, same as
	 * the other two caches -- ran a fresh query per call even when the exact same range repeats
	 * across employees on one page.
	 */
	@Test
	void holidaysByDateIsMemoizedPerCompanyAndRangeNotJustPerCompany() throws Exception {
		// Budget of 2: two distinct (company, from, to) keys below.
		LegacyAttendanceCalendar calendar = new LegacyAttendanceCalendar(
				new FailAfterNConnectionsDataSource(dataSource, 2), new LegacyWeeklyOffDays(dataSource));

		Map<String, String> first = calendar.holidaysByDate(COMPANY_A, "2025-06-15", "2025-06-15");
		assertThat(first).containsEntry("2025-06-15", "Cache Holiday");
		// Repeated calls for the SAME (company, from, to): cached, no new connection.
		assertThat(calendar.holidaysByDate(COMPANY_A, "2025-06-15", "2025-06-15"))
				.containsEntry("2025-06-15", "Cache Holiday");
		assertThat(calendar.holidaysByDate(COMPANY_A, "2025-06-15", "2025-06-15"))
				.containsEntry("2025-06-15", "Cache Holiday");

		// A different range is a genuine cache miss -- consumes the second and last allowed connection.
		assertThat(calendar.holidaysByDate(COMPANY_A, "2025-07-01", "2025-07-01")).isEmpty();
		assertThat(calendar.holidaysByDate(COMPANY_A, "2025-07-01", "2025-07-01")).isEmpty();
		// The first range's entry must still be cached after a different key was queried in between.
		assertThat(calendar.holidaysByDate(COMPANY_A, "2025-06-15", "2025-06-15"))
				.containsEntry("2025-06-15", "Cache Holiday");

		// A third distinct key would need a third connection, which the budget refuses.
		LegacyAttendanceCalendar exhausted = new LegacyAttendanceCalendar(
				new FailAfterNConnectionsDataSource(dataSource, 1), new LegacyWeeklyOffDays(dataSource));
		exhausted.holidaysByDate(COMPANY_A, "2025-06-15", "2025-06-15");
		assertThatThrownBy(() -> exhausted.holidaysByDate(COMPANY_B, "2025-06-15", "2025-06-15"))
				.isInstanceOf(RuntimeException.class);
	}

	@Test
	void shiftForEmployeeOnDateAlsoMemoizesTheNoAssignmentYetCase() throws Exception {
		String beforeAssignment = "2025-01-01";
		// Budget of 1: the no-assignment-yet result (null) must be cached too, or this throws on the
		// second call -- containsKey rather than computeIfAbsent is what this proves.
		LegacyAttendanceCalendar calendar =
				new LegacyAttendanceCalendar(new FailAfterNConnectionsDataSource(dataSource, 1), new LegacyWeeklyOffDays(dataSource));

		assertThat(calendar.shiftForEmployeeOnDate(EMPLOYEE, beforeAssignment)).isNull();
		assertThat(calendar.shiftForEmployeeOnDate(EMPLOYEE, beforeAssignment)).isNull();
		assertThat(calendar.shiftForEmployeeOnDate(EMPLOYEE, beforeAssignment)).isNull();
	}

	/** Allows exactly {@code allowed} real connections, then fails -- a cache miss where a hit was expected. */
	private static final class FailAfterNConnectionsDataSource extends AbstractDataSource {

		private final DataSource delegate;
		private final AtomicInteger remaining;

		FailAfterNConnectionsDataSource(DataSource delegate, int allowed) {
			this.delegate = delegate;
			this.remaining = new AtomicInteger(allowed);
		}

		@Override
		public Connection getConnection() throws SQLException {
			if (remaining.getAndDecrement() <= 0) {
				throw new SQLException("connection budget exhausted -- expected a cache hit here");
			}
			return delegate.getConnection();
		}

		@Override
		public Connection getConnection(String username, String password) throws SQLException {
			return getConnection();
		}
	}

	private static DataSource dataSourceFor(String database) {
		String url = MARIADB.getJdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/" + database + "$1");
		DriverManagerDataSource source = new DriverManagerDataSource(url, MARIADB.getUsername(), MARIADB.getPassword());
		source.setDriverClassName("org.mariadb.jdbc.Driver");
		return source;
	}

	private static void seed() throws Exception {
		try (Connection connection = connect(); Statement st = connection.createStatement()) {
			st.execute("SET SESSION sql_mode = ''");
			st.execute("""
					INSERT INTO companies (id, company_name, phone, status, created_at) VALUES
					  (20901, 'Cache Co A', '+201000020901', 'active', '2025-01-15 09:00:00'),
					  (20902, 'Cache Co B', '+201000020902', 'active', '2025-01-15 09:00:00')
					""");
			st.execute("""
					INSERT INTO branches (id, company_id, name, is_active, created_at) VALUES
					  (20911, 20901, 'Cache HQ', 1, '2025-03-01 10:00:00')
					""");
			st.execute("""
					INSERT INTO employees
					  (id, company_id, branch_id, employee_code, first_name, last_name, phone, role,
					   is_active, created_at)
					VALUES
					  (209011, 20901, 20911, '209011', 'Cache', 'Employee', '+201100209011', 'employee',
					   1, '2025-04-01 08:00:00')
					""");
			st.execute("""
					INSERT INTO shifts (id, company_id, name, start_time, end_time, days_off, is_active, created_at)
					VALUES (209021, 20901, 'Cache Shift', '09:00:00', '17:00:00', 'friday', 1, '2025-01-01 08:00:00')
					""");
			st.execute("""
					INSERT INTO employee_shift_assignments (id, employee_id, shift_id, effective_from, created_at)
					VALUES (209031, 209011, 209021, '%s', '2025-01-01 08:00:00')
					""".formatted(SHIFT_EFFECTIVE_FROM));

			// weekly_off_days: one shared setting_definitions row, one company_settings row per
			// company, each pointing at its own chosen setting_allowed_values row.
			st.execute("""
					INSERT INTO setting_definitions (id, setting_key, is_multi) VALUES (20950, 'weekly_off_days', 1)
					""");
			st.execute("""
					INSERT INTO company_settings (id, company_id, setting_definition_id) VALUES
					  (20951, 20901, 20950), (20952, 20902, 20950)
					""");
			st.execute("""
					INSERT INTO setting_allowed_values (id, setting_definition_id, value, sort_order) VALUES
					  (20953, 20950, 'friday', 0), (20954, 20950, 'saturday', 0)
					""");
			st.execute("""
					INSERT INTO company_setting_values (company_setting_id, setting_allowed_value_id) VALUES
					  (20951, 20953), (20952, 20954)
					""");
			st.execute("""
					INSERT INTO company_official_holidays (id, company_id, name, holiday_date, created_at)
					VALUES (20961, 20901, 'Cache Holiday', '2025-06-15', '2025-01-01 08:00:00')
					""");
		}
	}
}

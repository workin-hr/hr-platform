package com.workin.backend.platformadmin.hr;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import com.workin.legacy.AbstractLegacyMySqlTest;
import com.workin.legacy.LegacyClock;
import com.workin.legacy.attendance.LegacyWeeklyOffDays;
import com.workin.legacy.attendance.calendar.LegacyAttendanceCalendar;
import com.workin.legacy.attendance.calendar.LegacyAttendanceWorkedMinutes;
import com.workin.legacy.attendance.calendar.LegacyWeeklyRestCredit;
import com.workin.legacy.attendance.session.LegacyAttendanceSessions;
import com.workin.legacy.payroll.LegacyPayrollAttendanceFigures;

/**
 * The page's batched holiday credit is the same number the per-employee query
 * returns, for each employee on the page.
 *
 * <p>The aggregate view used to ask once per row; it now asks once per page.
 * The statement count is pinned by {@code AttendanceAggregateQueryBudgetTest},
 * which would happily pass against a batch that counted the wrong thing -- so
 * the number itself is asserted here, against the single-employee method the
 * batch replaces rather than against a figure written down by hand.
 *
 * <p>The cases are the ones the correlated {@code NOT EXISTS} decides: a
 * holiday the employee attended earns no credit, one they did not earns it, an
 * employee with no attendance at all earns every holiday in range, and a
 * holiday outside the range earns nothing.
 */
class AttendanceHolidayCreditBatchTest extends AbstractLegacyMySqlTest {

	private static final long COMPANY = 99770;
	private static final long ATTENDED_ONE = 997701;
	private static final long ATTENDED_NONE = 997702;
	private static final long ATTENDED_BOTH = 997703;

	private static final String FROM = "2026-03-01";
	private static final String TO = "2026-03-31";

	private AttendanceStore store;

	@BeforeEach
	void seed() throws Exception {
		seedAsLegacyWould(
				"DELETE FROM attendance WHERE employee_id BETWEEN 997700 AND 997799",
				"DELETE FROM employees WHERE id BETWEEN 997700 AND 997799",
				"DELETE FROM company_official_holidays WHERE company_id = " + COMPANY,
				"DELETE FROM branches WHERE id BETWEEN 997700 AND 997799",
				"DELETE FROM companies WHERE id = " + COMPANY,
				"INSERT INTO companies (id, company_name, phone, status, created_at) VALUES"
						+ " (" + COMPANY + ", 'Credit Co', '+201000997700', 'active', '2019-01-15 09:00:00')",
				"INSERT INTO branches (id, company_id, name, is_active, created_at) VALUES"
						+ " (997701, " + COMPANY + ", 'Credit HQ', 1, '2019-03-01 10:00:00')",
				// Two holidays inside the range, one outside it.
				"INSERT INTO company_official_holidays (company_id, holiday_date, name, created_at)"
						+ " VALUES (" + COMPANY + ", '2026-03-09', 'Spring', '2026-01-01 00:00:00'),"
						+ " (" + COMPANY + ", '2026-03-10', 'Second', '2026-01-01 00:00:00'),"
						+ " (" + COMPANY + ", '2026-04-05', 'Outside', '2026-01-01 00:00:00')",
				employee(ATTENDED_ONE), employee(ATTENDED_NONE), employee(ATTENDED_BOTH),
				// ATTENDED_ONE worked the 9th; ATTENDED_BOTH worked both; and
				// everyone has a non-holiday punch, so "has attendance at all"
				// is not what the count keys on.
				punch(ATTENDED_ONE, "2026-03-09"),
				punch(ATTENDED_BOTH, "2026-03-09"),
				punch(ATTENDED_BOTH, "2026-03-10"),
				punch(ATTENDED_ONE, "2026-03-02"),
				punch(ATTENDED_NONE, "2026-03-02"),
				punch(ATTENDED_BOTH, "2026-03-02"),
				// A punch on the holiday outside the range, which must not count.
				punch(ATTENDED_NONE, "2026-04-05"));

		DataSource dataSource = new DriverManagerDataSource(
				MARIADB.getJdbcUrl(), MARIADB.getUsername(), MARIADB.getPassword());
		LegacyClock clock = new LegacyClock(dataSource);
		LegacyWeeklyOffDays weeklyOffDays = new LegacyWeeklyOffDays(dataSource);
		LegacyAttendanceCalendar calendar = new LegacyAttendanceCalendar(dataSource, weeklyOffDays);
		LegacyWeeklyRestCredit weeklyRestCredit = new LegacyWeeklyRestCredit(dataSource, calendar);
		LegacyAttendanceSessions sessions = new LegacyAttendanceSessions(dataSource, calendar, clock);
		LegacyAttendanceWorkedMinutes worked =
				new LegacyAttendanceWorkedMinutes(dataSource, calendar, sessions, clock);
		LegacyPayrollAttendanceFigures figures = new LegacyPayrollAttendanceFigures(
				dataSource, calendar, weeklyRestCredit, worked, weeklyOffDays);
		this.store = new AttendanceStore(
				new JdbcTemplate(dataSource), worked, figures, weeklyRestCredit, calendar, clock);
	}

	@Test
	void theBatchedCreditMatchesThePerEmployeeQueryForEveryEmployeeOnThePage() {
		List<Long> page = List.of(ATTENDED_ONE, ATTENDED_NONE, ATTENDED_BOTH);
		Map<Long, Integer> batched = this.store.officialHolidayCreditForEmployees(COMPANY, page, FROM, TO);

		for (long employee : page) {
			assertThat(batched.getOrDefault(employee, 0))
					.as("employee %s", employee)
					.isEqualTo(this.store.officialHolidayCreditForEmployee(COMPANY, employee, FROM, TO));
		}
	}

	@Test
	void theNumbersAreTheOnesTheNotExistsDecides() {
		Map<Long, Integer> batched = this.store.officialHolidayCreditForEmployees(
				COMPANY, List.of(ATTENDED_ONE, ATTENDED_NONE, ATTENDED_BOTH), FROM, TO);

		assertThat(batched.get(ATTENDED_NONE))
				.as("both holidays in range, neither attended")
				.isEqualTo(2);
		assertThat(batched.get(ATTENDED_ONE))
				.as("attended one of the two, so one is still credited")
				.isEqualTo(1);
		assertThat(batched.get(ATTENDED_BOTH))
				.as("attended both, so nothing is credited -- and the punch on the holiday "
						+ "outside the range changes nobody's number")
				.isZero();
	}

	@Test
	void anEmployeeOutsideThePageIsNotCountedAndAnEmptyPageAsksNothing() {
		assertThat(this.store.officialHolidayCreditForEmployees(COMPANY, List.of(ATTENDED_NONE), FROM, TO))
				.as("only the employees asked for come back")
				.containsOnlyKeys(ATTENDED_NONE);
		assertThat(this.store.officialHolidayCreditForEmployees(COMPANY, List.of(), FROM, TO)).isEmpty();
		assertThat(this.store.officialHolidayCreditForEmployees(0L, List.of(ATTENDED_NONE), FROM, TO))
				.as("no company is no credit, as the single-employee method answers too")
				.isEmpty();
	}

	private static String employee(long id) {
		return "INSERT INTO employees (id, company_id, branch_id, employee_code, first_name,"
				+ " last_name, phone, role, is_active, created_at) VALUES (" + id + ", " + COMPANY
				+ ", 997701, '" + id + "', 'Credit', 'One', '+2010" + id + "', 'employee', 1,"
				+ " '2019-04-01 08:00:00')";
	}

	private static String punch(long employeeId, String date) {
		return "INSERT INTO attendance (employee_id, check_in, check_out, created_at) VALUES ("
				+ employeeId + ", '" + date + " 09:00:00', '" + date + " 17:00:00', '"
				+ date + " 17:00:00')";
	}
}

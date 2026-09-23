package com.workin.backend.perf;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import com.workin.backend.platformadmin.hr.AttendanceRecord;
import com.workin.backend.platformadmin.hr.AttendanceStore;
import com.workin.backend.platformadmin.web.DashboardListFilters;
import com.workin.legacy.AbstractLegacyMySqlTest;
import com.workin.legacy.LegacyClock;
import com.workin.legacy.attendance.calendar.LegacyAttendanceCalendar;
import com.workin.legacy.attendance.session.LegacyAttendanceSessions;
import com.workin.legacy.attendance.calendar.LegacyAttendanceWorkedMinutes;
import com.workin.legacy.attendance.LegacyWeeklyOffDays;
import com.workin.legacy.attendance.calendar.LegacyWeeklyRestCredit;
import com.workin.legacy.payroll.LegacyPayrollAttendanceFigures;

/**
 * What the attendance page's aggregate view costs in round trips.
 *
 * <p>The figures on that page are derived per row, and the derivation asks the
 * database per employee <em>and per day of the reporting period</em>. Against
 * the remote stack, 106 ms away, a count that grows with rows times days is not
 * a slow page, it is a page that never finishes: the statement count is the
 * page's wall time, which is why it is asserted here rather than described.
 *
 * <p>The budget is a ratchet on two axes, and both matter. Holding rows fixed
 * and doubling the period must not move the count, and holding the period fixed
 * and adding rows must not multiply it -- either one alone can be satisfied by
 * an accident.
 *
 * <h2>Each measurement builds the collaborators again, and that is the point</h2>
 * <p>{@link LegacyAttendanceCalendar} and {@link LegacyWeeklyOffDays} are
 * {@code @RequestScope} beans: in production every request gets its own
 * instance and therefore an empty cache. A harness that wires them once and
 * measures twice reports the second request's warm cost -- 17 statements where
 * a real request pays 87 -- and would ratchet the wrong number. So the wiring
 * is per measurement, which is what a cold request is.
 */
class AttendanceAggregateQueryBudgetTest extends AbstractLegacyMySqlTest {

	private static final long COMPANY = 99750;
	private static final long BRANCH = 997501;
	private static final long SHIFT = 997502;
	private static final long[] EMPLOYEES = { 997511, 997512, 997513 };

	/** Hired mid-period: every date before this one has no shift at all. */
	private static final long LATE_STARTER = 997514;

	/** Every employee the unfiltered page returns: the three plus the late starter. */
	private static final int ALL_ROWS = EMPLOYEES.length + 1;

	/**
	 * A second company, so the rest-day measurement below cannot change what the
	 * two ratchets above count. Its shift takes Friday off, which is this
	 * market's ordinary configuration and the fixture above deliberately lacks.
	 */
	private static final long REST_COMPANY = 99751;

	private static final long REST_BRANCH = 997521;

	private static final long REST_SHIFT = 997522;

	private static final long REST_EMPLOYEE = 997523;

	private final QueryCounter counter = new QueryCounter();

	@BeforeEach
	void seed() throws Exception {
		List<String> statements = new java.util.ArrayList<>(List.of(
				"DELETE FROM attendance WHERE employee_id BETWEEN 997500 AND 997599",
				"DELETE FROM employee_shift_assignments WHERE employee_id BETWEEN 997500 AND 997599",
				"DELETE FROM employees WHERE id BETWEEN 997500 AND 997599",
				"DELETE FROM shifts WHERE id BETWEEN 997500 AND 997599",
				"DELETE FROM company_official_holidays WHERE company_id = " + COMPANY,
				"DELETE FROM companies WHERE id = " + REST_COMPANY,
				"DELETE FROM branches WHERE id BETWEEN 997500 AND 997599",
				"DELETE FROM companies WHERE id = " + COMPANY,
				"INSERT INTO companies (id, company_name, phone, status, created_at) VALUES"
						+ " (" + COMPANY + ", 'Budget Co', '+201000997500', 'active', '2019-01-15 09:00:00')",
				"INSERT INTO branches (id, company_id, name, is_active, created_at) VALUES"
						+ " (" + BRANCH + ", " + COMPANY + ", 'Budget HQ', 1, '2019-03-01 10:00:00')",
				"INSERT INTO shifts (id, company_id, name, start_time, end_time, days_off, is_active,"
						+ " created_at) VALUES (" + SHIFT + ", " + COMPANY + ", 'Day', '09:00:00',"
						+ " '17:00:00', '', 1, '2019-05-01 08:00:00')",
				"INSERT INTO company_official_holidays (company_id, holiday_date, name, created_at)"
						+ " VALUES (" + COMPANY + ", '2026-03-09', 'Spring Day', '2026-01-01 00:00:00')"));

		for (long employee : EMPLOYEES) {
			statements.add("INSERT INTO employees (id, company_id, branch_id, employee_code,"
					+ " first_name, last_name, phone, role, is_active, expected_daily_hours, created_at)"
					+ " VALUES (" + employee + ", " + COMPANY + ", " + BRANCH + ", '" + employee + "',"
					+ " 'Budget', 'Worker', '+2010" + employee + "', 'employee', 1, 8,"
					+ " '2019-04-01 08:00:00')");
			statements.add("INSERT INTO employee_shift_assignments (employee_id, shift_id,"
					+ " effective_from) VALUES (" + employee + ", " + SHIFT + ", '2019-05-01')");
			// Two punched days each, inside the shorter of the two periods.
			for (String day : List.of("2026-03-02", "2026-03-03")) {
				statements.add("INSERT INTO attendance (employee_id, check_in, check_out, created_at)"
						+ " VALUES (" + employee + ", '" + day + " 09:00:00', '" + day + " 17:00:00',"
						+ " '" + day + " 17:00:00')");
			}
		}
		statements.add("INSERT INTO employees (id, company_id, branch_id, employee_code, first_name,"
				+ " last_name, phone, role, is_active, expected_daily_hours, created_at) VALUES ("
				+ LATE_STARTER + ", " + COMPANY + ", " + BRANCH + ", '" + LATE_STARTER + "',"
				+ " 'Late', 'Starter', '+2010" + LATE_STARTER + "', 'employee', 1, 8,"
				+ " '2019-04-01 08:00:00')");
		statements.add("INSERT INTO employee_shift_assignments (employee_id, shift_id,"
				+ " effective_from) VALUES (" + LATE_STARTER + ", " + SHIFT + ", '2026-03-20')");

		statements.addAll(List.of(
				"INSERT INTO companies (id, company_name, phone, status, created_at) VALUES ("
						+ REST_COMPANY + ", 'Rest Co', '+201000997501', 'active', '2019-01-15 09:00:00')",
				"INSERT INTO branches (id, company_id, name, is_active, created_at) VALUES ("
						+ REST_BRANCH + ", " + REST_COMPANY + ", 'Rest HQ', 1, '2019-03-01 10:00:00')",
				"INSERT INTO shifts (id, company_id, name, start_time, end_time, days_off, is_active,"
						+ " created_at) VALUES (" + REST_SHIFT + ", " + REST_COMPANY + ", 'Day',"
						+ " '09:00:00', '17:00:00', 'friday', 1, '2019-05-01 08:00:00')",
				"INSERT INTO employees (id, company_id, branch_id, employee_code, first_name,"
						+ " last_name, phone, role, is_active, expected_daily_hours, created_at) VALUES ("
						+ REST_EMPLOYEE + ", " + REST_COMPANY + ", " + REST_BRANCH + ", '" + REST_EMPLOYEE
						+ "', 'Rest', 'Worker', '+2010" + REST_EMPLOYEE + "', 'employee', 1, 8,"
						+ " '2019-04-01 08:00:00')",
				"INSERT INTO employee_shift_assignments (employee_id, shift_id, effective_from) VALUES ("
						+ REST_EMPLOYEE + ", " + REST_SHIFT + ", '2019-05-01')"));
		seedAsLegacyWould(statements.toArray(String[]::new));

	}

	/** One request's worth of collaborators: fresh caches, as {@code @RequestScope} gives them. */
	private AttendanceStore coldRequest() {
		DataSource counted = this.counter.wrap(new DriverManagerDataSource(
				MARIADB.getJdbcUrl(), MARIADB.getUsername(), MARIADB.getPassword()));
		LegacyClock clock = new LegacyClock(counted);
		LegacyWeeklyOffDays weeklyOffDays = new LegacyWeeklyOffDays(counted);
		LegacyAttendanceCalendar calendar = new LegacyAttendanceCalendar(counted, weeklyOffDays);
		LegacyWeeklyRestCredit weeklyRestCredit = new LegacyWeeklyRestCredit(counted, calendar);
		LegacyAttendanceSessions sessions = new LegacyAttendanceSessions(counted, calendar, clock);
		LegacyAttendanceWorkedMinutes worked =
				new LegacyAttendanceWorkedMinutes(counted, calendar, sessions, clock);
		LegacyPayrollAttendanceFigures figures = new LegacyPayrollAttendanceFigures(
				counted, calendar, weeklyRestCredit, worked, weeklyOffDays);
		return new AttendanceStore(
				new JdbcTemplate(counted), worked, figures, weeklyRestCredit, calendar, clock);
	}

	/**
	 * The one that matters: before the page pre-warmed its shifts, a month cost
	 * 109 statements against a week's 40 for the same three rows, because the
	 * shift was resolved per employee per day. Ten rows over a month was 354.
	 */
	@Test
	void theAggregateCostsTheSameWhetherThePeriodIsAWeekOrAMonth() {
		int week = measure("2026-03-01", "2026-03-07", ALL_ROWS);
		int month = measure("2026-03-01", "2026-03-31", ALL_ROWS);

		System.out.println("[budget] " + ALL_ROWS + " rows over 7 days: " + week + " statements");
		System.out.println("[budget] " + ALL_ROWS + " rows over 31 days: " + month + " statements");
		assertThat(month)
				.as("a month is 4.4x the days of a week; the count must not follow the days")
				.isEqualTo(week);
	}

	@Test
	void theAggregateDoesNotMultiplyTheCountByTheNumberOfRows() {
		int oneRow = measureForEmployee(EMPLOYEES[0], "2026-03-01", "2026-03-31");
		int allRows = measure("2026-03-01", "2026-03-31", ALL_ROWS);

		System.out.println("[budget] 1 row over 31 days: " + oneRow + " statements");
		System.out.println("[budget] " + ALL_ROWS + " rows over 31 days: " + allRows + " statements");
		assertThat(allRows - oneRow)
				.as("three more rows cost the four per-row lookups summarise still makes one at a "
						+ "time -- approved leave days, the employee's work hours, the attendance "
						+ "flags in range, and expectedWorkDays' own holiday read. Batching those "
						+ "means batched variants inside the payroll figures classes, which is its "
						+ "own change; what this pins is that the number is per row and not per row "
						+ "per day")
				.isLessThanOrEqualTo(12);
	}

	private int measure(String from, String to, int expectedRows) {
		DashboardListFilters filters =
				new DashboardListFilters(COMPANY, "", "all", 0L, 0L, 1, 10, false);
		AttendanceStore store = coldRequest();
		java.util.concurrent.atomic.AtomicInteger rows = new java.util.concurrent.atomic.AtomicInteger();
		List<String> issued = this.counter.measure(() -> {
			var page = store.aggregate(filters, from, to, 1, COMPANY, to);
			rows.set(page.data().size());
		});
		assertThat(rows.get()).as("the page under measurement returned its rows").isEqualTo(expectedRows);
		return issued.size();
	}

	private int measureForEmployee(long employeeId, String from, String to) {
		return measureForEmployee(COMPANY, employeeId, from, to);
	}

	private int measureForEmployee(long companyId, long employeeId, String from, String to) {
		DashboardListFilters filters = new DashboardListFilters(
				companyId, String.valueOf(employeeId), "all", 0L, 0L, 1, 10, false);
		AttendanceStore store = coldRequest();
		List<String> issued = this.counter.measure(
				() -> store.aggregate(filters, from, to, 1, companyId, to));
		return issued.size();
	}

	/**
	 * The pre-warm has to cache the answer "no shift on this date" too.
	 *
	 * <p>A date before an employee's first assignment is one the per-date query
	 * answers with no row at all. If the warm skips those instead of caching the
	 * {@code null}, every one of them falls back to a query -- correct, and
	 * quietly back to one statement per employee per day for exactly the rows a
	 * report most often contains, someone hired part-way through the period. The
	 * answers are unaffected either way, so only the count can see this: a
	 * mutant that cached only the non-null shifts passed every other test here.
	 */
	@Test
	void anEmployeeHiredMidPeriodCostsNoMoreThanOneAssignedLongAgo() {
		int assignedLongAgo = measureForEmployee(EMPLOYEES[0], "2026-03-01", "2026-03-31");
		int hiredMidPeriod = measureForEmployee(LATE_STARTER, "2026-03-01", "2026-03-31");

		System.out.println("[budget] 1 row assigned in 2019: " + assignedLongAgo + " statements");
		System.out.println("[budget] 1 row assigned on the 20th: " + hiredMidPeriod + " statements");
		assertThat(hiredMidPeriod)
				.as("the 19 dates before the assignment are answered from the warm, not re-asked")
				.isEqualTo(assignedLongAgo);
	}

	/**
	 * What a company with a weekly rest day pays, which is <b>not</b> what the
	 * two ratchets above measure.
	 *
	 * <p>The fixture they use has {@code days_off = ''} and no
	 * {@code WEEKLY_OFF_DAYS}, so it has no rest dates at all -- and the whole
	 * remaining per-row cost is {@code LegacyWeeklyRestCredit}'s, which asks
	 * {@code isOnApprovedLeave} once per preceding workday per rest date. With
	 * Friday off, one row over a month measured <b>34</b> statements against a
	 * week's <b>16</b>: the count still follows the period here, and this change
	 * does not fix that -- it removes the shift term only, which is why the
	 * shift query is absent from all of these.
	 *
	 * <p>Asserted rather than printed, so the next reader cannot take 22 for the
	 * whole truth, and so batching the rest credit has a number to beat.
	 */
	@Test
	void aCompanyWithAWeeklyRestDayStillPaysForEveryRestDateInThePeriod() {
		int week = measureForEmployee(REST_COMPANY, REST_EMPLOYEE, "2026-03-01", "2026-03-07");
		int month = measureForEmployee(REST_COMPANY, REST_EMPLOYEE, "2026-03-01", "2026-03-31");

		System.out.println("[budget] rest-day row over 7 days: " + week + " statements");
		System.out.println("[budget] rest-day row over 31 days: " + month + " statements");
		assertThat(month)
				.as("the rest-credit term is what is left, and it grows with the period")
				.isGreaterThan(week);
		assertThat(month)
				.as("a ratchet on the number batching the rest credit has to beat")
				.isLessThanOrEqualTo(34);
	}

	/** Kept so a compile error names the type if the row shape moves. */
	@SuppressWarnings("unused")
	private AttendanceRecord.AggregateRow shape;
}

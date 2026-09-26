package com.workin.legacy.attendance.calendar;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import com.workin.backend.perf.QueryCounter;
import com.workin.legacy.AbstractLegacyMySqlTest;
import com.workin.legacy.LegacyClock;
import com.workin.legacy.attendance.LegacyWeeklyOffDays;
import com.workin.legacy.attendance.session.LegacyAttendanceSessions;

/**
 * What one request can make the per-day warms hold (D-292, review round 1).
 *
 * <p>{@code stats.php} applies no range cap: {@code date_from=0001-01-01&date_to=9999-12-31}
 * is a request it answers. Warming every day of that range for its employee
 * held about 682 MB live under a 768 MB heap -- one authenticated request away
 * from {@code ExitOnOutOfMemoryError} stopping the container for every tenant.
 * The period stats never read a date after today, so their warm stops there;
 * and every warm is bounded in employee-days ({@link LegacyWarmedDays#MAX_SLOTS}),
 * so a range no roster needs is declined and its dates go to the per-date
 * statements. Review round 2: the bound is in employee-days rather than days
 * because a days-only bound turned the dashboard aggregate's longer ranges
 * back into per-date reads.
 */
class LegacyAttendanceWarmBoundsTest extends AbstractLegacyMySqlTest {

	private static final long COMPANY = 356901L;
	private static final long EMPLOYEE = 3569011L;
	private static final LocalDate TODAY = LocalDate.parse("2026-03-18");

	private final QueryCounter counter = new QueryCounter();

	@BeforeAll
	static void seed() throws Exception {
		seedAsLegacyWould(
				"INSERT INTO companies (id, company_name, phone, password_hash, status) VALUES (" + COMPANY
						+ ", 'Bounds Co', '+201000356901', 'x', 'active')",
				"INSERT INTO branches (id, company_id, name) VALUES (3569010, " + COMPANY + ", 'HQ')",
				"INSERT INTO shifts (id, company_id, name, start_time, end_time, days_off) VALUES (3569012, "
						+ COMPANY + ", 'Day', '09:00:00', '17:00:00', 'friday')",
				"INSERT INTO employees (id, company_id, branch_id, first_name, phone) VALUES (" + EMPLOYEE + ", "
						+ COMPANY + ", 3569010, 'Bounds', '+2019356901')",
				"INSERT INTO employee_shift_assignments (employee_id, shift_id, effective_from) VALUES ("
						+ EMPLOYEE + ", 3569012, '2025-01-01')",
				"INSERT INTO attendance (employee_id, check_in, check_out) VALUES (" + EMPLOYEE
						+ ", '2026-03-02 09:00:00', '2026-03-02 17:00:00')");
	}

	@Test
	void aRangeNoRosterNeedsIsNotWarmed() {
		LegacyAttendanceCalendar calendar = calendar(counter.wrap(dataSource()));
		List<String> issued = counter.measure(
				() -> calendar.warmReportRange(COMPANY, List.of(EMPLOYEE), "0001-01-01", "9999-12-31"));

		assertThat(calendar.warmedSlotCount()).as("no per-day slot for a range no report can ask").isZero();
		assertThat(issued).as("and no statement read for it").isEmpty();
	}

	@Test
	void aReportsWidestSpanIsStillWarmed() {
		LegacyAttendanceCalendar calendar = calendar(dataSource());
		calendar.warmReportRange(COMPANY, List.of(EMPLOYEE), "2025-01-01", "2026-01-01");

		assertThat(calendar.warmedSlotCount())
				.as("366 days plus the 14 before and 8 after for shifts and timed requests, and the 14 before"
						+ " for leave, which nothing reads past the last date")
				.isEqualTo(2L * (366 + LegacyAttendanceCalendar.REPORT_LOOKBACK_DAYS
						+ LegacyAttendanceCalendar.REPORT_LOOKAHEAD_DAYS)
						+ (366 + LegacyAttendanceCalendar.REPORT_LOOKBACK_DAYS));
	}

	/**
	 * Round 2's regression, at the slot level: two years for the dashboard's
	 * largest page -- 200 employees -- is warmed, as it was before D-292.
	 */
	@Test
	void theDashboardsLargestPageOverTwoYearsIsStillWarmed() {
		LegacyAttendanceCalendar calendar = calendar(dataSource());
		List<Long> page = java.util.stream.LongStream.rangeClosed(1, 200).boxed().toList();
		calendar.warmShiftsForEmployees(page, "2024-03-18", "2026-03-31");
		calendar.warmApprovedLeaveForEmployees(page, "2024-03-18", "2026-03-31");

		assertThat(calendar.warmedSlotCount()).isEqualTo(2L * 200 * 744);
	}

	/**
	 * The employee-day half of the budget: the dashboard's largest page from
	 * 0001-01-01 is about 740,000 days -- under the budget as a span, but 148
	 * million slots per kind across 200 employees, some 590 MB each for shifts
	 * and leave. Neither warm is made, and neither issues its statement.
	 */
	@Test
	void theDashboardsLargestPageFromTheYearOneIsNotWarmed() {
		LegacyAttendanceCalendar calendar = calendar(counter.wrap(dataSource()));
		List<Long> page = java.util.stream.LongStream.rangeClosed(1, 200).boxed().toList();
		List<String> issued = counter.measure(() -> {
			calendar.warmShiftsForEmployees(page, "0001-01-01", "2026-03-31");
			calendar.warmApprovedLeaveForEmployees(page, "0001-01-01", "2026-03-31");
		});

		assertThat(calendar.warmedSlotCount()).as("no per-day slot for 200 employees since the year 1").isZero();
		assertThat(issued).as("and no statement read for them").isEmpty();
	}

	/**
	 * The budget is what a kind holds over the whole request, not what one warm
	 * asks for: a second warm of 2,000,000 employee-days after a first of the
	 * same size would hold 4,000,000, so it is not made.
	 */
	@Test
	void aSecondWarmPastWhatTheRequestAlreadyHoldsIsNotMade() {
		LegacyAttendanceCalendar calendar = calendar(counter.wrap(dataSource()));
		List<Long> first = java.util.stream.LongStream.rangeClosed(1, 1000).boxed().toList();
		List<Long> second = java.util.stream.LongStream.rangeClosed(1001, 2000).boxed().toList();
		// 2,000 days: 2020-01-01 .. 2025-06-22.
		List<String> firstIssued = counter.measure(
				() -> calendar.warmShiftsForEmployees(first, "2020-01-01", "2025-06-22"));
		assertThat(firstIssued).as("the first warm fits and reads").isNotEmpty();
		assertThat(calendar.warmedSlotCount()).isEqualTo(2_000_000L);

		List<String> secondIssued = counter.measure(
				() -> calendar.warmShiftsForEmployees(second, "2020-01-01", "2025-06-22"));

		assertThat(secondIssued).as("the second would pass the request's budget, so it is not read").isEmpty();
		assertThat(calendar.warmedSlotCount()).as("and nothing more is held").isEqualTo(2_000_000L);
	}

	/**
	 * The request the review found: period stats for a range that runs far past
	 * today. What is warmed stops at today, whatever {@code to} says -- at
	 * {@code 2a5baf0d} this held a slot per day to 2100.
	 */
	@Test
	void periodStatsWarmNoFurtherThanToday() {
		DataSource dataSource = dataSource();
		LegacyAttendanceCalendar calendar = calendar(dataSource);
		LegacyClock clock = new LegacyClock(dataSource);
		LegacyAttendanceSessions sessions = new LegacyAttendanceSessions(dataSource, calendar, clock);
		LegacyAttendanceWorkedMinutes worked = new LegacyAttendanceWorkedMinutes(dataSource, calendar, sessions, clock);
		LegacyAttendancePeriodStats stats = new LegacyAttendancePeriodStats(
				dataSource, calendar, new LegacyWeeklyRestCredit(dataSource, calendar), worked);

		stats.employeePeriodStats(COMPANY, EMPLOYEE, "2026-03-01", "2100-12-31", "Rest", TODAY);

		long warmedDays = (TODAY.toEpochDay() - LocalDate.parse("2026-03-01").toEpochDay() + 1)
				+ LegacyAttendanceCalendar.REPORT_LOOKBACK_DAYS + LegacyAttendanceCalendar.REPORT_LOOKAHEAD_DAYS;
		assertThat(calendar.warmedSlotCount())
				.as("shifts, leave and timed requests from 14 days before the first date to 8 after today")
				.isLessThanOrEqualTo(3 * warmedDays);
	}

	private static LegacyAttendanceCalendar calendar(DataSource dataSource) {
		return new LegacyAttendanceCalendar(dataSource, new LegacyWeeklyOffDays(dataSource));
	}

	private static DataSource dataSource() {
		return new DriverManagerDataSource(MARIADB.getJdbcUrl(), MARIADB.getUsername(), MARIADB.getPassword());
	}
}

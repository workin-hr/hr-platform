package com.workin.legacy.attendance.calendar;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import com.workin.backend.perf.QueryCounter;
import com.workin.legacy.AbstractLegacyMySqlTest;
import com.workin.legacy.attendance.LegacyWeeklyOffDays;

/**
 * The batched approved-leave pre-warm answers what the per-date query answers,
 * for every date.
 *
 * <p>{@code warmApprovedLeaveForEmployees} replaces one statement per employee
 * per date -- 240 of the attendance page's 286 for ten rows over a month -- and
 * it earns that only if a warmed answer is indistinguishable from the query's.
 * The shapes that decide it are not the interval wholly inside the range but the
 * ones that straddle it: {@code IS_ON_APPROVED_LEAVE} compares a single date
 * against {@code from_date}/{@code to_date}, while the warm selects intervals by
 * overlap and then re-decides each date, so a leave beginning before the range
 * or ending after it is where a wrong predicate shows up. So are the two
 * inclusive boundaries -- the first and last day of a leave are both covered --
 * and the two ways a request is not paid leave at all: a status that is not
 * {@code approved}, and a request type with {@code counts_as_paid_leave = 0}.
 *
 * <p>Asserted by comparing the two, date by date, rather than by restating the
 * expected answer: a test that hard-coded its own reading of the predicate would
 * pass against a warm that was wrong in the same way.
 */
class LegacyApprovedLeaveWarmTest extends AbstractLegacyMySqlTest {

	private static final long COMPANY = 99780;
	private static final long BRANCH = 997801;

	/** Every interval shape, on one employee, so one comparison sweep covers them all. */
	private static final long EMPLOYEE = 997811;

	/** No requests at all: the warm must cache false, not leave the dates unwarmed. */
	private static final long NEVER_ON_LEAVE = 997812;

	/** Whose only requests are the two that do not count. */
	private static final long NOT_PAID_LEAVE = 997813;

	private static final long PAID_TYPE = 997821;
	private static final long UNPAID_TYPE = 997822;

	private DataSource dataSource;

	@BeforeEach
	void seed() throws Exception {
		seedAsLegacyWould(
				"DELETE FROM requests WHERE employee_id BETWEEN 997800 AND 997899",
				"DELETE FROM employees WHERE id BETWEEN 997800 AND 997899",
				"DELETE FROM request_types WHERE id BETWEEN 997800 AND 997899",
				"DELETE FROM branches WHERE id BETWEEN 997800 AND 997899",
				"DELETE FROM companies WHERE id = " + COMPANY,
				"INSERT INTO companies (id, company_name, phone, status, created_at) VALUES ("
						+ COMPANY + ", 'Leave Co', '+201000997800', 'active', '2019-01-15 09:00:00')",
				"INSERT INTO branches (id, company_id, name, is_active, created_at) VALUES ("
						+ BRANCH + ", " + COMPANY + ", 'Leave HQ', 1, '2019-03-01 10:00:00')",
				"INSERT INTO request_types (id, company_id, name, is_active, counts_as_paid_leave,"
						+ " created_at) VALUES"
						+ " (" + PAID_TYPE + ", " + COMPANY + ", 'Annual', 1, 1, '2019-05-01 08:00:00'),"
						+ " (" + UNPAID_TYPE + ", " + COMPANY + ", 'Unpaid', 1, 0, '2019-05-01 08:00:00')",
				employee(EMPLOYEE),
				employee(NEVER_ON_LEAVE),
				employee(NOT_PAID_LEAVE),
				// The five shapes, against a warm window of 2026-03-01..2026-03-31:
				// wholly inside; starting before it and ending inside; starting
				// inside and ending after it; wholly before it; wholly after it.
				"INSERT INTO requests (employee_id, request_type_id, from_date, to_date, status,"
						+ " created_at) VALUES"
						+ " (" + EMPLOYEE + ", " + PAID_TYPE + ", '2026-03-05', '2026-03-07', 'approved',"
						+ " '2026-02-01 09:00:00'),"
						+ " (" + EMPLOYEE + ", " + PAID_TYPE + ", '2026-02-25', '2026-03-02', 'approved',"
						+ " '2026-02-01 09:00:00'),"
						+ " (" + EMPLOYEE + ", " + PAID_TYPE + ", '2026-03-29', '2026-04-04', 'approved',"
						+ " '2026-02-01 09:00:00'),"
						+ " (" + EMPLOYEE + ", " + PAID_TYPE + ", '2026-02-10', '2026-02-12', 'approved',"
						+ " '2026-02-01 09:00:00'),"
						+ " (" + EMPLOYEE + ", " + PAID_TYPE + ", '2026-05-10', '2026-05-12', 'approved',"
						+ " '2026-02-01 09:00:00')",
				// Neither of these is paid leave: one is a paid type still awaiting
				// a decision, the other an approved type that does not count.
				"INSERT INTO requests (employee_id, request_type_id, from_date, to_date, status,"
						+ " created_at) VALUES"
						+ " (" + NOT_PAID_LEAVE + ", " + PAID_TYPE + ", '2026-03-10', '2026-03-12',"
						+ " 'pending', '2026-02-01 09:00:00'),"
						+ " (" + NOT_PAID_LEAVE + ", " + PAID_TYPE + ", '2026-03-14', '2026-03-16',"
						+ " 'rejected', '2026-02-01 09:00:00'),"
						+ " (" + NOT_PAID_LEAVE + ", " + UNPAID_TYPE + ", '2026-03-18', '2026-03-20',"
						+ " 'approved', '2026-02-01 09:00:00')");

		this.dataSource = new DriverManagerDataSource(
				MARIADB.getJdbcUrl(), MARIADB.getUsername(), MARIADB.getPassword());
	}

	@Test
	void aWarmedAnswerIsExactlyWhatThePerDateQueryAnswers() {
		LegacyAttendanceCalendar warmed = calendar();
		warmed.warmApprovedLeaveForEmployees(
				List.of(EMPLOYEE, NEVER_ON_LEAVE, NOT_PAID_LEAVE), "2026-03-01", "2026-03-31");

		int covered = 0;
		for (long employee : List.of(EMPLOYEE, NEVER_ON_LEAVE, NOT_PAID_LEAVE)) {
			for (String date : datesIn("2026-03-01", "2026-03-31")) {
				// A fresh calendar per lookup, so the comparison is against the
				// query and never against another warmed value.
				boolean direct = calendar().isOnApprovedLeave(employee, date);
				assertThat(warmed.isOnApprovedLeave(employee, date))
						.as("employee %s on %s", employee, date)
						.isEqualTo(direct);
				if (direct) {
					covered++;
				}
			}
		}
		assertThat(covered)
				.as("the fixture has to contain leave for the comparison to mean anything: "
						+ "5th-7th, 1st-2nd from the leave that began in February, and 29th-31st "
						+ "from the one that runs into April")
				.isEqualTo(8);
	}

	/**
	 * A leave that ends the day the range begins, and one that begins the day it
	 * ends, are the cases an overlap predicate written with {@code <} instead of
	 * {@code <=} would drop -- and the per-date comparison would still say
	 * covered, so the warm would answer false where the query answers true.
	 */
	@Test
	void anIntervalTouchingOnlyTheEdgeOfTheWindowIsStillSelected() {
		LegacyAttendanceCalendar warmed = calendar();
		warmed.warmApprovedLeaveForEmployees(List.of(EMPLOYEE), "2026-03-02", "2026-03-29");

		assertThat(warmed.isOnApprovedLeave(EMPLOYEE, "2026-03-02"))
				.as("the window's first day is the last day of the leave that began 2026-02-25")
				.isTrue();
		assertThat(warmed.isOnApprovedLeave(EMPLOYEE, "2026-03-29"))
				.as("the window's last day is the first day of the leave that ends 2026-04-04")
				.isTrue();
	}

	/** Both ends of a leave are covered; the days either side of it are not. */
	@Test
	void theFirstAndLastDayOfALeaveAreBothCoveredAndTheirNeighboursAreNot() {
		LegacyAttendanceCalendar warmed = calendar();
		warmed.warmApprovedLeaveForEmployees(List.of(EMPLOYEE), "2026-03-01", "2026-03-31");

		assertThat(warmed.isOnApprovedLeave(EMPLOYEE, "2026-03-04")).as("the day before").isFalse();
		assertThat(warmed.isOnApprovedLeave(EMPLOYEE, "2026-03-05")).as("from_date").isTrue();
		assertThat(warmed.isOnApprovedLeave(EMPLOYEE, "2026-03-06")).as("inside").isTrue();
		assertThat(warmed.isOnApprovedLeave(EMPLOYEE, "2026-03-07")).as("to_date").isTrue();
		assertThat(warmed.isOnApprovedLeave(EMPLOYEE, "2026-03-08")).as("the day after").isFalse();
	}

	/**
	 * The case that separates a cache from a claim: a date the warm never
	 * reached must fall back to the query, not read as "not on leave".
	 *
	 * <p>{@code creditStatus} walks up to fourteen days behind the period it was
	 * asked about, so a caller that warms exactly its own range will ask about
	 * dates outside it. A cache that stored {@code false} for every date, or one
	 * whose lookup could not tell an absent key from a cached {@code false},
	 * would answer those wrongly -- and the answer changes a paid rest day into
	 * a void one.
	 */
	@Test
	void aDateOutsideTheWarmedWindowFallsBackToTheQuery() {
		LegacyAttendanceCalendar warmed = calendar();
		warmed.warmApprovedLeaveForEmployees(List.of(EMPLOYEE), "2026-03-10", "2026-03-20");

		assertThat(warmed.isOnApprovedLeave(EMPLOYEE, "2026-03-06"))
				.as("before the warmed window, and inside the 5th-7th leave")
				.isTrue();
		assertThat(warmed.isOnApprovedLeave(EMPLOYEE, "2026-05-11"))
				.as("two months after the warmed window, and inside the May leave")
				.isTrue();
		assertThat(warmed.isOnApprovedLeave(EMPLOYEE, "2026-03-15"))
				.as("inside the warmed window, and on no leave")
				.isFalse();
	}

	/** An employee the warm was not given is answered by the query, not by absence. */
	@Test
	void anEmployeeTheWarmDidNotCoverFallsBackToTheQuery() {
		LegacyAttendanceCalendar warmed = calendar();
		warmed.warmApprovedLeaveForEmployees(List.of(NEVER_ON_LEAVE), "2026-03-01", "2026-03-31");

		assertThat(warmed.isOnApprovedLeave(EMPLOYEE, "2026-03-06"))
				.as("EMPLOYEE was not warmed, so the query answers, and it says covered")
				.isTrue();
		assertThat(warmed.isOnApprovedLeave(NEVER_ON_LEAVE, "2026-03-06"))
				.as("and the one that was warmed is still false")
				.isFalse();
	}

	/**
	 * Two methods on one request ask for the same window, and that must cost one
	 * statement.
	 *
	 * <p>The attendance report's absent-day list and its void weekly-rest list
	 * both cover the period plus the fourteen days of lookback `creditStatus`
	 * reaches into, so both call the warm with identical arguments. Without the
	 * memo the second re-issues the query -- one extra round trip on a page whose
	 * whole cost is round trips, and invisible to every assertion about answers.
	 */
	@Test
	void warmingTheSameWindowTwiceCostsOneStatement() {
		QueryCounter counter = new QueryCounter();
		LegacyAttendanceCalendar calendar = new LegacyAttendanceCalendar(
				counter.wrap(this.dataSource), new LegacyWeeklyOffDays(this.dataSource));

		List<String> issued = counter.measure(() -> {
			calendar.warmApprovedLeaveForEmployees(List.of(EMPLOYEE), "2026-03-01", "2026-03-31");
			calendar.warmApprovedLeaveForEmployees(List.of(EMPLOYEE), "2026-03-01", "2026-03-31");
		});

		assertThat(issued).as("the second warm is the same question, already answered").hasSize(1);
		assertThat(calendar.isOnApprovedLeave(EMPLOYEE, "2026-03-06"))
				.as("and the cache the first one filled still answers")
				.isTrue();
	}

	/** A different window is a different question, and is asked. */
	@Test
	void warmingADifferentWindowIsStillAsked() {
		QueryCounter counter = new QueryCounter();
		LegacyAttendanceCalendar calendar = new LegacyAttendanceCalendar(
				counter.wrap(this.dataSource), new LegacyWeeklyOffDays(this.dataSource));

		List<String> issued = counter.measure(() -> {
			calendar.warmApprovedLeaveForEmployees(List.of(EMPLOYEE), "2026-03-01", "2026-03-31");
			calendar.warmApprovedLeaveForEmployees(List.of(EMPLOYEE), "2026-04-01", "2026-04-30");
		});

		assertThat(issued).hasSize(2);
		assertThat(calendar.isOnApprovedLeave(EMPLOYEE, "2026-04-02"))
				.as("the April window covers the leave that runs 2026-03-29..2026-04-04")
				.isTrue();
	}

	@Test
	void warmingIsRefusedRatherThanGuessedWhenItCannotBeDone() {
		LegacyAttendanceCalendar calendar = calendar();
		calendar.warmApprovedLeaveForEmployees(List.of(), "2026-03-01", "2026-03-31");
		calendar.warmApprovedLeaveForEmployees(List.of(EMPLOYEE), "not-a-date", "2026-03-31");
		calendar.warmApprovedLeaveForEmployees(List.of(EMPLOYEE), "2026-03-31", "2026-03-01");
		calendar.warmApprovedLeaveForEmployees(List.of(0L, -1L), "2026-03-01", "2026-03-31");

		for (String date : List.of("2026-03-06", "2026-03-15")) {
			assertThat(calendar.isOnApprovedLeave(EMPLOYEE, date))
					.as("nothing was cached, so the per-date query still answers %s correctly", date)
					.isEqualTo(calendar().isOnApprovedLeave(EMPLOYEE, date));
		}
	}

	private static List<String> datesIn(String from, String to) {
		List<String> dates = new ArrayList<>();
		for (LocalDate d = LocalDate.parse(from); !d.isAfter(LocalDate.parse(to)); d = d.plusDays(1)) {
			dates.add(d.toString());
		}
		return dates;
	}

	private LegacyAttendanceCalendar calendar() {
		return new LegacyAttendanceCalendar(this.dataSource, new LegacyWeeklyOffDays(this.dataSource));
	}

	private static String employee(long id) {
		return "INSERT INTO employees (id, company_id, branch_id, employee_code, first_name,"
				+ " last_name, phone, role, is_active, created_at) VALUES (" + id + ", " + COMPANY
				+ ", " + BRANCH + ", '" + id + "', 'Leave', 'One', '+2010" + id + "', 'employee', 1,"
				+ " '2019-04-01 08:00:00')";
	}
}

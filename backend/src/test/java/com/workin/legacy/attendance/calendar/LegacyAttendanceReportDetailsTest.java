package com.workin.legacy.attendance.calendar;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import com.workin.legacy.AbstractLegacyMySqlTest;
import com.workin.legacy.LegacyClock;
import com.workin.legacy.attendance.LegacyWeeklyOffDays;
import com.workin.legacy.attendance.session.LegacyAttendanceSessions;
import com.workin.legacy.payroll.LegacyPayrollAttendanceFigures;

/**
 * Measured oracle for the five helpers Wave 12.6.6 owed
 * ({@link LegacyAttendanceReportDetails}), against the real legacy schema.
 *
 * <p>Each case exists for a branch the report's output depends on, not for
 * coverage: a blank exception name that legacy skips, a holiday the employee
 * attended, the four independent reasons a day is not absence, the elapsed-day
 * clamp, and the three rules that decide which attendance rows contribute
 * minutes. Every one of them would produce a plausible-looking report if got
 * wrong, which is why they are pinned rather than described.
 */
class LegacyAttendanceReportDetailsTest extends AbstractLegacyMySqlTest {

	private static final long COMPANY = 21601L;
	private static final long BRANCH = 21611L;
	private static final long EMPLOYEE = 216011L;
	private static final long SHIFT = 216021L;
	private static final long EXCEPTION_NAMED = 216031L;
	private static final long EXCEPTION_BLANK = 216032L;
	private static final long REQUEST_TYPE_LEAVE = 216041L;

	private static final String FROM = "2026-03-01";
	private static final String TO = "2026-03-10";
	private static final String ABSENT_LABEL = "Absence";
	private static final String PRESENT_LABEL = "Attendance";
	private static final String REST_LABEL = "Weekly rest";
	private static final String VOID_LABEL = "Unearned weekly rest";

	private static LegacyAttendanceReportDetails details;

	@BeforeAll
	static void seedAndWire() throws Exception {
		DriverManagerDataSource dataSource = new DriverManagerDataSource(
				MARIADB.getJdbcUrl(), MARIADB.getUsername(), MARIADB.getPassword());

		seedAsLegacyWould(
				"INSERT INTO companies (id, company_name, phone, status, created_at) VALUES"
						+ " (" + COMPANY + ", 'Report Details Co', '+201000021601', 'active', '2019-01-15 09:00:00')",
				"INSERT INTO branches (id, company_id, name, is_active, created_at) VALUES"
						+ " (" + BRANCH + ", " + COMPANY + ", 'Main', 1, '2019-03-01 10:00:00')",
				"INSERT INTO employees (id, company_id, branch_id, employee_code, first_name, last_name,"
						+ " phone, role, is_active, expected_daily_hours, created_at) VALUES"
						+ " (" + EMPLOYEE + ", " + COMPANY + ", " + BRANCH + ", " + EMPLOYEE + ", 'Rep', 'One',"
						+ " '+201000216011', 'employee', 1, 8, '2019-04-01 08:00:00')",
				// A shift that is never a rest day, so "rest day" in these cases
				// comes from the weekly-off setting rather than the shift.
				"INSERT INTO shifts (id, company_id, name, start_time, end_time, days_off, is_active, created_at)"
						+ " VALUES (" + SHIFT + ", " + COMPANY + ", 'Day', '09:00:00', '17:00:00', '', 1,"
						+ " '2019-05-01 08:00:00')",
				"INSERT INTO employee_shift_assignments (employee_id, shift_id, effective_from) VALUES"
						+ " (" + EMPLOYEE + ", " + SHIFT + ", '2019-05-01')",
				"INSERT INTO exception_types (id, company_id, name, is_active, created_at) VALUES"
						+ " (" + EXCEPTION_NAMED + ", " + COMPANY + ", 'Mission', 1, '2019-06-01 08:00:00'),"
						+ " (" + EXCEPTION_BLANK + ", " + COMPANY + ", '   ', 1, '2019-06-01 08:00:00')",
				"INSERT INTO request_types (id, company_id, name, counts_as_paid_leave, is_active, created_at)"
						+ " VALUES (" + REQUEST_TYPE_LEAVE + ", " + COMPANY + ", 'Annual', 1, 1,"
						+ " '2019-07-01 08:00:00')",

				// 03-02 present, a full punched day.
				"INSERT INTO attendance (employee_id, check_in, check_out, created_at) VALUES"
						+ " (" + EMPLOYEE + ", '2026-03-02 09:00:00', '2026-03-02 17:00:00', '2026-03-02 17:00:00')",
				// 03-02 again, a second punch on a day already seen -- must be
				// skipped entirely by periodWorkMinutes, not summed.
				"INSERT INTO attendance (employee_id, check_in, check_out, created_at) VALUES"
						+ " (" + EMPLOYEE + ", '2026-03-02 18:00:00', '2026-03-02 20:00:00', '2026-03-02 20:00:00')",
				// 03-03 a named exception with a punch.
				"INSERT INTO attendance (employee_id, check_in, check_out, exception_type_id, created_at) VALUES"
						+ " (" + EMPLOYEE + ", '2026-03-03 09:00:00', '2026-03-03 17:00:00', " + EXCEPTION_NAMED + ","
						+ " '2026-03-03 17:00:00')",
				// 03-04 an exception whose type name is blank -- exceptionDetails
				// must skip it rather than emit an empty label.
				"INSERT INTO attendance (employee_id, check_in, check_out, exception_type_id, created_at) VALUES"
						+ " (" + EMPLOYEE + ", '2026-03-04 09:00:00', '2026-03-04 17:00:00', " + EXCEPTION_BLANK + ","
						+ " '2026-03-04 17:00:00')",
				// 03-05 an exception-only marker: midnight check-in, no check-out.
				// Contributes no minutes and does not count expected minutes.
				"INSERT INTO attendance (employee_id, check_in, exception_type_id, created_at) VALUES"
						+ " (" + EMPLOYEE + ", '2026-03-05 00:00:00', " + EXCEPTION_NAMED + ", '2026-03-05 00:00:00')",
				// 03-06 a single punch with no check-out: zero worked, but its
				// expected minutes still count.
				"INSERT INTO attendance (employee_id, check_in, created_at) VALUES"
						+ " (" + EMPLOYEE + ", '2026-03-06 09:00:00', '2026-03-06 09:00:00')",

				// 03-09 an official holiday the employee did not attend (credited),
				// 03-10 one they did (not credited).
				"INSERT INTO company_official_holidays (company_id, holiday_date, name, created_at) VALUES"
						+ " (" + COMPANY + ", '2026-03-09', 'Spring Day', '2026-01-01 00:00:00'),"
						+ " (" + COMPANY + ", '2026-03-10', 'Attended Day', '2026-01-01 00:00:00')",
				"INSERT INTO attendance (employee_id, check_in, check_out, created_at) VALUES"
						+ " (" + EMPLOYEE + ", '2026-03-10 09:00:00', '2026-03-10 17:00:00', '2026-03-10 17:00:00')",

				// 03-07 approved paid leave -- not absence.
				"INSERT INTO requests (employee_id, request_type_id, status, from_date, to_date, created_at)"
						+ " VALUES (" + EMPLOYEE + ", " + REQUEST_TYPE_LEAVE + ", 'approved', '2026-03-07',"
						+ " '2026-03-07', '2026-03-01 08:00:00')");

		// Wired directly rather than through Spring, the way
		// LegacyAttendanceQueryCachingTest does: these are plain collaborators
		// and the test needs no application context.
		LegacyClock clock = new LegacyClock(dataSource);
		LegacyWeeklyOffDays weeklyOffDays = new LegacyWeeklyOffDays(dataSource);
		LegacyAttendanceCalendar calendar = new LegacyAttendanceCalendar(dataSource, weeklyOffDays);
		LegacyWeeklyRestCredit weeklyRestCredit = new LegacyWeeklyRestCredit(dataSource, calendar);
		LegacyAttendanceSessions sessions = new LegacyAttendanceSessions(dataSource, calendar, clock);
		LegacyAttendanceWorkedMinutes worked =
				new LegacyAttendanceWorkedMinutes(dataSource, calendar, sessions, clock);
		LegacyPayrollAttendanceFigures figures = new LegacyPayrollAttendanceFigures(
				dataSource, calendar, weeklyRestCredit, worked, weeklyOffDays);
		details = new LegacyAttendanceReportDetails(dataSource, calendar, worked, weeklyRestCredit, figures);
	}

	@Test
	void exceptionDetailsListsNamedExceptionsAndSkipsBlankOnes() {
		List<Map<String, Object>> rows = details.exceptionDetails(EMPLOYEE, FROM, TO);

		assertThat(rows)
				.as("the blank-named exception type on 03-04 is skipped, not emitted with an empty label")
				.hasSize(2);
		assertThat(rows.get(0)).containsEntry("date", "2026-03-03").containsEntry("exception_name", "Mission");
		assertThat(rows.get(1)).containsEntry("date", "2026-03-05").containsEntry("exception_name", "Mission");
	}

	@Test
	void holidayCreditCountsOnlyHolidaysWithoutACheckIn() {
		assertThat(details.holidayCreditDays(COMPANY, EMPLOYEE, FROM, TO))
				.as("03-09 is credited; 03-10 was attended and is not")
				.isEqualTo(1);
	}

	@Test
	void holidayCreditIsZeroForNonPositiveIdsAndEmptyDates() {
		assertThat(details.holidayCreditDays(0, EMPLOYEE, FROM, TO)).isZero();
		assertThat(details.holidayCreditDays(COMPANY, 0, FROM, TO)).isZero();
		assertThat(details.holidayCreditDays(COMPANY, EMPLOYEE, "", TO)).isZero();
		assertThat(details.holidayCreditDays(COMPANY, EMPLOYEE, FROM, "")).isZero();
	}

	@Test
	void absentDetailsExcludePresentHolidayLeaveAndRestDays() {
		List<Map<String, Object>> rows = details.absentDetails(
				COMPANY, EMPLOYEE, FROM, TO, TO, ABSENT_LABEL, PRESENT_LABEL, REST_LABEL);

		List<String> dates = rows.stream().map(row -> (String) row.get("date")).toList();
		assertThat(dates)
				.as("present 03-02/03/04/05/06/10, holiday 03-09/10, approved leave 03-07")
				.doesNotContain("2026-03-02", "2026-03-03", "2026-03-04", "2026-03-05", "2026-03-06",
						"2026-03-07", "2026-03-09", "2026-03-10");
		assertThat(dates).contains("2026-03-01", "2026-03-08");
		assertThat(rows).allSatisfy(row -> assertThat(row)
				.containsEntry("day_type", "absent").containsEntry("label", ABSENT_LABEL));
	}

	/**
	 * The clamp that keeps a period still in progress from reporting its future
	 * days as absence -- the single behaviour most likely to be lost in a
	 * refactor, because nothing else in the row shape hints at it.
	 */
	@Test
	void absentDetailsStopAtAsOfWhenThePeriodIsStillOpen() {
		List<String> clamped = details.absentDetails(
						COMPANY, EMPLOYEE, FROM, TO, "2026-03-02", ABSENT_LABEL, PRESENT_LABEL, REST_LABEL)
				.stream().map(row -> (String) row.get("date")).toList();

		assertThat(clamped).containsExactly("2026-03-01");
	}

	@Test
	void absentDetailsAreEmptyWhenAsOfPrecedesTheRange() {
		assertThat(details.absentDetails(
				COMPANY, EMPLOYEE, FROM, TO, "2026-02-01", ABSENT_LABEL, PRESENT_LABEL, REST_LABEL)).isEmpty();
	}

	@Test
	void absentDetailsAreEmptyForNonPositiveIdsAndEmptyDates() {
		assertThat(details.absentDetails(0, EMPLOYEE, FROM, TO, TO, ABSENT_LABEL, PRESENT_LABEL, REST_LABEL)).isEmpty();
		assertThat(details.absentDetails(COMPANY, 0, FROM, TO, TO, ABSENT_LABEL, PRESENT_LABEL, REST_LABEL)).isEmpty();
		assertThat(details.absentDetails(COMPANY, EMPLOYEE, "", TO, TO, ABSENT_LABEL, PRESENT_LABEL, REST_LABEL))
				.isEmpty();
	}

	@Test
	void voidWeeklyRestDetailsCarryTheirDayTypeAndLabel() {
		List<Map<String, Object>> rows = details.voidWeeklyRestAbsentDetails(
				COMPANY, EMPLOYEE, FROM, TO, Map.of(), Map.of(), TO, VOID_LABEL);

		assertThat(rows).allSatisfy(row -> assertThat(row)
				.containsEntry("day_type", "void_weekly_rest").containsEntry("label", VOID_LABEL));
		assertThat(rows).allSatisfy(row -> assertThat((String) row.get("date")).isBetween(FROM, TO));
	}

	@Test
	void voidWeeklyRestDetailsAreEmptyWhenAsOfPrecedesTheRange() {
		assertThat(details.voidWeeklyRestAbsentDetails(
				COMPANY, EMPLOYEE, FROM, TO, Map.of(), Map.of(), "2026-02-01", VOID_LABEL)).isEmpty();
	}

	/**
	 * Four interacting rules in one assertion set, because the interaction is
	 * the risk. The first row for a date wins, so 03-02's second punch is
	 * skipped rather than summed. An exception-only marker (03-05) contributes
	 * nothing and does not consume its date's expected minutes. A row that
	 * merely <em>carries</em> an exception type but has a check-out (03-03,
	 * 03-04) is an ordinary worked day. And a single punch with no check-out
	 * (03-06) is <b>not</b> zero: legacy pays it
	 * {@code expected - ATTENDANCE_INCOMPLETE_PUNCH_DEDUCTION_MINUTES}, a
	 * two-hour deduction, which is the one figure here that cannot be guessed
	 * from the row shape.
	 *
	 * <p>The expected-minutes total then shows the asymmetry that drives the
	 * report's overtime column: 03-10 is an official holiday that was worked, so
	 * it contributes 8h worked against <b>zero</b> expected.
	 */
	@Test
	void periodWorkMinutesDedupesByDateSkipsMarkersAndDeductsForASinglePunch() {
		LegacyAttendanceReportDetails.WorkMinutes minutes =
				details.periodWorkMinutes(COMPANY, EMPLOYEE, FROM, TO, REST_LABEL);

		assertThat(minutes.workedMinutes())
				.as("03-02, 03-03, 03-04 and 03-10 are 8h each; 03-06 is 8h less the 2h "
						+ "incomplete-punch deduction; 03-05 is a marker and 03-02's second punch is skipped")
				.isEqualTo(4 * 480 + (480 - 120));
		assertThat(minutes.expectedMinutes())
				.as("03-02, 03-03, 03-04 and 03-06 expect 8h each; 03-10 is an official holiday, "
						+ "so it expects nothing even though it was worked, and 03-05 is a marker")
				.isEqualTo(4 * 480);
		assertThat(minutes.workedMinutes() - minutes.expectedMinutes())
				.as("the surplus is what the report reports as overtime -- working a holiday "
						+ "contributes worked minutes against zero expected")
				.isEqualTo(2280 - 1920);
	}

	@Test
	void periodWorkMinutesAreZeroOutsideAnyAttendance() {
		LegacyAttendanceReportDetails.WorkMinutes minutes =
				details.periodWorkMinutes(COMPANY, EMPLOYEE, "2026-01-01", "2026-01-31", REST_LABEL);

		assertThat(minutes.workedMinutes()).isZero();
		assertThat(minutes.expectedMinutes()).isZero();
	}
}

package com.workin.legacy.attendance.calendar;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.workin.legacy.attendance.session.LegacyAttendanceSessions;
import com.workin.legacy.payroll.LegacyPayrollAttendanceFigures;

/**
 * The per-employee detail helpers {@code overall_attendance_report_build()}
 * alone reaches, all from {@code attendance_calendar_helper.php} except the
 * holiday count, which is {@code official_holidays_helper.php:129-151}.
 *
 * <p>They live together because they share exactly one consumer -- the Wave
 * 12.6.6 overall attendance report and the export built on the same builder --
 * and because none of them existed in Java before it: every other helper in that
 * builder's fifteen-function closure was already ported for payroll or for the
 * Wave 12.6.4b attendance endpoints.
 *
 * <h2>Why the holiday count is here and not beside the other holiday helpers</h2>
 * <p>{@code official_holidays_credit_days_for_employee()} is easy to confuse
 * with {@code official_holidays_working_credit_for_employee()}, which
 * {@link LegacyPayrollAttendanceFigures} already ports. They are different
 * functions: this one counts <b>every</b> holiday in range without a check-in,
 * while the payroll one filters weekly rest days out and reads
 * {@code company_settings}. Substituting one for the other under-reports the
 * report's holiday credit, so this port sits with its only caller and says so.
 */
@Component
public class LegacyAttendanceReportDetails {

	private static final String EXCEPTION_DETAILS = """
			SELECT DATE(a.check_in) AS exception_date, et.name AS exception_name
			FROM attendance AS a
			INNER JOIN exception_types AS et ON et.id = a.exception_type_id
			WHERE a.employee_id = ?
			  AND a.exception_type_id IS NOT NULL
			  AND DATE(a.check_in) BETWEEN ? AND ?
			ORDER BY exception_date ASC""";

	private static final String HOLIDAY_CREDIT_DAYS = """
			SELECT COUNT(*)
			FROM company_official_holidays h
			WHERE h.company_id = ?
			  AND h.holiday_date BETWEEN ? AND ?
			  AND NOT EXISTS (
			      SELECT 1 FROM attendance a
			      WHERE a.employee_id = ?
			        AND DATE(a.check_in) = h.holiday_date
			  )""";

	private static final String ATTENDANCE_IN_RANGE = """
			SELECT check_in, check_out, exception_type_id,
			  TIMESTAMPDIFF(MINUTE, check_in, check_out) AS duration_minutes
			FROM attendance AS a
			WHERE a.employee_id = ? AND DATE(a.check_in) BETWEEN ? AND ?
			ORDER BY a.check_in ASC""";

	private final JdbcTemplate jdbcTemplate;
	private final LegacyAttendanceCalendar calendar;
	private final LegacyAttendanceWorkedMinutes workedMinutes;
	private final LegacyWeeklyRestCredit weeklyRestCredit;
	private final LegacyPayrollAttendanceFigures payrollFigures;

	public LegacyAttendanceReportDetails(
			DataSource legacyDataSource, LegacyAttendanceCalendar calendar,
			LegacyAttendanceWorkedMinutes workedMinutes, LegacyWeeklyRestCredit weeklyRestCredit,
			LegacyPayrollAttendanceFigures payrollFigures) {
		this.jdbcTemplate = new JdbcTemplate(legacyDataSource);
		this.calendar = calendar;
		this.workedMinutes = workedMinutes;
		this.weeklyRestCredit = weeklyRestCredit;
		this.payrollFigures = payrollFigures;
	}

	/** Worked and expected minutes for a period, as {@code attendance_period_work_minutes()} returns them. */
	public record WorkMinutes(int workedMinutes, int expectedMinutes) {
	}

	/**
	 * {@code official_holidays_credit_days_for_employee()}
	 * ({@code official_holidays_helper.php:129-151}): holidays in the range with
	 * no check-in for this employee, credited as present.
	 *
	 * <p>No weekly-rest filter and no {@code company_settings} read -- see the
	 * class javadoc for why that distinction matters.
	 */
	public int holidayCreditDays(long companyId, long employeeId, String from, String to) {
		if (companyId <= 0 || employeeId <= 0 || from.isEmpty() || to.isEmpty()) {
			return 0;
		}
		Integer count = jdbcTemplate.queryForObject(
				HOLIDAY_CREDIT_DAYS, Integer.class, companyId, from, to, employeeId);
		return count == null ? 0 : count;
	}

	/**
	 * {@code attendance_exception_details_for_period()}
	 * ({@code attendance_calendar_helper.php:447-477}): one row per day carrying
	 * an exception type, with the type's name.
	 *
	 * <p>A row whose exception name trims to empty is skipped rather than
	 * emitted with a blank label -- legacy's own {@code continue}.
	 */
	public List<Map<String, Object>> exceptionDetails(long employeeId, String from, String to) {
		List<Map<String, Object>> details = new ArrayList<>();
		jdbcTemplate.query(EXCEPTION_DETAILS, rs -> {
			String name = rs.getString("exception_name");
			String trimmed = name == null ? "" : name.trim();
			if (trimmed.isEmpty()) {
				return;
			}
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("date", rs.getString("exception_date"));
			row.put("exception_name", trimmed);
			details.add(row);
		}, employeeId, from, to);
		return details;
	}

	/**
	 * {@code attendance_absent_details_for_period()}
	 * ({@code attendance_calendar_helper.php:540-601}): every elapsed day in the
	 * range that is not present, not an official holiday, not approved leave and
	 * not a rest day.
	 *
	 * <p>{@code to} is clamped to {@code asOf} first, so a period still in
	 * progress never reports its future days as absence.
	 */
	public List<Map<String, Object>> absentDetails(
			long companyId, long employeeId, String from, String to, String asOf,
			String absentLabel, String presentLabel, String weeklyRestLabel) {
		List<Map<String, Object>> details = new ArrayList<>();
		if (companyId <= 0 || employeeId <= 0 || from.isEmpty() || to.isEmpty()) {
			return details;
		}
		String rangeTo = to.compareTo(asOf) > 0 ? asOf : to;
		if (rangeTo.compareTo(from) < 0) {
			return details;
		}

		Set<String> presentDates = new LinkedHashSet<>();
		// Only the dates are read here, but the present label is passed through
		// rather than reused from another key: a caller reading these rows later
		// must see `csv_attendance_present_day`, which is what legacy emits.
		for (Map<String, Object> row : payrollFigures.attendancePresentDetails(
				employeeId, from, rangeTo, presentLabel)) {
			String date = (String) row.get("date");
			if (date != null && !date.isEmpty()) {
				presentDates.add(date);
			}
		}

		Map<String, String> holidayByDate = calendar.holidaysByDate(companyId, from, rangeTo);

		LocalDate start = LocalDate.parse(from);
		LocalDate end = LocalDate.parse(rangeTo);
		for (LocalDate day = start; !day.isAfter(end); day = day.plusDays(1)) {
			String dateStr = day.toString();
			if (presentDates.contains(dateStr) || holidayByDate.containsKey(dateStr)) {
				continue;
			}
			if (weeklyRestCredit.isOnApprovedLeave(employeeId, dateStr)) {
				continue;
			}
			if (calendar.expectedForDay(companyId, employeeId, dateStr, weeklyRestLabel).restDay()) {
				continue;
			}
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("date", dateStr);
			row.put("day_type", "absent");
			row.put("label", absentLabel);
			details.add(row);
		}
		return details;
	}

	/**
	 * {@code attendance_void_weekly_rest_absent_details_for_period()}
	 * ({@code attendance_calendar_helper.php:610-667}): the weekly rest days the
	 * employee did not earn, which count as unpaid absence.
	 *
	 * <p>The caller passes the attendance flags and holiday map it already built
	 * for the same range; legacy rebuilds them only when handed empty arrays, and
	 * the report never hands it empty ones.
	 */
	public List<Map<String, Object>> voidWeeklyRestAbsentDetails(
			long companyId, long employeeId, String from, String to,
			Map<String, LegacyWeeklyRestCredit.AttendanceFlag> attendanceByDate,
			Map<String, String> holidayByDate, String asOf, String voidLabel) {
		List<Map<String, Object>> details = new ArrayList<>();
		if (companyId <= 0 || employeeId <= 0 || from.isEmpty() || to.isEmpty()) {
			return details;
		}
		String rangeTo = to.compareTo(asOf) > 0 ? asOf : to;
		if (rangeTo.compareTo(from) < 0) {
			return details;
		}

		Map<String, LegacyWeeklyRestCredit.AttendanceFlag> flags = attendanceByDate.isEmpty()
				? weeklyRestCredit.attendanceFlagsInRange(companyId, employeeId, from, rangeTo)
				: attendanceByDate;
		// Legacy's own fallback looks back seven days, because weekly-rest credit
		// is decided against the block of workdays preceding the rest day.
		Map<String, String> holidays = holidayByDate.isEmpty()
				? calendar.holidaysByDate(
						companyId, LocalDate.parse(from).minusDays(7).toString(), rangeTo)
				: holidayByDate;

		for (String date : payrollFigures.weeklyRestDatesByStatus(
				companyId, employeeId, from, rangeTo, LegacyWeeklyRestCredit.VOID, flags, holidays, asOf)) {
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("date", date);
			row.put("day_type", "void_weekly_rest");
			row.put("label", voidLabel);
			details.add(row);
		}
		return details;
	}

	/**
	 * {@code attendance_period_work_minutes()}
	 * ({@code attendance_calendar_helper.php:859-928}): worked and expected
	 * minutes across a period.
	 *
	 * <p>Three legacy behaviours the loop preserves exactly. Rows are ordered by
	 * {@code check_in} and the <b>first</b> row for a date wins -- later punches
	 * on the same day are skipped entirely, not summed.
	 *
	 * <p><b>The date is reserved before the exception-only check, deliberately.</b>
	 * PHP sets {@code $seen[$date_key] = true} and only then tests
	 * {@code attendance_is_exception_only_row()}
	 * ({@code attendance_calendar_helper.php:888-900}), so a midnight marker
	 * followed by a real punch on the same date consumes the slot and the punch
	 * is skipped -- understating that day's minutes. That is legacy's behaviour,
	 * not a porting slip: moving the reservation after the check would read
	 * better and would diverge. D-058 forbids the silent fix; changing it needs
	 * its own numbered decision.
	 *
	 * <p>Expected minutes are counted for any <em>punched</em> day,
	 * including a single-punch day with no check-out, which is why the guard is
	 * on {@code checkIn != "" || checkOut != null || worked > 0} rather than on
	 * worked minutes alone.
	 */
	public WorkMinutes periodWorkMinutes(
			long companyId, long employeeId, String from, String to, String weeklyRestLabel) {
		Set<String> seen = new LinkedHashSet<>();
		int[] totals = new int[2];

		jdbcTemplate.query(ATTENDANCE_IN_RANGE, rs -> {
			String checkIn = rs.getString("check_in");
			if (checkIn == null || checkIn.isEmpty()) {
				return;
			}
			String dateKey = checkIn.substring(0, Math.min(10, checkIn.length()));
			if (!seen.add(dateKey)) {
				return;
			}

			String rawCheckOut = rs.getString("check_out");
			String checkOut = rawCheckOut == null || rawCheckOut.trim().isEmpty() ? null : rawCheckOut;
			Object exceptionTypeId = rs.getObject("exception_type_id");
			if (LegacyAttendanceSessions.isExceptionOnlyRow(checkIn, checkOut, exceptionTypeId)) {
				return;
			}

			int expectedMinutes = calendar
					.expectedForDay(companyId, employeeId, dateKey, weeklyRestLabel).expectedMinutes();
			int raw = checkOut != null ? Math.max(0, rs.getInt("duration_minutes")) : 0;
			int dayWorked = workedMinutes.rowWorkedMinutes(
					companyId, employeeId, dateKey, checkIn, checkOut, raw, exceptionTypeId, weeklyRestLabel);

			if (dayWorked <= 0 && checkIn.isEmpty() && checkOut == null) {
				return;
			}
			if (!checkIn.isEmpty() || checkOut != null || dayWorked > 0) {
				totals[1] += expectedMinutes;
				totals[0] += dayWorked;
			}
		}, employeeId, from, to);

		return new WorkMinutes(totals[0], totals[1]);
	}
}

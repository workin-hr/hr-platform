package com.workin.legacy.attendance.calendar;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.workin.legacy.LegacyPhpStrtotime;
import com.workin.legacy.attendance.session.LegacyAttendanceSessions;

/**
 * {@code attendance_build_employee_range_calendar()} and
 * {@code attendance_build_employee_monthly_calendar()}
 * ({@code attendance_calendar_helper.php:217-440}): one row per calendar day
 * in an inclusive range for one employee, real attendance, rest/holiday and
 * missing days alike.
 */
@Component
public class LegacyAttendanceRangeCalendar {

	private static final String ATTENDANCE_IN_RANGE = """
			SELECT a.id, a.check_in, a.check_out, a.exception_type_id, et.name AS exception_type_name,
				TIMESTAMPDIFF(MINUTE, a.check_in, a.check_out) AS duration_minutes
			FROM attendance a
			LEFT JOIN exception_types et ON et.id = a.exception_type_id
			WHERE a.employee_id = ?
			  AND DATE(a.check_in) >= ?
			  AND DATE(a.check_in) <= ?
			ORDER BY a.check_in ASC""";

	private final JdbcTemplate jdbcTemplate;
	private final LegacyAttendanceCalendar calendar;
	private final LegacyWeeklyRestCredit weeklyRestCredit;
	private final LegacyAttendanceWorkedMinutes workedMinutes;

	/** For the monthly wrapper's bounds, which are a fiscal period rather than a calendar month. */
	private final com.workin.legacy.payroll.LegacyPayrollFiscalSettings fiscalSettings;

	public LegacyAttendanceRangeCalendar(
			DataSource legacyDataSource, LegacyAttendanceCalendar calendar,
			LegacyWeeklyRestCredit weeklyRestCredit, LegacyAttendanceWorkedMinutes workedMinutes,
			com.workin.legacy.payroll.LegacyPayrollFiscalSettings fiscalSettings) {
		this.jdbcTemplate = new JdbcTemplate(legacyDataSource);
		this.calendar = calendar;
		this.weeklyRestCredit = weeklyRestCredit;
		this.workedMinutes = workedMinutes;
		this.fiscalSettings = fiscalSettings;
	}

	private record AttendanceRow(
			long id, String checkIn, String checkOut, Object exceptionTypeId, String exceptionTypeName,
			int durationMinutes) {
	}

	/**
	 * {@code attendance_build_employee_range_calendar()}
	 * ({@code attendance_calendar_helper.php:217-423}). Empty on an unparseable
	 * bound or an inverted range; when {@code capAtToday} is true (list.php's
	 * {@code fill_days} mode), {@code to} is clamped to today first.
	 */
	public List<Map<String, Object>> buildEmployeeRangeCalendar(
			long companyId, long employeeId, String fromRaw, String toRaw, boolean capAtToday,
			String weeklyRestLabel, LocalDate today) {
		var fromParsed = LegacyPhpStrtotime.dateOf(fromRaw, today);
		var toParsed = LegacyPhpStrtotime.dateOf(toRaw, today);
		if (fromParsed == null || toParsed == null) {
			return List.of();
		}
		String from = fromParsed.toString();
		String to = toParsed.toString();
		if (to.compareTo(from) < 0) {
			return List.of();
		}
		if (capAtToday) {
			String todayStr = today.toString();
			if (to.compareTo(todayStr) > 0) {
				to = todayStr;
			}
			if (to.compareTo(from) < 0) {
				return List.of();
			}
		}

		Map<String, AttendanceRow> byDate = new LinkedHashMap<>();
		for (AttendanceRow row : jdbcTemplate.query(
				ATTENDANCE_IN_RANGE,
				(rs, index) -> new AttendanceRow(
						rs.getLong("id"), rs.getString("check_in"), rs.getString("check_out"),
						rs.getObject("exception_type_id"), rs.getString("exception_type_name"),
						rs.getInt("duration_minutes")),
				employeeId, from, to)) {
			String dateKey = row.checkIn().length() >= 10 ? row.checkIn().substring(0, 10) : row.checkIn();
			// Later rows for the same date overwrite earlier ones, PHP's own
			// last-write-wins $by_date[$date_key] = $row assignment.
			byDate.put(dateKey, row);
		}

		String lookbackFrom = LocalDate.parse(from).minusDays(7).toString();
		Map<String, String> holidayByDate = calendar.holidaysByDate(companyId, lookbackFrom, to);
		Map<String, LegacyWeeklyRestCredit.AttendanceFlag> attendanceFlags =
				weeklyRestCredit.attendanceFlagsInRange(companyId, employeeId, from, to);

		List<Map<String, Object>> days = new ArrayList<>();
		LocalDate start = LocalDate.parse(from);
		LocalDate end = LocalDate.parse(to);
		for (LocalDate day = start; !day.isAfter(end); day = day.plusDays(1)) {
			days.add(buildDay(
					companyId, employeeId, day.toString(), byDate, holidayByDate, attendanceFlags, weeklyRestLabel,
					today));
		}
		return days;
	}

	/**
	 * {@code attendance_build_employee_monthly_calendar()}: a thin
	 * fiscal-period-bounds wrapper.
	 *
	 * <p>It used to be a <em>calendar</em>-month wrapper -- {@code
	 * sprintf('%04d-%02d-01')} to {@code date('Y-m-t')}. hr-legacy changed it
	 * to {@code payroll_fiscal_period_bounds()}, so a company whose month runs
	 * 26th-to-25th now gets a calendar covering exactly that window rather
	 * than the 1st to the 31st. For a company on the default 1st-to-last-day
	 * settings the two are identical, which is why the change is easy to miss
	 * and worth stating: {@code month} and {@code year} label a fiscal period
	 * here, not a calendar month.
	 */
	public List<Map<String, Object>> buildEmployeeMonthlyCalendar(
			long companyId, long employeeId, int month, int year, String weeklyRestLabel, LocalDate today) {
		String[] bounds = fiscalSettings.fiscalPeriodBounds(companyId, year, month);
		return buildEmployeeRangeCalendar(
				companyId, employeeId, bounds[0], bounds[1], false, weeklyRestLabel, today);
	}

	private Map<String, Object> buildDay(
			long companyId, long employeeId, String dateStr, Map<String, AttendanceRow> byDate,
			Map<String, String> holidayByDate, Map<String, LegacyWeeklyRestCredit.AttendanceFlag> attendanceFlags,
			String weeklyRestLabel, LocalDate today) {
		Map<String, Object> shift = calendar.shiftForEmployeeOnDate(employeeId, dateStr);
		String scheduleException = calendar.exceptionForDay(companyId, dateStr, shift, holidayByDate, weeklyRestLabel);
		boolean isRestOrHoliday = scheduleException != null && !scheduleException.trim().isEmpty();
		boolean isWeeklyRest = isRestOrHoliday && !holidayByDate.containsKey(dateStr);
		boolean isOfficialHoliday = holidayByDate.containsKey(dateStr);
		String weeklyRestCreditStatus = isWeeklyRest
				? weeklyRestCredit.creditStatus(
						companyId, employeeId, dateStr, attendanceFlags, holidayByDate, today.toString())
				: null;
		boolean isWeeklyRestVoid = LegacyWeeklyRestCredit.VOID.equals(weeklyRestCreditStatus);

		AttendanceRow att = byDate.get(dateStr);
		if (att != null) {
			String exceptionName = att.exceptionTypeName() == null ? "" : att.exceptionTypeName().trim();
			if (exceptionName.isEmpty() && isRestOrHoliday) {
				exceptionName = scheduleException.trim();
			}
			boolean isExceptionOnly = LegacyAttendanceSessions.isExceptionOnlyRow(
					att.checkIn(), att.checkOut(), att.exceptionTypeId());

			if (isExceptionOnly) {
				LegacyAttendanceWorkedMinutes.TimedRequest timed =
						workedMinutes.approvedTimedRequestForDay(employeeId, dateStr);
				int duration = timed == null ? 0 : workedMinutes.timedRequestWorkedMinutes(
						companyId, employeeId, dateStr, att.checkIn(), att.checkOut(), timed, weeklyRestLabel);
				return dayRow(
						dateStr, att.id(), null, null, duration, 0, att.exceptionTypeId(),
						exceptionName.isEmpty() ? null : exceptionName, false, isWeeklyRest, isWeeklyRestVoid,
						weeklyRestCreditStatus, isOfficialHoliday);
			}

			LegacyAttendanceCalendar.DayExpectation expected =
					calendar.expectedForDay(companyId, employeeId, dateStr, weeklyRestLabel);
			int duration = workedMinutes.rowWorkedMinutes(
					companyId, employeeId, dateStr, att.checkIn(), att.checkOut(), att.durationMinutes(),
					att.exceptionTypeId(), weeklyRestLabel);
			return dayRow(
					dateStr, att.id(), att.checkIn(), att.checkOut(), duration, expected.expectedMinutes(),
					att.exceptionTypeId(), exceptionName.isEmpty() ? null : exceptionName, false, isWeeklyRest,
					isWeeklyRestVoid, weeklyRestCreditStatus, isOfficialHoliday);
		}

		if (isRestOrHoliday) {
			return dayRow(
					dateStr, syntheticRowId(employeeId, dateStr), null, null, 0, 0, null,
					scheduleException.trim(), false, isWeeklyRest, isWeeklyRestVoid, weeklyRestCreditStatus,
					isOfficialHoliday);
		}

		LegacyAttendanceWorkedMinutes.TimedRequest timedMissing =
				workedMinutes.approvedTimedRequestForDay(employeeId, dateStr);
		int missingDuration = timedMissing == null ? 0 : workedMinutes.timedRequestWorkedMinutes(
				companyId, employeeId, dateStr, null, null, timedMissing, weeklyRestLabel);
		Map<String, Object> row = dayRow(
				dateStr, syntheticRowId(employeeId, dateStr), null, null, missingDuration, 0, null, null,
				missingDuration <= 0, false, false, null, false);
		return row;
	}

	private static Map<String, Object> dayRow(
			String date, long id, String checkIn, String checkOut, int durationMinutes, int expectedDurationMinutes,
			Object exceptionTypeId, String exceptionTypeName, boolean isMissing, boolean isWeeklyRest,
			boolean isWeeklyRestVoid, String weeklyRestCredit, boolean isOfficialHoliday) {
		Map<String, Object> row = new LinkedHashMap<>();
		row.put("date", date);
		row.put("attendance_id", id > 0 ? id : null);
		row.put("id", id);
		row.put("check_in", checkIn);
		row.put("check_out", checkOut);
		row.put("duration_minutes", durationMinutes);
		row.put("expected_duration_minutes", expectedDurationMinutes);
		row.put("exception_type_id", exceptionTypeId);
		row.put("exception_type_name", exceptionTypeName);
		row.put("is_missing", isMissing);
		row.put("is_weekly_rest", isWeeklyRest);
		row.put("is_weekly_rest_void", isWeeklyRestVoid);
		row.put("weekly_rest_credit", weeklyRestCredit);
		row.put("is_official_holiday", isOfficialHoliday);
		return row;
	}

	/** {@code attendance_synthetic_row_id()}: a stable negative id for a day with no real attendance row. */
	static long syntheticRowId(long employeeId, String dateYmd) {
		long compact = Long.parseLong(dateYmd.replace("-", ""));
		return -1 * ((employeeId * 100000000L) + (compact % 100000000L));
	}

}

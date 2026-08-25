package com.workin.legacy.attendance.calendar;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.workin.legacy.attendance.session.LegacyAttendanceSessions;
import com.workin.legacy.attendance.spreadsheet.LegacyAttendanceAnalyzer;

/**
 * {@code attendance_employee_period_stats()}
 * ({@code attendance_calendar_helper.php:700-852}) -- {@code stats.php}'s
 * per-employee branch: a day-by-day walk of the range classifying every day
 * as present, leave (exception, official holiday, earned weekly rest or
 * approved leave), a void/pending weekly rest (counted as neither), or an
 * absence by elimination.
 */
@Component
public class LegacyAttendancePeriodStats {

	private static final String ATTENDANCE_IN_RANGE = """
			SELECT a.check_in, a.check_out, a.exception_type_id,
				TIMESTAMPDIFF(MINUTE, a.check_in, a.check_out) AS duration_minutes
			FROM attendance a
			WHERE a.employee_id = ?
			  AND DATE(a.check_in) >= ?
			  AND DATE(a.check_in) <= ?
			ORDER BY a.check_in ASC""";

	private final JdbcTemplate jdbcTemplate;
	private final LegacyAttendanceCalendar calendar;
	private final LegacyWeeklyRestCredit weeklyRestCredit;
	private final LegacyAttendanceWorkedMinutes workedMinutes;

	public LegacyAttendancePeriodStats(
			DataSource legacyDataSource, LegacyAttendanceCalendar calendar,
			LegacyWeeklyRestCredit weeklyRestCredit, LegacyAttendanceWorkedMinutes workedMinutes) {
		this.jdbcTemplate = new JdbcTemplate(legacyDataSource);
		this.calendar = calendar;
		this.weeklyRestCredit = weeklyRestCredit;
		this.workedMinutes = workedMinutes;
	}

	public record PeriodStats(
			int totalDaysInMonth, int presentDays, int leaveDays, int officialHolidayDays, int absentDays,
			int totalDurationMinutes, int overtimeMinutes) {
	}

	private record DayRow(String checkIn, String checkOut, Object exceptionTypeId, int durationMinutes) {
	}

	private record ByDate(int minutes, boolean hasPunch, boolean isExceptionOnly) {
	}

	public PeriodStats employeePeriodStats(
			long companyId, long employeeId, String from, String to, String weeklyRestLabel, LocalDate today) {
		Map<String, ByDate> byDate = new LinkedHashMap<>();
		for (DayRow row : jdbcTemplate.query(
				ATTENDANCE_IN_RANGE,
				(rs, index) -> new DayRow(
						rs.getString("check_in"), rs.getString("check_out"), rs.getObject("exception_type_id"),
						rs.getInt("duration_minutes")),
				employeeId, from, to)) {
			if (row.checkIn() == null || row.checkIn().isEmpty()) {
				continue;
			}
			String checkOut = row.checkOut() != null && !row.checkOut().trim().isEmpty() ? row.checkOut() : null;
			String dateKey = row.checkIn().length() >= 10 ? row.checkIn().substring(0, 10) : row.checkIn();
			boolean isExceptionOnly = LegacyAttendanceSessions.isExceptionOnlyRow(
					row.checkIn(), checkOut, row.exceptionTypeId());
			int raw = checkOut != null ? Math.max(0, row.durationMinutes()) : 0;
			int minutes = workedMinutes.rowWorkedMinutes(
					companyId, employeeId, dateKey, row.checkIn(), checkOut, raw, row.exceptionTypeId(),
					weeklyRestLabel);
			isExceptionOnly = isExceptionOnly && minutes <= 0;
			// Later rows for the same date overwrite earlier ones, PHP's own
			// last-write-wins $by_date[$date_key] = [...] assignment.
			byDate.put(dateKey, new ByDate(minutes, !isExceptionOnly, isExceptionOnly));
		}

		Map<String, String> holidayByDate = calendar.holidaysByDate(companyId, from, to);
		String todayStr = today.toString();
		LocalDate start = LocalDate.parse(from);
		LocalDate end = LocalDate.parse(to);

		int presentDays = 0;
		int leaveDays = 0;
		int officialHolidayDays = 0;
		int skippedRestDays = 0;
		int totalDurationMinutes = 0;
		int overtimeMinutes = 0;

		for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
			String dateStr = d.toString();
			if (dateStr.compareTo(todayStr) > 0) {
				continue;
			}

			ByDate day = byDate.getOrDefault(dateStr, new ByDate(0, false, false));
			LegacyAttendanceCalendar.DayExpectation expected =
					calendar.expectedForDay(companyId, employeeId, dateStr, weeklyRestLabel);
			boolean isOfficialHoliday = holidayByDate.containsKey(dateStr);
			boolean isRestDay = expected.restDay();

			if (day.hasPunch()) {
				presentDays++;
				totalDurationMinutes += day.minutes();
				LegacyAttendanceAnalyzer.Classification classify =
						LegacyAttendanceAnalyzer.classifyDay(day.minutes(), expected.expectedMinutes(), true);
				overtimeMinutes += classify.overtimeMinutes();
				continue;
			}

			if (day.isExceptionOnly()) {
				leaveDays++;
				continue;
			}

			if (isOfficialHoliday) {
				officialHolidayDays++;
				leaveDays++;
				continue;
			}

			if (isRestDay) {
				String credit = weeklyRestCredit.creditStatus(
						companyId, employeeId, dateStr, restAttendanceFlags(byDate), holidayByDate, todayStr);
				if (LegacyWeeklyRestCredit.EARNED.equals(credit)) {
					leaveDays++;
				} else {
					skippedRestDays++;
				}
				continue;
			}

			if (weeklyRestCredit.isOnApprovedLeave(employeeId, dateStr)) {
				leaveDays++;
			}
			// else: a scheduled workday with no punch/leave/holiday -- an
			// absence, counted below by elimination.
		}

		int totalDaysInMonth = java.time.YearMonth.of(start.getYear(), start.getMonthValue()).lengthOfMonth();
		int elapsedDays = 0;
		for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
			if (d.toString().compareTo(todayStr) <= 0) {
				elapsedDays++;
			}
		}

		return new PeriodStats(
				totalDaysInMonth, presentDays, leaveDays, officialHolidayDays,
				Math.max(0, elapsedDays - presentDays - leaveDays - skippedRestDays), totalDurationMinutes,
				Math.max(0, overtimeMinutes));
	}

	/** {@code $by_date} narrowed to {@link LegacyWeeklyRestCredit}'s own flag shape. */
	private static Map<String, LegacyWeeklyRestCredit.AttendanceFlag> restAttendanceFlags(Map<String, ByDate> byDate) {
		Map<String, LegacyWeeklyRestCredit.AttendanceFlag> flags = new LinkedHashMap<>();
		byDate.forEach((date, day) -> flags.put(
				date, new LegacyWeeklyRestCredit.AttendanceFlag(day.hasPunch(), day.isExceptionOnly())));
		return flags;
	}

}

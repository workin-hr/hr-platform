package com.workin.legacy.attendance.calendar;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

	private final LegacyAttendanceCalendar calendar;
	private final LegacyWeeklyRestCredit weeklyRestCredit;
	private final LegacyAttendanceWorkedMinutes workedMinutes;
	private final LegacyAttendanceRangeRows rangeRows;

	/** For the monthly wrapper's bounds, which are a fiscal period rather than a calendar month. */
	private final com.workin.legacy.payroll.LegacyPayrollFiscalSettings fiscalSettings;

	public LegacyAttendanceRangeCalendar(
			LegacyAttendanceCalendar calendar, LegacyWeeklyRestCredit weeklyRestCredit,
			LegacyAttendanceWorkedMinutes workedMinutes, LegacyAttendanceRangeRows rangeRows,
			com.workin.legacy.payroll.LegacyPayrollFiscalSettings fiscalSettings) {
		this.calendar = calendar;
		this.weeklyRestCredit = weeklyRestCredit;
		this.workedMinutes = workedMinutes;
		this.rangeRows = rangeRows;
		this.fiscalSettings = fiscalSettings;
	}

	/** Receives one employee's calendar, by the employee's position in the list asked for. */
	@FunctionalInterface
	public interface EmployeeCalendar {
		void accept(int index, List<Map<String, Object>> days);
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
		List<List<Map<String, Object>>> built = new ArrayList<>(1);
		forEachEmployeeRangeCalendar(companyId, List.of(employeeId), fromRaw, toRaw, capAtToday, weeklyRestLabel,
				today, (index, days) -> built.add(days));
		return built.get(0);
	}

	/**
	 * {@link #buildEmployeeRangeCalendar} for a whole roster, with the reads
	 * made once for all of them (D-292).
	 *
	 * <p>Legacy builds each employee's calendar with its own reads, and the
	 * per-day rules inside it read again per day: the fingerprints export over a
	 * quarter for 500 employees was 26 s a month locally, and every one of those
	 * seconds was statements. Here the roster's attendance is one statement per
	 * {@link LegacyIdBatches batch} and every per-day answer is warmed by
	 * {@link LegacyAttendanceCalendar#warmReportRange}, so the count does not
	 * depend on the roster or the range. The per-day rules themselves are
	 * unchanged and run in the same order.
	 *
	 * <p>Each employee's calendar is handed to {@code sink} as soon as it is
	 * built, in the order of {@code employeeIds}, so a caller that writes it out
	 * does not hold every employee's days at once.
	 */
	public void forEachEmployeeRangeCalendar(
			long companyId, List<Long> employeeIds, String fromRaw, String toRaw, boolean capAtToday,
			String weeklyRestLabel, LocalDate today, EmployeeCalendar sink) {
		if (employeeIds.isEmpty()) {
			return;
		}
		String[] range = range(fromRaw, toRaw, capAtToday, today);
		if (range == null) {
			for (int index = 0; index < employeeIds.size(); index++) {
				sink.accept(index, List.of());
			}
			return;
		}
		String from = range[0];
		String to = range[1];
		String lookbackFrom = LocalDate.parse(from).minusDays(7).toString();

		calendar.warmReportRange(companyId, employeeIds, from, to);
		// The widest window any of the reads below used: the weekly-rest flags
		// look back seven days before `from`, the day rows start at `from`.
		Map<Long, List<LegacyAttendanceRangeRows.Row>> rowsByEmployee =
				rangeRows.byEmployee(employeeIds, lookbackFrom, to);
		Map<String, String> holidayByDate = calendar.holidaysByDate(companyId, lookbackFrom, to);

		LocalDate start = LocalDate.parse(from);
		LocalDate end = LocalDate.parse(to);
		for (int index = 0; index < employeeIds.size(); index++) {
			long employeeId = employeeIds.get(index);
			List<LegacyAttendanceRangeRows.Row> rows = rowsByEmployee.getOrDefault(employeeId, List.of());
			Map<String, LegacyAttendanceRangeRows.Row> byDate = new LinkedHashMap<>();
			for (LegacyAttendanceRangeRows.Row row : LegacyAttendanceRangeRows.between(rows, from, to)) {
				// Later rows for the same date overwrite earlier ones, PHP's own
				// last-write-wins $by_date[$date_key] = $row assignment.
				byDate.put(row.dateKey(), row);
			}
			Map<String, LegacyWeeklyRestCredit.AttendanceFlag> attendanceFlags =
					LegacyWeeklyRestCredit.attendanceFlags(rows);

			List<Map<String, Object>> days = new ArrayList<>();
			for (LocalDate day = start; !day.isAfter(end); day = day.plusDays(1)) {
				days.add(buildDay(
						companyId, employeeId, day.toString(), byDate, holidayByDate, attendanceFlags,
						weeklyRestLabel, today));
			}
			sink.accept(index, days);
		}
	}

	/** The parsed, clamped {@code [from, to]}, or null when the calendar is empty. */
	private static String[] range(String fromRaw, String toRaw, boolean capAtToday, LocalDate today) {
		var fromParsed = LegacyPhpStrtotime.dateOf(fromRaw, today);
		var toParsed = LegacyPhpStrtotime.dateOf(toRaw, today);
		if (fromParsed == null || toParsed == null) {
			return null;
		}
		String from = fromParsed.toString();
		String to = toParsed.toString();
		if (to.compareTo(from) < 0) {
			return null;
		}
		if (capAtToday) {
			String todayStr = today.toString();
			if (to.compareTo(todayStr) > 0) {
				to = todayStr;
			}
			if (to.compareTo(from) < 0) {
				return null;
			}
		}
		return new String[] { from, to };
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
			long companyId, long employeeId, String dateStr, Map<String, LegacyAttendanceRangeRows.Row> byDate,
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

		LegacyAttendanceRangeRows.Row att = byDate.get(dateStr);
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

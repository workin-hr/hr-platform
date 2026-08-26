package com.workin.legacy.payroll;

import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.workin.legacy.LegacyValues;
import com.workin.legacy.attendance.LegacyWeeklyOffDays;
import com.workin.legacy.attendance.calendar.LegacyAttendanceCalendar;
import com.workin.legacy.attendance.calendar.LegacyAttendanceWorkedMinutes;
import com.workin.legacy.attendance.calendar.LegacyWeeklyRestCredit;

/**
 * {@code payroll_attendance_summary()}, {@code payroll_expected_work_days_until()}
 * and {@code payroll_payslip_attendance_display()}
 * ({@code payroll_calculation.php:151-233,672-370}), reusing the Wave 12.6.4b
 * calendar primitives ({@link LegacyAttendanceWorkedMinutes},
 * {@link LegacyWeeklyRestCredit}, {@link LegacyAttendanceCalendar}) that
 * already carry the exact same day-classification rules.
 *
 * <h2>Two distinct, deliberately different shift-resolution strategies</h2>
 * <p>{@code payroll_period_working_days()} resolves the employee's shift
 * <b>once</b>, from {@code from} falling back to {@code to}, and reuses it
 * for every day in the range. {@code weekly_rest_dates_by_status_in_range()}
 * resolves the shift <b>fresh on every date</b>. These are not the same
 * function reproduced twice -- they are measured as genuinely different in
 * the vendored PHP, and this class keeps them apart rather than picking one
 * strategy for both.
 *
 * <h2>Two distinct, deliberately different weekly-rest-day matchers</h2>
 * <p>{@code payroll_is_weekly_rest_day()} (via {@link LegacyWeeklyOffDays})
 * matches English/abbreviated/numeric <b>and Arabic</b> day names.
 * {@code official_holidays_is_weekly_rest_day()} matches only
 * English/abbreviated/numeric -- no Arabic table at all. This is a real,
 * measured divergence between two independently-written legacy helpers
 * (the same class of issue {@code PayrollCalculationService}'s own javadoc
 * cites as hr-legacy#12/#13), not a typo to be silently unified.
 */
@Component
public class LegacyPayrollAttendanceFigures {

	/** {@code WEEKLY_REST_MIN_COVERED_WORKDAYS} ({@code weekly_rest_credit_helper.php}). */
	private static final int MIN_COVERED_WORKDAYS = 3;

	private static final String ATTENDANCE_IN_RANGE = """
			SELECT check_in, check_out, exception_type_id,
			  TIMESTAMPDIFF(MINUTE, check_in, check_out) AS duration_minutes
			FROM attendance
			WHERE employee_id = ? AND DATE(check_in) BETWEEN ? AND ?
			ORDER BY check_in ASC""";

	private static final String APPROVED_LEAVE_DAYS = """
			SELECT COALESCE(SUM(DATEDIFF(LEAST(r.to_date, ?), GREATEST(r.from_date, ?)) + 1), 0)
			FROM requests r
			INNER JOIN request_types t ON t.id = r.request_type_id
			WHERE r.employee_id = ? AND r.status = 'approved' AND t.counts_as_paid_leave = 1
			  AND r.from_date <= ? AND r.to_date >= ?""";

	private static final String HOLIDAY_DATES_IN_RANGE = """
			SELECT holiday_date FROM company_official_holidays
			WHERE company_id = ? AND holiday_date BETWEEN ? AND ? ORDER BY holiday_date ASC""";

	private static final String PRESENT_DATES = """
			SELECT DISTINCT DATE(check_in) AS d FROM attendance
			WHERE employee_id = ? AND DATE(check_in) BETWEEN ? AND ?""";

	/** {@code attendance_present_details_for_period()}'s own query, verbatim. */
	private static final String PRESENT_DETAILS = """
			SELECT
			  DATE(a.check_in) AS present_date,
			  MAX(a.exception_type_id) AS exception_type_id,
			  MAX(et.name) AS exception_name,
			  MAX(CASE WHEN a.check_out IS NOT NULL OR (a.exception_type_id IS NULL) THEN 1 ELSE 0 END) AS has_punch
			FROM attendance AS a
			LEFT JOIN exception_types AS et ON et.id = a.exception_type_id
			WHERE a.employee_id = ? AND DATE(a.check_in) BETWEEN ? AND ?
			GROUP BY DATE(a.check_in)
			ORDER BY present_date ASC""";

	/**
	 * {@code payroll_employee_work_hours_per_day()} ({@code payroll_calculation.php:743-757}):
	 * employee override, then job title, then 8h -- {@code NULLIF(x, 0)} at
	 * each step so a stored zero falls through rather than winning. Same
	 * query {@link LegacyAttendanceCalendar}'s own {@code FALLBACK_HOURS}
	 * uses internally; duplicated here rather than exposed across modules,
	 * matching this codebase's per-reader-bound convention (D-091 and its
	 * extensions).
	 */
	private static final String WORK_HOURS_PER_DAY = """
			SELECT COALESCE(NULLIF(e.expected_daily_hours, 0), NULLIF(jt.work_hours, 0), 8)
			FROM employees e
			LEFT JOIN job_titles jt ON jt.id = e.job_title_id
			WHERE e.id = ?""";

	private static final Map<String, Integer> DAY_NAMES_NO_ARABIC = Map.ofEntries(
			Map.entry("sunday", 0), Map.entry("monday", 1), Map.entry("tuesday", 2), Map.entry("wednesday", 3),
			Map.entry("thursday", 4), Map.entry("friday", 5), Map.entry("saturday", 6),
			Map.entry("sun", 0), Map.entry("mon", 1), Map.entry("tue", 2), Map.entry("wed", 3),
			Map.entry("thu", 4), Map.entry("fri", 5), Map.entry("sat", 6));

	private final JdbcTemplate jdbcTemplate;
	private final LegacyAttendanceCalendar calendar;
	private final LegacyWeeklyRestCredit weeklyRestCredit;
	private final LegacyAttendanceWorkedMinutes workedMinutes;
	private final LegacyWeeklyOffDays weeklyOffDays;

	public LegacyPayrollAttendanceFigures(
			DataSource legacyDataSource, LegacyAttendanceCalendar calendar,
			LegacyWeeklyRestCredit weeklyRestCredit, LegacyAttendanceWorkedMinutes workedMinutes,
			LegacyWeeklyOffDays weeklyOffDays) {
		this.jdbcTemplate = new JdbcTemplate(legacyDataSource);
		this.calendar = calendar;
		this.weeklyRestCredit = weeklyRestCredit;
		this.workedMinutes = workedMinutes;
		this.weeklyOffDays = weeklyOffDays;
	}

	/** {@code payroll_attendance_summary()} ({@code payroll_calculation.php:672-735}). */
	public record AttendanceSummary(int daysPresent, int daysException, double totalHours) {
	}

	private record AttendanceRow(String checkIn, String checkOut, Object exceptionTypeId, int durationMinutes) {
	}

	private record ByDate(int minutes, boolean isException) {
	}

	public AttendanceSummary attendanceSummary(
			long companyId, long employeeId, String from, String to, String weeklyRestLabel) {
		Map<String, ByDate> byDate = new LinkedHashMap<>();
		for (AttendanceRow row : jdbcTemplate.query(ATTENDANCE_IN_RANGE, this::mapRow, employeeId, from, to)) {
			if (row.checkIn() == null || row.checkIn().isEmpty()) {
				continue;
			}
			String checkOut = row.checkOut() != null && !row.checkOut().trim().isEmpty() ? row.checkOut() : null;
			String dateKey = row.checkIn().length() >= 10 ? row.checkIn().substring(0, 10) : row.checkIn();
			int raw = checkOut != null ? Math.max(0, row.durationMinutes()) : 0;
			int minutes = companyId > 0
					? workedMinutes.rowWorkedMinutes(companyId, employeeId, dateKey, row.checkIn(), checkOut, raw,
							row.exceptionTypeId(), weeklyRestLabel)
					: (checkOut != null ? raw : 0);
			boolean isException = row.exceptionTypeId() != null && LegacyValues.toPhpLong(row.exceptionTypeId()) > 0;
			byDate.put(dateKey, new ByDate(minutes, isException));
		}

		int daysException = 0;
		long totalMinutes = 0;
		for (ByDate day : byDate.values()) {
			if (day.isException()) {
				daysException++;
			}
			totalMinutes += day.minutes();
		}
		return new AttendanceSummary(byDate.size(), daysException, round4(totalMinutes / 60.0));
	}

	private AttendanceRow mapRow(ResultSet rs, int rowNum) throws java.sql.SQLException {
		return new AttendanceRow(
				rs.getString("check_in"), rs.getString("check_out"), rs.getObject("exception_type_id"),
				rs.getInt("duration_minutes"));
	}

	/**
	 * {@code payroll_period_working_days()}'s employee-aware branch
	 * ({@code payroll_calculation.php:151-186}): the shift is resolved
	 * <b>once</b> here -- deliberately not per day, see the class javadoc.
	 */
	private int periodWorkingDays(long companyId, long employeeId, String from, String to) {
		LocalDate start = LocalDate.parse(from);
		LocalDate end = LocalDate.parse(to);
		if (end.isBefore(start)) {
			return 0;
		}
		Map<String, Object> shift = calendar.shiftForEmployeeOnDate(employeeId, from);
		if (shift == null) {
			shift = calendar.shiftForEmployeeOnDate(employeeId, to);
		}
		Map<String, Object> resolvedShift = shift;
		List<String> rest = weeklyOffDays.forCompany(companyId);
		int count = 0;
		for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
			String dateStr = d.toString();
			boolean isRest = resolvedShift != null
					? calendar.isWeeklyRestDay(companyId, dateStr, resolvedShift)
					: LegacyWeeklyOffDays.isWeeklyRestDay(dayOfWeek(d), rest);
			if (!isRest) {
				count++;
			}
		}
		return Math.max(0, count);
	}

	/**
	 * {@code payroll_expected_work_days_until()} ({@code payroll_calculation.php:188-212}):
	 * working days minus official holidays that land on a working day, capped
	 * at {@code periodTo} and never before {@code from}.
	 */
	public int expectedWorkDaysUntil(long companyId, long employeeId, String from, String periodTo, String until) {
		if (from.isEmpty() || periodTo.isEmpty()) {
			return 0;
		}
		String endCap = until.compareTo(periodTo) > 0 ? periodTo : until;
		if (endCap.compareTo(from) < 0) {
			return 0;
		}
		int working = periodWorkingDays(companyId, employeeId, from, endCap);
		int holidays = officialHolidaysWorkingDaysInRange(companyId, from, endCap);
		return Math.max(0, working - holidays);
	}

	/**
	 * {@code official_holidays_working_days_in_range()}
	 * ({@code official_holidays_helper.php:112-124}): a company-wide check --
	 * no shift, no employee -- using {@code official_holidays_is_weekly_rest_day()}'s
	 * narrower (no-Arabic) matcher.
	 */
	private int officialHolidaysWorkingDaysInRange(long companyId, String from, String to) {
		List<String> rest = weeklyOffDays.forCompany(companyId);
		List<String> dates = jdbcTemplate.queryForList(HOLIDAY_DATES_IN_RANGE, String.class, companyId, from, to);
		int count = 0;
		for (String date : dates) {
			if (date == null || date.isEmpty()) {
				continue;
			}
			if (!isWeeklyRestDayNoArabic(dayOfWeek(LocalDate.parse(date)), rest)) {
				count++;
			}
		}
		return count;
	}

	/** {@code payroll_expected_work_days()} ({@code payroll_calculation.php:214-217}). */
	private int expectedWorkDays(long companyId, long employeeId, String from, String to) {
		return Math.max(0, expectedWorkDaysUntil(companyId, employeeId, from, to, to));
	}

	public record AttendanceDisplay(
			int expectedWorkDays, int expectedWorkDaysDue, int daysAbsent, int daysLeave,
			int officialHolidayDays, int earnedWeeklyRestDays, int voidWeeklyRestDays) {
	}

	/** {@code payroll_payslip_attendance_display()} ({@code payroll_calculation.php:292-370}). */
	public AttendanceDisplay attendanceDisplay(
			long companyId, long employeeId, String periodFrom, String periodTo, int daysPresent, String asOf) {
		boolean inProgress = asOf.compareTo(periodTo) < 0;
		String rangeTo = inProgress ? asOf : periodTo;

		int expectedFull = expectedWorkDays(companyId, employeeId, periodFrom, periodTo);
		int expectedDue = expectedWorkDaysUntil(companyId, employeeId, periodFrom, periodTo, asOf);

		int daysLeave = approvedLeaveDays(employeeId, periodFrom, rangeTo);
		int holidayCredit = officialHolidaysWorkingCreditForEmployee(companyId, employeeId, periodFrom, rangeTo);
		if (daysPresent < MIN_COVERED_WORKDAYS) {
			holidayCredit = 0;
		}

		int dueCap = inProgress ? Math.max(0, expectedDue) : Math.max(0, expectedFull);
		int paidDue = Math.min(Math.max(0, dueCap), Math.max(0, daysPresent) + daysLeave + holidayCredit);
		int workdayAbsent = Math.max(0, dueCap - paidDue);

		String lookbackFrom = LocalDate.parse(periodFrom).minusDays(7).toString();
		Map<String, LegacyWeeklyRestCredit.AttendanceFlag> attFlags =
				weeklyRestCredit.attendanceFlagsInRange(companyId, employeeId, periodFrom, rangeTo);
		Map<String, String> holidayByDate = calendar.holidaysByDate(companyId, lookbackFrom, rangeTo);

		int voidWeeklyRestDays = weeklyRestDatesByStatus(
				companyId, employeeId, periodFrom, rangeTo, LegacyWeeklyRestCredit.VOID, attFlags, holidayByDate, asOf)
				.size();
		int earnedWeeklyRestDays = weeklyRestDatesByStatus(
				companyId, employeeId, periodFrom, rangeTo, LegacyWeeklyRestCredit.EARNED, attFlags, holidayByDate, asOf)
				.size();

		int daysAbsent = workdayAbsent + Math.max(0, voidWeeklyRestDays);

		return new AttendanceDisplay(
				expectedFull, expectedDue, daysAbsent, daysLeave,
				(int) Math.round(holidayCredit), earnedWeeklyRestDays, voidWeeklyRestDays);
	}

	/**
	 * {@code weekly_rest_dates_by_status_in_range()} ({@code weekly_rest_credit_helper.php:225-260}):
	 * the shift is resolved <b>fresh for every date</b> here -- deliberately
	 * unlike {@link #periodWorkingDays}, see the class javadoc.
	 * {@code weekly_rest_count_by_status_in_range()} is just this list's size.
	 */
	private List<String> weeklyRestDatesByStatus(
			long companyId, long employeeId, String from, String to, String status,
			Map<String, LegacyWeeklyRestCredit.AttendanceFlag> attendanceByDate,
			Map<String, String> holidayByDate, String asOf) {
		LocalDate start = LocalDate.parse(from);
		LocalDate end = LocalDate.parse(to);
		if (end.isBefore(start)) {
			return List.of();
		}
		List<String> dates = new java.util.ArrayList<>();
		for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
			String dateStr = d.toString();
			if (dateStr.compareTo(asOf) > 0) {
				continue;
			}
			if (holidayByDate.containsKey(dateStr)) {
				continue;
			}
			Map<String, Object> shift = calendar.shiftForEmployeeOnDate(employeeId, dateStr);
			if (!calendar.isWeeklyRestDay(companyId, dateStr, shift)) {
				continue;
			}
			String creditStatus = weeklyRestCredit.creditStatus(
					companyId, employeeId, dateStr, attendanceByDate, holidayByDate, asOf);
			if (creditStatus.equals(status)) {
				dates.add(dateStr);
			}
		}
		return dates;
	}

	/** {@code payroll_employee_work_hours_per_day()} ({@code payroll_calculation.php:743-757}). */
	public java.math.BigDecimal employeeWorkHoursPerDay(long employeeId) {
		java.math.BigDecimal hours = jdbcTemplate.queryForObject(
				WORK_HOURS_PER_DAY, java.math.BigDecimal.class, employeeId);
		return hours != null && hours.signum() > 0 ? hours : java.math.BigDecimal.valueOf(8);
	}

	/** {@code payroll_approved_leave_days()} ({@code payroll_calculation.php:758-776}). */
	private int approvedLeaveDays(long employeeId, String from, String to) {
		Long days = jdbcTemplate.queryForObject(
				APPROVED_LEAVE_DAYS, Long.class, to, from, employeeId, to, from);
		return days == null ? 0 : (int) Math.max(0, days);
	}

	/**
	 * {@code official_holidays_working_credit_for_employee()}
	 * ({@code official_holidays_helper.php:159-223}): holidays in range the
	 * employee has no punch for, and which are not themselves a weekly rest
	 * day (company-wide only -- no shift, matching
	 * {@link #officialHolidaysWorkingDaysInRange}'s scope).
	 */
	private int officialHolidaysWorkingCreditForEmployee(long companyId, long employeeId, String from, String to) {
		return officialHolidaysWorkingCreditDetails(companyId, employeeId, from, to, "").size();
	}

	/**
	 * {@code official_holidays_working_credit_details_for_employee()}
	 * ({@code official_holidays_helper.php:159-207}): one {@code official_holiday}
	 * detail row per uncovered holiday date, labelled with the holiday's own
	 * name or, when blank, the {@code fallbackLabel} ({@code t('csv_official_holiday_days')}).
	 */
	private List<Map<String, Object>> officialHolidaysWorkingCreditDetails(
			long companyId, long employeeId, String from, String to, String fallbackLabel) {
		Map<String, String> holidayByDate = calendar.holidaysByDate(companyId, from, to);
		if (holidayByDate.isEmpty()) {
			return List.of();
		}
		List<String> presentDates = jdbcTemplate.queryForList(PRESENT_DATES, String.class, employeeId, from, to);
		java.util.Set<String> presentSet = new java.util.HashSet<>(presentDates);
		List<String> rest = weeklyOffDays.forCompany(companyId);

		List<Map<String, Object>> details = new java.util.ArrayList<>();
		for (Map.Entry<String, String> entry : holidayByDate.entrySet()) {
			String date = entry.getKey();
			if (presentSet.contains(date)) {
				continue;
			}
			if (isWeeklyRestDayNoArabic(dayOfWeek(LocalDate.parse(date)), rest)) {
				continue;
			}
			String label = LegacyValues.phpTrim(entry.getValue());
			details.add(Map.of(
					"date", date, "day_type", "official_holiday", "label", label.isEmpty() ? fallbackLabel : label));
		}
		return details;
	}

	/** {@code attendance_present_details_for_period()} ({@code attendance_calendar_helper.php:484-532}). */
	private List<Map<String, Object>> attendancePresentDetails(
			long employeeId, String from, String to, String presentLabel) {
		return jdbcTemplate.query(PRESENT_DETAILS, (rs, rowNum) -> {
			String exceptionName = LegacyValues.phpTrim(rs.getString("exception_name"));
			long exceptionId = rs.getLong("exception_type_id");
			if (exceptionId > 0 && !exceptionName.isEmpty()) {
				return Map.of("date", rs.getString("present_date"), "day_type", "exception", "label", exceptionName);
			}
			return Map.<String, Object>of(
					"date", rs.getString("present_date"), "day_type", "attendance", "label", presentLabel);
		}, employeeId, from, to);
	}

	/**
	 * {@code payroll_payslip_present_details()} ({@code payroll_calculation.php:382-437}):
	 * the day-by-day "present days" hover breakdown -- real punches/exceptions,
	 * earned weekly rest, and (once the employee clears the minimum-coverage
	 * threshold) credited official holidays, sorted by date.
	 */
	public List<Map<String, Object>> presentDetails(
			long companyId, long employeeId, String periodFrom, String periodTo, int punchPresent, String asOf,
			String presentLabel, String weeklyRestLabel, String officialHolidayFallbackLabel) {
		boolean inProgress = asOf.compareTo(periodTo) < 0;
		String rangeTo = inProgress ? asOf : periodTo;

		List<Map<String, Object>> details =
				new java.util.ArrayList<>(attendancePresentDetails(employeeId, periodFrom, rangeTo, presentLabel));

		Map<String, LegacyWeeklyRestCredit.AttendanceFlag> attFlags =
				weeklyRestCredit.attendanceFlagsInRange(companyId, employeeId, periodFrom, rangeTo);
		String lookbackFrom = LocalDate.parse(periodFrom).minusDays(7).toString();
		Map<String, String> holidayByDate = calendar.holidaysByDate(companyId, lookbackFrom, rangeTo);

		for (String date : weeklyRestDatesByStatus(
				companyId, employeeId, periodFrom, rangeTo, LegacyWeeklyRestCredit.EARNED, attFlags, holidayByDate, asOf)) {
			details.add(Map.of("date", date, "day_type", "weekly_rest", "label", weeklyRestLabel));
		}

		if (punchPresent >= MIN_COVERED_WORKDAYS) {
			details.addAll(officialHolidaysWorkingCreditDetails(
					companyId, employeeId, periodFrom, rangeTo, officialHolidayFallbackLabel));
		}

		details.sort(java.util.Comparator.comparing(row -> (String) row.get("date")));
		return details;
	}

	/**
	 * {@code official_holidays_is_weekly_rest_day()}
	 * ({@code official_holidays_helper.php:11-32}): English/abbreviated/numeric
	 * day names only -- no Arabic table, unlike {@link LegacyWeeklyOffDays}.
	 */
	private static boolean isWeeklyRestDayNoArabic(int dayOfWeek, List<String> restValues) {
		if (restValues.isEmpty()) {
			return false;
		}
		for (String raw : restValues) {
			String v = LegacyValues.phpTrim(raw).toLowerCase(Locale.ROOT);
			if (v.isEmpty()) {
				continue;
			}
			if (v.matches("^[+-]?(\\d+(\\.\\d*)?|\\.\\d+)([eE][+-]?\\d+)?$")) {
				double parsed = Double.parseDouble(v);
				if (!Double.isNaN(parsed) && !Double.isInfinite(parsed) && (int) parsed == dayOfWeek) {
					return true;
				}
			}
			Integer mapped = DAY_NAMES_NO_ARABIC.get(v);
			if (mapped != null && mapped == dayOfWeek) {
				return true;
			}
		}
		return false;
	}

	private static int dayOfWeek(LocalDate date) {
		return date.getDayOfWeek().getValue() % 7;
	}

	private static double round4(double value) {
		return Math.round(value * 10000.0) / 10000.0;
	}
}

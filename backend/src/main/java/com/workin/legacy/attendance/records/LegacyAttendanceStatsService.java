package com.workin.legacy.attendance.records;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.workin.legacy.LegacyClock;
import com.workin.legacy.LegacyJdbcValues;
import com.workin.legacy.LegacyQueryParameters;
import com.workin.legacy.LegacyValues;
import com.workin.legacy.attendance.calendar.LegacyAttendanceCalendar;
import com.workin.legacy.attendance.calendar.LegacyWeeklyRestCredit;
import com.workin.legacy.auth.LegacyRequestContext;
import com.workin.legacy.employees.LegacyEmployee;
import com.workin.legacy.wire.LegacyApiException;

/** Frozen {@code attendance/stats.php} plus {@code attendance_employee_period_stats()}. */
@Service
public class LegacyAttendanceStatsService {

	private static final String EMPLOYEE_EXISTS =
			"SELECT id FROM employees WHERE id = ? AND company_id = ?";
	private static final String EMPLOYEE_ROWS = """
			SELECT a.check_in, a.check_out, a.exception_type_id,
			       TIMESTAMPDIFF(MINUTE, a.check_in, a.check_out) AS duration_minutes
			FROM attendance AS a
			WHERE a.employee_id = ?
			  AND DATE(a.check_in) >= ?
			  AND DATE(a.check_in) <= ?
			ORDER BY a.check_in ASC""";
	private static final String HOLIDAY_COUNT = """
			SELECT COUNT(*) FROM company_official_holidays
			WHERE company_id = ? AND holiday_date BETWEEN ? AND ?""";

	private final JdbcTemplate jdbcTemplate;
	private final LegacyAttendanceWorkedMinutes workedMinutes;
	private final LegacyAttendanceCalendar calendar;
	private final LegacyWeeklyRestCredit weeklyRestCredit;
	private final LegacyClock clock;

	public LegacyAttendanceStatsService(DataSource legacyDataSource,
			LegacyAttendanceWorkedMinutes workedMinutes, LegacyAttendanceCalendar calendar,
			LegacyWeeklyRestCredit weeklyRestCredit, LegacyClock clock) {
		this.jdbcTemplate = new JdbcTemplate(legacyDataSource);
		this.workedMinutes = workedMinutes;
		this.calendar = calendar;
		this.weeklyRestCredit = weeklyRestCredit;
		this.clock = clock;
	}

	public Map<String, Object> stats(LegacyRequestContext context, LegacyQueryParameters query,
			String weeklyRestLabel) {
		long companyId = context.companyId();
		long targetEmployeeId = 0;
		if (context.role() == LegacyEmployee.Role.EMPLOYEE) {
			targetEmployeeId = context.employeeId();
		} else if (!LegacyValues.isPhpEmpty(query.value("employee_id"))) {
			targetEmployeeId = LegacyValues.toPhpLong(query.value("employee_id"));
		}

		String today = clock.today().toString();
		String fromRaw = nonEmptyText(query.value("from"));
		String toRaw = nonEmptyText(query.value("to"));
		String reference = fromRaw != null ? fromRaw : (toRaw != null ? toRaw : today);
		String dateFrom = fromRaw != null ? fromRaw : firstDayOfPhpMonth(reference);
		String dateTo = toRaw != null ? toRaw : lastDayOfPhpMonth(dateFrom);

		if (targetEmployeeId > 0) {
			if (!employeeExists(targetEmployeeId, companyId)) {
				throw new LegacyApiException(404, "employee_not_found");
			}
			return employeePeriodStats(companyId, targetEmployeeId, dateFrom, dateTo, weeklyRestLabel);
		}
		return aggregateStats(companyId, query, dateFrom, dateTo);
	}

	private Map<String, Object> employeePeriodStats(long companyId, long employeeId, String from, String to,
			String weeklyRestLabel) {
		LocalDate start = LocalDate.parse(from);
		LocalDate end = LocalDate.parse(to);
		Map<String, DayState> byDate = new LinkedHashMap<>();
		for (Map<String, Object> row : jdbcTemplate.query(EMPLOYEE_ROWS, LegacyJdbcValues.rowMapper(), employeeId, from, to)) {
			Object rawIn = row.get("check_in");
			if (LegacyValues.isPhpEmpty(rawIn)) {
				continue;
			}
			String checkIn = LegacyValues.toPhpString(rawIn);
			String checkOut = row.get("check_out") == null
					|| LegacyValues.phpTrim(LegacyValues.toPhpString(row.get("check_out"))).isEmpty()
						? null : LegacyValues.toPhpString(row.get("check_out"));
			String date = checkIn.length() >= 10 ? checkIn.substring(0, 10) : checkIn;
			boolean exceptionOnly = LegacyAttendanceWorkedMinutes.isExceptionOnly(
					checkIn, checkOut, row.get("exception_type_id"));
			long raw = checkOut == null ? 0 : Math.max(0, LegacyValues.toPhpLong(row.get("duration_minutes")));
			int minutes = workedMinutes.forRow(companyId, employeeId, date, checkIn, checkOut,
					raw, row.get("exception_type_id"), weeklyRestLabel);
			exceptionOnly = exceptionOnly && minutes <= 0;
			byDate.put(date, new DayState(minutes, !exceptionOnly, exceptionOnly));
		}

		Map<String, String> holidays = calendar.holidaysByDate(companyId, from, to);
		String today = clock.today().toString();
		int present = 0;
		int leave = 0;
		int officialHolidays = 0;
		int skippedRest = 0;
		long totalDuration = 0;
		long overtime = 0;
		Map<String, LegacyWeeklyRestCredit.Flags> flags = flags(byDate);

		for (LocalDate day = start; !day.isAfter(end); day = day.plusDays(1)) {
			String date = day.toString();
			if (date.compareTo(today) > 0) {
				continue;
			}
			DayState state = byDate.getOrDefault(date, DayState.EMPTY);
			LegacyAttendanceCalendar.DayExpectation expected =
					calendar.expectedForDay(companyId, employeeId, date, weeklyRestLabel);
			if (state.hasPunch()) {
				present++;
				totalDuration += state.minutes();
				overtime += overtimeMinutes(state.minutes(), expected.expectedMinutes());
				continue;
			}
			if (state.exceptionOnly()) {
				leave++;
				continue;
			}
			if (holidays.containsKey(date)) {
				officialHolidays++;
				leave++;
				continue;
			}
			if (expected.restDay()) {
				String credit = weeklyRestCredit.status(companyId, employeeId, date, flags, holidays, today);
				if (LegacyWeeklyRestCredit.EARNED.equals(credit)) {
					leave++;
				} else {
					skippedRest++;
				}
				continue;
			}
			if (weeklyRestCredit.isOnApprovedLeave(employeeId, date)) {
				leave++;
			}
		}

		int elapsed = 0;
		for (LocalDate day = start; !day.isAfter(end); day = day.plusDays(1)) {
			if (day.toString().compareTo(today) <= 0) {
				elapsed++;
			}
		}
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("total_days_in_month", YearMonth.from(start).lengthOfMonth());
		result.put("present_days", present);
		result.put("leave_days", leave);
		result.put("official_holiday_days", officialHolidays);
		result.put("absent_days", Math.max(0, elapsed - present - leave - skippedRest));
		result.put("total_duration_minutes", totalDuration);
		result.put("overtime_minutes", Math.max(0, overtime));
		return result;
	}

	private Map<String, Object> aggregateStats(long companyId, LegacyQueryParameters query,
			String dateFrom, String dateTo) {
		StringBuilder where = new StringBuilder("e.company_id=?");
		List<Object> params = new java.util.ArrayList<>();
		params.add(companyId);
		appendFilter(where, params, query, "branch_id", "e.branch_id");
		appendFilter(where, params, query, "department_id", "e.department_id");
		if (!LegacyValues.isPhpEmpty(query.value("from"))) {
			where.append(" AND DATE(a.check_in)>=?");
			params.add(LegacyValues.toPhpString(query.value("from")));
		}
		if (!LegacyValues.isPhpEmpty(query.value("to"))) {
			where.append(" AND DATE(a.check_in)<=?");
			params.add(LegacyValues.toPhpString(query.value("to")));
		}

		String join = " FROM attendance AS a JOIN employees AS e ON e.id = a.employee_id WHERE " + where;
		Long present = jdbcTemplate.queryForObject(
				"SELECT COUNT(DISTINCT DATE(a.check_in))" + join + " AND a.check_out IS NOT NULL",
				Long.class, params.toArray());
		Long duration = jdbcTemplate.queryForObject(
				"SELECT COALESCE(SUM(TIMESTAMPDIFF(MINUTE,a.check_in,a.check_out)),0)" + join
						+ " AND a.check_out IS NOT NULL",
				Long.class, params.toArray());
		Long holidayCount = jdbcTemplate.queryForObject(HOLIDAY_COUNT, Long.class, companyId, dateFrom, dateTo);

		int totalDays = phpCalendarDaysInMonth(dateFrom, clock.today());
		long holidays = holidayCount == null ? 0 : holidayCount;
		long presentDays = present == null ? 0 : present;
		long effectivePresent = Math.min(totalDays, presentDays + holidays);
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("total_days_in_month", totalDays);
		result.put("present_days", presentDays);
		result.put("leave_days", Math.max(0, holidays));
		result.put("official_holiday_days", holidays);
		result.put("absent_days", Math.max(0, totalDays - effectivePresent));
		result.put("total_duration_minutes", duration == null ? 0 : duration);
		result.put("overtime_minutes", 0);
		return result;
	}

	static int overtimeMinutes(int actual, int expected) {
		if (expected <= 0) {
			return Math.max(0, actual);
		}
		int diff = actual - expected;
		return diff >= 15 ? diff : 0;
	}

	static int phpCalendarDaysInMonth(String from, LocalDate today) {
		String[] parts = from.split("-", -1);
		int year = (int) LegacyValues.toPhpLong(parts.length > 0 ? parts[0] : String.valueOf(today.getYear()));
		int month = parts.length > 1
				? (int) LegacyValues.toPhpLong(parts[1])
				: today.getMonthValue();
		return YearMonth.of(year, month).lengthOfMonth();
	}

	private boolean employeeExists(long employeeId, long companyId) {
		return !jdbcTemplate.queryForList(EMPLOYEE_EXISTS, employeeId, companyId).isEmpty();
	}

	private static Map<String, LegacyWeeklyRestCredit.Flags> flags(Map<String, DayState> byDate) {
		Map<String, LegacyWeeklyRestCredit.Flags> result = new LinkedHashMap<>();
		byDate.forEach((date, state) -> result.put(date,
				new LegacyWeeklyRestCredit.Flags(state.hasPunch(), state.exceptionOnly())));
		return result;
	}

	private static void appendFilter(StringBuilder where, List<Object> params,
			LegacyQueryParameters query, String key, String column) {
		if (!LegacyValues.isPhpEmpty(query.value(key))) {
			where.append(" AND ").append(column).append("=?");
			params.add(LegacyValues.toPhpLong(query.value(key)));
		}
	}

	private String firstDayOfPhpMonth(String value) {
		LocalDate parsed = com.workin.legacy.LegacyPhpStrtotime.dateOf(value, clock.today());
		if (parsed == null) {
			parsed = LocalDate.of(1970, 1, 1);
		}
		return parsed.withDayOfMonth(1).toString();
	}

	private String lastDayOfPhpMonth(String value) {
		LocalDate parsed = com.workin.legacy.LegacyPhpStrtotime.dateOf(value, clock.today());
		if (parsed == null) {
			parsed = LocalDate.of(1970, 1, 1);
		}
		return YearMonth.from(parsed).atEndOfMonth().toString();
	}

	private static String nonEmptyText(Object value) {
		if (LegacyValues.isPhpEmpty(value)) {
			return null;
		}
		return LegacyValues.toPhpString(value);
	}

	private record DayState(int minutes, boolean hasPunch, boolean exceptionOnly) {
		private static final DayState EMPTY = new DayState(0, false, false);
	}
}

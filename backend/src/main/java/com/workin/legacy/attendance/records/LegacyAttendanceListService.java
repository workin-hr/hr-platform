package com.workin.legacy.attendance.records;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.workin.legacy.LegacyClock;
import com.workin.legacy.LegacyJdbcValues;
import com.workin.legacy.LegacyPagination;
import com.workin.legacy.LegacyPhpStrtotime;
import com.workin.legacy.LegacyQueryParameters;
import com.workin.legacy.LegacyValues;
import com.workin.legacy.attendance.calendar.LegacyAttendanceCalendar;
import com.workin.legacy.attendance.calendar.LegacyWeeklyRestCredit;
import com.workin.legacy.auth.LegacyRequestContext;
import com.workin.legacy.employees.LegacyEmployee;
import com.workin.legacy.wire.LegacyApiException;

/** Frozen {@code attendance/list.php}, including its {@code fill_days=1} calendar branch. */
@Service
public class LegacyAttendanceListService {

	private static final String EMPLOYEE_DISPLAY_NAME =
			"TRIM(CONCAT(COALESCE(e.first_name,''),' ',COALESCE(e.last_name,'')))";

	private static final String FILL_EMPLOYEE_SELECT = """
			SELECT
			    e.id,
			    %s AS employee_name,
			    e.employee_code AS employee_code,
			    e.photo_url AS photo_url,
			    br.name AS branch_name,
			    s.name AS department_name,
			    jt.name AS job_title_name
			FROM employees AS e
			LEFT JOIN branches AS br ON e.branch_id = br.id
			LEFT JOIN departments AS s ON s.id = e.department_id
			LEFT JOIN job_titles AS jt ON jt.id = e.job_title_id
			WHERE %s
			ORDER BY
			    CASE
			        WHEN e.employee_code REGEXP '^[0-9]+$' THEN CAST(e.employee_code AS UNSIGNED)
			        ELSE NULL
			    END ASC,
			    e.employee_code ASC,
			    e.id ASC
			""";

	private static final String CALENDAR_ATTENDANCE = """
			SELECT
			    a.*,
			    et.name AS exception_type_name,
			    TIMESTAMPDIFF(MINUTE, a.check_in, a.check_out) AS duration_minutes
			FROM attendance AS a
			LEFT JOIN exception_types AS et ON et.id = a.exception_type_id
			WHERE a.employee_id = ?
			  AND DATE(a.check_in) >= ?
			  AND DATE(a.check_in) <= ?
			ORDER BY a.check_in ASC
			""";

	private static final String REGULAR_COUNT = """
			SELECT COUNT(*)
			FROM attendance AS a
			JOIN employees AS e ON e.id = a.employee_id
			WHERE %s
			""";

	private static final String REGULAR_SELECT = """
			SELECT
			    a.*,
			    %s AS employee_name,
			    e.employee_code AS employee_code,
			    e.photo_url AS photo_url,
			    TIMESTAMPDIFF(MINUTE, a.check_in, a.check_out) AS duration_minutes,
			    br.name AS branch_name,
			    s.name AS department_name,
			    jt.name AS job_title_name,
			    et.id AS exception_type_id,
			    et.name AS exception_type_name,
			    (
			        SELECT sh.name
			        FROM employee_shift_assignments esa
			        INNER JOIN shifts sh ON sh.id = esa.shift_id
			        WHERE esa.employee_id = e.id
			        ORDER BY esa.effective_from DESC, esa.id DESC
			        LIMIT 1
			    ) AS shift_name,
			    (
			        SELECT
			            CASE
			                WHEN TIMESTAMPDIFF(MINUTE,
			                    STR_TO_DATE(CONCAT(DATE(a.check_in), ' ', sh.start_time), '%%Y-%%m-%%d %%H:%%i:%%s'),
			                    STR_TO_DATE(CONCAT(DATE(a.check_in), ' ', sh.end_time), '%%Y-%%m-%%d %%H:%%i:%%s')
			                ) < 0
			                THEN TIMESTAMPDIFF(MINUTE,
			                    STR_TO_DATE(CONCAT(DATE(a.check_in), ' ', sh.start_time), '%%Y-%%m-%%d %%H:%%i:%%s'),
			                    STR_TO_DATE(CONCAT(DATE(a.check_in), ' ', sh.end_time), '%%Y-%%m-%%d %%H:%%i:%%s')
			                ) + 1440
			                ELSE TIMESTAMPDIFF(MINUTE,
			                    STR_TO_DATE(CONCAT(DATE(a.check_in), ' ', sh.start_time), '%%Y-%%m-%%d %%H:%%i:%%s'),
			                    STR_TO_DATE(CONCAT(DATE(a.check_in), ' ', sh.end_time), '%%Y-%%m-%%d %%H:%%i:%%s')
			                )
			            END
			        FROM employee_shift_assignments esa
			        INNER JOIN shifts sh ON sh.id = esa.shift_id
			        WHERE esa.employee_id = e.id
			        ORDER BY esa.effective_from DESC, esa.id DESC
			        LIMIT 1
			    ) AS expected_duration_minutes
			FROM attendance AS a
			JOIN employees AS e ON e.id = a.employee_id
			LEFT JOIN branches AS br ON e.branch_id = br.id
			LEFT JOIN departments AS s ON s.id = e.department_id
			LEFT JOIN job_titles AS jt ON jt.id = e.job_title_id
			LEFT JOIN exception_types AS et ON et.id = a.exception_type_id
			WHERE %s
			ORDER BY
			    CASE
			        WHEN e.employee_code REGEXP '^[0-9]+$' THEN CAST(e.employee_code AS UNSIGNED)
			        ELSE NULL
			    END ASC,
			    e.employee_code ASC,
			    a.check_in ASC,
			    a.id ASC
			LIMIT ? OFFSET ?
			""";

	private final JdbcTemplate jdbcTemplate;
	private final LegacyAttendanceWorkedMinutes workedMinutes;
	private final LegacyAttendanceCalendar calendar;
	private final LegacyWeeklyRestCredit weeklyRestCredit;
	private final LegacyClock clock;

	public LegacyAttendanceListService(DataSource legacyDataSource,
			LegacyAttendanceWorkedMinutes workedMinutes, LegacyAttendanceCalendar calendar,
			LegacyWeeklyRestCredit weeklyRestCredit, LegacyClock clock) {
		this.jdbcTemplate = new JdbcTemplate(legacyDataSource);
		this.workedMinutes = workedMinutes;
		this.calendar = calendar;
		this.weeklyRestCredit = weeklyRestCredit;
		this.clock = clock;
	}

	public record Page(List<Map<String, Object>> rows, Map<String, Object> meta) {
	}

	public Page list(LegacyRequestContext context, LegacyQueryParameters query, String weeklyRestLabel) {
		return fillDays(query) ? fillDays(context, query, weeklyRestLabel) : regular(context, query, weeklyRestLabel);
	}

	private Page fillDays(LegacyRequestContext context, LegacyQueryParameters query, String weeklyRestLabel) {
		LocalDate today = clock.today();
		LocalDate from = phpDate(query.value("from"));
		LocalDate to = phpDate(query.value("to"));
		if (from == null || to == null) {
			YearMonth month = YearMonth.from(today);
			from = month.atDay(1);
			to = month.atEndOfMonth();
		}
		if (to.isBefore(from)) {
			throw new LegacyApiException(400, "invalid_input");
		}

		List<String> predicates = new ArrayList<>();
		List<Object> args = new ArrayList<>();
		predicates.add("e.company_id=?");
		args.add(context.companyId());
		if (context.role() == LegacyEmployee.Role.EMPLOYEE) {
			predicates.add("e.id=?");
			args.add(context.employeeId());
		} else {
			appendLongFilter(predicates, args, query, "employee_id", "e.id");
			appendLongFilter(predicates, args, query, "branch_id", "e.branch_id");
			appendLongFilter(predicates, args, query, "department_id", "e.department_id");
		}
		appendSearch(predicates, args, query);
		predicates.add("COALESCE(e.join_request_status, 'accepted') = 'accepted'");
		predicates.add("e.is_active = 1");

		String where = String.join(" AND ", predicates);
		List<Map<String, Object>> employees = jdbcTemplate.query(
				FILL_EMPLOYEE_SELECT.formatted(EMPLOYEE_DISPLAY_NAME, where),
				LegacyJdbcValues.rowMapper(), args.toArray());

		String fromText = from.toString();
		String toText = to.toString();
		List<Map<String, Object>> rows = new ArrayList<>();
		for (Map<String, Object> employee : employees) {
			long employeeId = LegacyValues.toPhpLong(employee.get("id"));
			for (Map<String, Object> day : buildCalendar(context.companyId(), employeeId,
					fromText, toText, true, weeklyRestLabel)) {
				Map<String, Object> row = new LinkedHashMap<>(day);
				row.put("employee_id", employeeId);
				row.put("employee_name", employee.get("employee_name"));
				row.put("employee_code", employee.get("employee_code"));
				row.put("photo_url", employee.get("photo_url"));
				row.put("branch_name", employee.get("branch_name"));
				row.put("department_name", employee.get("department_name"));
				row.put("job_title_name", employee.get("job_title_name"));
				rows.add(row);
			}
		}
		long total = rows.size();
		LegacyPagination.Params pagination = new LegacyPagination.Params(1, Math.max(1, total), 0);
		return new Page(rows, LegacyPagination.meta(total, pagination));
	}

	private Page regular(LegacyRequestContext context, LegacyQueryParameters query, String weeklyRestLabel) {
		List<String> predicates = new ArrayList<>();
		List<Object> args = new ArrayList<>();
		predicates.add("e.company_id=?");
		args.add(context.companyId());
		if (context.role() == LegacyEmployee.Role.EMPLOYEE) {
			predicates.add("a.employee_id=?");
			args.add(context.employeeId());
		} else {
			appendLongFilter(predicates, args, query, "employee_id", "a.employee_id");
			appendLongFilter(predicates, args, query, "branch_id", "e.branch_id");
			appendLongFilter(predicates, args, query, "department_id", "e.department_id");
		}
		appendDateFilter(predicates, args, query, "from", "DATE(a.check_in)>=?");
		appendDateFilter(predicates, args, query, "to", "DATE(a.check_in)<=?");
		appendSearch(predicates, args, query);

		String where = String.join(" AND ", predicates);
		Long count = jdbcTemplate.queryForObject(REGULAR_COUNT.formatted(where), Long.class, args.toArray());
		long total = count == null ? 0 : count;
		LegacyPagination.Params pagination = LegacyPagination.params(query);
		List<Object> dataArgs = new ArrayList<>(args);
		dataArgs.add(pagination.limit());
		dataArgs.add(pagination.offset());
		List<Map<String, Object>> rows = jdbcTemplate.query(
				REGULAR_SELECT.formatted(EMPLOYEE_DISPLAY_NAME, where),
				LegacyJdbcValues.rowMapper(), dataArgs.toArray());

		for (Map<String, Object> row : rows) {
			long employeeId = LegacyValues.toPhpLong(row.get("employee_id"));
			String checkIn = nullableText(row.get("check_in"));
			String checkOut = nullableNonBlankText(row.get("check_out"));
			String date = rowDate(checkIn);
			long raw = Math.max(0, LegacyValues.toPhpLong(row.get("duration_minutes")));
			row.put("duration_minutes", workedMinutes.forRow(context.companyId(), employeeId, date,
					checkIn, checkOut, raw, row.get("exception_type_id"), weeklyRestLabel));
		}
		return new Page(rows, LegacyPagination.meta(total, pagination));
	}

	private List<Map<String, Object>> buildCalendar(long companyId, long employeeId,
			String from, String to, boolean capAtToday, String weeklyRestLabel) {
		LocalDate start = LocalDate.parse(from);
		LocalDate end = LocalDate.parse(to);
		if (capAtToday && end.isAfter(clock.today())) {
			end = clock.today();
		}
		if (end.isBefore(start)) {
			return List.of();
		}
		String effectiveTo = end.toString();

		Map<String, Map<String, Object>> byDate = new LinkedHashMap<>();
		for (Map<String, Object> row : jdbcTemplate.query(
				CALENDAR_ATTENDANCE, LegacyJdbcValues.rowMapper(), employeeId, from, effectiveTo)) {
			String checkIn = nullableText(row.get("check_in"));
			if (checkIn != null) {
				byDate.put(rowDate(checkIn), row);
			}
		}

		String lookback = start.minusDays(7).toString();
		Map<String, String> holidays = calendar.holidaysByDate(companyId, lookback, effectiveTo);
		Map<String, LegacyWeeklyRestCredit.Flags> flags = weeklyRestCredit.attendanceFlags(employeeId, from, effectiveTo);
		String today = clock.today().toString();
		List<Map<String, Object>> result = new ArrayList<>();

		for (LocalDate day = start; !day.isAfter(end); day = day.plusDays(1)) {
			String date = day.toString();
			Map<String, Object> shift = calendar.shiftForEmployeeOnDate(employeeId, date);
			String scheduleException = calendar.exceptionForDay(companyId, date, shift, holidays, weeklyRestLabel);
			boolean restOrHoliday = scheduleException != null && !LegacyValues.phpTrim(scheduleException).isEmpty();
			boolean officialHoliday = holidays.containsKey(date);
			boolean weeklyRest = restOrHoliday && !officialHoliday;
			String restCredit = weeklyRest
					? weeklyRestCredit.status(companyId, employeeId, date, flags, holidays, today) : null;
			Map<String, Object> attendance = byDate.get(date);

			if (attendance != null) {
				String exceptionName = LegacyValues.phpTrim(nullableText(attendance.get("exception_type_name")) == null
						? "" : LegacyValues.toPhpString(attendance.get("exception_type_name")));
				if (exceptionName.isEmpty() && restOrHoliday) {
					exceptionName = LegacyValues.phpTrim(scheduleException);
				}
				String checkIn = nullableText(attendance.get("check_in"));
				String checkOut = nullableNonBlankText(attendance.get("check_out"));
				boolean exceptionOnly = LegacyAttendanceWorkedMinutes.isExceptionOnly(
						checkIn, checkOut, attendance.get("exception_type_id"));
				long raw = Math.max(0, LegacyValues.toPhpLong(attendance.get("duration_minutes")));
				int duration = workedMinutes.forRow(companyId, employeeId, date, checkIn, checkOut,
						raw, attendance.get("exception_type_id"), weeklyRestLabel);
				int expected = exceptionOnly ? 0
						: calendar.expectedForDay(companyId, employeeId, date, weeklyRestLabel).expectedMinutes();
				result.add(calendarRow(date, LegacyValues.toPhpLong(attendance.get("id")),
						exceptionOnly ? null : checkIn, exceptionOnly ? null : checkOut,
						duration, expected, attendance.get("exception_type_id"),
						exceptionName.isEmpty() ? null : exceptionName, false, weeklyRest,
						LegacyWeeklyRestCredit.VOID.equals(restCredit), restCredit, officialHoliday));
				continue;
			}

			if (restOrHoliday) {
				result.add(calendarRow(date, null, null, null, 0, 0, null,
						LegacyValues.phpTrim(scheduleException), false, weeklyRest,
						LegacyWeeklyRestCredit.VOID.equals(restCredit), restCredit, officialHoliday));
				continue;
			}

			int missingDuration = workedMinutes.forRow(companyId, employeeId, date,
					null, null, 0, null, weeklyRestLabel);
			result.add(calendarRow(date, null, null, null, missingDuration, 0, null,
					null, missingDuration <= 0, false, false, null, false));
		}
		return result;
	}

	private static Map<String, Object> calendarRow(String date, Long attendanceId,
			String checkIn, String checkOut, int duration, int expected, Object exceptionTypeId,
			Object exceptionTypeName, boolean missing, boolean weeklyRest, boolean weeklyRestVoid,
			String weeklyRestCredit, boolean officialHoliday) {
		Map<String, Object> row = new LinkedHashMap<>();
		row.put("date", date);
		row.put("attendance_id", attendanceId);
		row.put("id", attendanceId == null ? syntheticRowId(0, date) : attendanceId);
		row.put("check_in", checkIn);
		row.put("check_out", checkOut);
		row.put("duration_minutes", duration);
		row.put("expected_duration_minutes", expected);
		row.put("exception_type_id", exceptionTypeId);
		row.put("exception_type_name", exceptionTypeName);
		row.put("is_missing", missing);
		row.put("is_weekly_rest", weeklyRest);
		row.put("is_weekly_rest_void", weeklyRestVoid);
		row.put("weekly_rest_credit", weeklyRestCredit);
		row.put("is_official_holiday", officialHoliday);
		return row;
	}

	private static long syntheticRowId(long employeeId, String date) {
		long compact = Long.parseLong(date.replace("-", ""));
		return -1L * (employeeId * 100_000_000L + compact % 100_000_000L);
	}

	private boolean fillDays(LegacyQueryParameters query) {
		Object raw = query.value("fill_days");
		return raw != null && !"".equals(raw) && !"0".equals(LegacyValues.toPhpString(raw));
	}

	private LocalDate phpDate(Object raw) {
		if (raw == null || "".equals(raw)) {
			return null;
		}
		return LegacyPhpStrtotime.dateOf(LegacyValues.toPhpString(raw), clock.today());
	}

	private void appendDateFilter(List<String> predicates, List<Object> args,
			LegacyQueryParameters query, String key, String sql) {
		if (LegacyValues.isPhpEmpty(query.value(key))) {
			return;
		}
		LocalDate parsed = phpDate(query.value(key));
		predicates.add(sql);
		args.add((parsed == null ? LocalDate.of(1970, 1, 1) : parsed).toString());
	}

	private static void appendLongFilter(List<String> predicates, List<Object> args,
			LegacyQueryParameters query, String key, String column) {
		if (!LegacyValues.isPhpEmpty(query.value(key))) {
			predicates.add(column + "=?");
			args.add(LegacyValues.toPhpLong(query.value(key)));
		}
	}

	private static void appendSearch(List<String> predicates, List<Object> args, LegacyQueryParameters query) {
		String search = LegacyPagination.searchQueryParam(query);
		if (search == null) {
			return;
		}
		if (search.matches("^[0-9]+$")) {
			predicates.add("e.employee_code LIKE ?");
			args.add("%" + search + "%");
			return;
		}
		predicates.add("(" + EMPLOYEE_DISPLAY_NAME + " LIKE ? OR e.employee_code LIKE ?)");
		String like = "%" + search + "%";
		args.add(like);
		args.add(like);
	}

	private String rowDate(String checkIn) {
		if (checkIn == null || checkIn.isEmpty()) {
			return clock.today().toString();
		}
		LocalDate parsed = LegacyPhpStrtotime.dateOf(checkIn, clock.today());
		return parsed == null ? clock.today().toString() : parsed.toString();
	}

	private static String nullableText(Object value) {
		return value == null ? null : LegacyValues.toPhpString(value);
	}

	private static String nullableNonBlankText(Object value) {
		if (value == null) {
			return null;
		}
		String text = LegacyValues.toPhpString(value);
		return LegacyValues.phpTrim(text).isEmpty() ? null : text;
	}
}

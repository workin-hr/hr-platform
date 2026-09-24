package com.workin.legacy.attendance.calendar;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.sql.DataSource;

import org.springframework.context.annotation.ScopedProxyMode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.RequestScope;

import com.workin.legacy.LegacyValues;
import com.workin.legacy.attendance.LegacyWeeklyOffDays;
import com.workin.legacy.workforce.LegacyShiftTimes;

/**
 * What a given employee was expected to work on a given day.
 *
 * <p>The shared closure behind Wave 12.6.3, and the same one 12.6.4 and 12.6.5
 * consume. It ports the reached portions of
 * {@code attendance_import_expected_for_day()}
 * ({@code attendance_excel_analyzer.php:498-548}),
 * {@code schedule_shift_for_employee_on_date()},
 * {@code schedule_exception_for_day()}, {@code schedule_is_weekly_rest_day()},
 * {@code schedule_shift_marks_day_off()} and
 * {@code official_holidays_by_date_in_range()} -- once, not once per endpoint.
 *
 * <h2>Precedence is the contract</h2>
 * <p>An official holiday wins over a weekly rest day, and either wins over the
 * shift's own hours. Both produce {@code expectedMinutes = 0} with
 * {@code restDay = true}, and the shift's name and window are still reported
 * when there is an assigned shift -- so a rest day is not the same as "no
 * shift", and a caller reading {@code shiftStart} on a rest day gets a real
 * time.
 *
 * <p>Weekly rest itself has two independent sources, checked in this order:
 * the shift's own {@code days_off} text, then the company's
 * {@code WEEKLY_OFF_DAYS} setting through D-091's bounded reader. Either is
 * enough.
 *
 * <h2>No shift assigned is not an error</h2>
 * <p>{@code expected_minutes} then comes from a coalesce chain in SQL --
 * the employee's {@code expected_daily_hours}, else the job title's
 * {@code work_hours}, else 8 -- with {@code NULLIF(..., 0)} at each step, so a
 * stored zero is skipped rather than believed. Reproduced as the same single
 * statement rather than as three Java branches, because the zero-skipping is
 * the part that is easy to get wrong.
 *
 * <h2>Request-scoped, one query per employee/date per request</h2>
 * <p>{@link #shiftForEmployeeOnDate} is read once per day of the pay period,
 * per payslip, over one HTTP request in {@code payslips/list.php}'s
 * enrichment loop -- and nothing writes {@code employee_shift_assignments}
 * mid-request. Request-scoped with {@link ScopedProxyMode#TARGET_CLASS}, the
 * same reasoning and the same mechanism as {@link LegacyWeeklyOffDays} (see
 * that class's javadoc) and {@link com.workin.legacy.LegacyClock}.
 */
@Component
@RequestScope(proxyMode = ScopedProxyMode.TARGET_CLASS)
public class LegacyAttendanceCalendar {

	/**
	 * {@code schedule_shift_for_employee_on_date()}
	 * ({@code schedule_helper.php:12-26}).
	 *
	 * <p>The most recent assignment effective on or before the date, tie-broken
	 * by id. {@code s.*} is PHP's, so a column added to {@code shifts} reaches
	 * callers with no change here.
	 */
	private static final String SHIFT_ON_DATE = """
			SELECT esa.shift_id, esa.effective_from, s.*
			FROM employee_shift_assignments esa
			INNER JOIN shifts s ON s.id = esa.shift_id
			WHERE esa.employee_id = ? AND esa.effective_from <= ?
			ORDER BY esa.effective_from DESC, esa.id DESC
			LIMIT 1""";

	/**
	 * {@code attendance_is_on_approved_leave()}
	 * ({@code attendance_calendar_helper.php:669-683}).
	 */
	private static final String IS_ON_APPROVED_LEAVE = """
			SELECT COUNT(*)
			FROM requests r
			INNER JOIN request_types t ON t.id = r.request_type_id
			WHERE r.employee_id = ?
			  AND r.status = 'approved'
			  AND t.counts_as_paid_leave = 1
			  AND r.from_date <= ?
			  AND r.to_date >= ?""";

	/** {@code official_holidays_by_date_in_range()} ({@code official_holidays_helper.php:61-83}). */
	private static final String HOLIDAYS_IN_RANGE = """
			SELECT holiday_date, name
			FROM company_official_holidays
			WHERE company_id = ? AND holiday_date BETWEEN ? AND ?
			ORDER BY holiday_date ASC""";

	/**
	 * The no-shift fallback, verbatim.
	 *
	 * <p>{@code NULLIF(x, 0)} at both steps is why a stored zero does not win:
	 * an employee with {@code expected_daily_hours = 0} falls through to the
	 * job title, and a job title with {@code work_hours = 0} falls through to 8.
	 */
	private static final String FALLBACK_HOURS = """
			SELECT COALESCE(NULLIF(e.expected_daily_hours, 0), NULLIF(jt.work_hours, 0), 8)
			FROM employees e
			LEFT JOIN job_titles jt ON jt.id = e.job_title_id
			WHERE e.id = ?""";

	/** {@code schedule_shift_marks_day_off()}'s table -- the same names the rest-day setting uses. */
	private static final Map<String, Integer> DAY_NAMES = Map.ofEntries(
			Map.entry("sunday", 0), Map.entry("monday", 1), Map.entry("tuesday", 2),
			Map.entry("wednesday", 3), Map.entry("thursday", 4), Map.entry("friday", 5),
			Map.entry("saturday", 6),
			Map.entry("sun", 0), Map.entry("mon", 1), Map.entry("tue", 2), Map.entry("wed", 3),
			Map.entry("thu", 4), Map.entry("fri", 5), Map.entry("sat", 6),
			Map.entry("الأحد", 0), Map.entry("الاحد", 0),
			Map.entry("الإثنين", 1), Map.entry("الاثنين", 1),
			Map.entry("الثلاثاء", 2),
			Map.entry("الأربعاء", 3), Map.entry("الاربعاء", 3),
			Map.entry("الخميس", 4), Map.entry("الجمعة", 5), Map.entry("السبت", 6));

	private final JdbcTemplate jdbcTemplate;
	private final LegacyWeeklyOffDays weeklyOffDays;
	/** Removed from each warmed row, so the cached map matches {@link #SHIFT_ON_DATE}'s exactly. */
	private static final String WARM_EMPLOYEE_KEY = "warm_employee_id";

	private final Map<String, Map<String, Object>> shiftCache = new HashMap<>();
	private final Map<String, Map<String, String>> holidayCache = new HashMap<>();

	/**
	 * {@code employeeId|date} to the answer {@link #isOnApprovedLeave} would
	 * have queried. An absent key is not {@code false}: it means the date was
	 * never warmed, and the query still runs.
	 */
	private final Map<String, Boolean> approvedLeaveCache = new HashMap<>();

	/**
	 * The {@code (ids, from, to)} windows already warmed. Two methods on one
	 * request ask for the same window -- the absent-day list and the void
	 * weekly-rest list both cover the period plus its lookback -- and without
	 * this the second re-issues an identical statement.
	 */
	private final java.util.Set<String> approvedLeaveWarmed = new java.util.HashSet<>();

	public LegacyAttendanceCalendar(DataSource legacyDataSource, LegacyWeeklyOffDays weeklyOffDays) {
		this.jdbcTemplate = new JdbcTemplate(legacyDataSource);
		this.weeklyOffDays = weeklyOffDays;
	}

	/**
	 * {@code attendance_import_expected_for_day()}'s return array.
	 *
	 * @param expectedMinutes zero on a rest day, the shift duration otherwise,
	 *        and the coalesce-chain fallback when no shift is assigned
	 * @param shiftName null when no shift is assigned; the empty string is
	 *        possible when one is assigned but unnamed
	 * @param restNote the holiday's name, or the localized weekly-rest label
	 */
	public record DayExpectation(
			int expectedMinutes, String shiftName, String shiftStart, String shiftEnd,
			boolean restDay, String restNote) {
	}

	/** {@code attendance_import_expected_for_day($companyId, $employeeId, $date)}. */
	public DayExpectation expectedForDay(
			long companyId, long employeeId, String date, String weeklyRestLabel) {
		Map<String, String> holidays = holidaysByDate(companyId, date, date);
		Map<String, Object> shift = shiftForEmployeeOnDate(employeeId, date);

		// Weekly rest and official holidays apply even with no shift assigned,
		// which is why the exception is evaluated before the null-shift branch.
		String exception = exceptionForDay(companyId, date, shift, holidays, weeklyRestLabel);
		if (exception != null) {
			return new DayExpectation(
					0,
					shift != null ? text(shift.get("name")) : null,
					shift == null ? null : nullableText(shift.get("start_time")),
					shift == null ? null : nullableText(shift.get("end_time")),
					true,
					exception);
		}

		if (shift == null) {
			Double hours = jdbcTemplate.queryForObject(FALLBACK_HOURS, Double.class, employeeId);
			double fallback = hours == null ? 8d : hours;
			return new DayExpectation(
					Math.max(0, (int) Math.round(fallback * 60)), null, null, null, false, null);
		}

		String start = text(shift.get("start_time"));
		String end = text(shift.get("end_time"));
		Integer duration = LegacyShiftTimes.durationMinutes(start, end);
		return new DayExpectation(
				Math.max(0, duration == null ? 0 : duration),
				text(shift.get("name")),
				start.isEmpty() ? null : start,
				end.isEmpty() ? null : end,
				false,
				null);
	}

	/**
	 * {@code schedule_shift_for_employee_on_date()}, or null when none is
	 * effective yet.
	 *
	 * <p>Memoized per (employee, date) for the lifetime of this
	 * request-scoped bean, including the no-shift-assigned case -- see the
	 * class javadoc. {@code containsKey} rather than {@code computeIfAbsent}
	 * because the cached value is legitimately {@code null}.
	 */
	public Map<String, Object> shiftForEmployeeOnDate(long employeeId, String date) {
		String key = employeeId + "|" + date;
		if (shiftCache.containsKey(key)) {
			return shiftCache.get(key);
		}
		List<Map<String, Object>> rows = jdbcTemplate.query(
				SHIFT_ON_DATE, com.workin.legacy.LegacyJdbcValues.rowMapper(), employeeId, date);
		Map<String, Object> shift = rows.isEmpty() ? null : rows.get(0);
		shiftCache.put(key, shift);
		return shift;
	}

	/**
	 * Fills {@link #shiftForEmployeeOnDate}'s cache for a whole page in one
	 * statement, instead of one per employee per day.
	 *
	 * <p>The per-date query is the right shape for one date and the wrong shape
	 * for a report: the attendance page's aggregate view asks for every employee
	 * on the page, for every day of the period, so a month over ten rows was
	 * measured at 300 statements of the 354 that page issued. The cache is
	 * per-request, so it was 300 on every request, not only the first.
	 *
	 * <p>This is a pre-warm and not a second implementation: it selects the same
	 * columns and resolves the same winner, so a hit here is
	 * indistinguishable from the query it replaces. The ordering is the
	 * inverse of {@link #SHIFT_ON_DATE}'s -- ascending, so walking the dates
	 * forward keeps the last assignment that has taken effect, which is what
	 * {@code ORDER BY effective_from DESC, id DESC LIMIT 1} picks. A date before
	 * any assignment caches {@code null}, exactly as the query's empty result
	 * does, so it is not re-asked.
	 *
	 * <p><b>The comparison is lexical, and that is only correct because
	 * {@code effective_from} is a {@code DATE}</b> ({@code date NOT NULL} in the
	 * frozen schema, which {@code check_legacy_schema_drift.py} holds), and
	 * {@code LegacyJdbcValues} hands temporal columns back as raw text: an
	 * {@code ISO} date orders lexically exactly as it orders chronologically.
	 * Widen the column to {@code DATETIME} and the text becomes
	 * {@code 2026-03-10 00:00:00}, which compares <em>greater</em> than
	 * {@code 2026-03-10} -- the walk would then apply an assignment a day late
	 * while SQL applies it that day. The drift gate is what would catch the
	 * widening; this sentence is what tells the next reader why it matters.
	 *
	 * @param employeeIds the page's employees; at most a page size of them
	 * @param from        first date to resolve, inclusive
	 * @param to          last date to resolve, inclusive
	 */
	public void warmShiftsForEmployees(java.util.Collection<Long> employeeIds, String from, String to) {
		if (employeeIds == null || employeeIds.isEmpty()
				|| from == null || from.isEmpty() || to == null || to.isEmpty()) {
			return;
		}
		List<Long> ids = employeeIds.stream().filter(id -> id != null && id > 0).distinct().toList();
		if (ids.isEmpty()) {
			return;
		}
		java.time.LocalDate first;
		java.time.LocalDate last;
		try {
			first = java.time.LocalDate.parse(from);
			last = java.time.LocalDate.parse(to);
		} catch (java.time.format.DateTimeParseException unparseable) {
			return;
		}
		if (last.isBefore(first)) {
			return;
		}

		String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
		Object[] args = new Object[ids.size() + 1];
		for (int i = 0; i < ids.size(); i++) {
			args[i] = ids.get(i);
		}
		args[ids.size()] = to;
		Map<Long, List<Map<String, Object>>> byEmployee = new java.util.LinkedHashMap<>();
		jdbcTemplate.query(
				"SELECT esa.employee_id AS " + WARM_EMPLOYEE_KEY + ", esa.shift_id, esa.effective_from, s.*"
						+ " FROM employee_shift_assignments esa"
						+ " INNER JOIN shifts s ON s.id = esa.shift_id"
						+ " WHERE esa.employee_id IN (" + placeholders + ") AND esa.effective_from <= ?"
						+ " ORDER BY esa.employee_id ASC, esa.effective_from ASC, esa.id ASC",
				rs -> {
					Map<String, Object> row = com.workin.legacy.LegacyJdbcValues.rowMapper().mapRow(rs, 0);
					Object owner = row.remove(WARM_EMPLOYEE_KEY);
					long employeeId = owner instanceof Number number ? number.longValue()
							: Long.parseLong(String.valueOf(owner));
					byEmployee.computeIfAbsent(employeeId, key -> new java.util.ArrayList<>()).add(row);
				}, args);

		for (Long employeeId : ids) {
			List<Map<String, Object>> assignments = byEmployee.getOrDefault(employeeId, List.of());
			int next = 0;
			Map<String, Object> current = null;
			for (java.time.LocalDate date = first; !date.isAfter(last); date = date.plusDays(1)) {
				String text = date.toString();
				while (next < assignments.size()
						&& text(assignments.get(next).get("effective_from")).compareTo(text) <= 0) {
					current = assignments.get(next);
					next++;
				}
				// put, not putIfAbsent: the two can only ever be the same answer,
				// because nothing writes employee_shift_assignments mid-request --
				// and putIfAbsent would not have protected a cached absence anyway,
				// since it replaces a mapping whose value is null.
				shiftCache.put(employeeId + "|" + text, current);
			}
		}
	}

	/**
	 * {@code attendance_is_on_approved_leave()}
	 * ({@code attendance_calendar_helper.php:669-683}): whether an approved
	 * request of a paid-leave type covers this date.
	 *
	 * <p>Answered from {@link #warmApprovedLeaveForEmployees}'s cache when this
	 * employee/date was warmed, and by the query otherwise -- a warm that did
	 * not reach this date changes the cost, never the answer.
	 */
	public boolean isOnApprovedLeave(long employeeId, String date) {
		Boolean warmed = approvedLeaveCache.get(employeeId + "|" + date);
		if (warmed != null) {
			return warmed;
		}
		Long count = jdbcTemplate.queryForObject(IS_ON_APPROVED_LEAVE, Long.class, employeeId, date, date);
		return count != null && count > 0;
	}

	/**
	 * Fills {@link #isOnApprovedLeave}'s cache for a whole page in one statement,
	 * instead of one per employee per date.
	 *
	 * <p>This is the term {@link #warmShiftsForEmployees} left behind, and it was
	 * the larger one. The attendance page's aggregate view asks
	 * {@code isOnApprovedLeave} once per preceding workday per weekly-rest date
	 * per employee: ten rows over a month, for a company that takes Friday off,
	 * measured 240 of that page's 286 statements. Against the remote database,
	 * 106 ms away, that is half a minute of round trips for one page of ten rows.
	 *
	 * <p>Like the shift warm this is a pre-warm and not a second implementation.
	 * One row here covers a range of dates rather than one, so the selection is
	 * the overlap form -- an interval overlaps {@code [from, to]} exactly when
	 * {@code from_date <= to AND to_date >= from} -- and each date is then
	 * decided against the selected intervals with {@link #IS_ON_APPROVED_LEAVE}'s
	 * own comparison, {@code from_date <= date AND to_date >= date}. The overlap
	 * form cannot drop an interval the per-date query would have matched for a
	 * date in range, and the per-date re-check is what keeps it from adding one.
	 *
	 * <p><b>The comparison is lexical, and that is only correct because
	 * {@code requests.from_date} and {@code requests.to_date} are {@code DATE}
	 * columns</b> ({@code date NOT NULL} in the frozen schema, which
	 * {@code check_legacy_schema_drift.py} holds) read back as raw text: an
	 * {@code ISO} date orders lexically exactly as it orders chronologically.
	 * Widen either to {@code DATETIME} and the stored text becomes
	 * {@code 2026-03-10 00:00:00}, which compares <em>greater</em> than
	 * {@code 2026-03-10}: a leave whose {@code to_date} is that day would then
	 * read here as covering it and in SQL as covering it too, but a
	 * {@code from_date} of that day would read here as not yet begun while SQL,
	 * comparing datetime against the coerced midnight, agrees. The equality is
	 * the fragile case either way, so the drift gate is what protects it.
	 *
	 * @param employeeIds the page's employees; at most a page size of them
	 * @param from        first date to resolve, inclusive
	 * @param to          last date to resolve, inclusive
	 */
	public void warmApprovedLeaveForEmployees(
			java.util.Collection<Long> employeeIds, String from, String to) {
		if (employeeIds == null || employeeIds.isEmpty()
				|| from == null || from.isEmpty() || to == null || to.isEmpty()) {
			return;
		}
		List<Long> ids = employeeIds.stream().filter(id -> id != null && id > 0).distinct().toList();
		if (ids.isEmpty()) {
			return;
		}
		LocalDate first;
		LocalDate last;
		try {
			first = LocalDate.parse(from);
			last = LocalDate.parse(to);
		} catch (java.time.format.DateTimeParseException unparseable) {
			return;
		}
		if (last.isBefore(first)) {
			return;
		}
		String warmKey = ids + "|" + from + "|" + to;
		if (approvedLeaveWarmed.contains(warmKey)) {
			return;
		}

		String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
		Object[] args = new Object[ids.size() + 2];
		for (int i = 0; i < ids.size(); i++) {
			args[i] = ids.get(i);
		}
		args[ids.size()] = to;
		args[ids.size() + 1] = from;
		Map<Long, List<String[]>> byEmployee = new LinkedHashMap<>();
		jdbcTemplate.query(
				"SELECT r.employee_id AS " + WARM_EMPLOYEE_KEY + ", r.from_date, r.to_date"
						+ " FROM requests r"
						+ " INNER JOIN request_types t ON t.id = r.request_type_id"
						+ " WHERE r.employee_id IN (" + placeholders + ")"
						+ " AND r.status = 'approved' AND t.counts_as_paid_leave = 1"
						+ " AND r.from_date <= ? AND r.to_date >= ?",
				rs -> {
					long employeeId = rs.getLong(WARM_EMPLOYEE_KEY);
					String fromDate = rs.getString("from_date");
					String toDate = rs.getString("to_date");
					if (fromDate == null || toDate == null) {
						return;
					}
					byEmployee.computeIfAbsent(employeeId, key -> new java.util.ArrayList<>())
							.add(new String[] { fromDate, toDate });
				}, args);

		for (Long employeeId : ids) {
			List<String[]> leaves = byEmployee.getOrDefault(employeeId, List.of());
			for (LocalDate date = first; !date.isAfter(last); date = date.plusDays(1)) {
				String text = date.toString();
				boolean covered = false;
				for (String[] leave : leaves) {
					if (leave[0].compareTo(text) <= 0 && leave[1].compareTo(text) >= 0) {
						covered = true;
						break;
					}
				}
				approvedLeaveCache.put(employeeId + "|" + text, covered);
			}
		}
		// Recorded only now. Marking the window before the query would leave it
		// marked with nothing cached if that query threw, and a later call in the
		// same request would then skip a warm it never got -- correct answers, by
		// falling back to the per-date query, but the round trips this exists to
		// remove, silently back.
		approvedLeaveWarmed.add(warmKey);
	}

	/**
	 * {@code official_holidays_by_date_in_range()} as a date-keyed map.
	 *
	 * <p>Later rows overwrite earlier ones for the same date, so two holidays
	 * on one day leave the last by {@code holiday_date ASC} ordering -- PHP
	 * builds the map the same way and has the same behaviour.
	 *
	 * <p>Memoized per (company, from, to) for the lifetime of this
	 * request-scoped bean -- the same reasoning as {@link
	 * #shiftForEmployeeOnDate}: nothing writes {@code
	 * company_official_holidays} mid-request. {@link #expectedForDay} calls
	 * this once per date with the single-day range {@code (date, date)}, and
	 * {@code payslips/list.php}'s enrichment loop calls {@code
	 * expectedForDay}-adjacent paths once per day per payslip on the page --
	 * all for the same company and (mostly) the same pay period, so the exact
	 * {@code (companyId, from, to)} key recurs heavily across employees on one
	 * request. Unmemoized, this was hundreds of identical queries for one
	 * {@code list.php} page (PR #120 review).
	 */
	public Map<String, String> holidaysByDate(long companyId, String from, String to) {
		if (companyId <= 0 || from == null || from.isEmpty() || to == null || to.isEmpty()) {
			return Map.of();
		}
		String key = companyId + "|" + from + "|" + to;
		Map<String, String> cached = holidayCache.get(key);
		if (cached != null) {
			return cached;
		}
		Map<String, String> map = new LinkedHashMap<>();
		jdbcTemplate.query(HOLIDAYS_IN_RANGE, rs -> {
			String date = rs.getString("holiday_date");
			if (date != null && !date.isEmpty()) {
				String name = rs.getString("name");
				map.put(date, name == null ? "" : name);
			}
		}, companyId, from, to);
		holidayCache.put(key, map);
		return map;
	}

	/**
	 * {@code schedule_exception_for_day()} ({@code schedule_helper.php:73-90}).
	 *
	 * <p>A holiday whose name is blank still marks the day off, but reports the
	 * weekly-rest label instead of an empty string -- so a nameless holiday and
	 * a weekly rest day are indistinguishable to a caller reading the note.
	 *
	 * @return the note, or null when the day is a working day
	 */
	public String exceptionForDay(
			long companyId, String date, Map<String, Object> shift,
			Map<String, String> holidaysByDate, String weeklyRestLabel) {
		String holiday = holidaysByDate.get(date);
		if (holiday != null) {
			String name = LegacyValues.phpTrim(holiday);
			return name.isEmpty() ? weeklyRestLabel : name;
		}
		return isWeeklyRestDay(companyId, date, shift) ? weeklyRestLabel : null;
	}

	/**
	 * {@code schedule_is_weekly_rest_day()}: the shift's own {@code days_off}
	 * first, then the company setting.
	 *
	 * <p>The shift text is checked before any query runs, so a shift that marks
	 * the day off never reads {@code company_settings} at all.
	 */
	public boolean isWeeklyRestDay(long companyId, String date, Map<String, Object> shift) {
		int dayOfWeek = dayOfWeek(date);
		String daysOff = shift == null ? "" : text(shift.get("days_off"));
		if (shiftMarksDayOff(daysOff, dayOfWeek)) {
			return true;
		}
		return LegacyWeeklyOffDays.isWeeklyRestDay(dayOfWeek, weeklyOffDays.forCompany(companyId));
	}

	/**
	 * {@code schedule_shift_marks_day_off()}: a comma/Arabic-comma/semicolon
	 * separated list of day names.
	 *
	 * <p>Unlike the setting's matcher this one is <b>names only</b> -- a
	 * numeric {@code days_off} of {@code "5"} does not mark Friday off, where
	 * the same value in {@code WEEKLY_OFF_DAYS} would. Two lists of day names,
	 * two different grammars.
	 */
	public static boolean shiftMarksDayOff(String daysOff, int dayOfWeek) {
		String trimmed = LegacyValues.phpTrim(daysOff);
		if (trimmed.isEmpty()) {
			return false;
		}
		for (String part : trimmed.split("[,،;]+")) {
			String token = LegacyValues.phpTrim(part).toLowerCase(Locale.ROOT);
			if (token.isEmpty()) {
				continue;
			}
			Integer mapped = DAY_NAMES.get(token);
			if (mapped != null && mapped == dayOfWeek) {
				return true;
			}
		}
		return false;
	}

	/** {@code (int) $date->format('w')}: 0 = Sunday. */
	public static int dayOfWeek(String date) {
		return LocalDate.parse(date).getDayOfWeek().getValue() % 7;
	}

	private static String text(Object value) {
		return value == null ? "" : String.valueOf(value);
	}

	private static String nullableText(Object value) {
		return value == null ? null : String.valueOf(value);
	}

}

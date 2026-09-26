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
	 * The holiday ranges {@link #warmHolidays} read, per company. A narrower
	 * {@link #holidaysByDate} request inside one of them is answered by
	 * filtering it instead of by another statement.
	 */
	private final Map<Long, List<HolidayWindow>> holidayWindows = new HashMap<>();

	/** {@link #FALLBACK_HOURS}' answer per employee, filled only by {@link #warmFallbackHours}. */
	private final Map<Long, java.math.BigDecimal> fallbackHoursCache = new HashMap<>();

	/**
	 * The timed request {@link LegacyAttendanceWorkedMinutes#approvedTimedRequestForDay}
	 * would have read for an employee and date, before its trim; null when
	 * warmed and there is none.
	 */
	private final LegacyWarmedDays<LegacyAttendanceWorkedMinutes.TimedRequest> timedRequests =
			new LegacyWarmedDays<>(NOT_WARMED_TIMED);

	private static final LegacyAttendanceWorkedMinutes.TimedRequest NOT_WARMED_TIMED =
			new LegacyAttendanceWorkedMinutes.TimedRequest("not warmed", "not warmed");

	private record HolidayWindow(String from, String to, Map<String, String> byDate) {
	}

	/**
	 * The answer {@link #isOnApprovedLeave} would have queried, per employee and
	 * day. A day that was not warmed still runs the query.
	 */
	private final LegacyWarmedDays<Boolean> approvedLeave = new LegacyWarmedDays<>(null);

	/**
	 * The shift {@link #SHIFT_ON_DATE} would have returned, per employee and day,
	 * for the days {@link #warmShiftsForEmployees} read. {@link #shiftCache}
	 * still memoizes the dates asked one at a time.
	 */
	private final LegacyWarmedDays<Map<String, Object>> warmedShifts = new LegacyWarmedDays<>(NOT_WARMED_SHIFT);

	private static final Map<String, Object> NOT_WARMED_SHIFT = Map.of("not", "warmed");

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
			java.math.BigDecimal warmed = fallbackHoursCache.get(employeeId);
			// getDouble on the DECIMAL the statement returns and BigDecimal's
			// doubleValue() are both the nearest double to the same decimal text.
			Double hours = warmed != null
					? Double.valueOf(warmed.doubleValue())
					: jdbcTemplate.queryForObject(FALLBACK_HOURS, Double.class, employeeId);
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
		Map<String, Object> warmed = warmedShifts.get(employeeId, date);
		if (warmed != NOT_WARMED_SHIFT) {
			return warmed;
		}
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
	 * @param employeeIds the employees to warm, bound {@link LegacyIdBatches#SIZE} per statement;
	 *        a window longer than a report's widest ({@link LegacyWarmedDays#MAX_WINDOW_DAYS})
	 *        is not warmed
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

		if (java.time.temporal.ChronoUnit.DAYS.between(first, last) + 1 > LegacyWarmedDays.MAX_WINDOW_DAYS) {
			return;
		}

		Map<Long, List<Map<String, Object>>> byEmployee = new java.util.LinkedHashMap<>();
		for (List<Long> batch : LegacyIdBatches.of(ids)) {
			Object[] args = new Object[batch.size() + 1];
			for (int i = 0; i < batch.size(); i++) {
				args[i] = batch.get(i);
			}
			args[batch.size()] = to;
			jdbcTemplate.query(
					"SELECT esa.employee_id AS " + WARM_EMPLOYEE_KEY + ", esa.shift_id, esa.effective_from, s.*"
							+ " FROM employee_shift_assignments esa"
							+ " INNER JOIN shifts s ON s.id = esa.shift_id"
							+ " WHERE esa.employee_id IN (" + LegacyIdBatches.placeholders(batch.size()) + ")"
							+ " AND esa.effective_from <= ?"
							+ " ORDER BY esa.employee_id ASC, esa.effective_from ASC, esa.id ASC",
					rs -> {
						Map<String, Object> row = com.workin.legacy.LegacyJdbcValues.rowMapper().mapRow(rs, 0);
						Object owner = row.remove(WARM_EMPLOYEE_KEY);
						long employeeId = owner instanceof Number number ? number.longValue()
								: Long.parseLong(String.valueOf(owner));
						byEmployee.computeIfAbsent(employeeId, key -> new java.util.ArrayList<>()).add(row);
					}, args);
		}

		int days = (int) java.time.temporal.ChronoUnit.DAYS.between(first, last) + 1;
		for (Long employeeId : ids) {
			List<Map<String, Object>> assignments = byEmployee.getOrDefault(employeeId, List.of());
			int next = 0;
			Map<String, Object> current = null;
			Object[] answers = new Object[days];
			for (int offset = 0; offset < days; offset++) {
				String text = first.plusDays(offset).toString();
				while (next < assignments.size()
						&& text(assignments.get(next).get("effective_from")).compareTo(text) <= 0) {
					current = assignments.get(next);
					next++;
				}
				// The same map for every day an assignment is in effect: a slot is a
				// reference, not a copy. Null -- no assignment yet -- is recorded as
				// warmed, so it is not re-asked.
				answers[offset] = current;
			}
			warmedShifts.put(employeeId, first, answers);
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
		Boolean warmed = approvedLeave.get(employeeId, date);
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
	 * @param employeeIds the employees to warm, bound {@link LegacyIdBatches#SIZE} per statement;
	 *        a window longer than a report's widest ({@link LegacyWarmedDays#MAX_WINDOW_DAYS})
	 *        is not warmed
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
		if (java.time.temporal.ChronoUnit.DAYS.between(first, last) + 1 > LegacyWarmedDays.MAX_WINDOW_DAYS) {
			return;
		}
		// An employee whose every date here is already warmed -- by a wider warm
		// over the whole report, or by the same window asked twice -- costs
		// nothing to skip, and re-reading them is the per-employee statement the
		// report-wide warm exists to remove.
		ids = ids.stream().filter(id -> !approvedLeave.covers(id, first, last)).toList();
		if (ids.isEmpty()) {
			return;
		}

		Map<Long, List<String[]>> byEmployee = new LinkedHashMap<>();
		java.util.Set<Long> undecidable = new java.util.HashSet<>();
		for (List<Long> batch : LegacyIdBatches.of(ids)) {
			Object[] args = new Object[batch.size() + 2];
			for (int i = 0; i < batch.size(); i++) {
				args[i] = batch.get(i);
			}
			args[batch.size()] = to;
			args[batch.size() + 1] = from;
			jdbcTemplate.query(
					"SELECT r.employee_id AS " + WARM_EMPLOYEE_KEY + ", r.from_date, r.to_date"
							+ " FROM requests r"
							+ " INNER JOIN request_types t ON t.id = r.request_type_id"
							+ " WHERE r.employee_id IN (" + LegacyIdBatches.placeholders(batch.size()) + ")"
							+ " AND r.status = 'approved' AND t.counts_as_paid_leave = 1"
							+ " AND r.from_date <= ? AND r.to_date >= ?",
					rs -> {
						long employeeId = rs.getLong(WARM_EMPLOYEE_KEY);
						String fromDate = rs.getString("from_date");
						String toDate = rs.getString("to_date");
						if (fromDate == null || toDate == null) {
							// A date the driver cannot hand back as text cannot be
							// compared here, so this employee is left to the query.
							undecidable.add(employeeId);
							return;
						}
						byEmployee.computeIfAbsent(employeeId, key -> new java.util.ArrayList<>())
								.add(new String[] { fromDate, toDate });
					}, args);
		}

		int days = (int) java.time.temporal.ChronoUnit.DAYS.between(first, last) + 1;
		for (Long employeeId : ids) {
			if (undecidable.contains(employeeId)) {
				continue;
			}
			List<String[]> leaves = byEmployee.getOrDefault(employeeId, List.of());
			Object[] answers = new Object[days];
			for (int offset = 0; offset < days; offset++) {
				String text = first.plusDays(offset).toString();
				boolean covered = false;
				for (String[] leave : leaves) {
					if (leave[0].compareTo(text) <= 0 && leave[1].compareTo(text) >= 0) {
						covered = true;
						break;
					}
				}
				answers[offset] = covered ? Boolean.TRUE : Boolean.FALSE;
			}
			// Recorded only after the statement returned: a warm whose query threw
			// leaves nothing behind, and a later call warms again.
			approvedLeave.put(employeeId, first, answers);
		}
	}

	/**
	 * Days before a report's first date that its per-day rules read: weekly-rest
	 * credit walks back up to seven days to the start of a rest block
	 * ({@link LegacyWeeklyRestCredit#blockStart}) and seven more over the
	 * workdays before it.
	 */
	public static final int REPORT_LOOKBACK_DAYS = 14;

	/**
	 * Days after a report's last date that its per-day rules read: an open
	 * punch's deadline looks up to eight days ahead
	 * ({@code LegacyAttendanceSessions.openSessionDeadline}).
	 */
	public static final int REPORT_LOOKAHEAD_DAYS = 8;

	/**
	 * Reads, in a fixed number of statements, everything the per-day rules ask
	 * this calendar about a set of employees over {@code [from, to]} (D-292).
	 *
	 * <p>A report asks {@link #shiftForEmployeeOnDate}, {@link #isOnApprovedLeave},
	 * {@link #holidaysByDate}, the no-shift fallback hours and
	 * {@link LegacyAttendanceWorkedMinutes#approvedTimedRequestForDay} once per
	 * employee per day, and each used to be its own statement -- the whole of
	 * the report endpoints' cost, measured in D-292. After this they are map
	 * lookups. Each warm selects the same rows and decides each date with the
	 * same comparison as the query it stands in for, and a date outside what was
	 * warmed still falls back to that query, so a warm changes what a request
	 * costs and never what it answers.
	 *
	 * @param employeeIds the report's employees, any number of them
	 * @param from        the report's first date, ISO
	 * @param to          the report's last date, ISO
	 */
	public void warmReportRange(long companyId, java.util.Collection<Long> employeeIds, String from, String to) {
		List<Long> ids = LegacyIdBatches.usable(employeeIds);
		LocalDate first = isoDate(from);
		LocalDate last = isoDate(to);
		if (ids.isEmpty() || first == null || last == null || last.isBefore(first)) {
			return;
		}
		// Past a report's widest range nothing is warmed and every date goes to
		// its own statement, as before D-292. The per-day warms hold a slot per
		// employee per day, so without this a request naming 0001-01-01 to
		// 9999-12-31 -- which stats.php accepts -- would allocate millions.
		if (java.time.temporal.ChronoUnit.DAYS.between(first, last) + 1 > LegacyReportRange.MAX_DAYS) {
			return;
		}
		String lookback = first.minusDays(REPORT_LOOKBACK_DAYS).toString();
		String lookahead = last.plusDays(REPORT_LOOKAHEAD_DAYS).toString();
		warmHolidays(companyId, lookback, lookahead);
		warmShiftsForEmployees(ids, lookback, lookahead);
		warmApprovedLeaveForEmployees(ids, lookback, to);
		warmTimedRequestsForEmployees(ids, lookback, lookahead);
		warmFallbackHours(ids);
	}

	/**
	 * Reads a company's holidays over {@code [from, to]} once, so that every
	 * narrower {@link #holidaysByDate} inside it -- {@link #expectedForDay} asks
	 * for one date at a time -- is answered from memory.
	 *
	 * <p>The narrower answer is the wider one filtered to its bounds, which is
	 * the same map the narrower query builds: the same rows, because
	 * {@code holiday_date} is a {@code DATE} compared lexically as ISO text; the
	 * same order, because both are {@code holiday_date ASC}; and no collapsed
	 * duplicates, because {@code uq_company_holiday_date} allows one row per
	 * company and date.
	 */
	public void warmHolidays(long companyId, String from, String to) {
		if (companyId <= 0 || isoDate(from) == null || isoDate(to) == null) {
			return;
		}
		Map<String, String> byDate = holidaysByDate(companyId, from, to);
		holidayWindows.computeIfAbsent(companyId, key -> new java.util.ArrayList<>())
				.add(new HolidayWindow(from, to, byDate));
	}

	private Map<String, String> holidaysFromWarmedWindow(long companyId, String from, String to) {
		List<HolidayWindow> windows = holidayWindows.get(companyId);
		if (windows == null || isoDate(from) == null || isoDate(to) == null) {
			return null;
		}
		for (HolidayWindow window : windows) {
			if (window.from().compareTo(from) <= 0 && window.to().compareTo(to) >= 0) {
				Map<String, String> filtered = new LinkedHashMap<>();
				window.byDate().forEach((date, name) -> {
					if (date.compareTo(from) >= 0 && date.compareTo(to) <= 0) {
						filtered.put(date, name);
					}
				});
				return filtered;
			}
		}
		return null;
	}

	/**
	 * {@link #FALLBACK_HOURS} for a whole roster in one statement per batch, so
	 * {@link #expectedForDay} stops asking it once per day an employee has no
	 * shift, and {@code payroll_employee_work_hours_per_day()}'s identical
	 * statement once per employee.
	 */
	public void warmFallbackHours(java.util.Collection<Long> employeeIds) {
		List<Long> ids = LegacyIdBatches.usable(employeeIds).stream()
				.filter(id -> !fallbackHoursCache.containsKey(id)).toList();
		for (List<Long> batch : LegacyIdBatches.of(ids)) {
			jdbcTemplate.query(
					"SELECT e.id AS " + WARM_EMPLOYEE_KEY + ","
							+ " COALESCE(NULLIF(e.expected_daily_hours, 0), NULLIF(jt.work_hours, 0), 8) AS hours"
							+ " FROM employees e"
							+ " LEFT JOIN job_titles jt ON jt.id = e.job_title_id"
							+ " WHERE e.id IN (" + LegacyIdBatches.placeholders(batch.size()) + ")",
					rs -> {
						java.math.BigDecimal hours = rs.getBigDecimal("hours");
						if (hours != null) {
							fallbackHoursCache.put(rs.getLong(WARM_EMPLOYEE_KEY), hours);
						}
					}, batch.toArray());
		}
	}

	/**
	 * The fallback hours {@link #warmFallbackHours} read for this employee, or
	 * null when it did not -- the caller then runs its own statement, which for
	 * an employee that does not exist is the exception it always was.
	 */
	public java.math.BigDecimal warmedFallbackHours(long employeeId) {
		return fallbackHoursCache.get(employeeId);
	}

	/**
	 * {@code attendance_approved_timed_request_for_day()} for a roster and a
	 * range in one statement per batch.
	 *
	 * <p>The per-day statement returns the approved request with the highest id
	 * whose {@code [from_date, to_date]} covers the date and whose from- and
	 * to-times are both set. This selects every such request overlapping the
	 * range -- the same filter, with the overlap form of the date test -- and
	 * decides each date with the per-day comparison, keeping the highest id.
	 * The dates are compared as ISO text, which is only right because both
	 * columns are {@code DATE}; see {@link #warmApprovedLeaveForEmployees} for
	 * why that is the fragile part and what holds it.
	 *
	 * <p>An employee with a request whose date cannot be read back as text is
	 * not warmed at all, so that employee's dates still go to the per-day
	 * statement: a warm that cannot decide a date must not decide it.
	 */
	public void warmTimedRequestsForEmployees(java.util.Collection<Long> employeeIds, String from, String to) {
		List<Long> ids = LegacyIdBatches.usable(employeeIds);
		LocalDate first = isoDate(from);
		LocalDate last = isoDate(to);
		if (ids.isEmpty() || first == null || last == null || last.isBefore(first)
				|| java.time.temporal.ChronoUnit.DAYS.between(first, last) + 1 > LegacyWarmedDays.MAX_WINDOW_DAYS) {
			return;
		}
		record Timed(long id, String fromDate, String toDate, String fromTime, String toTime) {
		}
		Map<Long, List<Timed>> byEmployee = new HashMap<>();
		java.util.Set<Long> undecidable = new java.util.HashSet<>();
		for (List<Long> batch : LegacyIdBatches.of(ids)) {
			Object[] args = new Object[batch.size() + 2];
			for (int i = 0; i < batch.size(); i++) {
				args[i] = batch.get(i);
			}
			args[batch.size()] = to;
			args[batch.size() + 1] = from;
			jdbcTemplate.query(
					"SELECT id, employee_id AS " + WARM_EMPLOYEE_KEY + ", from_date, to_date, from_time, to_time"
							+ " FROM requests"
							+ " WHERE employee_id IN (" + LegacyIdBatches.placeholders(batch.size()) + ")"
							+ " AND status = 'approved'"
							+ " AND from_date <= ? AND to_date >= ?"
							+ " AND from_time IS NOT NULL AND TRIM(from_time) <> ''"
							+ " AND to_time IS NOT NULL AND TRIM(to_time) <> ''",
					rs -> {
						long employeeId = rs.getLong(WARM_EMPLOYEE_KEY);
						String fromDate = rs.getString("from_date");
						String toDate = rs.getString("to_date");
						if (fromDate == null || toDate == null) {
							undecidable.add(employeeId);
							return;
						}
						byEmployee.computeIfAbsent(employeeId, key -> new java.util.ArrayList<>()).add(new Timed(
								rs.getLong("id"), fromDate, toDate, rs.getString("from_time"),
								rs.getString("to_time")));
					}, args);
		}
		int days = (int) java.time.temporal.ChronoUnit.DAYS.between(first, last) + 1;
		for (Long employeeId : ids) {
			if (undecidable.contains(employeeId)) {
				continue;
			}
			List<Timed> requests = byEmployee.getOrDefault(employeeId, List.of());
			Object[] answers = new Object[days];
			for (int offset = 0; offset < days; offset++) {
				String text = first.plusDays(offset).toString();
				Timed winner = null;
				for (Timed request : requests) {
					if (request.fromDate().compareTo(text) <= 0 && request.toDate().compareTo(text) >= 0
							&& (winner == null || request.id() > winner.id())) {
						winner = request;
					}
				}
				answers[offset] = winner == null ? null
						: new LegacyAttendanceWorkedMinutes.TimedRequest(winner.fromTime(), winner.toTime());
			}
			timedRequests.put(employeeId, first, answers);
		}
	}

	/** Whether {@link #warmTimedRequestsForEmployees} decided this employee and date. */
	public boolean timedRequestWarmed(long employeeId, String date) {
		return timedRequests.get(employeeId, date) != NOT_WARMED_TIMED;
	}

	/**
	 * The warmed timed request for this employee and date, untrimmed, or null
	 * when there is none -- meaningful only when {@link #timedRequestWarmed}.
	 */
	public LegacyAttendanceWorkedMinutes.TimedRequest warmedTimedRequest(long employeeId, String date) {
		LegacyAttendanceWorkedMinutes.TimedRequest warmed = timedRequests.get(employeeId, date);
		return warmed == NOT_WARMED_TIMED ? null : warmed;
	}

	/** Employee-days the per-day warms hold, for a test to bound what one request can allocate. */
	long warmedSlotCount() {
		return warmedShifts.slotCount() + approvedLeave.slotCount() + timedRequests.slotCount();
	}

	/** An ISO {@code yyyy-MM-dd} string as a date, or null for anything else. */
	private static LocalDate isoDate(String value) {
		if (value == null || value.length() != 10) {
			return null;
		}
		try {
			return LocalDate.parse(value);
		} catch (java.time.format.DateTimeParseException unparseable) {
			return null;
		}
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
		Map<String, String> fromWindow = holidaysFromWarmedWindow(companyId, from, to);
		if (fromWindow != null) {
			holidayCache.put(key, fromWindow);
			return fromWindow;
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

package com.workin.legacy.attendance.session;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.workin.legacy.LegacyClock;
import com.workin.legacy.LegacyJdbcValues;
import com.workin.legacy.LegacyPhpStrtotime;
import com.workin.legacy.attendance.calendar.LegacyAttendanceCalendar;

/**
 * Open attendance sessions, and the stale-session auto-close that happens while
 * looking for one.
 *
 * <p>{@code attendance_session_helper.php:11-245}. All three Wave 12.6.3
 * endpoints reach this before they do anything of their own.
 *
 * <h2>This is a write-bearing read</h2>
 * <p>{@link #findOpenSession} is spelled like a lookup and is not one.
 * {@code attendance_find_open_session()} calls
 * {@code attendance_auto_close_stale_open_sessions()}, which <b>UPDATEs</b>
 * every open row of the employee whose deadline has passed, writing a synthetic
 * {@code check_out}. So a {@code check_in} request can mutate an older
 * attendance row before it reaches its own INSERT, and a request that then
 * fails -- on the two-hour rule, on the geofence, on anything -- leaves that
 * auto-close <b>committed</b>, because PHP opens no transaction anywhere on
 * this path.
 *
 * <p>Nothing here is annotated read-only or transactional, deliberately. The
 * partial write is the contract, and
 * {@code LegacyCheckInEndToEndTest.aFailedCheckInStillLeavesTheAutoCloseCommitted}
 * pins it.
 *
 * <h2>The deadline is a calendar walk, not a duration</h2>
 * <p>An open session is stale once the employee's <em>next working day's shift
 * start</em> has passed -- found by walking up to eight days forward and
 * skipping rest days. Only if none of those eight days yields a start later
 * than the check-in does it fall back to check-in + 18 hours. So an employee
 * with Friday and Saturday off who checks in on Thursday evening stays open all
 * weekend.
 */
@Component
public class LegacyAttendanceSessions {

	/** {@code attendance_open_session_max_hours()}. */
	private static final int MAX_OPEN_HOURS = 18;

	/** How many days forward {@code attendance_open_session_deadline()} looks. */
	private static final int DEADLINE_SCAN_DAYS = 8;

	/** {@code ATTENDANCE_INCOMPLETE_PUNCH_DEDUCTION_MINUTES}. */
	private static final int INCOMPLETE_PUNCH_DEDUCTION_MINUTES = 120;

	/** The shift-start fallback when the origin day has no usable one. */
	private static final String DEFAULT_SHIFT_START = "09:00:00";

	private static final Pattern HOUR_MINUTE = Pattern.compile("^\\d{1,2}:\\d{2}$");

	private static final Pattern HOUR_MINUTE_SECOND = Pattern.compile("^\\d{1,2}:\\d{2}:\\d{2}$");

	private static final java.time.format.DateTimeFormatter SQL_DATE_TIME =
			java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

	private final JdbcTemplate jdbcTemplate;
	private final LegacyAttendanceCalendar calendar;
	private final LegacyClock clock;

	public LegacyAttendanceSessions(
			DataSource legacyDataSource, LegacyAttendanceCalendar calendar, LegacyClock clock) {
		this.jdbcTemplate = new JdbcTemplate(legacyDataSource);
		this.calendar = calendar;
		this.clock = clock;
	}

	/**
	 * {@code attendance_find_open_session($employee_id, $full_row)}
	 * ({@code attendance_session_helper.php:170-225}).
	 *
	 * <p>Order matters and is PHP's: resolve the employee's company, auto-close
	 * stale sessions, <em>then</em> select the newest open row by id. A row that
	 * survives the auto-close can still be discarded afterwards -- if its own
	 * deadline has passed the helper closes the set again and returns null, so a
	 * caller never receives a session it would consider stale.
	 *
	 * @param fullRow {@code true} selects {@code *}, as {@code check_out.php}
	 *        needs; {@code false} selects the five columns the check-in paths use
	 * @return the open row, or null -- which is what "not checked in" means
	 */
	public Map<String, Object> findOpenSession(long employeeId, boolean fullRow, String weeklyRestLabel) {
		if (employeeId <= 0) {
			return null;
		}
		long companyId = employeeCompanyId(employeeId);
		if (companyId > 0) {
			autoCloseStaleOpenSessions(companyId, employeeId, weeklyRestLabel);
		}

		String projection = fullRow ? "*" : "id, check_in, latitude, longitude, exception_type_id";
		List<Map<String, Object>> rows = jdbcTemplate.query("""
				SELECT %s FROM attendance
				WHERE employee_id = ? AND check_out IS NULL AND check_in IS NOT NULL
				ORDER BY id DESC LIMIT 1""".formatted(projection),
				LegacyJdbcValues.rowMapper(), employeeId);
		if (rows.isEmpty()) {
			return null;
		}
		Map<String, Object> row = rows.get(0);

		String checkIn = trimmed(row.get("check_in"));
		if (checkIn.isEmpty()) {
			return null;
		}
		// A synthetic exception-only row is never an open session.
		if (isExceptionOnlyRow(checkIn, null, row.get("exception_type_id"))) {
			return null;
		}
		if (companyId <= 0) {
			return row;
		}

		LocalDateTime deadline = openSessionDeadline(companyId, employeeId, checkIn, weeklyRestLabel);
		if (!clock.now().isBefore(deadline)) {
			autoCloseStaleOpenSessions(companyId, employeeId, weeklyRestLabel);
			return null;
		}
		return row;
	}

	/**
	 * {@code attendance_auto_close_stale_open_sessions()}
	 * ({@code attendance_session_helper.php:80-150}).
	 *
	 * <p>Every open row of the employee, oldest first. A row is closed at
	 * {@code check_in + (expected_minutes - 120)} minutes, floored at zero --
	 * so an employee whose expected day is under two hours is closed at exactly
	 * their check-in time, and a rest-day check-in (expected zero) is too.
	 *
	 * <p>The UPDATE re-tests {@code check_out IS NULL}, which is the only
	 * concurrency guard on this path.
	 *
	 * @return how many rows were closed, as PHP's counter does
	 */
	public int autoCloseStaleOpenSessions(long companyId, long employeeId, String weeklyRestLabel) {
		if (employeeId <= 0 || companyId <= 0) {
			return 0;
		}
		LocalDateTime asOf = clock.now();
		List<Map<String, Object>> rows = jdbcTemplate.query("""
				SELECT id, check_in, exception_type_id FROM attendance
				WHERE employee_id = ? AND check_out IS NULL AND check_in IS NOT NULL
				ORDER BY id ASC""", LegacyJdbcValues.rowMapper(), employeeId);

		int closed = 0;
		for (Map<String, Object> row : rows) {
			String checkIn = trimmed(row.get("check_in"));
			if (checkIn.isEmpty()) {
				continue;
			}
			if (isExceptionOnlyRow(checkIn, null, row.get("exception_type_id"))) {
				continue;
			}
			LocalDateTime deadline = openSessionDeadline(companyId, employeeId, checkIn, weeklyRestLabel);
			if (asOf.isBefore(deadline)) {
				continue;
			}
			LocalDateTime checkInAt = LegacyPhpStrtotime.dateTimeOf(checkIn, asOf);
			if (checkInAt == null) {
				continue;
			}

			LegacyAttendanceCalendar.DayExpectation expected = calendar.expectedForDay(
					companyId, employeeId, checkInAt.toLocalDate().toString(), weeklyRestLabel);
			int worked = Math.max(0, expected.expectedMinutes() - INCOMPLETE_PUNCH_DEDUCTION_MINUTES);
			String checkOut = checkInAt.plusMinutes(worked).format(SQL_DATE_TIME);

			closed += jdbcTemplate.update(
					"UPDATE attendance SET check_out = ? WHERE id = ? AND check_out IS NULL",
					checkOut, row.get("id"));
		}
		return closed;
	}

	/**
	 * {@code attendance_open_session_deadline()}
	 * ({@code attendance_session_helper.php:40-72}).
	 *
	 * <p>The next working day's shift start after the check-in. Rest days are
	 * skipped; a candidate whose start is not after the check-in is skipped too,
	 * which is what stops a same-evening check-in being closed by that same
	 * morning's start.
	 *
	 * <p>The per-candidate start falls back to the <em>origin day's</em> start,
	 * and only then to 09:00:00 -- so an employee with no shift at all gets
	 * 09:00 tomorrow, not eighteen hours.
	 */
	public LocalDateTime openSessionDeadline(
			long companyId, long employeeId, String checkInDateTime, String weeklyRestLabel) {
		LocalDateTime checkIn = LegacyPhpStrtotime.dateTimeOf(checkInDateTime, clock.now());
		if (checkIn == null) {
			return clock.now().plusHours(MAX_OPEN_HOURS);
		}
		LegacyAttendanceCalendar.DayExpectation origin = calendar.expectedForDay(
				companyId, employeeId, checkIn.toLocalDate().toString(), weeklyRestLabel);
		String fallbackStart = normalizeShiftClock(origin.shiftStart());
		if (fallbackStart == null) {
			fallbackStart = DEFAULT_SHIFT_START;
		}

		for (int day = 1; day <= DEADLINE_SCAN_DAYS; day++) {
			LocalDate candidate = checkIn.toLocalDate().plusDays(day);
			LegacyAttendanceCalendar.DayExpectation expected = calendar.expectedForDay(
					companyId, employeeId, candidate.toString(), weeklyRestLabel);
			if (expected.restDay()) {
				continue;
			}
			String start = normalizeShiftClock(expected.shiftStart());
			if (start == null) {
				start = fallbackStart;
			}
			LocalDateTime deadline = LocalDateTime.parse(candidate + "T" + start);
			if (deadline.isAfter(checkIn)) {
				return deadline;
			}
		}
		return checkIn.plusHours(MAX_OPEN_HOURS);
	}

	/**
	 * {@code attendance_normalize_shift_clock()}
	 * ({@code attendance_session_helper.php:19-33}).
	 *
	 * <p>{@code H:i} gains {@code :00}, {@code H:i:s} passes through, and
	 * anything else -- including a stray date, a bare hour or an out-of-range
	 * clock -- is null rather than an error. The patterns do not bound the
	 * values, so {@code 99:99} normalizes and only fails later, when it is
	 * parsed as a datetime.
	 */
	public static String normalizeShiftClock(String time) {
		String trimmed = time == null ? "" : time.trim();
		if (trimmed.isEmpty()) {
			return null;
		}
		if (HOUR_MINUTE.matcher(trimmed).matches()) {
			return trimmed + ":00";
		}
		return HOUR_MINUTE_SECOND.matcher(trimmed).matches() ? trimmed : null;
	}

	/**
	 * {@code attendance_is_exception_only_row()}
	 * ({@code attendance_calendar_helper.php:12-26}).
	 *
	 * <p>A row is exception-only when it has a positive exception type, no
	 * check-out, and a check-in whose <b>time is exactly midnight</b>. That is
	 * how the import writes a leave or holiday marker, and it is why such rows
	 * are never treated as an open session and never auto-closed: they are not
	 * punches.
	 */
	public static boolean isExceptionOnlyRow(String checkIn, String checkOut, Object exceptionTypeId) {
		long exception = exceptionTypeId == null ? 0L : com.workin.legacy.LegacyValues.toPhpLong(exceptionTypeId);
		boolean hasException = exceptionTypeId != null && exception > 0;
		boolean hasOut = checkOut != null && !checkOut.trim().isEmpty();
		String checkInText = checkIn == null ? "" : checkIn.trim();
		if (!hasException || hasOut || checkInText.isEmpty()) {
			return false;
		}
		LocalDateTime parsed = LegacyPhpStrtotime.dateTimeOf(checkInText, LocalDateTime.now());
		return parsed != null && parsed.toLocalTime().equals(java.time.LocalTime.MIDNIGHT);
	}

	/** {@code attendance_employee_company_id()}: zero when the employee does not exist. */
	public long employeeCompanyId(long employeeId) {
		if (employeeId <= 0) {
			return 0L;
		}
		List<Long> values = jdbcTemplate.queryForList(
				"SELECT company_id FROM employees WHERE id = ?", Long.class, employeeId);
		return values.isEmpty() || values.get(0) == null ? 0L : values.get(0);
	}

	private static String trimmed(Object value) {
		return value == null ? "" : String.valueOf(value).trim();
	}

}

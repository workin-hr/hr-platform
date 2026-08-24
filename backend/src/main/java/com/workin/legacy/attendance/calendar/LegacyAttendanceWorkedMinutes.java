package com.workin.legacy.attendance.calendar;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.workin.legacy.LegacyClock;
import com.workin.legacy.LegacyPhpStrtotime;
import com.workin.legacy.attendance.session.LegacyAttendanceSessions;
import com.workin.legacy.workforce.LegacyShiftTimes;

/**
 * {@code attendance_row_worked_minutes()} and the functions it alone calls
 * ({@code attendance_calendar_helper.php:53-201}) -- the single-source-of-
 * truth for one attendance day's displayed duration, shared by {@code list.php}
 * (both branches), {@code stats.php} and {@code employee_monthly_attendance.php}.
 */
@Component
public class LegacyAttendanceWorkedMinutes {

	/** {@code ATTENDANCE_INCOMPLETE_PUNCH_DEDUCTION_MINUTES}. */
	private static final int INCOMPLETE_PUNCH_DEDUCTION_MINUTES = 120;

	private static final String APPROVED_TIMED_REQUEST_FOR_DAY = """
			SELECT from_time, to_time
			FROM requests
			WHERE employee_id = ?
			  AND status = 'approved'
			  AND from_date <= ?
			  AND to_date >= ?
			  AND from_time IS NOT NULL AND TRIM(from_time) <> ''
			  AND to_time IS NOT NULL AND TRIM(to_time) <> ''
			ORDER BY id DESC
			LIMIT 1""";

	private final JdbcTemplate jdbcTemplate;
	private final LegacyAttendanceCalendar calendar;
	private final LegacyAttendanceSessions sessions;
	private final LegacyClock clock;

	public LegacyAttendanceWorkedMinutes(
			DataSource legacyDataSource, LegacyAttendanceCalendar calendar,
			LegacyAttendanceSessions sessions, LegacyClock clock) {
		this.jdbcTemplate = new JdbcTemplate(legacyDataSource);
		this.calendar = calendar;
		this.sessions = sessions;
		this.clock = clock;
	}

	/** The from/to clock pair {@link #approvedTimedRequestForDay} returns. */
	public record TimedRequest(String fromTime, String toTime) {
	}

	/**
	 * {@code attendance_approved_timed_request_for_day()}
	 * ({@code attendance_calendar_helper.php:53-84}): the newest approved
	 * request covering this day that carries both a from and to time (a
	 * "mission"), or null.
	 */
	public TimedRequest approvedTimedRequestForDay(long employeeId, String dateYmd) {
		List<TimedRequest> rows = jdbcTemplate.query(
				APPROVED_TIMED_REQUEST_FOR_DAY,
				(rs, index) -> new TimedRequest(rs.getString("from_time"), rs.getString("to_time")),
				employeeId, dateYmd, dateYmd);
		if (rows.isEmpty()) {
			return null;
		}
		TimedRequest row = rows.get(0);
		String from = row.fromTime() == null ? "" : row.fromTime().trim();
		String to = row.toTime() == null ? "" : row.toTime().trim();
		return from.isEmpty() || to.isEmpty() ? null : new TimedRequest(from, to);
	}

	/** {@code attendance_mission_window_minutes()}: {@code shift_duration_minutes()}, floored at zero. */
	private static int missionWindowMinutes(String fromTime, String toTime) {
		Integer minutes = LegacyShiftTimes.durationMinutes(fromTime, toTime);
		return Math.max(0, minutes == null ? 0 : minutes);
	}

	/** {@code attendance_has_complete_real_punches()}. */
	private static boolean hasCompleteRealPunches(String checkIn, String checkOut) {
		return checkIn != null && !checkIn.trim().isEmpty() && checkOut != null && !checkOut.trim().isEmpty();
	}

	/**
	 * {@code attendance_timed_request_worked_minutes()}
	 * ({@code attendance_calendar_helper.php:110-145}): the mission window
	 * alone unless the employee also punched a complete in/out pair, in which
	 * case it is the shift's own start (falling back to the mission's own
	 * from-time) through the actual check-out.
	 */
	public int timedRequestWorkedMinutes(
			long companyId, long employeeId, String dateYmd, String checkIn, String checkOut,
			TimedRequest timedRequest, String weeklyRestLabel) {
		int missionMinutes = missionWindowMinutes(timedRequest.fromTime(), timedRequest.toTime());
		if (!hasCompleteRealPunches(checkIn, checkOut)) {
			return missionMinutes;
		}

		LegacyAttendanceCalendar.DayExpectation expected =
				calendar.expectedForDay(companyId, employeeId, dateYmd, weeklyRestLabel);
		String shiftStart = expected.shiftStart() == null ? "" : expected.shiftStart().trim();
		if (shiftStart.isEmpty()) {
			shiftStart = timedRequest.fromTime();
		}

		LocalDateTime start = LegacyPhpStrtotime.dateTimeOf(dateYmd + " " + shiftStart, clock.now());
		LocalDateTime end = LegacyPhpStrtotime.dateTimeOf(checkOut, clock.now());
		if (start == null || end == null) {
			return missionMinutes;
		}
		if (end.isBefore(start)) {
			// Overnight checkout.
			end = end.plusDays(1);
		}
		return Math.max(0, (int) Math.round(Duration.between(start, end).getSeconds() / 60.0));
	}

	/**
	 * {@code attendance_display_duration_minutes()}
	 * ({@code attendance_calendar_helper.php:31-46}): complete punches use the
	 * raw {@code TIMESTAMPDIFF}; exactly one punch is expected minus the
	 * incomplete-punch deduction; neither punch is zero.
	 */
	private static int displayDurationMinutes(
			String checkIn, String checkOut, int rawDurationMinutes, int expectedMinutes) {
		boolean hasIn = checkIn != null && !checkIn.trim().isEmpty();
		boolean hasOut = checkOut != null && !checkOut.trim().isEmpty();
		if (hasIn && hasOut) {
			return Math.max(0, rawDurationMinutes);
		}
		if (hasIn != hasOut) {
			return Math.max(0, expectedMinutes - INCOMPLETE_PUNCH_DEDUCTION_MINUTES);
		}
		return 0;
	}

	/**
	 * {@code attendance_row_worked_minutes()}
	 * ({@code attendance_calendar_helper.php:152-201}): worked minutes for one
	 * attendance day. An approved timed request (a "mission" with both a
	 * from-time and a to-time) overrides punch-based hours entirely; an
	 * exception-only row with no such request is zero; a punch still inside
	 * its own open-session deadline is zero (hours not finalized yet).
	 */
	public int rowWorkedMinutes(
			long companyId, long employeeId, String dateYmd, String checkIn, String checkOut,
			int rawDurationMinutes, Object exceptionTypeId, String weeklyRestLabel) {
		TimedRequest timed = approvedTimedRequestForDay(employeeId, dateYmd);
		if (timed != null) {
			return timedRequestWorkedMinutes(companyId, employeeId, dateYmd, checkIn, checkOut, timed, weeklyRestLabel);
		}

		if (LegacyAttendanceSessions.isExceptionOnlyRow(checkIn, checkOut, exceptionTypeId)) {
			return 0;
		}

		boolean hasIn = checkIn != null && !checkIn.trim().isEmpty();
		boolean hasOut = checkOut != null && !checkOut.trim().isEmpty();
		if (!hasIn && !hasOut) {
			return 0;
		}

		LegacyAttendanceCalendar.DayExpectation expected =
				calendar.expectedForDay(companyId, employeeId, dateYmd, weeklyRestLabel);

		if (hasIn && !hasOut
				&& clock.now().isBefore(sessions.openSessionDeadline(companyId, employeeId, checkIn, weeklyRestLabel))) {
			return 0;
		}

		return displayDurationMinutes(checkIn, checkOut, rawDurationMinutes, expected.expectedMinutes());
	}

}

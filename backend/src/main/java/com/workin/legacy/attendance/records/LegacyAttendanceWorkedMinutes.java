package com.workin.legacy.attendance.records;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.workin.legacy.LegacyClock;
import com.workin.legacy.LegacyJdbcValues;
import com.workin.legacy.LegacyPhpStrtotime;
import com.workin.legacy.LegacyValues;
import com.workin.legacy.attendance.calendar.LegacyAttendanceCalendar;
import com.workin.legacy.workforce.LegacyShiftTimes;

/**
 * Frozen {@code attendance_calendar_helper.php} worked-minute semantics shared by
 * list/monthly/stats/report. Keeping this in one component prevents the five
 * deferred attendance routes from drifting from one another.
 */
@Component
public class LegacyAttendanceWorkedMinutes {

	static final int INCOMPLETE_PUNCH_DEDUCTION_MINUTES = 120;
	private static final int OPEN_SESSION_MAX_HOURS = 18;
	private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss");

	private static final String TIMED_REQUEST = """
			SELECT r.from_time AS from_time, r.to_time AS to_time
			FROM requests AS r
			WHERE r.employee_id = ?
			  AND r.status = 'approved'
			  AND r.from_date <= ?
			  AND r.to_date >= ?
			  AND r.from_time IS NOT NULL AND TRIM(r.from_time) <> ''
			  AND r.to_time IS NOT NULL AND TRIM(r.to_time) <> ''
			ORDER BY r.id DESC
			LIMIT 1""";

	private final JdbcTemplate jdbcTemplate;
	private final LegacyAttendanceCalendar calendar;
	private final LegacyClock clock;

	public LegacyAttendanceWorkedMinutes(
			DataSource legacyDataSource, LegacyAttendanceCalendar calendar, LegacyClock clock) {
		this.jdbcTemplate = new JdbcTemplate(legacyDataSource);
		this.calendar = calendar;
		this.clock = clock;
	}

	/** {@code attendance_row_worked_minutes(...)}. */
	public int forRow(long companyId, long employeeId, String dateYmd,
			Object checkInValue, Object checkOutValue, Object rawDurationValue,
			Object exceptionTypeId, String weeklyRestLabel) {
		String checkIn = nullableText(checkInValue);
		String checkOut = nullableText(checkOutValue);
		Map<String, Object> timed = timedRequest(employeeId, dateYmd);
		if (timed != null) {
			return timedRequestMinutes(companyId, employeeId, dateYmd, checkIn, checkOut, timed, weeklyRestLabel);
		}
		if (isExceptionOnly(checkIn, checkOut, exceptionTypeId)) {
			return 0;
		}

		boolean hasIn = nonBlank(checkIn);
		boolean hasOut = nonBlank(checkOut);
		if (!hasIn && !hasOut) {
			return 0;
		}
		LegacyAttendanceCalendar.DayExpectation expected =
				calendar.expectedForDay(companyId, employeeId, dateYmd, weeklyRestLabel);
		if (hasIn && !hasOut && isLiveOpen(companyId, employeeId, checkIn, weeklyRestLabel)) {
			return 0;
		}
		long raw = rawDurationValue == null ? 0 : LegacyValues.toPhpLong(rawDurationValue);
		return displayDuration(hasIn, hasOut, raw, expected.expectedMinutes());
	}

	static int displayDuration(boolean hasIn, boolean hasOut, long rawDurationMinutes, int expectedMinutes) {
		if (hasIn && hasOut) {
			if (rawDurationMinutes <= 0) {
				return 0;
			}
			return (int) Math.min(Integer.MAX_VALUE, rawDurationMinutes);
		}
		if (hasIn ^ hasOut) {
			return Math.max(0, expectedMinutes - INCOMPLETE_PUNCH_DEDUCTION_MINUTES);
		}
		return 0;
	}

	static boolean isExceptionOnly(String checkIn, String checkOut, Object exceptionTypeId) {
		if (exceptionTypeId == null || LegacyValues.toPhpLong(exceptionTypeId) <= 0 || nonBlank(checkOut) || !nonBlank(checkIn)) {
			return false;
		}
		LocalDateTime parsed = LegacyPhpStrtotime.dateTimeOf(checkIn, LocalDateTime.of(2000, 1, 1, 0, 0));
		return parsed != null && parsed.toLocalTime().equals(LocalTime.MIDNIGHT);
	}

	private Map<String, Object> timedRequest(long employeeId, String dateYmd) {
		List<Map<String, Object>> rows = jdbcTemplate.query(
				TIMED_REQUEST, LegacyJdbcValues.rowMapper(), employeeId, dateYmd, dateYmd);
		if (rows.isEmpty()) {
			return null;
		}
		String from = LegacyValues.phpTrim(LegacyValues.toPhpString(rows.get(0).get("from_time")));
		String to = LegacyValues.phpTrim(LegacyValues.toPhpString(rows.get(0).get("to_time")));
		return from.isEmpty() || to.isEmpty() ? null : Map.of("from_time", from, "to_time", to);
	}

	private int timedRequestMinutes(long companyId, long employeeId, String dateYmd,
			String checkIn, String checkOut, Map<String, Object> timed, String weeklyRestLabel) {
		String from = String.valueOf(timed.get("from_time"));
		String to = String.valueOf(timed.get("to_time"));
		Integer mission = LegacyShiftTimes.durationMinutes(from, to);
		int missionMinutes = Math.max(0, mission == null ? 0 : mission);
		if (!nonBlank(checkIn) || !nonBlank(checkOut)) {
			return missionMinutes;
		}

		LegacyAttendanceCalendar.DayExpectation expected =
				calendar.expectedForDay(companyId, employeeId, dateYmd, weeklyRestLabel);
		String shiftStart = LegacyValues.phpTrim(expected.shiftStart() == null ? "" : expected.shiftStart());
		if (shiftStart.isEmpty()) {
			shiftStart = from;
		}
		LocalDateTime start = LegacyPhpStrtotime.dateTimeOf(dateYmd + " " + shiftStart, clock.now());
		LocalDateTime end = LegacyPhpStrtotime.dateTimeOf(checkOut, clock.now());
		if (start == null || end == null) {
			return missionMinutes;
		}
		if (end.isBefore(start)) {
			end = end.plusDays(1);
		}
		return Math.max(0, (int) Math.round(java.time.Duration.between(start, end).toSeconds() / 60d));
	}

	private boolean isLiveOpen(long companyId, long employeeId, String checkIn, String weeklyRestLabel) {
		LocalDateTime parsed = LegacyPhpStrtotime.dateTimeOf(checkIn, clock.now());
		if (parsed == null) {
			return false;
		}
		return clock.now().isBefore(openDeadline(companyId, employeeId, parsed, weeklyRestLabel));
	}

	/** {@code attendance_open_session_deadline(...)}. */
	LocalDateTime openDeadline(long companyId, long employeeId, LocalDateTime checkIn, String weeklyRestLabel) {
		String originDay = checkIn.toLocalDate().toString();
		LegacyAttendanceCalendar.DayExpectation origin =
				calendar.expectedForDay(companyId, employeeId, originDay, weeklyRestLabel);
		String fallbackStart = normalizeClock(origin.shiftStart());
		if (fallbackStart == null) {
			fallbackStart = "09:00:00";
		}
		for (int i = 1; i <= 8; i++) {
			LocalDate day = checkIn.toLocalDate().plusDays(i);
			LegacyAttendanceCalendar.DayExpectation expected =
					calendar.expectedForDay(companyId, employeeId, day.toString(), weeklyRestLabel);
			if (expected.restDay()) {
				continue;
			}
			String startText = normalizeClock(expected.shiftStart());
			if (startText == null) {
				startText = fallbackStart;
			}
			LocalDateTime deadline = LocalDateTime.of(day, LocalTime.parse(startText, CLOCK));
			if (deadline.isAfter(checkIn)) {
				return deadline;
			}
		}
		return checkIn.plusHours(OPEN_SESSION_MAX_HOURS);
	}

	static String normalizeClock(String value) {
		String text = LegacyValues.phpTrim(value == null ? "" : value);
		if (text.matches("^\\d{1,2}:\\d{2}$")) {
			text += ":00";
		}
		if (!text.matches("^\\d{1,2}:\\d{2}:\\d{2}$")) {
			return null;
		}
		String[] parts = text.split(":");
		int hour = Integer.parseInt(parts[0]);
		int minute = Integer.parseInt(parts[1]);
		int second = Integer.parseInt(parts[2]);
		if (hour > 23 || minute > 59 || second > 59) {
			return null;
		}
		return String.format("%02d:%02d:%02d", hour, minute, second);
	}

	private static boolean nonBlank(String value) {
		return value != null && !LegacyValues.phpTrim(value).isEmpty();
	}

	private static String nullableText(Object value) {
		return value == null ? null : String.valueOf(value);
	}
}

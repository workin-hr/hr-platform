package com.workin.backend.attendance;

import java.time.Instant;
import java.time.LocalDate;

/**
 * One classified calendar day — the port of legacy's per-day map from
 * {@code attendance_build_employee_range_calendar}
 * ({@code attendance_calendar_helper.php:277-420} @ d113204), key for
 * key, minus the weekly-rest-credit fields that belong to the next
 * slice.
 *
 * <p>Every day in the requested range gets a row, including rest days
 * and days with no attendance at all — that is the whole point of the
 * endpoint, and it is where it differs from the schedule module's
 * monthly overview, which omits unassigned days entirely.
 *
 * @param date                    the calendar day (UTC — see {@link AttendanceRules})
 * @param attendanceId            the real row's id, or null when the day is synthesized
 * @param id                      the real id, or a stable negative synthetic id for a day with no row
 * @param checkIn                 null on synthetic days <b>and</b> on exception-only days, where legacy hides the midnight punch
 * @param checkOut                null unless a real completed punch exists
 * @param durationMinutes         minutes credited for the day
 * @param expectedDurationMinutes scheduled minutes; legacy reports these only on a normal punched day and zero everywhere else, which this port preserves
 * @param exceptionTypeId         the row's exception type, when it has one
 * @param exceptionTypeName       the exception label, or the rest/holiday label on a synthesized rest day
 * @param isMissing               a working day with nothing credited to it — an absence
 * @param isWeeklyRest            weekly rest, and not an official holiday (a holiday outranks it)
 * @param isOfficialHoliday       the day is an official holiday, which outranks weekly rest
 * @param weeklyRestCredit        earned/void/pending on a weekly-rest day, null on any other day
 * @param isWeeklyRestVoid        shorthand for {@code weeklyRestCredit == VOID} — legacy reports both
 */
public record CalendarDayView(
		LocalDate date,
		Long attendanceId,
		long id,
		Instant checkIn,
		Instant checkOut,
		int durationMinutes,
		int expectedDurationMinutes,
		Long exceptionTypeId,
		String exceptionTypeName,
		boolean isMissing,
		boolean isWeeklyRest,
		boolean isOfficialHoliday,
		WeeklyRestCredit weeklyRestCredit,
		boolean isWeeklyRestVoid) {

	static CalendarDayView of(
			LocalDate date, Long attendanceId, long id, Instant checkIn, Instant checkOut,
			int durationMinutes, int expectedDurationMinutes, Long exceptionTypeId, String exceptionTypeName,
			boolean isMissing, boolean isWeeklyRest, boolean isOfficialHoliday, WeeklyRestCredit credit) {
		return new CalendarDayView(
				date, attendanceId, id, checkIn, checkOut, durationMinutes, expectedDurationMinutes,
				exceptionTypeId, exceptionTypeName, isMissing, isWeeklyRest, isOfficialHoliday,
				credit, credit == WeeklyRestCredit.VOID);
	}

}

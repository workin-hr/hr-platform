package com.workin.backend.organization;

import java.time.LocalTime;

/**
 * Ported 1:1 from hr-legacy/apis/helpers/shift_times.php @ d113204
 * ({@code shift_time_string_to_minutes} + {@code shift_duration_minutes}).
 * Referenced by the schedule, attendance-calendar, and session helpers
 * there; the same single seam here.
 *
 * <p>Two legacy behaviours are deliberate ports, not oversights:
 *
 * <ul>
 * <li><b>Seconds are discarded.</b> Legacy's regex captures the seconds
 * group and then never reads it ({@code shift_times.php:16,25}), so
 * {@code 17:00:59} behaves as {@code 17:00}. Truncating to whole
 * minutes here reproduces that; using {@code LocalTime} arithmetic
 * directly would not.</li>
 * <li><b>A 24-hour shift is inexpressible.</b> {@code end == start}
 * yields {@code 0}, not {@code 1440} ({@code shift_times.php:37-39}).
 * Range of non-null results is 0..1439.</li>
 * </ul>
 */
public final class ShiftTimes {

	private static final int MINUTES_PER_DAY = 24 * 60;

	private ShiftTimes() {
	}

	/**
	 * Overnight-safe shift length. Null when either bound is absent --
	 * legacy's {@code ?int} return, which every caller coerces to 0.
	 */
	public static Integer durationMinutes(LocalTime start, LocalTime end) {
		if (start == null || end == null) {
			return null;
		}
		int startMinutes = minutesOfDay(start);
		int endMinutes = minutesOfDay(end);
		if (endMinutes > startMinutes) {
			return endMinutes - startMinutes;
		}
		if (endMinutes == startMinutes) {
			return 0;
		}
		return (MINUTES_PER_DAY - startMinutes) + endMinutes;
	}

	/** Same contract, already coerced the way legacy's callers coerce it. */
	public static int durationMinutesOrZero(LocalTime start, LocalTime end) {
		Integer duration = durationMinutes(start, end);
		return duration == null ? 0 : Math.max(0, duration);
	}

	private static int minutesOfDay(LocalTime time) {
		return time.getHour() * 60 + time.getMinute();
	}

}

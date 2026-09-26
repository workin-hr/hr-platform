package com.workin.legacy.attendance.calendar;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

/**
 * One per-day answer per employee, warmed for a range of days: an array per
 * employee indexed by day offset (D-292).
 *
 * <p>The warms used to key a hash map by {@code "employeeId|date"} strings, one
 * entry of roughly a hundred bytes per employee per day per kind of answer. An
 * array slot is four, and an answer that is the same object on many days -- a
 * shift map, {@code Boolean.TRUE} -- is shared rather than copied.
 *
 * <p>Three states per slot, because the calendar needs all three: not warmed
 * (the caller runs its own statement), warmed with an answer, and warmed with
 * no answer (a date before any shift assignment, no timed request).
 */
final class LegacyWarmedDays<T> {

	/** Stored for "warmed, and the answer is null", so an empty slot can mean "not warmed". */
	private static final Object NONE = new Object();

	private static final class Window {
		private final long firstDay;
		private final Object[] slots;

		private Window(long firstDay, Object[] slots) {
			this.firstDay = firstDay;
			this.slots = slots;
		}

		private long lastDay() {
			return firstDay + slots.length - 1;
		}
	}

	/**
	 * The longest window one warm may hold: a report's widest range plus the
	 * days before and after it the per-day rules read. A wider request is not
	 * warmed at all, and its dates go to the per-date statements -- slower, but
	 * bounded by the request's own rows rather than by the width of the range
	 * it names (a {@code stats.php} for 0001-01-01..9999-12-31 is a valid
	 * request).
	 */
	static final int MAX_WINDOW_DAYS = LegacyReportRange.MAX_DAYS
			+ LegacyAttendanceCalendar.REPORT_LOOKBACK_DAYS + LegacyAttendanceCalendar.REPORT_LOOKAHEAD_DAYS;

	/** Two overlapping or adjacent warms are merged up to this span; past it the newer one replaces the older. */
	private static final int MAX_MERGED_DAYS = 2 * MAX_WINDOW_DAYS;

	private final Map<Long, Window> byEmployee = new HashMap<>();

	/** The answer to return from {@link #get} for a slot that was never warmed. */
	private final T notWarmed;

	LegacyWarmedDays(T notWarmed) {
		this.notWarmed = notWarmed;
	}

	/**
	 * The warmed answer, which may be null, or {@code notWarmed} when this
	 * employee and date were never warmed -- including any date string that is
	 * not the canonical ISO form a warm writes, which the per-date statement
	 * then answers exactly as it always did.
	 */
	@SuppressWarnings("unchecked")
	T get(long employeeId, String date) {
		long day = epochDay(date);
		Window window = byEmployee.get(employeeId);
		if (day == Long.MIN_VALUE || window == null || day < window.firstDay || day > window.lastDay()) {
			return notWarmed;
		}
		Object slot = window.slots[(int) (day - window.firstDay)];
		if (slot == null) {
			return notWarmed;
		}
		return slot == NONE ? null : (T) slot;
	}

	/** Whether every date in {@code [first, last]} is warmed for this employee. */
	boolean covers(long employeeId, LocalDate first, LocalDate last) {
		Window window = byEmployee.get(employeeId);
		if (window == null || first.toEpochDay() < window.firstDay || last.toEpochDay() > window.lastDay()) {
			return false;
		}
		for (long day = first.toEpochDay(); day <= last.toEpochDay(); day++) {
			if (window.slots[(int) (day - window.firstDay)] == null) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Records one employee's answers for the consecutive days starting at
	 * {@code first}; a null answer is recorded as warmed-and-none. A second warm
	 * for the same employee is merged into the first rather than replacing it.
	 */
	void put(long employeeId, LocalDate first, Object[] answers) {
		Object[] encoded = new Object[answers.length];
		for (int i = 0; i < answers.length; i++) {
			encoded[i] = answers[i] == null ? NONE : answers[i];
		}
		long firstDay = first.toEpochDay();
		Window existing = byEmployee.get(employeeId);
		if (existing == null) {
			byEmployee.put(employeeId, new Window(firstDay, encoded));
			return;
		}
		long mergedFirst = Math.min(existing.firstDay, firstDay);
		long mergedLast = Math.max(existing.lastDay(), firstDay + encoded.length - 1);
		if (mergedLast - mergedFirst + 1 > MAX_MERGED_DAYS) {
			// Two warms far apart: keep the newer rather than allocate the gap.
			byEmployee.put(employeeId, new Window(firstDay, encoded));
			return;
		}
		Object[] merged = new Object[(int) (mergedLast - mergedFirst + 1)];
		System.arraycopy(existing.slots, 0, merged, (int) (existing.firstDay - mergedFirst), existing.slots.length);
		System.arraycopy(encoded, 0, merged, (int) (firstDay - mergedFirst), encoded.length);
		byEmployee.put(employeeId, new Window(mergedFirst, merged));
	}

	/** How many employee-days are held, warmed or not, for a test to bound. */
	long slotCount() {
		long count = 0;
		for (Window window : byEmployee.values()) {
			count += window.slots.length;
		}
		return count;
	}

	/** The epoch day of a canonical {@code yyyy-MM-dd} string, or {@code Long.MIN_VALUE}. */
	static long epochDay(String date) {
		if (date == null || date.length() != 10) {
			return Long.MIN_VALUE;
		}
		try {
			LocalDate parsed = LocalDate.parse(date);
			return parsed.toString().equals(date) ? parsed.toEpochDay() : Long.MIN_VALUE;
		} catch (java.time.format.DateTimeParseException unparseable) {
			return Long.MIN_VALUE;
		}
	}
}

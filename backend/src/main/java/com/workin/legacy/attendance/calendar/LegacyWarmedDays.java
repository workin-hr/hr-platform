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
	 * The most employee-days one kind of warm may hold over a request (D-292).
	 * Each kind -- the calendar holds one {@code LegacyWarmedDays} per kind --
	 * has its own budget of this size.
	 *
	 * <p>Measured, not guessed: a slot is one compressed reference, and a warm
	 * at this budget retained 11.8-12.4 MB per kind under {@code -Xmx768m}
	 * (D-292 records the run), so the three kinds a request warms --
	 * shifts, leave, timed requests -- stay under 40 MB whatever it asks for.
	 * The same request at {@code 2a5baf0d}'s string-keyed maps was ~100 bytes a
	 * slot, so past this budget that code held over 300 MB for one kind: the
	 * budget admits every warm it could hold without exhausting the heap, not
	 * every range it tried. The dashboard aggregate's 200-row page is warmed up
	 * to 15,000 days (about 41 years), a year's report up to about 7,700
	 * employees, and a single employee's stats back to the year 1 when they stop
	 * at today. {@code 0001-01-01..9999-12-31} (3.65 million days) is refused for
	 * any roster. A warm that does not fit goes to the per-date statements.
	 */
	static final long MAX_SLOTS = 3_000_000L;

	/** Slots currently held across every employee. */
	private long held;

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
			held += encoded.length;
			byEmployee.put(employeeId, new Window(firstDay, encoded));
			return;
		}
		long mergedFirst = Math.min(existing.firstDay, firstDay);
		long mergedLast = Math.max(existing.lastDay(), firstDay + encoded.length - 1);
		if (mergedLast - mergedFirst + 1 > existing.slots.length + encoded.length) {
			// Two warms that neither overlap nor touch: keep the newer rather
			// than allocate the gap between them.
			held += encoded.length - existing.slots.length;
			byEmployee.put(employeeId, new Window(firstDay, encoded));
			return;
		}
		Object[] merged = new Object[(int) (mergedLast - mergedFirst + 1)];
		System.arraycopy(existing.slots, 0, merged, (int) (existing.firstDay - mergedFirst), existing.slots.length);
		System.arraycopy(encoded, 0, merged, (int) (firstDay - mergedFirst), encoded.length);
		held += merged.length - existing.slots.length;
		byEmployee.put(employeeId, new Window(mergedFirst, merged));
	}

	/**
	 * Whether warming {@code employees} more employees over {@code days} days
	 * stays within {@link #MAX_SLOTS}. A warm that does not fit is not made at
	 * all, and its dates go to the per-date statements.
	 */
	boolean admits(long employees, long days) {
		return employees > 0 && days > 0 && days <= MAX_SLOTS && held + employees * days <= MAX_SLOTS;
	}

	/** How many employee-days are held, warmed or not, for a test to bound. */
	long slotCount() {
		return held;
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

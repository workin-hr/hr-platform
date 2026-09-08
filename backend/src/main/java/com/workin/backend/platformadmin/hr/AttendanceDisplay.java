package com.workin.backend.platformadmin.hr;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/**
 * {@code hr_format_attendance_day_cell()}: the weekday beside the date.
 *
 * <p>A column legacy renders and this port did not. It is not decoration on an
 * attendance sheet -- whether a punch fell on a Friday is the difference
 * between an absence and a weekend, and reading that off a date is work the
 * page should have done.
 *
 * <p>The names are written out rather than taken from {@code DayOfWeek} and a
 * locale: legacy indexes a literal array by PHP's {@code w}, Sunday first, and
 * a JVM locale would produce whatever its CLDR data says -- which is not
 * necessarily the same words, and would change under a JDK upgrade.
 */
public final class AttendanceDisplay {

	private static final String[] ARABIC = {
		"الأحد", "الاثنين", "الثلاثاء", "الأربعاء", "الخميس", "الجمعة", "السبت",
	};

	private static final String[] ENGLISH = {
		"Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday",
	};

	private AttendanceDisplay() {
	}

	/** @param stored a {@code DATETIME} as the driver hands it back, or null */
	public static String dayName(String stored, boolean arabic) {
		if (stored == null || stored.length() < 10) {
			return "—";
		}
		try {
			// PHP's `w` is 0 for Sunday; DayOfWeek is 1 for Monday and 7 for
			// Sunday, so 7 folds to 0 and the rest shift by one.
			int index = LocalDate.parse(stored.substring(0, 10)).getDayOfWeek().getValue() % 7;
			return (arabic ? ARABIC : ENGLISH)[index];
		}
		catch (DateTimeParseException ex) {
			// A zero date parses to nothing in PHP too, and the cell is a dash.
			return "—";
		}
	}

}

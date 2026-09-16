package com.workin.backend.platformadmin.hr;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.function.Function;

/**
 * The attendance page's cells, as legacy formats them: {@code includes/table_ui.php}'s
 * {@code hr_format_attendance_date_cell()}, {@code _day_cell()} and {@code _time_cell()}, and
 * {@code includes/payroll_list_helper.php}'s {@code payroll_format_attendance_hours()},
 * {@code payroll_format_duration_ar()} and {@code payroll_render_overtime_delta_badge()}.
 *
 * <p>Month and day names are written out rather than taken from a locale: legacy indexes literal
 * arrays for Arabic and uses PHP's {@code F}, which is always English, for everything else. A JVM
 * locale would produce whatever its CLDR data says -- not necessarily the same words, and liable
 * to change under a JDK upgrade.
 */
public final class AttendanceDisplay {

	private static final String[] ARABIC_DAYS = {
		"الأحد", "الاثنين", "الثلاثاء", "الأربعاء", "الخميس", "الجمعة", "السبت",
	};

	private static final String[] ENGLISH_DAYS = {
		"Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday",
	};

	private static final String[] ARABIC_MONTHS = {
		"يناير", "فبراير", "مارس", "أبريل", "مايو", "يونيو",
		"يوليو", "أغسطس", "سبتمبر", "أكتوبر", "نوفمبر", "ديسمبر",
	};

	private static final String[] ENGLISH_MONTHS = {
		"January", "February", "March", "April", "May", "June",
		"July", "August", "September", "October", "November", "December",
	};

	private static final DateTimeFormatter WITH_SECONDS =
			DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss").withResolverStyle(ResolverStyle.STRICT);

	private static final DateTimeFormatter WITHOUT_SECONDS =
			DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm").withResolverStyle(ResolverStyle.STRICT);

	private AttendanceDisplay() {
	}

	/** {@code hr_format_attendance_date_cell()}: "5 مارس 2026", or "5 March 2026". */
	public static String date(String stored, boolean arabic) {
		LocalDateTime parsed = parse(stored);
		if (parsed == null) {
			return ListDisplay.EMPTY;
		}
		String month = (arabic ? ARABIC_MONTHS : ENGLISH_MONTHS)[parsed.getMonthValue() - 1];
		return parsed.getDayOfMonth() + " " + month + " " + parsed.getYear();
	}

	/** {@code hr_format_attendance_day_cell()}: the weekday beside the date. */
	public static String dayName(String stored, boolean arabic) {
		LocalDateTime parsed = parse(stored);
		if (parsed == null) {
			return ListDisplay.EMPTY;
		}
		// PHP's `w` is 0 for Sunday; DayOfWeek is 1 for Monday and 7 for
		// Sunday, so 7 folds to 0 and the rest shift by one.
		return (arabic ? ARABIC_DAYS : ENGLISH_DAYS)[parsed.getDayOfWeek().getValue() % 7];
	}

	/** {@code hr_format_attendance_time_cell()}: {@code H:i}. */
	public static String time(String stored) {
		LocalDateTime parsed = parse(stored);
		return parsed == null ? ListDisplay.EMPTY : "%02d:%02d".formatted(parsed.getHour(), parsed.getMinute());
	}

	/** {@code payroll_format_attendance_hours()}: signed, zero-padded {@code HH:MM}. */
	public static String hours(Integer minutes) {
		if (minutes == null) {
			return ListDisplay.EMPTY;
		}
		long absolute = Math.abs((long) minutes);
		return (minutes < 0 ? "-" : "") + "%02d:%02d".formatted(absolute / 60, absolute % 60);
	}

	/**
	 * {@code payroll_format_duration_ar()}: "2 ساعة و 5 دقيقة". Nothing, or less than nothing, is
	 * "0 دقيقة". The connective is legacy's literal {@code و} in both languages.
	 */
	public static String duration(long minutes, Function<String, String> t) {
		if (minutes <= 0) {
			return "0 " + t.apply("minute_unit");
		}
		long h = minutes / 60;
		long m = minutes % 60;
		if (h > 0 && m > 0) {
			return h + " " + t.apply("hour_unit") + " و " + m + " " + t.apply("minute_unit");
		}
		return h > 0 ? h + " " + t.apply("hour_unit") : m + " " + t.apply("minute_unit");
	}

	/**
	 * The text of {@code payroll_render_overtime_delta_badge()}: "+30 دقيقة" over the shift.
	 *
	 * <p>Under it, legacy prints the bare count and leaves the red of {@code --minus} to say it is a
	 * shortfall. This keeps the minus sign, so the column does not rest on colour alone.
	 */
	public static String overtime(long minutes, Function<String, String> t) {
		return (minutes > 0 ? "+" : minutes < 0 ? "-" : "") + Math.abs(minutes) + " " + t.apply("minute_unit");
	}

	/**
	 * {@code hr_parse_datetime()}: trimmed, a {@code T} read as a space, then the first 19
	 * characters as a date and time with seconds, or else the first 16 without.
	 *
	 * <p>PHP rolls an impossible value over where this refuses it. A column read back from MariaDB
	 * holds only real dates or the zero date, which legacy renders as "30 November -0001" and this
	 * renders as a dash.
	 */
	static LocalDateTime parse(String stored) {
		String normalized = ListDisplay.trim(stored).replace('T', ' ');
		if (normalized.isEmpty()) {
			return null;
		}
		LocalDateTime parsed = parse(normalized, 19, WITH_SECONDS);
		return parsed != null ? parsed : parse(normalized, 16, WITHOUT_SECONDS);
	}

	private static LocalDateTime parse(String normalized, int length, DateTimeFormatter format) {
		try {
			return LocalDateTime.parse(normalized.substring(0, Math.min(length, normalized.length())), format);
		}
		catch (DateTimeParseException ex) {
			return null;
		}
	}

}

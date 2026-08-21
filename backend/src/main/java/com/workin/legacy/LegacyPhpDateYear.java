package com.workin.legacy;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code (int) date('Y', strtotime($value))}, bounded to the input grammar
 * Wave 12.4 actually measured.
 *
 * <p>{@code employees/create.php} derives the leave-balance year this way, and
 * nothing else in the wave depends on {@code strtotime()}. This class exists so
 * that dependency is a named, tested compatibility boundary instead of an
 * expression buried in a service -- and so the next module that needs the same
 * thing reuses it rather than inventing a third reading.
 *
 * <h2>This is not a date parser</h2>
 * <p>It reproduces the forms a PHP 8.3 probe showed {@code strtotime()}
 * accepting, and <b>fails everything else</b>. Failing is the parity-correct
 * outcome: when {@code strtotime()} returns {@code false}, {@code date('Y', false)}
 * raises a {@code TypeError} under {@code strict_types=1}, which inside create's
 * transaction rolls the whole insert back and answers 500. Manufacturing a year
 * for an input PHP rejects would turn a rejected request into a stored employee.
 *
 * <h2>The measured grammar</h2>
 * <p>Accepted, with the year PHP produces:
 *
 * <pre>
 * 2026-08-21 / 2026/08/21 / 2026-8-1        the date's year
 * 2026-08-21 12:30 / 12:30:00 / T12:30:00   the date's year (time ignored)
 * 2026-08                                   the year (day defaults to the 1st)
 * 2026-02-30                                2026 -- day overflow rolls forward
 * 21-08-2026 / 21-8-2026                    d-m-Y with dashes
 * 08/21/2026 / 8/21/2026                    m/d/Y with slashes
 * 21 Aug 2026 / 21 August 2026              day first, month name
 * Aug 21 2026 / August 21, 2026             month name first
 * 0000-00-00                                -1  (PHP renders year -0001)
 * now / today                               today's year
 * tomorrow / yesterday                      that day's year
 * 2026                                      today's year -- a bare four digits
 *                                           is a HHMM time in PHP, not a year
 * </pre>
 *
 * <p>Rejected, and therefore a 500 with a rolled-back transaction:
 * {@code 2026-13-01}, {@code 2026-12-32}, {@code 2026-08-21garbage},
 * {@code 2026-08-21 garbage}, {@code 2026-08/21}, {@code 2026/08-21},
 * {@code invalid text}, the empty string, {@code Array}, {@code 1}.
 *
 * <p>Deliberately outside the grammar: relative expressions beyond the four
 * keywords ({@code +1 week}, {@code next monday}, {@code last day of}),
 * timezone suffixes, and epoch {@code @} syntax. None appears in this wave's
 * surface, and each would need real {@code strtotime()} machinery.
 */
public final class LegacyPhpDateYear {

	/** PHP's message when {@code date()} is handed {@code strtotime()}'s {@code false}. */
	public static final String TYPE_ERROR_MESSAGE =
			"date(): Argument #2 ($timestamp) must be of type ?int, false given";

	/** {@code 0000-00-00} parses to a real timestamp whose year renders as {@code -0001}. */
	private static final int ZERO_DATE_YEAR = -1;

	private static final Pattern ISO =
			Pattern.compile("^(\\d{4})-(\\d{1,2})-(\\d{1,2})(?:[ T](\\d{1,2}):(\\d{2})(?::(\\d{2}))?)?$");
	private static final Pattern ISO_SLASHES =
			Pattern.compile("^(\\d{4})/(\\d{1,2})/(\\d{1,2})(?:[ T](\\d{1,2}):(\\d{2})(?::(\\d{2}))?)?$");
	private static final Pattern YEAR_MONTH = Pattern.compile("^(\\d{4})-(\\d{1,2})$");
	/** Dashes put the day first: {@code 21-08-2026}. */
	private static final Pattern DAY_MONTH_YEAR = Pattern.compile("^(\\d{1,2})-(\\d{1,2})-(\\d{4})$");
	/** Slashes put the month first: {@code 08/21/2026}. */
	private static final Pattern MONTH_DAY_YEAR = Pattern.compile("^(\\d{1,2})/(\\d{1,2})/(\\d{4})$");
	private static final Pattern DAY_MONTHNAME_YEAR = Pattern.compile("^(\\d{1,2})\\s+([A-Za-z]+),?\\s+(\\d{4})$");
	private static final Pattern MONTHNAME_DAY_YEAR = Pattern.compile("^([A-Za-z]+)\\s+(\\d{1,2}),?\\s+(\\d{4})$");
	/** A bare four digits is a {@code HHMM} time of day, so the year is today's. */
	private static final Pattern BARE_TIME = Pattern.compile("^(\\d{2})(\\d{2})$");

	private static final List<String> MONTH_PREFIXES = List.of(
			"jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec");

	private LegacyPhpDateYear() {
	}

	/**
	 * @param raw the value {@code $hire_date} holds
	 * @param today the current date under legacy's configured offset -- see
	 *        {@link LegacyClock}, which is what makes the relative keywords match
	 * @return the year {@code (int) date('Y', strtotime($raw))} produces
	 * @throws LegacyPhpDateException when {@code strtotime()} would return
	 *         {@code false}, carrying the message PHP's {@code TypeError} does
	 */
	public static int of(String raw, LocalDate today) {
		String value = raw == null ? "" : raw.trim();
		if (value.isEmpty()) {
			throw new LegacyPhpDateException();
		}
		if ("0000-00-00".equals(value)) {
			return ZERO_DATE_YEAR;
		}

		Integer relative = relativeYear(value, today);
		if (relative != null) {
			return relative;
		}

		Matcher iso = ISO.matcher(value);
		if (iso.matches()) {
			return yearOf(digits(iso, 1), digits(iso, 2), digits(iso, 3), timeIsValid(iso));
		}
		Matcher isoSlashes = ISO_SLASHES.matcher(value);
		if (isoSlashes.matches()) {
			return yearOf(digits(isoSlashes, 1), digits(isoSlashes, 2), digits(isoSlashes, 3), timeIsValid(isoSlashes));
		}
		Matcher yearMonth = YEAR_MONTH.matcher(value);
		if (yearMonth.matches()) {
			return yearOf(digits(yearMonth, 1), digits(yearMonth, 2), 1, true);
		}
		Matcher dayMonthYear = DAY_MONTH_YEAR.matcher(value);
		if (dayMonthYear.matches()) {
			return yearOf(digits(dayMonthYear, 3), digits(dayMonthYear, 2), digits(dayMonthYear, 1), true);
		}
		Matcher monthDayYear = MONTH_DAY_YEAR.matcher(value);
		if (monthDayYear.matches()) {
			return yearOf(digits(monthDayYear, 3), digits(monthDayYear, 1), digits(monthDayYear, 2), true);
		}
		Matcher dayNameYear = DAY_MONTHNAME_YEAR.matcher(value);
		if (dayNameYear.matches()) {
			return yearOf(digits(dayNameYear, 3), monthFromName(dayNameYear.group(2)), digits(dayNameYear, 1), true);
		}
		Matcher nameDayYear = MONTHNAME_DAY_YEAR.matcher(value);
		if (nameDayYear.matches()) {
			return yearOf(digits(nameDayYear, 3), monthFromName(nameDayYear.group(1)), digits(nameDayYear, 2), true);
		}
		Matcher bareTime = BARE_TIME.matcher(value);
		if (bareTime.matches() && digits(bareTime, 1) < 24 && digits(bareTime, 2) < 60) {
			return today.getYear();
		}
		throw new LegacyPhpDateException();
	}

	/** {@code now}, {@code today}, {@code tomorrow}, {@code yesterday} -- case-insensitive, as PHP is. */
	private static Integer relativeYear(String value, LocalDate today) {
		return switch (value.toLowerCase(Locale.ROOT)) {
			case "now", "today" -> today.getYear();
			case "tomorrow" -> today.plusDays(1).getYear();
			case "yesterday" -> today.minusDays(1).getYear();
			default -> null;
		};
	}

	/**
	 * A month of 1-12 and a day of 1-31 are accepted and then <em>rolled</em>:
	 * {@code 2026-02-30} becomes 2 March 2026, which is why the year has to come
	 * from the rolled date rather than from the literal digits. A month or day
	 * outside those bounds is what {@code strtotime()} rejects outright --
	 * {@code 2026-13-01} and {@code 2026-12-32} both return {@code false}.
	 */
	private static int yearOf(int year, int month, int day, boolean timeIsValid) {
		if (!timeIsValid || month < 1 || month > 12 || day < 1 || day > 31) {
			throw new LegacyPhpDateException();
		}
		return LocalDate.of(year, month, 1).plusDays(day - 1L).getYear();
	}

	/** An out-of-range clock time makes the whole value unparseable in PHP. */
	private static boolean timeIsValid(Matcher matcher) {
		if (matcher.groupCount() < 4 || matcher.group(4) == null) {
			return true;
		}
		int hour = Integer.parseInt(matcher.group(4));
		int minute = Integer.parseInt(matcher.group(5));
		int second = matcher.group(6) == null ? 0 : Integer.parseInt(matcher.group(6));
		return hour < 24 && minute < 60 && second < 60;
	}

	/** PHP matches month names by their first three letters, case-insensitively. */
	private static int monthFromName(String name) {
		String lower = name.toLowerCase(Locale.ROOT);
		if (lower.length() < 3) {
			throw new LegacyPhpDateException();
		}
		int index = MONTH_PREFIXES.indexOf(lower.substring(0, 3));
		if (index < 0) {
			throw new LegacyPhpDateException();
		}
		return index + 1;
	}

	private static int digits(Matcher matcher, int group) {
		return Integer.parseInt(matcher.group(group));
	}

	/**
	 * What {@code strtotime()} returning {@code false} costs: PHP raises a
	 * {@code TypeError} from {@code date()}, and the caller's transaction rolls
	 * back around it.
	 */
	public static class LegacyPhpDateException extends RuntimeException {

		public LegacyPhpDateException() {
			super(TYPE_ERROR_MESSAGE);
		}

	}

}

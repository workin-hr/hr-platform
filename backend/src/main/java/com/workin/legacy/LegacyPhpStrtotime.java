package com.workin.legacy;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A bounded {@code strtotime()} for the date shapes legacy actually feeds it.
 *
 * <h2>Why bounded, and where the boundary is</h2>
 * <p>PHP's {@code strtotime()} is a large natural-language date parser, and
 * reproducing it in full is neither possible nor useful here. Two callers need
 * it: {@code employees/create.php}, through {@code date('Y', strtotime($hire_date))}
 * (see {@link LegacyPhpDateYear}), and {@code employee_excel_normalize_date_value()},
 * which normalizes a spreadsheet date cell. Both receive dates, not prose.
 *
 * <p>So this grammar covers the date-shaped inputs and nothing else. <b>Every
 * branch below was measured against a real PHP 8.3 CLI</b> rather than inferred
 * from the documentation -- several of them do not behave the way the
 * documentation suggests:
 *
 * <ul>
 * <li>A dashed three-part value is read <em>right to left</em>: with a
 *     four-digit last part it is {@code d-m-Y} ({@code 15-01-1990} is 15
 *     January), but with every part short it is {@code y-m-d}
 *     ({@code 1-1-1} is 1 January 2001, and {@code 1-1-69} is rejected because
 *     69 is read as a day).</li>
 * <li>A slashed three-part value is American: {@code 01/15/1990} is 15
 *     January.</li>
 * <li>Month and day <em>roll</em> rather than validate. {@code 31-4-2024} is 1
 *     May, and zero is a legal value that rolls backwards: {@code 0-0-2024} is
 *     30 November 2023. Only a month above 12 or a day above 31 is rejected.</li>
 * <li>Month names are matched by exact membership: the three-letter
 *     abbreviations, the full names, and {@code sept}. {@code septem} is
 *     rejected by PHP too. <b>{@code janu} is not</b> -- PHP reads it as
 *     {@code jan} followed by the military timezone {@code u} (UTC-8), so
 *     {@code 15 janu 1990} is {@code 1990-01-15 10:00:00} under UTC+2. This
 *     class rejects it, deliberately: implementing timezone-suffix tokens
 *     would drag in a branch of PHP's grammar Phase 1 does not reproduce
 *     (D-094).</li>
 * <li>A bare four-digit value is a time when it reads as a valid {@code HHMM}
 *     and a year otherwise -- {@code 1990} is the year 1990 because 19:90 is
 *     not a time, while {@code 1200} is midday today. Six digits are
 *     {@code HHMMSS} on the same rule. Hour 24 is legal and rolls into the
 *     next day; hour 25 is not.</li>
 * </ul>
 *
 * <p>Anything outside that grammar returns {@code null}, which is
 * {@code strtotime()} returning {@code false}. The known divergence is
 * therefore one-sided and narrow: a value PHP parses through some branch not
 * listed above would be reported here as unparseable. Callers must handle
 * {@code null} the way PHP handles {@code false}, and the two do differ in what
 * that costs -- {@code normalize_date_value()} returns the raw cell, while
 * {@code date()} raises a {@code TypeError}.
 */
public final class LegacyPhpStrtotime {

	private static final Pattern ISO = Pattern.compile(
			"^(\\d{4})-(\\d{1,2})-(\\d{1,2})(?:[ T](\\d{1,2}):(\\d{2})(?::(\\d{2}))?)?$");

	private static final Pattern ISO_SLASHES = Pattern.compile(
			"^(\\d{4})/(\\d{1,2})/(\\d{1,2})(?:[ T](\\d{1,2}):(\\d{2})(?::(\\d{2}))?)?$");

	private static final Pattern YEAR_MONTH = Pattern.compile("^(\\d{4})-(\\d{1,2})$");

	/** {@code 15-01-1990} -- day first when the year is last. */
	private static final Pattern DAY_MONTH_YEAR = Pattern.compile("^(\\d{1,2})-(\\d{1,2})-(\\d{4})$");

	/** {@code 01/15/1990} -- month first, because slashes are read the American way. */
	private static final Pattern MONTH_DAY_YEAR = Pattern.compile("^(\\d{1,2})/(\\d{1,2})/(\\d{4})$");

	/** {@code 1-1-1} -- with no four-digit part anywhere, the <em>year</em> comes first. */
	private static final Pattern SHORT_YEAR_MONTH_DAY =
			Pattern.compile("^(\\d{1,2})-(\\d{1,2})-(\\d{1,2})$");

	private static final Pattern DAY_MONTHNAME_YEAR =
			Pattern.compile("^(\\d{1,2})[\\s-]+([A-Za-z]+),?[\\s-]+(\\d{4})$");

	private static final Pattern MONTHNAME_DAY_YEAR =
			Pattern.compile("^([A-Za-z]+)[\\s-]+(\\d{1,2}),?[\\s-]+(\\d{4})$");

	private static final Pattern FOUR_DIGITS = Pattern.compile("^(\\d{2})(\\d{2})$");

	private static final Pattern SIX_DIGITS = Pattern.compile("^(\\d{2})(\\d{2})(\\d{2})$");

	/**
	 * The names PHP accepts, measured one by one. {@code sept} is the only
	 * four-letter form it knows. {@code septem} is not a name to PHP either;
	 * {@code janu} is not a name but PHP still parses the string, as
	 * {@code jan} plus a timezone token (D-094).
	 */
	private static final Map<String, Integer> MONTH_NAMES = Map.ofEntries(
			Map.entry("jan", 1), Map.entry("january", 1),
			Map.entry("feb", 2), Map.entry("february", 2),
			Map.entry("mar", 3), Map.entry("march", 3),
			Map.entry("apr", 4), Map.entry("april", 4),
			Map.entry("may", 5),
			Map.entry("jun", 6), Map.entry("june", 6),
			Map.entry("jul", 7), Map.entry("july", 7),
			Map.entry("aug", 8), Map.entry("august", 8),
			Map.entry("sep", 9), Map.entry("sept", 9), Map.entry("september", 9),
			Map.entry("oct", 10), Map.entry("october", 10),
			Map.entry("nov", 11), Map.entry("november", 11),
			Map.entry("dec", 12), Map.entry("december", 12));

	private LegacyPhpStrtotime() {
	}

	/**
	 * The date {@code strtotime()} would resolve to, or {@code null} where it
	 * would return {@code false}.
	 *
	 * @param today the current date under legacy's configured offset
	 *        ({@link LegacyClock}) -- what the relative keywords and the
	 *        time-only forms resolve against
	 */
	public static LocalDate dateOf(String raw, LocalDate today) {
		LocalDateTime parsed = dateTimeOf(raw, today.atStartOfDay());
		return parsed == null ? null : parsed.toLocalDate();
	}

	/**
	 * The same grammar, keeping the time PHP resolved.
	 *
	 * <p>{@code attendance/create.php} and {@code update.php} branch on
	 * {@code date('H:i:s', strtotime($v)) === '00:00:00'} to tell an
	 * exception-only day from a real punch, so the time is contract, not
	 * decoration. {@link #dateOf} is this method with
	 * {@link LocalDateTime#toLocalDate()} applied, so there is one grammar and
	 * the older callers keep their exact results.
	 *
	 * <p>Which branches carry a non-midnight time was measured, not assumed:
	 * {@code now} keeps the reference time, {@code today} does not, and the
	 * <b>year</b> branch does -- {@code 1990} against a reference of
	 * {@code 2026-03-11 14:37:19} is {@code 1990-03-11 14:37:19}, not midnight.
	 * That last one is invisible through {@link #dateOf} and is exactly why
	 * this method exists.
	 *
	 * @param reference what {@code now}, the relative keywords, the time-only
	 *        forms and the year branch resolve against
	 */
	public static LocalDateTime dateTimeOf(String raw, LocalDateTime reference) {
		if (raw == null || raw.isEmpty()) {
			return null;
		}

		// Whitespace-only input is decided before trimming, because
		// strtotime() does not accept the set PHP's trim() strips. Measured
		// under PHP 8.3: " ", "\t", "\n" and NUL are "now"; "\r", "\v" and
		// "\f" are false. See #acceptsAsNow.
		if (isWhitespaceOnly(raw)) {
			return acceptsAsNow(raw) ? reference : null;
		}

		String value = raw.trim();
		if (value.isEmpty()) {
			return null;
		}

		LocalDate today = reference.toLocalDate();

		LocalDateTime relative = relative(value, reference);
		if (relative != null) {
			return relative;
		}

		Matcher iso = ISO.matcher(value);
		if (iso.matches()) {
			LocalDate date = roll(digits(iso, 1), digits(iso, 2), digits(iso, 3), timeIsValid(iso));
			return date == null ? null : date.atTime(timeOf(iso));
		}
		Matcher isoSlashes = ISO_SLASHES.matcher(value);
		if (isoSlashes.matches()) {
			LocalDate date = roll(digits(isoSlashes, 1), digits(isoSlashes, 2), digits(isoSlashes, 3),
					timeIsValid(isoSlashes));
			return date == null ? null : date.atTime(timeOf(isoSlashes));
		}
		Matcher yearMonth = YEAR_MONTH.matcher(value);
		if (yearMonth.matches()) {
			return at(roll(digits(yearMonth, 1), digits(yearMonth, 2), 1, true), LocalTime.MIDNIGHT);
		}
		Matcher dayMonthYear = DAY_MONTH_YEAR.matcher(value);
		if (dayMonthYear.matches()) {
			return at(roll(digits(dayMonthYear, 3), digits(dayMonthYear, 2), digits(dayMonthYear, 1), true),
					LocalTime.MIDNIGHT);
		}
		Matcher monthDayYear = MONTH_DAY_YEAR.matcher(value);
		if (monthDayYear.matches()) {
			return at(roll(digits(monthDayYear, 3), digits(monthDayYear, 1), digits(monthDayYear, 2), true),
					LocalTime.MIDNIGHT);
		}
		Matcher shortDate = SHORT_YEAR_MONTH_DAY.matcher(value);
		if (shortDate.matches()) {
			return at(roll(twoDigitYear(digits(shortDate, 1)), digits(shortDate, 2), digits(shortDate, 3),
					true), LocalTime.MIDNIGHT);
		}
		Matcher dayNameYear = DAY_MONTHNAME_YEAR.matcher(value);
		if (dayNameYear.matches()) {
			Integer month = monthFromName(dayNameYear.group(2));
			return month == null ? null
					: at(roll(digits(dayNameYear, 3), month, digits(dayNameYear, 1), true),
							LocalTime.MIDNIGHT);
		}
		Matcher nameDayYear = MONTHNAME_DAY_YEAR.matcher(value);
		if (nameDayYear.matches()) {
			Integer month = monthFromName(nameDayYear.group(1));
			return month == null ? null
					: at(roll(digits(nameDayYear, 3), month, digits(nameDayYear, 2), true),
							LocalTime.MIDNIGHT);
		}
		return timeOnly(value, today, reference);
	}

	/** {@code null} date propagates; otherwise the date at the given time. */
	private static LocalDateTime at(LocalDate date, LocalTime time) {
		return date == null ? null : date.atTime(time);
	}

	/**
	 * The {@code H:i[:s]} tail of an ISO match, or midnight when it is absent.
	 *
	 * <p>Only safe to call once {@link #roll} has returned non-null: it is
	 * {@code roll}'s {@code timeIsValid} argument that rejects an out-of-range
	 * clock such as {@code 25:00:00}, and building a {@link LocalTime} from one
	 * throws before that rejection can be observed.
	 */
	private static LocalTime timeOf(Matcher matcher) {
		if (matcher.group(4) == null) {
			return LocalTime.MIDNIGHT;
		}
		int hour = Integer.parseInt(matcher.group(4));
		int minute = Integer.parseInt(matcher.group(5));
		int second = matcher.group(6) == null ? 0 : Integer.parseInt(matcher.group(6));
		return LocalTime.of(hour, minute, second);
	}

	/**
	 * Every character is one PHP's own tokenizer treats as separator-ish. Not
	 * derived from {@link String#trim}, {@link String#isBlank}, a regex class
	 * or PHP's {@code trim()} charlist -- none of those match what
	 * {@code strtotime()} accepts.
	 */
	private static boolean isWhitespaceOnly(String raw) {
		for (int index = 0; index < raw.length(); index++) {
			char character = raw.charAt(index);
			if (character != ' ' && character != '\t' && character != '\n'
					&& character != '\r' && character != 0x0B && character != '\f'
					&& character != '\0') {
				return false;
			}
		}
		return true;
	}

	/**
	 * Which whitespace-only strings {@code strtotime()} reads as "now",
	 * measured under PHP 8.3 at a frozen reference:
	 *
	 * <pre>
	 * ""      false        "\r"    false
	 * " "     now          "\v"    false
	 * "   "   now          "\f"    false
	 * "\t"    now          "\0"    now
	 * "\n"    now          " \t\n " now
	 * </pre>
	 *
	 * <p>So the accepted set is <b>space, tab, newline and NUL</b> -- and
	 * notably not carriage return, which PHP's {@code trim()} does strip, nor
	 * form feed, which Java's {@link String#trim} does. Composition follows the
	 * last character: {@code "\r "} is now while {@code " \r"} is false, and
	 * {@code "\r\n"} is now while {@code "\n\r"} is false.
	 *
	 * <p>One measured case is deliberately not reproduced: {@code "\0\r"} is
	 * accepted by PHP although its last character is a carriage return. NUL
	 * appears to interact with the tokenizer in a way the rest of the evidence
	 * does not explain, and modelling it from one data point would be
	 * guesswork. The divergence is one-sided in the same direction as the rest
	 * of this class -- Java rejects what PHP accepts -- and is recorded in
	 * D-094 rather than approximated.
	 */
	private static boolean acceptsAsNow(String raw) {
		char last = raw.charAt(raw.length() - 1);
		return last == ' ' || last == '\t' || last == '\n' || last == '\0';
	}

	/**
	 * {@code now}, {@code today}, {@code tomorrow}, {@code yesterday} --
	 * case-insensitive, as PHP is.
	 *
	 * <p>{@code now} and {@code today} are the <b>same date and different
	 * times</b>: measured under PHP 8.3, {@code now} keeps the reference clock
	 * while {@code today} is that day at midnight. Through {@link #dateOf}
	 * they were indistinguishable, which is why this only became visible with
	 * the timestamp projection.
	 */
	private static LocalDateTime relative(String value, LocalDateTime reference) {
		LocalDate today = reference.toLocalDate();
		return switch (value.toLowerCase(Locale.ROOT)) {
			case "now" -> reference;
			case "today" -> today.atStartOfDay();
			case "tomorrow" -> today.plusDays(1).atStartOfDay();
			case "yesterday" -> today.minusDays(1).atStartOfDay();
			default -> null;
		};
	}

	/**
	 * A bare {@code HHMM} or {@code HHMMSS} is a time today; a four-digit value
	 * that is not a valid time is a year, taking today's month and day. Hour 24
	 * rolls into tomorrow, hour 25 is not a time at all -- and then four digits
	 * fall back to being a year while six digits have nowhere left to go.
	 */
	private static LocalDateTime timeOnly(String value, LocalDate today, LocalDateTime reference) {
		Matcher four = FOUR_DIGITS.matcher(value);
		if (four.matches()) {
			int hour = digits(four, 1);
			int minute = digits(four, 2);
			if (hour <= 24 && minute <= 59) {
				return hour == 24
						? today.plusDays(1).atStartOfDay()
						: today.atTime(LocalTime.of(hour, minute));
			}
			// Not a time, so it is a year: 1990 is 19:90 to nobody. Measured:
			// the year branch keeps the reference TIME, not midnight, so
			// `1990` against 14:37:19 is 1990-<ref month/day> 14:37:19.
			return at(roll(Integer.parseInt(value), today.getMonthValue(), today.getDayOfMonth(), true),
					reference.toLocalTime());
		}
		Matcher six = SIX_DIGITS.matcher(value);
		if (six.matches()) {
			int hour = digits(six, 1);
			int minute = digits(six, 2);
			int second = digits(six, 3);
			if (hour <= 24 && minute <= 59 && second <= 59) {
				return hour == 24
						? today.plusDays(1).atStartOfDay()
						: today.atTime(LocalTime.of(hour, minute, second));
			}
		}
		return null;
	}

	/** 00-69 are the 2000s, 70-99 the 1900s -- measured at both ends of the window. */
	private static int twoDigitYear(int year) {
		return year <= 69 ? 2000 + year : 1900 + year;
	}

	/**
	 * Month and day roll rather than validate, in both directions -- so month 0
	 * is the previous December and day 0 is the previous month's last day.
	 * Only a month above 12 or a day above 31 makes the whole value
	 * unparseable.
	 */
	private static LocalDate roll(int year, int month, int day, boolean timeIsValid) {
		if (!timeIsValid || month < 0 || month > 12 || day < 0 || day > 31) {
			return null;
		}
		return LocalDate.of(year, 1, 1).plusMonths(month - 1L).plusDays(day - 1L);
	}

	/** An out-of-range clock time makes the whole value unparseable in PHP. */
	private static boolean timeIsValid(Matcher matcher) {
		if (matcher.group(4) == null) {
			return true;
		}
		int hour = Integer.parseInt(matcher.group(4));
		int minute = Integer.parseInt(matcher.group(5));
		int second = matcher.group(6) == null ? 0 : Integer.parseInt(matcher.group(6));
		return hour < 24 && minute < 60 && second < 60;
	}

	/** Case-insensitive, but the whole word has to be a month name PHP knows. */
	private static Integer monthFromName(String name) {
		return MONTH_NAMES.get(name.toLowerCase(Locale.ROOT));
	}

	private static int digits(Matcher matcher, int group) {
		return Integer.parseInt(matcher.group(group));
	}

}

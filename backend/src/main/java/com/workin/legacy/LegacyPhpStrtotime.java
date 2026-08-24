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
 * reproducing it in full is neither possible nor useful here. The evidenced
 * callers are:
 *
 * <ul>
 * <li>{@code employees/create.php}, through
 *     {@code date('Y', strtotime($hire_date))} (see {@link LegacyPhpDateYear});</li>
 * <li>{@code employee_excel_normalize_date_value()}, normalizing a spreadsheet
 *     date cell;</li>
 * <li>{@code attendance/create.php} and {@code attendance/update.php}, which
 *     read {@code date('H:i:s', strtotime($v))} to tell an exception-only day
 *     from a real punch -- so they need the resolved <em>time</em>, which is
 *     what {@link #dateTimeOf} exposes.</li>
 * </ul>
 *
 * <p>The first two receive dates. Attendance receives caller input, so the
 * grammar's relative and time-only families ({@code now}, a bare
 * {@code 0830}, the four-digit year fallback) are reachable from the wire and
 * are not merely theoretical.
 *
 * <p>So this grammar covers those families and nothing else. <b>Every
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
 * listed above would be reported here as unparseable (D-094).
 *
 * <p>That one-sidedness was checked at the boundary rather than assumed. Java
 * reaches this grammar through {@code raw.trim()}, which strips every
 * character at or below {@code U+0020} -- a wider set than PHP's tokenizer
 * ignores. A PHP 8.3 probe over four grammar representatives
 * ({@code 2026-01-15}, {@code 15 Jan 1990}, {@code now}, {@code 0830}) with
 * each of space, tab, LF, CR, VT, FF and NUL, leading and trailing, found
 * <b>56 of 56 accepted with identical results</b>. So trimming cannot make
 * this class accept something PHP rejects, nor give it a different meaning. Callers must handle
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

	/**
	 * A clock time: {@code H:i}, {@code H:i:s}, and either with a meridiem.
	 *
	 * <p>{@code :} and {@code .} are interchangeable separators and may be
	 * mixed ({@code 11:33.20} and {@code 11.33:20} both parse), every field is
	 * one or two digits, and the meridiem needs no space before it. The width
	 * and range rules are enforced in {@link #clockSeconds}, not here, because
	 * they differ depending on whether a meridiem is present.
	 */
	private static final String CLOCK =
			"(\\d{1,2})[:.](\\d{1,2})(?:[:.](\\d{1,2}))?(?:\\s*([AaPp])\\.?[Mm]\\.?)?";

	/** The separator between a date and the time that follows it: space, comma or {@code T}. */
	private static final String DATE_TIME = "[\\s,T]+";

	private static final Pattern CLOCK_TIME = Pattern.compile("^" + CLOCK + "$");

	/**
	 * {@code 26 Apr 2026}, optionally followed by a time.
	 *
	 * <p>The year is <b>required</b> in this form, and that is what makes
	 * {@code 26 Apr 08:03} unparseable: PHP consumes {@code 08} as the year and
	 * is then left with {@code :03}. Measured, and the reason this pattern is
	 * not simply "day, month, optional year, optional time" -- that would have
	 * accepted a value PHP rejects.
	 */
	private static final Pattern DAY_MONTHNAME_YEAR = Pattern.compile(
			"^(\\d{1,2})[\\s-]+([A-Za-z]+),?[\\s-]+(\\d{1,4})(?:" + DATE_TIME + CLOCK + ")?$");

	/**
	 * {@code Apr 26}, {@code Apr 26 2026} and {@code Apr 26, 2026}, optionally
	 * followed by a time.
	 *
	 * <p>Here the year <em>is</em> optional, and {@code Apr 26 08:03} therefore
	 * parses -- {@code 08:03} carries a colon, so it can only be the time. The
	 * asymmetry with the day-first form above is PHP's, measured both ways.
	 */
	private static final Pattern MONTHNAME_DAY_YEAR = Pattern.compile(
			"^([A-Za-z]+)[\\s-]+(\\d{1,2}),?(?:[\\s-]+(\\d{1,4}))?(?:" + DATE_TIME + CLOCK + ")?$");

	/** {@code 26 Apr} -- the reference year, at midnight. */
	private static final Pattern DAY_MONTHNAME =
			Pattern.compile("^(\\d{1,2})[\\s-]+([A-Za-z]+)$");

	/** {@code Apr} and {@code Apr 2026}. */
	private static final Pattern MONTHNAME_ONLY =
			Pattern.compile("^([A-Za-z]+)(?:[\\s-]+(\\d{1,4}))?$");

	private static final Pattern FOUR_DIGITS = Pattern.compile("^(\\d{2})(\\d{2})$");

	private static final Pattern SIX_DIGITS = Pattern.compile("^(\\d{2})(\\d{2})(\\d{2})$");

	/**
	 * {@code 20260426} -- eight digits as {@code YYYYMMDD}.
	 *
	 * <p>Added under D-094 for {@code schedule_generate_for_employee()}, which
	 * builds its range with {@code new DateTimeImmutable($raw)} and therefore
	 * reaches this form. Exactly eight digits: seven parses as something else
	 * entirely ({@code 2026042} is 11 February 2026) and nine is rejected, so
	 * the width is the branch rather than a prefix rule.
	 *
	 * <p>The bounds are the ISO branch's, measured: month 13 is rejected
	 * ({@code 20261301} is false), an over-long day rolls ({@code 20260431} is
	 * 1 May), and a zero month or day rolls backwards ({@code 20260000} is
	 * 30 November 2025). So it shares {@link #roll} rather than validating
	 * separately.
	 */
	private static final Pattern EIGHT_DIGITS =
			Pattern.compile("^(\\d{4})(\\d{2})(\\d{2})$");

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
			LocalDate date = roll(digits(iso, 1), digits(iso, 2), digits(iso, 3), true);
			return atIsoClock(date, iso);
		}
		Matcher isoSlashes = ISO_SLASHES.matcher(value);
		if (isoSlashes.matches()) {
			LocalDate date = roll(digits(isoSlashes, 1), digits(isoSlashes, 2), digits(isoSlashes, 3), true);
			return atIsoClock(date, isoSlashes);
		}
		Matcher eightDigits = EIGHT_DIGITS.matcher(value);
		if (eightDigits.matches()) {
			return at(roll(digits(eightDigits, 1), digits(eightDigits, 2), digits(eightDigits, 3),
					true), LocalTime.MIDNIGHT);
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
					: atClock(roll(yearOf(dayNameYear.group(3)), month, digits(dayNameYear, 1), true),
							dayNameYear, 4);
		}
		Matcher nameDayYear = MONTHNAME_DAY_YEAR.matcher(value);
		if (nameDayYear.matches()) {
			Integer month = monthFromName(nameDayYear.group(1));
			if (month != null) {
				int year = nameDayYear.group(3) == null
						? today.getYear()
						: yearOf(nameDayYear.group(3));
				return atClock(roll(year, month, digits(nameDayYear, 2), true), nameDayYear, 4);
			}
			// Not a month after all: fall through rather than answering null,
			// because `[A-Za-z]+` is broad enough to shadow another branch.
		}
		Matcher dayName = DAY_MONTHNAME.matcher(value);
		if (dayName.matches()) {
			Integer month = monthFromName(dayName.group(2));
			if (month != null) {
				return at(roll(today.getYear(), month, digits(dayName, 1), true), LocalTime.MIDNIGHT);
			}
		}
		Matcher nameOnly = MONTHNAME_ONLY.matcher(value);
		if (nameOnly.matches()) {
			Integer month = monthFromName(nameOnly.group(1));
			if (month != null) {
				// `Apr` keeps the reference DAY -- measured against a 31st, where
				// it rolls into the next month. `Apr 2026` is the first instead.
				int year = nameOnly.group(2) == null ? today.getYear() : yearOf(nameOnly.group(2));
				int day = nameOnly.group(2) == null ? today.getDayOfMonth() : 1;
				return at(roll(year, month, day, true), LocalTime.MIDNIGHT);
			}
		}
		return timeOnly(value, today, reference);
	}

	/** {@code null} date propagates; otherwise the date at the given time. */
	private static LocalDateTime at(LocalDate date, LocalTime time) {
		return date == null ? null : date.atTime(time);
	}

	/**
	 * The {@code H:i[:s]} tail of an ISO match, applied with the same rolling
	 * clock PHP uses: hour 24 rolls into the next day and second 60 rolls into
	 * the next minute, exactly as {@link #clockSeconds}'s no-meridiem branch
	 * does for the bare {@code CLOCK} grammar -- measured against
	 * {@code generate_employee_schedule.php}, which accepts {@code 24:01} and
	 * starts the following day rather than rejecting the value. This pattern
	 * carries no meridiem group, so {@link #clockSeconds} itself cannot be
	 * reused here.
	 */
	private static LocalDateTime atIsoClock(LocalDate date, Matcher matcher) {
		if (date == null) {
			return null;
		}
		if (matcher.group(4) == null) {
			return date.atStartOfDay();
		}
		int hour = Integer.parseInt(matcher.group(4));
		int minute = Integer.parseInt(matcher.group(5));
		String secondText = matcher.group(6);
		int second = secondText == null ? 0 : Integer.parseInt(secondText);
		if (minute > 59 || second > 60 || hour > 24) {
			return null;
		}
		return date.atStartOfDay().plusSeconds((long) hour * 3600L + (long) minute * 60L + second);
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
		Matcher clock = CLOCK_TIME.matcher(value);
		if (clock.matches()) {
			Long seconds = clockSeconds(clock, 1);
			return seconds == null ? null : today.atStartOfDay().plusSeconds(seconds);
		}
		Matcher four = FOUR_DIGITS.matcher(value);
		if (four.matches()) {
			int hour = digits(four, 1);
			int minute = digits(four, 2);
			if (hour <= 24 && minute <= 59) {
				// Hour 24 rolls into tomorrow, but the minute rides along with it:
				// "2401" is tomorrow 00:01, not tomorrow's midnight.
				return today.atStartOfDay().plusMinutes((long) hour * 60L + minute);
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

	/** Case-insensitive, but the whole word has to be a month name PHP knows. */
	private static Integer monthFromName(String name) {
		return MONTH_NAMES.get(name.toLowerCase(Locale.ROOT));
	}

	private static int digits(Matcher matcher, int group) {
		return Integer.parseInt(matcher.group(group));
	}

	/**
	 * A date plus whatever clock the match carries, or midnight when it carries
	 * none. A clock that is present but out of range makes the whole value
	 * unparseable, exactly as PHP's {@code false} does.
	 */
	private static LocalDateTime atClock(LocalDate date, Matcher matcher, int firstGroup) {
		if (date == null) {
			return null;
		}
		if (matcher.group(firstGroup) == null) {
			return date.atStartOfDay();
		}
		Long seconds = clockSeconds(matcher, firstGroup);
		return seconds == null ? null : date.atStartOfDay().plusSeconds(seconds);
	}

	/**
	 * Seconds since midnight for a {@link #CLOCK} match, or {@code null} where
	 * {@code strtotime()} returns {@code false}.
	 *
	 * <p>The result can exceed a day: hour 24 is legal and rolls into the next
	 * one ({@code 24:01} is 00:01 tomorrow), and second 60 is the leap second,
	 * which rolls a minute ({@code 11:33:60} is 11:34:00). Second 61 is not
	 * legal, and minute 60 is not legal either -- the two fields do <em>not</em>
	 * share a rule, and all four of those were measured rather than assumed.
	 *
	 * <p>A meridiem tightens everything: the hour must be 1 to 12 ({@code 00:03 AM}
	 * and {@code 13:03 PM} are both false), and the minute and second must be
	 * written with two digits ({@code 1:5 PM} is false while a bare {@code 11:5}
	 * is 11:05).
	 */
	private static Long clockSeconds(Matcher matcher, int firstGroup) {
		String hourText = matcher.group(firstGroup);
		String minuteText = matcher.group(firstGroup + 1);
		String secondText = matcher.group(firstGroup + 2);
		String meridiem = matcher.group(firstGroup + 3);

		int hour = Integer.parseInt(hourText);
		int minute = Integer.parseInt(minuteText);
		int second = secondText == null ? 0 : Integer.parseInt(secondText);

		if (minute > 59 || second > 60) {
			return null;
		}
		if (meridiem == null) {
			if (hour > 24) {
				return null;
			}
		} else {
			if (hour < 1 || hour > 12 || minuteText.length() != 2
					|| (secondText != null && secondText.length() != 2)) {
				return null;
			}
			hour = (hour % 12) + (Character.toUpperCase(meridiem.charAt(0)) == 'P' ? 12 : 0);
		}
		return (long) hour * 3600L + (long) minute * 60L + second;
	}

	/**
	 * A year token of one to four digits. Four digits are literal; anything
	 * shorter goes through {@link #twoDigitYear}, so {@code 026} and {@code 26}
	 * are both 2026 and {@code 1} is 2001 -- measured.
	 */
	private static int yearOf(String text) {
		int year = Integer.parseInt(text);
		return text.length() == 4 ? year : twoDigitYear(year);
	}

}

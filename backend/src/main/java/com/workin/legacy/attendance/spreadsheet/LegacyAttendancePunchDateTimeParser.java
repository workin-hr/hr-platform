package com.workin.legacy.attendance.spreadsheet;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.regex.Pattern;

import com.workin.legacy.LegacyPhpStrtotime;
import com.workin.legacy.LegacyValues;
import com.workin.legacy.spreadsheet.LegacyXlsxReader;

/**
 * {@code attendance_import_parse_punch_datetime()}
 * ({@code hr-legacy/apis/helpers/attendance_excel_analyzer.php:189-266}).
 *
 * <h2>This is its own grammar, and it is day-first</h2>
 * <p>It is <b>not</b> {@code strtotime()} with some pre-cleaning around it, and
 * must not be ported as one. It tries sixteen explicit formats in source order
 * first, and those start {@code d/m/Y} -- while D-094's bounded
 * {@code strtotime} grammar reads a slashed three-part value the American way
 * ({@code 01/15/1990} is 15 January there, measured). Sending a punch log
 * through the general parser first would transpose day and month on every
 * ambiguous date, so {@link LegacyPhpStrtotime} is used only where PHP uses it:
 * as the final fallback, after all sixteen formats have failed.
 *
 * <h2>The eight steps, in order</h2>
 * <ol>
 * <li>Excel serial, when the value is numeric and strictly {@code > 1000};</li>
 * <li>{@code trim()};</li>
 * <li>strip {@code U+200E}, {@code U+200F} and {@code U+FEFF};</li>
 * <li>strip a trailing device suffix such as {@code A4P4} -- but only when the
 *     value does not end in AM/PM, so a 12-hour export keeps its meridiem;</li>
 * <li>rewrite backslash to forward slash;</li>
 * <li>normalize a dotted <em>date prefix</em> ({@code 18.07.2026}) without
 *     touching a time ({@code 10:20:24});</li>
 * <li>the sixteen formats, first clean match wins;</li>
 * <li>{@code strtotime()}.</li>
 * </ol>
 *
 * <h2>"Clean match", precisely</h2>
 * <p>Each format is tried with {@code createFromFormat} and then
 * {@code getLastErrors()} is checked: a match carrying <em>any</em> warning or
 * error is discarded and the loop continues to the next format. PHP's date
 * parser rolls out-of-range components rather than rejecting them and reports
 * the roll as a warning, so {@code 31/02/2026 10:00} is not read as 3 March --
 * it is skipped, every remaining format fails too, and the value is null.
 * {@code 05/13/2026 10:00} is the same mechanism with a different ending: it
 * rolls under {@code d/m/Y} (month 13), is discarded, and is then matched
 * cleanly by {@code m/d/Y} as <b>13 May 2026</b>. That fall-through is exactly
 * why the American formats sit at the end of the list rather than being absent
 * -- and why an unambiguous {@code 05/06/2026} is 5 June, day-first, while the
 * general {@code strtotime} grammar would call it 6 May.
 *
 * <p>Contrast {@link com.workin.legacy.LegacyPhpConfigDate}, which ports a
 * helper that does <em>not</em> check {@code getLastErrors()} and therefore
 * does roll. Two PHP functions, one API, opposite behaviour -- which is why
 * neither was inferred from the other.
 *
 * <h2>Token widths, measured rather than assumed</h2>
 * <p>PHP 8.3 probes, because the manual documents the <em>output</em> widths
 * and not what the parser accepts:
 * <ul>
 * <li>{@code d}, {@code m}, {@code H} and {@code h} each take <b>one or two</b>
 *     digits ({@code 5/6/2026 10:00} parses);</li>
 * <li>{@code i} and {@code s} take <b>exactly two</b> ({@code 10:00:7} is
 *     rejected outright, not rolled);</li>
 * <li>{@code Y} takes one to four ({@code 05/06/26} is year <b>26</b>);</li>
 * <li>a literal space in the format matches <b>zero or more</b> spaces and
 *     tabs, but not a newline;</li>
 * <li>{@code A} matches {@code AM}/{@code PM} case-insensitively and also
 *     {@code A.M.}/{@code P.M.}; {@code h} accepts {@code 0} through {@code 12}
 *     and anything above 12 is a hard failure rather than a roll;</li>
 * <li>input left over after the format is consumed is "Trailing data", an
 *     <em>error</em> rather than a warning, so the value is rejected;</li>
 * <li>a time component the format omits defaults to <b>zero</b>, not to the
 *     current clock, because some time component was parsed -- so
 *     {@code 26/04/2026 11:33} is 11:33:00 on every run.</li>
 * </ul>
 */
public final class LegacyAttendancePunchDateTimeParser {

	/** {@code preg_match('/\s+(AM|PM)\s*$/i', $raw)} -- the guard that keeps a meridiem. */
	private static final Pattern MERIDIEM_TAIL =
			Pattern.compile("\\s+(AM|PM)\\s*$", Pattern.CASE_INSENSITIVE);

	/** {@code preg_replace('/\s+[A-Za-z0-9]{2,8}$/u', '', ...)} -- the device suffix. */
	private static final Pattern DEVICE_SUFFIX = Pattern.compile("\\s+[A-Za-z0-9]{2,8}$");

	/** {@code preg_match('/^\d{1,2}\.\d{1,2}\.\d{2,4}\b/', $raw)}. */
	private static final Pattern DOTTED_DATE_PREFIX =
			Pattern.compile("^\\d{1,2}\\.\\d{1,2}\\.\\d{2,4}\\b");

	/** {@code preg_replace('/^(\d{1,2})\.(\d{1,2})\.(\d{2,4})/', '$1-$2-$3', ...)}. */
	private static final Pattern DOTTED_DATE_HEAD =
			Pattern.compile("^(\\d{1,2})\\.(\\d{1,2})\\.(\\d{2,4})");

	/** {@code is_numeric()} for the shapes a spreadsheet cell can hold -- hex excluded, as PHP excludes it. */
	private static final Pattern NUMERIC =
			Pattern.compile("^[+-]?(\\d+(\\.\\d*)?|\\.\\d+)([eE][+-]?\\d+)?$");

	/** {@code U+200E} LEFT-TO-RIGHT MARK. */
	private static final String LRM = String.valueOf((char) 0x200E);

	/** {@code U+200F} RIGHT-TO-LEFT MARK. */
	private static final String RLM = String.valueOf((char) 0x200F);

	/** {@code U+FEFF} ZERO WIDTH NO-BREAK SPACE, the UTF-8 BOM as a character. */
	private static final String BOM = String.valueOf((char) 0xFEFF);

	/** The sixteen formats, in {@code $formats}' own order. */
	private static final List<String> FORMATS = List.of(
			"d/m/Y h:i:s A",
			"d/m/Y h:i A",
			"d-m-Y h:i:s A",
			"d-m-Y h:i A",
			"d/m/Y H:i:s",
			"d/m/Y H:i",
			"d-m-Y H:i:s",
			"d-m-Y H:i",
			"Y-m-d H:i:s",
			"Y-m-d H:i",
			"Y/m/d H:i:s",
			"Y/m/d H:i",
			"m/d/Y h:i:s A",
			"m/d/Y h:i A",
			"m/d/Y H:i:s",
			"m/d/Y H:i");

	private LegacyAttendancePunchDateTimeParser() {
	}

	/**
	 * The punch instant, or {@code null} where PHP returns null.
	 *
	 * @param raw the cell as it came out of the reader
	 * @param now legacy's current instant ({@link com.workin.legacy.LegacyClock#now()}),
	 *        which only the {@code strtotime} fallback can reach -- a bare
	 *        {@code 1000} is 10:00 <em>today</em> there rather than an Excel
	 *        serial, because the serial branch is strictly {@code > 1000}
	 */
	public static LocalDateTime parse(Object raw, LocalDateTime now) {
		if (raw == null) {
			return null;
		}

		// (1) The Excel serial branch.
		Double serial = numericValue(raw);
		if (serial != null && serial > 1000d) {
			// `new DateTimeImmutable(excel_serial_to_datetime_string($serial))`.
			// The conversion is shared with Wave 12.4's reader rather than
			// re-implemented: PHP uses the same excel_serial_to_datetime_string()
			// for both, and LegacyExcelSerialDifferentialTest pins the two
			// against measured PHP output so the reuse cannot drift.
			return parseSerialString(LegacyXlsxReader.excelSerialToDateTime(serial));
		}

		// (2) trim
		String value = LegacyValues.phpTrim(LegacyValues.toPhpString(raw));
		if (value.isEmpty()) {
			return null;
		}

		// (3) the two bidi marks and the BOM
		value = value.replace(LRM, "").replace(RLM, "").replace(BOM, "");

		// (4) the device suffix, unless the value ends in AM/PM
		if (!MERIDIEM_TAIL.matcher(value).find()) {
			value = DEVICE_SUFFIX.matcher(LegacyValues.phpTrim(value)).replaceAll("");
		}
		value = LegacyValues.phpTrim(value);

		// (5) backslash to forward slash
		value = value.replace('\\', '/');

		// (6) the dotted date prefix only -- a dotted *time* is left alone
		if (DOTTED_DATE_PREFIX.matcher(value).find()) {
			value = DOTTED_DATE_HEAD.matcher(value).replaceFirst("$1-$2-$3");
		}

		// (7) the sixteen formats, first clean match wins
		for (String format : FORMATS) {
			LocalDateTime parsed = createFromFormat(format, value);
			if (parsed != null) {
				return parsed;
			}
		}

		// (8) strtotime
		return LegacyPhpStrtotime.dateTimeOf(value, now);
	}

	/**
	 * One {@code createFromFormat($format, $value)} followed by the
	 * {@code getLastErrors()} check, collapsed into a single answer: anything
	 * that would have carried a warning or an error is {@code null} here,
	 * because the caller discards it either way.
	 */
	static LocalDateTime createFromFormat(String format, String value) {
		int cursor = 0;
		int year = -1;
		int month = -1;
		int day = -1;
		int hour24 = -1;
		int hour12 = -1;
		int minute = 0;
		int second = 0;
		boolean pm = false;
		boolean meridiemSeen = false;

		for (int index = 0; index < format.length(); index++) {
			char token = format.charAt(index);
			switch (token) {
				case 'Y' -> {
					int[] read = readDigits(value, cursor, 1, 4);
					if (read == null) {
						return null;
					}
					year = read[0];
					cursor = read[1];
				}
				case 'm', 'd', 'H', 'h' -> {
					int[] read = readDigits(value, cursor, 1, 2);
					if (read == null) {
						return null;
					}
					switch (token) {
						case 'm' -> month = read[0];
						case 'd' -> day = read[0];
						case 'H' -> hour24 = read[0];
						default -> {
							hour12 = read[0];
							// `h` is a hard 0..12: 13 is FALSE, not a roll.
							if (hour12 > 12) {
								return null;
							}
						}
					}
					cursor = read[1];
				}
				case 'i', 's' -> {
					int[] read = readDigits(value, cursor, 2, 2);
					if (read == null) {
						return null;
					}
					if (token == 'i') {
						minute = read[0];
					} else {
						second = read[0];
					}
					cursor = read[1];
				}
				case 'A' -> {
					int next = readMeridiem(value, cursor);
					if (next < 0) {
						return null;
					}
					pm = Character.toUpperCase(value.charAt(cursor)) == 'P';
					meridiemSeen = true;
					cursor = next;
				}
				case ' ' -> {
					// Zero or more spaces and tabs -- measured: a value with no
					// space at all and one with two both parse, a newline does not.
					while (cursor < value.length()
							&& (value.charAt(cursor) == ' ' || value.charAt(cursor) == '\t')) {
						cursor++;
					}
				}
				default -> {
					if (cursor >= value.length() || value.charAt(cursor) != token) {
						return null;
					}
					cursor++;
				}
			}
		}

		// Leftover input is "Trailing data", an error rather than a warning.
		if (cursor != value.length()) {
			return null;
		}

		// Everything below is a PHP *warning* -- an out-of-range component that
		// rolls. The caller's getLastErrors() check turns each into a
		// rejection, which is what is reproduced here.
		if (month < 1 || month > 12 || day < 1) {
			return null;
		}
		LocalDate date;
		try {
			date = LocalDate.of(year, month, day);
		} catch (java.time.DateTimeException ex) {
			// 29/02/2026: PHP rolls it to 1 March and warns; rejected here.
			return null;
		}

		int hour = meridiemSeen ? (hour12 % 12) + (pm ? 12 : 0) : hour24;
		if (hour < 0 || hour > 23 || minute > 59 || second > 59) {
			return null;
		}
		return LocalDateTime.of(date, LocalTime.of(hour, minute, second));
	}

	/**
	 * {@code [0-9]} between {@code min} and {@code max} times, never more than
	 * {@code max} -- so {@code 005} against {@code d} reads {@code 00} and
	 * leaves a {@code 5}, which the format's next literal then rejects. That is
	 * how PHP fails it too.
	 */
	private static int[] readDigits(String value, int cursor, int min, int max) {
		int end = cursor;
		while (end < value.length() && end - cursor < max && isDigit(value.charAt(end))) {
			end++;
		}
		if (end - cursor < min) {
			return null;
		}
		return new int[] {Integer.parseInt(value.substring(cursor, end)), end};
	}

	/**
	 * {@code A}: {@code AM}, {@code PM}, {@code A.M.} or {@code P.M.}, any case.
	 *
	 * @return the cursor after the token, or {@code -1} when there is none
	 */
	private static int readMeridiem(String value, int cursor) {
		if (cursor >= value.length()) {
			return -1;
		}
		char first = Character.toUpperCase(value.charAt(cursor));
		if (first != 'A' && first != 'P') {
			return -1;
		}
		if (cursor + 1 < value.length() && Character.toUpperCase(value.charAt(cursor + 1)) == 'M') {
			return cursor + 2;
		}
		if (cursor + 3 < value.length()
				&& value.charAt(cursor + 1) == '.'
				&& Character.toUpperCase(value.charAt(cursor + 2)) == 'M'
				&& value.charAt(cursor + 3) == '.') {
			return cursor + 4;
		}
		return -1;
	}

	private static boolean isDigit(char character) {
		return character >= '0' && character <= '9';
	}

	/**
	 * The value {@code (float) $raw} would produce, or {@code null} when the
	 * numeric branch is not taken at all.
	 *
	 * <p>PHP's guard is
	 * {@code is_int($raw) || is_float($raw) || (is_string($raw) && is_numeric(trim($raw)))},
	 * so a numeric string qualifies on its <em>trimmed</em> form while the cast
	 * runs on the original -- a distinction with no consequence, because the
	 * cast skips leading whitespace and stops at the first non-numeric byte.
	 */
	private static Double numericValue(Object raw) {
		if (raw instanceof Number number) {
			return number.doubleValue();
		}
		if (!(raw instanceof CharSequence)) {
			return null;
		}
		String trimmed = LegacyValues.phpTrim(raw.toString());
		if (!NUMERIC.matcher(trimmed).matches()) {
			return null;
		}
		try {
			return Double.valueOf(trimmed);
		} catch (NumberFormatException ex) {
			return null;
		}
	}

	/**
	 * {@code new DateTimeImmutable(...)} over the two shapes
	 * {@code excel_serial_to_datetime_string()} produces -- {@code Y-m-d} for a
	 * whole-day serial, {@code Y-m-d H:i:s} for a fractional one.
	 */
	private static LocalDateTime parseSerialString(String text) {
		try {
			if (text.length() == 10) {
				return LocalDate.parse(text).atStartOfDay();
			}
			return LocalDateTime.parse(text.replace(' ', 'T'));
		} catch (java.time.format.DateTimeParseException ex) {
			return null;
		}
	}

}

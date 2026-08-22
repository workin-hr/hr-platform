package com.workin.legacy.attendance.spreadsheet;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

import com.workin.legacy.LegacyPhpStrtotime;
import com.workin.legacy.LegacyValues;

/**
 * The two closures inside {@code import_fingerprint_attendance_rows()}
 * ({@code hr-legacy/apis/helpers/xlsx_parser.php:576-604}) -- {@code $normalizeDate}
 * and {@code $normalizeTime} -- and the {@code $composeDateTime} that joins them.
 *
 * <h2>Deliberately not the punch parser, and not compatible with it</h2>
 * <p>These look like smaller versions of
 * {@link LegacyAttendancePunchDateTimeParser} and differ in the one way that
 * matters: they <b>never check {@code getLastErrors()}</b>. An out-of-range
 * component therefore <em>rolls</em> here and is <em>rejected</em> there.
 * {@code 31/02/2026} is 3 March to this class and null to the punch parser, and
 * both are PHP. Sharing an implementation would silently pick one behaviour for
 * both.
 *
 * <p>They also disagree on order: {@code $normalizeDate} tries {@code Y-m-d},
 * {@code Y/m/d}, {@code d/m/Y}, {@code d-m-Y}, {@code m/d/Y}, {@code m-d-Y} --
 * ISO first, then day-first, then American -- while the punch parser starts at
 * {@code d/m/Y h:i:s A}. Neither list is a prefix of the other.
 *
 * <h2>The falsy-timestamp hole</h2>
 * <p>Both closures end {@code return $ts ? date(...) : null}, testing the
 * timestamp for <em>truthiness</em> rather than for {@code false}. Timestamp
 * zero -- the Unix epoch -- is falsy, so a value that {@code strtotime()}
 * resolves to exactly {@code 1970-01-01 00:00:00 UTC} is reported as
 * unparseable. Reproduced, which is why the offset has to be threaded in.
 */
final class LegacyPhpTemplateDateTimes {

	/** {@code $formats} in {@code $normalizeDate}, in order. */
	private static final List<String> DATE_FORMATS =
			List.of("Y-m-d", "Y/m/d", "d/m/Y", "d-m-Y", "m/d/Y", "m-d-Y");

	/** {@code $formats} in {@code $normalizeTime}, in order. */
	private static final List<String> TIME_FORMATS =
			List.of("H:i:s", "H:i", "g:i A", "g:i:s A", "h:i A", "h:i:s A");

	private static final DateTimeFormatter TIME_OUT = DateTimeFormatter.ofPattern("HH:mm:ss");

	private LegacyPhpTemplateDateTimes() {
	}

	/**
	 * {@code $normalizeDate}: {@code Y-m-d}, or null.
	 *
	 * <p>{@code str_replace(['.', '\\'], ['-', '/'], $s)} runs first and is
	 * global -- every dot becomes a dash and every backslash a slash anywhere in
	 * the value, not just in a leading date.
	 */
	static String normalizeDate(Object raw, LocalDateTime now, ZoneOffset offset) {
		String value = LegacyValues.phpTrim(raw == null ? "" : LegacyValues.toPhpString(raw));
		if (value.isEmpty()) {
			return null;
		}
		value = value.replace('.', '-').replace('\\', '/');
		for (String format : DATE_FORMATS) {
			LocalDate parsed = parseDate(format, value);
			if (parsed != null) {
				return parsed.toString();
			}
		}
		LocalDateTime fallback = LegacyPhpStrtotime.dateTimeOf(value, now);
		if (fallback == null || isEpochZero(fallback, offset)) {
			return null;
		}
		return fallback.toLocalDate().toString();
	}

	/**
	 * {@code $normalizeTime}: {@code H:i:s}, or null.
	 *
	 * <p>{@code preg_replace('/\s+/', ' ', $s)} collapses internal whitespace, so
	 * {@code "01:33   PM"} still matches {@code g:i A}.
	 */
	static String normalizeTime(Object raw, LocalDateTime now, ZoneOffset offset) {
		String value = LegacyValues.phpTrim(raw == null ? "" : LegacyValues.toPhpString(raw));
		if (value.isEmpty()) {
			return null;
		}
		value = value.replaceAll("\\s+", " ");
		for (String format : TIME_FORMATS) {
			String parsed = parseTime(format, value);
			if (parsed != null) {
				return parsed;
			}
		}
		LocalDateTime fallback = LegacyPhpStrtotime.dateTimeOf(value, now);
		if (fallback == null || isEpochZero(fallback, offset)) {
			return null;
		}
		return fallback.toLocalTime().format(TIME_OUT);
	}

	/**
	 * {@code $composeDateTime}: the date and the time joined by a space, with
	 * midnight standing in for a missing time. A missing <em>date</em> is null
	 * whatever the time is.
	 */
	static String compose(String date, String time) {
		if (date == null || date.isEmpty()) {
			return null;
		}
		String at = time == null || time.isEmpty() ? "00:00:00" : time;
		return date + " " + at;
	}

	/** One date format, rolling, because nothing inspects {@code getLastErrors()}. */
	private static LocalDate parseDate(String format, String value) {
		int[] fields = readFields(format, value, true);
		if (fields == null) {
			return null;
		}
		// 2026-02-30 is 2 March and month 13 is next January -- the same
		// arithmetic parse_ymd_config_date() needs, for the same reason.
		return LocalDate.of(fields[0], 1, 1).plusMonths(fields[1] - 1L).plusDays(fields[2] - 1L);
	}

	/**
	 * One time format, rolling into the day: {@code 25:00} is {@code 01:00:00}.
	 * {@code g} and {@code h} above 12 are a hard failure rather than a roll,
	 * as measured.
	 */
	private static String parseTime(String format, String value) {
		int[] fields = readFields(format, value, false);
		if (fields == null) {
			return null;
		}
		boolean twelveHour = format.indexOf('A') >= 0;
		int hour = twelveHour ? (fields[0] % 12) + (fields[3] == 1 ? 12 : 0) : fields[0];
		long seconds = Math.floorMod(
				(long) hour * 3600L + (long) fields[1] * 60L + fields[2], 86_400L);
		return String.format("%02d:%02d:%02d", seconds / 3600, (seconds / 60) % 60, seconds % 60);
	}

	/**
	 * Walks {@code format} over {@code value} and returns its numeric fields, or
	 * {@code null} when {@code createFromFormat} would have returned
	 * {@code false}.
	 *
	 * <p>In date mode the slots are year, month, day. In time mode they are
	 * hour, minute, second, meridiem -- with {@code H}, {@code g} and {@code h}
	 * all filling the hour slot, since a single format never carries more than
	 * one of them. A slot the format omits keeps zero, which is what PHP leaves
	 * once any field of the same kind has been parsed.
	 */
	private static int[] readFields(String format, String value, boolean dateMode) {
		int[] fields = new int[dateMode ? 3 : 4];
		int cursor = 0;
		for (int index = 0; index < format.length(); index++) {
			char token = format.charAt(index);
			int slot = slotOf(token, dateMode);
			if (slot < 0) {
				if (token == ' ') {
					while (cursor < value.length()
							&& (value.charAt(cursor) == ' ' || value.charAt(cursor) == '\t')) {
						cursor++;
					}
					continue;
				}
				if (cursor >= value.length() || value.charAt(cursor) != token) {
					return null;
				}
				cursor++;
				continue;
			}
			if (token == 'A') {
				int next = readMeridiem(value, cursor);
				if (next < 0) {
					return null;
				}
				fields[slot] = Character.toUpperCase(value.charAt(cursor)) == 'P' ? 1 : 0;
				cursor = next;
				continue;
			}
			int min = token == 'i' || token == 's' ? 2 : 1;
			int max = token == 'Y' ? 4 : 2;
			int end = cursor;
			while (end < value.length() && end - cursor < max && isDigit(value.charAt(end))) {
				end++;
			}
			if (end - cursor < min) {
				return null;
			}
			int parsed = Integer.parseInt(value.substring(cursor, end));
			if ((token == 'g' || token == 'h') && parsed > 12) {
				// Not a roll: createFromFormat returns false outright.
				return null;
			}
			fields[slot] = parsed;
			cursor = end;
		}
		// Leftover input is "Trailing data", an error rather than a warning.
		return cursor == value.length() ? fields : null;
	}

	private static int slotOf(char token, boolean dateMode) {
		if (dateMode) {
			return switch (token) {
				case 'Y' -> 0;
				case 'm' -> 1;
				case 'd' -> 2;
				default -> -1;
			};
		}
		return switch (token) {
			case 'H', 'g', 'h' -> 0;
			case 'i' -> 1;
			case 's' -> 2;
			case 'A' -> 3;
			default -> -1;
		};
	}

	/** {@code AM}, {@code PM}, {@code A.M.} or {@code P.M.}, any case. */
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
	 * {@code $ts ? ... : null}: a value resolving to exactly the Unix epoch is
	 * reported as unparseable, because zero is falsy. The offset is legacy's
	 * configured one, since the wall-clock value has to be turned back into the
	 * timestamp PHP actually tested.
	 */
	private static boolean isEpochZero(LocalDateTime moment, ZoneOffset offset) {
		return moment.toEpochSecond(offset) == 0L;
	}

}

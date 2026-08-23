package com.workin.legacy.workforce;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.workin.legacy.LegacyValues;
import com.workin.legacy.wire.LegacyApiException;

/**
 * {@code hr-legacy/apis/helpers/shift_times.php}, ported statement for
 * statement: the only validation {@code shifts/create.php} and
 * {@code shifts/update.php} apply to a shift's daily window.
 *
 * <h2>The seconds group is parsed and then ignored</h2>
 * <p>PHP's pattern is {@code /^(\d{1,2}):(\d{2})(?::(\d{2}))?$/} and its body
 * reads {@code $m[1]} and {@code $m[2]} only -- {@code $m[3]} is captured and
 * never used. So {@code 09:00:59} and {@code 09:00} are the same instant to
 * this validator, and a shift ending {@code 17:00:59} is exactly 8 hours, not
 * 8 hours and 59 seconds. That is reproduced deliberately: rounding the
 * seconds in, or rejecting them, would change which windows are accepted.
 * The third group is kept in the pattern because dropping it would reject the
 * {@code H:i:s} strings MariaDB hands back for a {@code TIME} column, which is
 * how {@code update.php} feeds a stored value straight back in.
 *
 * <h2>What the bounds actually are</h2>
 * <p>The hour is bounded {@code 0..23} and the minute {@code 0..59} by explicit
 * comparisons, not by the pattern -- {@code \d{1,2}} matches {@code 99}
 * happily, and the range check is what rejects it. One or two hour digits are
 * both accepted, so {@code 9:00} is valid input even though MariaDB never
 * produces it.
 *
 * <h2>Trim is PHP's trim, not Java's</h2>
 * <p>{@code trim($t)} strips exactly {@code " \t\n\r\0\x0B"}. Java's
 * {@link String#trim()} strips every character at or below {@code U+0020},
 * which is a strict superset -- form feed (0x0C) most visibly. Using it
 * would accept {@code "\f09:00"}, which PHP leaves untrimmed and then
 * fails to match. {@link LegacyValues#phpTrim} is the measured charlist,
 * and is what this helper uses.
 *
 * <p>MariaDB's {@code TIME} domain is far wider than this
 * ({@code -838:59:59 .. 838:59:59}), so an existing row can hold a value these
 * methods reject. {@code list.php} and {@code one.php} render such a row
 * unchanged, because they never call this helper; only {@code create.php} and
 * {@code update.php} do.
 */
public final class LegacyShiftTimes {

	/** {@code /^(\d{1,2}):(\d{2})(?::(\d{2}))?$/} -- group 3 exists and is unused. */
	private static final Pattern TIME = Pattern.compile("^(\\d{1,2}):(\\d{2})(?::(\\d{2}))?$");

	private static final int MINUTES_PER_DAY = 24 * 60;

	/** {@code $duration > 16 * 60} fails, so exactly 16h00m is accepted. */
	private static final int MAX_DURATION_MINUTES = 16 * 60;

	private LegacyShiftTimes() {
	}

	/**
	 * {@code shift_time_string_to_minutes()}: null when the value is blank,
	 * unparseable, or out of range -- never an exception. The caller decides
	 * what a null means.
	 */
	static Integer toMinutes(String raw) {
		if (raw == null) {
			return null;
		}
		String trimmed = LegacyValues.phpTrim(raw);
		if (trimmed.isEmpty()) {
			return null;
		}
		Matcher matcher = TIME.matcher(trimmed);
		if (!matcher.matches()) {
			return null;
		}
		int hours = Integer.parseInt(matcher.group(1));
		int minutes = Integer.parseInt(matcher.group(2));
		if (hours < 0 || hours > 23 || minutes < 0 || minutes > 59) {
			return null;
		}
		return hours * 60 + minutes;
	}

	/**
	 * {@code shift_duration_minutes()}: same-day when the end is later, zero
	 * when the two are equal, and an overnight wrap when the end is earlier.
	 * Null propagates from either operand.
	 */
	public static Integer durationMinutes(String startTime, String endTime) {
		Integer start = toMinutes(startTime);
		Integer end = toMinutes(endTime);
		if (start == null || end == null) {
			return null;
		}
		if (end > start) {
			return end - start;
		}
		if (end.equals(start)) {
			return 0;
		}
		return (MINUTES_PER_DAY - start) + end;
	}

	/**
	 * {@code assert_shift_daily_window_valid()}: the three failures in PHP's
	 * own order, each a {@code fail(..., 400)}.
	 *
	 * <p>The zero case is reachable only through equal times, because the
	 * overnight branch cannot produce zero -- a wrap of a non-zero start is at
	 * least one minute. So {@code duration <= 0} is in practice
	 * {@code duration == 0}; the comparison is kept as PHP writes it.
	 *
	 * @throws LegacyApiException 400 {@code invalid_input} when either time is
	 *         unparseable, 400 {@code shift_end_must_be_after_start} when the
	 *         window is empty, 400 {@code shift_exceeds_max_hours} beyond 16h
	 */
	static void assertDailyWindowValid(String startTime, String endTime) {
		Integer duration = durationMinutes(startTime, endTime);
		if (duration == null) {
			throw new LegacyApiException(400, "invalid_input");
		}
		if (duration <= 0) {
			throw new LegacyApiException(400, "shift_end_must_be_after_start");
		}
		if (duration > MAX_DURATION_MINUTES) {
			throw new LegacyApiException(400, "shift_exceeds_max_hours");
		}
	}

}

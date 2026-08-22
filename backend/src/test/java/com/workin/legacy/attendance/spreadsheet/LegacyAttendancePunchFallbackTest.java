package com.workin.legacy.attendance.spreadsheet;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Step 8 of {@link LegacyAttendancePunchDateTimeParser}: the values that fail
 * all sixteen explicit formats and reach {@code strtotime()}.
 *
 * <p>Split out from the main differential because this surface is
 * {@link com.workin.legacy.LegacyPhpStrtotime}'s, not the punch parser's, and
 * because it is the one place where the two are known to disagree. D-094 made
 * that grammar deliberately bounded and one-sided -- a value PHP parses through
 * some branch it does not model is reported as unparseable, never as a
 * different date. This class pins both halves: what agrees, and what does not.
 *
 * <p>Every expectation is measured PHP 8.3 under {@code Etc/GMT-2} with the
 * clock frozen at {@code 2026-08-22 22:51:55}.
 */
class LegacyAttendancePunchFallbackTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 22, 22, 51, 55);

	private static LocalDateTime parse(String raw) {
		return LegacyAttendancePunchDateTimeParser.parse(raw, NOW);
	}

	@ParameterizedTest(name = "[{index}] {0} -> {1}")
	@CsvSource(delimiter = '|', value = {
		"2026-04-26            | 2026-04-26T00:00",
		"2026/04/26            | 2026-04-26T00:00",
		"26-04-2026            | 2026-04-26T00:00",
		"2026-04-26T08:03:00   | 2026-04-26T08:03",
		"2026-04               | 2026-04-01T00:00",
		"0803                  | 2026-08-22T08:03",
		"now                   | 2026-08-22T22:51:55",
		"today                 | 2026-08-22T00:00",
		"tomorrow              | 2026-08-23T00:00",
		"2026-04-26 08:03:00 UTC | 2026-04-26T08:03",
		// Sub-second precision is not in any format and not in strtotime's
		// reach either: both answer null.
		"26/04/2026 08:03:00.500 | NULL",
	})
	void theFallbackAgreesWithPhp(String raw, String expected) {
		LocalDateTime actual = parse(raw);
		if ("NULL".equals(expected)) {
			assertThat(actual).isNull();
		} else {
			assertThat(actual).isEqualTo(LocalDateTime.parse(expected));
		}
	}

	/**
	 * Six digits are an <b>Excel serial</b>, not {@code HHMMSS}: the numeric
	 * branch is taken first and {@code 080315} is above 1000, so it never
	 * reaches the fallback at all. Four digits are below it and do.
	 */
	@Test
	void sixDigitsAreASerialAndFourDigitsAreATime() {
		assertThat(parse("080315")).isEqualTo(LocalDateTime.of(2119, 11, 22, 0, 0));
		assertThat(parse("0803")).isEqualTo(LocalDateTime.of(2026, 8, 22, 8, 3));
	}

	/**
	 * <b>Reported divergences, not a contract.</b>
	 *
	 * <p>Each value below is accepted by PHP's {@code strtotime()} and returns
	 * {@code null} here, because D-094's bounded grammar does not model its
	 * branch. They are asserted so the gap is visible and so a future change to
	 * that grammar is a deliberate, reviewed one -- <b>not</b> because null is
	 * the right answer. Broadening the grammar is an owner decision, and this
	 * test is the thing that should change when it is taken.
	 *
	 * <p>Two families, both newly evidenced by this endpoint:
	 *
	 * <ul>
	 * <li><b>A colon time with no date.</b> {@code 11:33} is 11:33 today to
	 *     PHP, with or without a meridiem. A bare {@code 0803} <em>is</em>
	 *     modelled, so the gap is the colon form specifically.</li>
	 * <li><b>A month-name date whose year has been eaten.</b> {@code 26 Apr 2026}
	 *     has no AM/PM tail, so step 4's device-suffix strip removes
	 *     {@code " 2026"} -- {@code 2026} is four alphanumerics preceded by
	 *     whitespace -- and PHP then resolves the remaining {@code 26 Apr}
	 *     against the current year, arriving back at 26 April 2026 by
	 *     coincidence. The grammar models {@code day monthname year} and not
	 *     {@code day monthname}, so the stripped value is unparseable here.
	 *     A value carrying a time keeps its year and diverges for the other
	 *     half of the same reason: the grammar's month-name patterns are
	 *     anchored and admit no trailing time.</li>
	 * </ul>
	 */
	@ParameterizedTest(name = "[{index}] {0} is null here and {1} in PHP")
	@CsvSource(delimiter = '|', value = {
		"11:33                | 2026-08-22 11:33:00",
		"11:33:20             | 2026-08-22 11:33:20",
		"8:03                 | 2026-08-22 08:03:00",
		"08:03 AM             | 2026-08-22 08:03:00",
		"08:03:20 PM          | 2026-08-22 20:03:20",
		"26 Apr 2026          | 2026-04-26 00:00:00",
		"Apr 26 2026          | 2026-04-26 00:00:00",
		"26 April 2026 08:03  | 2026-04-26 08:03:00",
	})
	void theseAreAcceptedByPhpAndReportedAsUnparseableHere(String raw, String phpWouldSay) {
		assertThat(parse(raw))
				.describedAs("PHP resolves %s to %s; D-094's grammar does not model it", raw, phpWouldSay)
				.isNull();
	}

}

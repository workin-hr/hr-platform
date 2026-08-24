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
 * {@link com.workin.legacy.LegacyPhpStrtotime}'s, not the punch parser's. D-094
 * made that grammar deliberately bounded and one-sided -- a value PHP parses
 * through a branch it does not model is reported as unparseable, never as a
 * different date -- and this endpoint is what evidenced two of those branches
 * and closed them: a clock time with no date, and a month-name date.
 *
 * <p>What survives is a single, narrower exclusion: a trailing military
 * timezone letter. It is pinned at the bottom of this class rather than left
 * implicit.
 *
 * <p>Every expectation is measured PHP 8.3 under {@code Etc/GMT-2} with the
 * clock frozen at {@code 2026-08-22 22:51:55}. The punch parser's own
 * preprocessing runs first, so several of these are not the value
 * {@code strtotime()} finally sees.
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
	 * The families D-094's follow-up closed, reached through the punch parser
	 * rather than through the grammar directly -- which matters, because step 4
	 * rewrites two of them on the way.
	 *
	 * <p>{@code 26 Apr 2026} has no AM/PM tail, so the device-suffix strip
	 * removes {@code " 2026"}: four alphanumerics preceded by whitespace is
	 * exactly what that pattern looks for. PHP then resolves the remaining
	 * {@code 26 Apr} against the reference year and arrives back at 26 April
	 * 2026. The value that reaches {@code strtotime()} is therefore <em>not</em>
	 * the value the sheet held, and a test that only exercised the grammar
	 * directly would never see it.
	 *
	 * <p>{@code 26 April 2026 08:03} keeps its year, because its last token
	 * carries a colon and the suffix pattern matches alphanumerics only.
	 */
	@ParameterizedTest(name = "[{index}] {0} -> {1}")
	@CsvSource(delimiter = '|', value = {
		"11:33               | 2026-08-22T11:33",
		"11:33:20            | 2026-08-22T11:33:20",
		"8:03                | 2026-08-22T08:03",
		"08:03 AM            | 2026-08-22T08:03",
		"08:03:20 PM         | 2026-08-22T20:03:20",
		"26 Apr 2026         | 2026-04-26T00:00",
		"Apr 26 2026         | 2026-04-26T00:00",
		"26 April 2026 08:03 | 2026-04-26T08:03",
	})
	void theNewlyEvidencedFallbackFamiliesNowMatchPhp(String raw, String expected) {
		assertThat(parse(raw)).isEqualTo(LocalDateTime.parse(expected));
	}

	/**
	 * The suffix strip is load-bearing above, so it is asserted rather than
	 * inferred -- and it really does destroy information.
	 *
	 * <p>{@code 26 Apr 1990} loses its year to the strip and comes back as
	 * <b>2026</b>, the reference year. Add a time and the year survives,
	 * because the last token is then a clock rather than a bare number. Both
	 * measured against PHP; the first is the kind of thing that looks like a
	 * bug in the port and is not.
	 */
	@Test
	void theDeviceSuffixStripCanDestroyTheYear() {
		assertThat(parse("26 Apr 1990")).isEqualTo(LocalDateTime.of(2026, 4, 26, 0, 0));
		assertThat(parse("26 Apr 1990 08:03")).isEqualTo(LocalDateTime.of(1990, 4, 26, 8, 3));
	}

	/**
	 * {@code 26 Febr} is null on both sides, but not for the reason the bare
	 * grammar gives: here the strip removes {@code " Febr"} and leaves a lone
	 * {@code 26}, which {@code strtotime()} rejects. The timezone-letter
	 * reading only applies when the value reaches the grammar untouched.
	 */
	@Test
	void aStrippedMonthNameLeavesNothingParseable() {
		assertThat(parse("26 Febr")).isNull();
	}

	/**
	 * <b>One reported divergence remains, and it is D-094's existing exclusion
	 * rather than a new one.</b>
	 *
	 * <p>PHP reads the trailing {@code a} in {@code 08:03 a} as the military
	 * timezone UTC+1, giving {@code 2026-08-22 09:03:00} under UTC+2 -- an
	 * hour later than the "8am" a reader would expect. Supporting it means
	 * implementing timezone-suffix tokens, which D-094 deliberately does not
	 * model and which this change was explicitly not to add. Pinned so the gap
	 * stays visible and so adding it later is a deliberate, reviewed change.
	 *
	 * <p>Reachable only from a value ending in a lone {@code a} or {@code p},
	 * since two or more trailing alphanumerics are removed as a device suffix
	 * before the grammar ever sees them.
	 */
	@Test
	void aLoneTimezoneLetterIsStillOutsideTheGrammar() {
		assertThat(parse("08:03 a"))
				.describedAs("PHP reads the trailing 'a' as UTC+1 and answers 2026-08-22 09:03:00")
				.isNull();
	}

}

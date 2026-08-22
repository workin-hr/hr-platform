package com.workin.legacy;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * {@link LegacyPhpStrtotime#dateTimeOf} against a PHP 8.3 probe.
 *
 * <p>Every expectation here is the measured value of
 * {@code date('Y-m-d H:i:s', strtotime($input, $reference))} under
 * {@code Etc/GMT-2} with the reference frozen at {@code 2026-03-11 14:37:19},
 * which is the timezone convention the existing measured date/year tests use.
 * Freezing the reference is what makes the relative and time-only families
 * deterministic.
 *
 * <p>{@link LegacyPhpStrtotime#dateOf} keeps its own tests unchanged: it is now
 * this method projected with {@code toLocalDate()}, so the date results it
 * asserted are the same results, and the two cannot drift because there is one
 * grammar.
 */
class LegacyPhpStrtotimeTimestampTest {

	/** The frozen probe reference: {@code 2026-03-11 14:37:19}. */
	private static final LocalDateTime REFERENCE = LocalDateTime.of(2026, 3, 11, 14, 37, 19);

	private static LocalDateTime parse(String raw) {
		return LegacyPhpStrtotime.dateTimeOf(raw, REFERENCE);
	}

	@ParameterizedTest(name = "{0} -> {1}")
	@CsvSource({
		// ISO, with and without a time
		"'2026-01-15',            '2026-01-15T00:00'",
		"'2026-01-15 09:30',      '2026-01-15T09:30'",
		"'2026-01-15 09:30:00',   '2026-01-15T09:30'",
		"'2026-01-15T09:30:00',   '2026-01-15T09:30'",
		"'2026-01-15 00:00:00',   '2026-01-15T00:00'",
		"'2026-01-15 23:59:59',   '2026-01-15T23:59:59'",
		// slashed ISO
		"'2026/01/15',            '2026-01-15T00:00'",
		"'2026/01/15 09:30:00',   '2026-01-15T09:30'",
		// relative -- `now` keeps the clock, `today` does not
		"'now',                   '2026-03-11T14:37:19'",
		"'NOW',                   '2026-03-11T14:37:19'",
		"'today',                 '2026-03-11T00:00'",
		"'Today',                 '2026-03-11T00:00'",
		"'tomorrow',              '2026-03-12T00:00'",
		"'yesterday',             '2026-03-10T00:00'",
		// time-only, resolved against the reference date
		"'0000',                  '2026-03-11T00:00'",
		"'0830',                  '2026-03-11T08:30'",
		"'1200',                  '2026-03-11T12:00'",
		"'2359',                  '2026-03-11T23:59'",
		"'083000',                '2026-03-11T08:30'",
		"'235959',                '2026-03-11T23:59:59'",
		// hour 24 rolls into the next day at midnight
		"'2400',                  '2026-03-12T00:00'",
		"'240000',                '2026-03-12T00:00'",
		// dashed, slashed and month-name dates are midnight
		"'15-01-1990',            '1990-01-15T00:00'",
		"'1-1-1',                 '2001-01-01T00:00'",
		"'01/15/1990',            '1990-01-15T00:00'",
		"'12/31/2026',            '2026-12-31T00:00'",
		"'15 Jan 1990',           '1990-01-15T00:00'",
		"'15 January 1990',       '1990-01-15T00:00'",
		"'15 sept 1990',          '1990-09-15T00:00'",
		// rolling forms roll, and still land at midnight
		"'31-4-2024',             '2024-05-01T00:00'",
		"'0-0-2024',              '2023-11-30T00:00'",
		"'2026-02-30',            '2026-03-02T00:00'",
	})
	void reproducesThePhpTimestamp(String input, String expected) {
		assertThat(parse(input)).isEqualTo(LocalDateTime.parse(expected));
	}

	/**
	 * The finding the {@link LocalDate} projection hid: a four-digit value that
	 * is not a valid clock is a <em>year</em>, and it keeps the reference
	 * month, day <b>and time</b>.
	 *
	 * <p>This matters beyond curiosity. {@code attendance/create.php} decides
	 * whether a punch is real with
	 * {@code date('H:i:s', $ts) !== '00:00:00'}, so a {@code check_in} of
	 * {@code "1990"} is a real punch, not an exception-only day.
	 */
	@ParameterizedTest(name = "{0} is a year carrying the reference time")
	@CsvSource({
		"'1990', '1990-03-11T14:37:19'",
		"'2500', '2500-03-11T14:37:19'",
	})
	void theYearBranchKeepsTheReferenceTime(String input, String expected) {
		assertThat(parse(input)).isEqualTo(LocalDateTime.parse(expected));
		// ...and it is emphatically not midnight, which is the whole point.
		assertThat(parse(input).toLocalTime()).isEqualTo(REFERENCE.toLocalTime());
	}

	@Test
	void midnightIsDistinguishableFromANonMidnightPunch() {
		// The exact test attendance performs, both ways round.
		assertThat(parse("2026-01-15").toLocalTime()).isEqualTo(java.time.LocalTime.MIDNIGHT);
		assertThat(parse("2026-01-15 00:00:00").toLocalTime()).isEqualTo(java.time.LocalTime.MIDNIGHT);
		assertThat(parse("today").toLocalTime()).isEqualTo(java.time.LocalTime.MIDNIGHT);
		assertThat(parse("2400").toLocalTime()).isEqualTo(java.time.LocalTime.MIDNIGHT);

		assertThat(parse("2026-01-15 09:30").toLocalTime()).isNotEqualTo(java.time.LocalTime.MIDNIGHT);
		assertThat(parse("0830").toLocalTime()).isNotEqualTo(java.time.LocalTime.MIDNIGHT);
		assertThat(parse("now").toLocalTime()).isNotEqualTo(java.time.LocalTime.MIDNIGHT);
		assertThat(parse("1990").toLocalTime()).isNotEqualTo(java.time.LocalTime.MIDNIGHT);
	}

	@ParameterizedTest(name = "{0} is unparseable")
	@CsvSource({
		"'oops'",
		"'2026-13-01'",
		"'99:99'",
		"'1-1-69'",
		"'15 septem 1990'",
		"'2026-08-21 25:00:00'",
	})
	void keepsTheMeasuredFailureFamily(String input) {
		assertThat(parse(input)).isNull();
	}

	/** {@code 15 janu 1990} -- see D-094. PHP parses it; this grammar does not. */
	@Test
	void theTimezoneSuffixBranchStaysUnimplemented() {
		// PHP 8.3 reads `jan` + the military timezone `u` (UTC-8) and answers
		// 1990-01-15 10:00:00 under UTC+2. Reproducing that would mean adding
		// timezone-token parsing, which D-094 declines. The divergence is
		// one-sided: Java rejects what PHP accepts.
		assertThat(parse("15 janu 1990")).isNull();
		// The neighbouring forms PHP also rejects, for contrast.
		assertThat(parse("15 janua 1990")).isNull();
		assertThat(parse("15 septem 1990")).isNull();
	}

	/**
	 * The "now" reading applies to the whitespace-only family and nothing
	 * else. A trailing accepted character does not rescue invalid prose.
	 *
	 * <p>Measured under PHP 8.3: "oops ", "garbage" + TAB, "nonsense" + LF,
	 * "2026-13-01 ", "oops" + NUL, " oops" and "2026-13-01" + TAB are all
	 * false, while "2026-01-15 " parses. So the terminal-character rule is
	 * a property of whitespace-only input, not a suffix rule -- which is
	 * why the implementation tests whitespace-only first.
	 */
	@Test
	void trailingWhitespaceDoesNotMakeProseParse() {
		// Deliberately not a CsvSource: it strips surrounding whitespace, and
		// here the whitespace IS the payload -- the assertion would otherwise
		// pass for the wrong reason.
		assertThat(parse("oops ")).isNull();
		assertThat(parse(" oops")).isNull();
		assertThat(parse("2026-13-01 ")).isNull();
		assertThat(parse("garbage" + "\t")).isNull();
		assertThat(parse("nonsense" + "\n")).isNull();
		assertThat(parse("oops" + String.valueOf((char) 0x00))).isNull();
		assertThat(parse("2026-13-01" + "\t")).isNull();

		// ...while a real date with trailing whitespace still parses, so this
		// is not "any trailing whitespace fails".
		assertThat(parse("2026-01-15 ")).isEqualTo(LocalDateTime.parse("2026-01-15T00:00"));
	}

	/**
	 * The year fallback is a rule, not two special cases: any four digits
	 * that do not read as a valid HHMM become a year, and every one of them
	 * keeps the reference time. Measured: 0060, 1360, 0099, 2599.
	 */
	@ParameterizedTest(name = "{0} is a year at the reference time")
	@CsvSource({
		"'0060', '0060-03-11T14:37:19'",
		"'1360', '1360-03-11T14:37:19'",
		"'0099', '0099-03-11T14:37:19'",
		"'2599', '2599-03-11T14:37:19'",
	})
	void anyInvalidClockFallsBackToAYearKeepingTheReferenceTime(String input, String expected) {
		assertThat(parse(input)).isEqualTo(LocalDateTime.parse(expected));
	}
	@Test
	void dateOfIsThisMethodProjected() {
		// One grammar, two views -- so the older callers cannot drift.
		LocalDate today = REFERENCE.toLocalDate();
		for (String input : java.util.List.of(
				"2026-01-15", "2026-01-15 09:30", "now", "today", "tomorrow",
				"0830", "2400", "1990", "15 Jan 1990", "31-4-2024")) {
			assertThat(LegacyPhpStrtotime.dateOf(input, today))
					.describedAs("input %s", input)
					.isEqualTo(LegacyPhpStrtotime.dateTimeOf(input, today.atStartOfDay()).toLocalDate());
		}
	}

}

package com.workin.legacy.attendance.spreadsheet;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * {@link LegacyAttendancePunchDateTimeParser} against measured PHP 8.3 output.
 *
 * <p>Every expectation below was produced by running the literal body of
 * {@code attendance_import_parse_punch_datetime()} under
 * {@code php:8.3-cli} with {@code date_default_timezone_set('Etc/GMT-2')}. The
 * reference instant is frozen at {@code 2026-08-22 22:51:55} so the rows that
 * reach the {@code strtotime} fallback -- the only ones whose answer depends on
 * the clock -- are reproducible.
 *
 * <p>{@code NULL} in the expectation column means PHP returned null.
 */
class LegacyAttendancePunchDateTimeParserTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 22, 22, 51, 55);

	private static LocalDateTime parse(String raw) {
		return LegacyAttendancePunchDateTimeParser.parse(raw, NOW);
	}

	@ParameterizedTest(name = "[{index}] {0} -> {1}")
	@CsvSource(delimiter = '|', value = {
		// The shape the helper's own docblock names, device suffix and all.
		"26/04/2026 11:33 A4P4      | 2026-04-26T11:33",
		"26/04/2026 11:33:20 A4P4   | 2026-04-26T11:33:20",
		"26/04/2026 11:33           | 2026-04-26T11:33",
		"26/04/2026 11:33:20        | 2026-04-26T11:33:20",
		// 12-hour exports keep their meridiem: the suffix strip is skipped.
		"26/04/2026 01:33 PM        | 2026-04-26T13:33",
		"26/04/2026 01:33:20 pm     | 2026-04-26T13:33:20",
		"26/04/2026 11:33 AM        | 2026-04-26T11:33",
		// The other separators and orders in the format list.
		"26-04-2026 11:33           | 2026-04-26T11:33",
		"2026-04-26 11:33:20        | 2026-04-26T11:33:20",
		"2026-04-26 11:33           | 2026-04-26T11:33",
		"2026/04/26 11:33           | 2026-04-26T11:33",
		// Dotted dates are rewritten; the time's colons are untouched.
		"18.07.2026 10:20:24        | 2026-07-18T10:20:24",
		"18.07.2026 10:20           | 2026-07-18T10:20",
		// Backslashes become slashes.
		"26\\04\\2026 11:33          | 2026-04-26T11:33",
		// A rolled component is discarded, never accepted.
		"31/02/2026 10:00           | NULL",
		"26/04/2026 25:00           | NULL",
		// Excel serials, strictly above 1000.
		"45000.25                   | 2023-03-15T06:00",
		"45000                      | 2023-03-15T00:00",
		"25569                      | 1970-01-01T00:00",
		"1001                       | 1902-09-27T00:00",
		// Not parseable at all.
		"garbage                    | NULL",
		"999                        | NULL",
		// The suffix strip is 2 to 8 alphanumerics, no more and no less.
		"26/04/2026 11:33 XY        | 2026-04-26T11:33",
		"26/04/2026 11:33 A         | NULL",
		"26/04/2026 11:33 ABCDEFGHI | NULL",
	})
	void matchesPhp(String raw, String expected) {
		LocalDateTime actual = parse(raw);
		if ("NULL".equals(expected)) {
			assertThat(actual).isNull();
		} else {
			assertThat(actual).isEqualTo(LocalDateTime.parse(expected));
		}
	}

	/**
	 * The whole reason this parser exists rather than a call to
	 * {@link com.workin.legacy.LegacyPhpStrtotime}.
	 *
	 * <p>{@code 05/06/2026} is 5 June here, because the explicit list is
	 * day-first and it matches before any fallback runs. The general grammar
	 * reads the same value the American way and would answer 6 May. Both are
	 * PHP, in different functions -- so reusing one for the other would
	 * transpose day and month on every punch whose day is 12 or below.
	 */
	@Test
	void aSlashedDateIsDayFirstHereAndMonthFirstInTheGeneralGrammar() {
		assertThat(parse("05/06/2026 10:00")).isEqualTo(LocalDateTime.of(2026, 6, 5, 10, 0));
		assertThat(com.workin.legacy.LegacyPhpStrtotime.dateTimeOf("05/06/2026", NOW))
				.isEqualTo(LocalDateTime.of(2026, 5, 6, 0, 0));
	}

	/**
	 * The day-first format rolls on month 13, is discarded, and the American
	 * format at the end of the list then matches cleanly. Without the
	 * {@code getLastErrors()} check this would have been 2027-01-05.
	 */
	@Test
	void anImpossibleDayFirstDateFallsThroughToTheAmericanFormat() {
		assertThat(parse("05/13/2026 10:00")).isEqualTo(LocalDateTime.of(2026, 5, 13, 10, 0));
	}

	/**
	 * Seconds are zero, never the current second.
	 *
	 * <p>{@code createFromFormat} without {@code !} fills unspecified fields
	 * from the current time, which would make an imported punch's seconds
	 * depend on when the import ran -- except that PHP zeroes the remaining
	 * <em>time</em> fields once any time field has been parsed, and all sixteen
	 * formats carry one. Measured, and pinned here because getting it wrong
	 * would be invisible in a single run and non-deterministic across runs.
	 */
	@Test
	void anOmittedSecondIsZeroRatherThanTheCurrentSecond() {
		assertThat(parse("26/04/2026 11:33")).isEqualTo(LocalDateTime.of(2026, 4, 26, 11, 33, 0));
		assertThat(parse("26/04/2026 11:33").getSecond()).isZero();
	}

	/**
	 * 1000 is not a serial -- the branch is strictly greater -- so it reaches
	 * {@code strtotime}, which reads it as 10:00 today.
	 */
	@Test
	void theSerialBranchIsStrictlyAboveOneThousand() {
		assertThat(parse("1000")).isEqualTo(LocalDateTime.of(2026, 8, 22, 10, 0));
		assertThat(parse("1001")).isEqualTo(LocalDateTime.of(1902, 9, 27, 0, 0));
	}

	/**
	 * The bidi marks and the BOM are removed, and surrounding whitespace is
	 * trimmed. Written out of {@code char} values rather than pasted, because
	 * the three characters are invisible in a source file and a lost one would
	 * make this test pass by accident.
	 */
	@Test
	void bidiMarksAndTheBomAreStripped() {
		String lrm = String.valueOf((char) 0x200E);
		String rlm = String.valueOf((char) 0x200F);
		String bom = String.valueOf((char) 0xFEFF);
		LocalDateTime expected = LocalDateTime.of(2026, 4, 26, 11, 33);

		assertThat(parse(lrm + "26/04/2026 11:33" + rlm)).isEqualTo(expected);
		assertThat(parse(bom + "26/04/2026 11:33")).isEqualTo(expected);
		assertThat(parse("  26/04/2026 11:33  ")).isEqualTo(expected);
	}

	/** Null and blank are null, and the blank check runs on PHP's trim charlist. */
	@Test
	void nullAndBlankAreNull() {
		assertThat(LegacyAttendancePunchDateTimeParser.parse(null, NOW)).isNull();
		assertThat(parse("")).isNull();
		assertThat(parse("   ")).isNull();
	}

	/**
	 * A date with no time at all is not in the format list, so it depends
	 * entirely on the fallback -- and the two slash conventions part company
	 * again: {@code 2026-04-26} is midnight, {@code 26/04/2026} is null because
	 * {@code strtotime} reads it as month 26.
	 */
	@Test
	void aDateWithoutATimeReachesTheFallback() {
		assertThat(parse("2026-04-26")).isEqualTo(LocalDateTime.of(2026, 4, 26, 0, 0));
		assertThat(parse("26/04/2026")).isNull();
	}

}

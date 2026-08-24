package com.workin.legacy;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The two {@code strtotime()} families D-094's follow-up activates: a clock
 * time with no date, and a month-name date.
 *
 * <p>Both were evidenced by {@code attendance/import_excel.php}, whose punch
 * parser falls back to {@code strtotime()} after sixteen explicit formats have
 * failed -- and the device-suffix strip manufactures month-name values with no
 * year by eating {@code " 2026"} off the end of {@code 26 Apr 2026}.
 *
 * <p>Every expectation is measured against PHP 8.3 under {@code Etc/GMT-2},
 * with the reference instant passed explicitly to {@code strtotime()} so the
 * reference-date and reference-time semantics are observable rather than
 * incidental. The reference is deliberately late in the day and on a non-zero
 * second, so "did the seconds come from the reference or from zero?" has a
 * visible answer.
 */
class LegacyPhpStrtotimeClockAndMonthNameTest {

	/** 2026-08-22 22:51:55 -- a Saturday, day 22, second 55. */
	private static final LocalDateTime REFERENCE = LocalDateTime.of(2026, 8, 22, 22, 51, 55);

	/** 2026-01-31 10:00:00 -- day 31, for the month-only rolls. */
	private static final LocalDateTime END_OF_MONTH = LocalDateTime.of(2026, 1, 31, 10, 0, 0);

	private static LocalDateTime parse(String raw) {
		return LegacyPhpStrtotime.dateTimeOf(raw, REFERENCE);
	}

	private static void expect(String raw, String expected) {
		LocalDateTime actual = parse(raw);
		if ("NULL".equals(expected)) {
			assertThat(actual).describedAs(raw).isNull();
		} else {
			assertThat(actual).describedAs(raw).isEqualTo(LocalDateTime.parse(expected.replace(' ', 'T')));
		}
	}

	// ------------------------------------------------------------------
	// Clock time, no date
	// ------------------------------------------------------------------

	/**
	 * The date comes from the reference and the seconds are <b>zero</b>, not
	 * the reference's 55 -- so a time-only cell is not "now with the hour
	 * replaced".
	 */
	@ParameterizedTest(name = "[{index}] {0} -> {1}")
	@CsvSource(delimiter = '|', value = {
		"11:33    | 2026-08-22 11:33:00",
		"8:03     | 2026-08-22 08:03:00",
		"08:03    | 2026-08-22 08:03:00",
		"0:00     | 2026-08-22 00:00:00",
		"23:59    | 2026-08-22 23:59:00",
		// A one-digit minute is a TENS-less minute: 11:5 is 11:05, not 11:50.
		"9:5      | 2026-08-22 09:05:00",
		"11:5     | 2026-08-22 11:05:00",
		"11:33:20 | 2026-08-22 11:33:20",
		"8:03:07  | 2026-08-22 08:03:07",
		"11:33:7  | 2026-08-22 11:33:07",
		"23:59:59 | 2026-08-22 23:59:59",
	})
	void aClockTimeTakesTheReferenceDateAndZeroSeconds(String raw, String expected) {
		expect(raw, expected);
	}

	/**
	 * Hour 24 is legal and rolls; hour 25 is not. Minute 60 is not legal, but
	 * second 60 <b>is</b> -- the leap second -- and rolls a minute. The two
	 * fields do not share a rule, and 61 is rejected.
	 */
	@ParameterizedTest(name = "[{index}] {0} -> {1}")
	@CsvSource(delimiter = '|', value = {
		"24:00       | 2026-08-23 00:00:00",
		"24:01       | 2026-08-23 00:01:00",
		"24:00:01    | 2026-08-23 00:00:01",
		"25:00       | NULL",
		"11:60       | NULL",
		"11:33:60    | 2026-08-22 11:34:00",
		"11:33:61    | NULL",
		"11:33:99    | NULL",
		// Every field is at most two digits.
		"011:33      | NULL",
		"11:033      | NULL",
		"11:333      | NULL",
		"11:33:020   | NULL",
		"11:33:20:40 | NULL",
	})
	void outOfRangeAndOverWideFieldsFollowPhpsOwnRules(String raw, String expected) {
		expect(raw, expected);
	}

	/**
	 * A meridiem tightens the hour to 1-12 and forces two-digit minutes and
	 * seconds. {@code 08:03 a} is <b>not</b> "8am": PHP reads a bare {@code a}
	 * as a military timezone, which D-094 deliberately does not model, so it
	 * stays unparseable here.
	 */
	@ParameterizedTest(name = "[{index}] {0} -> {1}")
	@CsvSource(delimiter = '|', value = {
		"08:03 AM      | 2026-08-22 08:03:00",
		"08:03 PM      | 2026-08-22 20:03:00",
		"8:03 am       | 2026-08-22 08:03:00",
		"8:03 pm       | 2026-08-22 20:03:00",
		"12:00 AM      | 2026-08-22 00:00:00",
		"12:00 PM      | 2026-08-22 12:00:00",
		"1:05 PM       | 2026-08-22 13:05:00",
		"08:03:20 PM   | 2026-08-22 20:03:20",
		// No space needed, and extra space is fine.
		"08:03AM       | 2026-08-22 08:03:00",
		// Dotted spellings, in every combination PHP accepts.
		"08:03 A.M.    | 2026-08-22 08:03:00",
		"08:03 P.M.    | 2026-08-22 20:03:00",
		"08:03 am.     | 2026-08-22 08:03:00",
		"08:03 a.m     | 2026-08-22 08:03:00",
		"08:03 P.M     | 2026-08-22 20:03:00",
		"08:03 Am      | 2026-08-22 08:03:00",
		"08:03:20 a.m. | 2026-08-22 08:03:20",
		// Out of the 12-hour range.
		"00:03 AM      | NULL",
		"13:03 PM      | NULL",
		// A one-digit minute is rejected once a meridiem is present.
		"1:5 PM        | NULL",
		"8:3 PM        | NULL",
	})
	void aMeridiemTightensTheHourAndTheFieldWidths(String raw, String expected) {
		expect(raw, expected);
	}

	/** {@code .} is interchangeable with {@code :}, and the two may be mixed. */
	@ParameterizedTest(name = "[{index}] {0} -> {1}")
	@CsvSource(delimiter = '|', value = {
		"11.33       | 2026-08-22 11:33:00",
		"11.33.20    | 2026-08-22 11:33:20",
		"8.3         | 2026-08-22 08:03:00",
		"11.5        | 2026-08-22 11:05:00",
		"24.00       | 2026-08-23 00:00:00",
		"11:33.20    | 2026-08-22 11:33:20",
		"11.33:20    | 2026-08-22 11:33:20",
		"11.33 PM    | 2026-08-22 23:33:00",
		"11.33.20 AM | 2026-08-22 11:33:20",
		"11.60       | NULL",
		"25.00       | NULL",
	})
	void aDotIsTheSameSeparatorAsAColon(String raw, String expected) {
		expect(raw, expected);
	}

	/** Whitespace around the colon is not a time; a partial one is not either. */
	@ParameterizedTest(name = "[{index}] {0} -> NULL")
	@CsvSource({"'11 : 33'", "'11:33:'", "':33'", "'11:'", "'11:33 xyz'"})
	void aMalformedClockIsUnparseable(String raw) {
		expect(raw, "NULL");
	}

	// ------------------------------------------------------------------
	// Month-name dates
	// ------------------------------------------------------------------

	/** With a year, in either order, with either separator, and with or without a comma. */
	@ParameterizedTest(name = "[{index}] {0} -> {1}")
	@CsvSource(delimiter = '|', value = {
		"26 Apr 2026     | 2026-04-26 00:00:00",
		"26 April 2026   | 2026-04-26 00:00:00",
		"Apr 26 2026     | 2026-04-26 00:00:00",
		"April 26 2026   | 2026-04-26 00:00:00",
		"26-Apr-2026     | 2026-04-26 00:00:00",
		"1 Jan 1990      | 1990-01-01 00:00:00",
		"31 Dec 2099     | 2099-12-31 00:00:00",
		"26 Sept 2026    | 2026-09-26 00:00:00",
		"26 apr 2026     | 2026-04-26 00:00:00",
		// The day rolls rather than being rejected.
		"29 Feb 2024     | 2024-02-29 00:00:00",
		"29 Feb 2026     | 2026-03-01 00:00:00",
		"31 Apr 2026     | 2026-05-01 00:00:00",
	})
	void aMonthNameDateWithAYear(String raw, String expected) {
		expect(raw, expected);
	}

	/** {@code Apr 26, 2026} -- the comma form, which only the month-first order has. */
	@Test
	void theCommaFormIsAccepted() {
		expect("Apr 26, 2026", "2026-04-26 00:00:00");
	}

	/**
	 * A short year is 20xx up to 69 and 19xx from 70 -- and a three-digit token
	 * goes through the same rule, so {@code 026} is 2026 rather than the year
	 * 26.
	 */
	@ParameterizedTest(name = "[{index}] {0} -> {1}")
	@CsvSource(delimiter = '|', value = {
		"26 Apr 00  | 2000-04-26 00:00:00",
		"26 Apr 68  | 2068-04-26 00:00:00",
		"26 Apr 69  | 2069-04-26 00:00:00",
		"26 Apr 70  | 1970-04-26 00:00:00",
		"26 Apr 99  | 1999-04-26 00:00:00",
		"26 Apr 1   | 2001-04-26 00:00:00",
		"26 Apr 026 | 2026-04-26 00:00:00",
		"26 Apr 26  | 2026-04-26 00:00:00",
	})
	void aShortYearFollowsThe69Boundary(String raw, String expected) {
		expect(raw, expected);
	}

	/**
	 * Without a year the reference year is used, and the day still rolls.
	 * {@code 0 Jan} steps backwards into the previous December.
	 */
	@ParameterizedTest(name = "[{index}] {0} -> {1}")
	@CsvSource(delimiter = '|', value = {
		"26 Apr   | 2026-04-26 00:00:00",
		"1 Jan    | 2026-01-01 00:00:00",
		"31 Dec   | 2026-12-31 00:00:00",
		"26 April | 2026-04-26 00:00:00",
		"26 Sep   | 2026-09-26 00:00:00",
		"Apr 26   | 2026-04-26 00:00:00",
		"April 26 | 2026-04-26 00:00:00",
		"Jan 1    | 2026-01-01 00:00:00",
		"31 Apr   | 2026-05-01 00:00:00",
		"29 Feb   | 2026-03-01 00:00:00",
		"0 Jan    | 2025-12-31 00:00:00",
		"0 Apr    | 2026-03-31 00:00:00",
		"32 Jan   | NULL",
		"26 Xyz   | NULL",
	})
	void aMonthNameDateWithoutAYearUsesTheReferenceYear(String raw, String expected) {
		expect(raw, expected);
	}

	/** A time may follow the date, separated by a space, a comma or a {@code T}. */
	@ParameterizedTest(name = "[{index}] {0} -> {1}")
	@CsvSource(delimiter = '|', value = {
		"26 Apr 2026 08:03       | 2026-04-26 08:03:00",
		"26 April 2026 08:03     | 2026-04-26 08:03:00",
		"26 Apr 2026 08:03:20    | 2026-04-26 08:03:20",
		"Apr 26 2026 08:03       | 2026-04-26 08:03:00",
		"Apr 26 2026 08:03:20    | 2026-04-26 08:03:20",
		"26 Apr 2026 08:03 PM    | 2026-04-26 20:03:00",
		"26 Apr 2026 8:03 pm     | 2026-04-26 20:03:00",
		"26 Apr 2026 08:03:20 PM | 2026-04-26 20:03:20",
		"26-Apr-2026 08:03       | 2026-04-26 08:03:00",
		"26 Apr 26 08:03         | 2026-04-26 08:03:00",
		"26 Apr 2026 11.33       | 2026-04-26 11:33:00",
		"26 Apr 2026,08:03       | 2026-04-26 08:03:00",
		"26 Apr 2026T08:03       | 2026-04-26 08:03:00",
		// The clock's own rules still apply to a trailing time.
		"26 Apr 2026 24:00       | 2026-04-27 00:00:00",
		"26 Apr 2026 08:03:60    | 2026-04-26 08:04:00",
		"26 Apr 2026 25:00       | NULL",
		"26 Apr 2026 xyz         | NULL",
	})
	void aTimeMayFollowTheDate(String raw, String expected) {
		expect(raw, expected);
	}

	/**
	 * The asymmetry that a tidier grammar would get wrong.
	 *
	 * <p>{@code Apr 26 08:03} parses and {@code 26 Apr 08:03} does not, because
	 * in the day-first form PHP consumes the following bare number as the
	 * <em>year</em> and is then left with {@code :03}. {@code 08:03} carries a
	 * colon, so in the month-first form it can only be a time.
	 */
	@Test
	void aTimeAfterAYearlessDateParsesOnlyInTheMonthFirstOrder() {
		expect("Apr 26 08:03", "2026-04-26 08:03:00");
		expect("26 Apr 08:03", "NULL");
	}

	/**
	 * A month name on its own keeps the reference <b>day</b>; with a year it is
	 * the first of the month instead. Against a 31st the reference day rolls,
	 * which is why this uses its own reference.
	 */
	@Test
	void aBareMonthNameKeepsTheReferenceDayAndRolls() {
		assertThat(LegacyPhpStrtotime.dateTimeOf("Apr", REFERENCE))
				.isEqualTo(LocalDateTime.of(2026, 4, 22, 0, 0));
		assertThat(LegacyPhpStrtotime.dateTimeOf("Apr", END_OF_MONTH))
				.isEqualTo(LocalDateTime.of(2026, 5, 1, 0, 0));
		assertThat(LegacyPhpStrtotime.dateTimeOf("Feb", END_OF_MONTH))
				.isEqualTo(LocalDateTime.of(2026, 3, 3, 0, 0));
		assertThat(LegacyPhpStrtotime.dateTimeOf("Jan", END_OF_MONTH))
				.isEqualTo(LocalDateTime.of(2026, 1, 31, 0, 0));
		assertThat(LegacyPhpStrtotime.dateTimeOf("Apr 2026", END_OF_MONTH))
				.isEqualTo(LocalDateTime.of(2026, 4, 1, 0, 0));
		assertThat(LegacyPhpStrtotime.dateTimeOf("Feb 2026", END_OF_MONTH))
				.isEqualTo(LocalDateTime.of(2026, 2, 1, 0, 0));
	}

	/**
	 * Still outside the grammar, and deliberately so: PHP parses these as a
	 * month name followed by a <b>military timezone letter</b>, which D-094
	 * does not model and which this change does not add.
	 */
	@Test
	void aMonthNameFollowedByATimezoneLetterStaysUnparseable() {
		// PHP: `Feb` + `r` (UTC-5) -> 2026-02-26 07:00:00 under UTC+2.
		assertThat(parse("26 Febr")).isNull();
		// PHP: `jan` + `u` (UTC-8). Already recorded under D-094.
		assertThat(parse("15 janu 1990")).isNull();
		// `Septem` is not a name to PHP either, and is null on both sides.
		assertThat(parse("26 Septem 2026")).isNull();
		// A bare `a` or `m` after a time is a timezone, not a meridiem.
		assertThat(parse("08:03 a")).isNull();
		assertThat(parse("08:03 m")).isNull();
	}

	/** Nothing here disturbs the shapes the grammar already read. */
	@Test
	void theExistingFamiliesAreUnchanged() {
		assertThat(parse("2026-01-15")).isEqualTo(LocalDateTime.of(2026, 1, 15, 0, 0));
		assertThat(parse("15-01-1990")).isEqualTo(LocalDateTime.of(1990, 1, 15, 0, 0));
		assertThat(parse("01/15/1990")).isEqualTo(LocalDateTime.of(1990, 1, 15, 0, 0));
		assertThat(parse("1200")).isEqualTo(LocalDateTime.of(2026, 8, 22, 12, 0));
		assertThat(parse("1990")).isEqualTo(LocalDateTime.of(1990, 8, 22, 22, 51, 55));
		assertThat(parse("now")).isEqualTo(REFERENCE);
		assertThat(parse("garbage")).isNull();
		assertThat(LegacyPhpStrtotime.dateOf("2026-01-15", LocalDate.of(2026, 8, 22)))
				.isEqualTo(LocalDate.of(2026, 1, 15));
	}

}

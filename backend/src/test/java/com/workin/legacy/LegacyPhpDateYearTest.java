package com.workin.legacy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

/**
 * Every expectation here was measured by running
 * {@code (int) date('Y', strtotime($value))} under PHP 8.3 with
 * {@code strict_types=1} and {@code date_default_timezone_set('Etc/GMT-2')} --
 * the timezone {@code create.php} runs under. Nothing is inferred from what a
 * date "should" mean.
 */
class LegacyPhpDateYearTest {

	/** A fixed "today" so the relative keywords are deterministic. */
	private static final LocalDate TODAY = LocalDate.of(2026, 8, 21);

	@Test
	void isoDatesGiveTheirOwnYear() {
		assertThat(LegacyPhpDateYear.of("2026-08-21", TODAY)).isEqualTo(2026);
		assertThat(LegacyPhpDateYear.of("2026-08-21 12:30:00", TODAY)).isEqualTo(2026);
		assertThat(LegacyPhpDateYear.of("2026-08-21 12:30", TODAY)).isEqualTo(2026);
		assertThat(LegacyPhpDateYear.of("2026-08-21T12:30:00", TODAY)).isEqualTo(2026);
		assertThat(LegacyPhpDateYear.of("2026/08/21", TODAY)).isEqualTo(2026);
		assertThat(LegacyPhpDateYear.of("2026-8-1", TODAY)).isEqualTo(2026);
		assertThat(LegacyPhpDateYear.of("2026-08", TODAY)).isEqualTo(2026);
		assertThat(LegacyPhpDateYear.of("  2026-08-21  ", TODAY)).isEqualTo(2026);
	}

	@Test
	void theZeroDateIsMinusOne() {
		// strtotime('0000-00-00') is a real negative timestamp whose year
		// renders as '-0001'; (int) then makes it -1, and a YEAR(4) column
		// stores that as 0000.
		assertThat(LegacyPhpDateYear.of("0000-00-00", TODAY)).isEqualTo(-1);
	}

	@Test
	void dayFirstDashesAndMonthFirstSlashesAreBothTheSameDate() {
		// 21-08-2026 is d-m-Y; 08/21/2026 is m/d/Y. Both resolve to 21 Aug 2026.
		assertThat(LegacyPhpDateYear.of("21-08-2026", TODAY)).isEqualTo(2026);
		assertThat(LegacyPhpDateYear.of("21-8-2026", TODAY)).isEqualTo(2026);
		assertThat(LegacyPhpDateYear.of("08/21/2026", TODAY)).isEqualTo(2026);
		assertThat(LegacyPhpDateYear.of("8/21/2026", TODAY)).isEqualTo(2026);
	}

	@Test
	void monthNamesAreAcceptedInEitherOrderAndAnyCase() {
		assertThat(LegacyPhpDateYear.of("21 Aug 2026", TODAY)).isEqualTo(2026);
		assertThat(LegacyPhpDateYear.of("Aug 21 2026", TODAY)).isEqualTo(2026);
		assertThat(LegacyPhpDateYear.of("21 August 2026", TODAY)).isEqualTo(2026);
		assertThat(LegacyPhpDateYear.of("August 21, 2026", TODAY)).isEqualTo(2026);
		assertThat(LegacyPhpDateYear.of("21 aug 2026", TODAY)).isEqualTo(2026);
		assertThat(LegacyPhpDateYear.of("AUG 21 2026", TODAY)).isEqualTo(2026);
		assertThat(LegacyPhpDateYear.of("Dec 31 2026", TODAY)).isEqualTo(2026);
		assertThat(LegacyPhpDateYear.of("Jan 1 2027", TODAY)).isEqualTo(2027);
	}

	@Test
	void aDayPastTheMonthsEndRollsForwardRatherThanFailing() {
		// Measured: 2026-02-30 resolves to 2026-03-02, so the year survives.
		assertThat(LegacyPhpDateYear.of("2026-02-30", TODAY)).isEqualTo(2026);
		assertThat(LegacyPhpDateYear.of("2026-11-31", TODAY)).isEqualTo(2026);
		assertThat(LegacyPhpDateYear.of("2026-12-31", TODAY)).isEqualTo(2026);
	}

	@Test
	void theFourRelativeKeywordsFollowTheLegacyClock() {
		assertThat(LegacyPhpDateYear.of("now", TODAY)).isEqualTo(2026);
		assertThat(LegacyPhpDateYear.of("today", TODAY)).isEqualTo(2026);
		assertThat(LegacyPhpDateYear.of("tomorrow", TODAY)).isEqualTo(2026);
		assertThat(LegacyPhpDateYear.of("yesterday", TODAY)).isEqualTo(2026);
		assertThat(LegacyPhpDateYear.of("TOMORROW", TODAY)).isEqualTo(2026);

		// The year boundary is what makes them worth testing: on 31 December
		// tomorrow is next year, and on 1 January yesterday is last year.
		assertThat(LegacyPhpDateYear.of("tomorrow", LocalDate.of(2026, 12, 31))).isEqualTo(2027);
		assertThat(LegacyPhpDateYear.of("today", LocalDate.of(2026, 12, 31))).isEqualTo(2026);
		assertThat(LegacyPhpDateYear.of("yesterday", LocalDate.of(2027, 1, 1))).isEqualTo(2026);
		assertThat(LegacyPhpDateYear.of("now", LocalDate.of(2027, 1, 1))).isEqualTo(2027);
	}

	@Test
	void aBareFourDigitValueIsATimeOfDayNotAYear() {
		// Measured: strtotime('2026') resolves to 20:26 *today*, so the year is
		// today's, not 2026. Surprising, and exactly why it is tested.
		assertThat(LegacyPhpDateYear.of("2026", LocalDate.of(2030, 5, 4))).isEqualTo(2030);
		assertThat(LegacyPhpDateYear.of("0830", LocalDate.of(2030, 5, 4))).isEqualTo(2030);
		// Not a valid clock time, so not a valid value at all.
		assertThatThrownBy(() -> LegacyPhpDateYear.of("2599", TODAY))
				.isInstanceOf(LegacyPhpDateYear.LegacyPhpDateException.class);
	}

	@Test
	void themeasuredFailureFamilyThrowsPhpsTypeError() {
		// strtotime() returns false for each of these, and date('Y', false) is
		// a TypeError under strict_types=1 -- which is a rolled-back 500, not a
		// year of any kind.
		for (String rejected : java.util.List.of(
				"invalid text", "", "   ", "2026-13-45", "2026-13-01", "2026-12-32", "Array", "1")) {
			assertThatThrownBy(() -> LegacyPhpDateYear.of(rejected, TODAY))
					.describedAs("input %s", rejected)
					.isInstanceOf(LegacyPhpDateYear.LegacyPhpDateException.class)
					.hasMessage("date(): Argument #2 ($timestamp) must be of type ?int, false given");
		}
		assertThatThrownBy(() -> LegacyPhpDateYear.of(null, TODAY))
				.isInstanceOf(LegacyPhpDateYear.LegacyPhpDateException.class);
	}

	@Test
	void trailingGarbageAndMixedSeparatorsAreRejectedRatherThanTruncated() {
		// The whole value has to match: a string that merely starts like a date
		// is not one. Measured false in PHP for every case here.
		for (String rejected : java.util.List.of(
				"2026-08-21garbage", "2026-08-21 garbage", "2026-08-21extra 12:30:00",
				"2026-08/21", "2026/08-21", "2026-08-21 25:00:00", "21 Foo 2026")) {
			assertThatThrownBy(() -> LegacyPhpDateYear.of(rejected, TODAY))
					.describedAs("input %s", rejected)
					.isInstanceOf(LegacyPhpDateYear.LegacyPhpDateException.class);
		}
	}

}

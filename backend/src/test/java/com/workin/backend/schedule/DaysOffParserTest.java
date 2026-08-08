package com.workin.backend.schedule;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.DayOfWeek;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Token map ported verbatim from schedule_helper.php @ d113204,
 * including both hamza spellings for Sunday/Monday/Wednesday.
 */
class DaysOffParserTest {

	@Test
	void everyLegacyTokenResolvesToItsDay() {
		assertThat(DaysOffParser.parseDaysOff("Fri")).containsExactly(DayOfWeek.FRIDAY);
		assertThat(DaysOffParser.parseDaysOff("friday")).containsExactly(DayOfWeek.FRIDAY);
		assertThat(DaysOffParser.parseDaysOff("الجمعة")).containsExactly(DayOfWeek.FRIDAY);
		assertThat(DaysOffParser.parseDaysOff("الأحد")).containsExactly(DayOfWeek.SUNDAY);
		assertThat(DaysOffParser.parseDaysOff("الاحد")).containsExactly(DayOfWeek.SUNDAY);
		assertThat(DaysOffParser.parseDaysOff("الإثنين")).containsExactly(DayOfWeek.MONDAY);
		assertThat(DaysOffParser.parseDaysOff("الاثنين")).containsExactly(DayOfWeek.MONDAY);
		assertThat(DaysOffParser.parseDaysOff("الثلاثاء")).containsExactly(DayOfWeek.TUESDAY);
		assertThat(DaysOffParser.parseDaysOff("الأربعاء")).containsExactly(DayOfWeek.WEDNESDAY);
		assertThat(DaysOffParser.parseDaysOff("الاربعاء")).containsExactly(DayOfWeek.WEDNESDAY);
		assertThat(DaysOffParser.parseDaysOff("الخميس")).containsExactly(DayOfWeek.THURSDAY);
		assertThat(DaysOffParser.parseDaysOff("السبت")).containsExactly(DayOfWeek.SATURDAY);
		assertThat(DaysOffParser.parseDaysOff("Sun,Mon,Tue,Wed,Thu"))
				.containsExactlyInAnyOrder(DayOfWeek.SUNDAY, DayOfWeek.MONDAY, DayOfWeek.TUESDAY,
						DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY);
	}

	@Test
	void splitsOnLatinAndArabicCommaAndSemicolon() {
		assertThat(DaysOffParser.parseDaysOff("Fri،Sat;Sun"))
				.containsExactlyInAnyOrder(DayOfWeek.FRIDAY, DayOfWeek.SATURDAY, DayOfWeek.SUNDAY);
	}

	@Test
	void blankUnknownAndEmptyTokensAreIgnored() {
		assertThat(DaysOffParser.parseDaysOff(null)).isEmpty();
		assertThat(DaysOffParser.parseDaysOff("  ")).isEmpty();
		assertThat(DaysOffParser.parseDaysOff("Fri,,notaday, ")).containsExactly(DayOfWeek.FRIDAY);
	}

	@Test
	void shiftDaysOffDoesNotAcceptNumericTokens() {
		// Legacy schedule_parse_days_off_to_dows has no is_numeric branch.
		assertThat(DaysOffParser.parseDaysOff("5")).isEmpty();
	}

	@Test
	void companyValuesAcceptNamesAndLegacyNumericIndexes() {
		assertThat(DaysOffParser.parseCompanyRestDays(List.of("friday", "6")))
				.containsExactlyInAnyOrder(DayOfWeek.FRIDAY, DayOfWeek.SATURDAY);
		assertThat(DaysOffParser.parseCompanyRestDays(List.of("0"))).containsExactly(DayOfWeek.SUNDAY);
		// Out-of-range numerics never match any real day in legacy
		// (format('w') is 0-6); dropping them is the equivalent behavior.
		assertThat(DaysOffParser.parseCompanyRestDays(List.of("9"))).isEmpty();
		assertThat(DaysOffParser.parseCompanyRestDays(List.of())).isEmpty();
	}

	@Test
	void companyValuesWithOversizedDigitTokensAreDroppedNotThrown() {
		// An all-digit token longer than Integer can hold (weekly_off_days
		// is VARCHAR(60), so storable) must not throw
		// NumberFormatException -- legacy PHP's (int) cast never throws.
		assertThat(DaysOffParser.parseCompanyRestDays(List.of("99999999999"))).isEmpty();
	}

	@Test
	void legacyIndexAndLabelConversions() {
		assertThat(DaysOffParser.toLegacyIndex(DayOfWeek.SUNDAY)).isZero();
		assertThat(DaysOffParser.toLegacyIndex(DayOfWeek.SATURDAY)).isEqualTo(6);
		assertThat(DaysOffParser.englishLabel(DayOfWeek.SUNDAY)).isEqualTo("Sunday");
	}

}
